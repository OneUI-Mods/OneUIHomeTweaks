package dev.ryuya.oneuihometweaks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.Choreographer
import android.view.View
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleName
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.min

/** Substitutes a glass plate for the closed folder's baked background. Samsung's mini icons stay separate. */
internal class GlassFolderIcons(
    loader: ClassLoader,
    private val level: () -> Int,
    private val dark: () -> Boolean,
    private val material: GlassMaterial,
    private val onDrawn: () -> Unit,
) {
    private enum class Placement(val label: String) {
        HOME("home"), DOCK("dock"), DIY_HOME("diy"),
    }

    private class DrawHost(val view: View, val placement: Placement)

    private class Capture(val supplier: Any, val background: Bitmap) {
        var icons: Bitmap? = null
    }
    private class Scene(val original: BitmapDrawable, val background: Bitmap?, val icons: Bitmap) {
        var plate: BitmapDrawable? = null
    }

    private class Pending(view: View, supplier: Any, original: BitmapDrawable) {
        val view = WeakReference(view)
        val supplier = WeakReference(supplier)
        val original = WeakReference(original)
    }

    private val pending = ArrayDeque<Pending>()
    private val requested = WeakHashMap<Any, WeakReference<BitmapDrawable>>()
    private var queued = false
    private val capture = ThreadLocal<Capture>()
    private val folderDraw = ThreadLocal<DrawHost>()
    private val scenes = WeakHashMap<Any, Scene>()
    private val seen = WeakHashMap<View, Unit>()
    private val contours = mutableMapOf<Int, Path>()
    private val reported = mutableSetOf<Placement>()
    @Volatile private var failed = false

    init {
        fun type(name: String) = DexSymbols.findClass("com.honeyspace.ui.common.iconview.$name", false, loader)
        val supplier = type("FolderIconSupplier")
        val folderView = type("FolderIconViewImpl").asSubclass(View::class.java)
        val iconView = type("IconViewImpl")
        val getAncestor = iconView.getMethod("getAncestorTypeForAnim")
        val getPreviewDrawer = iconView.getMethod("getFolderPreviewDrawer")
        val getMask = getPreviewDrawer.returnType.getMethod("getBgMask", Context::class.java)
        val getStockComposite = supplier.getMethod("getIconWithBg")
        val previewFactory = folderView.getMethod("getCreateIconBitmap")
        val invokeFactory = previewFactory.returnType.methods.single {
            java.lang.reflect.Modifier.isAbstract(it.modifiers) && it.parameterCount == 0
        }
        val onlyIcons = supplier.getMethod("getDrawOnlyIcon")
        val locked = supplier.getMethod("getLocked")
        val draw = iconView.getDeclaredMethod("onDraw", Canvas::class.java)
        val childPreview = supplier.getDeclaredMethod("createIconBitmap", List::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val build = supplier.getDeclaredMethod("updateIconWithBg", Bitmap::class.java, List::class.java, Int::class.javaPrimitiveType)
        val getters = supplier.declaredMethods.filter {
            it.name == "get" && it.parameterCount == 0 && (it.returnType == Drawable::class.java || it.returnType == Any::class.java)
        }
        check(getters.any { it.returnType == Drawable::class.java })
        check(childPreview.returnType == Bitmap::class.java && getStockComposite.returnType == Drawable::class.java)
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            // Keep both of the stock inputs before the supplier composites them into one bitmap.
            ModernHook.deoptimize(build)
            ModernHook.deoptimize(draw)
            handles += ModernHook.aroundMethod(childPreview) { chain ->
                val result = chain.proceed()
                capture.get()?.takeIf { it.supplier === chain.thisObject }?.icons = result as? Bitmap
                result
            }
            handles += ModernHook.aroundMethod(build) { chain ->
                if (failed) return@aroundMethod chain.proceed()
                val stockBackground = chain.args[0] as? Bitmap ?: return@aroundMethod chain.proceed()
                val current = Capture(chain.thisObject, stockBackground)
                val previous = capture.get()
                capture.set(current)
                try {
                    val result = chain.proceed()
                    val stock = getStockComposite.invoke(current.supplier) as? BitmapDrawable
                    val icons = current.icons
                    if (stock != null && icons != null && !icons.isRecycled && icons.width <= 384 && icons.height <= 384) {
                        synchronized(scenes) { scenes[current.supplier] = Scene(stock, stockBackground, icons) }
                    }
                    result
                } finally {
                    if (previous == null) capture.remove() else capture.set(previous)
                }
            }
            handles += ModernHook.aroundMethod(draw) { chain ->
                val view = chain.thisObject as View
                if (!folderView.isInstance(view) || failed || level() <= 0 || view.display?.displayId != 0 ||
                    !(chain.args[0] as Canvas).isHardwareAccelerated) return@aroundMethod chain.proceed()
                val ancestor = (getAncestor.invoke(view) as? Enum<*>)?.name
                val placement = placementOf(view, ancestor) ?: return@aroundMethod chain.proceed()
                seen[view] = Unit
                val previous = folderDraw.get()
                folderDraw.set(DrawHost(view, placement))
                try {
                    chain.proceed()
                } finally {
                    if (previous == null) folderDraw.remove() else folderDraw.set(previous)
                }
            }
            // Calls through Supplier may enter an erased bridge on some launcher builds.
            for (get in getters) {
                ModernHook.deoptimize(get)
                handles += ModernHook.aroundMethod(get) { chain ->
                    val result = chain.proceed()
                    val view = folderDraw.get()?.view ?: return@aroundMethod result
                    val source = chain.thisObject
                    if (failed || level() <= 0 || result !is BitmapDrawable || result is Plate ||
                        locked.invoke(source) == true || onlyIcons.invoke(source) == true) return@aroundMethod result
                    try {
                        val scene = synchronized(scenes) { scenes[source] }
                        if (scene != null && result === scene.original) {
                            val contour = contours.getOrPut(view.resources.configuration.densityDpi) {
                                runCatching {
                                    (getMask.invoke(getPreviewDrawer.invoke(view), view.context) as? Bitmap)?.let(::readContour)
                                }.getOrNull() ?: defaultContour()
                            }
                            scene.plate ?: Plate(view, scene, source, contour) { onlyIcons.invoke(it) == true || locked.invoke(it) == true }
                                .also { scene.plate = it }
                        } else {
                            requestPreview(view, source, result) { host, model, expected ->
                                if (expected !== getStockComposite.invoke(model) || onlyIcons.invoke(model) == true || locked.invoke(model) == true) null
                                else previewFactory.invoke(host)?.let { invokeFactory.invoke(it) as? Bitmap }
                            }
                            result
                        }
                    } catch (error: Exception) {
                        disable(error)
                        result
                    }
                }
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach { it.unhook() }
            throw error
        }
    }

    private fun placementOf(view: View, ancestor: String?): Placement? = when (ancestor) {
        "HOME" -> Placement.HOME
        "HOTSEAT" -> Placement.DOCK
        null, "NONE" -> {
            // Home Up's DIY workspace uses its own cell layout without Samsung's ItemAncestor.
            var parent = view.parent
            while (parent is View && parent.javaClass.compatibleName() != DIY_CELL_LAYOUT) {
                parent = parent.parent
            }
            if (parent is View) Placement.DIY_HOME else null
        }
        else -> null
    }

    fun refresh() = seen.keys.filter(View::isAttachedToWindow).forEach(View::invalidate)

    private fun requestPreview(
        view: View,
        source: Any,
        original: BitmapDrawable,
        readPreview: (View, Any, BitmapDrawable) -> Bitmap?,
    ) {
        // Existing folder bitmaps can predate module startup. Ask the stock preview callback once
        // for that bitmap, one folder per frame, without changing the supplier's stored artwork.
        if (requested[source]?.get() === original) return
        if (pending.size >= 48) return
        requested[source] = WeakReference(original)
        pending.addLast(Pending(view, source, original))
        if (queued) return
        queued = true
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (failed || level() <= 0) pending.clear()
                val first = pending.removeFirstOrNull()
                val host = first?.view?.get()
                val model = first?.supplier?.get()
                val expected = first?.original?.get()
                if (host != null && model != null && expected != null && host.isShown) {
                    try {
                        if (synchronized(scenes) { scenes[model]?.original } !== expected) {
                            val bitmap = readPreview(host, model, expected)
                            if (bitmap != null && !bitmap.isRecycled && bitmap.width <= 384 && bitmap.height <= 384) {
                                synchronized(scenes) {
                                    if (scenes[model]?.original !== expected) scenes[model] = Scene(expected, null, bitmap)
                                }
                                host.invalidate()
                            }
                        }
                    } catch (error: Exception) {
                        HookEntry.log("GLASS_FOLDER_PREVIEW_UNAVAILABLE ${error.javaClass.simpleName}")
                    }
                } else if (model != null) {
                    requested.remove(model)
                }
                if (pending.isEmpty()) queued = false else Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    private fun disable(error: Exception) {
        if (failed) return
        failed = true
        HookEntry.log("GLASS_FOLDER_ICONS_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
    }

    private fun readContour(mask: Bitmap): Path? {
        val width = mask.width
        val height = mask.height
        if (mask.isRecycled || width !in 4..384 || height !in 4..384 || !mask.hasAlpha()) return null
        val pixels = IntArray(width * height)
        mask.getPixels(pixels, 0, width, 0, 0, width, height)
        val left = IntArray(height) { width }
        val right = IntArray(height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (pixels[y * width + x] ushr 24 < 128) continue
                left[y] = min(left[y], x)
                right[y] = x + 1
            }
        }
        val top = left.indexOfFirst { it < width }
        val bottom = left.indexOfLast { it < width }
        if (top < 0 || bottom <= top) return null
        return Path().apply {
            moveTo(left[top].toFloat() / width, top.toFloat() / height)
            for (y in top..bottom) {
                if (left[y] < width) lineTo(left[y].toFloat() / width, (y + 0.5f) / height)
            }
            lineTo(left[bottom].toFloat() / width, (bottom + 1f) / height)
            lineTo(right[bottom].toFloat() / width, (bottom + 1f) / height)
            for (y in bottom downTo top) {
                if (left[y] < width) lineTo(right[y].toFloat() / width, (y + 0.5f) / height)
            }
            lineTo(right[top].toFloat() / width, top.toFloat() / height)
            close()
        }
    }

    private fun defaultContour() = Path().apply {
        addRoundRect(0.02f, 0.02f, 0.98f, 0.98f, 0.27f, 0.27f, Path.Direction.CW)
    }

    private inner class Plate(
        view: View,
        private val scene: Scene,
        supplier: Any,
        private val contour: Path,
        private val stockOnly: (Any) -> Boolean,
    ) : BitmapDrawable(view.resources, scene.original.bitmap) {
        private val supplier = WeakReference(supplier)
        private val layer = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val iconBounds = RectF()
        private val clip = Path()
        private val contourTransform = Matrix()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            if (bounds.isEmpty) return
            contourTransform.setScale(bounds.width().toFloat(), bounds.height().toFloat())
            contourTransform.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
            contour.transform(contourTransform, clip)

            if (scene.icons.width == scene.original.bitmap.width && scene.icons.height == scene.original.bitmap.height) {
                // Matching launcher bitmaps already include the preview grid's stock margins.
                // Keep its native position for the first frame of the open animation.
                iconBounds.set(bounds)
                return
            }
            val width = bounds.width() * scene.icons.width.toFloat() / scene.original.bitmap.width
            val height = bounds.height() * scene.icons.height.toFloat() / scene.original.bitmap.height
            val scale = min(1f, min(bounds.width() / width, bounds.height() / height)) * PREVIEW_SCALE
            val horizontal = width * scale / 2f
            val vertical = height * scale / 2f
            iconBounds.set(bounds.exactCenterX() - horizontal, bounds.exactCenterY() - vertical,
                bounds.exactCenterX() + horizontal, bounds.exactCenterY() + vertical)
        }

        override fun draw(canvas: Canvas) {
            val host = folderDraw.get()
            val model = supplier.get()
            val strength = level()
            if (host == null || model == null || strength <= 0 || failed || !canvas.isHardwareAccelerated ||
                bounds.isEmpty || scene.background?.isRecycled == true || scene.icons.isRecycled) {
                super.draw(canvas)
                return
            }
            try {
                if (stockOnly(model)) {
                    super.draw(canvas)
                    return
                }
                val effective = (strength * alpha + 127) / 255
                val rect = bounds
                val night = dark()
                val corner = rect.width().coerceAtMost(rect.height()) * 0.26f
                val save = canvas.save()
                try {
                    canvas.clipPath(clip)
                    val background = scene.background
                    if (background != null) {
                        layer.alpha = (alpha * (1f - strength * 0.0068f)).toInt()
                        layer.colorFilter = colorFilter
                        canvas.drawBitmap(background, null, rect, layer)
                    } else {
                        layer.color = if (night) 0xff192634.toInt() else 0xffe7edf8.toInt()
                        layer.alpha = (alpha * 0.31f).toInt()
                        layer.colorFilter = null
                        canvas.drawPath(clip, layer)
                    }
                    material.draw(canvas, rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(),
                        corner, corner, effective, night, contour = contour)
                    layer.alpha = alpha
                    layer.colorFilter = colorFilter
                    canvas.drawBitmap(scene.icons, null, iconBounds, layer)
                } finally {
                    canvas.restoreToCount(save)
                }
                if (reported.add(host.placement)) {
                    HookEntry.log("GLASS_HOME_CLOSED_FOLDER_DRAWN placement=${host.placement.label}")
                    if (reported.size == 1) onDrawn()
                }
            } catch (error: Exception) {
                disable(error)
                super.draw(canvas)
            }
        }
    }

    private companion object {
        const val DIY_CELL_LAYOUT = "com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridCellLayout"
        const val PREVIEW_SCALE = 0.93f
    }
}
