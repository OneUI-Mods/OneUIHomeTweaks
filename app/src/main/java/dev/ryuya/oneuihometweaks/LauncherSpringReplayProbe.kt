package dev.ryuya.oneuihometweaks

import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.view.AttachedSurfaceControl
import android.view.Choreographer
import android.view.Display
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Mirrors the stock spring until its clock and surface geometry are ready to hand over. */
@RequiresApi(35)
internal class LauncherSpringReplayProbe private constructor(
    loader: ClassLoader,
    private val onSurfacesRetired: ((Any) -> Unit)?,
) {
    private val drivesSurfaces = onSurfacesRetired != null
    private val main = Handler(Looper.getMainLooper())
    private val replay = LauncherSpringReplay(loader)
    private val clock = LauncherSpringFrameClock(loader)
    private val writer = if (drivesSurfaces) LauncherSurfaceFrameWriter() else null
    private val frameTime = Choreographer::class.java.compatibleDeclaredMethod("getFrameTimeNanos")
        .apply { isAccessible = true }
    private val vsyncId = Choreographer::class.java.compatibleDeclaredMethod("getVsyncId")
        .apply { isAccessible = true }
    private var worker: HandlerThread? = null
    private var executor: Handler? = null
    private var followingClose: CloseScope? = null
    private var targetlessSetup: TargetlessClose? = null
    private var retired = false
    private var active: Trial? = null
    private var token = 0L
    private var windowGeometry: LauncherWindowGeometry? = null
    private var readStockSeed: ((Any, Any) -> LauncherWindowGeometry.SpringSeed?)? = null
    /** Supplies the icon session's handoff and app leash when the worker may own the close from release. */
    var earlyTarget: ((Any) -> EarlyTarget?)? = null

    private fun installHooks(loader: ClassLoader) {
        val player = DexSymbols.findClass("com.honeyspace.transition.anim.floating.PlayerImpl", loader)
        val input = DexSymbols.findClass("com.honeyspace.transition.anim.floating.entity.FloatingInputData", loader)
        val desktop = DexSymbols.findClass("com.honeyspace.transition.anim.floating.entity.DesktopInputData", loader)
        val startClose = player.compatibleDeclaredMethod("startSpring", input, desktop)
        val isForward = input.compatibleDeclaredMethod("isForward")
        val fromLock = input.compatibleDeclaredMethod("getStartFromLock")
        val external = input.compatibleDeclaredMethod("getStartFromExtraDisplay")
        val retarget = replay.animationType.compatibleDeclaredMethod("animateToFinalPosition", Rect::class.java)
        val getRelayer = player.compatibleDeclaredMethod("getSpringRelayer")
        val relayerReplay = try {
            LauncherSpringRelayerReplay(getRelayer.returnType, loader)
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_SPRING_RELAYER_UNAVAILABLE ${failure.javaClass.simpleName}")
            null
        }
        val windowGeometry = try {
            LauncherWindowGeometry(player, input)
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_SPRING_WINDOW_GEOMETRY_UNAVAILABLE ${failure.javaClass.simpleName}")
            null
        }
        this.windowGeometry = windowGeometry
        val springCalculator = try {
            windowGeometry?.springCalculator(loader)
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_SPRING_CALCULATOR_UNAVAILABLE ${failure.javaClass.simpleName}")
            null
        }
        if (drivesSurfaces) {
            try {
                readStockSeed = springCalculator?.captureStockSeed(loader)
            } catch (failure: Exception) {
                HookEntry.log("LAUNCHER_SPRING_GEOMETRY_SEED_UNAVAILABLE ${failure.javaClass.simpleName}")
            }
        }
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            if (drivesSurfaces) {
                val targets = player.compatibleDeclaredMethod("getFinalRemoteTargets").returnType
                val apps = targets.compatibleDeclaredMethod("getUnfilteredApps").returnType
                val invalidatesSurfaces = listOf(
                    player.compatibleDeclaredMethod("reset"),
                    player.compatibleDeclaredMethod("pauseAnimation"),
                    player.compatibleDeclaredMethod("cancelAnimation"),
                    player.compatibleDeclaredMethod("endAnimation"),
                    player.compatibleDeclaredMethod("finish"),
                    player.compatibleDeclaredMethod("setSpringRelayer", getRelayer.returnType),
                    player.compatibleDeclaredMethod("targetsUpdate", targets, Int::class.javaPrimitiveType),
                    player.compatibleDeclaredMethod("swapAppTargets", apps, Int::class.javaPrimitiveType),
                )
                for (method in invalidatesSurfaces) {
                    handles += ModernHook.aroundMethod(method) { chain ->
                        val trial = active
                        if (onMain() && method.name == "reset" && targetlessSetup?.player?.get() === chain.thisObject) {
                            targetlessSetup = null
                        }
                        if (!onMain() || trial == null || trial.player.get() !== chain.thisObject) {
                            return@aroundMethod chain.proceed()
                        }
                        val handoff = trial.rendering?.handoff ?: trial.reference?.handoff ?: trial.targetless?.handoff
                        finish(trial)
                        if (handoff == null) chain.proceed() else handoff.duringTargetReplacement { chain.proceed() }
                    }
                }
                installTargetlessSetup(player, loader, handles)
                if (windowGeometry != null) installTrackingUpdates(player, windowGeometry, handles)
            }
            if (relayerReplay != null) {
                handles += ModernHook.aroundMethod(relayerReplay.deliver) { chain ->
                    val trial = active
                    val tick = trial?.lastStockTick
                    if (!onMain() || trial == null || tick == null ||
                        trial.stockRelayer?.get() !== chain.args[0]
                    ) return@aroundMethod chain.proceed()
                    val previous = trial.reference
                    val wasDelivering = trial.deliveringRelayer
                    trial.deliveringRelayer = true
                    val result = try {
                        chain.proceed()
                    } finally {
                        trial.deliveringRelayer = wasDelivering
                    }
                    val reference = trial.reference
                    if (active === trial && trial.rendering == null && reference != null &&
                        reference !== previous && reference.frameNanos == tick.frameNanos
                    ) {
                        executor?.post { compare(trial, reference) }
                    }
                    result
                }
                val type = relayerReplay.type
                val changes = listOf(
                    type.compatibleDeclaredMethod("reset", RectF::class.java, Float::class.javaPrimitiveType),
                    type.compatibleDeclaredMethod("setPosition", RectF::class.java, Float::class.javaPrimitiveType),
                    type.compatibleDeclaredMethod("animateToFinalPosition", RectF::class.java, Float::class.javaPrimitiveType),
                    type.compatibleDeclaredMethod("stiffnessControl", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Long::class.javaPrimitiveType),
                    type.compatibleDeclaredMethod("cancelStiffnessControl", Float::class.javaPrimitiveType),
                    type.compatibleDeclaredMethod("clear"),
                )
                for (method in changes) {
                    handles += ModernHook.aroundMethod(method) { chain ->
                        val trial = active
                        if (!onMain() || trial == null || trial.stockRelayer?.get() !== chain.thisObject) {
                            return@aroundMethod chain.proceed()
                        }
                        if (method.name == "animateToFinalPosition" && trial.dispatchingStockClock && trial.stockUpdated) {
                            return@aroundMethod chain.proceed()
                        }
                        val handoff = trial.rendering?.handoff ?: trial.reference?.handoff ?: trial.targetless?.handoff
                        finish(trial)
                        if (handoff == null) chain.proceed() else handoff.duringTargetReplacement { chain.proceed() }
                    }
                }
            }
            handles += ModernHook.aroundMethod(clock.dispatch) { chain ->
                val trial = active
                val handler = executor
                if (!onMain() || trial == null || handler == null || trial.cancelled.get() ||
                    (trial.rendering != null && trial.stockRelayer == null)
                ) {
                    return@aroundMethod chain.proceed()
                }
                val timeline = currentFrameTime()
                val uptime = chain.args[0] as Long
                val relayed = trial.stockRelayer != null
                val clockMatches = !relayed || clock.isThreadHandler(chain.thisObject)
                val tick = timeline?.let {
                    val vsync = if (relayed && clockMatches) vsyncId.invoke(Choreographer.getInstance()) as Long else 0
                    LauncherSpringFrameClock.StockTick(it, uptime, vsync)
                }
                if (relayed && clockMatches && tick != null) trial.lastStockTick = tick
                trial.stockUpdated = false
                val dispatching = trial.dispatchingStockClock
                trial.dispatchingStockClock = clockMatches
                val result = try {
                    chain.proceed()
                } finally {
                    trial.dispatchingStockClock = dispatching
                }
                if (active === trial && trial.rendering == null && tick != null && clockMatches && (relayed || trial.stockUpdated)) {
                    val reference = trial.reference?.takeIf { !relayed && it.frameNanos == timeline }
                    handler.post { mirror(trial, tick, reference) }
                }
                result
            }
            handles += ModernHook.aroundMethod(startClose) { chain ->
                val data = chain.args[0]
                val owner = chain.thisObject
                if (!onMain() || retired || (!drivesSurfaces && !Trace.isEnabled()) || data == null || owner == null) {
                    return@aroundMethod chain.proceed()
                }
                if (drivesSurfaces) active?.let(::finish)
                val previous = followingClose
                val eligible = chain.args[1] == null && isForward.invoke(data) == false &&
                    fromLock.invoke(data) == false && external.invoke(data) == false
                val targetless = targetlessSetup?.takeIf { it.player.get() === owner }
                if (targetless != null) targetlessSetup = null
                followingClose = if (eligible) CloseScope(owner, data, targetless) else null
                try {
                    chain.proceed()
                } finally {
                    followingClose = previous
                }
            }
            handles += ModernHook.aroundMethod(replay.startCallback) { chain ->
                val stock = chain.thisObject
                val closing = followingClose
                if (!retired && onMain() && closing != null && stock != null && ValueAnimator.areAnimatorsEnabled()) {
                    val context = chain.args[0] as? Context
                    val velocity = chain.args[1] as? PointF
                    if (context != null && velocity != null) {
                        val relayer = getRelayer.invoke(closing.player)
                        launch(stock, context, velocity, closing, windowGeometry, springCalculator, relayer, relayerReplay)
                    }
                }
                chain.proceed()
            }
            if (windowGeometry != null) {
                val apply = player.compatibleDeclaredMethod("apply", Float::class.javaPrimitiveType)
                handles += ModernHook.aroundMethod(apply) { chain ->
                    val owner = chain.thisObject
                    val trial = active
                    if (Trace.isEnabled() && onMain() && owner != null && trial?.player?.get() === owner && trial.recordWindow) {
                        windowGeometry.readStock(owner)?.let { traceWindow(STOCK_WINDOW, trial.token, it) }
                    }
                    chain.proceed()
                }
            }
            handles += ModernHook.aroundMethod(retarget) { chain ->
                val trial = active
                if (onMain() && trial != null && trial.stock.get() === chain.thisObject) {
                    val bounds = chain.args[0] as? Rect
                    if (bounds != null && !trial.sameTarget(bounds)) {
                        mark("OneUI spring target changed")
                        finish(trial)
                    }
                }
                chain.proceed()
            }
            for (name in listOf("cancel", "clear", "end")) {
                handles += ModernHook.aroundMethod(replay.animationType.compatibleDeclaredMethod(name)) { chain ->
                    val trial = active
                    if (onMain() && trial != null && trial.stock.get() === chain.thisObject) finish(trial)
                    chain.proceed()
                }
            }
            ModernHook.onGenerationRetired {
                if (onMain()) retire() else main.post(::retire)
            }
        } catch (failure: Exception) {
            handles.asReversed().forEach { it.unhook() }
            retire()
            throw failure
        }
        HookEntry.log("LAUNCHER_SPRING_REPLAY_READY")
    }

    private fun installTrackingUpdates(player: Class<*>, geometry: LauncherWindowGeometry,
        handles: MutableList<ModernHook.Handle>) {
        try {
            val update = player.compatibleDeclaredMethod("registerPositionListener\$lambda\$1", player,
                View::class.java, ViewGroup::class.java, Rect::class.java)
            handles += ModernHook.aroundMethod(update) { chain ->
                val result = chain.proceed()
                val owner = chain.args[0]
                val trial = active
                if (onMain() && trial != null && owner != null && trial.player.get() === owner) {
                    try {
                        publishStockTracking(trial, owner, geometry)
                    } catch (failure: Exception) {
                        fail(trial, failure)
                    }
                }
                result
            }
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_CLOSE_TRACKING_WATCH_UNAVAILABLE ${failure.javaClass.simpleName}")
        }
    }

    private fun publishStockTracking(trial: Trial, owner: Any, geometry: LauncherWindowGeometry): LauncherWindowGeometry.Tracking? {
        val previous = trial.liveTracking
        val current = geometry.readTracking(owner, previous) ?: return null
        if (current !== previous) {
            trial.liveTracking = current
            mark("OneUI closing stock tracking changed")
            if (Trace.isEnabled()) {
                Trace.setCounter("OneUI closing tracking x", current.x.toLong())
                Trace.setCounter("OneUI closing tracking y", current.y.toLong())
                Trace.setCounter("OneUI closing tracking scale x1e6", (current.scale * 1_000_000).toLong())
            }
        }
        return current
    }

    private fun installTargetlessSetup(player: Class<*>, loader: ClassLoader, handles: MutableList<ModernHook.Handle>) {
        try {
            val input = DexSymbols.findClass("com.honeyspace.transition.anim.floating.entity.SetupData", loader)
            val setup = player.compatibleDeclaredMethod("setup", input)
            val target = input.compatibleDeclaredMethod("getTarget")
            val center = input.compatibleDeclaredMethod("getCenterMostInfo")
            val screen = input.compatibleDeclaredMethod("getScreen")
            val closing = input.compatibleDeclaredMethod("isGestureClosing")
            val opening = input.compatibleDeclaredMethod("isForward")
            val container = player.compatibleDeclaredMethod("getContainerView")
            val originalView = player.compatibleDeclaredMethod("getOriginalView")
            val widget = player.compatibleDeclaredField("containerWidgetView").apply { isAccessible = true }
            val getTargets = player.compatibleDeclaredMethod("getFinalRemoteTargets")
            val setTargets = player.compatibleDeclaredMethod("setFinalRemoteTargets", getTargets.returnType)
            handles += ModernHook.aroundMethod(setTargets) { chain ->
                val trial = active?.takeIf { onMain() && it.player.get() === chain.thisObject }
                val handoff = trial?.targetless?.handoff
                if (handoff == null || getTargets.invoke(chain.thisObject) === chain.args[0]) {
                    chain.proceed()
                } else {
                    finish(checkNotNull(trial))
                    handoff.duringTargetReplacement { chain.proceed() }
                }
            }
            handles += ModernHook.aroundMethod(setup) { chain ->
                val owner = chain.thisObject
                val data = chain.args[0]
                if (!onMain() || retired || owner == null || data == null) return@aroundMethod chain.proceed()
                targetlessSetup = null
                val previous = active?.takeIf { it.targetless != null && it.player.get() === owner }
                val handoff = previous?.targetless?.handoff
                if (previous != null) finish(previous)
                val result = if (handoff == null) chain.proceed() else handoff.duringTargetReplacement { chain.proceed() }
                try {
                    if (target.invoke(data) == null && center.invoke(data) == null && closing.invoke(data) == true &&
                        opening.invoke(data) == false && container.invoke(owner) == null && originalView.invoke(owner) == null &&
                        widget.get(owner) == null
                    ) {
                        val view = screen.invoke(data) as? View
                        if (view?.display?.displayId == Display.DEFAULT_DISPLAY && view.isAttachedToWindow) {
                            targetlessSetup = TargetlessClose(owner, view)
                            mark("OneUI closing no icon setup")
                        }
                    }
                } catch (failure: Exception) {
                    HookEntry.log("LAUNCHER_TARGETLESS_CLOSE_SETUP_FAILED ${failure.javaClass.simpleName}")
                }
                result
            }
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_TARGETLESS_CLOSE_UNAVAILABLE ${failure.javaClass.simpleName}")
        }
    }

    private fun launch(stock: Any, context: Context, velocity: PointF, closing: CloseScope,
        windowGeometry: LauncherWindowGeometry?, springCalculator: LauncherWindowGeometry.SpringCalculator?,
        stockRelayer: Any?, relayerReplay: LauncherSpringRelayerReplay?) {
        try {
            val snapshot = replay.snapshot(stock, context, velocity) ?: return
            active?.let(::finish)
            val window = windowGeometry?.let { geometry ->
                try {
                    geometry.snapshot(closing.player, closing.input)
                } catch (failure: Exception) {
                    HookEntry.log("LAUNCHER_SPRING_WINDOW_SNAPSHOT_FAILED ${failure.javaClass.simpleName}")
                    null
                }
            }
            val relayer = stockRelayer?.let { relayerReplay?.snapshot(it) }
            if (stockRelayer != null) {
                mark(if (relayer == null) "OneUI spring relayer unsupported state" else "OneUI spring relayer captured")
                if (relayer == null) return
            }
            if (drivesSurfaces && (window == null || springCalculator == null)) return
            val handler = executor ?: run {
                val thread = HandlerThread("HomeSpringReplay", Process.THREAD_PRIORITY_DISPLAY)
                worker = thread
                thread.start()
                Handler(thread.looper).also { executor = it }
            }
            val trial = Trial(++token, closing.player, stock, snapshot.target, window != null, stockRelayer, closing.targetless,
                relayerReplay)
            trial.liveTracking = window?.tracking
            active = trial
            val earlyStart = if (BuildConfig.LAUNCHER_EARLY_CLOSING_DRIVE && drivesSurfaces && relayer != null &&
                window != null && closing.targetless == null
            ) claimAtRelease(trial, closing.player, window) else null
            trial.observation = replay.observe(stock, { rectangle, progress ->
                if (!trial.cancelled.get()) {
                    trial.stockUpdated = true
                    currentFrameTime()?.let { traceSample(STOCK, trial.token, rectangle, progress, it) }
                }
            }, {
                if (!trial.cancelled.get()) mark("OneUI spring stock finished")
                if (onMain() && (trial.early || trial.reference != null || trial.targetless?.handoff != null)) finish(trial)
            }, beforeStock = true)
            main.postDelayed(trial.expire, 3_500)
            handler.post {
                if (trial.cancelled.get()) return@post
                try {
                    val relay = relayer?.let { checkNotNull(relayerReplay).prepare(it) }
                    trial.relay = relay
                    trial.calculator = try {
                        window?.let { springCalculator?.prepare(context, it) }
                    } catch (failure: Exception) {
                        HookEntry.log("LAUNCHER_SPRING_WINDOW_PREPARE_FAILED ${failure.javaClass.simpleName}")
                        null
                    }
                    val animation = replay.prepare(snapshot, { rectangle, progress ->
                        if (!trial.cancelled.get()) {
                            val time = trial.workerFrameTime
                            traceSample(DETACHED, trial.token, rectangle, progress, time)
                            if (relay == null) calculate(trial, rectangle, progress) else relay.offer(rectangle, progress)
                        }
                    }, {
                        trial.workerFinished = true
                        mark("OneUI spring detached finished")
                    })
                    trial.motion = animation
                    trial.playback = clock.start(animation, relay) { rectangle, progress -> calculate(trial, rectangle, progress) }
                    mark("OneUI spring detached started")
                    if (earlyStart != null) {
                        check(relay != null && trial.calculator != null)
                        driveSurfaces(trial, checkNotNull(trial.rendering), alignToDisplay = false, startAt = earlyStart)
                    }
                } catch (failure: Exception) {
                    HookEntry.log("LAUNCHER_SPRING_REPLAY_FAILED ${failure.javaClass.simpleName}")
                    main.post { if (active === trial) finish(trial) }
                }
            }
            mark("OneUI spring stock started")
        } catch (failure: Exception) {
            active?.let(::finish)
            HookEntry.log("LAUNCHER_SPRING_REPLAY_UNAVAILABLE ${failure.javaClass.simpleName}")
        }
    }

    /** Main thread, inside the stock start. Afterwards every stock frame of this close is captured and discarded. */
    private fun claimAtRelease(trial: Trial, owner: Any, window: LauncherWindowGeometry.Snapshot):
        LauncherSpringFrameClock.StockTick? {
        return try {
            val target = earlyTarget?.invoke(owner) ?: return null
            val frame = windowGeometry?.readStock(owner) ?: return null
            if (!target.app.isValid) return null
            val now = System.nanoTime()
            val start = LauncherSpringFrameClock.StockTick(now, SystemClock.uptimeMillis())
            val reference = Reference(now, now, 0L, target.handoff, target.app, null, frame, window.tracking,
                null, null, null, null)
            if (!target.handoff.claimEarly(target.window)) {
                mark("OneUI closing early claim refused")
                return null
            }
            trial.early = true
            trial.rendering = reference
            start
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_EARLY_CLOSE_CLAIM_FAILED ${failure.javaClass.simpleName}")
            null
        }
    }

    /** Main thread. The icon joins the worker's frames once the Home window can parent it and its projection is verified. */
    fun attachEarlyIcon(owner: Any, icon: SurfaceControl, projection: LauncherIconPlaneProjection,
        position: LauncherIconPlaneProjection.Position): Boolean {
        val trial = active?.takeIf { it.early && it.player.get() === owner && !it.cancelled.get() } ?: return false
        trial.earlyIcon = IconFrame(icon, projection.copyForWorker(), position)
        mark("OneUI closing early icon attached")
        return true
    }

    fun isEarly(owner: Any): Boolean = active?.let { it.early && it.player.get() === owner && !it.cancelled.get() } == true

    /** Main thread, on a discarded stock frame. Copies the stock calculator's lazily-built seed once. */
    fun earlyStockFrame(owner: Any) {
        val trial = active?.takeIf { it.early && it.player.get() === owner && !it.cancelled.get() } ?: return
        if (trial.geometrySeed != null || trial.seedReadFailed) return
        try {
            val seed = trial.stock.get()?.let { stock -> readStockSeed?.invoke(owner, stock) } ?: return
            trial.geometrySeed = seed
            mark("OneUI spring stock geometry captured")
            executor?.post {
                val calculator = trial.calculator
                if (!trial.cancelled.get() && calculator != null && !trial.seedApplied) {
                    calculator.useStockSeed(seed)
                    trial.seedApplied = true
                    mark("OneUI spring stock geometry applied")
                }
            }
        } catch (failure: Exception) {
            trial.seedReadFailed = true
            HookEntry.log("LAUNCHER_SPRING_GEOMETRY_SEED_FAILED ${failure.javaClass.simpleName}")
        }
    }

    fun offer(owner: Any, pending: LauncherDirectClosingFrames.Pending, app: SurfaceControl, icon: SurfaceControl,
        projection: LauncherIconPlaneProjection, iconPosition: LauncherIconPlaneProjection.Position) {
        val trial = active?.takeIf { it.player.get() === owner && it.rendering == null && it.targetless == null } ?: return
        val workerProjection = trial.projection?.takeIf { trial.sourceProjection === projection }
            ?: projection.copyForWorker().also { trial.sourceProjection = projection; trial.projection = it }
        offer(trial, pending.handoff, pending, app, IconFrame(icon, workerProjection, iconPosition))
    }

    private fun offer(trial: Trial, handoff: LauncherDirectClosingFrames.Handoff,
        pending: LauncherDirectClosingFrames.Pending?, app: SurfaceControl, icon: IconFrame?) {
        val time = stockFrameTime(trial) ?: return
        val observedNanos = System.nanoTime()
        val owner = trial.player.get() ?: return
        val geometry = windowGeometry ?: return
        val window = geometry.readStock(owner) ?: return
        val tracking = publishStockTracking(trial, owner, geometry) ?: return
        if (trial.geometrySeed == null && !trial.seedReadFailed) {
            try {
                trial.geometrySeed = trial.stock.get()?.let { stock -> readStockSeed?.invoke(owner, stock) }
                if (trial.geometrySeed != null) mark("OneUI spring stock geometry captured")
            } catch (failure: Exception) {
                trial.seedReadFailed = true
                HookEntry.log("LAUNCHER_SPRING_GEOMETRY_SEED_FAILED ${failure.javaClass.simpleName}")
            }
        }
        val relayerSeed = if (trial.deliveringRelayer && !trial.relayerSeedCaptured) {
            trial.relayerSeedCaptured = true
            try {
                trial.stockRelayer?.get()?.let { trial.relayerReplay?.snapshot(it) }
                    ?.also { mark("OneUI spring live relayer state captured") }
            } catch (failure: Exception) {
                HookEntry.log("LAUNCHER_SPRING_RELAYER_REBASE_UNAVAILABLE ${failure.javaClass.simpleName}")
                null
            }
        } else null
        // The stock and worker can leave the fling phase on different ticks. Try again
        // for the app-only close while no phase-compatible state has been transferred.
        val retryHorizontal = BuildConfig.LAUNCHER_CLAIM_QUEUED_CLOSING_WINDOW && relayerSeed == null &&
            trial.targetless != null && trial.horizontalSamples > 0 &&
            trial.horizontalSamples < MAX_HORIZONTAL_SAMPLES && !trial.horizontalSynced && trial.deliveringRelayer
        val horizontalSeed = if (relayerSeed != null || retryHorizontal) {
            trial.horizontalSamples++
            try {
                trial.stock.get()?.let(replay::captureHorizontal)
                    ?.also { mark("OneUI spring live horizontal state captured") }
            } catch (failure: Exception) {
                HookEntry.log("LAUNCHER_SPRING_HORIZONTAL_STATE_UNAVAILABLE ${failure.javaClass.simpleName}")
                null
            }
        } else null
        val workerOffer = pending?.let {
            val handler = executor ?: return
            val queuedHasIcon = when {
                icon == null && BuildConfig.LAUNCHER_CLAIM_QUEUED_CLOSING_WINDOW -> false
                icon != null && BuildConfig.LAUNCHER_CLAIM_QUEUED_CLOSING_PAIR -> true
                else -> null
            }
            it.offerToWorker(Executor { callback -> handler.post(callback) }, queuedHasIcon) { offer ->
                mark(when {
                    offer.queued && icon != null -> "OneUI closing pair baseline queued for worker"
                    offer.queued -> "OneUI closing targetless baseline queued for worker"
                    icon == null -> "OneUI closing targetless baseline committed on worker"
                    else -> "OneUI closing pair baseline committed on worker"
                })
                tryClaim(trial, offer)
            }
        }
        trial.reference = Reference(time, observedNanos, pending?.stockVsync ?: 0L, handoff, app, icon, window,
            tracking, trial.geometrySeed, relayerSeed, horizontalSeed, workerOffer)
    }

    fun targetlessWindowUpdate(direct: LauncherDirectClosingFrames): TargetlessWindowUpdate = { owner, view, readWindow, stock ->
        applyTargetlessWindow(owner, view, readWindow, direct, stock)
    }

    fun targetlessApply(direct: LauncherDirectClosingFrames, readWindow: (Any, View) -> LauncherStockWindowUpdater.Window?):
        (Any, () -> Any?) -> Any? = apply@{ owner, stock ->
            val trial = active?.takeIf { onMain() && it.player.get() === owner && !it.cancelled.get() }
            val screen = trial?.targetless?.screen?.get() ?: return@apply stock()
            mark("OneUI closing no icon apply observed")
            applyTargetlessWindow(owner, screen, { readWindow(owner, screen) }, direct, stock)
        }

    private fun applyTargetlessWindow(owner: Any, view: View, readWindow: () -> LauncherStockWindowUpdater.Window?,
        direct: LauncherDirectClosingFrames, stock: () -> Any?): Any? {
        val trial = active?.takeIf { onMain() && it.player.get() === owner && !it.cancelled.get() } ?: return stock()
        val close = trial.targetless ?: return stock()
        val stockWindow = try {
            readWindow()
        } catch (failure: Exception) {
            fail(trial, failure)
            return stock()
        }
        val app = stockWindow?.app
        val rootView = view.rootView
        val root = rootView.rootSurfaceControl
        if (app == null || close.screen.get() !== view || !view.isAttachedToWindow || root == null ||
            stockFrameTime(trial) == null || (close.root != null && (close.root !== root || close.app !== app))
        ) {
            if (close.handoff != null) finish(trial)
            mark(if (app == null) "OneUI closing no icon stock window not eligible" else "OneUI closing no icon view or clock changed")
            return stock()
        }
        val handoff = close.handoff ?: direct.Handoff {
            if (active === trial) finish(trial)
        }.also {
            close.handoff = it
            close.root = root
            close.app = app
            mark("OneUI closing no icon window eligible")
        }
        if (handoff.disabled) return stock()
        val scope = direct.begin(rootView, handoff, ready = true, delayedVsync = delayedStockVsync(owner), player = owner)
        if (scope == null) {
            finish(trial)
            return stock()
        }
        val result = try {
            stock()
        } catch (failure: Throwable) {
            direct.end(scope)?.restoreStock()
            finish(trial)
            throw failure
        }
        val pending = direct.end(scope)
        try {
            if (active === trial) {
                val rendering = trial.rendering
                if (pending == null) {
                    if (scope.observedStockFrame) offer(trial, handoff, null, app, null)
                } else if (pending.detachedStock && rendering?.app === app) {
                    if (checkNotNull(stockWindow).carriesMidpoint()) {
                        if (preserveMidpoint(close, pending, rendering, delayedStockVsync(owner))) {
                            mark("OneUI closing detached stock midpoint submitted")
                        } else finish(trial)
                    } else pending.discardStock()
                } else if (!pending.detachedStock) {
                    offer(trial, handoff, pending, app, null)
                    pending.commit()
                }
            }
        } catch (failure: Exception) {
            fail(trial, failure)
        } finally {
            pending?.restoreStock()
        }
        return result
    }

    private fun preserveMidpoint(close: TargetlessClose, pending: LauncherDirectClosingFrames.Pending,
        baseline: Reference, stockVsync: Long): Boolean {
        val encoder = writer ?: return false
        // This transaction carries the original stock listener. Keep the last independent geometry so it cannot rewind the leashed app.
        return synchronized(close) {
            val frame = close.submittedFrame ?: baseline.window
            pending.commitWhileDetached { transaction ->
                if (!encoder.writeWindow(transaction, baseline.app, frame)) return@commitWhileDetached false
                transaction.setFrameTimeline(maxOf(stockVsync, close.submittedVsync, baseline.vsync))
                true
            }
        }
    }

    private fun stockFrameTime(trial: Trial): Long? = if (trial.stockRelayer == null) currentFrameTime() else {
        trial.lastStockTick?.frameNanos?.takeIf { trial.deliveringRelayer }
    }

    fun owns(owner: Any, app: SurfaceControl?, icon: SurfaceControl, requireSubmitted: Boolean = false): Boolean {
        val trial = active?.takeIf { it.player.get() === owner && !it.cancelled.get() } ?: return false
        val rendering = trial.rendering ?: return false
        return rendering.handoff.detached && rendering.app === app && (rendering.icon ?: trial.earlyIcon)?.surface === icon &&
            (!requireSubmitted || rendering.handoff.hasSubmittedDetachedFrame)
    }

    fun delayedStockVsync(owner: Any): Long {
        val trial = active?.takeIf { it.deliveringRelayer && it.player.get() === owner } ?: return 0L
        return trial.lastStockTick?.vsyncId ?: 0L
    }

    fun stop(owner: Any) {
        active?.takeIf { it.player.get() === owner }?.let(::finish)
    }

    private fun mirror(trial: Trial, tick: LauncherSpringFrameClock.StockTick, reference: Reference?) {
        if (trial.cancelled.get() || trial.rendering != null) return
        val playback = trial.playback ?: return
        trial.workerFrameTime = tick.frameNanos
        try {
            trial.lastWindow = null
            if (playback.mirror(tick) && reference != null) compare(trial, reference)
        } catch (failure: Exception) {
            fail(trial, failure)
        }
    }

    private fun calculate(trial: Trial, rectangle: RectF, progress: Float) {
        if (trial.cancelled.get()) return
        val calculator = trial.calculator ?: return
        if (trial.rendering != null) trial.liveTracking?.let(calculator::useTracking)
        trial.lastBounds.set(rectangle)
        trial.lastProgress = progress
        val window = calculator.update(rectangle, progress)
        trial.lastWindow = window
        DETACHED_WINDOW.record(trial.workerFrameTime, trial.token, window)
    }

    private fun compare(trial: Trial, reference: Reference) {
        if (trial.cancelled.get() || trial.rendering != null || trial.workerFrameTime != reference.frameNanos) return
        try {
            val seed = reference.geometrySeed
            val calculator = trial.calculator
            var recalculate = calculator?.useTracking(reference.tracking) == true
            if (seed != null && calculator != null && !trial.seedApplied) {
                calculator.useStockSeed(seed)
                trial.seedApplied = true
                recalculate = true
                mark("OneUI spring stock geometry applied")
            }
            val relayerSeed = reference.relayerSeed
            val relay = trial.relay
            val rebased = relayerSeed != null && relay != null && trial.playback?.rebase(reference.frameNanos) {
                reference.horizontalSeed?.let { horizontal ->
                    syncHorizontal(trial, horizontal)
                }
                relay.rebase(relayerSeed, reference.window.progress) { rectangle, progress -> calculate(trial, rectangle, progress) }
            } == true
            if (rebased) mark("OneUI spring live relayer state applied")
            if (recalculate && !rebased && trial.lastWindow != null) {
                trial.lastWindow = checkNotNull(calculator).update(trial.lastBounds, trial.lastProgress).also { corrected ->
                    DETACHED_WINDOW.record(trial.workerFrameTime, trial.token, corrected)
                }
            }
            val retry = reference.horizontalSeed?.takeIf {
                relayerSeed == null && !trial.horizontalSynced && reference.frameNanos > trial.lastCompared
            }
            if (retry != null) {
                var synced = false
                trial.playback?.rebase(reference.frameNanos) { synced = syncHorizontal(trial, retry) }
                if (synced) {
                    // The copied frame does not count. If the previous sample matched,
                    // one fresh prediction can confirm the state across the phase change.
                    trial.lastCompared = reference.frameNanos
                    return
                }
            }
            val window = trial.lastWindow
            val icon = reference.icon
            val matches = when {
                window == null -> false
                icon == null -> reference.window.matches(window)
                else -> calculator?.projectSurfaces(window, icon.projection)?.let { independent ->
                    LauncherWindowGeometry.SurfaceFrame(reference.window, icon.actual).matches(independent)
                } == true
            }
            recordComparison(trial, reference, matches)
            reference.workerOffer?.let { tryClaim(trial, it) }
        } catch (failure: Exception) {
            fail(trial, failure)
        }
    }

    private fun syncHorizontal(trial: Trial, snapshot: LauncherHorizontalSpringState.Snapshot): Boolean {
        val applied = trial.motion?.useHorizontal(snapshot) == true
        if (applied) trial.horizontalSynced = true
        mark(if (applied) "OneUI spring live horizontal state applied" else "OneUI spring live horizontal phase differed")
        return applied
    }

    private fun recordComparison(trial: Trial, reference: Reference, matches: Boolean) {
        if (reference.frameNanos <= trial.lastCompared) return
        trial.lastCompared = reference.frameNanos
        val previous = trial.matched
        trial.matchCount = when {
            !matches -> 0
            previous != null && previous.handoff === reference.handoff && previous.app === reference.app &&
                previous.icon?.surface === reference.icon?.surface &&
                previous.icon?.projection === reference.icon?.projection -> trial.matchCount + 1
            else -> 1
        }
        trial.matched = reference.takeIf { matches }
        mark(if (matches) "OneUI spring exact frame matched" else "OneUI spring exact frame differed")
    }

    private fun tryClaim(trial: Trial, offer: LauncherDirectClosingFrames.WorkerOffer) {
        if (trial.cancelled.get() || trial.rendering != null || trial.matchCount < 2) return
        val reference = trial.matched?.takeIf {
            it === trial.reference && it.workerOffer === offer && it.frameNanos == trial.workerFrameTime
        } ?: return
        try {
            if (!reference.app.isValid || reference.icon?.surface?.isValid == false) return
            var alignToDisplay = false
            if (offer.claim {
                if (trial.cancelled.get() || trial.reference !== reference) return@claim false
                val now = System.nanoTime()
                val frameAge = now - reference.frameNanos
                if (frameAge !in 0L..MAX_HANDOFF_AGE_NS) {
                    // A delayed stock callback can deliver a newly-queued pose calculated against an old tick.
                    // The app-only path can continue from that pose without skipping the elapsed part of the spring.
                    alignToDisplay = BuildConfig.LAUNCHER_ALIGN_LATE_CLOSING_CLOCK && offer.queued &&
                        trial.targetless != null && reference.icon == null && frameAge > MAX_HANDOFF_AGE_NS &&
                        now - reference.observedNanos in 0L..MAX_HANDOFF_AGE_NS
                    if (!alignToDisplay) return@claim false
                    mark("OneUI closing late clock aligned")
                }
                trial.rendering = reference
                true
            }) driveSurfaces(trial, reference, alignToDisplay)
        } catch (failure: Exception) {
            fail(trial, failure)
        }
    }

    private fun driveSurfaces(trial: Trial, reference: Reference, alignToDisplay: Boolean,
        startAt: LauncherSpringFrameClock.StockTick? = null) {
        if (trial.cancelled.get()) return
        val playback = trial.playback ?: return
        val encoder = writer ?: return
        try {
            val transaction = SurfaceControl.Transaction()
            val oldPriority = prioritizeDisplay()
            val release = {
                try {
                    transaction.close()
                } finally {
                    if (oldPriority != null) {
                        try {
                            Process.setThreadPriority(oldPriority)
                            mark("OneUI closing worker priority restored")
                        } catch (failure: RuntimeException) {
                            HookEntry.log("LAUNCHER_CLOSE_PRIORITY_RESTORE_FAILED ${failure.javaClass.simpleName}")
                        }
                    }
                }
            }
            var started = false
            try {
                trial.lastWindow = null
                started = playback.continueOnDisplay({ vsync ->
                    if (trial.cancelled.get()) return@continueOnDisplay false
                    val window = trial.lastWindow ?: return@continueOnDisplay !trial.workerIsDone()
                    trial.lastWindow = null
                    val icon = reference.icon ?: trial.earlyIcon
                    if (icon == null) {
                        check(encoder.writeWindow(transaction, reference.app, window))
                    } else {
                        val frame = checkNotNull(trial.calculator?.projectSurfaces(window, icon.projection))
                        check(encoder.writePair(transaction, reference.app, frame.window, icon.surface, frame.icon))
                    }
                    transaction.setFrameTimeline(vsync)
                    val targetless = trial.targetless
                    val submitted = if (targetless == null) reference.handoff.directLease.submit(transaction)
                    else synchronized(targetless) {
                        reference.handoff.directLease.submit(transaction).also { accepted ->
                            if (accepted) {
                                targetless.submittedFrame = window
                                targetless.submittedVsync = vsync
                            }
                        }
                    }
                    if (!submitted) return@continueOnDisplay false
                    reference.handoff.hasSubmittedDetachedFrame = true
                    if (Trace.isEnabled()) {
                        Trace.setCounter("OneUI detached close vsync", vsync)
                        mark(when {
                            trial.early && icon == null -> "OneUI closing early window submitted"
                            trial.early -> "OneUI closing early pair submitted"
                            icon == null -> "OneUI closing detached window submitted"
                            else -> "OneUI closing detached pair submitted"
                        })
                    }
                    !trial.workerIsDone()
                }, { failure -> fail(trial, failure) }, onStopped = release, alignToFirstDisplayTick = alignToDisplay,
                    startAt = startAt)
            } finally {
                if (!started) release()
            }
            check(started)
        } catch (failure: Exception) {
            fail(trial, failure)
        }
    }

    private fun prioritizeDisplay(): Int? {
        return try {
            val previous = Process.getThreadPriority(Process.myTid())
            if (previous <= Process.THREAD_PRIORITY_URGENT_DISPLAY) return null
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            mark("OneUI closing worker priority raised")
            previous
        } catch (_: SecurityException) {
            mark("OneUI closing worker priority denied")
            null
        }
    }

    private fun fail(trial: Trial, failure: Exception) {
        trial.cancelled.set(true)
        HookEntry.log("LAUNCHER_SPRING_REPLAY_FRAME_FAILED ${failure.javaClass.simpleName}")
        main.post { if (active === trial) finish(trial) }
    }

    private fun finish(trial: Trial) {
        if (active === trial) active = null
        main.removeCallbacks(trial.expire)
        trial.cancelled.set(true)
        val handoff = trial.rendering?.handoff ?: trial.reference?.handoff ?: trial.targetless?.handoff
        handoff?.fallBack()
        if (handoff?.hasDetached == true) trial.player.get()?.let { onSurfacesRetired?.invoke(it) }
        val observation = trial.observation
        trial.observation = null
        // Retarget and cancellation may happen from another stock listener's callback.
        if (observation != null) main.post {
            try {
                observation.detach()
            } catch (failure: Exception) {
                HookEntry.log("LAUNCHER_SPRING_REPLAY_DETACH_FAILED ${failure.javaClass.simpleName}")
            }
        }
        executor?.post {
            try {
                trial.playback?.close()
            } catch (failure: Exception) {
                HookEntry.log("LAUNCHER_SPRING_REPLAY_CANCEL_FAILED ${failure.javaClass.simpleName}")
            } finally {
                trial.playback = null
                trial.relay = null
                trial.motion = null
            }
        }
    }

    private fun retire() {
        retired = true
        targetlessSetup = null
        active?.let(::finish)
        worker?.quitSafely()
        worker = null
        executor = null
    }

    private fun onMain() = Looper.myLooper() === main.looper

    private fun currentFrameTime(): Long? {
        if (!drivesSurfaces && !Trace.isEnabled()) return null
        return try {
            frameTime.invoke(Choreographer.getInstance()) as Long
        } catch (_: InvocationTargetException) {
            null
        }
    }

    private fun traceSample(labels: Labels, token: Long, rectangle: RectF, progress: Float, time: Long) {
        if (!Trace.isEnabled() || time <= 0L) return
        Trace.beginSection(labels.sample)
        Trace.setCounter(labels.trial, token)
        Trace.setCounter(labels.time, time)
        Trace.setCounter(labels.left, (rectangle.left * 100f).toLong())
        Trace.setCounter(labels.top, (rectangle.top * 100f).toLong())
        Trace.setCounter(labels.right, (rectangle.right * 100f).toLong())
        Trace.setCounter(labels.bottom, (rectangle.bottom * 100f).toLong())
        Trace.setCounter(labels.progress, (progress * 1_000_000f).toLong())
        Trace.endSection()
    }

    private fun traceWindow(labels: LauncherWindowFrameTrace, token: Long, state: LauncherWindowGeometry.Frame) {
        val time = currentFrameTime() ?: active?.takeIf { it.deliveringRelayer }?.lastStockTick?.frameNanos ?: return
        labels.record(time, token, state)
    }

    private class CloseScope(val player: Any, val input: Any, val targetless: TargetlessClose?)

    private class TargetlessClose(player: Any, screen: View) {
        val player = WeakReference(player)
        val screen = WeakReference(screen)
        var app: SurfaceControl? = null
        var root: AttachedSurfaceControl? = null
        var handoff: LauncherDirectClosingFrames.Handoff? = null
        // Accessed only while synchronized on this close, including native transaction submission.
        var submittedFrame: LauncherWindowGeometry.Frame? = null
        var submittedVsync = 0L
    }

    internal class EarlyTarget(val handoff: LauncherDirectClosingFrames.Handoff, val app: SurfaceControl, val window: View)

    private class IconFrame(val surface: SurfaceControl, val projection: LauncherIconPlaneProjection,
        val actual: LauncherIconPlaneProjection.Position)

    private class Reference(
        val frameNanos: Long,
        val observedNanos: Long,
        val vsync: Long,
        val handoff: LauncherDirectClosingFrames.Handoff,
        val app: SurfaceControl,
        val icon: IconFrame?,
        val window: LauncherWindowGeometry.Frame,
        val tracking: LauncherWindowGeometry.Tracking,
        val geometrySeed: LauncherWindowGeometry.SpringSeed?,
        val relayerSeed: LauncherSpringRelayerReplay.Snapshot?,
        val horizontalSeed: LauncherHorizontalSpringState.Snapshot?,
        val workerOffer: LauncherDirectClosingFrames.WorkerOffer?,
    )

    private inner class Trial(val token: Long, player: Any, stock: Any, private val target: RectF,
        val recordWindow: Boolean, relayer: Any?, val targetless: TargetlessClose?, val relayerReplay: LauncherSpringRelayerReplay?) {
        val player = WeakReference(player)
        val stock = WeakReference(stock)
        val stockRelayer = relayer?.let(::WeakReference)
        val cancelled = AtomicBoolean()
        val expire = Runnable { finish(this) }
        var observation: LauncherSpringReplay.Observation? = null
        var stockUpdated = false
        var dispatchingStockClock = false
        var deliveringRelayer = false
        var lastStockTick: LauncherSpringFrameClock.StockTick? = null
        @Volatile var reference: Reference? = null
        @Volatile var rendering: Reference? = null
        @Volatile var liveTracking: LauncherWindowGeometry.Tracking? = null
        // Parity and the playback clock belong to the worker.
        var matched: Reference? = null
        var lastCompared = 0L
        var matchCount = 0
        var geometrySeed: LauncherWindowGeometry.SpringSeed? = null
        var seedReadFailed = false
        var relayerSeedCaptured = false
        var horizontalSamples = 0
        @Volatile var horizontalSynced = false
        var sourceProjection: LauncherIconPlaneProjection? = null
        var projection: LauncherIconPlaneProjection? = null
        // The playback and its clock belong to the extension's worker.
        var playback: LauncherSpringFrameClock.Playback? = null
        var relay: LauncherSpringRelayerReplay.Playback? = null
        var motion: LauncherSpringReplay.Motion? = null
        var calculator: LauncherWindowGeometry.Calculator? = null
        var lastWindow: LauncherWindowGeometry.Frame? = null
        val lastBounds = RectF()
        var lastProgress = 0f
        var seedApplied = false
        var workerFinished = false
        var workerFrameTime = 0L
        /** Owned by the worker since release. Stock frames only report tracking, seed and the icon plane. */
        @Volatile var early = false
        @Volatile var earlyIcon: IconFrame? = null
        fun workerIsDone() = workerFinished && relay?.isRunning() != true
        fun sameTarget(bounds: Rect) = target.left == bounds.left.toFloat() && target.top == bounds.top.toFloat() &&
            target.right == bounds.right.toFloat() && target.bottom == bounds.bottom.toFloat()
    }

    companion object {
        private const val MAX_HANDOFF_AGE_NS = 25_000_000L
        private const val MAX_HORIZONTAL_SAMPLES = 6
        private val STOCK = Labels("stock")
        private val DETACHED = Labels("detached")
        private val STOCK_WINDOW = LauncherWindowFrameTrace("OneUI spring stock window")
        private val DETACHED_WINDOW = LauncherWindowFrameTrace("OneUI spring detached window")

        fun install(app: Application, loader: ClassLoader) {
            val sdk = Build.VERSION.SDK_INT
            if (sdk != 37 || !HookCompatibility.matchesClockBuild(sdk, Build.DISPLAY)) return
            try {
                @Suppress("DEPRECATION")
                val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
                if (HookCompatibility.matchesLauncherVersion(sdk, version)) LauncherSpringReplayProbe(loader, null).installHooks(loader)
            } catch (failure: Exception) {
                HookEntry.log("LAUNCHER_SPRING_REPLAY_INSTALL_FAILED ${failure.javaClass.simpleName}")
            }
        }

        fun forClosingSurfaces(loader: ClassLoader, onRetired: (Any) -> Unit): LauncherSpringReplayProbe =
            LauncherSpringReplayProbe(loader, onRetired).also { it.installHooks(loader) }

        private fun mark(name: String) {
            if (!Trace.isEnabled()) return
            Trace.beginSection(name)
            Trace.endSection()
        }

        private class Labels(host: String) {
            val sample = "OneUI spring $host sample"
            val trial = "OneUI spring $host trial"
            val time = "OneUI spring $host frame time"
            val left = "OneUI spring $host left x100"
            val top = "OneUI spring $host top x100"
            val right = "OneUI spring $host right x100"
            val bottom = "OneUI spring $host bottom x100"
            val progress = "OneUI spring $host progress x1e6"
        }
    }
}
