package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmStatic

/* Pure policy: unknown colors and unsupported context always use the original value. */
object ColorPolicy {
    const val AUTO: Int = 0
    const val BLACK: Int = 1
    const val WHITE: Int = 2
    const val OFF: Int = 3
    const val LOCAL: Int = 4

    @JvmStatic
    fun darkValue(mode: Int, supportedWallpaper: Boolean, hints: Int?): Int? {
        if (!supportedWallpaper || mode == ColorPolicy.OFF) return null
        if (mode == ColorPolicy.BLACK) return 1
        if (mode == ColorPolicy.WHITE) return 0
        if (mode != ColorPolicy.AUTO || hints == null) return null
        return if ((hints and 1) != 0) 1 else 0
    }

    @JvmStatic
    fun isMainHomeEvent(which: Int): Boolean {
        val mode: Int = which and 60
        return (which and 1) != 0 && (mode == 0 || mode == 4)
    }
}
