package dev.ryuya.oneuihometweaks

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.function.Supplier
import kotlin.concurrent.Volatile
import kotlin.math.log

/* Local configuration for native Samsung volume dialogs only, never global resources. */
class VolumeHooks {
    @Volatile private var mode: Int = 0
    private var failed: Boolean = false
    private var failureDetail: String = "Volume hook stopped"
    private var context: Context? = null
    private var main: Handler? = null
    private var worker: Handler? = null
    private var panel: WeakReference<Any?> = WeakReference<Any?>(null)
    private var recreate: Method? = null
    private var lightPreset: Int = 0
    private var darkPreset: Int = 0
    private var lastBlur: String = ""
    private val volumeStar: VolumeStarHooks = VolumeStarHooks()

    @Throws(Exception::class)
    fun install(app: Context, loader: ClassLoader?) {
        context = app
        main = Handler(Looper.getMainLooper())
        val dependency =
            Class.forName("com.android.systemui.volume.VolumeDependency", false, loader)
        val panelClass = Class.forName("com.android.systemui.volume.VolumePanelImpl", false, loader)
        val window =
            Class.forName(
                "com.android.systemui.volume.view.standard.VolumePanelWindow",
                false,
                loader,
            )
        val expanded =
            Class.forName(
                "com.android.systemui.volume.view.expand.VolumePanelExpandWindow",
                false,
                loader,
            )
        val blur = Class.forName("com.android.systemui.volume.util.BlurEffect", false, loader)
        val colors = Class.forName("com.android.systemui.volume.util.ColorUtils", false, loader)
        val sem = Class.forName("android.view.SemBlurInfo")
        lightPreset = sem.getField("BLUR_UI_MEDIUM_ULTRA_THIN_LIGHT").getInt(null)
        darkPreset = sem.getField("BLUR_UI_MEDIUM_ULTRA_THIN_DARK").getInt(null)
        volumeStar.install(app, loader, lightPreset, darkPreset)
        check(lightPreset != darkPreset) { "Ambiguous Samsung blur presets" }
        check(
            !(!Dialog::class.java.isAssignableFrom(window) ||
                !Dialog::class.java.isAssignableFrom(expanded))
        ) {
            "Volume window signature changed"
        }
        recreate = panelClass.getDeclaredMethod("recreateVolumePanelForNewConfig")
        // Confirmed constructor callers must not retain inlined dependency lookups.
        for (target in arrayOf<Class<*>?>(window, expanded)) for (constructor in
            target!!.getDeclaredConstructors()) check(ModernHook.deoptimize(constructor)) {
            "Failed to deoptimize volume constructor"
        }
        DexEvidence.deoptimizeVolumeCalls(app.getApplicationInfo().sourceDir, loader)
        ModernHook.hookMethod(
            dependency.getDeclaredMethod("get", Class::class.java),
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    val patternPrefix0 =
                        failed || mode == 0 || p.hasThrowable() || p.args[0] !== Context::class.java
                    val original = if (!patternPrefix0) p.getResult() as? Context else null
                    if (patternPrefix0 || original == null) return
                    try {
                        var target: Boolean = false
                        for (frame in Thread.currentThread().getStackTrace()) {
                            val n = frame.getClassName()
                            if (
                                (n ==
                                    "com.android.systemui.volume.view.standard.VolumePanelWindow" ||
                                    n ==
                                        "com.android.systemui.volume.view.expand.VolumePanelExpandWindow") &&
                                    frame.getMethodName() == "<init>"
                            ) {
                                target = true
                                break
                            }
                        }
                        if (!target) return
                        val cfg = Configuration(original.getResources().getConfiguration())
                        cfg.uiMode =
                            ((cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                (if (mode == 2) Configuration.UI_MODE_NIGHT_YES
                                else Configuration.UI_MODE_NIGHT_NO))
                        val configured = original.createConfigurationContext(cfg)
                        val themed = ContextThemeWrapper(configured, 0)
                        themed.getTheme().setTo(original.getTheme())
                        themed.getTheme().rebase()
                        p.setResult(themed)
                        HookEntry.log("VOLUME_CONTEXT mode=" + mode)
                    } catch (t: Throwable) {
                        disable(t)
                    }
                }
            },
        )
        ModernHook.hookAllConstructors(
            panelClass,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (!p.hasThrowable()) panel = WeakReference<Any?>(p.thisObject)
                }
            },
        )
        ModernHook.hookMethod(
            blur.getDeclaredMethod(
                "setRealTimeBlur",
                View::class.java,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(p: ModernHook.Param) {
                    run {
                        val patternPrefix1 = failed || mode == 0
                        val view = if (!patternPrefix1) p.args[0] as? View else null
                        if (patternPrefix1 || view == null || !volumeView(view)) return
                    }
                    try {
                        p.args[3] = selectedPreset()
                    } catch (t: Throwable) {
                        disable(t)
                    }
                }
            },
        )
        ModernHook.hookMethod(
            blur.getDeclaredMethod(
                "setCapturedBlur",
                ImageView::class.java,
                Int::class.javaPrimitiveType,
                Supplier::class.java,
            ),
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(p: ModernHook.Param) {
                    run {
                        val patternPrefix2 = failed || mode == 0
                        val view = if (!patternPrefix2) p.args[0] as? View else null
                        if (patternPrefix2 || view == null || !volumeView(view)) return
                    }
                    try {
                        p.args[1] = selectedPreset()
                    } catch (t: Throwable) {
                        disable(t)
                    }
                }
            },
        )
        val foreground =
            java.util.Set.of<String?>(
                "volume_icon_color",
                "volume_icon_caption_color",
                "volume_icon_muted_color",
                "volume_icon_setting_color",
                "volume_detail_icon_color",
                "volume_panel_bluetooth_device_icon_color",
                "volume_panel_title_color",
                "volume_panel_status_message_color",
                "volume_panel_fg_color",
            )
        ModernHook.hookMethod(
            colors.getDeclaredMethod(
                "getSingleColorStateList",
                Int::class.javaPrimitiveType,
                Context::class.java,
            ),
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    val patternPrefix3 = failed || mode == 0 || p.hasThrowable()
                    val original = if (!patternPrefix3) p.getResult() as? ColorStateList else null
                    if (patternPrefix3 || original == null) return
                    try {
                        val ctx: Context? = p.args[1] as Context?
                        if (
                            ctx!!.getDisplay() == null ||
                                ctx!!.getDisplay().getDisplayId() != 0 ||
                                !foreground.contains(
                                    ctx!!.getResources().getResourceEntryName(p.args[0] as Int)
                                )
                        )
                            return
                        val rgb: Int = if (mode == 1) 0x00252525 else 0x00f5f5f5
                        p.setResult(
                            ColorStateList.valueOf(
                                (original.getDefaultColor() and -0x1000000) or rgb
                            )
                        )
                    } catch (t: Throwable) {
                        disable(t)
                    }
                }
            },
        )
        val thread = HandlerThread("OneUiVolumeConfig")
        thread.start()
        worker = Handler(thread.getLooper())
        app.getContentResolver()
            .registerContentObserver(
                ConfigProvider.URI!!,
                false,
                object : ContentObserver(worker) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        refresh()
                    }
                },
            )
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent) {
                    if (Intent.ACTION_USER_UNLOCKED == intent.getAction())
                        worker!!.post(Runnable { this@VolumeHooks.refresh() })
                }
            },
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        worker!!.post(Runnable { this.refresh() })
        HookEntry.log("VOLUME_HOOK_READY native context + native recreate")
    }

    private fun volumeView(view: View): Boolean {
        if (view.getDisplay() == null || view.getDisplay().getDisplayId() != 0) return false
        var current: View? = view
        while (current != null) {
            val name = current.javaClass.getName()
            if (
                name == "com.android.systemui.volume.view.standard.VolumePanelView" ||
                    name == "com.android.systemui.volume.view.expand.VolumePanelExpandView"
            )
                return true
            val parent = current.getParent()
            current = if (parent is View) parent as View? else null
        }
        return false
    }

    private fun selectedPreset(): Int {
        val preset: Int = if (mode == 1) lightPreset else darkPreset
        val report: String = "VOLUME_BLUR mode=" + mode + " preset=" + preset
        if (report != lastBlur) {
            lastBlur = report
            HookEntry.log(report)
        }
        return preset
    }

    private fun refresh() {
        if (failed) {
            HostStatus.report(context, "ERROR", failureDetail)
            return
        }
        try {
            context!!
                .getContentResolver()
                .query(ConfigProvider.URI!!, null, null, null, null)
                .use { c ->
                    check(!(c == null || !c.moveToFirst())) { "No configuration" }
                    val enabled: Boolean =
                        c.getInt(c.getColumnIndexOrThrow("enabled")) != 0 &&
                            c.getInt(c.getColumnIndexOrThrow("systemui_enabled")) != 0
                    var next: Int = if (enabled) c.getInt(c.getColumnIndexOrThrow("volume")) else 0
                    if (next < 0 || next > 2) next = 0
                    val selected: Int = next
                    main!!.post(
                        Runnable {
                            if (mode == selected) {
                                HostStatus.report(
                                    context,
                                    if (enabled) (if (volumeStar.hasError()) "ERROR" else "ACTIVE")
                                    else "OFF",
                                    "Volume hook ready; mode=" + mode,
                                )
                                return@Runnable
                            }
                            mode = selected
                            volumeStar.setMode(selected)
                            try {
                                val current = panel.get()
                                if (current != null) recreate!!.invoke(current)
                                HostStatus.report(
                                    context,
                                    if (enabled) (if (volumeStar.hasError()) "ERROR" else "ACTIVE")
                                    else "OFF",
                                    "Volume mode=" + mode,
                                )
                            } catch (t: Throwable) {
                                disable(t)
                            }
                        }
                    )
                }
        } catch (t: Throwable) {
            main!!.post(
                Runnable {
                    if (failed) return@Runnable
                    val restore: Boolean = mode != 0
                    mode = 0
                    volumeStar.setMode(0)
                    try {
                        val current = panel.get()
                        if (restore && current != null) recreate!!.invoke(current)
                    } catch (error: Throwable) {
                        disable(error)
                        return@Runnable
                    }
                    // Provider availability is transient before user unlock. Keep the observer
                    // alive;
                    // unlock or opening the settings app retries without polling or bypassing
                    // guards.
                    HostStatus.report(
                        context,
                        "ERROR",
                        ("Volume configuration unavailable; native mode; retries on unlock/config" +
                            " notification: " +
                            t.javaClass.getSimpleName()),
                    )
                    HookEntry.log("VOLUME_CONFIG_UNAVAILABLE " + t)
                }
            )
        }
    }

    private fun disable(t: Throwable) {
        val restore: Boolean = !failed
        failed = true
        failureDetail = "Volume: " + t.javaClass.getSimpleName()
        mode = 0
        volumeStar.setMode(0)
        HostStatus.report(context, "ERROR", failureDetail)
        HookEntry.log("VOLUME_DISABLED " + t)
        if (restore && main != null)
            main!!.post(
                Runnable {
                    try {
                        val current = panel.get()
                        if (current != null) recreate!!.invoke(current)
                    } catch (restoreError: Throwable) {
                        HookEntry.log(
                            "VOLUME_NATIVE_RESTORE_ERROR " + restoreError.javaClass.getSimpleName()
                        )
                    }
                }
            )
    }
}
