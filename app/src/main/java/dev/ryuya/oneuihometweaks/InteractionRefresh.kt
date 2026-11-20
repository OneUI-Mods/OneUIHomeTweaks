package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.MotionEvent
import android.view.View
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/** A bounded Samsung minimum-rate request for active UI on the built-in display. */
internal class InteractionRefresh(private val app: Application, private val home: Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = BackgroundExecutors.serial("OneUiInteractionRefresh")
    private val power = checkNotNull(app.getSystemService(PowerManager::class.java))
    private var homeActivity = WeakReference<Activity>(null)
    private var unlockUntil = 0L
    private var deadline = 0L
    @Volatile private var allowed = false
    @Volatile private var wantsRate = false
    private var active: Any? = null
    private var unavailable = false
    private val native by lazy { SamsungRateToken() }

    private val expire = object : Runnable {
        override fun run() {
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining > 0) main.postDelayed(this, remaining) else stop()
        }
    }

    private fun install(loader: ClassLoader) {
        val callback = object : ModernHook.Callback() {
            override fun beforeHookedMethod(param: ModernHook.Param) {
                if (!allowed) return
                val view = if (home) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name !in HOME_ACTIVITIES || !activity.hasWindowFocus()) return
                    activity.window.peekDecorView()
                } else param.thisObject as? View
                if (view?.display?.displayId != Display.DEFAULT_DISPLAY) return
                val event = param.args[0] as? MotionEvent ?: return
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> pulse(TOUCH_MS)
                    MotionEvent.ACTION_UP -> pulse(TOUCH_TAIL_MS)
                    MotionEvent.ACTION_CANCEL -> stop()
                }
            }
        }

        if (home) {
            val dispatch = Activity::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            ModernHook.hookMethod(dispatch, callback)
            ModernHook.hookMethod(
                Activity::class.java.getDeclaredMethod("onWindowFocusChanged", Boolean::class.javaPrimitiveType),
                object : ModernHook.Callback() {
                    override fun afterHookedMethod(param: ModernHook.Param) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.javaClass.name !in HOME_ACTIVITIES ||
                            activity.window.peekDecorView()?.display?.displayId != Display.DEFAULT_DISPLAY) return
                        if (param.args[0] == true) {
                            homeActivity = WeakReference(activity)
                            val remaining = unlockUntil - SystemClock.uptimeMillis()
                            if (remaining > 0) pulse(remaining)
                        } else if (homeActivity.get() === activity) {
                            homeActivity.clear()
                            stop()
                        }
                    }
                },
            )
        } else {
            val shade = Class.forName("com.android.systemui.shade.NotificationShadeWindowView", false, loader)
            ModernHook.hookMethod(shade.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java), callback)
        }

        val settings = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = refreshSettings()
        }
        val resolver = app.contentResolver
        resolver.registerContentObserver(requireNotNull(ConfigProvider.URI), false, settings)
        resolver.registerContentObserver(Settings.System.getUriFor("peak_refresh_rate"), false, settings)
        resolver.registerContentObserver(Settings.Secure.getUriFor("refresh_rate_mode"), false, settings)
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        unlockUntil = SystemClock.uptimeMillis() + UNLOCK_MS
                        if (!home || homeActivity.get()?.hasWindowFocus() == true) pulse(UNLOCK_MS)
                    }
                    Intent.ACTION_SCREEN_OFF, PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        unlockUntil = 0
                        stop()
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        power.addThermalStatusListener(app.mainExecutor) { status ->
            if (status >= PowerManager.THERMAL_STATUS_MODERATE) stop()
        }
        refreshSettings()
        HookEntry.log("INTERACTION_REFRESH_READY ${app.packageName}")
    }

    private fun refreshSettings() {
        worker.execute {
            val enabled = runCatching {
                val resolver = app.contentResolver
                val controls = Settings.System.getFloat(resolver, "peak_refresh_rate", RATE.toFloat()) >= RATE &&
                    Settings.Secure.getInt(resolver, "refresh_rate_mode", 1) == 1
                if (!controls) false
                else resolver.query(requireNotNull(ConfigProvider.URI), null, null, null, null)?.use { row ->
                    row.moveToFirst() && row.getInt(row.getColumnIndexOrThrow("enabled")) == 1 &&
                        row.getInt(row.getColumnIndexOrThrow(if (home) "home_enabled" else "systemui_enabled")) == 1 &&
                        row.getInt(row.getColumnIndexOrThrow(KEY)) == 1
                } == true
            }.getOrDefault(false)
            allowed = enabled
            if (!enabled) main.post(::stop)
        }
    }

    private fun pulse(duration: Long) {
        if (!allowed) return
        val now = SystemClock.uptimeMillis()
        deadline = maxOf(deadline, now + duration)
        if (wantsRate) return
        wantsRate = true
        main.postDelayed(expire, deadline - now)
        worker.execute(::updateToken)
    }

    private fun stop() {
        deadline = 0
        if (!wantsRate) return
        wantsRate = false
        main.removeCallbacks(expire)
        worker.execute(::updateToken)
    }

    private fun updateToken() {
        if (unavailable) return
        val request = allowed && wantsRate && power.isInteractive && !power.isPowerSaveMode &&
            power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE &&
            app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
        try {
            if (request && active == null) {
                active = native.acquire(if (home) "OneUIHomeTweaks:Home" else "OneUIHomeTweaks:SystemUI")
                if (active == null) {
                    unavailable = true
                    HookEntry.log("INTERACTION_REFRESH_DENIED ${app.packageName}")
                }
            } else if (!request) {
                val token = active ?: return
                native.release(token)
                active = null
            }
        } catch (error: Exception) {
            // Keep a token whose release failed so a subsequent adjustment can retry it.
            if (active == null) unavailable = true
            HookEntry.log("INTERACTION_REFRESH_FAILED ${app.packageName} ${error.javaClass.simpleName}")
        }
    }

    private class SamsungRateToken {
        private val owner = Binder()
        private val iface = Class.forName("android.hardware.display.IDisplayManager")
        private val acquire: Method = iface.getMethod("acquireRefreshRateMinLimitToken",
            IBinder::class.java, Int::class.javaPrimitiveType, String::class.java)
        private val release = acquire.returnType.getMethod("release")
        private val proxy: Any

        init {
            val service = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, Context.DISPLAY_SERVICE) as IBinder
            proxy = requireNotNull(Class.forName("android.hardware.display.IDisplayManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, service))
        }

        fun acquire(tag: String): Any? = acquire.invoke(proxy, owner, RATE, tag)
        fun release(token: Any) { release.invoke(token) }
    }

    companion object {
        const val KEY = "interaction_refresh"
        private const val RATE = 120
        private const val TOUCH_MS = 550L
        private const val TOUCH_TAIL_MS = 350L
        private const val UNLOCK_MS = 900L
        private val HOME_ACTIVITIES = setOf(
            "com.sec.android.app.launcher.Launcher",
            "com.sec.android.app.launcher.activities.LauncherActivity",
        )

        fun supported() = Build.VERSION.SDK_INT == 37 && Build.MODEL == "SM-S948Q" &&
            HookCompatibility.matchesClockBuild(Build.VERSION.SDK_INT, Build.DISPLAY)

        fun install(app: Application, loader: ClassLoader, home: Boolean) {
            if (!supported()) return
            runCatching { InteractionRefresh(app, home).install(loader) }
                .onFailure { HookEntry.log("INTERACTION_REFRESH_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}") }
        }
    }
}
