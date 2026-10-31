package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.SurfaceControlViewHost.SurfacePackage
import android.view.SurfaceHolder
import android.view.SurfaceHolder.Callback
import android.view.SurfaceView
import android.view.ViewTreeObserver.OnWindowFocusChangeListener
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.annotation.RequiresApi
import java.io.File
import java.lang.Boolean as JavaBoolean
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.WeakHashMap
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.log
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference

/* Mirrors the existing home wallpaper. No secondary engine, offsets or visibility commands. */
@RequiresApi(36)
class PreviewHooks {
    private val views: MutableMap<SurfaceView?, PreviewHooks.Session?> =
        WeakHashMap<SurfaceView?, PreviewHooks.Session?>()
    private val originalFlags: MutableMap<Activity?, Boolean?> = WeakHashMap<Activity?, Boolean?>()
    private var main: Handler? = null
    private var worker: Handler? = null
    private var app: Application? = null
    private var enabled: Boolean = false
    private var failed: Boolean = false
    private var previewClass: Class<*>? = null
    private var mirrorWallpaper: Method? = null
    private var parentSurface: Method? = null
    private var windowService: Any? = null

    @Throws(Exception::class)
    fun install(context: Application?, loader: ClassLoader?) {
        app = context
        main = Handler(Looper.getMainLooper())
        val previews = ArrayList<Class<*>?>()
        val dex =
            DexFileFactory.loadDexContainer(
                File(app!!.getApplicationInfo().sourceDir),
                Opcodes.getDefault(),
            )
        for (entry in dex.getDexEntryNames()) for (c in
            dex.getEntry(entry)!!.getDexFile().getClasses()) {
            var preview: Boolean = false
            for (m in c.getMethods()) if (m.getImplementation() != null)
                for (i in m.getImplementation()!!.getInstructions()) run {
                    val patternPrefix0 = i is ReferenceInstruction
                    val str = if (patternPrefix0) i.getReference() as? StringReference else null
                    if (
                        patternPrefix0 && str != null && "HomePreviewSurfaceView" == str.getString()
                    )
                        preview = true
                }
            if (!preview) continue
            val actual =
                Class.forName(
                    c.getType().substring(1, c.getType().length - 1).replace('/', '.'),
                    false,
                    loader,
                )
            if (
                SurfaceView::class.java.isAssignableFrom(actual) &&
                    Callback::class.java.isAssignableFrom(actual)
            )
                previews.add(actual)
        }
        check(previews.size == 1) { "Ambiguous native preview DEX evidence" }
        previewClass = previews.get(0)
        windowService =
            Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService")
                .invoke(null)
        mirrorWallpaper =
            Class.forName("android.view.IWindowManager")
                .getMethod("mirrorWallpaperSurface", Int::class.javaPrimitiveType)
        check(mirrorWallpaper!!.getReturnType() == SurfaceControl::class.java) {
            "Wallpaper mirror contract changed"
        }
        parentSurface = SurfaceView::class.java.getMethod("getRenderingSurfaceControl")
        ModernHook.hookAllConstructors(
            previewClass!!,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (!p.hasThrowable()) {
                        val view: SurfaceView? = p.thisObject as SurfaceView?
                        if (!views.containsKey(view)) {
                            views.put(view, PreviewHooks.Session())
                            view!!
                                .getViewTreeObserver()
                                .addOnWindowFocusChangeListener(
                                    OnWindowFocusChangeListener { focus: Boolean ->
                                        if (focus) safeStart(view!!)
                                    }
                                )
                        }
                    }
                }
            },
        )
        val update: ModernHook.Callback =
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (!p.hasThrowable()) safeStart(p.thisObject as SurfaceView)
                }
            }
        ModernHook.hookMethod(
            previewClass!!.getMethod("surfaceCreated", SurfaceHolder::class.java),
            update,
        )
        ModernHook.hookMethod(
            previewClass!!.getMethod(
                "surfaceChanged",
                SurfaceHolder::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
            update,
        )
        ModernHook.hookMethod(
            previewClass!!.getMethod("surfaceDestroyed", SurfaceHolder::class.java),
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(p: ModernHook.Param) {
                    stop(p.thisObject as SurfaceView)
                }
            },
        )
        ModernHook.findAndHookMethod(
            SurfaceView::class.java,
            "setChildSurfacePackage",
            SurfacePackage::class.java,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (previewClass!!.isInstance(p.thisObject) && !p.hasThrowable())
                        try {
                            layers(p.thisObject as SurfaceView)
                        } catch (t: Throwable) {
                            disable(t)
                        }
                }
            },
        )
        val thread = HandlerThread("OneUiPreviewConfig")
        thread.start()
        worker = Handler(thread.getLooper())
        app!!
            .getContentResolver()
            .registerContentObserver(
                ConfigProvider.URI!!,
                false,
                object : ContentObserver(worker) {
                    override fun onChange(self: Boolean, uri: Uri?) {
                        refresh()
                    }
                },
            )
        app!!.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    refresh()
                }
            },
            IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        app!!.registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: Activity) {
                    refresh()
                }

                override fun onActivityPaused(a: Activity) {}

                override fun onActivityDestroyed(a: Activity) {
                    for (v in ArrayList<SurfaceView?>(views.keys)) if (
                        PreviewHooks.activity(v!!.getContext()) === a
                    )
                        stop(v!!)
                    restoreFlag(a)
                }

                override fun onActivityCreated(a: Activity, b: Bundle?) {}

                override fun onActivityStarted(a: Activity) {}

                override fun onActivityStopped(a: Activity) {
                    for (v in ArrayList<SurfaceView?>(views.keys)) if (
                        PreviewHooks.activity(v!!.getContext()) === a
                    )
                        stop(v!!)
                }

                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            }
        )
        worker!!.post(Runnable { this.refresh() })
        HookEntry.log(
            "PREVIEW_HOOK_READY view=" +
                previewClass!!.getName() +
                "; existing home wallpaper mirror"
        )
    }

    private fun refresh() {
        if (failed) return
        try {
            app!!.getContentResolver().query(ConfigProvider.URI!!, null, null, null, null).use { c
                ->
                check(!(c == null || !c.moveToFirst())) { "No configuration" }
                var on: Boolean =
                    c.getInt(c.getColumnIndexOrThrow("enabled")) != 0 &&
                        c.getInt(c.getColumnIndexOrThrow("preview_enabled")) != 0 &&
                        c.getInt(c.getColumnIndexOrThrow("preview")) != 0
                val info = WallpaperManager.getInstance(app).getWallpaperInfo()
                on = on and (info != null)
                val next: Boolean = on
                main!!.post(
                    Runnable {
                        enabled = next
                        for (v in ArrayList<SurfaceView?>(views.keys)) if (next) safeStart(v!!)
                        else stop(v!!)
                        if (!next)
                            for (a in ArrayList<Activity?>(originalFlags.keys)) restoreFlag(a!!)
                        HostStatus.report(
                            app,
                            if (next) "ACTIVE" else "OFF",
                            "Existing wallpaper mirror " + (if (next) "ready" else "disabled"),
                        )
                    }
                )
            }
        } catch (t: Throwable) {
            main!!.post(Runnable { disable(t) })
        }
    }

    private fun safeStart(view: SurfaceView) {
        try {
            start(view)
        } catch (t: Throwable) {
            disable(t)
        }
    }

    @Throws(Exception::class)
    private fun start(view: SurfaceView) {
        if (
            !enabled ||
                failed ||
                !view.isAttachedToWindow() ||
                !view.isShown() ||
                !view.getHolder().getSurface().isValid() ||
                view.getWidth() == 0 ||
                view.getHeight() == 0 ||
                view.getDisplay() == null ||
                view.getDisplay().getDisplayId() != 0
        )
            return
        if (
            app!!.getSystemService<KeyguardManager?>(KeyguardManager::class.java).isKeyguardLocked()
        )
            return
        val a = PreviewHooks.activity(view.getContext())
        if (a == null || a.isFinishing() || a.isDestroyed() || !a.hasWindowFocus()) return
        val s = views.computeIfAbsent(view) { k: SurfaceView? -> PreviewHooks.Session() }
        if (s!!.mirror != null) {
            resize(view, s!!)
            return
        }
        if (s!!.pending) return
        originalFlags.putIfAbsent(
            a,
            (a.getWindow().getAttributes().flags and LayoutParams.FLAG_SHOW_WALLPAPER) != 0,
        )
        // Keeps the existing home engine visible behind this opaque settings window.
        a.getWindow().addFlags(LayoutParams.FLAG_SHOW_WALLPAPER)
        s!!.pending = true
        val generation: Int = ++s!!.generation
        requestMirror(view, s!!, generation, 0)
    }

    private fun requestMirror(
        view: SurfaceView,
        s: PreviewHooks.Session,
        generation: Int,
        attempt: Int,
    ) {
        worker!!.postDelayed(
            Runnable {
                try {
                    val mirror: SurfaceControl? =
                        mirrorWallpaper!!.invoke(windowService, 0) as SurfaceControl?
                    main!!.post(
                        Runnable {
                            if (
                                s.generation != generation ||
                                    !enabled ||
                                    failed ||
                                    !view.isAttachedToWindow()
                            ) {
                                if (mirror != null) mirror.release()
                                return@Runnable
                            }
                            if (mirror == null || !mirror.isValid()) {
                                if (mirror != null) mirror.release()
                                if (attempt < 2) requestMirror(view, s, generation, attempt + 1)
                                else {
                                    s.pending = false
                                    HostStatus.report(
                                        app,
                                        "ERROR",
                                        "Existing wallpaper surface unavailable",
                                    )
                                }
                                return@Runnable
                            }
                            try {
                                s.pending = false
                                s.mirror = mirror
                                resize(view, s)
                                layers(view)
                                HookEntry.log("PREVIEW_HOME_MIRROR_ATTACHED current home page")
                                HostStatus.report(
                                    app,
                                    "ACTIVE",
                                    "Current home wallpaper mirrored below native icons",
                                )
                            } catch (t: Throwable) {
                                disable(t)
                            }
                        }
                    )
                } catch (t: Throwable) {
                    main!!.post(Runnable { disable(t) })
                }
            },
            (if (attempt == 0) 120 else 250).toLong(),
        )
    }

    @Throws(Exception::class)
    private fun resize(view: SurfaceView, s: PreviewHooks.Session) {
        val parent: SurfaceControl? = parentSurface!!.invoke(view) as SurfaceControl?
        check(!(parent == null || !parent.isValid())) { "Preview surface unavailable" }
        val source =
            app!!
                .getSystemService<WindowManager?>(WindowManager::class.java)
                .getMaximumWindowMetrics()
                .getBounds()
        check(!(source.width() == 0 || source.height() == 0)) { "Unknown source size" }
        Transaction().use { t ->
            t.reparent(s.mirror!!, parent)
                .setLayer(s.mirror!!, 1)
                .setCrop(s.mirror!!, Rect(0, 0, source.width(), source.height()))
                .setScale(
                    s.mirror!!,
                    view.getWidth() / source.width().toFloat(),
                    view.getHeight() / source.height().toFloat(),
                )
                .setVisibility(s.mirror!!, true)
                .apply()
        }
    }

    private fun layers(view: SurfaceView) {
        val s = views.get(view)
        if (s == null || s.mirror == null) return
        val child = view.getChildSurfacePackage()
        if (child == null) return
        val icons = child.getSurfaceControl()
        if (icons != null && icons.isValid())
            Transaction().use { t ->
                t.setLayer(icons, 2).apply()
                s.iconRaised = true
            }
    }

    private fun stop(view: SurfaceView) {
        val s = views.get(view)
        if (s == null) return
        s.generation++
        s.pending = false
        if (s.mirror != null) {
            try {
                Transaction().use { t ->
                    t.reparent(s!!.mirror!!, null).apply()
                }
            } catch (ignored: Throwable) {}
            s!!.mirror!!.release()
            s.mirror = null
        }
        if (s.iconRaised)
            try {
                val child = view.getChildSurfacePackage()
                if (child != null)
                    Transaction().use { t ->
                        t.setLayer(child.getSurfaceControl(), 0).apply()
                    }
            } catch (ignored: Throwable) {} finally {
                s.iconRaised = false
            }
    }

    private fun restoreFlag(a: Activity) {
        val original = originalFlags.remove(a)
        if (JavaBoolean.FALSE == original && !a.isDestroyed())
            a.getWindow().clearFlags(LayoutParams.FLAG_SHOW_WALLPAPER)
    }

    private fun disable(t: Throwable) {
        failed = true
        enabled = false
        for (v in ArrayList<SurfaceView?>(views.keys)) stop(v!!)
        for (a in ArrayList<Activity?>(originalFlags.keys)) restoreFlag(a!!)
        HostStatus.report(app, "ERROR", "Preview: " + t.javaClass.getSimpleName())
        HookEntry.log("PREVIEW_DISABLED " + t)
    }

    private class Session {
        @JvmField var mirror: SurfaceControl? = null
        @JvmField var pending: Boolean = false
        @JvmField var iconRaised: Boolean = false
        @JvmField var generation: Int = 0
    }

    companion object {
        @JvmStatic
        private fun activity(context: Context?): Activity? {
            var context: Context? = context
            for (n in 0..9) {
                if (context is Activity) return context
                if (context !is ContextWrapper || context.getBaseContext() === context) return null
                context = context.getBaseContext()
            }
            return null
        }
    }
}
