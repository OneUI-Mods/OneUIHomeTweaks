package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.os.IBinder
import android.os.Looper
import android.provider.Settings.System
import android.view.ViewGroup
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import dev.ryuya.oneuihometweaks.compat.compatibleMethod
import dev.ryuya.oneuihometweaks.compat.compatibleName
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
    private val ownWallpaperWrite = ThreadLocal.withInitial { false }
    private var correctedNativeWrites = 0L
    private var nativeWriterFailed = false
    private var freeGridObserver: Consumer<ViewGroup?>? = null

    fun setFreeGridObserver(observer: Consumer<ViewGroup?>?) {
        freeGridObserver = observer
    }

    @Throws(Exception::class)
    fun install(loader: ClassLoader?) {
        check(DexEvidence.workspaceVerified) { "Workspace scroll DEX evidence absent" }
        val workspace =
            DexSymbols.findClass(
                "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceFastRecyclerView",
                false,
                loader,
            )
        cells =
            DexSymbols.findClass(
                "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceCellLayoutContainer",
                false,
                loader,
            )
        scrollForPage = workspace.compatibleMethod("getScrollForPage", Int::class.javaPrimitiveType)
        cellType = cells!!.compatibleMethod("getCellType")
        spaceInfo = workspace.compatibleMethod("getHoneySpaceInfo")
        visibleCount = workspace.compatibleMethod("getVisibleCount")
        currentState = workspace.compatibleDeclaredMethod("getCurrentState")
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
                    WallpaperManager::class
                        .java
                        .getDeclaredMethod(
                            "setWallpaperOffsets",
                            IBinder::class.java,
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                        ),
                    object : ModernHook.Callback() {
                        override fun beforeHookedMethod(p: ModernHook.Param) {
                            if (
                                !enabled ||
                                    failed ||
                                    ownWallpaperWrite.get() == true ||
                                    nativeWriterFailed ||
                                    Looper.myLooper() != Looper.getMainLooper()
                            )
                                return
                            val bridge = freeGrid?.takeIf { it.visible() } ?: this@PageBridge
                            if (
                                !bridge.enabled ||
                                    bridge.failed ||
                                    bridge.ownWallpaperWrite.get() == true
                            )
                                return
                            val view = bridge.last.get() ?: return
                            try {
                                val position = bridge.position(view) ?: return
                                if (p.args[0] !== position.token) return
                                val incoming = p.args[1] as? Float ?: return
                                // This writer has its own offset-step fields. Correct that same
                                // manager instance before its one native IPC sends both values.
                                (p.thisObject as WallpaperManager).setWallpaperOffsetSteps(
                                    position.step,
                                    0f,
                                )
                                p.args[1] = position.offset
                                p.args[2] = 0.5f
                                if (abs(incoming - position.offset) > 0.001f) {
                                    correctedNativeWrites++
                                    if (correctedNativeWrites <= 4)
                                        HookEntry.log(
                                            "PAGE_NATIVE_WRITE_CORRECTED x=$incoming -> ${position.offset} step=${position.step}"
                                        )
                                }
                            } catch (error: Throwable) {
                                // Retain native writes and stop retrying this correction after a
                                // failure.
                                nativeWriterFailed = true
                                HookEntry.log(
                                    "PAGE_NATIVE_WRITE_UNAVAILABLE ${error.javaClass.simpleName}"
                                )
                            }
                        }
                    },
                )
            )
            installed.add(
                ModernHook.hookMethod(
                    workspace.compatibleDeclaredMethod(
                        "notifyScrollX",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ),
                    events,
                )
            )
            installed.add(
                ModernHook.hookMethod(
                    workspace.compatibleDeclaredMethod("onAttachedToWindow"),
                    events,
                )
            )
            installed.add(
                ModernHook.hookMethod(
                    workspace.compatibleDeclaredMethod("onEndPageScrolling"),
                    events,
                )
            )
        } catch (t: Throwable) {
            for (h in installed) h!!.unhook()
            throw t
        }
        val base = DexSymbols.findClass("com.honeyspace.ui.common.FastRecyclerView", false, loader)
        val plugin =
            DexSymbols.findClass("com.honeyspace.common.plugin.PluginContextWrapper", false, loader)
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
                        bridge.installFreeGrid(DexSymbols.findClass(name, false, pluginLoader))
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
            plugin.compatibleDeclaredMethod("getClassLoader"),
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
                        bridge.installFreeGrid(DexSymbols.findClass(name, false, pluginLoader))
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
            !("swipeScrollToWithOverScroll" == method.compatibleName() &&
                !ModernHook.deoptimize(method))
        ) {
            "Failed to deoptimize workspace scroll caller"
        }
        HookEntry.log(
            "PAGE_HOOK_READY standard offsets; includes dynamically loaded Home Up discovery"
        )
    }

    @Throws(Exception::class)
    private fun installFreeGrid(workspace: Class<*>) {
        scrollForPage = workspace.compatibleMethod("getScrollForPage", Int::class.javaPrimitiveType)
        spaceInfo = workspace.compatibleMethod("getHoneySpaceInfo")
        visibleCount = workspace.compatibleMethod("getVisibleCount")
        currentState = workspace.compatibleDeclaredMethod("getCurrentState")
        currentState!!.setAccessible(true)
        indexedCellType =
            workspace.compatibleDeclaredMethod("getCellType", Int::class.javaPrimitiveType)
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
                    workspace.compatibleDeclaredMethod(
                        "notifyScrollX",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ),
                    events,
                )
            )
            installed.add(
                ModernHook.hookMethod(
                    workspace.compatibleDeclaredMethod("onAttachedToWindow"),
                    events,
                )
            )
            installed.add(
                ModernHook.hookMethod(
                    workspace.compatibleDeclaredMethod("onEndPageScrolling"),
                    events,
                )
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
        if ("com.sec.android.app.launcher.Launcher" != activity.javaClass.compatibleName()) return
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

    private data class Position(
        val token: IBinder,
        val offset: Float,
        val step: Float,
        val count: Int,
    )

    private fun position(view: ViewGroup): Position? {
        val owner = homeActivity.get()
        // Remote launcher previews build their own workspace. Never send their offsets to home.
        if (
            owner == null ||
                owner.isDestroyed() ||
                !owner.hasWindowFocus() ||
                view.getWindowToken() !== owner.getWindow().getDecorView().getWindowToken()
        )
            return null
        if (!isFreeGrid && freeGrid != null && freeGrid!!.visible()) return null
        if (
            !view.isAttachedToWindow() ||
                !view.isShown() ||
                view.getDisplay() == null ||
                view.getDisplay().getDisplayId() != 0
        )
            return null
        val info = spaceInfo!!.invoke(view)
        if (
            info.javaClass.compatibleMethod("isDexSpace").invoke(info) as Boolean ||
                visibleCount!!.invoke(view) as Int != 1
        )
            return null
        if (
            "com.honeyspace.sdk.HomeScreen\$Normal" !=
                currentState!!.invoke(view).javaClass.compatibleName()
        )
            return null
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
        if (positions.isEmpty()) return null
        val array = IntArray(positions.size)
        for (i in array.indices) array[i] = positions.get(i)!!
        if (array.size > 1 && array[0] == array[array.size - 1]) return null // not laid out yet

        val offset = PagePolicy.offset(view.getScrollX(), array)
        val step: Float = if (array.size > 1) 1f / (array.size - 1) else 0f
        val token = view.getWindowToken()
        if (token == null) return null
        return Position(token, offset, step, array.size)
    }

    private fun send(view: ViewGroup) {
        try {
            val position = position(view) ?: return
            val wm = WallpaperManager.getInstance(view.context)
            val previous = ownWallpaperWrite.get()
            ownWallpaperWrite.set(true)
            try {
                wm.setWallpaperOffsetSteps(position.step, 0f)
                wm.setWallpaperOffsets(position.token, position.offset, 0.5f)
            } finally {
                ownWallpaperWrite.set(previous)
            }
            val offset = position.offset
            val step = position.step
            val rank = offset * max(0, position.count - 1)
            if (abs(rank - Math.round(rank)) < 0.001f) {
                val report =
                    (if (isFreeGrid) "FREEGRID_PAGE_OFFSET pages=" else "PAGE_OFFSET pages=") +
                        position.count +
                        " rank=" +
                        Math.round(rank) +
                        " x=" +
                        offset +
                        " xStep=" +
                        step
                if (report != lastReport) {
                    HookEntry.log(report)
                    lastReport = report
                }
            }
        } catch (t: Throwable) {
            failed = true
            HookEntry.log("PAGE_DISABLED " + t.javaClass.simpleName + ": " + t.message)
        }
    }
}
