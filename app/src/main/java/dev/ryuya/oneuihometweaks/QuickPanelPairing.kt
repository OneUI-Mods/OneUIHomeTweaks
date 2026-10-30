package dev.ryuya.oneuihometweaks

import java.util.ArrayList
import java.util.HashSet
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Merge only adjacent bar/companion cells whose union cannot cover another item. */
object QuickPanelPairing {
    @JvmStatic
    fun pairs(cells: MutableList<QuickPanelPairing.Cell?>): MutableList<QuickPanelPairing.Pair?> {
        val result = ArrayList<QuickPanelPairing.Pair?>()
        val used = HashSet<Int?>()
        for (bar in cells) {
            val spec: String? =
                when (bar!!.type) {
                    "BrightBar" -> "UiModeNight"
                    "VolumeBar" -> "SoundMode"
                    else -> null
                }
            if (spec == null || !QuickPanelPairing.valid(bar!!) || used.contains(bar!!.index))
                continue
            for (button in cells) {
                if (
                    ("QuickTile" != button!!.type) ||
                        (spec != button!!.spec) ||
                        !QuickPanelPairing.valid(button!!) ||
                        used.contains(button!!.index)
                )
                    continue
                val below: Boolean =
                    button!!.x == bar!!.x &&
                        button!!.width == bar!!.width &&
                        button!!.y == bar!!.y + bar!!.height
                val right: Boolean =
                    button!!.y == bar!!.y &&
                        button!!.height == bar!!.height &&
                        button!!.x == bar!!.x + bar!!.width
                if (!below && !right) continue
                val width: Int = if (below) bar!!.width else bar!!.width + button!!.width
                val height: Int = if (right) bar!!.height else bar!!.height + button!!.height
                if (width > 4 || height > 4) continue
                var occupied: Boolean = false
                for (other in cells) {
                    if (
                        other!!.index == bar!!.index ||
                            other!!.index == button!!.index ||
                            !QuickPanelPairing.valid(other!!)
                    )
                        continue
                    if (
                        other!!.x < bar!!.x + width &&
                            other!!.x + other!!.width > bar!!.x &&
                            other!!.y < bar!!.y + height &&
                            other!!.y + other!!.height > bar!!.y
                    ) {
                        occupied = true
                        break
                    }
                }
                if (occupied) continue
                result.add(
                    QuickPanelPairing.Pair(
                        bar!!.index,
                        button!!.index,
                        bar!!.x,
                        bar!!.y,
                        width,
                        height,
                    )
                )
                used.add(bar!!.index)
                used.add(button!!.index)
                break
            }
        }
        return result
    }

    @JvmStatic
    private fun valid(cell: QuickPanelPairing.Cell): Boolean {
        return cell.x >= 0 &&
            cell.y >= 0 &&
            cell.width > 0 &&
            cell.height > 0 &&
            cell.x < 100 &&
            cell.y < 100 &&
            cell.width <= 8 &&
            cell.height <= 32
    }

    @JvmRecord
    data class Cell(
        val index: Int,
        val type: String?,
        val spec: String?,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    ) {
        override fun toString(): String =
            "Cell[index=$index, type=$type, spec=$spec, x=$x, y=$y, width=$width, height=$height]"
    }

    @JvmRecord
    data class Pair(
        val bar: Int,
        val button: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    ) {
        override fun toString(): String =
            "Pair[bar=$bar, button=$button, x=$x, y=$y, width=$width, height=$height]"
    }
}
