package dev.ryuya.oneuihometweaks

import org.junit.Assert.*
import org.junit.Test

class HomeColorSnapshotTest {
    @Test
    fun localLabelsAndNativeStatusBarKeepNullableOverrides() {
        assertEquals(
            HomeColorSnapshot(null, null),
            HomeColorSnapshot.resolve(ColorPolicy.LOCAL, ColorPolicy.OFF, true, null),
        )
    }

    @Test
    fun explicitLabelOverrideDoesNotRequireAStatusOverride() {
        assertEquals(
            HomeColorSnapshot(1, null),
            HomeColorSnapshot.resolve(ColorPolicy.BLACK, ColorPolicy.OFF, true, null),
        )
        assertEquals(
            HomeColorSnapshot(0, null),
            HomeColorSnapshot.resolve(ColorPolicy.WHITE, ColorPolicy.OFF, true, null),
        )
    }

    @Test
    fun unknownColorsAndStaticWallpaperPreserveNativeValues() {
        assertEquals(
            HomeColorSnapshot(null, null),
            HomeColorSnapshot.resolve(ColorPolicy.AUTO, ColorPolicy.OFF, true, null),
        )
        assertEquals(
            HomeColorSnapshot(null, null),
            HomeColorSnapshot.resolve(ColorPolicy.BLACK, ColorPolicy.WHITE, false, 1),
        )
    }
}
