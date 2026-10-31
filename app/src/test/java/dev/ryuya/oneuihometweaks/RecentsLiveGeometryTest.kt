package dev.ryuya.oneuihometweaks

import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

open class RecentsLiveGeometryTest {
    @Test
    open fun reducedSnapshotIsScaledExactlyOnceBeforeItsShader() {
        val m =
            RecentsLiveGeometry.compose(
                1440,
                3120,
                720,
                1560,
                .5,
                floatArrayOf(
                    1.2f,
                    (0).toFloat(),
                    (-10).toFloat(),
                    (0).toFloat(),
                    1.2f,
                    (-20).toFloat(),
                    (0).toFloat(),
                    (0).toFloat(),
                    (1).toFloat(),
                ),
                floatArrayOf(
                    (1).toFloat(),
                    (0).toFloat(),
                    (265).toFloat(),
                    (0).toFloat(),
                    (1).toFloat(),
                    (434).toFloat(),
                    (0).toFloat(),
                    (0).toFloat(),
                    (1).toFloat(),
                ),
            )
        val p = m!!.map(720.0, 1560.0)
        assertEquals((687)!!.toDouble(), (p!!.x).toDouble(), (.001).toDouble())
        assertEquals((1350)!!.toDouble(), (p!!.y).toDouble(), (.001).toDouble())
    }

    @Test
    open fun nativeRoundingAndHomeUpShearAreRetained() {
        val m =
            RecentsLiveGeometry.compose(
                1440,
                3120,
                1440,
                3120,
                1.0,
                floatArrayOf(
                    .6637071f,
                    (0).toFloat(),
                    (0).toFloat(),
                    (0).toFloat(),
                    .66288596f,
                    (-90).toFloat(),
                    (0).toFloat(),
                    (0).toFloat(),
                    (1).toFloat(),
                ),
                floatArrayOf(
                    (1).toFloat(),
                    .07f,
                    (265).toFloat(),
                    (0).toFloat(),
                    (1).toFloat(),
                    (434).toFloat(),
                    (0).toFloat(),
                    (0).toFloat(),
                    (1).toFloat(),
                ),
            )
        assertEquals((.6637071).toDouble(), (m!!.a).toDouble(), (1e-6).toDouble())
        assertEquals((.66288596).toDouble(), (m!!.d).toDouble(), (1e-6).toDouble())
        assertEquals((.07 * .66288596).toDouble(), (m!!.c).toDouble(), (1e-6).toDouble())
        assertEquals((258.7).toDouble(), (m!!.tx).toDouble(), (1e-4).toDouble())
    }

    @Test
    open fun portraitCardCanShowLandscapeSnapshotWithoutStretchingIt() {
        val m =
            RecentsLiveGeometry.compose(
                3120,
                1440,
                3120,
                1440,
                1.0,
                floatArrayOf(
                    (0).toFloat(),
                    -.6955f,
                    970.9f,
                    .6946f,
                    (0).toFloat(),
                    -97.2f,
                    (0).toFloat(),
                    (0).toFloat(),
                    (1).toFloat(),
                ),
                RecentsLiveGeometryTest.ID!!,
            )
        val a = m!!.map(0.0, 0.0)
        val b = m!!.map(3120.0, 0.0)
        assertEquals((a!!.x).toDouble(), (b!!.x).toDouble(), (1e-6).toDouble())
        assertEquals((3120 * .6946)!!.toDouble(), (b.y - a.y).toDouble(), (.001).toDouble())
    }

    @Test
    open fun settledLandscapeHomeUpResidualStaysWithinHalfAPixelEverywhere() {
        val sh =
            floatArrayOf(
                .59344506f,
                (0).toFloat(),
                -83.08231f,
                1.8505962e-5f,
                .5937261f,
                -52.84421f,
                4.7708575e-8f,
                (0).toFloat(),
                .9999933f,
            )
        val gl =
            floatArrayOf(
                .9999263f,
                (0).toFloat(),
                768.0583f,
                (0).toFloat(),
                .9999263f,
                291.02454f,
                (0).toFloat(),
                (0).toFloat(),
                (1).toFloat(),
            )
        val m =
            RecentsLiveGeometry.compose(
                RecentsCardSpace.Space(3120, 1440, 1),
                3120,
                1440,
                1,
                1.0,
                sh,
                gl,
            )
        var x: Int = 0
        while (x <= 3120) {
            var y: Int = 0
            while (y <= 1440) {
                val w = (sh[6] * x + sh[7] * y + sh[8]).toDouble()
                val sx: Double = (sh[0] * x + sh[1] * y + sh[2]) / w
                val sy: Double = (sh[3] * x + sh[4] * y + sh[5]) / w
                val p = m!!.map(x.toDouble(), y.toDouble())
                assertTrue(hypot(p!!.x - (gl[0] * sx + gl[2]), p!!.y - (gl[4] * sy + gl[5])) < .5)
                y += 120
            }
            x += 120
        }
    }

    @Test
    open fun realPerspectiveAndDenominatorCrossingStillUseSnapshots() {
        for (p in floatArrayOf(.00001f, -.001f)) assertThrows(
            RecentsLiveGeometry.PerspectiveCard::class.java,
            {
                RecentsLiveGeometry.compose(
                    3120,
                    1440,
                    3120,
                    1440,
                    1.0,
                    floatArrayOf(
                        (1).toFloat(),
                        (0).toFloat(),
                        (0).toFloat(),
                        (0).toFloat(),
                        (1).toFloat(),
                        (0).toFloat(),
                        p,
                        (0).toFloat(),
                        (1).toFloat(),
                    ),
                    RecentsLiveGeometryTest.ID!!,
                )
            },
        )
    }

    @Test
    open fun croppedOrStaleSnapshotIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java,
            {
                RecentsLiveGeometry.compose(
                    1440,
                    3120,
                    1440,
                    2800,
                    1.0,
                    RecentsLiveGeometryTest.ID!!,
                    RecentsLiveGeometryTest.ID!!,
                )
            },
        )
        assertThrows(
            IllegalArgumentException::class.java,
            {
                RecentsLiveGeometry.compose(
                    1440,
                    3120,
                    1440,
                    3120,
                    1.0,
                    floatArrayOf(
                        (1).toFloat(),
                        (0).toFloat(),
                        (0).toFloat(),
                        (0).toFloat(),
                        (1).toFloat(),
                        (0).toFloat(),
                        .01f,
                        (0).toFloat(),
                        (1).toFloat(),
                    ),
                    RecentsLiveGeometryTest.ID!!,
                )
            },
        )
    }

    companion object {
        private val ID: FloatArray? =
            floatArrayOf(
                (1).toFloat(),
                (0).toFloat(),
                (0).toFloat(),
                (0).toFloat(),
                (1).toFloat(),
                (0).toFloat(),
                (0).toFloat(),
                (0).toFloat(),
                (1).toFloat(),
            )
    }
}
