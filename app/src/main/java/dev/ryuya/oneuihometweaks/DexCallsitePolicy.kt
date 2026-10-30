package dev.ryuya.oneuihometweaks

import org.jf.dexlib2.iface.reference.MethodReference

/** Match complete callsite contracts only after confirming the DEX reference is a method. */
internal object DexCallsitePolicy {
    fun isDiyPositionCall(ref: MethodReference?): Boolean {
        if (ref == null) return false
        val owner = ref.definingClass
        return when {
            owner.startsWith("Lcom/honeyspace/ui/honeypots/freegrid/domain/model/FreeGridItem") ->
                (ref.name == "getX" || ref.name == "getY") &&
                    ref.parameterTypes.isEmpty() &&
                    ref.returnType == "I"
            owner == "Lcom/honeyspace/ui/honeypots/freegrid/presentation/FreeGridCellLayout;" ->
                ref.name in
                    setOf(
                        "prepareSyncPosition",
                        "syncPosition",
                        "translateTarget",
                        "createLayoutParams",
                    )
            owner == "Lcom/honeyspace/ui/honeypots/freegrid/viewmodel/FreeGridViewModel;" ->
                ref.name == "updateToDropPoint"
            else -> false
        }
    }

    fun isVolumeCall(ref: MethodReference?): Boolean =
        when (ref?.definingClass) {
            "Lcom/android/systemui/volume/util/BlurEffect;",
            "Lcom/android/systemui/volume/util/ColorUtils;" -> true
            else -> false
        }
}
