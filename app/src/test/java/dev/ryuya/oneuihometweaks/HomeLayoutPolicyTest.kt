package dev.ryuya.oneuihometweaks

import org.junit.Assert.*
import org.junit.Test

class HomeLayoutPolicyTest {
    @Test
    fun rejectsObservedOverviewHandoffEvenWithStablePortraitMeasurements() {
        // 19:03:59: both activities may briefly report resumed during the handoff.
        assertFalse(HomeLayoutPolicy.canApply(true, true, true, true))
        // 19:04:02: portrait Recents is resumed and the Home page still measures 1330x2375.
        assertFalse(HomeLayoutPolicy.canApply(false, true, false, true))
        assertFalse(HomeLayoutPolicy.canApply(true, false, false, true))
        assertFalse(HomeLayoutPolicy.canApply(true, false, true, false))
        assertTrue(HomeLayoutPolicy.canApply(true, false, true, true))
    }

    @Test
    fun rejectsObservedPortraitWithLandscapeCameraHole() {
        assertFalse(HomeLayoutPolicy.consistent(1440, 3120, 139, 0, 0, 0))
        assertFalse(HomeLayoutPolicy.consistent(1440, 3120, 0, 0, 139, 0))
        assertTrue(HomeLayoutPolicy.consistent(1440, 3120, 0, 139, 0, 0))
    }

    @Test
    fun acceptsAllFourSettledRotations() {
        assertTrue(HomeLayoutPolicy.consistent(1440, 3120, 0, 139, 0, 0))
        assertTrue(HomeLayoutPolicy.consistent(1440, 3120, 0, 0, 0, 139))
        assertTrue(HomeLayoutPolicy.consistent(3120, 1440, 139, 0, 0, 0))
        assertTrue(HomeLayoutPolicy.consistent(3120, 1440, 0, 0, 139, 0))
        assertFalse(HomeLayoutPolicy.consistent(3120, 1440, 0, 139, 0, 0))
    }

    @Test
    fun rejectsMixedWindowMetricsEvenWithPlausibleCutout() {
        assertFalse(HomeLayoutPolicy.sameBounds(1440, 3120, 3120, 1440))
        assertFalse(HomeLayoutPolicy.sameBounds(1440, 3120, 1301, 3120))
        assertTrue(HomeLayoutPolicy.sameBounds(1440, 3120, 1440, 3120))
        assertFalse(HomeLayoutPolicy.consistent(0, 0, 0, 0, 0, 0))
    }
}
