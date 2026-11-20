package dev.ryuya.oneuihometweaks

import dev.ryuya.oneuihometweaks.DiyWidgetReflowPlan.Bounds
import dev.ryuya.oneuihometweaks.DiyWidgetReflowPlan.Item
import dev.ryuya.oneuihometweaks.DiyWidgetReflowPlan.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiyWidgetReflowPlanTest {
    @Test
    fun existingOverlapMovesAtCurrentSizeWithOverflowOnlyAfterSourceFills() {
        val widgets = listOf(
            Bounds(8, 62, 1296, 746), Bounds(20, 832, 925, 1031),
            Bounds(-21, 1089, 884, 1288), Bounds(913, 775, 1395, 1061),
        )
        val items = (0 until 42).map { index ->
            val x = 9 + (index % 7) * 190
            val y = 411 + (index / 7) * 320
            Item(index, Bounds(x, y, x + 171, y + 218), movable = index < 27)
        }
        val source = Page(35, 1, 1330, 2375, widgets, items)
        val targetAnchor = Item(42, Bounds(6, 6, 177, 224))
        val target = Page(38, 3, 1330, 2375, emptyList(), listOf(targetAnchor))
        val moves = requireNotNull(DiyWidgetReflowPlan.build(source, listOf(target), 6))
        val expected = items.filter { it.movable && widgets.any(it.saved::intersects) }.map { it.id }.toSet()
        assertEquals(expected, moves.map { it.item.id }.toSet())
        assertTrue(moves.any { it.pageId == source.id })
        assertTrue(moves.any { it.pageId == target.id })
        for (move in moves) {
            assertEquals(move.item.saved.width, move.bounds.width)
            assertEquals(move.item.saved.height, move.bounds.height)
            val forbidden = if (move.pageId == source.id) {
                widgets + items.filter { it.id !in expected }.map { it.saved }
            } else listOf(targetAnchor.saved)
            assertFalse(forbidden.any(move.bounds::intersects))
            assertFalse(moves.any { it !== move && it.pageId == move.pageId && it.bounds.intersects(move.bounds) })
        }
    }

    @Test
    fun keepsFoldersAndWidgetsAndDoesNotPartiallyMoveIconsWhenThereIsNoRoom() {
        val full = Bounds(0, 0, 20, 20)
        val icon = Item(10, Bounds(5, 5, 15, 15), movable = true)
        val folder = Item(11, Bounds(3, 3, 13, 13))
        val page = Page(1, 0, 20, 20, listOf(full), listOf(icon, folder))
        assertNull(DiyWidgetReflowPlan.build(page, emptyList(), 1))
        val target = Page(2, 1, 20, 20, emptyList(), emptyList())
        val move = requireNotNull(DiyWidgetReflowPlan.build(page, listOf(target), 1)).single()
        assertEquals(icon, move.item)
        assertEquals(target.id, move.pageId)
    }

    @Test
    fun usesAnEmptyPageBeforeAddingToAnExistingLayout() {
        val full = Bounds(0, 0, 60, 60)
        val icon = Item(10, Bounds(5, 5, 15, 15), movable = true)
        val source = Page(1, 0, 60, 60, listOf(full), listOf(icon))
        val nearby = Page(2, 1, 60, 60, emptyList(), listOf(Item(11, Bounds(35, 35, 45, 45))))
        val empty = Page(3, 2, 60, 60, emptyList(), emptyList())
        val move = requireNotNull(DiyWidgetReflowPlan.build(source, listOf(nearby, empty), 1)).single()
        assertEquals(empty.id, move.pageId)
        assertEquals(icon.saved, move.bounds)
    }

    @Test
    fun repairsAnOverlapCausedByTheRenderedPosition() {
        val widget = Bounds(0, 0, 40, 40)
        val icon = Item(7, Bounds(42, 4, 62, 24), Bounds(35, 4, 55, 24), movable = true)
        val page = Page(1, 0, 100, 100, listOf(widget), listOf(icon))
        val move = requireNotNull(DiyWidgetReflowPlan.build(page, emptyList(), 2)).single()
        assertEquals(icon.saved, move.bounds)
    }
}
