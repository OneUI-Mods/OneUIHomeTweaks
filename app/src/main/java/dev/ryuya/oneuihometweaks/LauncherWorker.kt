package dev.ryuya.oneuihometweaks

import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Process
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Quiesces a launcher-owned worker without waiting for its Binder calls on the UI thread. */
internal class LauncherWorker(name: String, priority: Int = Process.THREAD_PRIORITY_DEFAULT) {
    private val thread = HandlerThread(name, priority).apply { start() }
    private val dispatch = ReentrantLock()
    @Volatile private var paused = false

    val handler = object : Handler(thread.looper) {
        override fun dispatchMessage(message: Message) {
            dispatch.withLock {
                if (!paused) super.dispatchMessage(message)
            }
        }
    }

    fun tryPause(ready: () -> Boolean = { true }): Boolean {
        if (!dispatch.tryLock()) return false
        return try {
            if (!ready()) false
            else {
                paused = true
                true
            }
        } finally {
            dispatch.unlock()
        }
    }

    fun resume() { paused = false }

    fun close() {
        paused = true
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}
