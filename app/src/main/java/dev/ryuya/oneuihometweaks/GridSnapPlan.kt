package dev.ryuya.oneuihometweaks

import java.util.ArrayList
import java.util.Comparator
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.function.ToIntFunction
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Plans a page atomically: no duplicate cells, no moving widgets, no partial overflow plans. */
object GridSnapPlan {
    @JvmStatic /* A user move reserves every other icon's current slot, including large folders. */
    fun move(
        columns: Int,
        rows: Int,
        width: Int,
        height: Int,
        item: GridSnapPlan.Item,
        occupiedSlots: MutableCollection<GridSnapPlan.Slot?>,
    ): GridSnapPlan.Slot? {
        if (
            columns < 1 ||
                rows < 1 ||
                columns > 20 ||
                rows > 20 ||
                width <= 0 ||
                height <= 0 ||
                item.spanX < 1 ||
                item.spanY < 1 ||
                item.spanX > columns ||
                item.spanY > rows
        )
            return null
        val occupied = Array<BooleanArray?>(rows) { BooleanArray(columns) }
        for (other in occupiedSlots) {
            if (other!!.source!!.id == item.id) continue
            if (!GridSnapPlan.free(occupied, other!!.column, other!!.row, other!!.source!!))
                return null
            GridSnapPlan.occupy(occupied, other!!)
        }
        var best: GridSnapPlan.Slot? = null
        var distance: Double = Double.POSITIVE_INFINITY
        for (row in 0..rows - item.spanY) for (column in 0..columns - item.spanX) {
            if (!GridSnapPlan.free(occupied, column, row, item)) continue
            val candidate = GridSnapPlan.slot(item, column, row, width, height)
            val dx: Double = (candidate!!.x).toDouble() - item.x
            val dy: Double = (candidate!!.y).toDouble() - item.y
            val next: Double = dx * dx + dy * dy
            if (next < distance) {
                distance = next
                best = candidate
            }
        }
        return best
    }

    @JvmStatic
    fun build(
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
        items: MutableList<GridSnapPlan.Item?>?,
        previous: MutableMap<Int?, GridSnapPlan.Slot?>,
    ): MutableMap<Int?, GridSnapPlan.Slot?>? {
        if (
            columns < 1 ||
                rows < 1 ||
                columns > 20 ||
                rows > 20 ||
                cellWidth <= 0 ||
                cellHeight <= 0
        )
            return null
        val occupied = Array<BooleanArray?>(rows) { BooleanArray(columns) }
        val result = LinkedHashMap<Int?, GridSnapPlan.Slot?>()
        val pending = ArrayList<GridSnapPlan.Item?>(items)
        val ids = HashSet<Int?>()
        for (item in pending) if (
            !ids.add(item!!.id) ||
                item!!.spanX < 1 ||
                item!!.spanY < 1 ||
                item!!.spanX > columns ||
                item!!.spanY > rows
        )
            return null
        pending.sortWith(Comparator.comparingInt<GridSnapPlan.Item?>(GridSnapPlan.Item::id))
        // Keep unchanged placements stable when another item moves, is added or is removed.
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val old = previous.get(item!!.id)
            if (
                old != null &&
                    item == old.source &&
                    GridSnapPlan.free(occupied, old.column, old.row, item!!)
            ) {
                val slot = GridSnapPlan.slot(item!!, old.column, old.row, cellWidth, cellHeight)
                result.put(item!!.id, slot)
                GridSnapPlan.occupy(occupied, slot!!)
                iterator.remove()
            }
        }
        // Large folders first, then stable IDs. A full page fails closed instead of overlapping
        // icons.
        pending.sortWith(
            Comparator.comparingInt<GridSnapPlan.Item?>(
                    ToIntFunction { i: GridSnapPlan.Item? -> -(i!!.spanX * i!!.spanY) }
                )
                .thenComparingInt(GridSnapPlan.Item::id)
        )
        for (item in pending) {
            var best: GridSnapPlan.Slot? = null
            var distance: Double = Double.POSITIVE_INFINITY
            for (row in 0..rows - item!!.spanY) for (column in 0..columns - item!!.spanX) {
                if (!GridSnapPlan.free(occupied, column, row, item!!)) continue
                val candidate = GridSnapPlan.slot(item!!, column, row, cellWidth, cellHeight)
                val dx: Double = (candidate!!.x).toDouble() - item!!.x
                val dy: Double = (candidate!!.y).toDouble() - item!!.y
                val next: Double = dx * dx + dy * dy
                if (next < distance) {
                    distance = next
                    best = candidate
                }
            }
            if (best == null) return null
            result.put(item!!.id, best)
            GridSnapPlan.occupy(occupied, best)
        }
        return result
    }

    @JvmStatic
    private fun slot(
        item: GridSnapPlan.Item,
        column: Int,
        row: Int,
        width: Int,
        height: Int,
    ): GridSnapPlan.Slot? {
        return GridSnapPlan.Slot(
            column,
            row,
            item.offsetX + column * width,
            item.offsetY + row * height,
            item,
        )
    }

    @JvmStatic
    private fun free(
        occupied: Array<BooleanArray?>,
        column: Int,
        row: Int,
        item: GridSnapPlan.Item,
    ): Boolean {
        if (
            column < 0 ||
                row < 0 ||
                row + item.spanY > occupied.size ||
                column + item.spanX > occupied[0]!!.size
        )
            return false
        for (y in row until row + item.spanY) for (x in column until column + item.spanX) if (
            occupied!![y]!![x]
        )
            return false
        return true
    }

    @JvmStatic
    private fun occupy(occupied: Array<BooleanArray?>, slot: GridSnapPlan.Slot) {
        for (y in slot.row until slot.row + slot.source!!.spanY) for (x in
            slot.column until slot.column + slot.source!!.spanX) occupied!![y]!![x] = true
    }

    @JvmRecord
    data class Item(
        val id: Int,
        val x: Int,
        val y: Int,
        val spanX: Int,
        val spanY: Int,
        val offsetX: Int,
        val offsetY: Int,
    ) {
        override fun toString(): String =
            "Item[id=$id, x=$x, y=$y, spanX=$spanX, spanY=$spanY, offsetX=$offsetX, offsetY=$offsetY]"
    }

    @JvmRecord
    data class Slot(
        val column: Int,
        val row: Int,
        val x: Int,
        val y: Int,
        val source: GridSnapPlan.Item?,
    ) {
        override fun toString(): String =
            "Slot[column=$column, row=$row, x=$x, y=$y, source=$source]"
    }
}
