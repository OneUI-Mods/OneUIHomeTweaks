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

    @JvmStatic
    fun choose(pixels: IntArray, previous: Int?): Int? {
        val black = DoubleArray(pixels.size)
        val white = DoubleArray(pixels.size)
        var count: Int = 0
        for (pixel in pixels) {
            if ((pixel ushr 24) < 240) continue
            val luminance: Double =
                (0.2126 * LocalContrast.linear((pixel shr 16) and 255) +
                    0.7152 * LocalContrast.linear((pixel shr 8) and 255) +
                    0.0722 * LocalContrast.linear(pixel and 255))
            black[count] = (luminance + 0.05) / 0.05
            white[count++] = 1.05 / (luminance + 0.05)
        }
        if (count < 8 || count < pixels.size * 0.75) return null
        Arrays.sort(black, 0, count)
        Arrays.sort(white, 0, count)
        // Favor contrast in the harder fifth of the label area, rather than a single mean color.
        val lowerFifth: Int = (count - 1) / 5
        val b: Double = black[lowerFifth]
        val w: Double = white[lowerFifth]
        // A small margin prevents rapid flips around equal contrast during wallpaper transitions.
        if (previous != null) {
            if (previous == LocalContrast.BLACK && w <= b * 1.12) return LocalContrast.BLACK
            if (previous == LocalContrast.WHITE && b <= w * 1.12) return LocalContrast.WHITE
        }
        return if (b >= w) LocalContrast.BLACK else LocalContrast.WHITE
    }

    @JvmStatic
    private fun linear(component: Int): Double {
        val value: Double = component / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
}
