package dev.ryuya.oneuihometweaks

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.RelativeLayout
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/** Styles the dock around the native icon row without changing its layout or hit targets. */
@RequiresApi(33)
internal class CompactGlassDock(
    context: Context,
    loader: ClassLoader,
    private val main: Handler,
    private val worker: Executor,
    private val level: () -> Int,
    private val dark: () -> Boolean,
    private val material: GlassMaterial,
    private val onDrawn: () -> Unit,
) {
    private val iconView = DexSymbols.findClass("com.honeyspace.ui.common.iconview.IconViewImpl", false, loader)
    private val iconCenter = iconView.getMethod("getCenterPosition")
    private val iconStyle = iconView.getMethod("getItemStyle")
    private val itemSize = iconStyle.returnType.getMethod("getItemSize")
    private val holders = WeakHashMap<ViewGroup, Dock>()
    private val nativeBlur = runCatching { NativeBlur() }.getOrNull()
    private val wallpaperReader = runCatching { GlassDockWallpaper.Reader(context) }.onFailure {
        HookEntry.log("GLASS_HOME_DOCK_WALLPAPER_UNAVAILABLE ${it.javaClass.simpleName}")
    }.getOrNull()
    private var failed = false

    fun watch(view: ViewGroup) {
        if (view in holders) return
        val dock = Dock(view)
        holders[view] = dock
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = dock.start()
            override fun onViewDetachedFromWindow(view: View) = dock.stop()
        })
        if (view.isAttachedToWindow) dock.start()
    }

    fun refresh() = holders.values.toList().forEach { it.refresh() }

    fun wallpaperChanged() = holders.values.toList().forEach { it.wallpaperChanged() }

    fun clearBackdrops() = holders.values.toList().forEach { it.clearBackdrop() }

    private inner class Dock(view: ViewGroup) {
        private val host = WeakReference(view)
        private val density = view.resources.displayMetrics.density
        private val iconRow = Rect()
        private val plateBounds = Rect()
        private val hostLocation = IntArray(2)
        private val iconLocation = IntArray(2)
        private val rootLocation = IntArray(2)
        private var largestIcon = 0
        private var plateCorner = 0f
        private val listener = ViewTreeObserver.OnGlobalLayoutListener { update() }
        private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
            plate?.updateBackdrop()
        }
        private val visibilityListener = ViewTreeObserver.OnWindowVisibilityChangeListener { visibility ->
            if (visibility == View.VISIBLE) refresh()
            else clearBackdrop()
        }
        private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            plate?.alignBackdrop()
            true
        }
        private val retryLayout = Runnable { update(keepDuringLayout = false) }
        private val scrollListener = ViewTreeObserver.OnScrollChangedListener { plate?.wallpaperMoved() }
        private var observer: ViewTreeObserver? = null
        private var plate: Plate? = null

        fun start() {
            val view = host.get() ?: return
            val tree = view.viewTreeObserver
            if (observer !== tree && tree.isAlive) {
                removeListeners()
                tree.addOnGlobalLayoutListener(listener)
                tree.addOnWindowFocusChangeListener(focusListener)
                if (Build.VERSION.SDK_INT >= 34) tree.addOnWindowVisibilityChangeListener(visibilityListener)
                tree.addOnPreDrawListener(preDrawListener)
                tree.addOnScrollChangedListener(scrollListener)
                observer = tree
            }
            update()
        }

        fun stop() {
            removeListeners()
            restore()
        }

        private fun removeListeners() {
            observer?.takeIf { it.isAlive }?.run {
                removeOnGlobalLayoutListener(listener)
                removeOnWindowFocusChangeListener(focusListener)
                if (Build.VERSION.SDK_INT >= 34) removeOnWindowVisibilityChangeListener(visibilityListener)
                removeOnPreDrawListener(preDrawListener)
                removeOnScrollChangedListener(scrollListener)
            }
            observer = null
        }

        fun refresh() {
            update()
            plate?.updateBackdrop()
            plate?.invalidateSelf()
        }

        fun wallpaperChanged() = plate?.wallpaperMoved(wallpaperChanged = true)

        fun clearBackdrop() = plate?.clearBackdrop()

        fun update(keepDuringLayout: Boolean = true) {
            val view = host.get() ?: return
            if (level() <= 0 || failed || !view.isAttachedToWindow || view.display?.displayId != 0) {
                restore()
                return
            }
            // Home Up may provide its own background. Never replace it with a second plate.
            if (view.background !== plate) {
                restore()
                if (view.background != null) return
            }
            try {
                if (!measure(view)) {
                    if (keepDuringLayout && plate != null && view.isShown) {
                        // The icon row can be temporarily unavailable during a native transition.
                        if (!main.hasCallbacks(retryLayout)) main.postDelayed(retryLayout, 280L)
                    } else {
                        restore()
                    }
                    return
                }
                main.removeCallbacks(retryLayout)
                val current = plate ?: Plate(view).also {
                    plate = it
                    setBackground(view, it)
                }
                current.update(plateBounds, plateCorner, level())
            } catch (error: Exception) {
                failed = true
                restore()
                HookEntry.log("GLASS_DOCK_UNAVAILABLE ${error.javaClass.simpleName}")
            }
        }

        private fun measure(view: ViewGroup): Boolean {
            if (view.width <= 0 || view.height <= 0) return false
            iconRow.setEmpty()
            largestIcon = 0
            view.getLocationInWindow(hostLocation)
            for (index in 0 until view.childCount) measureIcon(view.getChildAt(index))
            if (iconRow.isEmpty) return false
            val insets = view.rootWindowInsets
            val nav = insets?.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
            val root = view.rootView
            root.getLocationInWindow(rootLocation)
            val hostLeft = hostLocation[0] - rootLocation[0]
            val hostTop = hostLocation[1] - rootLocation[1]
            val frameLeft = ((nav?.left ?: 0) - hostLeft).coerceAtLeast(0)
            val frameRight = (root.width - (nav?.right ?: 0) - hostLeft).coerceAtMost(view.width)
            val frameBottom = (root.height - (nav?.bottom ?: 0) - hostTop).coerceAtMost(view.height)
            if (frameLeft >= frameRight || frameBottom <= 0 || iconRow.left < frameLeft ||
                iconRow.right > frameRight || iconRow.top < 0 || iconRow.bottom > frameBottom) return false

            // iOS 27's 402 pt portrait profile uses a 17 pt platter edge and an 80 pt icon.
            // Its visible plate height is icon height + 2 × (35 pt grid inset − 17 pt edge + 8 pt icon inset).
            val scale = largestIcon / 80f
            val side = (17 * scale).roundToInt().coerceAtMost(
                minOf(iconRow.left - frameLeft, frameRight - iconRow.right),
            )
            val verticalPadding = 26 * scale
            val height = (iconRow.height() + 2 * verticalPadding).roundToInt().coerceAtMost(frameBottom)
            val top = (iconRow.exactCenterY() - height / 2f).roundToInt().coerceIn(0, frameBottom - height)
            plateBounds.set(frameLeft + side, top, frameRight - side, top + height)
            val displayCorner = maxOf(
                insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0,
                insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0,
            )
            val preferredCorner = maxOf(displayCorner - side.toFloat(), largestIcon * 0.26f + verticalPadding)
            plateCorner = (preferredCorner - 6 * scale).roundToInt()
                .coerceIn(0, minOf(plateBounds.width(), height) / 2).toFloat()
            return !plateBounds.isEmpty
        }

        private fun measureIcon(child: View) {
            if (child.visibility != View.VISIBLE || child.width <= 0 || child.height <= 0) return
            if (iconView.isInstance(child)) {
                val style = iconStyle.invoke(child) ?: return
                val size = itemSize.invoke(style) as Int
                val center = iconCenter.invoke(child) as? Point ?: return
                if (size <= 0 || size > 128 * density) return
                child.getLocationInWindow(iconLocation)
                val left = iconLocation[0] - hostLocation[0] + center.x - size / 2
                val top = iconLocation[1] - hostLocation[1] + center.y - size / 2
                iconRow.union(left, top, left + size, top + size)
                largestIcon = maxOf(largestIcon, size)
                return
            }
            if (child is ViewGroup) for (index in 0 until child.childCount) measureIcon(child.getChildAt(index))
        }

        private fun restore() {
            main.removeCallbacks(retryLayout)
            val current = plate ?: return
            plate = null
            current.close()
            val view = host.get() ?: return
            if (view.background === current) setBackground(view, null)
        }

        private fun setBackground(view: ViewGroup, background: Drawable?) {
            val start = view.paddingStart
            val top = view.paddingTop
            val end = view.paddingEnd
            val bottom = view.paddingBottom
            view.background = background
            if (start != view.paddingStart || top != view.paddingTop || end != view.paddingEnd || bottom != view.paddingBottom) {
                view.setPaddingRelative(start, top, end, bottom)
            }
        }
    }

    private inner class Plate(view: View) : Drawable(), Drawable.Callback {
        private val view = WeakReference(view)
        private val rect = Rect()
        private val density = view.resources.displayMetrics.density
        private val blur = runCatching { nativeBlur?.create(view) }.getOrNull()?.apply { callback = this@Plate }
        private var lens = wallpaperReader?.let { runCatching { GlassLens(density) }.getOrNull() }
        private val wallpaper = wallpaperReader?.takeIf { lens != null }?.let {
            GlassDockWallpaper(view, main, worker, it, ::backdropChanged)
        }
        private val stream = (view as? RelativeLayout)?.let {
            GlassDockStream(it, main, material, ::streamChanged)
        }
        private var strength = -1
        private var corner = 0f
        private var reported = false
        private var lensReported = false

        fun update(bounds: Rect, corner: Float, strength: Int) {
            if (rect == bounds && this.corner == corner && this.strength == strength) return
            val moved = rect != bounds
            rect.set(bounds)
            this.corner = corner
            this.strength = strength
            if (blur != null) {
                blur.bounds = rect
                nativeBlur?.configure(blur, (density * (3.0f + strength * 0.12f)).roundToInt(), corner)
            }
            if (moved || stream != null) updateBackdrop()
            invalidateSelf()
        }

        fun updateBackdrop() {
            stream?.update(rect, corner, strength, dark())
            if (stream?.supported != true) wallpaper?.update(rect)
            backdropChanged()
        }

        fun alignBackdrop() = stream?.align()

        fun wallpaperMoved(wallpaperChanged: Boolean = false) {
            if (wallpaperChanged) {
                stream?.close()
                updateBackdrop()
            }
            if (stream?.supported != true) wallpaper?.dirty(wallpaperChanged)
        }

        fun clearBackdrop() {
            stream?.close()
            wallpaper?.clear()
        }

        private fun streamChanged() {
            if (stream?.supported == true) wallpaper?.clear()
            else wallpaper?.update(rect)
            backdropChanged()
        }

        private fun backdropChanged() {
            val hasSnapshot = wallpaper?.hasTexture == true && lens != null
            if (!hasSnapshot) lens?.clearBackdrop()
            blur?.setVisible(stream?.supported != true && !hasSnapshot, false)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val host = view.get() ?: return
            if (rect.isEmpty || level() <= 0) return
            val night = dark()
            if (stream?.ownsPlate != true) {
                // On return from Recents, the clear glass face is ready before its live edge.
                if (stream?.supported != true && canvas.isHardwareAccelerated && !drawLens(canvas, host, night)) {
                    blur?.draw(canvas)
                }
                material.draw(
                    canvas, rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(),
                    corner, corner, strength, night,
                )
            }
            if (!reported) {
                reported = true
                HookEntry.log("GLASS_HOME_DOCK_BOUNDS ${rect.width()}x${rect.height()} radius=$corner")
                onDrawn()
            }
        }

        private fun drawLens(canvas: Canvas, host: View, night: Boolean): Boolean {
            val optical = lens ?: return false
            val backdrop = wallpaper?.shaderFor(host) ?: return false
            try {
                optical.draw(
                    canvas, backdrop, rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(),
                    rect.bottom.toFloat(), corner, strength, night,
                    opacity = (strength / 45f).coerceAtMost(1f), tintFactor = material.tintFactor, dock = true,
                )
            } catch (error: RuntimeException) {
                lens = null
                wallpaper.clear()
                HookEntry.log("GLASS_HOME_DOCK_LENS_UNAVAILABLE ${error.javaClass.simpleName}")
                return false
            }
            if (!lensReported) {
                lensReported = true
                HookEntry.log("GLASS_HOME_DOCK_LENS_DRAWN")
            }
            return true
        }

        fun close() {
            clearBackdrop()
            blur?.setVisible(false, false)
            blur?.callback = null
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Required by Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun invalidateDrawable(who: Drawable) = invalidateSelf()
        override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) = scheduleSelf(what, time)
        override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
    }

    private class NativeBlur {
        private val root = View::class.java.getMethod("getViewRootImpl")
        private val create = root.returnType.getMethod("createBackgroundBlurDrawable")
        private val radius = create.returnType.getMethod("setBlurRadius", Int::class.javaPrimitiveType)
        private val corners = create.returnType.getMethod("setCornerRadius", Float::class.javaPrimitiveType)

        fun create(view: View): Drawable? = root.invoke(view)?.let { create.invoke(it) as? Drawable }

        fun configure(drawable: Drawable, blur: Int, corner: Float) {
            radius.invoke(drawable, blur)
            corners.invoke(drawable, corner)
        }
    }
}
