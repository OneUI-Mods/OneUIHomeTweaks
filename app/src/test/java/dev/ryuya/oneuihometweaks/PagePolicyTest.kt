package dev.ryuya.oneuihometweaks

import org.junit.Assert.*
import org.junit.Test

open class PagePolicyTest {
    @Test
    open fun pagePositionsAndUnequalGaps() {
        assertEquals(
            (0.5f).toFloat(),
            (PagePolicy.offset(1100, intArrayOf(100, 1100, 2300))).toFloat(),
            (0.0001f).toFloat(),
        )
        assertEquals(
            (0.75f).toFloat(),
            (PagePolicy.offset(1700, intArrayOf(100, 1100, 2300))).toFloat(),
            (0.0001f).toFloat(),
        )
    }

    @Test
    open fun descendingRtlPositions() {
        assertEquals(
            (0f).toFloat(),
            (PagePolicy.offset(2200, intArrayOf(2200, 1100, 0))).toFloat(),
            (0.0001f).toFloat(),
        )
        assertEquals(
            (0.75f).toFloat(),
            (PagePolicy.offset(550, intArrayOf(2200, 1100, 0))).toFloat(),
            (0.0001f).toFloat(),
        )
    }

    @Test
    open fun clampsAndSinglePage() {
        assertEquals(
            (0f).toFloat(),
            (PagePolicy.offset(-10, intArrayOf(0, 100))).toFloat(),
            (0f).toFloat(),
        )
        assertEquals(
            (1f).toFloat(),
            (PagePolicy.offset(110, intArrayOf(0, 100))).toFloat(),
            (0f).toFloat(),
        )
        assertEquals(
            (0.5f).toFloat(),
            (PagePolicy.offset(9, intArrayOf(0))).toFloat(),
            (0f).toFloat(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    open fun ambiguousLayoutRejected() {
        PagePolicy.offset(90, intArrayOf(0, 100, 80))
    }
}
