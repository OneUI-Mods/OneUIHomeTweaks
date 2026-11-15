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
internal object HostStartupGate {
    fun unlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == true

    fun whenUnlocked(app: Application, ready: (Activity?) -> Unit) {
        if (unlocked(app)) {
            ready(null)
            return
        }
        val tracksHome = app.packageName == HookEntry.LAUNCHER
        var home = WeakReference<Activity>(null)
        val lifecycle =
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) {
                    if (activity.javaClass.name == "com.sec.android.app.launcher.Launcher")
                        home = WeakReference(activity)
                }

                override fun onActivityResumed(activity: Activity) =
                    onActivityCreated(activity, null)

                override fun onActivityDestroyed(activity: Activity) {
                    if (home.get() === activity) home.clear()
                }

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            }
        UnlockStartup(
                isUnlocked = { unlocked(app) },
                subscribe = { check ->
                    val receiver =
                        object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                if (intent.action == Intent.ACTION_USER_UNLOCKED) check()
                            }
                        }
                    if (tracksHome) app.registerActivityLifecycleCallbacks(lifecycle)
                    try {
                        app.registerReceiver(
                            receiver,
                            IntentFilter(Intent.ACTION_USER_UNLOCKED),
                            Context.RECEIVER_NOT_EXPORTED,
                        )
                    } catch (error: RuntimeException) {
                        if (tracksHome) app.unregisterActivityLifecycleCallbacks(lifecycle)
                        throw error
                    }
                    val removeListener: () -> Unit = {
                        app.unregisterReceiver(receiver)
                        if (tracksHome) app.unregisterActivityLifecycleCallbacks(lifecycle)
                    }
                    removeListener
                },
            )
            .run {
                ready(home.get()?.takeUnless { it.isDestroyed })
                home.clear()
            }
    }
}
