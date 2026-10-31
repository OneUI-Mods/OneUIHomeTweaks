package dev.ryuya.oneuihometweaks

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import java.io.IOException
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField

class ConfigProvider : ContentProvider() {
    private var focus: RecentsFocusRegistry? = null
    @Volatile private var quickPanelPreview: IBinder? = null

    override fun onCreate(): Boolean {
        focus = RecentsFocusRegistry(getContext()!!)
        return true
    }

    private fun hostCaller(): String? {
        val uid = Binder.getCallingUid()
        if (uid / 100000 != 0) throw SecurityException("Owner user only")
        val caller = getCallingPackage()
        for (host in ConfigProvider.HOSTS!!) try {
            if (
                host == caller &&
                    getContext()!!.getPackageManager().getApplicationInfo(host!!, 0).uid == uid
            )
                return host
        } catch (ignored: NameNotFoundException) {}
        throw SecurityException("Unsupported caller")
    }

    override fun query(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        args: Array<String?>?,
        sort: String?,
    ): Cursor {
        require(ConfigProvider.URI == uri) { "Unknown URI" }
        if (Binder.getCallingUid() != Process.myUid()) hostCaller()
        val p = getContext()!!.getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
        val c =
            MatrixCursor(
                arrayOf<String>(
                    "label",
                    "status",
                    "enabled",
                    "pages",
                    "drawer",
                    "volume",
                    "preview",
                    "home_enabled",
                    "systemui_enabled",
                    "preview_enabled",
                    "recents_portrait",
                    "recents_hide_bar",
                    "clock_date_left",
                    "diy_icon_grid",
                    "recents_live_tile",
                    "recents_focus_mode",
                    "qs_enabled",
                    "qs_brightness",
                    "qs_volume",
                    "qs_dark",
                    "qs_sound",
                    "qs_legacy_design",
                    "folder_return",
                    "settings_enabled",
                )
            )
        c.addRow(
            arrayOf<Any>(
                p.getInt("label", ColorPolicy.LOCAL),
                ColorPolicy.OFF,
                if (p.getBoolean("enabled", true)) 1 else 0,
                if (p.getBoolean("pages", true)) 1 else 0,
                p.getInt("drawer", ColorPolicy.AUTO),
                p.getInt("volume", 0),
                if (p.getBoolean("preview", false)) 1 else 0,
                if (p.getBoolean("home_enabled", true)) 1 else 0,
                if (p.getBoolean("systemui_enabled", true)) 1 else 0,
                if (p.getBoolean("preview_enabled", true)) 1 else 0,
                0, // Retired orientation option: tell pre-0.11 Launcher hooks to restore native
                // requests.
                if (p.getBoolean("recents_hide_bar", false)) 1 else 0,
                if (p.getBoolean("clock_date_left", false)) 1 else 0,
                if (p.getBoolean("diy_icon_grid", false)) 1 else 0,
                if (p.getBoolean("recents_live_tile", false)) 1 else 0,
                RecentsFocusPolicy.mode(p.getInt("recents_focus_mode", 0)),
                if (p.getBoolean("qs_enabled", false)) 1 else 0,
                QuickPanelPolicy.percent(p.getInt("qs_brightness", 100)),
                QuickPanelPolicy.percent(p.getInt("qs_volume", 100)),
                QuickPanelPolicy.percent(p.getInt("qs_dark", 100)),
                QuickPanelPolicy.percent(p.getInt("qs_sound", 100)),
                if (p.getBoolean("qs_legacy_design", false)) 1 else 0,
                if (p.getBoolean("folder_return", true)) 1 else 0,
                if (p.getBoolean("settings_enabled", true)) 1 else 0,
            )
        )
        return c
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if ("layout_config" == method || "layout_append" == method) {
            if (Binder.getCallingUid() != Process.myUid() && HookEntry.LAUNCHER != hostCaller())
                throw SecurityException("Launcher only")
            if ("layout_config" == method) {
                val prefs =
                    getContext()!!.getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
                val config = Bundle()
                config.putBoolean(
                    "protect",
                    prefs.getBoolean("enabled", true) &&
                        prefs.getBoolean("home_enabled", true) &&
                        prefs.getBoolean("layout_protect", true),
                )
                config.putBoolean("logging", prefs.getBoolean("layout_logging", true))
                return config
            }
            try {
                HomeLayoutJournal.append(
                    getContext()!!,
                    extras?.getString("records"),
                )
                return Bundle.EMPTY
            } catch (error: IOException) {
                throw IllegalStateException("Journal unavailable", error)
            }
        }

        if (QuickPanelPreviewIpc.REGISTER == method) {
            if ("com.android.systemui" != hostCaller()) throw SecurityException("SystemUI only")
            val backend: IBinder? = if (extras == null) null else extras.getBinder("backend")
            requireNotNull(backend) { "Missing preview backend" }
            quickPanelPreview = backend
            getContext()!!.getContentResolver().notifyChange(ConfigProvider.STATUS_URI!!, null)
            return Bundle.EMPTY
        }
        if (QuickPanelPreviewIpc.BACKEND == method) {
            if (Binder.getCallingUid() != Process.myUid())
                throw SecurityException("Module app only")
            val result = Bundle()
            result.putBinder("backend", quickPanelPreview)
            return result
        }
        if ("focus_register" == method) {
            if (HookEntry.LAUNCHER != hostCaller()) throw SecurityException("Launcher only")
            try {
                return focus!!.register(extras)
            } catch (error: Exception) {
                throw IllegalStateException("Focus backend unavailable", error)
            }
        }
        if ("focus_status" == method || "focus_apply" == method) {
            if (Binder.getCallingUid() != Process.myUid())
                throw SecurityException("Module app only")
            return if ("focus_apply" == method) focus!!.apply() else focus!!.status()
        }
        if ("take_grid_request" == method || "grid_result" == method) {
            if ("com.sec.android.app.launcher" != hostCaller())
                throw SecurityException("Launcher only")
            return if ("take_grid_request" == method) GridSaveStore.take(getContext()!!)
            else GridSaveStore.result(getContext()!!, extras)
        }
        require(!("report_status" != method || extras == null)) { "Unsupported call" }
        var host = hostCaller()
        if (host != extras.getString("host")) throw SecurityException("Host mismatch")
        val feature = extras.getString("feature")
        if (feature != null) {
            if (
                "com.android.systemui" != host ||
                    ("clock_date" != feature && "quick_panel" != feature)
            )
                throw SecurityException("Unsupported feature")
            host += "." + feature
        }
        val state = extras.getString("state", "ERROR")
        var detail = extras.getString("detail", "")
        if (detail.length > 512) detail = detail.substring(0, 512)
        getContext()!!
            .getSharedPreferences("host_status", Context.MODE_PRIVATE)
            .edit()
            .putString(host + ".state", state)
            .putString(host + ".detail", detail)
            .putLong(host + ".elapsed", extras.getLong("elapsed"))
            .putInt(host + ".pid", extras.getInt("pid"))
            .putInt(host + ".boot", extras.getInt("boot", -1))
            .apply()
        getContext()!!.getContentResolver().notifyChange(ConfigProvider.STATUS_URI!!, null)
        val result = Bundle()
        result.putBoolean("saved", true)
        return result
    }

