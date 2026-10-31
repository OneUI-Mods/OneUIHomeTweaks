package dev.ryuya.oneuihometweaks

import android.content.Context
import kotlin.jvm.JvmStatic

/* Mode values verified against Samsung NavigationModeSource: 0/1 buttons, 2/3 gestures. */
object NavigationPolicy {
    @JvmStatic
    fun gestures(context: Context): Boolean {
        try {
            val resources = context.getResources()
            val id = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
            val mode: Int = if (id == 0) -1 else resources.getInteger(id)
            return mode == 2 || mode == 3
        } catch (unavailable: RuntimeException) {
            return false
        }
    }

    @JvmStatic
    fun hide(enabled: Boolean, mode: Int, hint: Int, mainFullscreen: Boolean): Boolean {
        return enabled && mainFullscreen && (mode == 2 || mode == 3) && hint == 1
    }

    @JvmStatic
    fun restoreVisible(nativeVisible: Boolean, mode: Int, hint: Int): Boolean {
        return nativeVisible && !((mode == 2 || mode == 3) && hint == 0)
    }
}
