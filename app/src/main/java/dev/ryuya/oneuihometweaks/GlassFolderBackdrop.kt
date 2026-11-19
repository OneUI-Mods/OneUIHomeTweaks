package dev.ryuya.oneuihometweaks

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Styles the two surfaces Samsung hands off between during a folder transition. */
@RequiresApi(33)
internal class GlassFolderBackdrop(
    private val loader: ClassLoader,
    private val level: () -> Int,
    private val dark: () -> Boolean,
    private val material: GlassMaterial,
    private val onDrawn: () -> Unit,
) {
    private enum class Role { POPUP, FULL, OPENING, CLOSING }

    private class Motion(moving: View, settled: View?, val closedCorner: Float, opening: Boolean) {
        val moving = WeakReference(moving)
        val settled = WeakReference(settled)
        var openFraction = if (opening) 0f else 1f
    }

    private fun folderType(name: String) =
        DexSymbols.findClass("com.honeyspace.ui.honeypots.folder.presentation.open.$name", false, loader)

    private val popup = folderType("OpenPopupFolderContainer")
    private val full = folderType("OpenFullFolderContainer")
    private val folderTray = folderType("OpenFolderFRView")
    private val stroke = folderType("StrokeBackground")
    private val blur = folderType("BlurBackground")
    private val popupRadius = stroke.getDeclaredMethod("getRadius")
    private val fullTray = full.getMethod("getOpenFolderFRView")
    private val blurInfo = View::class.java.getDeclaredField("mBlurInfo").apply { isAccessible = true }
    private val getMode = blurInfo.type.getMethod("getBlurMode")
    private val getBitmap = blurInfo.type.getMethod("getCapturedBitmap")
    private val surfaces = WeakHashMap<View, Plate>()
    private val active = WeakHashMap<Any, Motion>()
    private val motionByView = WeakHashMap<View, Motion>()
    private val reported = mutableSetOf<Role>()
    private var reportedHandoff = false

    init {
        val steadyHooks = mutableListOf<ModernHook.Handle>()
        try {
            steadyHooks += ModernHook.hookAllConstructors(stroke, object : ModernHook.Callback() {
                override fun afterHookedMethod(param: ModernHook.Param) {
                    if (!param.hasThrowable()) safely("popup") { watchStroke(param.thisObject as View) }
                }
            })
            val applyCrop = blur.declaredMethods.single {
                it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(Rect::class.java, Bitmap::class.java))
            }
            steadyHooks += ModernHook.aroundMethod(applyCrop) { chain ->
                val result = chain.proceed()
                val parent = (chain.thisObject as View).parent as? ViewGroup
                if (parent != null) child(parent, stroke)?.let { surfaces[it]?.invalidateSelf() }
                result
            }
            // Both lifecycle methods can replace the stock tray background.
            for (callback in listOf("b", "f")) {
                val method = full.getDeclaredMethod(callback, Boolean::class.javaPrimitiveType)
                steadyHooks += ModernHook.aroundMethod(method) { chain ->
                    val result = chain.proceed()
                    safely("full") { styleFullFolder(chain.thisObject) }
                    result
                }
            }
            // The first full-folder background is supplied by the generated layout binding.
            val binding = DexSymbols.findClass("gb.t", false, loader)
            val bind = binding.getDeclaredMethod("executeBindings")
            val root = binding.getMethod("getRoot")
            ModernHook.deoptimize(bind)
            steadyHooks += ModernHook.aroundMethod(bind) { chain ->
                val result = chain.proceed()
                if (level() > 0) safely("full") {
                    val folder = root.invoke(chain.thisObject)
                    if (full.isInstance(folder)) styleFullFolder(requireNotNull(folder))
                }
                result
            }
        } catch (error: Throwable) {
            steadyHooks.asReversed().forEach { it.unhook() }
            throw error
        }
        safely("transition") { hookTransition(loader) }
    }

    fun find(view: View) = safely("attach") {
        when {
            popup.isInstance(view) && view is ViewGroup -> child(view, stroke)?.let(::watchStroke)
            full.isInstance(view) -> styleFullFolder(view)
        }
    }

    fun refresh() {
        surfaces.values.toList().forEach {
            if (level() <= 0) it.clearCrop()
            it.invalidateSelf()
        }
        if (level() > 0) surfaces.keys.toList().filter { it.isAttachedToWindow && stroke.isInstance(it) }
            .forEach { safely("popup") { styleStroke(it) } }
    }

    private fun hookTransition(loader: ClassLoader) {
        val contract = requireNotNull(FolderContracts.forSdk(android.os.Build.VERSION.SDK_INT))
        val animator = DexSymbols.findClass(requireNotNull(contract.animator), false, loader)
        val prepare = animator.getDeclaredMethod(requireNotNull(contract.prepare),
            Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val transform = animator.getDeclaredMethod(
            "c0", View::class.java, Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Point::class.java, PointF::class.java,
        )
        val cleanup = animator.getDeclaredMethod("t")
        // BZI9's animator keeps the backdrop ImageView in a separate field from its copied app icons.
        val image = animator.getDeclaredField("i").apply { isAccessible = true }
        val target = animator.getDeclaredField("e").apply { isAccessible = true }
        val info = animator.getDeclaredField("d").apply { isAccessible = true }
        val radius = info.type.getDeclaredField("e").apply { isAccessible = true }
        val icon = info.type.getDeclaredField("a").apply { isAccessible = true }
        check(ImageView::class.java.isAssignableFrom(image.type) && radius.type == Int::class.javaPrimitiveType)
        ModernHook.deoptimize(prepare)
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            handles += ModernHook.aroundMethod(prepare) { chain ->
                val result = chain.proceed()
                if (level() > 0) safely("transition") {
                    val owner = chain.thisObject
                    val view = image.get(owner) as? ImageView ?: return@safely
                    val stock = view.drawable
                    if (stock is Plate) return@safely
                    val nativeInfo = info.get(owner)
                    val originalIcon = icon.get(nativeInfo) as? View
                    val nativeRadius = radius.getInt(nativeInfo).toFloat()
                    val opening = chain.args[1] as Boolean
                    val settled = (target.get(owner) as? View)?.let(::backgroundHost)
                    val iconCorner = originalIcon?.let { minOf(it.width, it.height) * 0.26f } ?: 0f
                    endMotion(owner)
                    val motion = Motion(view, settled, iconCorner, opening)
                    active[owner] = motion
                    motionByView[view] = motion
                    if (settled != null) motionByView[settled] = motion
                    val plate = Plate(view, stock, nativeRadius, if (opening) Role.OPENING else Role.CLOSING)
                    surfaces[view] = plate
                    view.setImageDrawable(plate)
                    stock?.callback = plate
                    settled?.let { surfaces[it]?.invalidateSelf() }
                }
                result
            }
            ModernHook.deoptimize(transform)
            handles += ModernHook.aroundMethod(transform) { chain ->
                val result = chain.proceed()
                val motion = active[chain.thisObject] ?: return@aroundMethod result
                if (chain.args[0] !== motion.moving.get()) return@aroundMethod result
                val fraction = (chain.args[2] as Float).coerceIn(0f, 1f)
                val openFraction = if (chain.args[1] == true) fraction else 1f - fraction
                if (abs(motion.openFraction - openFraction) >= 0.001f) {
                    motion.openFraction = openFraction
                    invalidateMotion(motion)
                }
                result
            }
            handles += ModernHook.aroundMethod(cleanup) { chain ->
                val result = chain.proceed()
                endMotion(chain.thisObject)
                result
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach { it.unhook() }
            throw error
        }
    }

    private fun backgroundHost(target: View): View? = when {
        popup.isInstance(target) && target is ViewGroup -> child(target, stroke)
        folderTray.isInstance(target) && full.isInstance(target.parent) -> target
        else -> null
    }

    private fun invalidateMotion(motion: Motion) {
        motion.moving.get()?.let { surfaces[it]?.invalidateSelf() }
        motion.settled.get()?.let { surfaces[it]?.invalidateSelf() }
    }

    private fun endMotion(owner: Any) {
        val motion = active.remove(owner) ?: return
        motion.moving.get()?.let { motionByView.remove(it, motion) }
        motion.settled.get()?.let { motionByView.remove(it, motion) }
        invalidateMotion(motion)
    }

    private fun watchStroke(view: View) {
        if (view in surfaces) return
        val plate = Plate(view, null, 0f, Role.POPUP)
        surfaces[view] = plate
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = safely("popup") { styleStroke(view) }
            override fun onViewDetachedFromWindow(view: View) = plate.clearCrop()
        })
        if (view.isAttachedToWindow) styleStroke(view)
    }

    private fun styleStroke(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        if (!popup.isInstance(parent) || level() <= 0) return
        val plate = surfaces[view] ?: return
        plate.corner = popupRadius.invoke(view) as Float
        plate.blurView = WeakReference(child(parent, blur))
        if (view.background !== plate) {
            val stock = view.background ?: ColorDrawable(Color.TRANSPARENT)
            plate.setDrawable(stock)
            view.background = plate
            stock.callback = plate
        }
    }

    private fun styleFullFolder(folder: Any) {
        if (level() <= 0) return
        val tray = fullTray.invoke(folder) as? View ?: return
        val stock = tray.background
        if (stock is Plate) return
        val radiusId = tray.resources.getIdentifier("folder_bg_radius", "dimen", tray.context.packageName)
        val radius = if (radiusId != 0) tray.resources.getDimension(radiusId) else 24f * tray.resources.displayMetrics.density
        val plate = Plate(tray, stock, radius, Role.FULL)
        surfaces[tray] = plate
        tray.background = plate
        stock?.callback = plate
    }

    private fun child(parent: ViewGroup, type: Class<*>): View? {
        for (index in 0 until parent.childCount) {
            val view = parent.getChildAt(index)
            if (type.isInstance(view)) return view
        }
        return null
    }

    private fun safely(surface: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            HookEntry.log("GLASS_FOLDER_UNAVAILABLE $surface ${error.javaClass.simpleName}: ${error.message}")
        } catch (error: LinkageError) {
            HookEntry.log("GLASS_FOLDER_UNAVAILABLE $surface ${error.javaClass.simpleName}")
        }
    }

    private inner class Plate(view: View, stock: Drawable?, var corner: Float, private val role: Role) :
        DrawableWrapper(stock ?: ColorDrawable(Color.TRANSPARENT)) {
        private val host = WeakReference(view)
        private val density = view.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val matrix = Matrix()
        private var lens: GlassLens? = null
        private var source: BitmapShader? = null
        private var crop = WeakReference<Bitmap>(null)
        private val cropBounds = Rect()
        private val radii = FloatArray(8)
        private var drawFailed = false
        var blurView = WeakReference<View>(null)

        fun clearCrop() {
            crop.clear()
            source = null
            cropBounds.setEmpty()
        }

        @Deprecated("Required by Drawable")
        override fun getOpacity(): Int = if (level() > 0) PixelFormat.TRANSLUCENT else super.getOpacity()

        override fun draw(canvas: Canvas) {
            val view = host.get()
            val strength = level()
            if (view == null || view.display?.displayId != 0 || strength <= 0 ||
                !canvas.isHardwareAccelerated || bounds.isEmpty || drawFailed) {
                super.draw(canvas)
                return
            }
            try {
                drawGlass(canvas, view, strength)
                if (reported.add(role)) HookEntry.log("GLASS_FOLDER_SURFACE_DRAWN role=${role.name}")
                onDrawn()
            } catch (error: Exception) {
                drawFailed = true
                clearCrop()
                HookEntry.log("GLASS_FOLDER_DRAW_FAILED role=${role.name} ${error.javaClass.simpleName}")
                super.draw(canvas)
            }
        }

        private fun drawGlass(canvas: Canvas, view: View, strength: Int) {
            val rect = bounds
            val left = rect.left.toFloat()
            val top = rect.top.toFloat()
            val right = rect.right.toFloat()
            val bottom = rect.bottom.toFloat()
            val night = dark()
            val animated = role == Role.OPENING || role == Role.CLOSING
            val motion = motionByView[view]
            val settled = motion?.settled?.get()
            val fraction = motion?.openFraction ?: 1f
            // The native progress already includes the gesture or spring curve. Match the two
            // backgrounds on that clock and keep their combined coverage from jumping at teardown.
            val opacity = when {
                motion == null || settled == null -> 1f
                animated && !settled.isShown -> 1f
                animated -> 1f - fraction
                else -> fraction
            }
            if (opacity <= 0f) return
            val radius = if (animated && corner <= 0f) 0.24f * minOf(rect.width(), rect.height()) else corner
            val scaleX = abs(view.scaleX).coerceAtLeast(0.05f)
            val scaleY = abs(view.scaleY).coerceAtLeast(0.05f)
            val endCorner = settled?.let { surfaces[it]?.corner } ?: radius
            val xCorner: Float
            val yCorner: Float
            if (animated && motion != null && motion.closedCorner > 0f) {
                val screenCorner = motion.closedCorner + (endCorner - motion.closedCorner) * fraction
                xCorner = (screenCorner / scaleX).coerceIn(0f, rect.width() / 2f)
                yCorner = (screenCorner / scaleY).coerceIn(0f, rect.height() / 2f)
            } else {
                xCorner = radius
                yCorner = radius
            }

            if (role != Role.POPUP) {
                // Preserve the native artwork, including previews in large folders.
                val stockOpacity = ((255 - strength * 0.92f) * opacity).roundToInt().coerceIn(0, 255)
                drawStock(canvas, stockOpacity)
            }
            val shader = if (role == Role.POPUP) cropShader().also { if (it == null) clearCrop() } else null
            if (shader != null) {
                val glass = lens ?: GlassLens(density).also { lens = it }
                glass.draw(canvas, shader, left, top, right, bottom, radius, strength, night,
                    opacity = strength * 0.0078f * opacity, tintFactor = material.tintFactor)
            }
            paint.color = if (night) 0xff27394e.toInt() else 0xffbacde3.toInt()
            paint.alpha = (strength * 0.5f * opacity * material.tintFactor).roundToInt().coerceIn(0, 255)
            canvas.drawRoundRect(left, top, right, bottom, xCorner, yCorner, paint)
            for (index in radii.indices step 2) {
                radii[index] = xCorner
                radii[index + 1] = yCorner
            }
            material.drawSurface(canvas, left, top, right, bottom, radii, strength, night,
                fill = true, rim = true, opacity = opacity)
            if (!reportedHandoff && animated && settled != null && fraction in 0.1f..0.9f) {
                reportedHandoff = true
                HookEntry.log("GLASS_FOLDER_HANDOFF progress=$fraction")
            }
        }

        private fun drawStock(canvas: Canvas, opacity: Int) {
            val bitmap = drawable as? BitmapDrawable
            if (bitmap != null) {
                val previous = bitmap.paint.alpha
                bitmap.paint.alpha = previous * opacity / 255
                try {
                    bitmap.draw(canvas)
                } finally {
                    bitmap.paint.alpha = previous
                }
                return
            }
            val rect = bounds
            val save = canvas.saveLayerAlpha(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), opacity)
            try {
                super.draw(canvas)
            } finally {
                canvas.restoreToCount(save)
            }
        }

        private fun cropShader(): BitmapShader? {
            val backdrop = blurView.get()?.takeIf { it.visibility == View.VISIBLE } ?: return null
            val info = blurInfo.get(backdrop) ?: return null
            if (getMode.invoke(info) != 1) return null
            val bitmap = getBitmap.invoke(info) as? Bitmap ?: return null
            if (bitmap.isRecycled) return null
            if (crop.get() !== bitmap) {
                crop = WeakReference(bitmap)
                source = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                cropBounds.setEmpty()
            }
            if (cropBounds != bounds) {
                cropBounds.set(bounds)
                matrix.setScale(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
                matrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
                source?.setLocalMatrix(matrix)
            }
            return source
        }
    }
}
