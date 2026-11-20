package dev.ryuya.oneuihometweaks

import kotlin.math.abs

internal object DiyWidgetReflowPlan {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top

        fun at(x: Int, y: Int) = Bounds(x, y, x + width, y + height)

        fun expanded(padding: Int) =
            Bounds(left - padding, top - padding, right + padding, bottom + padding)

        fun intersects(other: Bounds): Boolean =
            left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    data class Item(
        val id: Int,
        val saved: Bounds,
        val shown: Bounds = saved,
        val movable: Boolean = false,
    )

    data class Page(
        val id: Int,
        val rank: Int,
        val width: Int,
        val height: Int,
        val widgets: List<Bounds>,
        val items: List<Item>,
    )

    data class Move(val item: Item, val pageId: Int, val bounds: Bounds)

    /** Existing non-overlapping items are anchors. Only icons colliding on the open page move. */
    fun build(source: Page, otherPages: List<Page>, padding: Int): List<Move>? {
        val displaced = source.items.filter { item ->
            item.movable && source.widgets.any { it.intersects(item.saved) || it.intersects(item.shown) }
        }.sortedWith(compareBy({ it.saved.top }, { it.saved.left }, { it.id }))
        if (displaced.isEmpty()) return emptyList()

        val displacedIds = displaced.mapTo(HashSet()) { it.id }
        val pages = listOf(source) + otherPages.sortedWith(
            compareBy({ it.widgets.isNotEmpty() || it.items.isNotEmpty() }, { abs(it.rank - source.rank) }, { it.rank }),
        )
        val occupied = pages.associate { page ->
            val bounds = page.widgets.toMutableList()
            for (item in page.items) {
                if (page === source && item.id in displacedIds) continue
                bounds += item.saved
                if (item.shown != item.saved) bounds += item.shown
            }
            page.id to bounds
        }

        val moves = ArrayList<Move>(displaced.size)
        for (item in displaced.sortedByDescending { it.saved.width.toLong() * it.saved.height }) {
            val move = pages.firstNotNullOfOrNull { page ->
                val blocked = occupied.getValue(page.id)
                val bounds = findSlot(page, blocked, item.saved, padding) ?: return@firstNotNullOfOrNull null
                blocked += bounds
                Move(item, page.id, bounds)
            } ?: return null
            moves += move
        }
        return moves
    }

    private fun findSlot(page: Page, occupied: List<Bounds>, preferred: Bounds, padding: Int): Bounds? {
        val maxX = page.width - preferred.width - padding
        val maxY = page.height - preferred.height - padding
        if (maxX < padding || maxY < padding) return null

        val xPositions = sortedSetOf(padding, maxX, preferred.left.coerceIn(padding, maxX))
        val yPositions = sortedSetOf(padding, maxY, preferred.top.coerceIn(padding, maxY))
        val obstacles = occupied.map { it.expanded(padding) }
        for (obstacle in obstacles) {
            val left = obstacle.left - preferred.width
            val top = obstacle.top - preferred.height
            if (left in padding..maxX) xPositions += left
            if (obstacle.right in padding..maxX) xPositions += obstacle.right
            if (top in padding..maxY) yPositions += top
            if (obstacle.bottom in padding..maxY) yPositions += obstacle.bottom
        }

        var best: Bounds? = null
        var bestDistance = Long.MAX_VALUE
        for (y in yPositions) {
            for (x in xPositions) {
                val dx = x.toLong() - preferred.left
                val dy = y.toLong() - preferred.top
                val distance = dx * dx + dy * dy
                if (distance >= bestDistance) continue
                val candidate = preferred.at(x, y)
                if (obstacles.none(candidate::intersects)) {
                    best = candidate
                    bestDistance = distance
                }
            }
        }
        return best
    }
}
