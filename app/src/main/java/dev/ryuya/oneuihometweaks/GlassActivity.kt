package dev.ryuya.oneuihometweaks

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class GlassActivity : AppCompatActivity() {
    private var statePrefs: SharedPreferences? = null
    private var stateListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onDestroy() {
        stateListener?.let { statePrefs?.unregisterOnSharedPreferenceChangeListener(it) }
        super.onDestroy()
    }

    override fun onCreate(state: Bundle?) {
        AppUiTheme.apply(this)
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_glass)
        val root = requireViewById<View>(R.id.glass_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
        val toolbar = requireViewById<MaterialToolbar>(R.id.glass_toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        val prefs = getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
        val preview = requireViewById<GlassPreview>(R.id.glass_preview)
        val quick = requireViewById<MaterialSwitch>(R.id.glass_quick)
        val notifications = requireViewById<MaterialSwitch>(R.id.glass_notifications)
        val volume = requireViewById<MaterialSwitch>(R.id.glass_volume)
        val home = requireViewById<MaterialSwitch>(R.id.glass_home)
        val slider = requireViewById<Slider>(R.id.glass_strength)
        val value = requireViewById<TextView>(R.id.glass_value)
        val tintSlider = requireViewById<Slider>(R.id.glass_tint)
        val tintValue = requireViewById<TextView>(R.id.glass_tint_value)
        val supported = Build.VERSION.SDK_INT == 37 && Build.FINGERPRINT == Android17Hooks.FINGERPRINT
        requireViewById<TextView>(R.id.glass_requirements).setText(
            if (supported) R.string.glass_requirements else R.string.glass_preview_only,
        )
        quick.isChecked = prefs.getBoolean(GlassAppearance.QUICK, false)
        notifications.isChecked = prefs.getBoolean(GlassAppearance.NOTIFICATIONS, false)
        volume.isChecked = prefs.getBoolean(GlassAppearance.VOLUME, false)
        home.isChecked = prefs.getBoolean(GlassAppearance.HOME, false)
        slider.value = prefs.getInt(GlassAppearance.STRENGTH, GlassAppearance.DEFAULT_STRENGTH).coerceIn(0, 100).toFloat()
        tintSlider.value = GlassAppearance.tintPercent(
            prefs.getInt(GlassAppearance.TINT, GlassAppearance.DEFAULT_TINT),
        ).toFloat()
        val status = requireViewById<TextView>(R.id.glass_live_status)
        statePrefs = getSharedPreferences("host_status", Context.MODE_PRIVATE)
        fun displayStatus() {
            if (!supported) return
            val currentBoot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
            val states = requireNotNull(statePrefs)
            fun state(key: String, targetEnabled: Boolean, enabled: Boolean): Int {
                if (!enabled || slider.value <= 0f) return R.string.glass_state_off
                if (!prefs.getBoolean("enabled", true) || !targetEnabled) return R.string.glass_state_master_off
                if (states.getInt("$key.version", 0) != BuildConfig.VERSION_CODE ||
                    states.getInt("$key.boot", -2) != currentBoot) return R.string.glass_state_reload
                return when (states.getString("$key.state", "")) {
                    "DRAWN" -> R.string.glass_state_drawn
                    "UNSUPPORTED" -> R.string.glass_state_error
                    else -> R.string.glass_state_pending
                }
            }
            val shade = prefs.getBoolean("systemui_enabled", true)
            val launcher = prefs.getBoolean("home_enabled", true)
            val rows = listOf(
                R.string.glass_quick to state("com.android.systemui.glass_quick", shade, quick.isChecked),
                R.string.glass_notifications to state("com.android.systemui.glass_notifications", shade, notifications.isChecked),
                R.string.glass_volume to state("com.android.systemui.glass_volume", shade, volume.isChecked),
                R.string.glass_home to state("com.sec.android.app.launcher.glass_home", launcher, home.isChecked),
            )
            status.visibility = View.VISIBLE
            status.text = rows.joinToString("\n") { (name, result) -> getString(R.string.glass_status_line, getString(name), getString(result)) }
        }
        if (supported) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> displayStatus() }
            stateListener = listener
            statePrefs?.registerOnSharedPreferenceChangeListener(listener)
        }
        fun display() {
            val strength = slider.value.toInt()
            val tint = GlassAppearance.tintPercent(tintSlider.value.toInt())
            preview.appearance = GlassAppearance(
                quick = quick.isChecked, notifications = notifications.isChecked, home = home.isChecked,
                strength = strength, tint = tint, volume = volume.isChecked,
                volumeTheme = prefs.getInt("volume", 0),
            )
            value.text = getString(R.string.qs_percent, strength)
            tintValue.text = getString(R.string.qs_percent, tint)
            displayStatus()
        }
        fun changed() {
            if (supported) contentResolver.notifyChange(requireNotNull(ConfigProvider.URI), null)
        }
        for ((control, key) in listOf(quick to GlassAppearance.QUICK,
                   notifications to GlassAppearance.NOTIFICATIONS, volume to GlassAppearance.VOLUME,
                   home to GlassAppearance.HOME)) {
            control.setOnCheckedChangeListener { _, enabled ->
                if (supported) prefs.edit().putBoolean(key, enabled).apply()
                display()
                changed()
            }
        }
        fun bindSlider(control: Slider, key: String) {
            val notifyHosts = Runnable { changed() }
            control.addOnChangeListener { _, position, fromUser ->
                if (supported && fromUser) {
                    prefs.edit().putInt(key, position.toInt()).apply()
                    control.removeCallbacks(notifyHosts)
                    control.postDelayed(notifyHosts, 80L)
                }
                display()
            }
        }
        bindSlider(slider, GlassAppearance.STRENGTH)
        bindSlider(tintSlider, GlassAppearance.TINT)
        display()
    }
}