    override fun getType(u: Uri): String? {
        return "vnd.android.cursor.item/vnd.oneuihometweaks.modes"
    }

    override fun insert(u: Uri, v: ContentValues?): Uri? {
        throw SecurityException("Read only")
    }

    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String?>?): Int {
        throw SecurityException("Read only")
    }

    override fun delete(u: Uri, s: String?, a: Array<String?>?): Int {
        throw SecurityException("Read only")
    }

    companion object {
        @JvmField val URI: Uri? = Uri.parse("content://dev.ryuya.oneuihometweaks.config/modes")
        @JvmField
        val STATUS_URI: Uri? = Uri.parse("content://dev.ryuya.oneuihometweaks.config/status")
        const val PREFS: String = "modes"
        @JvmField
        val HOSTS: Array<String?>? =
            arrayOf<String?>(
                "com.sec.android.app.launcher",
                "com.android.systemui",
                "com.samsung.android.app.dressroom",
                "com.android.settings",
            )

        @JvmField
        val HOST_SWITCHES: Array<String?>? =
            arrayOf<String?>(
                "home_enabled",
                "systemui_enabled",
                "preview_enabled",
                "settings_enabled",
            )
        @JvmField val HOST_STATUS_CARDS: IntArray? = intArrayOf(0, 1, 2, 9)
    }
}
