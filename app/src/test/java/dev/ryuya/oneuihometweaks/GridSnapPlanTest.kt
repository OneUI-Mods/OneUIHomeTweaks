package dev.ryuya.oneuihometweaks

import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.List
import java.util.Map
import java.util.Random
import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test

class GridSnapPlanTest {
    @Test
    fun layoutsAreBoundedUniqueAndIndependentOfInputOrdering() {
        val random = Random(917)
        for (run in 0..99) {
            val items = ArrayList<GridSnapPlan.Item?>()
            for (id in 0..29) items.add(
                GridSnapPlanTest.item(id, random.nextInt(2000) - 200, random.nextInt(2800) - 200)
            )
            val first =
                GridSnapPlan.build(7, 7, 190, 339, items, Map.of<Int?, GridSnapPlan.Slot?>())
            assertNotNull(first)
            val cells = HashSet<String?>()
            for (slot in first!!.values) {
                assertTrue(slot!!.column >= 0 && slot!!.column < 7)
                assertTrue(slot!!.row >= 0 && slot!!.row < 7)
                assertTrue(cells.add("${slot!!.column}:${slot!!.row}"))
                assertEquals(9 + 190 * slot!!.column, slot!!.x)
                assertEquals(61 + 339 * slot!!.row, slot!!.y)
            }
            Collections.shuffle(items, random)
            assertEquals(
                first,
                GridSnapPlan.build(7, 7, 190, 339, items, Map.of<Int?, GridSnapPlan.Slot?>()),
            )
        }
    }

    @Test
    fun usesNativeGridGeometryRatherThanScreenFractions() {
        val plan =
            GridSnapPlan.build(
                7,
                7,
                190,
                339,
                List.of<GridSnapPlan.Item?>(GridSnapPlanTest.item(1, 210, 420)),
                Map.of<Int?, GridSnapPlan.Slot?>(),
            )
        assertEquals(199, plan!!.get(1)!!.x)
        assertEquals(400, plan!!.get(1)!!.y)
    }

    @Test
    fun duplicateNearbyIconsGetSeparateSlots() {
        val plan =
            GridSnapPlan.build(
                2,
                2,
                100,
                100,
                List.of<GridSnapPlan.Item?>(
                    GridSnapPlanTest.item(1, 9, 61),
                    GridSnapPlanTest.item(2, 10, 62),
                ),
                Map.of<Int?, GridSnapPlan.Slot?>(),
            )
        assertNotEquals(
            plan!!.get(1)!!.column + 2 * plan!!.get(1)!!.row,
            plan!!.get(2)!!.column + 2 * plan!!.get(2)!!.row,
        )
    }

    @Test
    fun unchangedIconsStayPutWhenAnotherMoves() {
        val first =
            GridSnapPlan.build(
                3,
                2,
                100,
                100,
                List.of<GridSnapPlan.Item?>(
                    GridSnapPlanTest.item(1, 9, 61),
                    GridSnapPlanTest.item(2, 109, 61),
                ),
                Map.of<Int?, GridSnapPlan.Slot?>(),
            )
        val second =
            GridSnapPlan.build(
                3,
                2,
                100,
                100,
                List.of<GridSnapPlan.Item?>(
                    GridSnapPlanTest.item(1, 109, 61),
                    GridSnapPlanTest.item(2, 109, 61),
                ),
                first!!,
            )
        assertEquals(first!!.get(2), second!!.get(2))
        assertNotEquals(second!!.get(1)!!.column, second!!.get(2)!!.column)
    }

    @Test
    fun foldersReserveAllTheirCellsAndOverflowIsAtomic() {
        val large = GridSnapPlan.Item(1, 9, 61, 2, 2, 9, 61)
        assertNull(
            GridSnapPlan.build(
                2,
                2,
                100,
                100,
                List.of<GridSnapPlan.Item?>(large, GridSnapPlanTest.item(2, 9, 61)),
                Map.of<Int?, GridSnapPlan.Slot?>(),
            )
        )
        assertNull(
            GridSnapPlan.build(
                0,
                7,
                100,
                100,
                java.util.List.of<GridSnapPlan.Item?>(),
                Map.of<Int?, GridSnapPlan.Slot?>(),
            )
        )
        assertNull(
            GridSnapPlan.build(
                2,
                2,
                100,
                100,
                List.of<GridSnapPlan.Item?>(
                    GridSnapPlanTest.item(1, 9, 61),
                    GridSnapPlanTest.item(1, 10, 62),
                ),
                Map.of<Int?, GridSnapPlan.Slot?>(),
            )
        )
    }

    @Test
    fun userMoveUsesNearestFreeCellAndKeepsOtherIcons() {
        val fixed =
            GridSnapPlan.build(
                3,
                2,
                100,
                100,
                List.of<GridSnapPlan.Item?>(
                    GridSnapPlanTest.item(1, 9, 61),
                    GridSnapPlanTest.item(2, 109, 61),
                ),
                Map.of<Int?, GridSnapPlan.Slot?>(),
            )
        val moved =
            GridSnapPlan.move(3, 2, 100, 100, GridSnapPlanTest.item(1, 190, 70)!!, fixed!!.values)
        assertEquals(2, moved!!.column)
        assertEquals(0, moved!!.row)
        assertEquals(109, fixed!!.get(2)!!.x)
        assertEquals(
            0,
            GridSnapPlan.move(
                    3,
                    2,
                    100,
                    100,
                    GridSnapPlanTest.item(1, -10000, -10000)!!,
                    fixed!!.values,
                )!!
                .column,
        )
    }

    @Test
    fun movingFolderCannotOverlapOccupiedCellsOrLeaveGrid() {
        val fixed =
            GridSnapPlan.build(
                3,
                2,
                100,
                100,
                List.of<GridSnapPlan.Item?>(GridSnapPlanTest.item(2, 209, 61)),
                Map.of<Int?, GridSnapPlan.Slot?>(),
            )
        val folder = GridSnapPlan.Item(1, 999, 999, 2, 2, 9, 61)
        val moved = GridSnapPlan.move(3, 2, 100, 100, folder, fixed!!.values)
        assertEquals(0, moved!!.column)
        assertEquals(0, moved!!.row)
        assertNull(
            GridSnapPlan.move(1, 1, 100, 100, folder, java.util.List.of<GridSnapPlan.Slot?>())
        )
    }

    companion object {
        @JvmStatic
        private fun item(id: Int, x: Int, y: Int): GridSnapPlan.Item? {
            return GridSnapPlan.Item(id, x, y, 1, 1, 9, 61)
        }
    }
}
