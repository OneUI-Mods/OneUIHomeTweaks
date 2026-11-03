package dev.ryuya.oneuihometweaks

import dev.ryuya.oneuihometweaks.IconOverlapPolicy.Bounds
import org.junit.Assert.*
import org.junit.Test

class IconOverlapPolicyTest {
    @Test
    fun neighboringHomeIconsStaySeparateInTheirCommonParent() {
        // Measured Settings and NEXUS containers, including their labels.
        assertTrue(
            IconOverlapPolicy.definitelySeparate(
                Bounds(769f, 1371f, 940f, 1589f),
                Bounds(959f, 1371f, 1130f, 1589f),
            )
        )
    }

    @Test
    fun genuinelyOverlaidIconsRetainTheNativeDecision() {
        assertFalse(
            IconOverlapPolicy.definitelySeparate(
                Bounds(769f, 1371f, 940f, 1589f),
                Bounds(930f, 1371f, 1101f, 1589f),
            )
        )
        assertFalse(
            IconOverlapPolicy.definitelySeparate(
                Bounds(0f, 0f, 100f, 100f),
                Bounds(20f, 20f, 80f, 80f),
            )
        )
    }

    @Test
    fun labelOrRotatedBoundingBoxOverlapIsConservativelyPreserved() {
        assertFalse(
            IconOverlapPolicy.definitelySeparate(
                Bounds(0f, 0f, 100f, 140f),
                Bounds(80f, 100f, 180f, 240f),
            )
        )
    }

    @Test
    fun separationWorksOnEitherAxisAndRegardlessOfOrder() {
        val center = Bounds(0f, 0f, 100f, 100f)
        for (other in
            listOf(
                Bounds(100f, 0f, 200f, 100f),
                Bounds(-200f, 0f, -100f, 100f),
                Bounds(0f, 120f, 100f, 220f),
                Bounds(0f, -100f, 100f, 0f),
            )) {
            assertTrue(IconOverlapPolicy.definitelySeparate(center, other))
            assertTrue(IconOverlapPolicy.definitelySeparate(other, center))
        }
    }

    @Test
    fun unknownOrSingularGeometryNeverSuppressesNativeBehavior() {
        val valid = Bounds(0f, 0f, 100f, 100f)
        for (unknown in
            listOf(
                Bounds(Float.NaN, 0f, 200f, 100f),
                Bounds(0f, 0f, Float.POSITIVE_INFINITY, 100f),
                Bounds(200f, 0f, 200f, 100f),
                Bounds(200f, 100f, 300f, 0f),
            )) {
            assertFalse(IconOverlapPolicy.definitelySeparate(valid, unknown))
            assertFalse(IconOverlapPolicy.definitelySeparate(unknown, valid))
        }
    }
}
