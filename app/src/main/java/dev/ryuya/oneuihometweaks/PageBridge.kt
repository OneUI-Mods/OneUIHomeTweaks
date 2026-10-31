package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.provider.Settings.System
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.function.Consumer
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.log
import kotlin.math.max
import kotlin.math.round

/* Sends standard launcher offsets; never synthesizes swipe gestures in the wallpaper. */
class PageBridge {
    @Volatile private var enabled: Boolean = false
    private var last: WeakReference<ViewGroup?> = WeakReference<ViewGroup?>(null)
    private var pending: Boolean = false
    private var failed: Boolean = false
    private var scrollForPage: Method? = null
    private var cellType: Method? = null
    private var spaceInfo: Method? = null
    private var currentState: Method? = null
    private var visibleCount: Method? = null
    private var cells: Class<*>? = null
    private var indexedCellType: Method? = null
    private var freeGrid: PageBridge? = null
    private var isFreeGrid: Boolean = false
    private var discovering: Boolean = false
    private var discoveryFailed: Boolean = false
    private var homeActivity: WeakReference<Activity?> = WeakReference<Activity?>(null)
    private var lastReport: String = ""
    private var freeGridObserver: Consumer<ViewGroup?>? = null

    fun setFreeGridObserver(observer: Consumer<ViewGroup?>?) {
        freeGridObserver = observer
    }

