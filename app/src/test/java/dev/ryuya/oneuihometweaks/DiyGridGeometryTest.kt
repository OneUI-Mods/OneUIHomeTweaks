package dev.ryuya.oneuihometweaks

import java.util.List
import java.util.Map
import org.junit.Assert.*
import org.junit.Test

class DiyGridGeometryTest {
    @Test
    fun portraitHomeAcceptsItsMeasuredNativeGrid() {
        assertTrue(DiyGridGeometry(7, 7, 190, 339, 0, 0, false).fits(1330, 2375))
    }

    @Test
    fun observedWideCellsCannotRearrangePortraitIcons() {
        val source =
            List.of<GridSnapPlan.Item?>(
                GridSnapPlan.Item(1, 1149, 2077, 1, 1, 221, -71),
                GridSnapPlan.Item(2, 1149, 2077, 1, 1, 221, -71),
                GridSnapPlan.Item(3, 1149, 2077, 1, 1, 221, -71),
            )
        val unchecked =
            GridSnapPlan.build(7, 7, 394, 117, source, Map.of<Int?, GridSnapPlan.Slot?>())
        assertTrue(
            unchecked!!.values.stream().anyMatch { slot: GridSnapPlan.Slot? -> slot!!.x > 1330 }
        )
        assertFalse(DiyGridGeometry(7, 7, 394, 117, 0, 0, false).fits(1330, 2375))
        assertFalse(DiyGridGeometry(7, 7, 394, 117, 110, -21, false).fits(1330, 2375))
        assertFalse(DiyGridGeometry(7, 7, 394, 117, 58, -77, false).fits(1330, 2375))
    }

    @Test
    fun geometryFromLandscapeIsNeverUsedForPortraitProjection() {
        assertFalse(DiyGridGeometry(7, 7, 190, 339, 0, 0, true).fits(1330, 2375))
        assertFalse(DiyGridGeometry(7, 7, 394, 117, 0, 0, false).fits(2758, 819))
    }

    @Test
    fun nativeMarginDifferencesAndRoundingAreAllowed() {
        assertTrue(DiyGridGeometry(7, 7, 200, 339, -35, 0, false).fits(1330, 2375))
        assertTrue(DiyGridGeometry(7, 7, 191, 340, 0, 0, false).fits(1330, 2375))
        assertFalse(DiyGridGeometry(7, 7, 300, 339, -35, 0, false).fits(1330, 2375))
    }

    @Test
    fun viewportChangesAreCheckedAgainWithoutCachingTheOldAcceptance() {
        val grid = DiyGridGeometry(7, 7, 190, 339, 0, 0, false)
        assertTrue(grid.fits(1330, 2375))
        assertFalse(grid.fits(1000, 2375))
        assertFalse(grid.fits(1330, 900))
        assertTrue(grid.fits(1330, 2375))
    }

    @Test
    fun customGridCountsUseTheirOwnDimensions() {
        assertTrue(DiyGridGeometry(5, 8, 288, 280, 0, 0, false).fits(1440, 2240))
    }

    @Test
    fun unmeasuredOrInvalidGeometryIsNotProjected() {
        assertFalse(DiyGridGeometry(7, 7, 190, 339, 0, 0, false).fits(0, 2375))
        assertFalse(DiyGridGeometry(7, 7, 190, 339, 0, 0, false).fits(1330, 0))
        assertFalse(DiyGridGeometry(0, 7, 190, 339, 0, 0, false).fits(1330, 2375))
        assertFalse(DiyGridGeometry(7, 7, -1, 339, 0, 0, false).fits(1330, 2375))
    }

    @Test
    fun extremeDimensionsAndOffsetsCannotOverflowIntoAcceptance() {
        assertFalse(
            DiyGridGeometry(20, 20, Int.MAX_VALUE, Int.MAX_VALUE, 0, 0, false).fits(1330, 2375)
        )
        assertFalse(
            DiyGridGeometry(7, 7, 190, 339, Int.MIN_VALUE, Int.MIN_VALUE, false).fits(1330, 2375)
        )
    }
}
