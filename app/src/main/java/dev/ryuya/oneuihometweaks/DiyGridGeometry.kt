package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmRecord
import kotlin.math.abs

/* A native grid may only be projected into a measured home viewport that can contain it. */
@JvmRecord
data class DiyGridGeometry(
    val columns: Int,
    val rows: Int,
    val cellWidth: Int,
    val cellHeight: Int,
    val offsetX: Int,
    val offsetY: Int,
    val landscape: Boolean,
) {
    fun fits(viewportWidth: Int, viewportHeight: Int): Boolean {
        if (
            landscape ||
                columns < 1 ||
                rows < 1 ||
                columns > 20 ||
                rows > 20 ||
                cellWidth < 1 ||
                cellHeight < 1 ||
                viewportWidth < 1 ||
                viewportHeight <= viewportWidth
        )
            return false

        val insetX = abs((offsetX).toLong())
        val insetY = abs((offsetY).toLong())
        if (insetX > viewportWidth || insetY > viewportHeight) return false
        // Normal home and DIY home can have different margins. Allow their native inset
        // difference, plus at most one pixel of rounding per cell, without swapping axes.
        return (columns).toLong() * cellWidth <= viewportWidth + 2 * insetX + columns &&
            (rows).toLong() * cellHeight <= viewportHeight + 2 * insetY + rows
    }

    override fun toString(): String =
        "DiyGridGeometry[columns=$columns, rows=$rows, cellWidth=$cellWidth, cellHeight=$cellHeight, offsetX=$offsetX, offsetY=$offsetY, landscape=$landscape]"
}