    @Throws(Exception::class)
    fun install(loader: ClassLoader?) {
        check(DexEvidence.workspaceVerified) { "Workspace scroll DEX evidence absent" }
        val workspace =
            Class.forName(
                "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceFastRecyclerView",
                false,
                loader,
            )
        cells =
            Class.forName(
                "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceCellLayoutContainer",
                false,
                loader,
            )
        scrollForPage = workspace.getMethod("getScrollForPage", Int::class.javaPrimitiveType)
        cellType = cells!!.getMethod("getCellType")
        spaceInfo = workspace.getMethod("getHoneySpaceInfo")
        visibleCount = workspace.getMethod("getVisibleCount")
        currentState = workspace.getDeclaredMethod("getCurrentState")
        currentState!!.setAccessible(true)
        val events: ModernHook.Callback =
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (!p.hasThrowable()) request(p.thisObject as ViewGroup)
                }
            }
        val installed = ArrayList<ModernHook.Handle?>()
        try {
            installed.add(
                ModernHook.hookMethod(
                    workspace.getDeclaredMethod(
                        "notifyScrollX",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ),
                    events,
                )
            )
            installed.add(
                ModernHook.hookMethod(workspace.getDeclaredMethod("onAttachedToWindow"), events)
            )
            installed.add(
                ModernHook.hookMethod(workspace.getDeclaredMethod("onEndPageScrolling"), events)
            )
        } catch (t: Throwable) {
            for (h in installed) h!!.unhook()
            throw t
        }
        val base = Class.forName("com.honeyspace.ui.common.FastRecyclerView", false, loader)
        val plugin =
            Class.forName("com.honeyspace.common.plugin.PluginContextWrapper", false, loader)
        ModernHook.hookAllConstructors(
            plugin,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    val patternPrefix0 = p.hasThrowable() || freeGrid != null || p.args.size != 3
                    val ctx = if (!patternPrefix0) p.args[0] as? Context else null
                    val patternPrefix1 = patternPrefix0 || ctx == null
                    val pluginLoader = if (!patternPrefix1) p.args[2] as? ClassLoader else null
                    if (
                        patternPrefix1 ||
                            pluginLoader == null ||
                            "com.samsung.android.app.homestar" != ctx.getPackageName()
                    )
                        return
                    try {
                        val name: String =
                            "com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridFastRecyclerView"
                        DexEvidence.verifyScrollContract(ctx.getApplicationInfo().sourceDir, name)
                        val bridge = PageBridge()
                        bridge.isFreeGrid = true
                        bridge.freeGridObserver = freeGridObserver
                        bridge.installFreeGrid(Class.forName(name, false, pluginLoader))
                        freeGrid = bridge
                        bridge.homeActivity = homeActivity
                        bridge.setEnabled(enabled)
                    } catch (t: Throwable) {
                        HookEntry.log("FREEGRID_PAGE_UNSUPPORTED " + t)
                    }
                }
            },
        )
        ModernHook.hookMethod(
            plugin.getDeclaredMethod("getClassLoader"),
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    val patternPrefix2 =
                        p.hasThrowable() || freeGrid != null || discovering || discoveryFailed
                    val pluginLoader = if (!patternPrefix2) p.getResult() as? ClassLoader else null
                    if (
                        patternPrefix2 ||
                            pluginLoader == null ||
                            p.thisObject !is Context ||
                            "com.samsung.android.app.homestar" !=
                                (p.thisObject as Context).getPackageName()
                    )
                        return
                    discovering = true
                    try {
                        val name: String =
                            "com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridFastRecyclerView"
                        DexEvidence.verifyScrollContract(
                            (p.thisObject as Context).getApplicationInfo().sourceDir,
                            name,
                        )
                        val bridge = PageBridge()
                        bridge.isFreeGrid = true
                        bridge.freeGridObserver = freeGridObserver
                        bridge.installFreeGrid(Class.forName(name, false, pluginLoader))
                        freeGrid = bridge
                        bridge.homeActivity = homeActivity
                        bridge.setEnabled(enabled)
                    } catch (t: Throwable) {
                        discoveryFailed = true
                        HookEntry.log("FREEGRID_PAGE_UNSUPPORTED " + t)
                    } finally {
                        discovering = false
                    }
                }
            },
        )
        // Verified caller contains the actual virtual scroll callback; undo possible ART inlining.
        for (method in base.getDeclaredMethods()) check(
            !("swipeScrollToWithOverScroll" == method.getName() && !ModernHook.deoptimize(method))
        ) {
            "Failed to deoptimize workspace scroll caller"
        }
        HookEntry.log(
            "PAGE_HOOK_READY standard offsets; includes dynamically loaded Home Up discovery"
        )
    }

    @Throws(Exception::class)
    private fun installFreeGrid(workspace: Class<*>) {
        scrollForPage = workspace.getMethod("getScrollForPage", Int::class.javaPrimitiveType)
        spaceInfo = workspace.getMethod("getHoneySpaceInfo")
        visibleCount = workspace.getMethod("getVisibleCount")
        currentState = workspace.getDeclaredMethod("getCurrentState")
        currentState!!.setAccessible(true)
        indexedCellType = workspace.getDeclaredMethod("getCellType", Int::class.javaPrimitiveType)
        indexedCellType!!.setAccessible(true)
        val installed = ArrayList<ModernHook.Handle?>()
        val events: ModernHook.Callback =
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (!p.hasThrowable()) request(p.thisObject as ViewGroup)
                }
            }
        try {
            installed.add(
                ModernHook.hookMethod(
                    workspace.getDeclaredMethod(
                        "notifyScrollX",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ),
                    events,
                )
            )
            installed.add(
                ModernHook.hookMethod(workspace.getDeclaredMethod("onAttachedToWindow"), events)
            )
            installed.add(
                ModernHook.hookMethod(workspace.getDeclaredMethod("onEndPageScrolling"), events)
            )
        } catch (t: Throwable) {
            for (h in installed) h!!.unhook()
            throw t
        }
        HookEntry.log("FREEGRID_PAGE_HOOK_READY runtime class + DEX verified")
    }

    fun setEnabled(value: Boolean) {
        val before: Boolean = enabled
        enabled = value
        if (!isFreeGrid && before && !value) restoreNativePosition()
        if (freeGrid != null) freeGrid!!.setEnabled(value)
        val view = last.get()
        if (value && view != null) request(view)
    }

    private fun restoreNativePosition() {
        val owner = homeActivity.get()
        if (owner == null || owner.isDestroyed()) return
        try {
            val token = owner.getWindow().getDecorView().getWindowToken()
            if (token == null) return
            val wm = WallpaperManager.getInstance(owner)
            if (wm.getWallpaperInfo() == null) return
            val x =
                System.getFloat(
                    owner.getContentResolver(),
                    "wallpaper_x_offset",
                    0.5f,
                )
            val y =
                System.getFloat(
                    owner.getContentResolver(),
                    "wallpaper_y_offset",
                    0.5f,
                )
            wm.setWallpaperOffsetSteps(-1f, -1f)
            wm.setWallpaperOffsets(token, x, y)
            HookEntry.log("PAGE_NATIVE_POSITION_RESTORED")
        } catch (t: Throwable) {
            HookEntry.log("PAGE_RESTORE_ERROR " + t.javaClass.getSimpleName())
        }
    }

    fun setHomeWindow(activity: Activity) {
        if ("com.sec.android.app.launcher.Launcher" != activity.javaClass.getName()) return
        homeActivity = WeakReference<Activity?>(activity)
        if (freeGrid != null) freeGrid!!.homeActivity = homeActivity
        val view = last.get()
        if (view != null) request(view)
        if (freeGrid != null && freeGrid!!.last.get() != null)
            freeGrid!!.request(freeGrid!!.last.get()!!)
    }

    private fun visible(): Boolean {
        val view = last.get()
        return view != null && view.isAttachedToWindow() && view.isShown()
    }

    private fun request(view: ViewGroup) {
        val owner = homeActivity.get()
        if (owner != null && !owner.isDestroyed()) {
            val expected = owner.getWindow().getDecorView().getWindowToken()
            if (expected != null && view.getWindowToken() !== expected) return
        }
        last = WeakReference<ViewGroup?>(view)
        if (isFreeGrid && freeGridObserver != null) freeGridObserver!!.accept(view)
        if (!enabled || failed || pending) return
        pending = true
        view.postOnAnimation(
            Runnable {
                pending = false
                if (enabled && !failed) send(view)
            }
        )
    }

    private fun send(view: ViewGroup) {
        try {
            val owner = homeActivity.get()
            // Remote launcher previews build their own workspace. Never send their offsets to home.
            if (
                owner == null ||
                    owner.isDestroyed() ||
                    !owner.hasWindowFocus() ||
                    view.getWindowToken() !== owner.getWindow().getDecorView().getWindowToken()
            )
                return
            if (!isFreeGrid && freeGrid != null && freeGrid!!.visible()) return
            if (
                !view.isAttachedToWindow() ||
                    !view.isShown() ||
                    view.getDisplay() == null ||
                    view.getDisplay().getDisplayId() != 0
            )
                return
            val info = spaceInfo!!.invoke(view)
            if (
                info.javaClass.getMethod("isDexSpace").invoke(info) as Boolean ||
                    visibleCount!!.invoke(view) as Int != 1
            )
                return
            if (
                "com.honeyspace.sdk.HomeScreen\$Normal" !=
                    currentState!!.invoke(view).javaClass.getName()
            )
                return
            val positions = ArrayList<Int?>()
            for (i in 0 until view.getChildCount()) {
                val child = view.getChildAt(i)
                if (
                    if (indexedCellType != null)
                        ("CELL_LAYOUT" == (indexedCellType!!.invoke(view, i) as Enum<*>).name)
                    else
                        cells!!.isInstance(child) &&
                            "CELL_LAYOUT" == (cellType!!.invoke(child) as Enum<*>).name
                )
                    positions.add(scrollForPage!!.invoke(view, i) as Int?)
            }
            if (positions.isEmpty()) return
            val array = IntArray(positions.size)
            for (i in array.indices) array[i] = positions.get(i)!!
            if (array.size > 1 && array[0] == array[array.size - 1]) return // not laid out yet

            val offset = PagePolicy.offset(view.getScrollX(), array)
            val step: Float = if (array.size > 1) 1f / (array.size - 1) else 0f
            val token = view.getWindowToken()
            if (token == null) return
            val wm = WallpaperManager.getInstance(view.getContext())
            wm.setWallpaperOffsetSteps(step, 0f)
            wm.setWallpaperOffsets(token, offset, 0.5f)
            val rank: Float = offset * max(0, array.size - 1)
            if (abs(rank - Math.round(rank)) < 0.001f) {
                val report: String =
                    ((if (isFreeGrid) "FREEGRID_PAGE_OFFSET pages=" else "PAGE_OFFSET pages=") +
                        array.size +
                        " rank=" +
                        Math.round(rank) +
                        " x=" +
                        offset +
                        " xStep=" +
                        step)
                if (report != lastReport) {
                    HookEntry.log(report)
                    lastReport = report
                }
            }
        } catch (t: Throwable) {
            failed = true
            HookEntry.log("PAGE_DISABLED " + t.javaClass.getSimpleName() + ": " + t.message)
        }
    }
}
