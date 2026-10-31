package dev.ryuya.oneuihometweaks

import org.junit.Assert.*
import org.junit.Test

class ComposeRecomposeFlagsTest {
    @Test
    fun nativeComparisonCallsArePreservedForEveryStateField() {
        for (field in 0..7) {
            val native = field shl 10
            val fixed = ComposeRecomposeFlags.panelStateChanged(native)
            assertEquals(native and 3072 == 0, fixed and 3072 == 0)
        }
    }

    @Test
    fun nativeComparisonCallsArePreservedForEveryModifierField() {
        for (field in 0..7) {
            val native = field shl 4
            val fixed = ComposeRecomposeFlags.tileModifierChanged(native, false)
            assertEquals(native and 48 == 0, fixed and 48 == 0)
        }
    }

    @Test
    fun callbackRemainsInItsNativeRememberSlotAcrossRecomposition() {
        // Samsung's renderer conditionally compares State before reading a Function1.
        // A previous comparison slot holds an Integer. Skipping that comparison on a
        // later pass reads the Integer as a callback, matching the captured crash.
        val callback: (Any?) -> Unit = {}
        val slots = listOf(0, callback)
        fun remembered(flags: Int): Any = slots[if (flags and 3072 == 0) 1 else 0]
        val oldFlags = 2048 or 1
        assertTrue(remembered(oldFlags) is Int)
        assertSame(callback, remembered(ComposeRecomposeFlags.panelStateChanged(0)))
    }

    @Test
    fun knownUnchangedArgumentsBecomeChangedWithoutAddingComparisonSlots() {
        assertEquals(2048 or 1, ComposeRecomposeFlags.panelStateChanged(1024))
        assertEquals(32 or 1, ComposeRecomposeFlags.tileModifierChanged(16, false))
    }

    @Test
    fun unrelatedAndStabilityBitsRemainIntact() {
        val flags = 0x6db6db6c
        val state = ComposeRecomposeFlags.panelStateChanged(flags)
        val modifier = ComposeRecomposeFlags.tileModifierChanged(flags, false)
        assertEquals(flags and (3072 or 1).inv(), state and (3072 or 1).inv())
        assertEquals(flags and (48 or 1).inv(), modifier and (48 or 1).inv())
    }

    @Test
    fun replacingDefaultModifierDoesNotIntroduceANewComparisonSlot() {
        for (field in 0..7) {
            val native = field shl 4
            val fixed = ComposeRecomposeFlags.tileModifierChanged(native, true)
            // The default-argument branch skips Composer.changed. Supplying a custom
            // modifier must retain that behavior after clearing the default bit.
            assertFalse(fixed and 48 == 0)
            assertEquals(32, fixed and 48)
        }
    }
}
