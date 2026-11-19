package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import java.lang.ref.WeakReference

/** Uses the same first-unlock and crash protection as the existing One UI Home Tweaks hooks. */
internal class GlassHost(
    private val app: Application,
    private val loader: ClassLoader,
    activity: Activity?,
    private val briefOnly: Boolean = false,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = BackgroundExecutors.serial("OneUiGlassConfig")
    private val systemUi = app.packageName == "com.android.systemui"
    private val material = GlassMaterial(app.resources.displayMetrics.density)
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    private var appearance = GlassAppearance()
    private var nightMode = app.resources.configuration.isNightModeActive
    private var homeActivity = WeakReference(activity)
    private var unlocked = false
    private var quick: GlassQuickTiles? = null
    private var cards: GlassNotificationCards? = null
    private var popups: GlassNotificationPopups? = null
    private var notificationShade: GlassNotificationShade? = null
    private var panelBackdrop: GlassPanelBackdrop? = null
    private var volume: GlassVolumePanel? = null
    private var dock: GlassHomeSurfaces? = null
    private var shadeTouchInstalled = false
    private var drawScheduled = false
    private val shadeLight = GlassTouchLight {
        if (!drawScheduled) {
            drawScheduled = true
            Choreographer.getInstance().postFrameCallback {
                drawScheduled = false
                if (Build.VERSION.SDK_INT >= 33) refreshPanelSurfaces()
            }
        }
    }
    private val unavailable = mutableSetOf<String>()
    private val drawn = mutableSetOf<String>()
    private val reported = mutableMapOf<String, String>()

    fun start() {
        if (Build.VERSION.SDK_INT != 37 || Build.FINGERPRINT != Android17Hooks.FINGERPRINT) return
        if (briefOnly) popups = createPopups()
        main.post {
            nightMode = app.resources.configuration.isNightModeActive
            app.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(configuration: Configuration) {
                    val night = configuration.isNightModeActive
                    if (nightMode == night) return
                    nightMode = night
                    if (Build.VERSION.SDK_INT >= 33) refreshSurfaces()
                }

                override fun onLowMemory() = Unit
            })
            unlocked = keyguard?.isKeyguardLocked == false
            if (systemUi) trackKeyguard()
            else if (Build.VERSION.SDK_INT >= 33) trackHomeActivity()
            val uri = requireNotNull(ConfigProvider.URI)
            app.contentResolver.registerContentObserver(uri, false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = read()
            })
            read()
        }
    }

    private fun trackKeyguard() {
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                unlocked = when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> false
                    Intent.ACTION_USER_PRESENT -> true
                    else -> keyguard?.isKeyguardLocked == false
                }
                if (!unlocked) {
                    shadeLight.clear()
                    if (Build.VERSION.SDK_INT >= 33) panelBackdrop?.clear()
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    cards?.refresh()
                    popups?.refresh()
                    volume?.refresh()
                }
                notificationShade?.refresh()
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, Context.RECEIVER_NOT_EXPORTED)
    }

    @RequiresApi(33)
    private fun trackHomeActivity() {
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> dock?.clearDockBackdrops()
                    Intent.ACTION_USER_PRESENT -> dock?.refresh()
                    Intent.ACTION_WALLPAPER_CHANGED -> dock?.wallpaperChanged()
                }
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_WALLPAPER_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (activity.javaClass.name !in HOME_ACTIVITY_NAMES) return
                homeActivity = WeakReference(activity)
                dock?.find(activity.window.decorView)
                dock?.refresh()
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                if (homeActivity.get() === activity) dock?.clearDockBackdrops()
            }
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (homeActivity.get() === activity) homeActivity.clear()
            }
        })
    }

    private fun read() {
        worker.execute {
            val next = runCatching {
                app.contentResolver.query(requireNotNull(ConfigProvider.URI), null, null, null, null)?.use { row ->
                    check(row.moveToFirst())
                    fun value(key: String) = row.getInt(row.getColumnIndexOrThrow(key))
                    val global = value("enabled") != 0
                    val shade = global && value("systemui_enabled") != 0
                    val home = global && value("home_enabled") != 0
                    GlassAppearance(
                        quick = shade && value(GlassAppearance.QUICK) != 0,
                        notifications = shade && value(GlassAppearance.NOTIFICATIONS) != 0,
                        home = home && value(GlassAppearance.HOME) != 0,
                        strength = value(GlassAppearance.STRENGTH).coerceIn(0, 100),
                        tint = GlassAppearance.tintPercent(value(GlassAppearance.TINT)),
                        volume = shade && value(GlassAppearance.VOLUME) != 0,
                        volumeTheme = if (shade) value("volume") else 0,
                    )
                }
            }.getOrNull() ?: GlassAppearance()
            main.post {
                if (Build.VERSION.SDK_INT >= 33) apply(next)
            }
        }
    }

    @RequiresApi(33)
    private fun apply(next: GlassAppearance) {
        val changed = appearance != next
        appearance = next
        material.tint = next.tint
        if (systemUi && !briefOnly) {
            val panelsEnabled = next.strength > 0 && (next.quick || next.notifications)
            if (panelsEnabled) {
                if (!shadeTouchInstalled) installShadeTouch()
                installPanelBackdrop()
            } else {
                shadeLight.clear()
                panelBackdrop?.clear()
            }
            update(GlassAppearance.QUICK, next.quick, quick != null) {
                quick = GlassQuickTiles(loader, { if (appearance.quick) appearance.strength else 0 },
                    ::isNight, { unlocked }, { shadeLight.pointer }, material, app.resources.displayMetrics.density,
                    { panelBackdrop },
                    { markDrawn(GlassAppearance.QUICK) }, { markFailed(GlassAppearance.QUICK, it) })
            }
            update(GlassAppearance.NOTIFICATIONS, next.notifications, cards != null) {
                val popupGlass = createPopups()
                popups = popupGlass
                cards = GlassNotificationCards(loader, { if (unlocked) appearance else GlassAppearance() },
                    ::isNight, { shadeLight.pointer }, material, { panelBackdrop }, popupGlass,
                    { markDrawn(GlassAppearance.NOTIFICATIONS) })
            }
            update(GlassAppearance.VOLUME, next.volume, volume != null) {
                volume = GlassVolumePanel(app, loader, main, worker, material,
                    { if (unlocked && appearance.volume) appearance.strength else 0 },
                    { view ->
                        when (appearance.volumeTheme) {
                            1 -> false
                            2 -> true
                            else -> view.resources.configuration.isNightModeActive
                        }
                    }, ::refreshPopupUnlock,
                    { markDrawn(GlassAppearance.VOLUME) })
            }
        } else if (!systemUi) {
            update(GlassAppearance.HOME, next.home, dock != null) {
                dock = GlassHomeSurfaces(app, loader, main, worker, { if (appearance.home) appearance.strength else 0 },
                    ::isNight, material, { markDrawn(GlassAppearance.HOME) })
                homeActivity.get()?.takeUnless(Activity::isDestroyed)?.let { dock?.find(it.window.decorView) }
            }
        }
        if (changed) refreshSurfaces()
    }

    @RequiresApi(33)
    private fun refreshSurfaces() {
        quick?.refresh()
        cards?.refresh()
        popups?.refresh()
        notificationShade?.refresh()
        volume?.refresh()
        dock?.refresh()
    }

    @RequiresApi(33)
    private fun createPopups() = GlassNotificationPopups(
        loader, main, worker, app.resources.displayMetrics.density,
        { if (unlocked) appearance else GlassAppearance() }, ::refreshPopupUnlock, ::isNight, material,
        briefOnly,
    )

    private fun refreshPopupUnlock() {
        // SystemUI reload can preserve the user's unlocked session without broadcasting USER_PRESENT.
        unlocked = power?.isInteractive == true && keyguard?.isKeyguardLocked == false
    }

    @RequiresApi(33)
    private fun installPanelBackdrop() {
        if (panelBackdrop != null) return
        val density = app.resources.displayMetrics.density
        val backdrop = GlassPanelBackdrop(main, worker, density, ::refreshPanelSurfaces, live = true)
        panelBackdrop = backdrop
        notificationShade = runCatching {
            GlassNotificationShade(loader, { if (unlocked && appearance.notifications) appearance.strength else 0 },
                { material.tintFactor }, ::isNight, density,
                { view, open ->
                    backdrop.observe(view, open && unlocked && appearance.strength > 0 &&
                        (appearance.quick || appearance.notifications))
                },
                { markDrawn(GlassAppearance.NOTIFICATIONS) })
        }.onFailure { HookEntry.log("GLASS_NOTIFICATION_SHADE_UNAVAILABLE ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
    }

    @RequiresApi(33)
    private fun refreshPanelSurfaces() {
        if (appearance.quick) quick?.refresh()
        if (appearance.notifications) cards?.refresh()
    }

    private fun installShadeTouch() {
        shadeTouchInstalled = true
        try {
            val shade = DexSymbols.findClass("com.android.systemui.shade.NotificationShadeWindowView", false, loader)
                .asSubclass(View::class.java)
            val dispatch = shade.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            ModernHook.aroundMethod(dispatch) { chain ->
                if (appearance.strength > 0 && (appearance.quick || appearance.notifications)) {
                    val event = chain.args[0] as MotionEvent
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) unlocked = keyguard?.isKeyguardLocked == false
                    if (unlocked) shadeLight.touch(chain.thisObject as View, event)
                }
                chain.proceed()
            }
        } catch (error: Exception) {
            HookEntry.log("GLASS_SHADE_TOUCH_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun update(feature: String, enabled: Boolean, installed: Boolean, install: () -> Unit) {
        val state = when {
            !enabled || appearance.strength == 0 -> {
                drawn.remove(feature)
                "OFF"
            }
            feature in unavailable -> "UNSUPPORTED"
            feature in drawn -> "DRAWN"
            installed -> "READY"
            else -> try {
                install()
                "READY"
            } catch (error: Exception) {
                unavailable += feature
                HookEntry.log("GLASS_UNSUPPORTED $feature ${error.javaClass.simpleName}: ${error.message}")
                "UNSUPPORTED"
            } catch (error: LinkageError) {
                unavailable += feature
                HookEntry.log("GLASS_UNSUPPORTED $feature ${error.javaClass.simpleName}")
                "UNSUPPORTED"
            }
        }
        publish(feature, state)
    }

    private fun markDrawn(feature: String) {
        if (drawn.add(feature)) publish(feature, "DRAWN")
    }

    private fun markFailed(feature: String, error: Exception) {
        unavailable += feature
        HookEntry.log("GLASS_DRAW_FAILED $feature ${error.javaClass.simpleName}: ${error.message}")
        publish(feature, "UNSUPPORTED", error.javaClass.simpleName)
    }

    private fun publish(feature: String, state: String, detail: String = "") {
        if (reported.put(feature, state) == state) return
        if (state != "OFF") HookEntry.log("GLASS_${state} $feature build=${BuildConfig.VERSION_CODE}")
        worker.execute { HostStatus.reportFeature(app, feature, state, detail, BuildConfig.VERSION_CODE) }
    }

    private fun isNight(): Boolean = nightMode

    private companion object {
        val HOME_ACTIVITY_NAMES = setOf(
            "com.sec.android.app.launcher.Launcher",
            "com.sec.android.app.launcher.activities.LauncherActivity",
        )
    }
}
