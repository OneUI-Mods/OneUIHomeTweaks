package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmStatic

/* Pure geometry checks for the reviewed, centered-camera Galaxy phone profile. */
object HomeLayoutPolicy {
    const val SETTLE_MS: Long = 750
    const val MAX_WAIT_MS: Long = 10000

    @JvmStatic
    fun consistent(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        if (width <= 0 || height <= 0 || width == height) return false
        return if (width < height) left == 0 && right == 0 else top == 0 && bottom == 0
    }

    @JvmStatic /*
                * Recents may be resumed in a different orientation while Home still has portrait measurements.
                */
    fun canApply(
        homeResumed: Boolean,
        recentsResumed: Boolean,
        focused: Boolean,
        stable: Boolean,
    ): Boolean {
        return homeResumed && !recentsResumed && focused && stable
    }

    @JvmStatic
    fun sameBounds(width: Int, height: Int, otherWidth: Int, otherHeight: Int): Boolean {
        return width > 0 && height > 0 && width == otherWidth && height == otherHeight
    }
}
