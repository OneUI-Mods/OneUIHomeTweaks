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

    init {
        this.context = context
    }

    @Synchronized
    @Throws(Exception::class)
    fun register(data: Bundle?): Bundle {
        val candidate: IBinder? = if (data == null) null else data.getBinder("backend")
        require(!(candidate == null || !candidate.isBinderAlive())) { "Live backend required" }
        RecentsFocusWire.call(candidate, RecentsFocusWire.STATUS, null)
        backend = candidate
        val result = Bundle()
        result.putBinder("anchor", anchor)
        return result
    }

    @Synchronized
    fun status(): Bundle? {
        try {
            if (backend != null)
                return RecentsFocusWire.call(backend!!, RecentsFocusWire.STATUS, null)
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
            return RecentsFocusWire.call(
                backend!!,
                if (mode == 0) RecentsFocusWire.STOP else RecentsFocusWire.ARM,
                data,
            )
        } catch (ignored: Exception) {
            backend = null
            return status()
        }
    }
}
