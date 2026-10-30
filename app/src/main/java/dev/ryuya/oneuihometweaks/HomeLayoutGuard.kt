package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.DisplayCutout
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import java.lang.Boolean as JavaBoolean
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.UUID
import java.util.WeakHashMap
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized
import kotlin.math.hypot
import kotlin.math.log
import kotlin.math.round
import org.json.JSONObject

/*
 * BZI9-only layout stabilization. Never edits the layout database or restores guessed coordinates.
 */
class HomeLayoutGuard {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val pending: MutableMap<Any?, HomeLayoutGuard.Pending?> =
        WeakHashMap<Any?, HomeLayoutGuard.Pending?>()
    private val layouts: MutableSet<ViewGroup?>? =
        Collections.newSetFromMap<ViewGroup?>(WeakHashMap<ViewGroup?, Boolean?>())
    private val handles: MutableList<ModernHook.Handle?> = ArrayList<ModernHook.Handle?>()
    private val journal: ArrayDeque<String?> = ArrayDeque<String?>()
    private var app: Application? = null
    private var io: Handler? = null
    private var itemType: Class<*>? = null
    private var updateAll: Method? = null
    private var getBounds: Method? = null
    @Volatile private var enabled: Boolean = true
    @Volatile private var logging: Boolean = true
    private var replaying: Boolean = false
    private var installing: Boolean = false
    private var installed: Boolean = false
    @Volatile private var stopped: Boolean = false
    private val previousItems: MutableMap<Int?, String?> = HashMap<Int?, String?>()
    private var observed: String = ""
    private val repositoryTypes: MutableSet<Class<*>?> = HashSet<Class<*>?>()
    private var sequence: Long = 0
    private var geometryChanged: Long = 0
    private var dropped: Long = 0
    private var geometry: String = ""
    private var nativeBounds: WeakReference<Any?> = WeakReference<Any?>(null)
    private var touchDown: Boolean = false
    private var launcherResumed: Boolean = false
    private var recentsResumed: Boolean = false
    private val resumedActivities: MutableSet<Activity?>? =
        Collections.newSetFromMap<Activity?>(WeakHashMap<Activity?, Boolean?>())
    private val session: String? = UUID.randomUUID().toString()
    private val drain: Runnable = Runnable { this.flush() }

