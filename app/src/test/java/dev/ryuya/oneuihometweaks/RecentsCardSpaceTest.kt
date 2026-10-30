package dev.ryuya.oneuihometweaks

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

open class RecentsCardSpaceTest {
    @Test
    open fun portraitDestinationIsRotatedIntoLandscapeWithoutStretching() {
        val p = RecentsCardSpace.Space(1440, 3120, 0)
        val l = RecentsCardSpace.Space(3120, 1440, 1)
        val t = RecentsCardSpace.between(p, l)
        // Measured portrait card size: its long side becomes landscape width, not scaleY.
        val a = t!!.map(323.0, 354.0)
        val b = t!!.map((323 + 793).toDouble(), (354 + 1969).toDouble())
        assertEquals((1969).toDouble(), (Math.abs(b!!.x - a!!.x)).toDouble(), (1e-8).toDouble())
        assertEquals((793).toDouble(), (Math.abs(b!!.y - a!!.y)).toDouble(), (1e-8).toDouble())
        assertEquals((1).toDouble(), (hypot(t.a, t.b)).toDouble(), (1e-8).toDouble())
        assertEquals((1).toDouble(), (hypot(t.c, t.d)).toDouble(), (1e-8).toDouble())
    }

    @Test
    open fun allWindowRotationsRoundTripAndPreserveDistances() {
        for (r in 0..3) for (s in 0..3) {
            val from =
                RecentsCardSpace.Space(
                    if (r % 2 == 0) 1440 else 3120,
                    if (r % 2 == 0) 3120 else 1440,
                    r,
                )
            val to =
                RecentsCardSpace.Space(
                    if (s % 2 == 0) 1440 else 3120,
                    if (s % 2 == 0) 3120 else 1440,
                    s,
                )
            val f = RecentsCardSpace.between(from, to)
            val back = RecentsCardSpace.between(to, from)
            val a = f!!.map(71.0, 302.0)
            val b = f!!.map(171.0, 302.0)
            assertEquals(
                (100).toDouble(),
                (hypot(a!!.x - b!!.x, a!!.y - b!!.y)).toDouble(),
                (1e-8).toDouble(),
            )
            val q = back!!.map(a!!.x, a!!.y)
            assertEquals((71).toDouble(), (q!!.x).toDouble(), (1e-8).toDouble())
            assertEquals((302).toDouble(), (q!!.y).toDouble(), (1e-8).toDouble())
        }
    }

    @Test
    open fun sameSpaceIsIdentityAndCannotApplyASecondQuarterTurn() {
        val s = RecentsCardSpace.Space(3120, 1440, 1)
        val t = RecentsCardSpace.between(s, s)
        assertEquals(RecentsAffine.Point(971.0, 249.0), t!!.map(971.0, 249.0))
    }

    @Test
    open fun staleDimensionsAndOtherDisplaysCannotBeScaledToFit() {
        val p = RecentsCardSpace.Space(1440, 3120, 0)
        assertThrows(
            IllegalArgumentException::class.java,
            { RecentsCardSpace.between(p, RecentsCardSpace.Space(3120, 1440, 0)) },
        )
        assertThrows(
            IllegalArgumentException::class.java,
            { RecentsCardSpace.between(p, RecentsCardSpace.Space(1, 1, 1)) },
        )
        assertThrows(IllegalArgumentException::class.java, { RecentsCardSpace.Space(0, 3120, 0) })
    }
}
