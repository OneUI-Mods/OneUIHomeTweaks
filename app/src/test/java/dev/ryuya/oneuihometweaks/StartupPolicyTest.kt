package dev.ryuya.oneuihometweaks

import org.junit.Assert.*
import org.junit.Test

open class StartupPolicyTest {
    @Test
    open fun threeConfirmedEarlyCrashesBlockNextStart() {
        var n: Int = 0
        n = StartupPolicy.nextCount(n, false, 500, 1000, StartupPolicy.Exit.CRASH)
        assertEquals(1, n)
        n = StartupPolicy.nextCount(n, false, 1000, 2000, StartupPolicy.Exit.CRASH)
        assertEquals(2, n)
        assertEquals(3, StartupPolicy.nextCount(n, false, 2000, 3000, StartupPolicy.Exit.CRASH))
    }

    @Test
    open fun intentionalRestartsDoNotAccumulate() {
        assertEquals(
            0,
            StartupPolicy.nextCount(2, false, 1000, 2000, StartupPolicy.Exit.INTENTIONAL),
        )
    }

    @Test
    open fun stableAndWidelySpacedRunsReset() {
        assertEquals(0, StartupPolicy.nextCount(2, true, 1000, 2000, StartupPolicy.Exit.UNKNOWN))
        assertEquals(0, StartupPolicy.nextCount(2, false, 1000, 602000, StartupPolicy.Exit.CRASH))
    }

    @Test
    open fun NewBootResetsMonotonicHistory() {
        assertEquals(
            0,
            StartupPolicy.nextCount(2, false, 1000000, 2000, StartupPolicy.Exit.UNKNOWN),
        )
    }

    @Test
    open fun unknownExitDoesNotCreateFalseCrashLoop() {
        assertEquals(0, StartupPolicy.nextCount(2, false, 1000, 2000, StartupPolicy.Exit.UNKNOWN))
        assertEquals(
            3,
            StartupPolicy.nextCount(Int.MAX_VALUE, false, 1000, 2000, StartupPolicy.Exit.CRASH),
        )
    }
}
