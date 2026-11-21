package dev.ryuya.oneuihometweaks

import android.view.ViewGroup

/** Tracks the geometry checked by the DIY layout observers on each scroll callback. */
internal class WorkspaceGeometry {
    private var width = -1
    private var height = -1
    private var children = -1
    private var orientation = -1
    private var widthDp = -1
    private var heightDp = -1
    private var densityDpi = -1

    fun update(view: ViewGroup): Boolean {
        val config = view.resources.configuration
        if (width == view.width && height == view.height && children == view.childCount &&
            orientation == config.orientation && widthDp == config.screenWidthDp &&
            heightDp == config.screenHeightDp && densityDpi == config.densityDpi
        ) return false
        width = view.width
        height = view.height
        children = view.childCount
        orientation = config.orientation
        widthDp = config.screenWidthDp
        heightDp = config.screenHeightDp
        densityDpi = config.densityDpi
        return true
    }
}
