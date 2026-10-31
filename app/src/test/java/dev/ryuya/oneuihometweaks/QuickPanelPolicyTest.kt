package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test

open class QuickPanelPolicyTest {
    @Test
    open fun legacyStandardRetainsNativeSmallButtonAndBarSize() {
        assertEquals(
            (44).toDouble(),
            (QuickPanelPolicy.legacyDimension(44f, 100)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (35.2f).toFloat(),
            (QuickPanelPolicy.legacyDimension(44f, 80)).toFloat(),
            (.001f).toFloat(),
        )
        assertEquals(
            (52.8f).toFloat(),
            (QuickPanelPolicy.legacyDimension(44f, 120)).toFloat(),
            (.001f).toFloat(),
        )
    }

    @Test
    open fun verticalOnlyChangesThicknessAndPreservesLength() {
        val actual = QuickPanelPolicy.thickness(QuickPanelPolicyTest.size(280f, 600f), true, .6f)
        assertEquals(
            (168).toFloat(),
            (java.lang.Float.intBitsToFloat((actual ushr 32).toInt())).toFloat(),
            (.001f).toFloat(),
        )
        assertEquals(
            (600).toDouble(),
            (java.lang.Float.intBitsToFloat(actual.toInt())).toDouble(),
            (0).toDouble(),
        )
    }

    @Test
    open fun horizontalOnlyChangesThicknessAndPreservesLength() {
        val actual = QuickPanelPolicy.thickness(QuickPanelPolicyTest.size(600f, 120f), false, 1.2f)
        assertEquals(
            (600).toDouble(),
            (java.lang.Float.intBitsToFloat((actual ushr 32).toInt())).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (144).toFloat(),
            (java.lang.Float.intBitsToFloat(actual.toInt())).toFloat(),
            (.001f).toFloat(),
        )
    }

    @Test
    open fun standardPackedSizeIsRestoredExactly() {
        assertEquals(
            QuickPanelPolicyTest.size(278.25f, 603.5f),
            QuickPanelPolicy.thickness(QuickPanelPolicyTest.size(278.25f, 603.5f), true, 1f),
        )
        assertEquals(
            (92).toDouble(),
            (QuickPanelPolicy.dimension(92f, 100)).toDouble(),
            (0).toDouble(),
        )
    }

    @Test
    open fun expandedRangeScalesBothDesignsWithoutThePreviousMinimum() {
        for (nativeDp in floatArrayOf((44).toFloat(), (80).toFloat(), (92).toFloat())) {
            assertEquals(
                (nativeDp / 4).toFloat(),
                (QuickPanelPolicy.dimension(nativeDp, 25)).toFloat(),
                (.001f).toFloat(),
            )
            assertEquals(
                (nativeDp * 2).toFloat(),
                (QuickPanelPolicy.dimension(nativeDp, 200)).toFloat(),
                (.001f).toFloat(),
            )
            assertEquals(
                (nativeDp / 4).toFloat(),
                (QuickPanelPolicy.legacyDimension(nativeDp, 25)).toFloat(),
                (.001f).toFloat(),
            )
            assertEquals(
                (nativeDp * 2).toFloat(),
                (QuickPanelPolicy.legacyDimension(nativeDp, 200)).toFloat(),
                (.001f).toFloat(),
            )
        }
    }

    @Test
    open fun invalidGeometryRemainsNative() {
        val original = QuickPanelPolicyTest.size(Float.NaN, 600f)
        assertEquals(original, QuickPanelPolicy.thickness(original, true, .5f))
        assertEquals(
            QuickPanelPolicyTest.size(200f, 600f),
            QuickPanelPolicy.thickness(QuickPanelPolicyTest.size(200f, 600f), true, Float.NaN),
        )
        assertTrue(java.lang.Float.isNaN(QuickPanelPolicy.dimension(Float.NaN, 50)))
        assertEquals(
            (0).toDouble(),
            (QuickPanelPolicy.dimension(0f, 25)).toDouble(),
            (0).toDouble(),
        )
        assertEquals(
            (Float.POSITIVE_INFINITY).toDouble(),
            (QuickPanelPolicy.dimension(Float.POSITIVE_INFINITY, 200)).toDouble(),
            (0).toDouble(),
        )
    }

    @Test
    open fun corruptPreferencesStayInsideReviewedBounds() {
        assertEquals(25, QuickPanelPolicy.percent(Int.MIN_VALUE))
        assertEquals(200, QuickPanelPolicy.percent(Int.MAX_VALUE))
        for (value in intArrayOf(25, 50, 60, 80, 100, 105, 110, 120, 150, 200)) assertEquals(
            value,
            QuickPanelPolicy.percent(value),
        )
    }

    companion object {
        @JvmStatic
        private fun size(w: Float, h: Float): Long {
            return (java.lang.Float.floatToRawIntBits(w).toLong() shl 32) or
                (java.lang.Float.floatToRawIntBits(h).toLong() and 0xffffffffL)
        }
    }
}
