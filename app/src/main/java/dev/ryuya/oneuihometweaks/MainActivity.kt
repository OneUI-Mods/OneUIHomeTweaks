package dev.ryuya.oneuihometweaks

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.provider.Settings.Global
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.Filter.FilterListener
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigation.NavigationBarView.OnItemSelectedListener
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private val commands: ExecutorService? = Executors.newSingleThreadExecutor()
    private var root: View? = null
    private var prefs: SharedPreferences? = null
    private var statusCards: Array<HookStatusCard?>? = null
    private var statusRequest: Long = 0
    private var focusStatusRequest: Long = 0
    private var gridSave: GridSaveUi? = null
    private val modes: IntArray = IntArray(3)
    private val modeIds: IntArray? = intArrayOf(R.id.label_mode, R.id.drawer_mode, R.id.volume_mode)
    private val keys: Array<String?>? = arrayOf<String?>("label", "drawer", "volume")
    private val switches: Array<String?>? =
        arrayOf<String?>(
            "enabled",
            "home_enabled",
            "systemui_enabled",
            "preview_enabled",
            "pages",
            "preview",
            "recents_hide_bar",
            "recents_live_tile",
            "clock_date_left",
            "diy_icon_grid",
            "folder_return",
            "settings_enabled",
        )
    private val switchIds: IntArray? =
        intArrayOf(
            R.id.master,
            R.id.home_enabled,
            R.id.systemui_enabled,
            R.id.preview_enabled,
            R.id.pages,
            R.id.preview,
            R.id.recents_hide_bar,
            R.id.recents_live_tile,
            R.id.clock_date_left,
            R.id.diy_icon_grid,
            R.id.folder_return,
            R.id.settings_enabled,
        )
    private val reports: ContentObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(self: Boolean, uri: Uri?) {
                showStatus()
                if (gridSave != null) gridSave!!.result()
            }
        }

    public override fun onCreate(state: Bundle?) {
        AppUiTheme.apply(this)
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false)
        setContentView(R.layout.activity_main)
        root = findViewById<View?>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(
            root!!,
            OnApplyWindowInsetsListener { view: View?, insets: WindowInsetsCompat? ->
                val safe = insets!!.getInsets(Type.systemBars() or Type.displayCutout())
                view!!.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                WindowInsetsCompat.CONSUMED
            },
        )
        ViewCompat.requestApplyInsets(root!!)
        val night: Boolean =
            ((getResources().getConfiguration().uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES)
        val controller = WindowCompat.getInsetsController(getWindow(), root!!)
        controller.setAppearanceLightStatusBars(!night)
        controller.setAppearanceLightNavigationBars(!night)
        prefs = getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
        if (prefs!!.contains("recents_portrait")) prefs!!.edit().remove("recents_portrait").apply()
        findViewById<View?>(R.id.layout_diagnostics)
            .setOnClickListener(
                android.view.View.OnClickListener { view: View? ->
                    startActivity(Intent(this, HomeLayoutActivity::class.java))
                }
            )
        for (i in modeIds!!.indices) {
            val index: Int = i
            var labels: Array<String?>? =
                if (i == 2)
                    arrayOf<String?>(
                        getString(R.string.volume_system),
                        getString(R.string.volume_light),
                        getString(R.string.volume_dark),
                    )
                else
                    arrayOf<String?>(
                        getString(if (i == 1) R.string.mode_native_auto else R.string.mode_auto),
                        getString(R.string.mode_black),
                        getString(R.string.mode_white),
                        getString(R.string.mode_off),
                    )
            if (i == 0) {
                labels = labels!!.copyOf(5)
                labels[ColorPolicy.LOCAL] = getString(R.string.mode_local)
            }
            val fallback: Int = if (i == 0) ColorPolicy.LOCAL else 0
            modes[i] = prefs!!.getInt(keys!![i], fallback)
            if (modes[i] < 0 || modes[i] >= labels!!.size) modes[i] = 0
            val selector = findViewById<MaterialAutoCompleteTextView?>(modeIds!![i])
            selector.setAdapter<ArrayAdapter<String?>?>(
                ArrayAdapter<String?>(this, android.R.layout.simple_list_item_1, labels!!)
            )
            selector.setText(labels!![modes[i]], false)
            selector.setOnItemClickListener(
                OnItemClickListener { p: AdapterView<*>?, v: View?, position: Int, id: Long ->
                    modes[index] = position
                    prefs!!.edit().putInt(keys!![index], position).apply()
                    val preview = findViewById<View?>(R.id.appearance_preview)
                    if (preview != null) preview.invalidate()
                    notifyConfig()
                }
            )
        }
        for (i in switches!!.indices) {
            val key: String? = switches!![i]
            val control = findViewById<MaterialSwitch?>(switchIds!![i])
            control.setChecked(
                prefs!!.getBoolean(
                    key,
                    (key != "preview") &&
                        !key!!.startsWith("recents_") &&
                        (key != "clock_date_left") &&
                        (key != "diy_icon_grid"),
                )
            )
            control.setOnCheckedChangeListener(
                OnCheckedChangeListener { button: CompoundButton?, checked: Boolean ->
                    prefs!!.edit().putBoolean(key, checked).apply()
                    notifyConfig()
                }
            )
        }
        val focusMode = findViewById<MaterialAutoCompleteTextView?>(R.id.recents_focus_mode)
        val focusChoices =
            arrayOf<String?>(
                getString(R.string.focus_native),
                getString(R.string.focus_notify),
                getString(R.string.focus_preserve),
            )
        val focusAdapter =
            ArrayAdapter<String?>(this, android.R.layout.simple_list_item_1, focusChoices)
        focusMode.setAdapter<ArrayAdapter<String?>?>(focusAdapter)
        focusMode.setText(
            focusChoices[RecentsFocusPolicy.mode(prefs!!.getInt("recents_focus_mode", 0))],
            false,
        )
        focusMode.setOnItemClickListener(
            OnItemClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: Long ->
                val selected = parent!!.getItemAtPosition(position)
                for (choice in focusChoices.indices) {
                    if (focusChoices[choice] == selected) {
                        prefs!!.edit().putInt("recents_focus_mode", choice).apply()
                        notifyConfig()
                        break
                    }
                }
            }
        )
        focusMode.setOnClickListener(
            android.view.View.OnClickListener { v: View? ->
                focusAdapter
                    .getFilter()
                    .filter(null, FilterListener { count: Int -> focusMode.showDropDown() })
            }
        )
        findViewById<View?>(R.id.focus_activate)
            .setOnClickListener(android.view.View.OnClickListener { v: View? -> applyFocus() })
        val infoViews =
            intArrayOf(
                R.id.local_note_button,
                R.id.diy_note_button,
                R.id.recents_bar_info,
                R.id.recents_live_info,
                R.id.clock_date_info,
                R.id.preview_info,
            )
        val infoTitles =
            intArrayOf(
                R.string.home_labels,
                R.string.diy_icon_grid,
                R.string.recents_hide_bar,
                R.string.recents_live_tile,
                R.string.clock_date_left,
                R.string.live_preview,
            )
        val infoMessages =
            intArrayOf(
                R.string.local_labels_note,
                R.string.diy_icon_grid_note,
                R.string.recents_hide_bar_note,
                R.string.recents_live_tile_note,
                R.string.clock_date_left_note,
                R.string.preview_note,
            )
        for (i in infoViews.indices) {
            val title: Int = infoTitles[i]
            val message: Int = infoMessages[i]
            findViewById<View?>(infoViews[i])
                .setOnClickListener(
                    android.view.View.OnClickListener { v: View? ->
                        MaterialAlertDialogBuilder(this)
                            .setTitle(title)
                            .setMessage(message)
                            .setPositiveButton(R.string.close, null)
                            .show()
                    }
                )
        }

        findViewById<View?>(R.id.quick_panel_settings)
            .setOnClickListener(
                android.view.View.OnClickListener { v: View? ->
                    startActivity(Intent(this, QuickPanelActivity::class.java))
                }
            )
        val navigation = findViewById<BottomNavigationView?>(R.id.navigation)
        val pageIds = intArrayOf(R.id.page_0, R.id.page_1, R.id.page_2, R.id.page_3)
        val navigationIds =
            intArrayOf(R.id.nav_home, R.id.nav_appearance, R.id.nav_status, R.id.nav_settings)
        navigation.setOnItemSelectedListener(
            OnItemSelectedListener { item: MenuItem? ->
                for (i in pageIds.indices) {
                    val selected: Boolean = item!!.getItemId() == navigationIds[i]
                    findViewById<View?>(pageIds[i])
                        .setVisibility(if (selected) View.VISIBLE else View.GONE)
                    if (selected) prefs!!.edit().putInt("selected_tab", i).apply()
                }
                true
            }
        )
        val selected = max(0, min(3, prefs!!.getInt("selected_tab", 0)))
        navigation.setSelectedItemId(navigationIds[selected])
        findViewById<View?>(R.id.restart_home)
            .setOnClickListener(
                android.view.View.OnClickListener { v: View? -> restartHome(v as MaterialButton) }
            )
        findViewById<View?>(R.id.reload_targets)
            .setOnClickListener(
                android.view.View.OnClickListener { v: View? ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.reload_title)
                        .setItems(
                            arrayOf<String?>(
                                getString(R.string.reload_volume),
                                getString(R.string.reload_preview),
                            ),
                            android.content.DialogInterface.OnClickListener { _, index: Int ->
                                MaterialAlertDialogBuilder(this)
                                    .setTitle(
                                        if (index == 0) R.string.reload_volume
                                        else R.string.reload_preview
                                    )
                                    .setMessage(R.string.reload_warning)
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton(
                                        android.R.string.ok,
                                        android.content.DialogInterface.OnClickListener { _, w: Int
                                            ->
                                            reloadTarget(index)
                                        },
                                    )
                                    .show()
                            },
                        )
                        .show()
                }
            )
        findViewById<View?>(R.id.clear_guard)
            .setOnClickListener(
                android.view.View.OnClickListener { v: View? ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.clear_guard)
                        .setMessage(R.string.clear_guard_note)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(
                            android.R.string.ok,
                            android.content.DialogInterface.OnClickListener { _, w: Int ->
                                resetProtection()
                            },
                        )
                        .show()
                }
            )
        findViewById<View?>(R.id.help)
            .setOnClickListener(
                android.view.View.OnClickListener { v: View? ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.help)
                        .setMessage(R.string.help_text)
                        .setPositiveButton(R.string.close, null)
                        .show()
                }
            )
        findViewById<View?>(R.id.language)
            .setOnClickListener(android.view.View.OnClickListener { v: View? -> language() })
        findViewById<View?>(R.id.licenses)
            .setOnClickListener(
                android.view.View.OnClickListener { v: View? ->
                    startActivity(Intent(this, LicensesActivity::class.java))
                }
            )
        getContentResolver().registerContentObserver(ConfigProvider.STATUS_URI!!, false, reports)
        gridSave = GridSaveUi(this)
        val uiPrefs = getSharedPreferences("app_ui", Context.MODE_PRIVATE)
        findViewById<View?>(R.id.app_theme)
            .setOnClickListener(
                android.view.View.OnClickListener { v: View? ->
                    val themeModes =
                        intArrayOf(
                            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                            AppCompatDelegate.MODE_NIGHT_NO,
                            AppCompatDelegate.MODE_NIGHT_YES,
                        )
                    val current =
                        uiPrefs.getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    val chosen: Int =
                        if (current == themeModes[1]) 1 else if (current == themeModes[2]) 2 else 0
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.app_theme)
                        .setSingleChoiceItems(
                            arrayOf<String?>(
                                getString(R.string.volume_system),
                                getString(R.string.volume_light),
                                getString(R.string.volume_dark),
                            ),
                            chosen,
                            android.content.DialogInterface.OnClickListener {
                                d: DialogInterface?,
                                w: Int ->
                                uiPrefs.edit().putInt("night", themeModes[w]).apply()
                                d!!.dismiss()
                                AppCompatDelegate.setDefaultNightMode(themeModes[w])
                            },
                        )
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            )
        val dynamic = findViewById<MaterialSwitch?>(R.id.dynamic_ui)
        dynamic.setChecked(uiPrefs.getBoolean("dynamic", false))
        dynamic.setOnCheckedChangeListener(
            OnCheckedChangeListener { b: CompoundButton?, on: Boolean ->
                uiPrefs.edit().putBoolean("dynamic", on).apply()
                recreate()
            }
        )
        findViewById<View?>(R.id.open_developer_options)
            .setOnClickListener(
                android.view.View.OnClickListener { v: View? ->
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    } catch (error: ActivityNotFoundException) {
                        snack(R.string.reload_failed)
                    }
                }
            )
        val cards = findViewById<LinearLayout?>(R.id.status_cards)
        statusCards = arrayOfNulls<HookStatusCard>(10)
        val titles =
            intArrayOf(
                R.string.scope_home,
                R.string.volume_theme,
                R.string.scope_preview,
                R.string.recents_hide_bar,
                R.string.mode_local,
                R.string.clock_date_left,
                R.string.diy_icon_grid,
                R.string.recents_live_tile,
                R.string.recents_focus_mode,
                R.string.scope_settings,
            )
        for (i in statusCards!!.indices) {
            statusCards!![i] = HookStatusCard(this, titles[i])
            cards.addView(statusCards!![i])
        }
        showStatus()
        if (!MainActivity.rootRequested) {
            MainActivity.rootRequested = true
            requestRoot()
        } else (findViewById<View?>(R.id.root_status) as TextView).setText(R.string.restart_note)
    }

    private fun notifyConfig() {
        getContentResolver().notifyChange(ConfigProvider.URI!!, null)
        applyFocus()
        showStatus()
    }

    private fun applyFocus() {
        commands!!.submit(
            Runnable {
                try {
                    val reply =
                        getContentResolver().call(ConfigProvider.URI!!, "focus_apply", null, null)
                    Log.i(
                        "OneUIHomeTweaks",
                        "RECENTS_FOCUS_UI_RESULT " +
                            (if (reply == null) "missing" else reply.getString("state")),
                    )
                } catch (error: RuntimeException) {
                    Log.w("OneUIHomeTweaks", "Focus backend unavailable", error)
                }
                runOnUiThread(Runnable { this.showStatus() })
            }
        )
    }

    private fun showFocusStatus() {
        val request: Long = ++focusStatusRequest
        val on: Boolean =
            prefs!!.getBoolean("enabled", true) &&
                prefs!!.getBoolean("home_enabled", true) &&
                prefs!!.getBoolean("recents_live_tile", false) &&
                RecentsFocusPolicy.mode(prefs!!.getInt("recents_focus_mode", 0)) != 0
        findViewById<View?>(R.id.focus_activate).setEnabled(on)
        statusCards!![8]!!.update(
            if (on) R.string.status_unconfirmed else R.string.status_off,
            if (on) "" else "OFF",
            on,
            if (on) getString(R.string.recents_focus_note) else "",
        )
        if (!on) return
        commands!!.submit(
            Runnable {
                var reply: Bundle? = null
                try {
                    reply =
                        getContentResolver().call(ConfigProvider.URI!!, "focus_status", null, null)
                } catch (ignored: RuntimeException) {}
                val state: String? =
                    if (reply == null) "backend-missing" else reply.getString("state", "error")
                runOnUiThread(
                    Runnable {
                        if (isDestroyed() || request != focusStatusRequest) return@Runnable
                        val label: Int =
                            when (state) {
                                "ready" -> R.string.status_ready
                                "state-dispatched" -> R.string.focus_dispatched
                                "disarmed" -> R.string.focus_disarmed
                                "backend-missing" -> R.string.focus_backend_missing
                                else -> R.string.status_error
                            }
                        val visual: String? =
                            when (state) {
                                "ready",
                                "state-dispatched" -> "ACTIVE"
                                "disarmed" -> "OFF"
                                "backend-missing" -> "UNSUPPORTED"
                                else -> "ERROR"
                            }
                        statusCards!![8]!!.update(
                            label,
                            visual!!,
                            true,
                            getString(R.string.recents_focus_note),
                        )
                    }
                )
            }
        )
    }

    private fun showStatus() {
        if (isDestroyed() || statusCards == null) return
        showFocusStatus()
        if (gridSave != null) gridSave!!.availability()
        val report = getSharedPreferences("host_status", Context.MODE_PRIVATE)
        val dateOn: Boolean =
            prefs!!.getBoolean("enabled", true) &&
                prefs!!.getBoolean("systemui_enabled", true) &&
                prefs!!.getBoolean("clock_date_left", false)
        val dateKey: String = "com.android.systemui.clock_date"
        var dateState: String? = if (dateOn) report.getString(dateKey + ".state", "") else "OFF"
        val dateReported = report.getLong(dateKey + ".elapsed", 0)
        val boot =
            Global.getInt(
                getContentResolver(),
                Global.BOOT_COUNT,
                -1,
            )
        if (
            dateOn &&
                !StatusFreshness.usable(
                    dateState,
                    report.getInt(dateKey + ".boot", -1),
                    boot,
                    dateReported,
                    SystemClock.elapsedRealtime(),
                    statusRequest,
                )
        )
            dateState = ""
        var dateLabel: Int =
            if (!dateOn) R.string.status_off
            else
                when (dateState) {
                    "ACTIVE" -> R.string.status_active
                    "OFF" -> R.string.status_unused
                    "UNSUPPORTED" -> R.string.status_unsupported
                    "ERROR" -> R.string.status_error
                    else -> R.string.status_unconfirmed
                }
        // A host-wide crash guard/compatibility gate also prevents this feature from loading.
        if (dateOn && dateState!!.isEmpty()) {
            val hostState = report.getString("com.android.systemui.state", "")
            if (
                ("CRASH_GUARD" == hostState || "UNSUPPORTED" == hostState) &&
                    StatusFreshness.usable(
                        hostState,
                        report.getInt("com.android.systemui.boot", -1),
                        boot,
                        report.getLong("com.android.systemui.elapsed", 0),
                        SystemClock.elapsedRealtime(),
                        statusRequest,
                    )
            ) {
                dateState = hostState
                dateLabel =
                    if ("CRASH_GUARD" == hostState) R.string.status_guard
                    else R.string.status_unsupported
            }
        }
        statusCards!![5]!!.update(
            dateLabel,
            dateState!!,
            dateOn,
            if (dateOn && !dateState!!.isEmpty()) report.getString(dateKey + ".detail", "")!!
            else "",
        )
        for (i in ConfigProvider.HOSTS!!.indices) {
            val host: String? = ConfigProvider.HOSTS[i]
            val on: Boolean =
                prefs!!.getBoolean("enabled", true) &&
                    prefs!!.getBoolean(ConfigProvider.HOST_SWITCHES!![i], true)
            var state = report.getString(host + ".state", "")
            val reported = report.getLong(host + ".elapsed", 0)
            // ACTIVE/OFF describe a live process and need a fresh response. Terminal diagnostics
            // remain
            // useful when that process deliberately installs no observers (especially CRASH_GUARD).
            if (
                !StatusFreshness.usable(
                    state,
                    report.getInt(host + ".boot", -1),
                    boot,
                    reported,
                    SystemClock.elapsedRealtime(),
                    statusRequest,
                )
            )
                state = ""
            val label: Int =
                if (!on) R.string.status_off
                else
                    when (state) {
                        "ACTIVE" -> R.string.status_active
                        "OFF" -> R.string.status_unused
                        "CRASH_GUARD" -> R.string.status_guard
                        "UNSUPPORTED" -> R.string.status_unsupported
                        "ERROR" -> R.string.status_error
                        else -> R.string.status_unconfirmed
                    }
            statusCards!![ConfigProvider.HOST_STATUS_CARDS!![i]]!!.update(
                label,
                state!!,
                on,
                if (on && !state!!.isEmpty()) report.getString(host + ".detail", "")!! else "",
            )
            if (i == 0) {
                val detail = report.getString(host + ".detail", "")
                val gridOn: Boolean = on && prefs!!.getBoolean("diy_icon_grid", false)
                var gridState: String? = if (gridOn) state else "OFF"
                var gridLabel: Int = if (gridOn) label else R.string.status_off
                if (gridOn && "ACTIVE" == state) {
                    if (detail!!.contains("diyGrid=error:")) {
                        gridState = "ERROR"
                        gridLabel = R.string.status_error
                    } else if (!detail!!.contains("diyGrid=active")) {
                        gridState = ""
                        gridLabel = R.string.status_unconfirmed
                    }
                }
                statusCards!![6]!!.update(
                    gridLabel,
                    gridState!!,
                    gridOn,
                    if (gridOn && !gridState!!.isEmpty())
                        detail!!.substring(max(0, detail!!.indexOf("diyGrid=")))
                    else "",
                )
                val localOn: Boolean =
                    on && prefs!!.getInt("label", ColorPolicy.LOCAL) == ColorPolicy.LOCAL
                var localState: String? = if (!localOn) "OFF" else state
                var localLabel: Int = if (!localOn) R.string.status_off else label
                if (localOn && "ACTIVE" == state) {
                    if (detail!!.contains("localLabels=error:")) {
                        localState = "ERROR"
                        localLabel = R.string.status_error
                    } else if (detail!!.contains("localLabels=unavailable")) {
                        localState = "UNSUPPORTED"
                        localLabel = R.string.status_unsupported
                    } else if (detail!!.contains("wallpaper=static")) {
                        localState = "OFF"
                        localLabel = R.string.status_unused
                    } else if (detail!!.contains("localLabels=geometry-skipped")) {
                        localState = "UNSUPPORTED"
                        localLabel = R.string.status_unsupported
                    } else if (!detail!!.contains("localLabels=active")) {
                        localState = ""
                        localLabel = R.string.status_unconfirmed
                    }
                }
                statusCards!![4]!!.update(
                    localLabel,
                    localState!!,
                    localOn,
                    if (localOn && !localState!!.isEmpty())
                        detail!!
                            .substring(max(0, detail!!.indexOf("localLabels=")))
                            .split(";".toRegex(), limit = 2)
                            .toTypedArray()[0]
                    else "",
                )
                if (statusCards!!.size > 7) {
                    val liveOn: Boolean = on && prefs!!.getBoolean("recents_live_tile", false)
                    var liveState: String? = state
                    var liveLabel: Int = label
                    val liveDetail: String? =
                        if (detail!!.contains("liveRecents="))
                            detail!!
                                .substring(detail!!.indexOf("liveRecents="))
                                .split(";".toRegex(), limit = 2)
                                .toTypedArray()[0]
                        else ""
                    if (!liveOn) {
                        liveState = "OFF"
                        liveLabel = R.string.status_off
                    } else if ("ACTIVE" == state) {
                        if (liveDetail!!.contains("unavailable")) {
                            liveState = "UNSUPPORTED"
                            liveLabel = R.string.status_unsupported
                        } else if (liveDetail!!.contains("error:")) {
                            liveState = "ERROR"
                            liveLabel = R.string.status_error
                        } else if (liveDetail!!.endsWith("=held"))
                            liveLabel = R.string.status_active
                        else if (
                            liveDetail!!.endsWith("=ready") || liveDetail!!.endsWith("=gesture")
                        )
                            liveLabel = R.string.status_ready
                        else {
                            liveState = ""
                            liveLabel = R.string.status_unconfirmed
                        }
                    }
                    statusCards!![7]!!.update(
                        liveLabel,
                        liveState!!,
                        liveOn,
                        if (liveOn) liveDetail!! else "",
                    )
                }
                val barOn: Boolean = on && prefs!!.getBoolean("recents_hide_bar", false)
                var barState: String? = state
                var barLabel: Int = label
                if (!barOn) {
                    barState = "OFF"
                    barLabel = R.string.status_off
                } else if ("ACTIVE" == state) {
                    if (detail!!.contains("recentsBar=unavailable")) {
                        barState = "UNSUPPORTED"
                        barLabel = R.string.status_unsupported
                    } else if (detail!!.contains("recentsBar=error:")) {
                        barState = "ERROR"
                        barLabel = R.string.status_error
                    } else if (
                        detail!!.contains("recentsBar=buttons-skipped") ||
                            detail!!.contains("recentsBar=system")
                    ) {
                        barState = "OFF"
                        barLabel = R.string.status_unused
                    } else if (
                        !detail!!.contains("recentsBar=ready") &&
                            !detail!!.contains("recentsBar=hidden")
                    ) {
                        barState = ""
                        barLabel = R.string.status_unconfirmed
                    }
                }
                statusCards!![3]!!.update(
                    barLabel,
                    barState!!,
                    barOn,
                    if (barOn && !barState!!.isEmpty())
                        if (detail!!.contains("recentsBar=buttons-skipped"))
                            getString(R.string.recents_bar_buttons)
                        else if (detail!!.contains("recentsBar=system"))
                            getString(R.string.recents_bar_system)
                        else detail!!.substring(max(0, detail!!.indexOf("recentsBar=")))
                    else "",
                )
            }
        }
    }

    private fun language() {
        val tags = arrayOf<String?>("", "en", "ja", "zh-CN", "zh-TW", "ko")
        val names =
            arrayOf<String?>(
                getString(R.string.follow_system),
                "English",
                "日本語",
                "简体中文",
                "繁體中文",
                "한국어",
            )
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        var selected: Int = 0
        for (i in 1 until tags.size) if (tags[i] == current) selected = i
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language_title)
            .setSingleChoiceItems(
                names,
                selected,
                android.content.DialogInterface.OnClickListener { d: DialogInterface?, index: Int ->
                    prefs!!.edit().putString("language", tags[index]).apply()
                    d!!.dismiss()
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(tags[index])
                    )
                },
            )
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun snack(message: Int) {
        if (!isDestroyed()) Snackbar.make(root!!, message, Snackbar.LENGTH_LONG).show()
    }

    private fun requestRoot() {
        commands!!.execute(
            Runnable {
                val result = MainActivity.command("id", 30, true)
                runOnUiThread(
                    Runnable {
                        if (!isDestroyed())
                            (findViewById<View?>(R.id.root_status) as TextView).setText(
                                if (result) R.string.root_granted else R.string.root_missing
                            )
                    }
                )
            }
        )
    }

    private fun restartHome(button: MaterialButton) {
        button.setEnabled(false)
        button.setText(R.string.restart_busy)
        commands!!.execute(
            Runnable {
                val success =
                    MainActivity.command(
                        "am force-stop --user 0 com.sec.android.app.launcher && am start --user 0 -a" +
                            " android.intent.action.MAIN -c android.intent.category.HOME",
                        20,
                        false,
                    )
                runOnUiThread(
                    Runnable {
                        if (isDestroyed()) return@Runnable
                        button.setEnabled(true)
                        button.setText(R.string.restart_home)
                        snack(if (success) R.string.restart_done else R.string.restart_failed)
                    }
                )
            }
        )
    }

    private fun reloadTarget(index: Int) {
        // Package names and operations are constants, never user-provided shell text.
        val cmd: String? =
            if (index == 0)
                "pid=$(pidof com.android.systemui); test -n \"\$pid\" && kill -TERM \$pid"
            else "am force-stop --user 0 com.samsung.android.app.dressroom"
        commands!!.execute(
            Runnable {
                val success = MainActivity.command(cmd, 20, false)
                runOnUiThread(
                    Runnable {
                        snack(if (success) R.string.reload_done else R.string.reload_failed)
                    }
                )
            }
        )
    }

    private fun resetProtection() {
        val cmd = StringBuilder("rm -f")
        for (root in arrayOf<String>("/data/user/0/", "/data/user_de/0/")) for (host in
            ConfigProvider.HOSTS!!) for (suffix in arrayOf<String>("", ".bak", ".new")) cmd.append(
                ' '
            )
            .append(root)
            .append(host)
            .append("/files/")
            .append(CrashGuard.FILE)
            .append(suffix)
        commands!!.execute(
            Runnable {
                val success = MainActivity.command(cmd.toString(), 20, false)
                runOnUiThread(
                    Runnable {
                        if (success) {
                            getSharedPreferences("host_status", Context.MODE_PRIVATE)
                                .edit()
                                .clear()
                                .apply()
                            showStatus()
                        }
                        snack(
                            if (success) R.string.guard_reset_done else R.string.guard_reset_failed
                        )
                    }
                )
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (prefs != null) {
            statusRequest = SystemClock.elapsedRealtime()
            getContentResolver().notifyChange(ConfigProvider.URI!!, null)
            showStatus()
        }
    }

    override fun onDestroy() {
        if (gridSave != null) gridSave!!.destroy()
        getContentResolver().unregisterContentObserver(reports)
        commands!!.shutdown()
        super.onDestroy()
    }

    companion object {
        private var rootRequested: Boolean = false

        @JvmStatic
        private fun command(cmd: String?, seconds: Int, verifyRoot: Boolean): Boolean {
            var process: Process? = null
            try {
                process = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                if (
                    !process.waitFor(seconds.toLong(), TimeUnit.SECONDS) || process.exitValue() != 0
                )
                    return false
                if (!verifyRoot) return true
                val out = ByteArrayOutputStream()
                val b = ByteArray(1024)
                var n: Int
                while (
                    (process.getInputStream().read(b).also { n = it }) != -1 && out.size() < 16384
                ) out.write(b, 0, n)
                return String(out.toByteArray(), StandardCharsets.UTF_8).contains("uid=0(")
            } catch (e: Exception) {
                Log.w("OneUIHomeTweaksUI", "Root operation failed", e)
                return false
            } finally {
                if (process != null) process.destroy()
            }
        }
    }
}
