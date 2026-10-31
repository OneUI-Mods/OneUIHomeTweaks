package dev.ryuya.oneuihometweaks

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.View.OnClickListener
import android.widget.CompoundButton
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.slider.Slider.OnChangeListener
import com.google.android.material.slider.Slider.OnSliderTouchListener
import java.util.HashSet

/* Dedicated controls keep the appearance tab short and the four independent values visible. */
class QuickPanelActivity : AppCompatActivity() {
    private var prefs: SharedPreferences? = null
    private var preview: QuickPanelPreview? = null
    private val dragging: MutableSet<Slider?> = HashSet<Slider?>()
    private val sliders: Array<Slider?> = arrayOfNulls<Slider>(4)
    private val labels: Array<TextView?> = arrayOfNulls<TextView>(4)
    private val status: ContentObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(self: Boolean, uri: Uri?) {
                showStatus()
            }
        }

    public override fun onCreate(state: Bundle?) {
        AppUiTheme.apply(this)
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false)
        setContentView(R.layout.activity_quick_panel)
        val root = findViewById<View?>(R.id.qs_root)
        ViewCompat.setOnApplyWindowInsetsListener(
            root,
            OnApplyWindowInsetsListener { v: View?, insets: WindowInsetsCompat? ->
                val safe = insets!!.getInsets(Type.systemBars() or Type.displayCutout())
                v!!.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                WindowInsetsCompat.CONSUMED
            },
        )
        ViewCompat.requestApplyInsets(root)
        val toolbar = findViewById<MaterialToolbar?>(R.id.qs_toolbar)
        toolbar.setNavigationOnClickListener(OnClickListener { v: View? -> finish() })
        prefs = getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
        preview = findViewById<QuickPanelPreview?>(R.id.qs_preview)
        val ids = intArrayOf(R.id.qs_brightness, R.id.qs_volume, R.id.qs_dark, R.id.qs_sound)
        val values =
            intArrayOf(
                R.id.qs_brightness_value,
                R.id.qs_volume_value,
                R.id.qs_dark_value,
                R.id.qs_sound_value,
            )
        for (i in 0..3) {
            val index: Int = i
            val slider = findViewById<Slider?>(ids[i])
            sliders[i] = slider
            labels[i] = findViewById<TextView?>(values[i])
            slider.setValueFrom(QuickPanelPolicy.MIN.toFloat())
            slider.setValueTo(QuickPanelPolicy.MAX.toFloat())
            slider.setStepSize(QuickPanelPolicy.STEP.toFloat())
            slider.setValue(
                QuickPanelPolicy.percent(prefs!!.getInt(QuickPanelPolicy.KEYS!![i], 100)).toFloat()
            )
            slider.setLabelFormatter(
                LabelFormatter { value: Float -> getString(R.string.qs_percent, value.toInt()) }
            )
            slider.addOnChangeListener(
                OnChangeListener { s: Slider?, value: Float, user: Boolean -> updateLabels() }
            )
            slider.addOnSliderTouchListener(
                object : OnSliderTouchListener {
                    override fun onStartTrackingTouch(s: Slider) {
                        dragging.add(s)
                    }

                    override fun onStopTrackingTouch(s: Slider) {
                        dragging.remove(s)
                        save(index)
                    }
                }
            )
            // Keyboard and accessibility increments do not dispatch touch-end callbacks.
            slider.addOnChangeListener(
                OnChangeListener { s: Slider?, value: Float, user: Boolean ->
                    if (user && !dragging.contains(s)) save(index)
                }
            )
        }
        val master = findViewById<MaterialSwitch?>(R.id.qs_enabled)
        master.setChecked(prefs!!.getBoolean("qs_enabled", false))
        master.setOnCheckedChangeListener(
            OnCheckedChangeListener { button: CompoundButton?, checked: Boolean ->
                prefs!!.edit().putBoolean("qs_enabled", checked).apply()
                notifyConfig()
                setControls(checked)
            }
        )
        val design = findViewById<MaterialSwitch?>(R.id.qs_legacy_design)
        design.setChecked(
            Build.VERSION.SDK_INT == 36 || prefs!!.getBoolean("qs_legacy_design", false)
        )
        design.setEnabled(Build.VERSION.SDK_INT == 37)
        design.setOnCheckedChangeListener(
            OnCheckedChangeListener { button: CompoundButton?, checked: Boolean ->
                prefs!!.edit().putBoolean("qs_legacy_design", checked).apply()
                notifyConfig()
                setControls(master.isChecked())
                updateLabels()
            }
        )
        findViewById<View?>(R.id.qs_legacy_preset)
            .setOnClickListener(
                OnClickListener { v: View? -> applySizes(intArrayOf(110, 110, 105, 105)) }
            )
        setControls(master.isChecked())
        findViewById<View?>(R.id.qs_reset)
            .setOnClickListener(
                OnClickListener { v: View? -> applySizes(intArrayOf(100, 100, 100, 100)) }
            )
        updateLabels()
    }

    private fun applySizes(values: IntArray) {
        val edit = prefs!!.edit()
        for (i in sliders.indices) {
            sliders[i]!!.setValue(values[i].toFloat())
            edit.putInt(QuickPanelPolicy.KEYS!![i], values[i])
        }
        edit.apply()
        notifyConfig()
        updateLabels()
    }

    private fun save(index: Int) {
        prefs!!
            .edit()
            .putInt(QuickPanelPolicy.KEYS!![index], sliders[index]!!.getValue().toInt())
            .apply()
        notifyConfig()
    }

    private fun notifyConfig() {
        getContentResolver().notifyChange(ConfigProvider.URI!!, null)
    }

    private fun setControls(enabled: Boolean) {
        for (slider in sliders) slider!!.setEnabled(enabled)
        findViewById<View?>(R.id.qs_legacy_design)
            .setEnabled(enabled && Build.VERSION.SDK_INT == 37)
        findViewById<View?>(R.id.qs_legacy_preset)
            .setEnabled(
                enabled &&
                    (Build.VERSION.SDK_INT == 36 || prefs!!.getBoolean("qs_legacy_design", false))
            )
    }

    private fun updateLabels() {
        for (i in sliders.indices) {
            if (sliders[i] == null) return
            labels[i]!!.setText(getString(R.string.qs_percent, sliders[i]!!.getValue().toInt()))
        }
    }

    private fun showStatus() {
        val statusPrefs = getSharedPreferences("host_status", Context.MODE_PRIVATE)
        var state = statusPrefs.getString("com.android.systemui.quick_panel.state", "WAITING")
        if ("CRASH_GUARD" == statusPrefs.getString("com.android.systemui.state", ""))
            state = "CRASH_GUARD"
        val message: Int =
            when (state) {
                "ACTIVE" -> R.string.qs_active
                "PARTIAL" -> R.string.qs_legacy_unavailable
                "OFF" -> R.string.qs_off
                "UNSUPPORTED",
                "ERROR",
                "CRASH_GUARD" -> R.string.qs_unavailable
                else -> R.string.qs_waiting
            }
        (findViewById<View?>(R.id.qs_status) as TextView).setText(message)
    }

    override fun onStart() {
        super.onStart()
        getContentResolver().registerContentObserver(ConfigProvider.STATUS_URI!!, false, status)
        showStatus()
        preview!!.start()
    }

    override fun onDestroy() {
        preview!!.destroy()
        super.onDestroy()
    }

    override fun onStop() {
        preview!!.stop()
        getContentResolver().unregisterContentObserver(status)
        super.onStop()
    }
}
