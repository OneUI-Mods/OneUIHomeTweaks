package dev.ryuya.oneuihometweaks

import org.junit.Assert.*
import org.junit.Test

class RecentsFocusCompatibilityTest {
    @Test
    fun auditedLegacyServersSurviveAnAppUpdate() {
        for (build in 31..34) assertTrue(RecentsFocusCompatibility.accepts(0, build))
    }

    @Test
    fun unknownLegacyServersStayRejected() {
        for (build in intArrayOf(-1, 0, 30, 35, Int.MAX_VALUE)) assertFalse(
            RecentsFocusCompatibility.accepts(0, build)
        )
    }

    @Test
    fun explicitWireVersionIsIndependentOfAppBuild() {
        assertTrue(RecentsFocusCompatibility.accepts(1, 34))
        assertTrue(RecentsFocusCompatibility.accepts(1, 35))
    }

    @Test
    fun unknownWireVersionCannotPassThroughAMatchingBuild() {
        for (protocol in intArrayOf(-1, 2, Int.MAX_VALUE)) assertFalse(
            RecentsFocusCompatibility.accepts(protocol, 34)
        )
    }
}