    fun start(application: Application?) {
        app = application
        val thread = HandlerThread("HomeLayoutJournal", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        io = Handler(thread.getLooper())
        readConfig()
        app!!
            .getContentResolver()
            .registerContentObserver(
                ConfigProvider.URI!!,
                false,
                object : ContentObserver(io) {
                    override fun onChange(selfChange: Boolean) {
                        readConfig()
                    }
                },
            )
        app!!.registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: Activity) {
                    activity(a, "resumed")
                }

                override fun onActivityPaused(a: Activity) {
                    activity(a, "paused")
                }

                override fun onActivityCreated(a: Activity, b: Bundle?) {
                    activity(a, "created")
                }

                override fun onActivityDestroyed(a: Activity) {
                    activity(a, "destroyed")
                }

                override fun onActivityStarted(a: Activity) {}

                override fun onActivityStopped(a: Activity) {}

                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            }
        )
        io!!.post(drain)
    }

    fun observe(workspace: ViewGroup) {
        if (app == null || stopped) return
        if (!installed) {
            if (!installing) install(workspace.javaClass.getClassLoader())
            if (!installed) return
        }
        try {
            val config = workspace.getResources().getConfiguration()
            val key: String =
                (workspace.getWidth().toString() +
                    "x" +
                    workspace.getHeight() +
                    "/" +
                    workspace.getChildCount() +
                    "/" +
                    config.orientation +
                    "/" +
                    config.screenWidthDp +
                    "/" +
                    config.screenHeightDp +
                    "/" +
                    config.densityDpi)
            if (key == observed) return
            observed = key
            collectLayouts(workspace)

            for (layout in ArrayList<ViewGroup?>(layouts)) {
                val vm = HomeLayoutGuard.call(layout!!, "getViewModel")
                if (JavaBoolean.TRUE == HomeLayoutGuard.call(vm!!, "isPreview")) continue
                observeRepository(vm!!)
            }
        } catch (error: Throwable) {
            disableRepair(error)
        }
    }

    private fun collectLayouts(view: ViewGroup) {
        if (
            view.javaClass.getName() ==
                "com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridCellLayout"
        ) {
            layouts!!.add(view)
            return
        }
        for (i in 0 until view.getChildCount()) run {
            val group = view.getChildAt(i) as? ViewGroup
            if (group != null) collectLayouts(group)
        }
    }

    private fun install(loader: ClassLoader?) {
        installing = true
        try {
            val version =
                app!!
                    .getPackageManager()
                    .getPackageInfo(app!!.getPackageName(), 0)
                    .getLongVersionCode()
            if (
                Build.VERSION.SDK_INT != 37 ||
                    version != Android17Hooks.LAUNCHER_VERSION ||
                    (app!!
                        .getPackageManager()
                        .getPackageInfo("com.samsung.android.app.homestar", 0)
                        .getLongVersionCode() != 1800005036L)
            )
                throw UnsupportedOperationException(
                    "No reviewed layout-repair profile for this launcher"
                )
            val bounds = Class.forName("z5.b", false, app!!.getClassLoader())
            val updateBounds =
                bounds.getDeclaredMethod("j", Context::class.java, WindowInsets::class.java)
            getBounds = bounds.getDeclaredMethod("b", Configuration::class.java)
            getBounds!!.setAccessible(true)
            val vm =
                Class.forName(
                    "com.honeyspace.ui.honeypots.freegrid.viewmodel.FreeGridViewModel",
                    false,
                    loader,
                )
            val layout =
                Class.forName(
                    "com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridCellLayout",
                    false,
                    loader,
                )
            itemType =
                Class.forName(
                    "com.honeyspace.ui.honeypots.freegrid.domain.model.FreeGridItem",
                    false,
                    loader,
                )
            updateAll = vm.getDeclaredMethod("updateAllItemByLayoutChanged")
            updateAll!!.setAccessible(true)
            vm.getDeclaredField("uiContext")
            vm.getDeclaredMethod("isPreview")
            // Resolve every repair contract before intercepting any behavior.
            hookBounds(updateBounds)
            hookLayoutUpdate()
            handles.addAll(
                ModernHook.hookAllConstructors(
                    layout,
                    object : ModernHook.Callback() {
                        override fun afterHookedMethod(p: ModernHook.Param) {
                            if (!p.hasThrowable()) layouts!!.add(p.thisObject as ViewGroup?)
                        }
                    },
                )
            )
            hookSanitizer(loader)
            installDiagnostics(vm, loader)
            val apk =
                app!!
                    .getPackageManager()
                    .getApplicationInfo("com.samsung.android.app.homestar", 0)
                    .sourceDir
            io!!.post(
                Runnable {
                    try {
                        var count = DexEvidence.deoptimizeLayoutCalls(apk, loader)
                        count +=
                            DexEvidence.deoptimizeLayoutCalls(
                                app!!.getApplicationInfo().sourceDir,
                                app!!.getClassLoader(),
                            )
                        record("callsites_ready", "count", count)
                    } catch (error: Throwable) {
                        main.post(Runnable { disableRepair(error) })
                    }
                }
            )
            installed = true
            record("ready", "profile", "android17-bzi9", "version", BuildConfig.VERSION_NAME)
        } catch (error: Throwable) {
            for (handle in handles) handle!!.unhook()
            handles.clear()
            stopped = true
            record("unsupported", "error", error.toString())
            ModernHook.log("HOME_LAYOUT_UNSUPPORTED " + error)
        }
    }

    private fun hookBounds(method: Method?) {
        handles.add(
            ModernHook.hookMethod(
                method!!,
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        try {
                            val windowName =
                                HomeLayoutGuard.call(p.thisObject!!, "getName").toString()
                            if ((app!!.getPackageName() + ".Launcher") != windowName) {
                                record("other_window", "name", windowName)
                                return
                            }
                            nativeBounds = WeakReference<Any?>(p.thisObject)
                            val context: Context? = p.args[0] as Context?
                            val bounds = configurationBounds(context!!)
                            val supplied: WindowInsets? = p.args[1] as WindowInsets?
                            val valid = HomeLayoutGuard.consistent(bounds!!, supplied)
                            if (!valid && enabled && !stopped) {
                                val current =
                                    context!!
                                        .getSystemService<WindowManager?>(WindowManager::class.java)
                                        .getCurrentWindowMetrics()
                                if (
                                    HomeLayoutGuard.same(bounds!!, current.getBounds()) &&
                                        HomeLayoutGuard.consistent(
                                            bounds!!,
                                            current.getWindowInsets(),
                                        )
                                ) {
                                    p.args[1] = current.getWindowInsets()
                                    record(
                                        "insets_replaced",
                                        "bounds",
                                        bounds,
                                        "oldCutout",
                                        HomeLayoutGuard.cutout(supplied),
                                        "newCutout",
                                        HomeLayoutGuard.cutout(p.args[1] as WindowInsets?),
                                    )
                                } else {
                                    // Retain the last native geometry; deferred sanitation cannot
                                    // persist this
                                    // transition.
                                    p.setResult(null)
                                    record(
                                        "insets_rejected",
                                        "bounds",
                                        bounds,
                                        "cutout",
                                        HomeLayoutGuard.cutout(supplied),
                                        "metrics",
                                        current.getBounds(),
                                    )
                                }
                            }
                            val key: String =
                                bounds.toString() +
                                    "/" +
                                    HomeLayoutGuard.cutout(p.args[1] as WindowInsets?)
                            if (key != geometry) {
                                geometry = key
                                geometryChanged = SystemClock.uptimeMillis()
                                record(
                                    "window",
                                    "bounds",
                                    bounds,
                                    "cutout",
                                    HomeLayoutGuard.cutout(supplied),
                                    "valid",
                                    valid,
                                )
                            }
                        } catch (error: Throwable) {
                            disableRepair(error)
                        }
                    }
                },
            )
        )
    }

    private fun hookLayoutUpdate() {
        handles.add(
            ModernHook.hookMethod(
                updateAll!!,
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        if (
                            !enabled ||
                                stopped ||
                                replaying ||
                                Looper.myLooper() != Looper.getMainLooper()
                        )
                            return
                        try {
                            if (
                                JavaBoolean.TRUE ==
                                    HomeLayoutGuard.call(p.thisObject!!, "isPreview")
                            )
                                return
                            observeRepository(p.thisObject!!)
                            var state = pending.get(p.thisObject)
                            if (state == null) {
                                state = Pending(p.thisObject)
                                pending.put(p.thisObject, state)
                            }
                            state!!.request()
                            p.setResult(null)
                        } catch (error: Throwable) {
                            disableRepair(error)
                        }
                    }
                },
            )
        )
    }

    @Throws(ReflectiveOperationException::class)
    private fun hookSanitizer(loader: ClassLoader?) {
        val type =
            Class.forName(
                "com.honeyspace.ui.honeypots.freegrid.domain.model.FreeGridDataSanitizer",
                false,
                loader,
            )
        var method: Method? = null
        for (candidate in type.getDeclaredMethods()) if (
            candidate.getName() == "sanitizeOnCellLayoutSizeChanged" &&
                candidate.getParameterCount() == 5
        )
            method = candidate
        if (method == null) throw NoSuchMethodException("Layout sanitizer")
        handles.add(
            ModernHook.hookMethod(
                method,
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        try {
                            val context: Context? = p.args[0] as Context?
                            val valid: Boolean =
                                !recentsResumed &&
                                    metricsValid(context!!) &&
                                    nativeGeometryValid(context!!) &&
                                    (SystemClock.uptimeMillis() - geometryChanged >=
                                        HomeLayoutPolicy.SETTLE_MS)
                            record(
                                "sanitize",
                                "reason",
                                p.args[4],
                                "orientation",
                                p.args[1],
                                "size",
                                p.args[2],
                                "valid",
                                valid,
                                "caller",
                                HomeLayoutGuard.stack(),
                            )
                            if (enabled && !stopped && !valid) {
                                p.setResult(PointF(1f, 1f))
                                record("sanitize_blocked", "reason", p.args[4], "size", p.args[2])
                            }
                        } catch (error: Throwable) {
                            disableRepair(error)
                        }
                    }

                    override fun afterHookedMethod(p: ModernHook.Param) {
                        record(
                            "sanitize_result",
                            "ratio",
                            p.getResult(),
                            "failed",
                            p.hasThrowable(),
                        )
                    }
                },
            )
        )
    }

    private inner class Pending(vm: Any?) : Runnable {
        @JvmField val owner: WeakReference<Any?>
        @JvmField var started: Long = 0
        @JvmField var candidate: Point? = null

        init {
            owner = WeakReference<Any?>(vm)
        }

        @Throws(ReflectiveOperationException::class)
        fun request() {
            if (started == 0L) {
                started = SystemClock.uptimeMillis()
                record(
                    "layout_deferred",
                    "size",
                    HomeLayoutGuard.call(owner.get()!!, "getCellLayoutSize"),
                )
            }
            candidate = Point(HomeLayoutGuard.call(owner.get()!!, "getCellLayoutSize") as Point)
            main.removeCallbacks(this)
            main.postDelayed(this, HomeLayoutPolicy.SETTLE_MS)
        }

        override fun run() {
            val vm = owner.get()
            if (vm == null) return
            try {
                if (enabled && !stopped && (!launcherResumed || recentsResumed)) {
                    started = 0
                    record(
                        "layout_wait_home",
                        "homeResumed",
                        launcherResumed,
                        "recentsResumed",
                        recentsResumed,
                    )
                    return
                }
                val context = HomeLayoutGuard.uiContext(vm)
                val current = Point(HomeLayoutGuard.call(vm, "getCellLayoutSize") as Point)
                var stable: Boolean =
                    candidate == current &&
                        SystemClock.uptimeMillis() - geometryChanged >=
                            HomeLayoutPolicy.SETTLE_MS &&
                        metricsValid(context!!) &&
                        nativeGeometryValid(context!!) &&
                        measuredGeometryValid(vm, current) &&
                        !touchDown
                stable =
                    HomeLayoutPolicy.canApply(
                        launcherResumed,
                        recentsResumed,
                        focusedHome(vm),
                        stable,
                    )
                if (enabled && !stopped && !stable) {
                    if (SystemClock.uptimeMillis() - started >= HomeLayoutPolicy.MAX_WAIT_MS) {
                        record(
                            "layout_abandoned",
                            "size",
                            current,
                            "reason",
                            "geometry did not settle; no write",
                            "homeResumed",
                            launcherResumed,
                            "recentsResumed",
                            recentsResumed,
                            "measured",
                            measuredSizes(vm),
                            "focused",
                            focusedHome(vm),
                        )
                        pending.remove(vm)
                        return
                    }
                    candidate = current
                    main.postDelayed(this, HomeLayoutPolicy.SETTLE_MS)
                    return
                }
                pending.remove(vm)
                replaying = true
                try {
                    updateAll!!.invoke(vm)

                    record("layout_applied", "size", current)
                } finally {
                    replaying = false
                }
            } catch (error: Throwable) {
                disableRepair(error)
            }
        }
    }

    @Throws(ReflectiveOperationException::class)
    private fun installDiagnostics(vm: Class<*>?, loader: ClassLoader?) {
        handles.add(
            ModernHook.findAndHookMethod(
                Activity::class.java,
                "dispatchTouchEvent",
                MotionEvent::class.java,
                object : ModernHook.Callback() {
                    private var downX: Float = 0f
                    private var downY: Float = 0f
                    private var downAt: Long = 0

                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        val activity: Activity? = p.thisObject as Activity?
                        if (app!!.getPackageName() != activity!!.getPackageName()) return
                        val event: MotionEvent? = p.args[0] as MotionEvent?
                        if (event!!.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            touchDown = true
                            downX = event!!.getX()
                            downY = event!!.getY()
                            downAt = event!!.getEventTime()
                        } else if (event!!.getActionMasked() == MotionEvent.ACTION_UP) {
                            touchDown = false
                            val dx: Float = event!!.getX() - downX
                            val dy: Float = event!!.getY() - downY
                            val slop =
                                ViewConfiguration.get(activity).getScaledTouchSlop().toFloat()
                            record(
                                "home_input",
                                "kind",
                                if (hypot(dx, dy) <= slop) "tap" else "swipe_or_drag",
                                "dx",
                                Math.round(dx),
                                "dy",
                                Math.round(dy),
                                "durationMs",
                                event!!.getEventTime() - downAt,
                            )
                        } else if (event!!.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                            touchDown = false
                        }
                    }
                },
            )
        )
        handles.add(
            ModernHook.findAndHookMethod(
                Activity::class.java,
                "startActivityForResult",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java,
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        val activity: Activity? = p.thisObject as Activity?
                        if (app!!.getPackageName() != activity!!.getPackageName()) return
                        val intent: Intent? = p.args[0] as Intent?
                        if (intent != null)
                            record(
                                "launch",
                                "component",
                                intent.getComponent(),
                                "action",
                                intent.getAction(),
                            )
                    }
                },
            )
        )
    }

    @Throws(ReflectiveOperationException::class)
    private fun observeRepository(vm: Any) {
        val field = vm.javaClass.getDeclaredField("freeGridRepository")
        field.setAccessible(true)
        val repository = field.get(vm)
        for (item in HomeLayoutGuard.call(vm, "getItems") as Iterable<*>) {
            val id = HomeLayoutGuard.number(item!!, "getId")
            if (!previousItems.containsKey(id)) {
                previousItems.put(id, HomeLayoutGuard.snapshot(item!!))
                record("item_baseline", "item", HomeLayoutGuard.snapshot(item!!))
            }
        }
        if (!repositoryTypes.add(repository.javaClass)) return
        for (update in repository.javaClass.getDeclaredMethods()) {
            if (
                (update.getName() != "update") ||
                    update.getParameterCount() == 0 ||
                    update.getParameterTypes()[0] != itemType ||
                    Modifier.isAbstract(update.getModifiers())
            )
                continue
            handles.add(
                ModernHook.hookMethod(
                    update,
                    object : ModernHook.Callback() {
                        override fun beforeHookedMethod(p: ModernHook.Param) {
                            if (!logging) return
                            try {
                                val id = HomeLayoutGuard.number(p.args[0]!!, "getId")
                                val next = HomeLayoutGuard.snapshot(p.args[0]!!)
                                val old = previousItems.put(id, next)
                                record(
                                    "item_write",
                                    "before",
                                    old,
                                    "after",
                                    next,
                                    "caller",
                                    HomeLayoutGuard.stack(),
                                )
                            } catch (error: Throwable) {
                                record(
                                    "item_write",
                                    "item",
                                    HomeLayoutGuard.snapshot(p.args[0]!!),
                                    "caller",
                                    HomeLayoutGuard.stack(),
                                )
                            }
                        }

                        override fun afterHookedMethod(p: ModernHook.Param) {
                            if (p.hasThrowable())
                                record("item_write_failed", "error", "native exception")
                        }
                    },
                )
            )
        }
        record("repository_observed", "class", repository.javaClass.getName())
    }

    private fun activity(activity: Activity, event: String?) {
        val name = activity.javaClass.getName()
        if ("resumed" == event) resumedActivities!!.add(activity)
        else if ("paused" == event || "destroyed" == event) resumedActivities!!.remove(activity)
        launcherResumed = false
        recentsResumed = false
        for (resumed in resumedActivities!!) {
            val resumedName = resumed!!.javaClass.getName()
            if ((app!!.getPackageName() + ".Launcher") == resumedName) launcherResumed = true
            if ("com.android.quickstep.RecentsActivity" == resumedName) recentsResumed = true
        }
        if (launcherResumed && !recentsResumed) {
            for (state in ArrayList<HomeLayoutGuard.Pending?>(pending.values)) {
                try {
                    state!!.request()
                } catch (error: Throwable) {
                    disableRepair(error)
                }
            }
        }

        record(
            "activity",
            "phase",
            event,
            "class",
            activity.javaClass.getName(),
            "orientation",
            activity.getResources().getConfiguration().orientation,
            "rotation",
            if (activity.getDisplay() == null) -1 else activity.getDisplay().getRotation(),
        )
    }

    private fun disableRepair(error: Throwable) {
        stopped = true
        record("repair_disabled", "error", error.toString(), "caller", HomeLayoutGuard.stack())
        ModernHook.log("HOME_LAYOUT_DISABLED " + error)
    }

    private fun readConfig() {
        try {
            val config =
                app!!.getContentResolver().call(ConfigProvider.URI!!, "layout_config", null, null)
            if (config == null) return
            enabled = config.getBoolean("protect", true)
            logging = config.getBoolean("logging", true)
            record("config", "protect", enabled, "logging", logging)
        } catch (error: Throwable) {
            ModernHook.log("HOME_LAYOUT_CONFIG " + error.javaClass.getSimpleName())
        }
    }

    @Synchronized
    private fun record(event: String?, vararg fields: Any?) {
        if (!logging) return
        try {
            val row = JSONObject()
            row.put("time", System.currentTimeMillis())
            row.put("uptime", SystemClock.uptimeMillis())
            row.put("session", session)
            row.put("pid", Process.myPid())
            row.put("seq", ++sequence)
            row.put("event", event)
            var i: Int = 0
            while (i + 1 < fields.size) {
                row.put(
                    fields[i].toString(),
                    if (fields[i + 1] == null) JSONObject.NULL else fields[i + 1].toString(),
                )
                i += 2
            }
            if (journal.size >= 256) {
                journal.removeFirst()
                dropped++
            }
            journal.addLast(row.toString() + "\n")
        } catch (ignored: Throwable) {
            /* Logging must never interrupt the launcher. */
        }
    }

    private fun flush() {
        val batch = StringBuilder()
        synchronized(this) {
            if (dropped > 0) {
                record("records_dropped", "count", dropped)
                dropped = 0
            }
            while (!journal.isEmpty() && batch.length + journal.peekFirst()!!.length <= 44000) batch
                .append(journal.removeFirst())
        }
        if (batch.length > 0) {
            try {
                val data = Bundle()
                data.putString("records", batch.toString())
                app!!.getContentResolver().call(ConfigProvider.URI!!, "layout_append", null, data)
            } catch (error: Throwable) {
                ModernHook.log("HOME_LAYOUT_JOURNAL " + error.javaClass.getSimpleName())
            }
        }
        io!!.postDelayed(drain, 2000)
    }

    @Throws(ReflectiveOperationException::class)
    private fun configurationBounds(context: Context): Rect? {
        return Rect(getBounds!!.invoke(null, context.getResources().getConfiguration()) as Rect?)
    }

    @Throws(ReflectiveOperationException::class)
    private fun metricsValid(context: Context): Boolean {
        val metrics =
            context
                .getSystemService<WindowManager?>(WindowManager::class.java)
                .getCurrentWindowMetrics()
        val bounds = configurationBounds(context)
        return HomeLayoutGuard.same(bounds!!, metrics.getBounds()) &&
            HomeLayoutGuard.consistent(bounds!!, metrics.getWindowInsets())
    }

    @Throws(ReflectiveOperationException::class)
    private fun nativeGeometryValid(context: Context): Boolean {
        val bounds = nativeBounds.get()
        if (bounds == null)
            return true // Before the first observed bounds update, check measured layout too.

        val current: Rect? = HomeLayoutGuard.call(bounds, "getBounds") as Rect?
        val safe: Rect? = HomeLayoutGuard.call(bounds, "getCutout") as Rect?
        return HomeLayoutGuard.same(current!!, configurationBounds(context)!!) &&
            HomeLayoutPolicy.consistent(
                current!!.width(),
                current!!.height(),
                safe!!.left,
                safe!!.top,
                safe!!.right,
                safe!!.bottom,
            )
    }

    @Throws(ReflectiveOperationException::class)
    private fun focusedHome(vm: Any?): Boolean {
        for (layout in ArrayList<ViewGroup?>(layouts)) if (
            layout!!.isAttachedToWindow() &&
                HomeLayoutGuard.call(layout, "getViewModel") === vm &&
                layout!!.hasWindowFocus()
        )
            return true
        return false
    }

    @Throws(ReflectiveOperationException::class)
    private fun measuredSizes(vm: Any?): String? {
        val result = StringBuilder()
        for (layout in ArrayList<ViewGroup?>(layouts)) if (
            layout!!.isAttachedToWindow() && HomeLayoutGuard.call(layout, "getViewModel") === vm
        )
            result.append(layout!!.getWidth()).append('x').append(layout!!.getHeight()).append(';')
        return result.toString()
    }

    @Throws(ReflectiveOperationException::class)
    private fun measuredGeometryValid(vm: Any?, expected: Point): Boolean {
        var found: Boolean = false
        for (layout in ArrayList<ViewGroup?>(layouts)) {
            if (
                !layout!!.isAttachedToWindow() ||
                    HomeLayoutGuard.call(layout, "getViewModel") !== vm
            )
                continue
            if (layout!!.getWidth() == 0 || layout!!.getHeight() == 0) continue
            if (
                !HomeLayoutPolicy.sameBounds(
                    expected.x,
                    expected.y,
                    layout!!.getWidth(),
                    layout!!.getHeight(),
                )
            )
                return false
            found = true
        }
        return found
    }

    companion object {
        @JvmStatic
        private fun same(a: Rect, b: Rect): Boolean {
            return HomeLayoutPolicy.sameBounds(a.width(), a.height(), b.width(), b.height())
        }

        @JvmStatic
        private fun consistent(bounds: Rect, insets: WindowInsets?): Boolean {
            if (insets == null) return false
            val cutout = insets.getDisplayCutout()
            return HomeLayoutPolicy.consistent(
                bounds.width(),
                bounds.height(),
                if (cutout == null) 0 else cutout.getSafeInsetLeft(),
                if (cutout == null) 0 else cutout.getSafeInsetTop(),
                if (cutout == null) 0 else cutout.getSafeInsetRight(),
                if (cutout == null) 0 else cutout.getSafeInsetBottom(),
            )
        }

        @JvmStatic
        private fun cutout(insets: WindowInsets?): String? {
            val cutout: DisplayCutout? = if (insets == null) null else insets.getDisplayCutout()
            return if (cutout == null) "none"
            else
                Rect(
                        cutout.getSafeInsetLeft(),
                        cutout.getSafeInsetTop(),
                        cutout.getSafeInsetRight(),
                        cutout.getSafeInsetBottom(),
                    )
                    .toShortString()
        }

        @JvmStatic
        @Throws(ReflectiveOperationException::class)
        private fun uiContext(vm: Any): Context? {
            val field = vm.javaClass.getDeclaredField("uiContext")
            field.setAccessible(true)
            return field.get(vm) as Context?
        }

        @JvmStatic
        @Throws(ReflectiveOperationException::class)
        private fun call(owner: Any, name: String?): Any? {
            var type = owner.javaClass
            while (type != null) {
                try {
                    val method = type.getDeclaredMethod(name)
                    method.setAccessible(true)
                    return method.invoke(owner)
                } catch (missing: NoSuchMethodException) {
                    type = type.getSuperclass()
                }
            }
            throw NoSuchMethodException(owner.javaClass.getName() + "." + name)
        }

        @JvmStatic
        @Throws(ReflectiveOperationException::class)
        private fun number(item: Any, name: String?): Int {
            return (HomeLayoutGuard.call(item, name) as Number).toInt()
        }

        @JvmStatic
        private fun snapshot(item: Any): String? {
            try {
                return ("id=" +
                    HomeLayoutGuard.number(item, "getId") +
                    ",type=" +
                    item.javaClass.getSimpleName() +
                    ",page=" +
                    HomeLayoutGuard.number(item, "getPageId") +
                    ",x=" +
                    HomeLayoutGuard.number(item, "getX") +
                    ",y=" +
                    HomeLayoutGuard.number(item, "getY") +
                    ",span=" +
                    HomeLayoutGuard.number(item, "getSpanX") +
                    "x" +
                    HomeLayoutGuard.number(item, "getSpanY") +
                    ",scale=" +
                    HomeLayoutGuard.call(item, "getScale"))
            } catch (error: Throwable) {
                return item.javaClass.getName() + ":snapshot unavailable"
            }
        }

        @JvmStatic
        private fun stack(): String? {
            val trace = StringBuilder()
            for (frame in Thread.currentThread().getStackTrace()) {
                val name = frame.getClassName()
                if (
                    name.startsWith("com.honeyspace.") ||
                        name.startsWith("com.sec.android.app.launcher.")
                ) {
                    if (trace.length > 0) trace.append(" <- ")
                    trace
                        .append(name)
                        .append('.')
                        .append(frame.getMethodName())
                        .append(':')
                        .append(frame.getLineNumber())
                    if (trace.length > 1800) break
                }
            }
            return trace.toString()
        }
    }
}
