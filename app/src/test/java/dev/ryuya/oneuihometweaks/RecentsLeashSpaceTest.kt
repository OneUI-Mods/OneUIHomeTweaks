package dev.ryuya.oneuihometweaks

import org.junit.Assert.*
import org.junit.Test

open class RecentsLeashSpaceTest {
    @Test
    open fun normalizedPortraitLeashIsResolvedFromIdentityInsteadOfStaleAppRotation() {
        val p = RecentsCardSpace.Space(1440, 3120, 0)
        assertEquals(
            p,
            RecentsLeashSpace.resolve(
                p,
                1440,
                3120,
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
                ),
            ),
        )
    }

    @Test
    open fun nativeLandscapeQuarterTurnMatchesMeasuredSource() {
        val p = RecentsCardSpace.Space(1440, 3120, 0)
        assertEquals(
            RecentsCardSpace.Space(3120, 1440, 1),
            RecentsLeashSpace.resolve(
                p,
                3120,
                1440,
                floatArrayOf(
                    (0).toFloat(),
                    (1).toFloat(),
                    (0).toFloat(),
                    (-1).toFloat(),
                    (0).toFloat(),
                    (1440).toFloat(),
                    (0).toFloat(),
                    (0).toFloat(),
                    (1).toFloat(),
                ),
            ),
        )
    }

    @Test
    open fun zoomedOrUnmeasuredSourceIsRejected() {
        val p = RecentsCardSpace.Space(1440, 3120, 0)
        assertThrows(
            IllegalArgumentException::class.java,
            {
                RecentsLeashSpace.resolve(
                    p,
                    1440,
                    3120,
                    floatArrayOf(
                        .9f,
                        (0).toFloat(),
                        (0).toFloat(),
                        (0).toFloat(),
                        .9f,
                        (0).toFloat(),
                        (0).toFloat(),
                        (0).toFloat(),
                        (1).toFloat(),
                    ),
                )
            },
        )
        assertThrows(
            IllegalArgumentException::class.java,
            {
                RecentsLeashSpace.resolve(
                    p,
                    720,
                    1560,
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
                    ),
                )
            },
        )
    }

    @Test
    open fun portraitNormalizedAppCanMatchALandscapeSnapshot() {
        val p = RecentsCardSpace.Space(1440, 3120, 0)
        val t =
            RecentsLiveGeometry.compose(
                p,
                3120,
                1440,
                1,
                1.0,
                floatArrayOf(
                    (0).toFloat(),
                    -.694f,
                    (1000).toFloat(),
                    .694f,
                    (0).toFloat(),
                    (-100).toFloat(),
                    (0).toFloat(),
                    (0).toFloat(),
                    (1).toFloat(),
                ),
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
                ),
            )
        assertEquals((.694).toDouble(), (t!!.a).toDouble(), (1e-6).toDouble())
        assertEquals((.694).toDouble(), (t!!.d).toDouble(), (1e-6).toDouble())
        assertEquals((0)!!.toDouble(), (t.b).toDouble(), (0).toDouble())
        assertEquals((0)!!.toDouble(), (t.c).toDouble(), (0).toDouble())
        assertEquals((.64)!!.toDouble(), (t.tx).toDouble(), (.001).toDouble())
    }
}
