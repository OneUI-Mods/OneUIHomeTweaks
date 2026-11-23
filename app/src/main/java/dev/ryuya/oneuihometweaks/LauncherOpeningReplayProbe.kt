package dev.ryuya.oneuihometweaks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.Trace
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.LauncherHiddenIconViews.Transition.OPENING
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.reflect.InvocationTargetException
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Compares the stock AnimatorSet and transfers its opening surfaces once their frames agree. */
@RequiresApi(35)
internal class LauncherOpeningReplayProbe private constructor(
    loader: ClassLoader,
    private val direct: LauncherDirectClosingFrames?,
    private val hiddenStockViews: LauncherHiddenIconViews? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private val player = DexSymbols.findClass("com.honeyspace.transition.anim.floating.PlayerImpl", loader)
    private val input = DexSymbols.findClass("com.honeyspace.transition.anim.floating.entity.FloatingInputData", loader)
    private val geometry = LauncherWindowGeometry(player, input)
    private val replay = geometry.openingReplay(loader)
    private val start = player.compatibleDeclaredMethod("start", input, Boolean::class.javaPrimitiveType)
    private val apply = player.compatibleDeclaredMethod("apply", Float::class.javaPrimitiveType)
    private val isForward = input.compatibleDeclaredMethod("isForward")
    private val fromLock = input.compatibleDeclaredMethod("getStartFromLock")
    private val external = input.compatibleDeclaredMethod("getStartFromExtraDisplay")
    private val animator = input.compatibleDeclaredMethod("getAnimatorSet")
    private val context = player.compatibleDeclaredField("context").apply { isAccessible = true }
    private val container = player.compatibleDeclaredMethod("getContainerView")
    private val layouts = try {
        LauncherIconProjectionTrace(player)
    } catch (failure: Exception) {
        HookEntry.log("LAUNCHER_OPEN_ICON_LAYOUT_UNAVAILABLE ${failure.javaClass.simpleName}")
        null
    }
    private val readOpeningApplier = if (direct != null && layouts != null) {
        LauncherStockWindowUpdater.readOpeningApplier(player, loader)
    } else null
    private val viewTransform = Matrix()
    private val viewValues = FloatArray(9)
    private val rootOrigin = IntArray(2)
    private val viewOutline = Outline()
    private val outlineBounds = Rect()
    private val preparation = if (direct == null) null else LauncherOpeningPreparation(main)
    private val surfaceWriter = if (direct != null) LauncherOpeningSurfaceWriter() else null
    private var prepared: LauncherOpeningIconSurface? = null
    private val warmExpiry = Runnable {
        prepared?.close()
        prepared = null
    }
    private val frameTime = Choreographer::class.java.compatibleDeclaredMethod("getFrameTimeNanos")
        .apply { isAccessible = true }
    private val stockTrace = LauncherWindowFrameTrace("OneUI open stock window")
    private val replayTrace = LauncherWindowFrameTrace("OneUI open detached window")
    private var scope: LaunchScope? = null
    private var active: Session? = null
    private var worker: HandlerThread? = null
    private var executor: Handler? = null
    private var applyingStockFrame = false
    @Volatile private var retired = false
    private var nextId = 0L
    // Stock objects that received a module proxy; restored to the stock callback on retirement.
    private val stockSwaps = mutableListOf<StockReferenceSwap>()

    private fun installHooks() {
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            handles += ModernHook.aroundMethod(start) { chain ->
                val stockPlayer = chain.thisObject
                val stockInput = chain.args[0]
                if (!onMain() || retired || (direct == null && !Trace.isEnabled()) || stockPlayer == null || stockInput == null) {
                    return@aroundMethod chain.proceed()
                }
                val previous = scope
                val eligible = chain.args[1] == false && isForward.invoke(stockInput) == true &&
                    fromLock.invoke(stockInput) == false && external.invoke(stockInput) == false
                scope = if (eligible) LaunchScope(stockPlayer, stockInput) else null
                try {
                    chain.proceed()
                } finally {
                    scope = previous
                }
            }
            if (direct != null) installSurfaceHooks(checkNotNull(player.classLoader), handles)
            handles += ModernHook.aroundMethod(replay.play) { chain ->
                val launch = scope
                if (!onMain() || retired || launch == null || !ValueAnimator.areAnimatorsEnabled()) {
                    return@aroundMethod chain.proceed()
                }
                val reference = animator.invoke(launch.input) as? AnimatorSet
                val applicationContext = context.get(launch.owner) as? Context
                val snapshot = if (reference != null && applicationContext != null && reference.childAnimations.isEmpty()) {
                    try {
                        geometry.snapshot(launch.owner, launch.input)
                    } catch (failure: Exception) {
                        HookEntry.log("LAUNCHER_OPEN_REPLAY_SNAPSHOT_FAILED ${failure.javaClass.simpleName}")
                        null
                    }
                } else null
                if (snapshot == null || reference == null || applicationContext == null) return@aroundMethod chain.proceed()
                val callback = chain.args[0]?.let { original ->
                    try {
                        replay.wrapStockCallback(original) { stock -> runRegisteredFrame(launch.owner, reference, stock) }
                    } catch (failure: Exception) {
                        HookEntry.log("LAUNCHER_OPEN_CALLBACK_WRAPPER_UNAVAILABLE ${failure.javaClass.simpleName}")
                        null
                    }
                }
                try {
                    val result = if (callback == null) chain.proceed() else chain.proceed(arrayOf(callback.replacement))
                    val stockPlayer = chain.thisObject
                    val original = chain.args[0]
                    if (callback != null && stockPlayer != null && original != null) {
                        if (stockSwaps.size >= MAX_STOCK_SWAPS) stockSwaps.removeAt(0).restore()
                        stockSwaps += StockReferenceSwap.find(stockPlayer, callback.replacement, original,
                            stockPlayer.javaClass.classLoader)
                    }
                    observe(launch.owner, reference, applicationContext, snapshot, callback)
                    result
                } finally {
                    if (active?.stockCallback !== callback) callback?.close()
                }
            }
            handles += ModernHook.aroundMethod(apply) { chain ->
                interceptStockApply(chain.thisObject) { chain.proceed() }
            }
            if (direct != null) installStockApplyCallers(handles)
            handles += ModernHook.aroundMethod(LauncherOpenValueReplay.Playback.pulse) { chain ->
                val session = active
                if (session == null || chain.thisObject !== session.reference || !onMain() ||
                    session.reference.isPaused || session.cancelled.get()
                ) return@aroundMethod chain.proceed()
                val tick = if (session.driving == null) {
                    currentFrameTime()?.let { LauncherOpenValueReplay.Tick(it, chain.args[0] as Long) }
                } else null
                if (!session.reportedPulse) {
                    session.reportedPulse = true
                    mark(if (tick == null) "OneUI open stock pulse missing frame time" else "OneUI open stock pulse observed")
                }
                if (session.firstTick == null) session.firstTick = tick
                if (session.stockCallback != null) return@aroundMethod chain.proceed()
                val owner = session.owner.get()
                val pulseApplier = if (owner != null && session.started && !session.explicitStockApplyObserved) {
                    findStockApplier(owner, session)
                } else null
                val result = if (pulseApplier != null) {
                    interceptStockApply(owner, pulseApplier = pulseApplier) { chain.proceed() }
                } else if (session.driving != null && !session.explicitStockApplyObserved) {
                    stockAfterFinish(session) { chain.proceed() }
                } else chain.proceed()
                if (active === session && session.started && session.driving == null && tick != null) {
                    if (Trace.isEnabled() && owner != null && session.lastStockSample != tick.nanos) {
                        // This observes a native frame without taking ownership of its transaction.
                        try {
                            geometry.readStock(owner)?.let { record(stockTrace, session, it, tick.nanos) }
                        } catch (failure: Exception) {
                            session.note("stock pulse sample unavailable ${failure.javaClass.simpleName}")
                        }
                    }
                    queueStockTick(session, tick)
                }
                result
            }
            ModernHook.onGenerationRetired { if (onMain()) retire() else main.post(::retire) }
        } catch (failure: Exception) {
            handles.asReversed().forEach { it.unhook() }
            retire()
            throw failure
        }
        HookEntry.log("LAUNCHER_OPEN_REPLAY_READY")
    }

    private fun runRegisteredFrame(owner: Any, reference: AnimatorSet, stock: () -> Any?): Any? {
        val session = active ?: return stock()
        if (!onMain() || session.owner.get() !== owner || session.reference !== reference || !session.started ||
            session.cancelled.get() || reference.isPaused) return stock()
        session.note("stock registered callback invoked")
        val tick = try {
            currentFrameTime()?.let { replay.stockTick(reference, it) }?.also {
                if (session.firstTick == null) session.firstTick = replay.stockOrigin(reference, it)
            }
        } catch (failure: Exception) {
            session.note("stock callback clock rejected ${failure.javaClass.simpleName}")
            null
        }
        if (tick == null) {
            session.note("stock registered callback outside a matching frame")
            return if (session.driving == null) stock() else stockAfterFinish(session, stock)
        }
        if (session.firstTick == null) return stock()
        val result = interceptStockApply(owner, viaCallback = true, stock = stock)
        if (active === session && session.driving == null) queueStockTick(session, tick)
        return result
    }

    private fun queueStockTick(session: Session, tick: LauncherOpenValueReplay.Tick) {
        if (tick.nanos <= session.lastQueuedTickNanos) return
        val handler = executor ?: return
        val first = session.firstTick?.takeIf { session.lastQueuedTickNanos == 0L && it.nanos < tick.nanos }
        val icon = session.iconReference?.takeIf { it.frameNanos == tick.nanos }
        val pair = session.latestPair?.takeIf { it.icon === icon }
        session.lastQueuedTickNanos = tick.nanos
        handler.post {
            if (first != null) advance(session, first, null, null)
            advance(session, tick, icon, pair)
        }
    }

    private fun findStockApplier(owner: Any, session: Session): LauncherStockWindowUpdater.OpeningApplier? {
        val root = session.assets?.group?.rootView
        return try {
            val reader = readOpeningApplier ?: return null
            reader.read(owner, root, session::noteWriterRejection)
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_OPEN_APPLIER_READ_FAILED ${failure.javaClass.simpleName}")
            null
        }
    }

    private fun installStockApplyCallers(handles: MutableList<ModernHook.Handle>) {
        val loader = checkNotNull(player.classLoader)
        val installed = mutableListOf<ModernHook.Handle>()
        try {
            val callback = DexSymbols.findClass("${player.name}\$start\$1", loader)
            val receiver = DexSymbols.findClass("kotlin.jvm.internal.CallableReference", loader)
                .compatibleDeclaredMethod("getBoundReceiver").apply { isAccessible = true }
            val callbacks = listOf(Float::class.javaPrimitiveType, Any::class.java).map { parameter ->
                callback.compatibleDeclaredMethod("invoke", parameter)
            }
            val access = player.compatibleDeclaredMethod("access\$apply", player, Float::class.javaPrimitiveType)
            for (method in callbacks) installed += ModernHook.aroundMethod(method) { chain ->
                if (!onMain() || applyingStockFrame) return@aroundMethod chain.proceed()
                val session = active?.takeIf { it.started } ?: return@aroundMethod chain.proceed()
                val callback = chain.thisObject ?: return@aroundMethod chain.proceed()
                val owner = receiver.invoke(callback)
                if (session.owner.get() !== owner) return@aroundMethod chain.proceed()
                interceptStockApply(owner, viaCaller = true) { chain.proceed() }
            }
            installed += ModernHook.aroundMethod(access) { chain ->
                if (!onMain() || applyingStockFrame) return@aroundMethod chain.proceed()
                val owner = chain.args[0] ?: return@aroundMethod chain.proceed()
                val session = active ?: return@aroundMethod chain.proceed()
                if (!session.started || session.owner.get() !== owner) return@aroundMethod chain.proceed()
                interceptStockApply(owner, viaCaller = true) { chain.proceed() }
            }
            handles += installed
        } catch (failure: Exception) {
            installed.asReversed().forEach { it.unhook() }
            HookEntry.log("LAUNCHER_OPEN_APPLY_CALLERS_UNAVAILABLE ${failure.javaClass.simpleName}")
        }
    }

    private fun interceptStockApply(owner: Any?, viaCaller: Boolean = false, viaCallback: Boolean = false,
        pulseApplier: LauncherStockWindowUpdater.OpeningApplier? = null, stock: () -> Any?): Any? {
        if (!onMain() || applyingStockFrame) return stock()
        applyingStockFrame = true
        try {
            return applyStockFrame(owner, viaCaller, viaCallback, pulseApplier, stock)
        } finally {
            applyingStockFrame = false
        }
    }

    private fun installSurfaceHooks(loader: ClassLoader, handles: MutableList<ModernHook.Handle>) {
        val setupType = DexSymbols.findClass("com.honeyspace.transition.anim.floating.entity.SetupData", loader)
        val setup = player.compatibleDeclaredMethod("setupViews", setupType, View::class.java)
        val opening = setupType.compatibleDeclaredMethod("isForward")
        handles += ModernHook.aroundMethod(setup) { chain ->
            if (!onMain() || retired) return@aroundMethod chain.proceed()
            active?.let(::finish)
            clearPrepared()
            val owner = chain.thisObject
            val workers = preparation
            if (owner == null || workers == null || opening.invoke(chain.args[0]) != true) {
                return@aroundMethod chain.proceed()
            }
            var assets: LauncherOpeningIconSurface? = null
            // These hidden layers do not depend on the bitmap or View produced by setupViews.
            val reserved = LauncherOpeningIconSurface.reserveLayers(workers::prepareLayers, main) {
                assets?.acceptLayers(it)
            }
            try {
                val result = chain.proceed()
                assets = prepareIcon(owner, reserved)
                prepared = assets
                main.postDelayed(warmExpiry, 3_500)
                result
            } finally {
                if (assets == null) reserved?.close()
            }
        }
        val applyBackground = player.declaredMethods.singleOrNull { method ->
            method.name == "setupBackgroundView\$lambda\$0\$1" && method.parameterCount == 4 &&
                method.parameterTypes[1] === View::class.java && method.parameterTypes[3] === player
        }
        if (applyBackground != null) handles += ModernHook.aroundMethod(applyBackground) { chain ->
            if (onMain()) active?.takeIf { it.owner.get() === chain.args[3] }?.handoff?.beginStockCapture()
            val result = chain.proceed()
            if (onMain() && !retired) {
                val owner = chain.args[3]
                val child = chain.args[1]
                if (owner != null && child is View) prepareUpdatedBackground(owner, child)
            }
            result
        }
        val targets = player.compatibleDeclaredMethod("getFinalRemoteTargets").returnType
        val apps = targets.compatibleDeclaredMethod("getUnfilteredApps").returnType
        val invalidations = listOf(
            player.compatibleDeclaredMethod("reset"),
            player.compatibleDeclaredMethod("pauseAnimation"),
            player.compatibleDeclaredMethod("cancelAnimation"),
            player.compatibleDeclaredMethod("endAnimation"),
            player.compatibleDeclaredMethod("finish"),
            player.compatibleDeclaredMethod("targetsUpdate", targets, Int::class.javaPrimitiveType),
            player.compatibleDeclaredMethod("swapAppTargets", apps, Int::class.javaPrimitiveType),
        )
        for (method in invalidations) handles += ModernHook.aroundMethod(method) { chain ->
            if (!onMain()) return@aroundMethod chain.proceed()
            val owner = chain.thisObject
            if (prepared?.owner === owner) clearPrepared()
            val session = active?.takeIf { it.owner.get() === owner } ?: return@aroundMethod chain.proceed()
            stockAfterFinish(session) { chain.proceed() }
        }
    }

    private fun prepareUpdatedBackground(owner: Any, view: View) {
        val session = active?.takeIf { it.owner.get() === owner }
        val icon = (session?.assets ?: prepared?.takeIf { it.owner === owner }) ?: return
        if (icon.group.getChildAt(0) !== view) return
        try {
            if (!icon.refresh() && session != null && icon.layers != null) replaceIcon(session, owner, icon)
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_OPEN_BACKGROUND_PREPARE_FAILED ${failure.javaClass.simpleName}")
            if (session != null) finish(session) else clearPrepared()
        }
    }

    private fun applyStockFrame(owner: Any?, viaCaller: Boolean, viaCallback: Boolean,
        pulseApplier: LauncherStockWindowUpdater.OpeningApplier?,
        stock: () -> Any?): Any? {
        if (owner == null) return runStock(stock)
        val session = active?.takeIf { it.owner.get() === owner && it.started } ?: return runStock(stock)
        // A newer stock frame must revoke the previous offer before touching any View data.
        session.handoff?.beginStockCapture()
        if (pulseApplier == null) session.explicitStockApplyObserved = true
        if (!session.reportedStockApply) {
            session.reportedStockApply = true
            mark(when {
                pulseApplier != null -> "OneUI open stock apply observed through pulse"
                viaCallback -> "OneUI open stock apply observed through registered callback"
                viaCaller -> "OneUI open stock apply observed through caller"
                else -> "OneUI open stock apply observed"
            })
        }
        val icon = session.assets
        val intact = !session.restoringIcon && icon?.refresh() == true
        val stockWriter = pulseApplier ?: findStockApplier(owner, session)
        if (stockWriter == null && session.driving != null) return stockAfterFinish(session, stock)
        val stockWindow = stockWriter?.window
        if (stockWindow?.hasCompanion == true) session.note("stock writer has one animated target and one companion")
        if (icon?.layers != null) {
            if (session.handoff?.disabled == true) return stockAfterFinish(session, stock)
            if (!intact) {
                val previous = replaceIcon(session, owner, icon)
                return runStock {
                    if (previous != null) previous.duringTargetReplacement(stock) else stock()
                }
            }
        }
        val handoff = if (direct != null && icon != null && intact && icon.isAttached() && stockWriter != null) {
            session.handoff ?: direct.Handoff(opening = true) {
                if (active === session && session.assets === icon) finish(session)
            }.also { session.handoff = it }
        } else null
        var submittedWindow: LauncherWindowGeometry.Frame? = null
        val directScope = if (handoff != null && icon != null && stockWriter != null) {
            val settledWindow = stockWriter.window.takeIf { current ->
                val driving = session.driving
                // The committed two-target baseline already submitted the stock companion's fixed pose.
                driving != null && stockWriter.applier === driving.applier &&
                    current.sameOpeningTarget(driving.stockWindow)
            }
            direct?.begin(icon.group.rootView, handoff, icon.layers != null, player = owner,
                stockApplier = stockWriter.applier, openingWindow = settledWindow,
                bindOnStockFrame = icon.canBindOnStockFrame(), onStockSchedule = {
                    if (!handoff.detached) {
                        // The alpha animator can update outputData again before the enclosing pulse returns.
                        try {
                            submittedWindow = geometry.readStock(owner)
                        } catch (failure: Exception) {
                            session.note("stock schedule sample unavailable ${failure.javaClass.simpleName}")
                        }
                    }
                })
        } else null
        val result = try {
            val views = hiddenStockViews
            if (views != null && icon != null && directScope != null && handoff?.detached == true &&
                handoff.hasSubmittedDetachedFrame && icon.onDrawCommitted &&
                icon.group.transitionAlpha == 0f && views.ownsIcon(owner, icon.group)
            ) {
                if (icon.restoreStockGeometry == null) icon.restoreStockGeometry = { views.restore(owner, icon.group, OPENING) }
                views.runFrame(owner, OPENING, stock)
            } else runStock(stock)
        } catch (failure: Throwable) {
            direct?.end(directScope)?.restoreStock()
            finish(session)
            throw failure
        }
        val pending = direct?.end(directScope)
        try {
            if (active !== session) return result
            if (pending?.detachedStock == true) {
                val expected = session.driving
                if (expected == null || icon == null || stockWindow == null ||
                    stockWindow.app !== expected.app || stockWriter.applier !== expected.applier) {
                    finish(session)
                } else if (!icon.refresh()) {
                    replaceIcon(session, owner, icon)
                } else if (!pending.isOmitted && (stockWindow.hasCompanion || stockWindow.carriesMidpoint())) {
                    if (preserveStockWrites(pending, expected)) {
                        mark("OneUI opening preserved stock window writes")
                    } else finish(session)
                } else pending.discardStock()
                return result
            }
            val exact = submittedWindow
            if (exact != null) session.note("stock window sampled at schedule")
            else if (directScope != null) session.note("selected writer was not intercepted at schedule")
            if (directScope?.observedStockFrame == true && !directScope.ready) session.note("stock frame before icon bind")
            val window = exact ?: geometry.readStock(owner)
            if (window != null) record(stockTrace, session, window)
            session.iconReference = null
            if (icon == null || !intact) {
                if (icon == null) session.note("icon surface unavailable")
                return result
            }
            if (!icon.refresh()) {
                if (icon.layers != null) replaceIcon(session, owner, icon)
                return result
            }
            val reference = if (window == null) null else captureOpeningIcon(session, owner, icon, window)
            session.iconReference = reference
            if (reference == null) {
                session.note("stock icon reference unavailable")
                if (icon.layers != null) finish(session)
                return result
            }
            val rendered = icon.layers
            if (pending?.stockDraw == true) {
                if (exact == null || stockWriter == null ||
                    !pending.completeStockDraw { icon.bindOnStockFrame(it, reference.pose) }
                ) {
                    session.note("icon not ready for stock frame")
                } else {
                    // The native stock draw can count toward parity before independent submission is allowed.
                    session.latestPair = PairAnchor(reference, stockWriter.window, stockWriter.applier,
                        checkNotNull(icon.layers), icon.bindCommit, pending.handoff, pending.stockVsync)
                }
            } else if (pending != null && rendered != null) {
                if (stockWriter == null || exact == null) {
                    finish(session)
                    return result
                }
                val writer = checkNotNull(surfaceWriter)
                val anchor = PairAnchor(reference, stockWriter.window, stockWriter.applier, rendered,
                    icon.bindCommit, pending.handoff, pending.stockVsync)
                val handler = executor
                if (handler != null) {
                    anchor.offer = pending.offerToWorker(Executor { callback -> handler.post(callback) },
                        submitOpeningPairOnWorker = true) { _ ->
                        promote(session, anchor)
                    }
                }
                session.latestPair = anchor
                pending.commit { writer.writeIcon(it, rendered, reference.pose) }
            } else if (rendered != null && session.driving == null) finish(session)
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_OPEN_ICON_FRAME_FAILED ${failure.javaClass.simpleName}")
            finish(session)
        } finally {
            pending?.restoreStock()
        }
        return result
    }

    private fun preserveStockWrites(pending: LauncherDirectClosingFrames.Pending, anchor: PairAnchor): Boolean {
        val writer = surfaceWriter ?: return false
        return synchronized(anchor) {
            pending.commitWhileDetached { transaction ->
                val window = anchor.lastSubmittedWindow ?: anchor.icon.window
                if (!writer.writeWindow(transaction, anchor.app, window)) return@commitWhileDetached false
                transaction.setFrameTimeline(anchor.lastSubmittedVsync)
                true
            }
        }
    }

    private fun stockAfterFinish(session: Session, stock: () -> Any?): Any? {
        val handoff = session.handoff ?: session.restoreHandoff
        finish(session)
        return runStock { if (handoff != null) handoff.duringTargetReplacement(stock) else stock() }
    }

    private fun runStock(stock: () -> Any?): Any? {
        val views = hiddenStockViews ?: return stock()
        return views.runFrame(null, OPENING, stock)
    }

    private fun replaceIcon(
        session: Session,
        owner: Any,
        icon: LauncherOpeningIconSurface,
    ): LauncherDirectClosingFrames.Handoff? {
        val previous = session.handoff
        previous?.beginStockCapture()
        val group = container.invoke(owner) as? ViewGroup
        if (!icon.isAttached() || group !== icon.group) {
            finish(session)
            return previous
        }
        icon.restoreSuspendedGeometry()
        val next = icon.prepareReplacement()
        session.driving?.let { anchor ->
            anchor.revoked.set(true)
            executor?.post { session.playback?.stopDisplayDrive() }
        }
        session.driving = null
        session.iconReference = null
        session.latestPair = null
        session.assets = next
        session.handoff = null
        session.restoringIcon = true
        session.restoreHandoff = previous
        mark("OneUI open icon asset changed")
        val restore = {
            icon.close {
                if (active === session && session.assets === next) {
                    session.restoringIcon = false
                    session.restoreHandoff = null
                    mark("OneUI open icon ready for replacement")
                }
            }
        }
        if (previous == null) restore() else previous.fallBack(restore)
        return previous
    }

    private fun prepareIcon(owner: Any, reserved: LauncherOpeningIconSurface.LayerReservation? = null): LauncherOpeningIconSurface? {
        val workers = preparation ?: return null
        val group = container.invoke(owner) as? ViewGroup ?: return null
        val assets = LauncherOpeningIconSurface(owner, group, main, workers::prepareLayers, reserved, workers::upload)
        try {
            assets.refresh()
            return assets
        } catch (failure: Exception) {
            assets.close()
            throw failure
        }
    }

    private fun clearPrepared() {
        main.removeCallbacks(warmExpiry)
        prepared?.close()
        prepared = null
    }

    private fun observe(owner: Any, reference: AnimatorSet, context: Context, snapshot: LauncherWindowGeometry.Snapshot,
        stockCallback: LauncherOpenValueReplay.StockCallback?) {
        val children = reference.childAnimations.size
        if (children == 0) return
        active?.let(::finish)
        val warm = prepared?.takeIf { it.owner === owner }
        if (warm != null) {
            prepared = null
            main.removeCallbacks(warmExpiry)
        }
        val assets = if (direct == null || layouts == null) null else warm ?: prepareIcon(owner)
        if (assets == null && !Trace.isEnabled()) return
        val session = Session(++nextId, owner, reference, assets, stockCallback)
        active = session
        val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                if (active !== session || session.started || retired || !reference.isStarted || reference.isPaused) return
                if (reference.childAnimations.size != children) {
                    mark("OneUI open stock tracks changed")
                    finish(session)
                    return
                }
                if (session.firstTick == null && reference.currentPlayTime != 0L) {
                    mark("OneUI open stock begins at nonzero play time")
                    finish(session)
                    return
                }
                session.started = true
                mark("OneUI open stock started")
                val timing = LauncherOpenValueReplay.Timing(reference)
                startReplay(session, context, snapshot, timing, children)
            }

            override fun onAnimationCancel(animation: Animator) {
                if (active === session) finish(session)
            }

            override fun onAnimationPause(animation: Animator) {
                if (active === session) finish(session)
            }

            override fun onAnimationEnd(animation: Animator) {
                if (active === session && session.started) {
                    mark("OneUI open stock finished")
                    if (session.assets != null) {
                        finish(session)
                        return
                    }
                    main.removeCallbacks(session.expire)
                    main.postDelayed(session.expire, 160)
                }
            }
        }
        session.listener = listener
        reference.addListener(listener)
        reference.addPauseListener(listener)
        main.postDelayed(session.expire, 3_500)
    }

    private fun startReplay(session: Session, context: Context, snapshot: LauncherWindowGeometry.Snapshot,
        timing: LauncherOpenValueReplay.Timing, expectedChildren: Int) {
        val handler = executor ?: run {
            val thread = HandlerThread("HomeOpenReplay", Process.THREAD_PRIORITY_DISPLAY)
            worker = thread
            thread.start()
            Handler(thread.looper).also { executor = it }
        }
        handler.post {
            if (session.cancelled.get()) return@post
            try {
                val playback = replay.prepare(context, snapshot, timing, expectedChildren,
                    {
                        session.workerWindow = it
                        record(replayTrace, session, it, session.workerFrameTime)
                    }, {
                        session.playback = null
                        if (!session.cancelled.get()) mark("OneUI open detached finished")
                    })
                if (playback == null) {
                    mark("OneUI open detached track mismatch")
                    return@post
                }
                session.playback = playback
                mark("OneUI open detached started")
                playback.startFrameDriven()
            } catch (failure: Exception) {
                HookEntry.log("LAUNCHER_OPEN_REPLAY_FAILED ${failure.javaClass.simpleName}")
                main.post { if (active === session) finish(session) }
            }
        }
    }

    private fun captureOpeningIcon(session: Session, owner: Any, icon: LauncherOpeningIconSurface,
        window: LauncherWindowGeometry.Frame): IconReference? {
        fun reject(reason: String): IconReference? {
            session.note(reason)
            return null
        }
        val layout = layouts ?: return reject("icon layout unavailable")
        val time = currentFrameTime() ?: return reject("icon frame clock unavailable")
        val group = icon.group
        if (container.invoke(owner) !== group) return reject("icon group changed")
        val back = group.getChildAt(0) ?: return reject("icon background view missing")
        val front = group.getChildAt(1) ?: return reject("icon foreground view missing")
        val frontSize = icon.foregroundSize ?: return reject("icon foreground source missing")
        val backSize = icon.backgroundSize
        val projection = layout.captureOpening(owner, group, back, front, frontSize.width, frontSize.height)
            ?: return reject("icon projection setup rejected")
        val stockState = geometry.readStockOpeningIcon(owner) ?: return reject("icon stock state unavailable")
        val stockPose = projection.project(stockState, backSize) ?: return reject("icon stock pose rejected")
        val matches = matchPlane(group, stockPose.group, null, null) &&
            matchPlane(front, stockPose.foreground.position, frontSize, stockPose.group) &&
            matchClip(group, stockPose.foreground.clip) &&
            (stockPose.background?.let { matchPlane(back, it.position, backSize, stockPose.group) && matchClip(back, it.clip) } ?: true)
        mark(if (matches) "OneUI open icon stock geometry matched" else "OneUI open icon stock geometry differed")
        return IconReference(time, projection, backSize, stockPose, window).takeIf { matches }
    }

    private fun matchPlane(view: View, position: LauncherIconPlaneProjection.Position,
        bitmap: LauncherOpeningIconProjection.BitmapSize?, parent: LauncherIconPlaneProjection.Position?): Boolean {
        val root = view.rootView
        viewTransform.reset()
        view.transformMatrixToGlobal(viewTransform)
        root.transformMatrixToLocal(viewTransform)
        root.getLocationInSurface(rootOrigin)
        viewTransform.postTranslate(rootOrigin[0].toFloat(), rootOrigin[1].toFloat())
        viewTransform.getValues(viewValues)
        val scaleX = parent?.scaleX ?: 1f
        val scaleY = parent?.scaleY ?: 1f
        val actualScaleX = viewValues[Matrix.MSCALE_X] * (bitmap?.let { view.width.toFloat() / it.width } ?: 1f)
        val actualScaleY = viewValues[Matrix.MSCALE_Y] * (bitmap?.let { view.height.toFloat() / it.height } ?: 1f)
        return view.visibility == View.VISIBLE && viewValues[Matrix.MSKEW_X] == 0f && viewValues[Matrix.MSKEW_Y] == 0f &&
            abs(viewValues[Matrix.MTRANS_X] - ((parent?.x ?: 0f) + position.x * scaleX)) <= 0.02f &&
            abs(viewValues[Matrix.MTRANS_Y] - ((parent?.y ?: 0f) + position.y * scaleY)) <= 0.02f &&
            abs(actualScaleX - position.scaleX * scaleX) <= 0.00002f &&
            abs(actualScaleY - position.scaleY * scaleY) <= 0.00002f &&
            abs(view.alpha - position.alpha) <= 0.000001f
    }

    private fun matchClip(view: View, clip: LauncherOpeningIconProjection.Clip?): Boolean {
        if (!view.clipToOutline) return clip == null ||
            (clip.radius == 0f && clip.bounds == Rect(0, 0, view.width, view.height))
        viewOutline.setEmpty()
        view.outlineProvider?.getOutline(view, viewOutline)
        return clip != null && viewOutline.getRect(outlineBounds) && outlineBounds == clip.bounds &&
            abs(viewOutline.radius - clip.radius) <= 0.02f
    }

    private fun advance(
        session: Session,
        tick: LauncherOpenValueReplay.Tick,
        icon: IconReference?,
        pair: PairAnchor?,
    ) {
        if (session.cancelled.get() || session.driving != null || tick.nanos <= session.workerFrameTime) return
        val playback = session.playback ?: return
        session.workerFrameTime = tick.nanos
        session.workerWindow = null
        try {
            val matches = if (playback.advance(tick) && icon != null) {
                val predicted = icon.projection.project(playback.openingIcon(), icon.background)
                val matched = predicted == icon.pose && session.workerWindow == icon.window
                mark(if (matched) "OneUI open icon detached geometry matched" else "OneUI open icon detached geometry differed")
                matched
            } else false
            val previousMatch = session.lastMatched
            val match = pair?.takeIf { matches }
            session.lastMatched = match
            session.pairedMatches = when {
                match == null -> 0
                previousMatch != null && previousMatch.handoff === match.handoff && previousMatch.layers === match.layers ->
                    session.pairedMatches + 1
                else -> 1
            }
            if (match != null) promote(session, match)
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_OPEN_CLOCK_FAILED ${failure.javaClass.simpleName}")
            main.post { if (active === session) finish(session) }
        }
    }

    private fun promote(session: Session, anchor: PairAnchor) {
        if (session.cancelled.get() || session.driving != null || session.pairedMatches < 2 ||
            session.lastMatched !== anchor || session.latestPair !== anchor || session.workerFrameTime != anchor.icon.frameNanos
        ) return
        val offer = anchor.offer ?: return
        val writer = surfaceWriter ?: return
        try {
            if (!anchor.app.isValid || !anchor.layers.group.isValid || !anchor.layers.foreground.isValid ||
                (anchor.icon.background != null && !anchor.layers.background.isValid)
            ) return
            if (offer.claim {
                if (session.cancelled.get() || session.latestPair !== anchor || anchor.revoked.get() ||
                    !anchor.bindCommit.committed || System.nanoTime() - anchor.icon.frameNanos !in 0..25_000_000L
                ) return@claim false
                session.driving = anchor
                true
            }) drive(session, anchor, writer)
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_OPEN_HANDOFF_FAILED ${failure.javaClass.simpleName}")
            main.post { if (active === session) finish(session) }
        }
    }

    private fun drive(session: Session, anchor: PairAnchor, writer: LauncherOpeningSurfaceWriter) {
        if (session.cancelled.get() || anchor.revoked.get()) return
        val playback = session.playback
        if (playback == null || session.workerFrameTime != anchor.icon.frameNanos) {
            finishOnMain(session, anchor)
            return
        }
        session.workerWindow = null
        val transaction = SurfaceControl.Transaction()
        var started = false
        try {
            started = playback.continueOnDisplay({ vsync ->
                playback.frameTick?.let { session.workerFrameTime = it.nanos }
                val window = session.workerWindow
                session.workerWindow = null
                val submitted = !session.cancelled.get() && !anchor.revoked.get() && window != null && vsync > 0L &&
                    anchor.icon.projection.project(playback.openingIcon(), anchor.icon.background)?.let { pose ->
                        writer.write(transaction, anchor.app, window, anchor.layers, pose) &&
                            synchronized(anchor) {
                                val accepted = anchor.handoff.directLease.submit(transaction.setFrameTimeline(vsync))
                                if (accepted) {
                                    anchor.lastSubmittedWindow = window
                                    anchor.lastSubmittedVsync = vsync
                                }
                                accepted
                            }
                    } == true
                if (submitted) {
                    anchor.handoff.hasSubmittedDetachedFrame = true
                    if (Trace.isEnabled()) Trace.setCounter("OneUI detached open vsync", vsync)
                    mark("OneUI open detached surfaces submitted")
                } else finishOnMain(session, anchor)
                submitted
            }, { failure ->
                HookEntry.log("LAUNCHER_OPEN_DETACHED_FAILED ${failure.javaClass.simpleName}")
                finishOnMain(session, anchor)
            }, onStopped = transaction::close)
        } finally {
            if (!started) transaction.close()
        }
        if (!started) finishOnMain(session, anchor)
    }

    private fun finishOnMain(session: Session, anchor: PairAnchor) {
        if (!anchor.revoked.get()) main.post {
            if (active === session && session.driving === anchor) finish(session)
        }
    }

    private fun finish(session: Session) {
        if (active === session) active = null
        if (!session.cancelled.compareAndSet(false, true)) return
        session.stockCallback?.close()
        val assets = session.assets
        assets?.restoreSuspendedGeometry()
        val handoff = session.handoff
        if (handoff == null) assets?.close() else handoff.fallBack { assets?.close() }
        main.removeCallbacks(session.expire)
        session.listener?.let { listener ->
            main.post {
                session.reference.removeListener(listener)
                session.reference.removePauseListener(listener)
            }
        }
        session.listener = null
        executor?.post {
            try {
                session.playback?.cancel()
            } catch (failure: Exception) {
                HookEntry.log("LAUNCHER_OPEN_REPLAY_CANCEL_FAILED ${failure.javaClass.simpleName}")
            } finally {
                session.playback = null
            }
        }
    }

    private fun retire() {
        retired = true
        clearPrepared()
        active?.let(::finish)
        stockSwaps.forEach(StockReferenceSwap::restore)
        stockSwaps.clear()
        worker?.quitSafely()
        worker = null
        executor = null
        preparation?.close()
    }

    private fun onMain() = Looper.myLooper() === main.looper

    private fun record(trace: LauncherWindowFrameTrace, session: Session, frame: LauncherWindowGeometry.Frame,
        frameNanos: Long? = currentFrameTime()) {
        if (session.cancelled.get() || !Trace.isEnabled()) return
        if (frameNanos != null && frameNanos > 0L) {
            if (trace === stockTrace) session.lastStockSample = frameNanos
            trace.record(frameNanos, session.id, frame)
        }
    }

    private fun currentFrameTime(): Long? =
        try {
            frameTime.invoke(Choreographer.getInstance()) as Long
        } catch (_: InvocationTargetException) {
            null
        }

    private class LaunchScope(val owner: Any, val input: Any)

    private class IconReference(
        val frameNanos: Long,
        val projection: LauncherOpeningIconProjection,
        val background: LauncherOpeningIconProjection.BitmapSize?,
        val pose: LauncherOpeningIconProjection.Pose,
        val window: LauncherWindowGeometry.Frame,
    )

    private class PairAnchor(
        val icon: IconReference,
        val stockWindow: LauncherStockWindowUpdater.Window,
        val applier: Any,
        val layers: LauncherOpeningSurfaceWriter.Layers,
        val bindCommit: LauncherOpeningIconCommit,
        val handoff: LauncherDirectClosingFrames.Handoff,
        vsync: Long,
    ) {
        val app get() = stockWindow.app
        var offer: LauncherDirectClosingFrames.WorkerOffer? = null
        val revoked = AtomicBoolean()
        // Synchronized with worker submission so a later stock update does not rewind the animated app.
        var lastSubmittedWindow: LauncherWindowGeometry.Frame? = null
        var lastSubmittedVsync = vsync
    }

    private inner class Session(val id: Long, owner: Any, val reference: AnimatorSet, var assets: LauncherOpeningIconSurface?,
        val stockCallback: LauncherOpenValueReplay.StockCallback?) {
        val owner = WeakReference(owner)
        val cancelled = AtomicBoolean()
        val expire = Runnable { finish(this) }
        var started = false
        var reportedPulse = false
        var reportedStockApply = false
        private val traceNotes = mutableSetOf<String>()
        fun note(reason: String) {
            if (Trace.isEnabled() && traceNotes.add(reason)) mark("OneUI open $reason")
        }
        fun noteWriterRejection(reason: String) = note("stock writer rejected $reason")
        var lastStockSample = 0L
        var explicitStockApplyObserved = false
        var firstTick: LauncherOpenValueReplay.Tick? = null
        var lastQueuedTickNanos = 0L
        var iconReference: IconReference? = null
        var listener: AnimatorListenerAdapter? = null
        var handoff: LauncherDirectClosingFrames.Handoff? = null
        var restoreHandoff: LauncherDirectClosingFrames.Handoff? = null
        var restoringIcon = false
        @Volatile var latestPair: PairAnchor? = null
        @Volatile var driving: PairAnchor? = null
        // Accessed by the worker only.
        var lastMatched: PairAnchor? = null
        var pairedMatches = 0
        var playback: LauncherOpenValueReplay.Playback? = null
        var workerWindow: LauncherWindowGeometry.Frame? = null
        var workerFrameTime = 0L
    }

    companion object {
        private const val MAX_STOCK_SWAPS = 16
        fun install(app: Application, loader: ClassLoader) {
            val sdk = Build.VERSION.SDK_INT
            if (sdk != 37 || !HookCompatibility.matchesClockBuild(sdk, Build.DISPLAY)) return
            try {
                @Suppress("DEPRECATION")
                val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
                if (HookCompatibility.matchesLauncherVersion(sdk, version)) LauncherOpeningReplayProbe(loader, null).installHooks()
            } catch (failure: Exception) {
                HookEntry.log("LAUNCHER_OPEN_REPLAY_INSTALL_FAILED ${failure.javaClass.simpleName}")
            }
        }

        fun installForSurfaces(loader: ClassLoader, direct: LauncherDirectClosingFrames,
            hiddenViews: LauncherHiddenIconViews?): (() -> Boolean)? {
            return try {
                val probe = LauncherOpeningReplayProbe(loader, direct, hiddenViews).also { it.installHooks() }
                val idle = { probe.active == null }
                idle
            } catch (failure: Exception) {
                HookEntry.log("LAUNCHER_OPEN_HANDOFF_INSTALL_FAILED ${failure.javaClass.simpleName}")
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
