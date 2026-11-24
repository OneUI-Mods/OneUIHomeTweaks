package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * Samsung's wallpaper and theme app stays cached with its whole heap after its screens close. Once no
 * screen of it has been started for a while and Android ranks the process as cached (no service,
 * provider client or job keeps it important), end the process so that memory returns to the system.
 * Android starts it again the next time it is opened or needed.
 */
internal class PreviewHostRelease(private val app: Application) {
    private val main = Handler(Looper.getMainLooper())
    private var started = 0
    private var checks = 0
    @Volatile private var closed = false

    private val check = Runnable {
        if (closed || started > 0) return@Runnable
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        if (state.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) {
            HookEntry.log("PREVIEW_HOST_RELEASED importance=${state.importance}")
            Process.killProcess(Process.myPid())
        } else if (++checks < DELAYS.size) {
            schedule()
        }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            started++
            main.removeCallbacks(check)
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) {
                checks = 0
                schedule()
            }
        }

        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun schedule() {
        main.removeCallbacks(check)
        main.postDelayed(check, DELAYS[checks])
    }

    /** Main thread. A process loaded or updated while hidden is checked once it settles. */
    fun install() {
        app.registerActivityLifecycleCallbacks(callbacks)
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        if (state.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) schedule()
    }

    fun close() {
        closed = true
        main.removeCallbacks(check)
        app.unregisterActivityLifecycleCallbacks(callbacks)
    }

    private companion object {
        /** Bounded re-checks while a service or job still holds the process; no polling afterwards. */
        val DELAYS = longArrayOf(30_000L, 120_000L, 600_000L)
    }
}
