package dev.ryuya.oneuihometweaks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadeFrameRateRequestsTest {
    @Test
    fun sameRateSkipsOnlyWhenStockSelfRequestFlagAlreadyMatches() {
        assertTrue(ShadeFrameRateRequests.isRedundant(120f, 120f, true, Int.MIN_VALUE))
        assertTrue(ShadeFrameRateRequests.isRedundant(-4f, -4f, true, Int.MIN_VALUE or 0x04))
        assertFalse(ShadeFrameRateRequests.isRedundant(120f, 120f, true, 0))
    }

    @Test
    fun resetPreservesNanIdentityAndClearsAnExistingSelfRequest() {
        val alternateNan = Float.fromBits(0x7fc00001)
        assertTrue(ShadeFrameRateRequests.isRedundant(Float.NaN, Float.NaN, true, 0))
        assertFalse(ShadeFrameRateRequests.isRedundant(Float.NaN, Float.NaN, true, Int.MIN_VALUE))
        assertFalse(ShadeFrameRateRequests.isRedundant(Float.NaN, alternateNan, true, 0))
    }

    @Test
    fun changedRatesAndForcedOverridesKeepTheirNativePath() {
        assertFalse(ShadeFrameRateRequests.isRedundant(120f, 60f, true, Int.MIN_VALUE))
        assertFalse(ShadeFrameRateRequests.isRedundant(-0f, 0f, false, 0))
        assertFalse(ShadeFrameRateRequests.isRedundant(120f, 120f, true, Int.MIN_VALUE or (1 shl 30)))
    }

    @Test
    fun frameworkWithoutGroupFlagsOnlyWritesTheRate() {
        assertTrue(ShadeFrameRateRequests.isRedundant(120f, 120f, false, 0))
        assertFalse(ShadeFrameRateRequests.isRedundant(120f, Float.NaN, false, 0))
    }
}
