package dev.ryuya.oneuihometweaks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassBackdropCadenceTest {
    @Test
    fun motionUsesTheNextFrameAndIdleProbesFollowDisplayRefreshRate() {
        val cadence = GlassBackdropCadence()
        repeat(2) { cadence.recordIdenticalFrame() }
        assertFalse(cadence.isIdle)
        assertEquals(0L, cadence.delayMillis(120f))

        cadence.recordIdenticalFrame()
        assertTrue(cadence.isIdle)
        assertEquals(17L, cadence.delayMillis(120f))
        assertEquals(33L, cadence.delayMillis(60f))
    }

    @Test
    fun anIdleBackdropHasABoundedDelayAndResumesImmediatelyOnMovement() {
        val cadence = GlassBackdropCadence()
        repeat(20) { cadence.recordIdenticalFrame() }
        assertEquals(200L, cadence.delayMillis(60f))
        assertEquals(200L, cadence.delayMillis(Float.NaN))

        cadence.reset()
        assertFalse(cadence.isIdle)
        assertEquals(0L, cadence.delayMillis(60f))
    }
}
