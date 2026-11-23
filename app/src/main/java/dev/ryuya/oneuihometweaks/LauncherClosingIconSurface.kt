package dev.ryuya.oneuihometweaks

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.hardware.DataSpace
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.view.AttachedSurfaceControl
import android.view.Display
import android.view.Gravity
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.LauncherHiddenIconViews.Transition.CLOSING
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in experiment that hands one stock icon bitmap to SurfaceFlinger. */
@RequiresApi(34)
internal class LauncherClosingIconSurface {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "HomeClosingIconUpload") }
    private val uploadInFlight = AtomicBoolean()
    private val geometry = Matrix()
    private val matrix = FloatArray(9)
    private val windowOrigin = IntArray(2)
    private val visible = Rect()
    private var projectionTrace: LauncherIconProjectionTrace? = null
    private var closingSpring: LauncherSpringReplayProbe? = null
    private var hiddenViews: LauncherHiddenIconViews? = null
    private var openingIdle: (() -> Boolean)? = null
    private var directFrames: LauncherDirectClosingFrames? = null
    private var session: Session? = null
    private var lastAttempt = WeakReference<Any>(null)
    private var lastDeferredPreparation = WeakReference<Any>(null)
    @Volatile private var retired = false

    private fun installHooks(loader: ClassLoader) {
        val player = DexSymbols.findClass("com.honeyspace.transition.anim.floating.PlayerImpl", loader)
        val setupData = DexSymbols.findClass("com.honeyspace.transition.anim.floating.entity.SetupData", loader)
        val setup = player.compatibleDeclaredMethod("setupViews", setupData, View::class.java)
        val apply = player.compatibleDeclaredMethod("apply", Float::class.javaPrimitiveType)
        val reset = player.compatibleDeclaredMethod("reset")
        val container = player.compatibleDeclaredMethod("getContainerView").apply { isAccessible = true }
        val isOpening = setupData.compatibleDeclaredMethod("isForward").apply { isAccessible = true }
        val playerOpening = player.compatibleDeclaredMethod("isForward").apply { isAccessible = true }
        val direct = if (BuildConfig.LAUNCHER_DIRECT_CLOSING_SURFACES) LauncherDirectClosingFrames(loader, main) else null
        directFrames = direct
        if (BuildConfig.LAUNCHER_CLOSING_ICON_PROJECTION_TRACE || BuildConfig.LAUNCHER_DETACHED_CLOSING_SPRING ||
            BuildConfig.LAUNCHER_SKIP_DETACHED_WINDOW_UPDATES
        ) {
            try {
                projectionTrace = LauncherIconProjectionTrace(player)
            } catch (failure: Exception) {
                HookEntry.log("LAUNCHER_ICON_PROJECTION_UNAVAILABLE ${failure.javaClass.simpleName}")
            }
        }
        if (direct != null && projectionTrace != null && BuildConfig.LAUNCHER_DETACHED_CLOSING_SPRING &&
            Build.VERSION.SDK_INT >= 35
        ) {
            try {
                closingSpring = LauncherSpringReplayProbe.forClosingSurfaces(loader) { owner ->
                    if (session?.owner === owner) finish()
                }.also { spring ->
                    if (BuildConfig.LAUNCHER_EARLY_CLOSING_DRIVE) spring.earlyTarget = { player -> earlyTarget(player) }
                }
            } catch (failure: Exception) {
                HookEntry.log("LAUNCHER_DETACHED_CLOSING_UNAVAILABLE ${failure.javaClass.simpleName}")
            }
        }
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            direct?.install(handles)
            val targetlessApply = if (Build.VERSION.SDK_INT >= 35 && direct != null && projectionTrace != null) {
                closingSpring?.let { spring ->
                    LauncherStockWindowUpdater.readDuringApply(player, loader, checkNotNull(projectionTrace)::singleAppSurface)
                        ?.let { readApp -> spring.targetlessApply(direct, readApp) }
                }
            } else null
            val omitOpening = BuildConfig.LAUNCHER_DETACHED_OPENING && Build.VERSION.SDK_INT >= 35
            val omitClosing = BuildConfig.LAUNCHER_SKIP_DETACHED_WINDOW_UPDATES && projectionTrace != null
            if (direct != null && (omitOpening || omitClosing || (closingSpring != null && targetlessApply == null))) {
                val targetless = if (Build.VERSION.SDK_INT >= 35 && targetlessApply == null) {
                    closingSpring?.targetlessWindowUpdate(direct)
                } else null
                LauncherStockWindowUpdater.install(player, direct, { owner -> projectionTrace?.singleAppSurface(owner) },
                    targetless, omitStockUpdate = omitClosing, omitOpeningStockUpdate = omitOpening)?.let(handles::add)
            }
            if (direct != null && (BuildConfig.LAUNCHER_SKIP_HIDDEN_OPENING_VIEWS ||
                    BuildConfig.LAUNCHER_SKIP_TRANSPARENT_CLOSING_BACKGROUND)
            ) {
                val installed = mutableListOf<ModernHook.Handle>()
                try {
                    hiddenViews = LauncherHiddenIconViews(player).also { it.install(apply, installed) }
                    handles += installed
                } catch (failure: Exception) {
                    installed.asReversed().forEach { it.unhook() }
                    HookEntry.log("LAUNCHER_HIDDEN_ICON_VIEW_OPTIMIZATION_UNAVAILABLE ${failure.javaClass.simpleName}")
                }
            }
            handles += ModernHook.aroundMethod(setup) { chain ->
                val owner = chain.thisObject
                if (!retired && owner != null && onMain() && session != null) finish()
                val result = chain.proceed()
                if (!retired && owner != null && onMain()) {
                    if (isOpening.invoke(chain.args[0]) != true) {
                        lastAttempt.clear()
                        lastDeferredPreparation.clear()
                        prepare(owner, container.invoke(owner) as? ViewGroup)
                    }
                }
                result
            }
            handles += ModernHook.aroundMethod(apply) { chain ->
                val owner = chain.thisObject
                if (!onMain() || owner == null || retired) return@aroundMethod chain.proceed()
                session?.takeIf { it.owner === owner }?.let(::acceptPreparedImage)
                var observed = session
                if (observed?.owner === owner && observed.handoff?.detached == true &&
                    (!observed.front.isAttachedToWindow ||
                        (observed.root != null && observed.front.rootView.rootSurfaceControl !== observed.root) ||
                        playerOpening.invoke(owner) == true)
                ) {
                    finish()
                    observed = null
                }
                if (direct != null && observed?.owner === owner && observed.surface != null &&
                    ((!observed.attachedCommitted && !observed.transparentReady) ||
                        observed.lastSynchronizedFrame != observed.lastSynchronizedCommit)
                ) mark("OneUI closing direct waits for icon commit")
                val invisibleApp = if (BuildConfig.LAUNCHER_EARLY_TRANSPARENT_CLOSING_FRAMES &&
                    observed?.owner === owner
                ) readAppWhileIconInvisible(observed) else null
                val scope = if (direct != null && observed?.owner === owner &&
                    observed.front.isAttachedToWindow && playerOpening.invoke(owner) != true
                ) {
                    val handoff = observed.handoff ?: direct.Handoff {
                        if (session === observed) finish()
                    }.also { observed.handoff = it }
                    val planeReady = observed.transparentReady ||
                        (closingSpring != null && observed.attachedCommitted && observed.suppressed)
                    val ready = planeReady && observed.lastSynchronizedFrame == observed.lastSynchronizedCommit
                    val delayedVsync = if (Build.VERSION.SDK_INT >= 35) closingSpring?.delayedStockVsync(owner) ?: 0L else 0L
                    direct.begin(observed.front.rootView, handoff, ready, delayedVsync, player = owner,
                        invisibleClosingIcon = invisibleApp != null)
                } else null
                val result = try {
                    runStockFrame(owner, observed, scope) {
                        val frame = {
                            if (observed == null && targetlessApply != null) {
                                targetlessApply(owner) { chain.proceed() }
                            } else chain.proceed()
                        }
                        if (direct == null) frame() else direct.duringPlayerFrame(frame)
                    }
                } catch (error: Throwable) {
                    direct?.end(scope)?.restoreStock()
                    if (observed?.backgroundNeedsRestore == true && session === observed) finish()
                    throw error
                }
                val pending = direct?.end(scope)
                try {
                    val active = session
                    if (active?.owner === owner) {
                        if (pending?.invisibleClosingBaseline == true) {
                            if (active === observed && invisibleApp != null &&
                                readAppWhileIconInvisible(active) === invisibleApp
                            ) pending.commitInvisibleClosingBaseline()
                            pending.restoreStock()
                            render(active, null)
                        } else render(active, pending)
                    } else if (active == null && lastAttempt.get() !== owner && playerOpening.invoke(owner) != true) {
                        prepare(owner, container.invoke(owner) as? ViewGroup)
                    }
                } finally {
                    pending?.discardIfEarly()
                    pending?.restoreStock()
                }
                result
            }
            handles += ModernHook.aroundMethod(reset) { chain ->
                if (!onMain()) return@aroundMethod chain.proceed()
                val active = session
                if (active != null && active.owner === chain.thisObject) {
                    restoreBackground(active)
                    active.handoff?.fallBack()
                }
                try {
                    val views = hiddenViews
                    if (views == null) chain.proceed() else views.runFrame(null, CLOSING) { chain.proceed() }
                } finally {
                    if (session?.owner === chain.thisObject) finish()
                    if (lastAttempt.get() === chain.thisObject) lastAttempt.clear()
                    if (lastDeferredPreparation.get() === chain.thisObject) lastDeferredPreparation.clear()
                }
            }
            if (direct != null && BuildConfig.LAUNCHER_DETACHED_OPENING && Build.VERSION.SDK_INT >= 35) {
                openingIdle = LauncherOpeningReplayProbe.installForSurfaces(loader, direct,
                    hiddenViews.takeIf { BuildConfig.LAUNCHER_SKIP_HIDDEN_OPENING_VIEWS })
            }
            ModernHook.onGenerationRetired {
                if (onMain()) retire() else main.post(::retire)
            }
        } catch (error: Exception) {
            handles.asReversed().forEach { it.unhook() }
            retire()
            throw error
        }
        HookEntry.log("LAUNCHER_CLOSING_ICON_SURFACE_READY")
    }

    private fun onMain() = Looper.myLooper() === main.looper

    private fun readAppWhileIconInvisible(target: Session): SurfaceControl? {
        if (!invisibleFloatingIcon(target)) return null
        return try {
            projectionTrace?.singleAppSurface(target.owner)
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun invisibleFloatingIcon(target: Session): Boolean {
        val group = target.group
        val front = target.front
        if (!front.isAttachedToWindow || group.display?.displayId != Display.DEFAULT_DISPLAY ||
            group.childCount != 2 || group.getChildAt(1) !== front || front.alpha != 0f ||
            front.transitionAlpha != 1f || front.background !== target.drawable ||
            group.background != null || group.foreground != null || target.surface != null
        ) return false
        val back = group.getChildAt(0)
        val background = back.background
        val plainBackground = back.javaClass == View::class.java ||
            back.javaClass == ImageView::class.java && (back as ImageView).drawable == null
        return plainBackground && back.foreground == null &&
            (background == null || background is ColorDrawable && background.alpha == 0)
    }

    private fun runStockFrame(owner: Any, observed: Session?, scope: LauncherDirectClosingFrames.Scope?,
        stock: () -> Any?): Any? {
        val views = hiddenViews ?: return stock()
        val surface = observed?.surface
        val skipBackground = BuildConfig.LAUNCHER_SKIP_TRANSPARENT_CLOSING_BACKGROUND &&
            Build.VERSION.SDK_INT >= 35 && observed != null && observed.owner === owner && surface != null &&
            scope?.handoff?.detached == true && observed.suppressed &&
            views.ownsIcon(owner, observed.group) && views.hasTransparentBackground(observed.group) &&
            closingSpring?.owns(owner, projectionTrace?.singleAppSurface(owner), surface, requireSubmitted = true) == true
        if (skipBackground) observed.backgroundNeedsRestore = true
        return views.runFrame(owner.takeIf { skipBackground }, CLOSING, stock)
    }

    private fun restoreBackground(target: Session) {
        if (!target.backgroundNeedsRestore) return
        target.backgroundNeedsRestore = false
        try {
            if (target.front.rootView.rootSurfaceControl === target.root) {
                hiddenViews?.restore(target.owner, target.group, CLOSING)
            }
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_CLOSE_VIEW_RESTORE_FAILED ${failure.javaClass.simpleName}")
        }
    }

    private fun prepare(owner: Any, group: ViewGroup?) {
        val front = group?.getChildAt(1)
        val drawable = front?.background as? BitmapDrawable
        val bitmap = drawable?.bitmap
        if (group == null || front == null || drawable == null || bitmap == null || !compatible(drawable, bitmap)) {
            if (Trace.isEnabled() && lastDeferredPreparation.get() !== owner) {
                lastDeferredPreparation = WeakReference(owner)
                mark(when {
                    group == null -> "OneUI closing waiting for icon container"
                    front == null -> "OneUI closing waiting for foreground view"
                    bitmap == null -> "OneUI closing waiting for foreground bitmap"
                    else -> "OneUI closing waiting for compatible foreground bitmap"
                })
            }
            return
        }
        if (!uploadInFlight.compareAndSet(false, true)) return
        // setupViews can run before the stock foreground is assigned. Consume the attempt only when a bitmap is queued.
        lastAttempt = WeakReference(owner)
        val next = Session(owner, group, front, drawable, bitmap)
        session = next
        mark("OneUI closing bitmap preparing ${bitmap.width}x${bitmap.height}")
        main.postDelayed(next.expire, 2000)
        try {
            worker.execute {
                try {
                    LauncherIconBuffer.render(bitmap, worker, "One UI closing icon") { uploaded ->
                        completeUpload(next, uploaded)
                    }
                } catch (error: RuntimeException) {
                    completeUpload(next, null)
                    HookEntry.log("LAUNCHER_CLOSING_ICON_UPLOAD_ERROR ${error.javaClass.simpleName}")
                }
            }
        } catch (_: RuntimeException) {
            uploadInFlight.set(false)
            finish()
        }
    }

    private fun compatible(drawable: BitmapDrawable, bitmap: Bitmap): Boolean =
        !bitmap.isRecycled && bitmap.width in 1..512 && bitmap.height in 1..512 &&
            (bitmap.colorSpace == null || bitmap.colorSpace?.isSrgb == true) &&
            drawable.gravity == Gravity.FILL && drawable.alpha == 255 && drawable.colorFilter == null &&
            !drawable.isAutoMirrored && drawable.tileModeX == null && drawable.tileModeY == null

    private fun completeUpload(target: Session, uploaded: LauncherIconBuffer?) {
        if (uploaded != null && target.publishImage(uploaded)) {
            mark("OneUI closing bitmap published by worker")
            if (!main.post { acceptPreparedImage(target) }) target.cancelImagePreparation()?.close()
        } else {
            uploaded?.close()
        }
        uploadInFlight.set(false)
        if (retired) worker.shutdown()
    }

    private fun acceptPreparedImage(target: Session) {
        if (session !== target || retired) return
        val image = target.takeImage() ?: return
        target.uploaded = image
        mark("OneUI closing bitmap available")
        if (BuildConfig.LAUNCHER_DIRECT_CLOSING_SURFACES && target.front.alpha == 0f) render(target, null)
    }

    private fun render(target: Session, pending: LauncherDirectClosingFrames.Pending?) {
        if (target.handoff?.let { it.hasDetached && it.disabled } == true) {
            finish()
            return
        }
        val group = target.group
        val front = target.front
        val upload = target.uploaded ?: return
        if (!upload.ready) return
        val back = group.getChildAt(0)?.background
        if (!front.isAttachedToWindow || group.display?.displayId != Display.DEFAULT_DISPLAY ||
            group.childCount != 2 || group.clipToOutline || front.clipToOutline || group.alpha != 1f ||
            (back != null && !(back is ColorDrawable && back.alpha == 0)) ||
            front.background !== target.drawable || target.drawable.bitmap !== target.bitmap ||
            target.bitmap.generationId != target.generation ||
            !compatible(target.drawable, target.bitmap)
        ) {
            if (!target.reportedGroupRejection) {
                target.reportedGroupRejection = true
                mark("OneUI closing bitmap unsupported group")
            }
            if (target.surface != null) finish()
            return
        }
        val window = front.rootView
        val root = window.rootSurfaceControl ?: return
        if (target.root != null && target.root !== root) {
            finish()
            return
        }
        if (target.handoff?.early == true) {
            renderEarly(target, window, root, upload)
            return
        }
        if (target.transparentAttachPending) return
        if (target.surface == null && front.alpha == 0f && front.transitionAlpha == 1f &&
            BuildConfig.LAUNCHER_DIRECT_CLOSING_SURFACES
        ) {
            stageTransparentPlane(target, root, upload)
            return
        }
        if (target.surface == null && (front.alpha <= 0f || front.transitionAlpha != 1f)) return
        if (!readGeometry(window, front)) {
            if (!target.reportedGeometryRejection) {
                target.reportedGeometryRejection = true
                mark("OneUI closing bitmap unsupported geometry")
            }
            if (target.surface != null) finish()
            return
        }
        try {
            val first = target.surface == null
            val surface = target.surface ?: target.preparePlane()
            if (pending?.detachedStock == true) {
                val app = projectionTrace?.singleAppSurface(target.owner)
                if (Build.VERSION.SDK_INT >= 35 && closingSpring?.owns(target.owner, app, surface) == true) {
                    pending.discardStock()
                } else finish()
                return
            }
            val position = LauncherIconPlaneProjection.Position(matrix[Matrix.MTRANS_X], matrix[Matrix.MTRANS_Y],
                matrix[Matrix.MSCALE_X] * front.width / upload.buffer.width,
                matrix[Matrix.MSCALE_Y] * front.height / upload.buffer.height, front.alpha)
            compareProjection(target, upload, surface, position)
            if (!first && pending != null) {
                val spring = closingSpring
                val projection = target.projection
                if (Build.VERSION.SDK_INT >= 35 && spring != null && projection != null) {
                    projectionTrace?.singleAppSurface(target.owner)?.let { app ->
                        spring.offer(target.owner, pending, app, surface, projection, position)
                    }
                }
                pending.commit { transaction ->
                    LauncherSurfaceFrameWriter.writeIcon(transaction, surface, position)
                }
                return
            }
            SurfaceControl.Transaction().use { transaction ->
                if (first) {
                    if (!attachIcon(transaction, root, surface, upload)) {
                        mark("OneUI closing icon reparent unavailable")
                        return
                    }
                    target.ownPlane(surface, root)
                }
                val synchronizedFrame = ++target.lastSynchronizedFrame
                transaction.addTransactionCommittedListener(Executor { action -> main.post(action) }) {
                    if (session === target) {
                        target.attachedCommitted = true
                        target.lastSynchronizedCommit = maxOf(target.lastSynchronizedCommit, synchronizedFrame)
                    }
                    if (first) mark("OneUI closing bitmap attached")
                }
                LauncherSurfaceFrameWriter.writeIcon(transaction, surface, position)
                if (!root.applyTransactionOnDraw(transaction)) {
                    mark("OneUI closing icon draw rejected")
                    finish()
                    return
                }
            }
            front.transitionAlpha = 0f
            target.suppressed = true
            mark("OneUI closing bitmap frame")
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_CLOSING_ICON_RENDER_ERROR ${error.javaClass.simpleName}")
            finish()
        }
    }

    /** The worker has driven the app since release. Attach the plane hidden, then hand it over with a verified projection. */
    private fun renderEarly(target: Session, window: View, root: AttachedSurfaceControl, upload: LauncherIconBuffer) {
        val spring = closingSpring ?: return
        val trace = projectionTrace ?: return
        if (Build.VERSION.SDK_INT < 35 || !spring.isEarly(target.owner)) return
        try {
            if (target.surface == null) {
                val plane = target.preparePlane()
                SurfaceControl.Transaction().use { transaction ->
                    if (!attachIcon(transaction, root, plane, upload)) {
                        if (!target.reportedEarlyParent) {
                            target.reportedEarlyParent = true
                            mark("OneUI closing early plane waits for Home window")
                        }
                        return
                    }
                    target.ownPlane(plane, root)
                    transaction.setAlpha(plane, 0f)
                    transaction.apply()
                }
                // The stock view would draw on the delayed stock clock. The plane replaces it from this frame.
                target.front.transitionAlpha = 0f
                target.suppressed = true
                mark("OneUI closing early plane attached")
            }
            val surface = checkNotNull(target.surface)
            spring.earlyStockFrame(target.owner)
            val front = target.front
            // Only the transform is compared here. The stock pose on its delayed clock may still extend past the display.
            if (!readGeometry(window, front, requireVisible = false)) return
            val position = LauncherIconPlaneProjection.Position(matrix[Matrix.MTRANS_X], matrix[Matrix.MTRANS_Y],
                matrix[Matrix.MSCALE_X] * front.width / upload.buffer.width,
                matrix[Matrix.MSCALE_Y] * front.height / upload.buffer.height, front.alpha)
            var model = target.projection
            if (model == null || !trace.matches(target.owner, model, position)) {
                if (model != null) mark("OneUI closing early projection differed")
                model = trace.capture(target.owner, target.group, front, upload.buffer.width, upload.buffer.height)
                    ?.takeIf { trace.matches(target.owner, it, position) }
                target.projection = model
                target.projectionAttempted = true
                if (model == null) {
                    mark("OneUI closing early projection unverified")
                    return
                }
                mark("OneUI closing early projection verified")
            }
            if (target.publishedProjection !== model && spring.attachEarlyIcon(target.owner, surface, model, position)) {
                target.publishedProjection = model
            }
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_CLOSING_EARLY_ICON_ERROR ${error.javaClass.simpleName}")
            finish()
        }
    }

    /** Main thread, inside the stock spring start. No stock frame of this close has been scheduled yet. */
    @RequiresApi(35)
    private fun earlyTarget(player: Any): LauncherSpringReplayProbe.EarlyTarget? {
        val active = session?.takeIf { it.owner === player } ?: return null
        val direct = directFrames ?: return null
        if (retired || !active.front.isAttachedToWindow || active.surface != null || active.handoff?.owner != null) return null
        val app = projectionTrace?.singleAppSurface(player) ?: return null
        val handoff = active.handoff ?: direct.Handoff {
            if (session === active) finish()
        }.also { active.handoff = it }
        return LauncherSpringReplayProbe.EarlyTarget(handoff, app, active.front.rootView)
    }

    private fun compareProjection(target: Session, upload: LauncherIconBuffer, surface: SurfaceControl,
        position: LauncherIconPlaneProjection.Position) {
        val trace = projectionTrace ?: return
        if (closingSpring == null && !Trace.isEnabled()) return
        try {
            if (!target.projectionAttempted) {
                target.projectionAttempted = true
                target.projection = trace.capture(target.owner, target.group, target.front, upload.buffer.width, upload.buffer.height)
                mark(if (target.projection == null) "OneUI closing projection unsupported" else "OneUI closing projection captured")
            }
            val model = target.projection ?: return
            val projected = trace.compare(target.owner, model, position)
            if (projected != null && !target.pairChecked && target.attachedCommitted) {
                target.pairChecked = true
                mark(if (trace.checkPair(target.owner, surface, projected))
                    "OneUI closing projection pair encoded" else "OneUI closing projection pair unsupported")
            }
        } catch (failure: Exception) {
            target.projection = null
            HookEntry.log("LAUNCHER_ICON_PROJECTION_FAILED ${failure.javaClass.simpleName}")
        }
    }

    private fun stageTransparentPlane(target: Session, root: AttachedSurfaceControl, upload: LauncherIconBuffer) {
        try {
            val surface = target.preparePlane()
            SurfaceControl.Transaction().use { transaction ->
                if (!attachIcon(transaction, root, surface, upload)) {
                    mark("OneUI closing transparent plane reparent unavailable")
                    return
                }
                target.ownPlane(surface, root)
                target.transparentAttachPending = true
                transaction.setAlpha(surface, 0f)
                transaction.addTransactionCommittedListener(Executor { callback -> main.post(callback) }) {
                    if (session === target) {
                        target.transparentAttachPending = false
                        target.attachedCommitted = true
                        useTransparentPlane(target)
                    }
                }
                transaction.apply()
                if (BuildConfig.LAUNCHER_CLAIM_QUEUED_CLOSING_PAIR) {
                    // The hidden attachment and its first visible update enter the same apply queue.
                    target.transparentAttachPending = false
                    useTransparentPlane(target)
                    mark("OneUI closing transparent plane queued")
                }
            }
            mark("OneUI closing bitmap staged transparent")
        } catch (error: RuntimeException) {
            HookEntry.log("LAUNCHER_CLOSING_ICON_STAGE_ERROR ${error.javaClass.simpleName}")
            finish()
        }
    }

    private fun useTransparentPlane(target: Session) {
        val front = target.front
        if (front.alpha != 0f || front.transitionAlpha != 1f) return
        front.transitionAlpha = 0f
        target.suppressed = true
        target.transparentReady = true
        mark("OneUI closing bitmap ready while transparent")
    }

    private fun attachIcon(transaction: SurfaceControl.Transaction, root: AttachedSurfaceControl,
        surface: SurfaceControl, upload: LauncherIconBuffer): Boolean {
        val parent = root.buildReparentTransaction(surface) ?: return false
        parent.use { transaction.merge(it) }
        transaction.setBuffer(surface, upload.buffer, upload.fence)
            .setDataSpace(surface, DataSpace.DATASPACE_SRGB)
            .setLayer(surface, 1)
            .setVisibility(surface, true)
        return true
    }

    private fun readGeometry(window: View, front: View, requireVisible: Boolean = true): Boolean {
        if (front.width <= 0 || front.height <= 0 || front.visibility != View.VISIBLE) return false
        geometry.reset()
        front.transformMatrixToGlobal(geometry)
        window.transformMatrixToLocal(geometry)
        window.getLocationInSurface(windowOrigin)
        geometry.postTranslate(windowOrigin[0].toFloat(), windowOrigin[1].toFloat())
        geometry.getValues(matrix)
        val scaleX = matrix[Matrix.MSCALE_X]
        val scaleY = matrix[Matrix.MSCALE_Y]
        return geometry.isAffine && matrix.all { it.isFinite() } && scaleX > 0f && scaleY > 0f &&
            matrix[Matrix.MSKEW_X] == 0f && matrix[Matrix.MSKEW_Y] == 0f && (!requireVisible ||
                front.getGlobalVisibleRect(visible) &&
                visible.width() + 1 >= scaleX * front.width && visible.height() + 1 >= scaleY * front.height)
    }

    private fun finish() {
        val old = session ?: return
        session = null
        old.cancelImagePreparation()?.close()
        old.releaseUnattachedPlane()
        restoreBackground(old)
        main.removeCallbacks(old.expire)
        if (Build.VERSION.SDK_INT >= 35) closingSpring?.stop(old.owner)
        val handoff = old.handoff
        if (handoff != null) handoff.fallBack { releaseIcon(old) } else releaseIcon(old)
    }

    private fun releaseIcon(old: Session) {
        val current = session
        if (old.suppressed && !(current?.front === old.front && current.suppressed)) old.front.transitionAlpha = 1f
        val surface = old.surface
        if (surface == null) {
            old.uploaded?.close()
            return
        }
        val release = Runnable {
            SurfaceControl.Transaction().use { transaction ->
                transaction.setVisibility(surface, false).setBuffer(surface, null).reparent(surface, null).apply()
            }
            surface.release()
            old.uploaded?.close()
            mark("OneUI closing bitmap released")
        }
        val root = old.root
        if (root == null || !old.front.isAttachedToWindow) {
            release.run()
            return
        }
        val expiry = Runnable { release.run() }
        SurfaceControl.Transaction().use { transaction ->
            transaction.setVisibility(surface, false)
            transaction.addTransactionCommittedListener(Executor { action -> main.post(action) }) {
                if (main.hasCallbacks(expiry)) {
                    main.removeCallbacks(expiry)
                    release.run()
                }
            }
            if (root.applyTransactionOnDraw(transaction)) main.postDelayed(expiry, 90) else release.run()
        }
    }

    private fun retire() {
        retired = true
        finish()
        if (!uploadInFlight.get()) worker.shutdown()
    }

    private inner class Session(val owner: Any, val group: ViewGroup, val front: View,
        val drawable: BitmapDrawable, val bitmap: Bitmap) {
        val generation = bitmap.generationId
        private var preparedImage: LauncherIconBuffer? = null
        private var imagePreparationCancelled = false

        @Synchronized
        fun publishImage(upload: LauncherIconBuffer): Boolean {
            if (imagePreparationCancelled) return false
            preparedImage = upload
            return true
        }

        @Synchronized
        fun takeImage(): LauncherIconBuffer? = preparedImage.also { preparedImage = null }

        @Synchronized
        fun cancelImagePreparation(): LauncherIconBuffer? {
            imagePreparationCancelled = true
            return takeImage()
        }

        var uploaded: LauncherIconBuffer? = null
        private var unattachedPlane: SurfaceControl? = null
        var surface: SurfaceControl? = null
            private set
        var root: AttachedSurfaceControl? = null
            private set

        fun preparePlane(): SurfaceControl = unattachedPlane ?: SurfaceControl.Builder()
            .setName("OneUI closing icon bitmap").setHidden(true).build().also { unattachedPlane = it }

        fun ownPlane(plane: SurfaceControl, parent: AttachedSurfaceControl) {
            check(plane === unattachedPlane)
            unattachedPlane = null
            surface = plane
            root = parent
        }

        fun releaseUnattachedPlane() {
            unattachedPlane?.release()
            unattachedPlane = null
        }

        var handoff: LauncherDirectClosingFrames.Handoff? = null
        var projectionAttempted = false
        var projection: LauncherIconPlaneProjection? = null
        var pairChecked = false
        var transparentAttachPending = false
        var transparentReady = false
        var attachedCommitted = false
        var lastSynchronizedFrame = 0
        var lastSynchronizedCommit = 0
        var suppressed = false
        var backgroundNeedsRestore = false
        var reportedGeometryRejection = false
        var reportedGroupRejection = false
        var reportedEarlyParent = false
        var publishedProjection: LauncherIconPlaneProjection? = null
        val expire = Runnable { if (session === this) finish() }
    }

    companion object {
        fun install(app: Application, loader: ClassLoader): (() -> Boolean)? {
            val sdk = Build.VERSION.SDK_INT
            if (sdk != 37 || !HookCompatibility.matchesClockBuild(sdk, Build.DISPLAY)) return null
            return try {
                @Suppress("DEPRECATION")
                val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
                if (!HookCompatibility.matchesLauncherVersion(sdk, version)) return null
                val surface = LauncherClosingIconSurface().also { it.installHooks(loader) }
                val idle = {
                    surface.session == null && surface.directFrames?.idleForHotReload() != false &&
                        surface.openingIdle?.invoke() != false
                }
                idle
            } catch (error: Exception) {
                HookEntry.log("LAUNCHER_CLOSING_ICON_SURFACE_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
                null
            }
        }

        private fun mark(name: String) {
            if (!Trace.isEnabled()) return
            Trace.beginSection(name)
            Trace.endSection()
        }
    }
}
