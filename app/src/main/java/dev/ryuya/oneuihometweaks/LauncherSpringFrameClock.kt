package dev.ryuya.oneuihometweaks

import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.reflect.Proxy

/** Runs the stock spring scheduler with explicit ticks on the rendering thread. */
@RequiresApi(33)
internal class LauncherSpringFrameClock(loader: ClassLoader) {
    private val handlerType = DexSymbols.findClass("androidx.dynamicanimation.animation.AnimationHandler", loader)
    private val schedulerType = DexSymbols.findClass("androidx.dynamicanimation.animation.FrameCallbackScheduler", loader)
    private val constructor = handlerType.getDeclaredConstructor(schedulerType)
    private val localField = handlerType.compatibleDeclaredField("sAnimatorHandler").apply { isAccessible = true }
    private val animatorLocal get() = readAnimatorLocal()
    val dispatch = handlerType.compatibleDeclaredMethod("doAnimationFrame", Long::class.javaPrimitiveType).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun readAnimatorLocal(): ThreadLocal<Any> = localField.get(null) as ThreadLocal<Any>

    fun isThreadHandler(handler: Any?) = handler != null && animatorLocal.get() === handler

    fun start(motion: LauncherSpringReplay.Motion, relayer: LauncherSpringRelayerReplay.Playback? = null,
        onRelayedFrame: (RectF, Float) -> Unit = { _, _ -> }): Playback =
        start(
            startMotion = {
                relayer?.start()
                motion.start()
            },
            stopMotion = { try { motion.cancel() } finally { relayer?.close() } },
            afterFrame = { relayer?.flush(onRelayedFrame) },
        )

    fun start(startMotion: () -> Unit, stopMotion: () -> Unit, afterFrame: () -> Unit): Playback {
        val owner = checkNotNull(Looper.myLooper())
        val scheduler = Proxy.newProxyInstance(schedulerType.classLoader, arrayOf(schedulerType)) { proxy, method, args ->
            when (method.name) {
                "isCurrentThread" -> Looper.myLooper() === owner
                // Only the controller can request a tick. AndroidX still owns the callback list.
                "postFrameCallback" -> null
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "One UI transition frame scheduler"
                else -> error("Unexpected frame scheduler method ${method.name}")
            }
        }
        val playback = Playback(owner, constructor.newInstance(scheduler), stopMotion, afterFrame)
        try {
            playback.start(startMotion)
        } catch (failure: Exception) {
            try { playback.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
        return playback
    }

    internal data class StockTick(val frameNanos: Long, val uptimeMillis: Long, val vsyncId: Long = 0L)

    internal inner class Playback(
        private val owner: Looper,
        private val handler: Any,
        private val stopMotion: () -> Unit,
        private val afterFrame: () -> Unit,
    ) {
        private var previous: StockTick? = null
        private var choreographer: Choreographer? = null
        private var pulse: Choreographer.VsyncCallback? = null
        private var stopDisplay: (() -> Unit)? = null
        private var closed = false

        internal fun start(startMotion: () -> Unit) = inHandler(startMotion)

        fun mirror(tick: StockTick): Boolean {
            check(pulse == null)
            return advance(tick)
        }

        fun rebase(frameNanos: Long, action: () -> Unit): Boolean {
            if (closed || pulse != null || previous?.frameNanos != frameNanos) return false
            inHandler(action)
            return true
        }

        /** Continue on display, optionally holding the queued stock pose until the first display tick. */
        fun continueOnDisplay(
            onFrame: (vsyncId: Long) -> Boolean,
            onFailure: (Exception) -> Unit,
            onStopped: () -> Unit = {},
            alignToFirstDisplayTick: Boolean = false,
            startAt: StockTick? = null,
        ): Boolean {
            check(Looper.myLooper() === owner)
            // A close claimed at release has no stock tick yet. It starts from the release time on the display clock.
            val anchor = (if (previous == null) startAt else previous) ?: return false
            if (closed || pulse != null) return false
            val display = Choreographer.getInstance()
            val callback = object : Choreographer.VsyncCallback {
                private var displayAnchorNanos = if (alignToFirstDisplayTick) null else anchor.frameNanos

                override fun onVsync(frame: Choreographer.FrameData) {
                    if (pulse !== this) return
                    try {
                        val displayAnchor = displayAnchorNanos
                        if (displayAnchor == null) {
                            displayAnchorNanos = frame.frameTimeNanos
                            display.postVsyncCallback(this)
                            return
                        }
                        val elapsed = frame.frameTimeNanos - displayAnchor
                        val tick = StockTick(anchor.frameNanos + elapsed, anchor.uptimeMillis + elapsed / NANOS_PER_MILLI)
                        val advanced = advance(tick)
                        if (pulse !== this) return
                        val keepDriving = !advanced || onFrame(frame.preferredFrameTimeline.vsyncId)
                        if (pulse === this) {
                            if (keepDriving) display.postVsyncCallback(this) else close()
                        }
                    } catch (failure: Exception) {
                        try {
                            close()
                        } finally {
                            onFailure(failure)
                        }
                    }
                }
            }
            choreographer = display
            pulse = callback
            stopDisplay = onStopped
            try {
                display.postVsyncCallback(callback)
            } catch (failure: Exception) {
                pulse = null
                choreographer = null
                stopDisplay = null
                throw failure
            }
            return true
        }

        private fun advance(tick: StockTick): Boolean {
            check(Looper.myLooper() === owner)
            val last = previous
            if (closed || tick.uptimeMillis <= 0 || tick.frameNanos <= 0 ||
                (last != null && (tick.frameNanos <= last.frameNanos || tick.uptimeMillis < last.uptimeMillis))
            ) return false
            previous = tick
            inHandler {
                dispatch.invoke(handler, tick.uptimeMillis)
                afterFrame()
            }
            return true
        }

        fun close() {
            check(Looper.myLooper() === owner)
            if (closed) return
            closed = true
            val callback = pulse
            val display = choreographer
            val stopped = stopDisplay
            pulse = null
            choreographer = null
            stopDisplay = null
            try {
                if (callback != null) display?.removeVsyncCallback(callback)
                inHandler {
                    stopMotion()
                    // Stock cancel asks some springs to end on their next sample.
                    val lastTime = previous?.uptimeMillis ?: SystemClock.uptimeMillis()
                    dispatch.invoke(handler, lastTime + 1)
                }
            } finally {
                stopped?.invoke()
            }
        }

        private inline fun <T> inHandler(block: () -> T): T {
            check(Looper.myLooper() === owner)
            val local = animatorLocal
            val prior = local.get()
            local.set(handler)
            try {
                return block()
            } finally {
                if (prior == null) local.remove() else local.set(prior)
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
