package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Independently recorded contracts for the two reviewed launcher APKs. */
@JvmRecord
data class FolderContracts(
    val manager: String?,
    val animator: String?,
    val prepare: String?,
    val resetGeometry: String?,
    val viewModelField: String?,
    val managerGetter: String?,
) {

    companion object {
        @JvmStatic
        fun forSdk(sdk: Int): FolderContracts? {
            return when (HookCompatibility.platform(sdk)) {
                HookCompatibility.Platform.ANDROID_16 ->
                    FolderContracts("g3.i", "K5.p1", "t", "c", "f", "Y")
                HookCompatibility.Platform.ANDROID_17 ->
                    FolderContracts("t5.h", "lb.e0", "A", "k", "c", "y0")
                HookCompatibility.Platform.UNSUPPORTED ->
                    throw UnsupportedOperationException("Unreviewed folder platform")
            }
        }
    }

    override fun toString(): String =
        "FolderContracts[manager=$manager, animator=$animator, prepare=$prepare, resetGeometry=$resetGeometry, viewModelField=$viewModelField, managerGetter=$managerGetter]"
}
