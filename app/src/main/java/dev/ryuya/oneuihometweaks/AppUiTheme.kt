package dev.ryuya.oneuihometweaks

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import kotlin.jvm.JvmStatic

/* UI preferences belong to the manager app, never any hooked host. */
object AppUiTheme {
    @JvmStatic
    fun apply(activity: Activity) {
        val p = activity.getSharedPreferences("app_ui", Activity.MODE_PRIVATE)
        var mode = p.getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        if (mode != AppCompatDelegate.MODE_NIGHT_NO && mode != AppCompatDelegate.MODE_NIGHT_YES)
            mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        AppCompatDelegate.setDefaultNightMode(mode)
        if (p.getBoolean("dynamic", false)) DynamicColors.applyToActivityIfAvailable(activity)
    }
}
