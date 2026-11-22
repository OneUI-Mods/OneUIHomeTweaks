package dev.ryuya.oneuihometweaks

import java.util.Arrays
import kotlin.jvm.JvmStatic
import kotlin.math.abs
import kotlin.math.pow

/* Contrast of real sRGB pixels; unknown/transparent regions never invent a color. */
object LocalContrast {
    const val BLACK: Int = -0x1000000
    const val WHITE: Int = -0x1

    @JvmStatic /* The same local pixels must settle before replacing a label's last displayed color. */
    fun settled(before: IntArray?, after: IntArray?): Boolean {
        if (before == null || after == null || before.size != after.size || after.size < 8)
            return false
        var difference: Long = 0
        for (i in before.indices) {
            difference += abs((before[i] and 255) - (after[i] and 255))
            difference += abs(((before[i] shr 8) and 255) - ((after[i] shr 8) and 255))
            difference += abs(((before[i] shr 16) and 255) - ((after[i] shr 16) and 255))
        }
        return difference <= after.size * 3L * 4 // Average change at most four sRGB levels/channel.
    }

    private val linear = DoubleArray(256) { component ->
        val value = component / 255.0
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    @JvmStatic
    fun choose(pixels: IntArray, previous: Int?): Int? =
        choose(pixels, previous, DoubleArray(pixels.size))

    internal fun choose(pixels: IntArray, previous: Int?, luminance: DoubleArray): Int? {
        var count: Int = 0
        for (pixel in pixels) {
            if ((pixel ushr 24) < 240) continue
            luminance[count++] =
                0.2126 * linear[(pixel shr 16) and 255] +
                    0.7152 * linear[(pixel shr 8) and 255] +
                    0.0722 * linear[pixel and 255]
        }
        if (count < 8 || count < pixels.size * 0.75) return null
        Arrays.sort(luminance, 0, count)
        // Contrast against white decreases as luminance increases, so read the opposite percentile.
        val lowerFifth = (count - 1) / 5
        val b = (luminance[lowerFifth] + 0.05) / 0.05
        val w = 1.05 / (luminance[count - 1 - lowerFifth] + 0.05)
        // A small margin prevents rapid flips around equal contrast during wallpaper transitions.
        if (previous != null) {
            if (previous == LocalContrast.BLACK && w <= b * 1.12) return LocalContrast.BLACK
            if (previous == LocalContrast.WHITE && b <= w * 1.12) return LocalContrast.WHITE
        }
        return if (b >= w) LocalContrast.BLACK else LocalContrast.WHITE
    }

}
