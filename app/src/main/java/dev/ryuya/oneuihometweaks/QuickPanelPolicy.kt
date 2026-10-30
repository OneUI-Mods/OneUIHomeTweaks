package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min

/* Limits are independent of density, rotation, and the two firmware implementations. */
object QuickPanelPolicy {
    @JvmField
    val KEYS: Array<String?>? =
        arrayOf<String?>("qs_brightness", "qs_volume", "qs_dark", "qs_sound")
    const val MIN: Int = 25
    const val MAX: Int = 200
    const val STEP: Int = 5

    @JvmStatic
    fun percent(value: Int): Int {
        return max(QuickPanelPolicy.MIN, min(QuickPanelPolicy.MAX, value))
    }

    @JvmStatic
    fun dimension(nativeDp: Float, percent: Int): Float {
        if (!java.lang.Float.isFinite(nativeDp) || nativeDp <= 0) return nativeDp
        return nativeDp * QuickPanelPolicy.percent(percent) / 100f
    }

    @JvmStatic
    fun legacyDimension(nativeDp: Float, percent: Int): Float {
        return QuickPanelPolicy.dimension(nativeDp, percent)
    }

    @JvmStatic
    fun thickness(size: Long, vertical: Boolean, ratio: Float): Long {
        var width = java.lang.Float.intBitsToFloat((size ushr 32).toInt())
        var height = java.lang.Float.intBitsToFloat(size.toInt())
        if (
            !java.lang.Float.isFinite(width) ||
                !java.lang.Float.isFinite(height) ||
                width <= 0 ||
                height <= 0 ||
                !java.lang.Float.isFinite(ratio) ||
                ratio <= 0
        )
            return size
        if (vertical) width *= ratio else height *= ratio
        return ((java.lang.Float.floatToRawIntBits(width).toLong() shl 32) or
            (java.lang.Float.floatToRawIntBits(height).toLong() and 0xffffffffL))
    }
}
