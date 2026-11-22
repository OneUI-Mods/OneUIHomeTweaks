package dev.ryuya.oneuihometweaks

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleMethod
import kotlin.concurrent.Volatile

/* Launcher-only discovery. No injection or hooks in the source application. */
class RecentsFocusClient {
    private data class Connection(val backend: IBinder, val anchor: IBinder, val viaTaskManager: Boolean)

    data class Lease(val backend: IBinder, val owner: IBinder, val viaTaskManager: Boolean)

    @Volatile private var connection: RecentsFocusClient.Connection? = null
    @Volatile private var state: String = "backend-missing"
    private var lastFailure: String? = null

    fun diagnostic(): String {
        return state
    }

    fun connect(context: Context) {
        try {
            val current = connection
            var backend = current?.backend
            var viaTaskManager = current?.viaTaskManager == true
            if (backend == null || !backend.isBinderAlive()) {
                val services = DexSymbols.findClass("android.os.ServiceManager")
                val atm: IBinder? =
                    services
                        .compatibleMethod("getService", String::class.java)
                        .invoke(null, "activity_task") as IBinder?
                val found = RecentsFocusWire.call(atm!!, RecentsFocusWire.DISCOVER, null)
                viaTaskManager = found!!.getBoolean(RecentsFocusWire.TASK_MANAGER)
                backend = if (viaTaskManager) atm else checkNotNull(found.getBinder("backend"))
            }
            val data = Bundle()
            data.putBinder("backend", backend)
            data.putBoolean(RecentsFocusWire.TASK_MANAGER, viaTaskManager)
            val reply =
                context
                    .getContentResolver()
                    .call(ConfigProvider.URI!!, "focus_register", null, data)
            val anchor: IBinder? = if (reply == null) null else reply.getBinder("anchor")
            checkNotNull(anchor) { "No provider anchor" }
            connection = Connection(backend, anchor, viaTaskManager)
            state =
                RecentsFocusWire.call(backend, RecentsFocusWire.STATUS, null, viaTaskManager)!!.getString(
                    "state",
                    "error",
                )
            lastFailure = null
        } catch (error: Throwable) {
            connection = null
            state = "backend-missing"
            val failure = error.javaClass.simpleName + ": " + error.message
            if (failure != lastFailure) {
                lastFailure = failure
                HookEntry.log("RECENTS_FOCUS_CONNECT_UNAVAILABLE " + failure)
            }
        }
    }

    fun begin(task: Int, mode: Int): RecentsFocusClient.Lease? {
        val current: RecentsFocusClient.Connection? = connection
        if (current == null || RecentsFocusPolicy.mode(mode) == 0) return null
        val backend = current.backend
        val anchor = current.anchor
        val owner = Binder()
        val data = Bundle()
        data.putBinder("owner", owner)
        data.putBinder("anchor", anchor)
        data.putInt("task", task)
        data.putInt("mode", mode)
        try {
            val reply = RecentsFocusWire.call(backend, RecentsFocusWire.BEGIN, data, current.viaTaskManager)
            state = reply!!.getString("state", "error")
            return if (reply!!.getBoolean("accepted")) Lease(backend, owner, current.viaTaskManager)
            else null
        } catch (error: Throwable) {
            state = "error"
            return null
        }
    }

    fun end(lease: RecentsFocusClient.Lease?) {
        if (lease == null) return
        val data = Bundle()
        data.putBinder("owner", lease.owner)
        try {
            state =
                RecentsFocusWire.call(lease.backend, RecentsFocusWire.END, data, lease.viaTaskManager)!!.getString(
                    "state",
                    "error",
                )
        } catch (error: Throwable) {
            state = "error"
        }
    }
}
