package dev.ryuya.oneuihometweaks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderHomePolicyTest {
    @Test
    fun trailingResetMustNotFinishAnAlreadyPreparedNativeClose() {
        val manager = Any()
        assertTrue(FolderHomePolicy.preservesPreparedClose(manager, manager, true))
        assertFalse(FolderHomePolicy.preservesPreparedClose(manager, manager, false))
        assertFalse(FolderHomePolicy.preservesPreparedClose(manager, Any(), true))
        assertFalse(FolderHomePolicy.preservesPreparedClose(null, manager, true))
    }

    @Test
    fun fastInternalFlickSurvivesTransientFocusHandover() {
        assertTrue(FolderHomePolicy.isVisibleHomeRequest(true, true, false, false))
    }

    @Test
    fun slowFocusedHomeRetainsAnimation() {
        assertTrue(FolderHomePolicy.isVisibleHomeRequest(true, true, true, true))
    }

    @Test
    fun firstAppReturnDoesNotBecomeAFolderClose() {
        assertFalse(FolderHomePolicy.isVisibleHomeRequest(false, true, false, true))
        assertFalse(FolderHomePolicy.isVisibleHomeRequest(false, true, true, false))
    }

    @Test
    fun backgroundAndHiddenHomesAreExcluded() {
        assertFalse(FolderHomePolicy.isVisibleHomeRequest(true, false, true, false))
        assertFalse(FolderHomePolicy.isVisibleHomeRequest(true, true, false, true))
    }
}
