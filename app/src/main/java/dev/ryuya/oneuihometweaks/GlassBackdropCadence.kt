package dev.ryuya.oneuihometweaks

import kotlin.math.roundToLong

/** Sample on the next frame during motion and space out probes once the image is still. */
internal class GlassBackdropCadence {
    private var identicalFrames = 0

    val isIdle: Boolean get() = identicalFrames >= IDLE_AFTER

    fun reset() {
        identicalFrames = 0
    }

    fun recordIdenticalFrame() {
        identicalFrames = (identicalFrames + 1).coerceAtMost(MAX_IDENTICAL_FRAMES)
    }

    fun delayMillis(refreshRate: Float?): Long {
        if (!isIdle) return 0
        if (refreshRate == null || !refreshRate.isFinite() || refreshRate <= 0f) return MAX_IDLE_PAUSE
        val skippedFrames = 1 shl (identicalFrames - IDLE_AFTER + 1)
        return (1_000f * skippedFrames / refreshRate).roundToLong().coerceAtMost(MAX_IDLE_PAUSE)
    }

    private companion object {
        const val IDLE_AFTER = 3
        const val MAX_IDENTICAL_FRAMES = IDLE_AFTER + 3
        const val MAX_IDLE_PAUSE = 200L
    }
}
