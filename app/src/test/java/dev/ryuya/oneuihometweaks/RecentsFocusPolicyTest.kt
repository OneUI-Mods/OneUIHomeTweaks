package dev.ryuya.oneuihometweaks

import org.junit.Assert.*
import org.junit.Test

open class RecentsFocusPolicyTest {
    private val task: RecentsFocusPolicy.Target = RecentsFocusPolicy.Target(42, 10123, 0, 0, 1, 1)

    @Test
    open fun staleGestureCannotRestoreNewerTask() {
        val policy = RecentsFocusPolicy()
        val old = Any()
        val next = Any()
        assertTrue(policy.begin(old, task, 1))
        assertTrue(policy.begin(next, task, 2))
        assertNull(policy.end(old))
        assertSame(next, policy.current()!!.owner)
        assertEquals(2, policy.current()!!.mode)
    }

    @Test
    open fun ownerDeathEndsOnlyItsOwnLease() {
        val policy = RecentsFocusPolicy()
        val owner = Any()
        policy.begin(owner, task, 1)
        assertNotNull(policy.end(owner))
        assertNull(policy.current())
        assertNull(policy.end(owner))
    }

    @Test
    open fun invalidCandidateDoesNotReplaceValidLease() {
        val policy = RecentsFocusPolicy()
        val owner = Any()
        policy.begin(owner, task, 1)
        assertFalse(policy.begin(Any(), RecentsFocusPolicy.Target(43, 10124, 0, 0, 2, 1), 1))
        assertSame(owner, policy.current()!!.owner)
    }

    @Test
    open fun workProfileAndExternalDisplayCannotBeTargets() {
        assertFalse(RecentsFocusPolicy.Target(42, 110123, 1, 0, 1, 1).eligible())
        assertFalse(RecentsFocusPolicy.Target(42, 10123, 0, 2, 1, 1).eligible())
    }

    @Test
    open fun systemHomePipAndSplitAreExcluded() {
        assertFalse(RecentsFocusPolicy.Target(42, 1000, 0, 0, 1, 1).eligible())
        assertFalse(RecentsFocusPolicy.Target(42, 10123, 0, 0, 1, 2).eligible())
        assertFalse(RecentsFocusPolicy.Target(42, 10123, 0, 0, 2, 1).eligible())
        assertFalse(RecentsFocusPolicy.Target(42, 10123, 0, 0, 6, 1).eligible())
    }

    @Test
    open fun corruptModesFailToNativeWithoutReplacingLease() {
        val policy = RecentsFocusPolicy()
        val owner = Any()
        policy.begin(owner, task, 1)
        for (mode in intArrayOf(-1, 0, 3, Int.MAX_VALUE)) {
            assertFalse(policy.begin(Any(), task, mode))
            assertSame(owner, policy.current()!!.owner)
        }
    }

    @Test
    open fun arbitraryAppSystemShellAndRootCannotUseBackend() {
        for (uid in intArrayOf(0, 1000, 2000, 10125, 110123, 99001)) assertFalse(
            RecentsFocusPolicy.allowedUid(uid, 10123, 10124, false)
        )
    }

    @Test
    open fun launcherCannotArmButModuleCan() {
        assertTrue(RecentsFocusPolicy.allowedUid(10124, 10123, 10124, false))
        assertFalse(RecentsFocusPolicy.allowedUid(10124, 10123, 10124, true))
        assertTrue(RecentsFocusPolicy.allowedUid(10123, 10123, 10124, true))
    }

    @Test
    open fun preserveRequiresExistingNativeRetention() {
        assertTrue(RecentsFocusPolicy.canOverride(1, false))
        assertTrue(RecentsFocusPolicy.canOverride(1, true))
        assertTrue(RecentsFocusPolicy.canOverride(2, true))
        assertFalse(RecentsFocusPolicy.canOverride(2, false))
        assertFalse(RecentsFocusPolicy.canOverride(0, true))
    }

    @Test
    open fun matchingUidInAnotherUserIsRejected() {
        assertFalse(RecentsFocusPolicy.allowedUid(110123, 110123, 110124, false))
    }
}
