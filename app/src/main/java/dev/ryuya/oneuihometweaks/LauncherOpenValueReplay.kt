package dev.ryuya.oneuihometweaks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.TimeInterpolator
import android.content.Context
import android.os.Looper
import android.view.Choreographer
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Builds the stock icon-opening animators with a private AnimatorSet, input, and output. */
internal class LauncherOpenValueReplay(loader: ClassLoader, private val geometry: LauncherWindowGeometry,
    input: Class<*>, output: Class<*>, params: Class<*>) {
    private val valuePlayer = DexSymbols.findClass("com.honeyspace.transition.anim.floating.animator.ValueAnimPlayer", loader)
    private val tracking = DexSymbols.findClass("com.honeyspace.transition.anim.floating.entity.TargetTrackingData", loader)
    private val newTracking = tracking.getDeclaredConstructor()
    private val callback = DexSymbols.findClass("kotlin.jvm.functions.Function1", loader)
    private val resultUnit = DexSymbols.findClass("kotlin.Unit", loader).getField("INSTANCE").get(null)
    private val newPlayer = valuePlayer.getDeclaredConstructor(Context::class.java, input, output, params,
        IntArray::class.java, tracking, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    val play = valuePlayer.compatibleDeclaredMethod("play", callback)
    private val firstPlatformFrame = runCatching {
        AnimatorSet::class.java.compatibleDeclaredField("mFirstFrame").apply { isAccessible = true }
    }.getOrNull()
    private val lastPlatformFrame = runCatching {
        AnimatorSet::class.java.compatibleDeclaredField("mLastFrameTime").apply { isAccessible = true }
    }.getOrNull()

    fun wrapStockCallback(stock: Any, runFrame: (() -> Any?) -> Any?): StockCallback? {
        if (!callback.isInstance(stock) || firstPlatformFrame == null || lastPlatformFrame == null) return null
        return StockCallback(callback, stock, runFrame)
    }

    fun stockTick(animator: AnimatorSet, frameNanos: Long): Tick? {
        val stamp = lastPlatformFrame?.getLong(animator) ?: return null
        return Tick(frameNanos, stamp).takeIf { stamp > 0 && stamp == frameNanos / NANOS_PER_MILLI }
    }

    fun stockOrigin(animator: AnimatorSet, tick: Tick): Tick? {
        val stamp = firstPlatformFrame?.getLong(animator) ?: return null
        if (stamp !in 1..tick.platformMillis) return null
        return if (stamp == tick.platformMillis) tick else Tick(stamp * NANOS_PER_MILLI, stamp)
    }

    internal class StockCallback(type: Class<*>, stock: Any, runFrame: (() -> Any?) -> Any?) : AutoCloseable {
        @Volatile private var interception: ((() -> Any?) -> Any?)? = runFrame

        val replacement: Any = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    fun proceed(): Any? {
                        return try {
                            method.invoke(stock, *checkNotNull(args))
                        } catch (failure: InvocationTargetException) {
                            throw failure.targetException
                        }
                    }
                    val wrapper = interception
                    if (wrapper == null) proceed() else wrapper(::proceed)
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> stock.toString()
                else -> error("Unexpected stock opening callback ${method.name}")
            }
        }

        override fun close() {
            interception = null
        }
    }

    fun prepare(context: Context, snapshot: LauncherWindowGeometry.Snapshot, timing: Timing,
        expectedChildren: Int, onFrame: (LauncherWindowGeometry.Frame) -> Unit, onEnd: () -> Unit): Playback? {
        val set = AnimatorSet()
        val data = geometry.copyForAnimation(snapshot, set)
        val player = newPlayer.newInstance(context, data.input, data.output, data.params, data.screen, newTracking.newInstance(), false, false)
        val apply = Proxy.newProxyInstance(callback.classLoader, arrayOf(callback)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    onFrame(geometry.readOutput(data.output))
                    resultUnit
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "One UI opening frame listener"
                else -> null
            }
        }
        play.invoke(player, apply)
        if (set.childAnimations.size != expectedChildren || expectedChildren == 0) return null
        set.startDelay = timing.startDelay
        if (timing.duration >= 0) set.duration = timing.duration
        if (timing.interpolator != null) set.interpolator = timing.interpolator
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = onEnd()
        })
        return Playback(set) { geometry.readOpeningIcon(data.output) }
    }

    internal class Timing(val startDelay: Long, val duration: Long, val interpolator: TimeInterpolator?) {
        constructor(reference: AnimatorSet) : this(reference.startDelay, reference.duration, reference.interpolator)
    }

    internal data class Tick(val nanos: Long, val platformMillis: Long) {
        fun platformTimeAt(frameNanos: Long) = platformMillis + frameNanos / NANOS_PER_MILLI - nanos / NANOS_PER_MILLI
    }

    internal class Playback(private val animator: AnimatorSet, val openingIcon: () -> LauncherOpeningIconProjection.State) {
        private val owner = checkNotNull(Looper.myLooper())
        private var lastTick: Tick? = null
        private var stopVsync: (() -> Unit)? = null

        fun startFrameDriven() { startWithoutPulsing.invoke(animator, false) }

        fun advance(tick: Tick): Boolean {
            check(Looper.myLooper() === owner)
            val previous = lastTick
            if (!animator.isStarted || (previous != null && (tick.nanos <= previous.nanos ||
                    tick.platformMillis < previous.platformMillis))) return false
            lastTick = tick
            pulse.invoke(animator, tick.platformMillis)
            return true
        }

        @RequiresApi(33)
        fun continueOnDisplay(
            onFrame: (Long) -> Boolean,
            onFailure: (Exception) -> Unit,
            onStopped: () -> Unit = {},
        ): Boolean {
            check(Looper.myLooper() === owner)
            val anchor = lastTick ?: return false
            if (!animator.isStarted || stopVsync != null) return false
            val clock = Choreographer.getInstance()
            lateinit var stop: () -> Unit
            val callback = object : Choreographer.VsyncCallback {
                override fun onVsync(frame: Choreographer.FrameData) {
                    if (stopVsync !== stop) return
                    try {
                        val time = frame.frameTimeNanos
                        val advanced = advance(Tick(time, anchor.platformTimeAt(time)))
                        if (stopVsync !== stop) return
                        val keepDriving = !advanced || onFrame(frame.preferredFrameTimeline.vsyncId)
                        if (stopVsync === stop) {
                            if (keepDriving && animator.isStarted) clock.postVsyncCallback(this) else stopDisplayDrive()
                        }
                    } catch (failure: Exception) {
                        try {
                            cancel()
                        } finally {
                            onFailure(failure)
                        }
                    }
                }
            }
            stop = {
                try {
                    clock.removeVsyncCallback(callback)
                } finally {
                    onStopped()
                }
            }
            stopVsync = stop
            try {
                clock.postVsyncCallback(callback)
            } catch (failure: Exception) {
                stopVsync = null
                throw failure
            }
            return true
        }

        fun cancel() {
            stopDisplayDrive()
            animator.cancel()
        }

        fun stopDisplayDrive() {
            check(Looper.myLooper() === owner)
            val stop = stopVsync
            stopVsync = null
            stop?.invoke()
        }

        val frameTick: Tick? get() = lastTick

        companion object {
            val pulse = AnimatorSet::class.java.compatibleDeclaredMethod("doAnimationFrame", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            private val startWithoutPulsing = AnimatorSet::class.java.compatibleDeclaredMethod(
                "startWithoutPulsing", Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
