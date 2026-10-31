package dev.ryuya.oneuihometweaks

/** Null keeps Samsung's own value, including when only local labels are enabled. */
internal data class HomeColorSnapshot(val font: Int?, val status: Int?) {
    companion object {
        fun resolve(labelMode: Int, statusMode: Int, supportedWallpaper: Boolean, hints: Int?) =
            HomeColorSnapshot(
                ColorPolicy.darkValue(labelMode, supportedWallpaper, hints),
                ColorPolicy.darkValue(statusMode, supportedWallpaper, hints),
            )
    }
}
