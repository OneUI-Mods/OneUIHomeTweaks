package dev.ryuya.oneuihometweaks

import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SystemUiHookScopeTest {
    @Test
    fun stagesSwapsAndRetiresHooksWithoutDoubleDispatch() {
        val events = mutableListOf<String>()
        val original = scope()
        val update = scope()
        val rejected = scope()
        for ((label, scope) in listOf("old" to original, "new" to update, "rejected" to rejected)) {
            scope.track(ModernHook.Handle { events += "$label-unhook" })
            scope.onRetire { events += "$label-close" }
            scope.afterActivation { events += "$label-start" }
        }

        assertTrue(SystemUiHookScopes.trySwitch(original) { it?.close() })
        val oldCall = { SystemUiHookScopes.dispatch(original, { "stock" }) { "old" } }
        val newCall = { SystemUiHookScopes.dispatch(update, { "stock" }) { "new" } }
        assertEquals("old", oldCall())
        assertEquals("stock", newCall())
        rejected.close()
        assertEquals("old", oldCall())
        assertTrue(SystemUiHookScopes.trySwitch(update) { it?.close() })
        assertEquals("stock", oldCall())
        assertEquals("new", newCall())
        assertEquals(
            listOf("old-start", "rejected-unhook", "rejected-close", "old-unhook", "old-close", "new-start"),
            events,
        )
        update.close()
    }

    @Test
    fun waitsForAnInFlightCallAndOwnsHooksRegisteredWithinIt() {
        val old = scope()
        val next = scope()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val detached = CountDownLatch(1)
        assertTrue(SystemUiHookScopes.trySwitch(old) { it?.close() })
        val running = FutureTask {
            SystemUiHookScopes.dispatch(old, { "stock" }) {
                assertSame(old, SystemUiHookScopes.bound())
                old.track(ModernHook.Handle { detached.countDown() })
                entered.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                SystemUiHookScopes.dispatch(next, { "old" }) { "new" }
            }
        }
        Thread(running, "hook-test").start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(SystemUiHookScopes.trySwitch(next) { it?.close() })
            assertFalse(SystemUiHookScopes.tryRetire { fail("An active callback still owns the generation") })
            assertEquals(1L, detached.count)
        } finally {
            finish.countDown()
        }
        assertEquals("old", running.get(5, TimeUnit.SECONDS))
        assertTrue(SystemUiHookScopes.trySwitch(next) { it?.close() })
        assertEquals(0L, detached.count)
        next.close()
    }

    @Test
    fun failedApi102StateSaveKeepsCurrentHooksAndResources() {
        val current = scope()
        var retired = 0
        current.onRetire { retired++ }
        assertTrue(SystemUiHookScopes.trySwitch(current) { it?.close() })
        assertThrows(IllegalArgumentException::class.java) {
            SystemUiHookScopes.tryRetire { throw IllegalArgumentException("State rejected") }
        }
        assertEquals(0, retired)
        assertEquals("hook", SystemUiHookScopes.dispatch(current, { "stock" }) { "hook" })
        assertTrue(SystemUiHookScopes.tryRetire {})
        assertEquals(1, retired)
        assertEquals("stock", SystemUiHookScopes.dispatch(current, { "stock" }) { "hook" })
    }

    private fun scope() = SystemUiHookScope { throw AssertionError(it) }
}
