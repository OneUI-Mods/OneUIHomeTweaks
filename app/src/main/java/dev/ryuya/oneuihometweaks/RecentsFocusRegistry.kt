package dev.ryuya.oneuihometweaks

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import kotlin.jvm.Synchronized

/*
 * App-owned broker. The backend is discovered by Launcher; source apps cannot register or use it.
 */
class RecentsFocusRegistry(context: Context) {
    private val context: Context
    private val anchor: IBinder = Binder()
    private var backend: IBinder? = null
    private var viaTaskManager = false

    init {
        this.context = context
    }

    @Synchronized
    @Throws(Exception::class)
    fun register(data: Bundle?): Bundle {
        val candidate: IBinder? = if (data == null) null else data.getBinder("backend")
        require(!(candidate == null || !candidate.isBinderAlive())) { "Live backend required" }
        val transport = data?.getBoolean(RecentsFocusWire.TASK_MANAGER) == true
        val status = RecentsFocusWire.call(candidate, RecentsFocusWire.STATUS, null, transport)
        backend = candidate
        viaTaskManager = transport
        syncLauncherRetention(candidate, transport, status)
        val result = Bundle()
        result.putBinder("anchor", anchor)
        return result
    }

    @Synchronized
    fun status(): Bundle? {
        try {
            if (backend != null)
                return RecentsFocusWire.call(backend!!, RecentsFocusWire.STATUS, null, viaTaskManager)
        } catch (ignored: Exception) {
            backend = null
        }
        val result = Bundle()
        result.putString("state", "backend-missing")
        return result
    }

    @Synchronized
    fun apply(): Bundle? {
        if (backend == null) return status()
        val p = context.getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
        val mode: Int =
            if (
                p.getBoolean("enabled", true) &&
                    p.getBoolean("home_enabled", true) &&
                    p.getBoolean("recents_live_tile", false)
            )
                RecentsFocusPolicy.mode(p.getInt("recents_focus_mode", 0))
            else 0
        val data = Bundle()
        data.putInt("mode", mode)
        try {
            val reply = RecentsFocusWire.call(
                backend!!,
                if (mode == 0) RecentsFocusWire.STOP else RecentsFocusWire.ARM,
                data,
                viaTaskManager,
            )
            return syncLauncherRetention(backend!!, viaTaskManager, reply)
        } catch (ignored: Exception) {
            backend = null
            return status()
        }
    }

    private fun syncLauncherRetention(endpoint: IBinder, transport: Boolean, status: Bundle?): Bundle? {
        val homeKey = StockLauncherActivityRetention.HOME_KEY
        val recentsKey = StockLauncherActivityRetention.RECENTS_KEY
        if (status?.containsKey(homeKey) != true || !status.containsKey(recentsKey)) return status
        val p = context.getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
        val homeEnabled = p.getBoolean("enabled", true) && p.getBoolean("home_enabled", true)
        val home = homeEnabled && p.getBoolean(homeKey, false)
        val recents = homeEnabled && p.getBoolean(recentsKey, false)
        val keep = p.getInt(StockSnapshotCacheTrim.KEY, StockSnapshotCacheTrim.AOSP)
            .coerceIn(StockSnapshotCacheTrim.MIN, StockSnapshotCacheTrim.MAX)
        val keepMatches = !status.containsKey(StockSnapshotCacheTrim.KEY) ||
            status.getInt(StockSnapshotCacheTrim.KEY) == keep
        if (status.getBoolean(homeKey) == home && status.getBoolean(recentsKey) == recents && keepMatches) {
            return status
        }
        val data = Bundle().apply {
            putBoolean(homeKey, home)
            putBoolean(recentsKey, recents)
            putInt(StockSnapshotCacheTrim.KEY, keep)
        }
        return RecentsFocusWire.call(endpoint, RecentsFocusWire.KEEP_LAUNCHER_SCREENS, data, transport)
    }
}
