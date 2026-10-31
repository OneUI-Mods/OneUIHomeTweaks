package dev.ryuya.oneuihometweaks

import org.junit.Assert.*
import org.junit.Test

open class NavigationPolicyTest {
    @Test
    open fun buttonsAndUnknownModesNeverHidden() {
        for (mode in intArrayOf(-1, 0, 1, 4, 99)) assertFalse(
            NavigationPolicy.hide(true, mode, 1, true)
        )
    }

    @Test
    open fun bothSamsungGestureModesSupported() {
        assertTrue(NavigationPolicy.hide(true, 2, 1, true))
        assertTrue(NavigationPolicy.hide(true, 3, 1, true))
    }

    @Test
    open fun globalHiddenOrUnknownHintIsNeverOverridden() {
        for (mode in intArrayOf(2, 3)) {
            assertFalse(NavigationPolicy.hide(true, mode, 0, true))
            assertFalse(NavigationPolicy.hide(true, mode, -1, true))
            assertFalse(NavigationPolicy.restoreVisible(true, mode, 0))
        }
    }

    @Test
    open fun disabledAndNonFullscreenNeverHide() {
        assertFalse(NavigationPolicy.hide(false, 2, 1, true))
        assertFalse(NavigationPolicy.hide(true, 2, 1, false))
    }

    @Test
    open fun restorationKeepsNativeHiddenAndAllowsButtonControls() {
        for (mode in intArrayOf(0, 1, 2, 3)) assertFalse(
            NavigationPolicy.restoreVisible(false, mode, 1)
        )
        assertTrue(NavigationPolicy.restoreVisible(true, 0, 0))
        assertTrue(NavigationPolicy.restoreVisible(true, 1, 0))
    }
}
