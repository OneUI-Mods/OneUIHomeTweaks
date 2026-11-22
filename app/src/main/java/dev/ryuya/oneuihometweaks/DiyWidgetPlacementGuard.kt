package dev.ryuya.oneuihometweaks

import android.appwidget.AppWidgetHostView
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import dev.ryuya.oneuihometweaks.compat.compatibleMethod
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/** Prevents a new icon position from intersecting a widget on the destination DIY page. */
internal class DiyWidgetPlacementGuard {
    private class Edit(val page: ViewGroup, val icon: View) {
        var blocked = false
    }

    private class Drop(val page: ViewGroup, val model: Any) {
        var size: Rect? = null
    }

    private class Geometry(
        val widget: Class<*>, val widgetDrop: Class<*>, val icon: Class<*>, val folder: Class<*>,
        val cellX: Method, val cellY: Method, val actualX: Method, val actualY: Method,
    )

    private val main = Handler(Looper.getMainLooper())
    private var workspace = WeakReference<ViewGroup>(null)
    private var enabled = false
    private var started = false
    private var installing = false
    private var edit: Edit? = null
    private var drop: Drop? = null
    private var loggedBlocks = 0
    private var reflow: DiyWidgetReflow? = null
    var onChanged: () -> Unit = {}

    fun observe(view: ViewGroup) {
        if (workspace.get() !== view) workspace = WeakReference(view)
        prepare()
        if (enabled) reflow?.observe(view)
    }

    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        prepare()
        reflow?.observe(workspace.get().takeIf { on })
        onChanged()
    }

    internal fun idleForHotReload() = !installing && edit == null && drop == null

    internal fun closeForReload() {
        enabled = false
        reflow?.observe(null)
        main.removeCallbacksAndMessages(null)
    }

    private fun prepare() {
        val owner = workspace.get() ?: return
        if (!enabled || started) return
        started = true
        installing = true
        val loader = owner.javaClass.classLoader
        val context = owner.context.applicationContext
        Thread({
            runCatching {
                @Suppress("DEPRECATION")
                val apk = context.packageManager.getApplicationInfo("com.samsung.android.app.homestar", 0).sourceDir
                install(loader, apk)
            }.onFailure {
                HookEntry.log("DIY_WIDGET_PLACEMENT_UNAVAILABLE ${it.javaClass.simpleName}: ${it.message}")
                main.post { installing = false }
            }
        }, "DiyWidgetPlacement").start()
    }

    private fun install(loader: ClassLoader?, apk: String) {
        fun type(name: String) = DexSymbols.findClass(name, false, loader)
        val prefix = "com.honeyspace.ui.honeypots.freegrid."
        val layout = type(prefix + "presentation.FreeGridCellLayout")
        val params = type(prefix + "presentation.FreeGridCellLayout\$FreeGridLayoutParams")
        val vm = type(prefix + "viewmodel.FreeGridViewModel")
        val position = type(prefix + "domain.model.ItemPosition")
        val sticker = type("com.honeyspace.ui.honeypots.sticker.StickerView")
        val frame = type("com.honeyspace.ui.honeypots.sticker.FrameControlStickerView")
        val iconView = type("com.honeyspace.common.iconview.IconView")
        val folder = type("com.honeyspace.ui.common.iconview.FolderIconViewImpl")
        val widget = type("com.honeyspace.common.widget.SpannableWidgetView")
        val widgetDrop = type("com.honeyspace.ui.common.interfaces.widget.WidgetDropAcceptable")
        val appItem = type("com.honeyspace.sdk.source.entity.AppItem")
        val dragInfo = type("com.honeyspace.sdk.DragInfo")
        val dragItem = type("com.honeyspace.sdk.DragItem")
        val int = Int::class.javaPrimitiveType
        val float = Float::class.javaPrimitiveType
        val boolean = Boolean::class.javaPrimitiveType
        val sync = layout.compatibleDeclaredMethod("syncPosition", frame)
        val translate = layout.compatibleDeclaredMethod("translateTarget", sticker, float, float, boolean)
        val setup = params.compatibleDeclaredMethod("setup", Point::class.java, float)
        val nativeDrop = layout.compatibleDeclaredMethod("onDrop", View::class.java, DragEvent::class.java, IntArray::class.java)
        val findDrop = vm.compatibleDeclaredMethod("findDropPosition", int, position, Rect::class.java)
        val getVm = layout.compatibleDeclaredMethod("getViewModel").apply { isAccessible = true }
        val getDrag = vm.compatibleMethod("getDragInfo")
        val getItems = dragInfo.compatibleMethod("getDragItems")
        val getItem = dragItem.compatibleMethod("getItem")
        val getTarget = sticker.compatibleMethod("getTargetView")
        val fit = layout.compatibleDeclaredMethod("fitTargetView", sticker).apply { isAccessible = true }
        val cellX = params.compatibleMethod("getCellX")
        val cellY = params.compatibleMethod("getCellY")
        val actualX = params.compatibleMethod("getX")
        val actualY = params.compatibleMethod("getY")
        val chosenX = position.compatibleMethod("getX")
        val chosenY = position.compatibleMethod("getY")
        check(nativeDrop.returnType == boolean && findDrop.returnType == boolean)

        val installed = mutableListOf<ModernHook.Handle>()
        try {
            val discovery = runCatching { DexEvidence.deoptimizeDiyWidgetPlacement(apk, loader) }
            if (discovery.isFailure) {
                check(Build.VERSION.INCREMENTAL == "S948QOPU1BZI9") { "DIY placement call sites unavailable" }
                // Verified in Home Up 18.0.00.36 on this firmware.
                val layoutCallers = setOf("onDragDrop", "onSelectedItemMoveEvent", "translateTarget\$default")
                val vmCallers = setOf("endReorder", "endReorder\$lambda\$4\$1", "endReorder\$lambda\$4\$2")
                val callers = layout.declaredMethods.filter { it.name in layoutCallers } +
                    vm.declaredMethods.filter { it.name in vmCallers }
                check(callers.map { it.name }.toSet() == layoutCallers + vmCallers)
                for (caller in callers) {
                    check(ModernHook.deoptimize(caller)) { "DIY placement caller deoptimization unavailable" }
                }
            }
            check(ModernHook.deoptimize(sync) && ModernHook.deoptimize(translate))
            // The Kotlin default-argument bridge can inline setup into the edit callbacks.
            val setupDefault = params.compatibleDeclaredMethod(
                "setup\$default", params, Point::class.java, float, int, Any::class.java,
            )
            check(ModernHook.deoptimize(setupDefault)) { "DIY position bridge deoptimization unavailable" }
            for (move in listOf(sync, translate)) {
                // Run outside the optional grid-snap hook so its final setup call is covered too.
                installed += ModernHook.aroundMethod(move, XposedInterface.PRIORITY_HIGHEST) { chain ->
                    val page = chain.thisObject as ViewGroup
                    if (!active(page)) return@aroundMethod chain.proceed()
                    val control = chain.args[0]
                    val target = getTarget.invoke(control) as? View
                    if (target == null || target.parent !== page || !isAppIcon(target, iconView, folder)) {
                        return@aroundMethod chain.proceed()
                    }
                    val previous = edit
                    val current = Edit(page, target)
                    edit = current
                    try {
                        val result = chain.proceed()
                        if (current.blocked) fit.invoke(page, control)
                        result
                    } finally {
                        edit = previous
                    }
                }
            }
            installed += ModernHook.aroundMethod(setup) { chain ->
                val current = edit
                if (!main.looper.isCurrentThread || current == null ||
                    current.icon.layoutParams !== chain.thisObject) return@aroundMethod chain.proceed()
                val point = chain.args[0] as Point
                val lp = chain.thisObject
                val dx = point.x - (cellX.invoke(lp) as Int)
                val dy = point.y - (cellY.invoke(lp) as Int)
                val direction = if (current.page.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
                val bounds = boundsInPage(current.icon).apply {
                    offset((actualX.invoke(lp) as Int) - current.icon.left + dx * direction.toFloat(),
                        (actualY.invoke(lp) as Int) - current.icon.top + dy.toFloat())
                }
                if (!overlapsWidget(current.page, bounds, widget, widgetDrop)) {
                    return@aroundMethod chain.proceed()
                }
                current.blocked = true
                reportBlocked("editor")
                null
            }
            installed += ModernHook.aroundMethod(nativeDrop) { chain ->
                val page = chain.thisObject as ViewGroup
                if (!active(page)) return@aroundMethod chain.proceed()
                val owner = getVm.invoke(page) ?: return@aroundMethod chain.proceed()
                val info = getDrag.invoke(owner) ?: return@aroundMethod chain.proceed()
                val items = getItems.invoke(info) as? List<*> ?: return@aroundMethod chain.proceed()
                if (items.isEmpty() || !items.all { it != null && appItem.isInstance(getItem.invoke(it)) }) {
                    return@aroundMethod chain.proceed()
                }
                val previous = drop
                drop = Drop(page, owner)
                try {
                    chain.proceed()
                } finally {
                    drop = previous
                }
            }
            installed += ModernHook.aroundMethod(findDrop) { chain ->
                val result = chain.proceed()
                val current = drop
                if (result != true || !main.looper.isCurrentThread || current == null ||
                    current.model !== chain.thisObject) return@aroundMethod result
                val chosen = chain.args[1]
                val size = chain.args[2] as Rect
                val x = chosenX.invoke(chosen) as Int
                val y = chosenY.invoke(chosen) as Int
                val blocked = overlapsWidget(current.page, dropBounds(current.page, x, y, size), widget, widgetDrop)
                current.size = if (blocked) null else Rect(size)
                if (blocked) reportBlocked("drop")
                !blocked
            }
        } catch (failure: Throwable) {
            installed.asReversed().forEach(ModernHook.Handle::unhook)
            throw failure
        }
        geometry = Geometry(widget, widgetDrop, iconView, folder, cellX, cellY, actualX, actualY)
        HookEntry.log("DIY_WIDGET_PLACEMENT_READY")
        main.post {
            installing = false
            reflow = runCatching {
                DiyWidgetReflow(
                    loader, layout, vm, params,
                    { isWidget(it, widget, widgetDrop) },
                    { isAppIcon(it, iconView, folder) },
                    { onChanged() },
                )
            }.onFailure {
                HookEntry.log("DIY_WIDGET_REFLOW_UNAVAILABLE ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()
            if (enabled) reflow?.observe(workspace.get())
            onChanged()
        }
    }

    @Volatile private var geometry: Geometry? = null

    fun checksGridProjection(): Boolean = enabled && geometry != null

    fun permitsGridProjection(page: ViewGroup, view: View, id: Int, saved: Point, projected: Point): Boolean {
        val shapes = geometry ?: return true
        if (!active(page) || view.parent !== page || !isAppIcon(view, shapes.icon, shapes.folder) ||
            saved == projected || view.width <= 0 || view.height <= 0) return true
        return runCatching {
            if (reflow?.preservesPosition(page, id, saved.x, saved.y) == true) return false
            val lp = view.layoutParams
            val cellX = shapes.cellX.invoke(lp) as Int
            val cellY = shapes.cellY.invoke(lp) as Int
            val actualX = shapes.actualX.invoke(lp) as Int
            val actualY = shapes.actualY.invoke(lp) as Int
            val sign = if (page.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
            fun intersects(point: Point): Boolean {
                val bounds = boundsInPage(view).apply {
                    offset(actualX - view.left + (point.x - cellX) * sign.toFloat(),
                        actualY - view.top + (point.y - cellY).toFloat())
                }
                return overlapsWidget(page, bounds, shapes.widget, shapes.widgetDrop)
            }
            // Preserve the original projection of an icon already saved over a widget.
            !intersects(projected) || intersects(saved)
        }.getOrDefault(true)
    }

    fun permitsSnappedDrop(owner: Any?, point: Point): Boolean {
        val current = drop ?: return true
        val size = current.size ?: return true
        if (current.model !== owner) return true
        val shapes = geometry ?: return true
        return !overlapsWidget(current.page, dropBounds(current.page, point.x, point.y, size), shapes.widget, shapes.widgetDrop)
    }

    private fun active(page: ViewGroup): Boolean {
        if (!main.looper.isCurrentThread || !enabled || !page.isAttachedToWindow) return false
        val observed = workspace.get() ?: return false
        return generateSequence(page as View) { it.parent as? View }.any { it === observed }
    }

    private fun reportBlocked(operation: String) {
        if (loggedBlocks < 4) {
            loggedBlocks++
            HookEntry.log("DIY_WIDGET_PLACEMENT_BLOCKED $operation")
        }
    }

    private fun dropBounds(page: ViewGroup, x: Int, y: Int, size: Rect): RectF {
        val left = if (page.layoutDirection == View.LAYOUT_DIRECTION_RTL) page.width - x - size.width() else x
        return RectF(left.toFloat(), y.toFloat(), (left + size.width()).toFloat(), (y + size.height()).toFloat())
    }

    private fun overlapsWidget(page: ViewGroup, icon: RectF, widget: Class<*>, widgetDrop: Class<*>): Boolean {
        if (icon.isEmpty) return false
        for (index in 0 until page.childCount) {
            val child = page.getChildAt(index)
            if (child.visibility != View.VISIBLE || child.width <= 0 || child.height <= 0 ||
                !isWidget(child, widget, widgetDrop)) continue
            if (RectF.intersects(icon, boundsInPage(child))) return true
        }
        return false
    }

    private fun isWidget(view: View, widget: Class<*>, widgetDrop: Class<*>): Boolean =
        view is AppWidgetHostView || widget.isInstance(view) || widgetDrop.isInstance(view)

    private fun isAppIcon(view: View, icon: Class<*>, folder: Class<*>): Boolean {
        if (icon.isInstance(view)) return !folder.isInstance(view)
        // Home's grid positions the outer IconContainer; the icon itself is its child.
        val container = view as? ViewGroup ?: return false
        return (0 until container.childCount).any { index ->
            val child = container.getChildAt(index)
            icon.isInstance(child) && !folder.isInstance(child)
        }
    }

    private fun boundsInPage(view: View): RectF = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()).apply {
        view.matrix.mapRect(this)
        offset(view.left.toFloat(), view.top.toFloat())
    }
}
