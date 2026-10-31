package dev.ryuya.oneuihometweaks

import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.*
import org.junit.Test

class DexCallsitePolicyTest {
    private val item = "Lcom/honeyspace/ui/honeypots/freegrid/domain/model/FreeGridItem;"

    private fun ref(
        owner: String,
        name: String,
        params: List<String> = emptyList(),
        result: String = "I",
    ) = ImmutableMethodReference(owner, name, params, result)

    @Test
    fun instructionsWithoutAMethodReferenceAreRejected() {
        assertFalse(DexCallsitePolicy.isDiyPositionCall(null))
        assertFalse(DexCallsitePolicy.isVolumeCall(null))
    }

    @Test
    fun supportedDiyCallsiteFamiliesMatch() {
        assertTrue(DexCallsitePolicy.isDiyPositionCall(ref(item, "getX")))
        assertTrue(DexCallsitePolicy.isDiyPositionCall(ref(item, "getY")))
        assertTrue(
            DexCallsitePolicy.isDiyPositionCall(
                ref(
                    "Lcom/honeyspace/ui/honeypots/freegrid/presentation/FreeGridCellLayout;",
                    "syncPosition",
                )
            )
        )
        assertTrue(
            DexCallsitePolicy.isDiyPositionCall(
                ref(
                    "Lcom/honeyspace/ui/honeypots/freegrid/viewmodel/FreeGridViewModel;",
                    "updateToDropPoint",
                )
            )
        )
    }

    @Test
    fun gettersNeedTheOriginalOwnerArityAndReturnType() {
        assertFalse(DexCallsitePolicy.isDiyPositionCall(ref("Lother/Item;", "getY")))
        assertFalse(DexCallsitePolicy.isDiyPositionCall(ref(item, "getZ")))
        assertFalse(DexCallsitePolicy.isDiyPositionCall(ref(item, "getX", listOf("I"))))
        assertFalse(DexCallsitePolicy.isDiyPositionCall(ref(item, "getX", result = "J")))
    }

    @Test
    fun volumeReferencesMatchOnlyTheTwoOriginalOwners() {
        assertTrue(
            DexCallsitePolicy.isVolumeCall(
                ref("Lcom/android/systemui/volume/util/BlurEffect;", "apply")
            )
        )
        assertTrue(
            DexCallsitePolicy.isVolumeCall(
                ref("Lcom/android/systemui/volume/util/ColorUtils;", "color")
            )
        )
        assertFalse(DexCallsitePolicy.isVolumeCall(ref("Lother/ColorUtils;", "color")))
    }
}
