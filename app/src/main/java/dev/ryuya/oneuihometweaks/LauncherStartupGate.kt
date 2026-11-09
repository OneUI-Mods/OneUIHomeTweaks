package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.UserManager
import java.lang.ref.WeakReference

/** CE settings and module-owned discovery are unavailable before the first unlock. */
internal object LauncherStartupGate {
    fun unlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked != false

    fun whenUnlocked(app: Application, ready: (Activity?) -> Unit) {
        if (unlocked(app)) {
            ready(null)
            return
        }
        var home = WeakReference<Activity>(null)
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity.javaClass.name == "com.sec.android.app.launcher.Launcher") home = WeakReference(activity)
            }
            override fun onActivityResumed(activity: Activity) = onActivityCreated(activity, null)
            override fun onActivityDestroyed(activity: Activity) { if (home.get() === activity) home.clear() }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        }
        var finished = false
        lateinit var receiver: BroadcastReceiver
        fun complete() {
            if (finished || !unlocked(app)) return
            finished = true
            app.unregisterReceiver(receiver)
            app.unregisterActivityLifecycleCallbacks(lifecycle)
            ready(home.get()?.takeUnless { it.isDestroyed })
            home.clear()
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_UNLOCKED) complete()
            }
        }
        app.registerActivityLifecycleCallbacks(lifecycle)
        app.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED)
        complete() // Covers unlocking between the first check and receiver registration.
    }
}
