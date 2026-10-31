package dev.ryuya.oneuihometweaks

import org.junit.Assert.*
import org.junit.Test

open class RecentsNavigationGestureTest {
    private fun navigation(): RecentsNavigationGesture? {
        return RecentsNavigationGesture(1440, 3120, 80, 80, 94, 24)
    }

    @Test
    open fun homeOnlyFromBottomAndBeyondNativeCancelArea() {
        val n = navigation()
        assertFalse(n!!.event(10, 0, 1, 720f, 3114f)!!.consume)
        assertEquals(0, n!!.event(10, 1, 1, 720f, 3000f)!!.command)
        n!!.event(20, 0, 1, 720f, 3114f)
        assertEquals(3, n!!.event(20, 1, 1, 720f, 1800f)!!.command)
        assertEquals(0, n!!.event(20, 1, 1, 720f, 1800f)!!.command)
    }

    @Test
    open fun bothBackEdgesBlockCardScrollingAndReturnOneCommand() {
        for (x in intArrayOf(6, 1434)) {
            val n = navigation()
            assertTrue(n!!.event(10, 0, 1, x.toFloat(), 1550f)!!.consume)
            assertTrue(n!!.event(10, 2, 1, 720f, 1550f)!!.consume)
            val up = n!!.event(10, 1, 1, 720f, 1550f)
            assertTrue(up!!.consume)
            assertEquals(4, up!!.command)
            assertEquals(0, n!!.event(10, 1, 1, 720f, 1550f)!!.command)
        }
    }

    @Test
    open fun cancelledAndReversedEdgesDoNotNavigate() {
        val n = navigation()
        n!!.event(10, 0, 1, 6f, 1550f)
        assertTrue(n!!.event(10, 3, 1, 500f, 1550f)!!.consume)
        assertEquals(0, n!!.event(10, 1, 1, 720f, 1550f)!!.command)
        n!!.event(20, 0, 1, 6f, 1550f)
        n!!.event(20, 2, 1, 600f, 1550f)
        assertEquals(0, n!!.event(20, 1, 1, 7f, 1550f)!!.command)
    }

    @Test
    open fun multitouchCannotBecomeBackAndRemainsBlockedUntilUp() {
        val n = navigation()
        n!!.event(10, 0, 1, 6f, 1550f)
        assertTrue(n!!.event(10, 5, 2, 500f, 1550f)!!.consume)
        val up = n!!.event(10, 1, 1, 720f, 1550f)
        assertTrue(up!!.consume)
        assertEquals(0, up!!.command)
        assertFalse(n!!.event(20, 0, 1, 720f, 1550f)!!.consume)
    }

    @Test
    open fun cardSwipesAndStaleStreamsAreNotNavigation() {
        val n = navigation()
        n!!.event(10, 0, 1, 1200f, 1550f)
        assertEquals(RecentsNavigationGesture.NONE, n!!.event(10, 1, 1, 400f, 1550f))
        n!!.event(20, 0, 1, 720f, 3114f)
        assertEquals(0, n!!.event(19, 1, 1, 720f, 1800f)!!.command)
        n!!.event(30, 0, 1, 6f, 1550f)
        assertEquals(0, n!!.event(30, 1, 1, 300f, 2200f)!!.command)
    }

    @Test
    open fun landscapeUsesItsOwnBottomAndBothBackEdges() {
        val n = RecentsNavigationGesture(3120, 1440, 80, 80, 60, 24)
        n.event(10, 0, 1, 1560f, 1434f)
        assertEquals(3, n.event(10, 1, 1, 1560f, 800f)!!.command)
        n.event(20, 0, 1, 6f, 720f)
        val left = n.event(20, 1, 1, 500f, 720f)
        assertTrue(left!!.consume)
        assertEquals(4, left!!.command)
        n.event(30, 0, 1, 3114f, 720f)
        val right = n.event(30, 1, 1, 2600f, 720f)
        assertTrue(right!!.consume)
        assertEquals(4, right!!.command)
        assertEquals(RecentsNavigationGesture.NONE, n.event(40, 0, 1, 1560f, 720f))
    }

    @Test(expected = IllegalArgumentException::class)
    open fun missingEdgeRegionsAreRejected() {
        RecentsNavigationGesture(1440, 3120, 0, 0, 94, 24)
    }
}
