package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.os.IBinder
import android.os.Looper
import android.view.Choreographer
import android.view.ViewTreeObserver
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
    private var pendingView: WeakReference<ViewGroup?>? = null
    private var frameScheduled = false
    private val sendWallpaperOffset = Choreographer.FrameCallback {
        frameScheduled = false
        val view = pendingView?.get()
        pendingView = null
        if (view != null && enabled && !failed) send(view)
    }
    private var pagePositions = IntArray(0)
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
    private var focusObserver = WeakReference<ViewTreeObserver>(null)
    private val homeFocus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused) requestCurrentWorkspace()
    }
    private var lastReport: String = ""
    private var lastSkipped: String? = null
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

    internal fun stateForReload(): Array<WeakReference<ViewGroup?>> =
        arrayOf(last, freeGrid?.last ?: WeakReference(null))

    internal fun restoreObservations(saved: Any?) {
        val state = saved as? Array<*> ?: return
        fun view(index: Int) = (state.getOrNull(index) as? WeakReference<*>)?.get() as? ViewGroup
        view(0)?.takeIf(ViewGroup::isAttachedToWindow)?.let(::request)
        view(1)?.takeIf(ViewGroup::isAttachedToWindow)?.let { live ->
            val bridge = freeGrid ?: PageBridge().also {
                it.isFreeGrid = true
                it.homeActivity = homeActivity
                it.freeGridObserver = freeGridObserver
                it.installFreeGrid(live.javaClass)
                freeGrid = it
            }
            bridge.request(live)
        }
    }

    internal fun closeForReload() {
        enabled = false
        if (frameScheduled) {
            Choreographer.getInstance().removeFrameCallback(sendWallpaperOffset)
            frameScheduled = false
        }
        pendingView = null
        freeGrid?.closeForReload()
        freeGridObserver = null
        removeFocusListener()
    }

    fun setHomeWindow(activity: Activity) {
        if ("com.sec.android.app.launcher.Launcher" != activity.javaClass.compatibleName()) return
        val observer = activity.window.decorView.viewTreeObserver
        if (focusObserver.get() !== observer && observer.isAlive) {
            removeFocusListener()
            observer.addOnWindowFocusChangeListener(homeFocus)
            focusObserver = WeakReference(observer)
        }
        homeActivity = WeakReference(activity)
        freeGrid?.homeActivity = homeActivity
        requestCurrentWorkspace()
    }

    fun clearHomeWindow(activity: Activity) {
        if (homeActivity.get() !== activity) return
        homeActivity.clear()
        removeFocusListener()
    }

    private fun removeFocusListener() {
        focusObserver.get()?.takeIf(ViewTreeObserver::isAlive)
            ?.removeOnWindowFocusChangeListener(homeFocus)
        focusObserver.clear()
    }

    private fun requestCurrentWorkspace() {
        last.get()?.let(::request)
        freeGrid?.let { bridge -> bridge.last.get()?.let(bridge::request) }
    }

    private fun visible(): Boolean {
        val view = last.get()
        return view != null && view.isAttachedToWindow() && view.isShown()
    }

    private fun request(view: ViewGroup) {
        if (Looper.myLooper() !== Looper.getMainLooper()) return
        val owner = homeActivity.get()
        if (owner != null && !owner.isDestroyed()) {
            val expected = owner.getWindow().getDecorView().getWindowToken()
            if (expected != null && view.getWindowToken() !== expected) return
        }
        if (last.get() !== view) last = WeakReference(view)
        if (isFreeGrid) freeGridObserver?.accept(view)
        if (!enabled || failed) return
        pendingView = last
        // A replaced workspace can lose its View callbacks before detaching. Keep the
        // scheduled frame on the launcher looper and always use the newest workspace.
        if (!frameScheduled) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(sendWallpaperOffset)
        }
    }

    private fun skip(reason: String): Position? {
        if (lastSkipped != reason) {
            lastSkipped = reason
            HookEntry.log("PAGE_POSITION_WAIT freeGrid=$isFreeGrid reason=$reason")
        }
        return null
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
        if (owner == null || owner.isDestroyed()) return skip("no home activity")
        if (!owner.hasWindowFocus()) return skip("home does not have focus")
        if (view.windowToken !== owner.window.decorView.windowToken) return skip("different window")
        if (!isFreeGrid && freeGrid?.visible() == true) return skip("DIY workspace is visible")
        if (
            !view.isAttachedToWindow() ||
                !view.isShown() ||
                view.getDisplay() == null ||
                view.getDisplay().getDisplayId() != 0
        )
            return skip("workspace is hidden or on another display")
        val info = spaceInfo!!.invoke(view)
        if (
            info.javaClass.compatibleMethod("isDexSpace").invoke(info) as Boolean ||
                visibleCount!!.invoke(view) as Int != 1
        )
            return skip("desktop or multiple visible pages")
        if (
            "com.honeyspace.sdk.HomeScreen\$Normal" !=
                currentState!!.invoke(view).javaClass.compatibleName()
        )
            return skip("workspace is outside normal home mode")
        if (pagePositions.size < view.childCount) pagePositions = IntArray(view.childCount)
        var count = 0
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (
                if (indexedCellType != null)
                    ("CELL_LAYOUT" == (indexedCellType!!.invoke(view, i) as Enum<*>).name)
                else
                    cells!!.isInstance(child) &&
                        "CELL_LAYOUT" == (cellType!!.invoke(child) as Enum<*>).name
            )
                pagePositions[count++] = scrollForPage!!.invoke(view, i) as Int
        }
        if (count == 0 || (count > 1 && pagePositions[0] == pagePositions[count - 1]))
            return skip("no distinct page positions")

        val offset = PagePolicy.offset(view.scrollX, pagePositions, count)
        val step = if (count > 1) 1f / (count - 1) else 0f
        val token = view.windowToken ?: return skip("window token unavailable")
        return Position(token, offset, step, count)
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
