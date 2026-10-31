package dev.ryuya.oneuihometweaks

import org.junit.Assert.*
import org.junit.Test

class ColorPolicyTest {
    @Test
    fun hintsAreIndependentOfSeedColorAndTheme() {
        assertEquals(1, ColorPolicy.darkValue(0, true, 1))
        assertEquals(0, ColorPolicy.darkValue(0, true, 2))
        assertEquals(1, ColorPolicy.darkValue(0, true, 3))
        assertEquals(0, ColorPolicy.darkValue(0, true, 0))
    }

    @Test
    fun noColorsDoesNotInventAutomaticResult() {
        assertNull(ColorPolicy.darkValue(0, true, null))
        assertEquals(1, ColorPolicy.darkValue(1, true, null))
        assertEquals(0, ColorPolicy.darkValue(2, true, null))
    }

    @Test
    fun staticWallpaperNeverOverridden() {
        for (mode in 0..3) assertNull(ColorPolicy.darkValue(mode, false, 1))
        assertNull(ColorPolicy.darkValue(3, true, 1))
        assertNull(ColorPolicy.darkValue(98, true, 1))
    }

    @Test
    fun scopeRejectsLockDexCoverVirtualEvents() {
        assertTrue(ColorPolicy.isMainHomeEvent(1))
        assertTrue(ColorPolicy.isMainHomeEvent(5))
        assertTrue(ColorPolicy.isMainHomeEvent(7))
        for (which in intArrayOf(0, 2, 6, 9, 11, 17, 33)) assertFalse(
            ColorPolicy.isMainHomeEvent(which)
        )
    }
}
