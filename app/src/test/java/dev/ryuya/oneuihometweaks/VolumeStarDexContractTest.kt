package dev.ryuya.oneuihometweaks

import org.junit.Assert.*
import org.junit.Test

class VolumeStarDexContractTest {
    private val owner = "Landroid/view/SemBlurInfo\$Builder;"

    @Test
    fun zeroArgumentCallersAreSafelyRejected() {
        assertFalse(VolumeStarDexContract.isBlurCall(owner, "setColorCurvePreset") { emptyList() })
        assertFalse(VolumeStarDexContract.isBlurCall(null, null) { emptyList() })
    }

    @Test
    fun bothSupportedViewParametersRequireTheMatchingCallee() {
        for (view in listOf("Landroid/view/View;", "Landroid/widget/ImageView;")) {
            assertTrue(
                VolumeStarDexContract.isBlurCall(owner, "setColorCurvePreset") { listOf(view) }
            )
            assertFalse(
                VolumeStarDexContract.isBlurCall(
                    "Lother/Builder;",
                    "setColorCurvePreset",
                ) {
                    listOf(view)
                }
            )
            assertFalse(VolumeStarDexContract.isBlurCall(owner, "other") { listOf(view) })
            assertFalse(VolumeStarDexContract.isBlurCall(null, null) { listOf(view) })
        }
    }

    @Test
    fun unrelatedCalleesNeverInspectCallerParameters() {
        assertFalse(
            VolumeStarDexContract.isBlurCall(null, "other") {
                throw AssertionError("Unrelated instructions must not parse caller parameters")
            }
        )
    }

    @Test
    fun unrelatedCallerParametersAreRejected() {
        assertFalse(VolumeStarDexContract.isBlurCall(owner, "setColorCurvePreset") { listOf("I") })
    }
}
