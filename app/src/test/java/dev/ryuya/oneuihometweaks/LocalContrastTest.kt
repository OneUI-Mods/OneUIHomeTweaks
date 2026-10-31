package dev.ryuya.oneuihometweaks

import java.util.Arrays
import org.junit.Assert.*
import org.junit.Test

class LocalContrastTest {
    private fun solid(color: Int): IntArray {
        val pixels = IntArray(100)
        Arrays.fill(pixels, color)
        return pixels
    }

    @Test
    fun transitionFramesNeedMatchingSettledLocalPixels() {
        assertFalse(LocalContrast.settled(null, solid(-0x777778)))
        assertFalse(LocalContrast.settled(IntArray(7), IntArray(7)))
        assertFalse(LocalContrast.settled(solid(-0x777778), IntArray(8)))
        assertFalse(LocalContrast.settled(solid(-0xdfdfe0), solid(-0x111112)))
        assertTrue(LocalContrast.settled(solid(-0x777778), solid(-0x757576)))
        assertTrue(LocalContrast.settled(solid(-0x777778), solid(-0x777778)))
    }

    @Test
    fun eachRegionChoosesItsOwnContrast() {
        assertEquals(
            LocalContrast.BLACK,
            LocalContrast.choose(solid(-0x111112), null),
        )
        assertEquals(
            LocalContrast.WHITE,
            LocalContrast.choose(solid(-0xdfdfe0), null),
        )
    }

    @Test
    fun srgbLuminanceUsesColorNotJustRgbAverage() {
        assertEquals(
            LocalContrast.BLACK,
            LocalContrast.choose(solid(-0xff0100), null),
        )
        assertEquals(
            LocalContrast.WHITE,
            LocalContrast.choose(solid(-0xffff01), null),
        )
    }

    @Test
    fun smallOutlierDoesNotReverseTheWholeLabel() {
        val bright = solid(-0x111112)
        Arrays.fill(bright, 0, 10, -0x1000000)
        assertEquals(LocalContrast.BLACK, LocalContrast.choose(bright, null))
        val dark = solid(-0xdfdfe0)
        Arrays.fill(dark, 0, 10, -0x1)
        assertEquals(LocalContrast.WHITE, LocalContrast.choose(dark, null))
    }

    @Test
    fun transparentOrInsufficientDataFallsBack() {
        assertNull(LocalContrast.choose(IntArray(0), null))
        assertNull(LocalContrast.choose(IntArray(7), LocalContrast.BLACK))
        assertNull(LocalContrast.choose(solid(0x00ffffff), null))
        val partial = solid(-0x1)
        Arrays.fill(partial, 0, 26, 0x00ffffff)
        assertNull(LocalContrast.choose(partial, LocalContrast.BLACK))
    }

    @Test
    fun hysteresisAvoidsFlipsButDoesNotLockThePreviousColor() {
        assertEquals(
            LocalContrast.WHITE,
            LocalContrast.choose(solid(-0x8a8a8b), null),
        )
        assertEquals(
            LocalContrast.BLACK,
            LocalContrast.choose(solid(-0x8a8a8b), LocalContrast.BLACK),
        )
        assertEquals(
            LocalContrast.WHITE,
            LocalContrast.choose(solid(-0xdfdfe0), LocalContrast.BLACK),
        )
        assertEquals(
            LocalContrast.BLACK,
            LocalContrast.choose(solid(-0x111112), LocalContrast.WHITE),
        )
    }
}
