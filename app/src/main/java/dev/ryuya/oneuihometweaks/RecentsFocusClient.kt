package dev.ryuya.oneuihometweaks

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmRecord

/* Launcher-only discovery. No injection or hooks in the source application. */
class RecentsFocusClient {
    @JvmRecord
    private data class Connection(val backend: IBinder?, val anchor: IBinder?) {
        override fun toString(): String = "Connection[backend=$backend, anchor=$anchor]"
    }

    @JvmRecord
    data class Lease(val backend: IBinder?, val owner: IBinder?) {
        override fun toString(): String = "Lease[backend=$backend, owner=$owner]"
    }

    @Volatile private var connection: RecentsFocusClient.Connection? = null
    @Volatile private var state: String = "backend-missing"

    fun diagnostic(): String {
        return state
    }

    fun connect(context: Context) {
        try {
            val current: RecentsFocusClient.Connection? = connection
            var backend: IBinder? = if (current == null) null else current.backend
            if (backend == null || !backend.isBinderAlive()) {
                val services = Class.forName("android.os.ServiceManager")
                val atm: IBinder? =
                    services
                        .getMethod("getService", String::class.java)
                        .invoke(null, "activity_task") as IBinder?
                val found = RecentsFocusWire.call(atm!!, RecentsFocusWire.DISCOVER, null)
                backend = found!!.getBinder("backend")
                checkNotNull(backend) { "No backend" }
            }
            val data = Bundle()
            data.putBinder("backend", backend)
            val reply =
                context
                    .getContentResolver()
                    .call(ConfigProvider.URI!!, "focus_register", null, data)
            val anchor: IBinder? = if (reply == null) null else reply.getBinder("anchor")
            checkNotNull(anchor) { "No provider anchor" }
            connection = RecentsFocusClient.Connection(backend, anchor)
            state =
                RecentsFocusWire.call(backend, RecentsFocusWire.STATUS, null)!!.getString(
                    "state",
                    "error",
                )
        } catch (error: Throwable) {
            state = "backend-missing"
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
            val reply = RecentsFocusWire.call(backend!!, RecentsFocusWire.BEGIN, data)
            state = reply!!.getString("state", "error")
            return if (reply!!.getBoolean("accepted")) RecentsFocusClient.Lease(backend, owner)
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
                RecentsFocusWire.call(lease.backend!!, RecentsFocusWire.END, data)!!.getString(
                    "state",
                    "error",
                )
        } catch (error: Throwable) {
            state = "error"
        }
    }
}
