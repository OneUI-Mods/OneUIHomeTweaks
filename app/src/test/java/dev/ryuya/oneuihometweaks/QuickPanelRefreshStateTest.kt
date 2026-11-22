package dev.ryuya.oneuihometweaks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPanelRefreshStateTest {
    private val stock = intArrayOf(100, 100, 100, 100)

    @Test
    fun stockLayoutDoesNotForceRecomposition() {
        val state = QuickPanelPolicy.RefreshState()
        state.record(stock, legacy = false)
        assertFalse(state.any())
        assertFalse(state.legacyChanged)
        for (index in stock.indices) assertFalse(state.size(index))
    }

    @Test
    fun onlyPreviouslyResizedControlsNeedToRestoreTheirNativeModifiers() {
        val state = QuickPanelPolicy.RefreshState()
        state.record(intArrayOf(100, 95, 100, 100), legacy = false)
        state.record(stock, legacy = false)
        assertTrue(state.any())
        assertTrue(state.size(1))
        assertFalse(state.size(0))
        assertFalse(state.size(2))
        assertFalse(state.size(3))
        assertFalse(state.legacyChanged)
    }

    @Test
    fun switchingBackFromLegacyLayoutRestoresAllFourControls() {
        val state = QuickPanelPolicy.RefreshState()
        state.record(stock, legacy = true)
        state.record(stock, legacy = false)
        assertTrue(state.legacyChanged)
        for (index in stock.indices) assertTrue(state.size(index))
    }
}
