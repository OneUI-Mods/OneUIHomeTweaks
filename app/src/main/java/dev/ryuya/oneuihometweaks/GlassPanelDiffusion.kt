package dev.ryuya.oneuihometweaks

import android.graphics.Bitmap
import kotlin.math.min

/** Produces the two shared textures on the capture worker. */
internal class GlassPanelDiffusion(private val skipUnchanged: Boolean) {
    data class Backdrops(val diffuse: Bitmap, val bevel: Bitmap)

    private var pixels = IntArray(0)
    private var scratch = IntArray(0)
    private var previous = IntArray(0)
    private var width = 0
    private var height = 0
    private var hasPrevious = false

    fun forgetPrevious() {
        hasPrevious = false
    }

    /** Returns null if the input has exactly the same pixels as the previous frame. */
    fun apply(bitmap: Bitmap): Backdrops? {
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Backdrop software copy failed") else bitmap
        try {
            if (width != software.width || height != software.height) {
                width = software.width
                height = software.height
                pixels = IntArray(width * height)
                scratch = IntArray(pixels.size)
                if (skipUnchanged) previous = IntArray(pixels.size)
                hasPrevious = false
            }
            software.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally {
            if (software !== bitmap) software.recycle()
        }
        if (skipUnchanged) {
            if (hasPrevious && pixels.contentEquals(previous)) return null
            pixels.copyInto(previous)
            hasPrevious = true
        }

        val shortSide = min(width, height)
        val bevelRadius = (shortSide / 44).coerceAtLeast(1)
        blurLines(pixels, scratch, height, width, width, 1, bevelRadius)
        blurLines(scratch, pixels, width, height, 1, width, bevelRadius)
        val bevel = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val radius = (shortSide / 18).coerceAtLeast(1)
        repeat(2) {
            blurLines(pixels, scratch, height, width, width, 1, radius)
            blurLines(scratch, pixels, width, height, 1, width, radius)
        }
        return Backdrops(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), bevel)
    }

    private fun blurLines(
        source: IntArray,
        destination: IntArray,
        lineCount: Int,
        lineLength: Int,
        lineStride: Int,
        pixelStride: Int,
        radius: Int,
    ) {
        val windowSize = 2 * radius + 1
        for (line in 0 until lineCount) {
            val first = line * lineStride
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val pixel = source[first + offset.coerceIn(0, lineLength - 1) * pixelStride]
                red += (pixel ushr 16) and 255
                green += (pixel ushr 8) and 255
                blue += pixel and 255
            }
            for (offset in 0 until lineLength) {
                destination[first + offset * pixelStride] = 0xff000000.toInt() or
                    ((red / windowSize) shl 16) or ((green / windowSize) shl 8) or (blue / windowSize)
                val leaving = source[first + (offset - radius).coerceAtLeast(0) * pixelStride]
                val entering = source[first + (offset + radius + 1).coerceAtMost(lineLength - 1) * pixelStride]
                red += ((entering ushr 16) and 255) - ((leaving ushr 16) and 255)
                green += ((entering ushr 8) and 255) - ((leaving ushr 8) and 255)
                blue += (entering and 255) - (leaving and 255)
            }
        }
    }
}
