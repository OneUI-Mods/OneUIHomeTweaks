package dev.ryuya.oneuihometweaks

/** Keep the caller-parameter alternatives inside the guarded blur-call contract. */
internal object VolumeStarDexContract {
    inline fun isBlurCall(
        owner: String?,
        name: String?,
        callerParameters: () -> List<CharSequence>,
    ): Boolean {
        if (owner != "Landroid/view/SemBlurInfo\$Builder;" || name != "setColorCurvePreset")
            return false
        return when (callerParameters().firstOrNull()) {
            "Landroid/view/View;",
            "Landroid/widget/ImageView;" -> true
            else -> false
        }
    }
}
