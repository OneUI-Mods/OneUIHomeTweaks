package dev.ryuya.oneuihometweaks

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import java.util.WeakHashMap

/** Follows the native card's measured bounds, grouped corners and scroll clipping. */
@RequiresApi(33)
internal class GlassNotificationCards(
    loader: ClassLoader,
    private val appearance: () -> GlassAppearance,
    private val dark: () -> Boolean,
    private val pointer: () -> GlassPointer?,
    private val material: GlassMaterial,
    private val backdrop: () -> GlassPanelBackdrop?,
    private val popups: GlassNotificationPopups,
    private val onDrawn: () -> Unit,
) {
    private val seen = WeakHashMap<View, Unit>()
    private val location = IntArray(2)
    private val softened = WeakHashMap<Drawable, Alpha>()
    private var reported = false
    private val reportedLayouts = mutableSetOf<String>()
    private val reportedPopupLayouts = mutableSetOf<String>()
    private data class Alpha(val native: Int, val applied: Int)

    init {
        val card = DexSymbols.findClass(
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView", false, loader,
        )
        val row = DexSymbols.findClass(
            "com.android.systemui.statusbar.notification.row.ActivatableNotificationView", false, loader,
        )
        val notification = DexSymbols.findClass(
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", false, loader,
        )
        val outline = requireNotNull(row.superclass)
        val drawChild = outline.getDeclaredMethod("drawChild", Canvas::class.java, View::class.java, Long::class.javaPrimitiveType)
        val clipPath = outline.getMethod("getClipPath", Boolean::class.javaPrimitiveType)
        val contentView = row.getMethod("getContentView")
        val showNoBackground = notification.getField("mShowNoBackground")
        val headsUpLeaving = notification.getMethod("isHeadsUpAnimatingAway")
        val onKeyguard = row.getField("mOnKeyguard")
        val mainBackground = row.getField("mBackgroundNormal")
        val original = card.getField("mBackground")
        val corners = card.getField("mCornerRadii")
        val pinned = card.getField("mIsPinned")
        val expanding = card.getField("mExpandAnimationRunning")
        val clipTop = card.getField("mClipTopAmount")
        val clipBottom = card.getField("mClipBottomAmount")
        val actualHeight = card.getMethod("getActualHeight")
        val shelf = runCatching {
            DexSymbols.findClass("com.android.systemui.statusbar.notification.shelf.NotificationShelfBackgroundView", false, loader)
        }.getOrNull()
        require(View::class.java.isAssignableFrom(card) && Drawable::class.java.isAssignableFrom(original.type))
        require(corners.type == FloatArray::class.java)
        val draw = card.getDeclaredMethod("onDraw", Canvas::class.java)
        ModernHook.deoptimize(draw)
        ModernHook.aroundMethod(draw) { chain ->
            val view = chain.thisObject as View
            if (shelf?.isInstance(view) == true) return@aroundMethod chain.proceed()
            seen[view] = Unit
            val config = appearance()
            val parent = view.parent
            val native = original.get(view) as? Drawable ?: return@aroundMethod chain.proceed()
            val canvas = chain.args[0] as Canvas
            val eligible = config.notifications && config.strength > 0 && canvas.isHardwareAccelerated &&
                row.isInstance(parent) && mainBackground.get(parent) === view && !onKeyguard.getBoolean(parent)
            if (!eligible) {
                restore(native)
                return@aroundMethod chain.proceed()
            }
            // Keep the native blur layer and notification text intact. Reduce only the fill drawable.
            val previous = softened[native]
            val originalAlpha = if (previous != null && native.alpha == previous.applied) previous.native else native.alpha
            val translucent = (originalAlpha * (1f - 0.52f * config.strength / 100f)).toInt().coerceIn(0, 255)
            if (translucent != native.alpha) native.alpha = translucent
            if (previous?.native != originalAlpha || previous.applied != translucent) softened[native] = Alpha(originalAlpha, translucent)
            val result = chain.proceed()
            val bounds = native.bounds
            val radii = corners.get(view) as FloatArray
            // Samsung already uses a low fill alpha for window blur. The rim follows the view's
            // opacity, and a suppressed stock background still suppresses the entire surface.
            val top = clipTop.getInt(view).coerceAtLeast(0)
            val bottom = (actualHeight.invoke(view) as Int) - clipBottom.getInt(view).coerceAtLeast(0)
            val isExpanding = expanding.getBoolean(view)
            if (radii.size < 8 || bounds.isEmpty || originalAlpha == 0 || (!isExpanding && top >= bottom)) {
                return@aroundMethod result
            }
            val save = canvas.save()
            try {
                if (isExpanding || canvas.clipRect(0, top, view.width, bottom)) {
                    view.getLocationOnScreen(location)
                    val light = pointer()?.let { touch ->
                        touch.onSurface((location[0] + bounds.left).toFloat(), (location[1] + bounds.top).toFloat(),
                            bounds.width().toFloat(), bounds.height().toFloat(), view.resources.displayMetrics.density)
                    }
                    val night = dark()
                    val left = bounds.left.toFloat()
                    val cardTop = bounds.top.toFloat()
                    val right = bounds.right.toFloat()
                    val cardBottom = bounds.bottom.toFloat()
                    material.drawSurface(
                        canvas, left, cardTop, right, cardBottom, radii, config.strength, night, fill = true, rim = true,
                        glint = light, opacity = originalAlpha / 255f,
                        profile = GlassMaterial.Profile.NOTIFICATION,
                    )
                    if (!reported) {
                        reported = true
                        HookEntry.log("GLASS_NOTIFICATION_CARD_DRAWN")
                    }
                    onDrawn()
                }
            } finally {
                canvas.restoreToCount(save)
            }
            result
        }

        // Composite the lens at the row's content boundary, above its stock backdrop and below text.
        ModernHook.deoptimize(drawChild)
        ModernHook.aroundMethod(drawChild) { chain ->
            val config = appearance()
            val owner = chain.thisObject as View
            val canvas = chain.args[0] as Canvas
            val child = chain.args[1] as View
            if (!config.notifications || config.strength <= 0 || !canvas.isHardwareAccelerated ||
                !notification.isInstance(owner) || onKeyguard.getBoolean(owner) ||
                child.visibility != View.VISIBLE || contentView.invoke(owner) !== child) {
                return@aroundMethod chain.proceed()
            }
            val background = mainBackground.get(owner) as? View ?: return@aroundMethod chain.proceed()
            val popup = pinned.getBoolean(background) || headsUpLeaving.invoke(owner) == true
            if (!popup && showNoBackground.getBoolean(owner)) return@aroundMethod chain.proceed()
            val layer = if (popup) popups.detailedSurface(owner) else backdrop()
            if (layer == null || !layer.isDrawReady()) return@aroundMethod chain.proceed()
            val native = original.get(background) as? Drawable ?: return@aroundMethod chain.proceed()
            val radii = corners.get(background) as FloatArray
            val bounds = native.bounds
            if (radii.size < 8 || bounds.isEmpty || native.alpha == 0) {
                return@aroundMethod chain.proceed()
            }

            owner.getLocationOnScreen(location)
            val left = background.x + bounds.left
            val top = background.y + bounds.top
            val right = background.x + bounds.right
            val bottom = background.y + bounds.bottom
            val night = dark()
            val save = canvas.save()
            try {
                val nativeClip = clipPath.invoke(owner, false) as? Path
                if (nativeClip != null) canvas.clipPath(nativeClip)
                val visible = (native.alpha / 24f).coerceAtMost(1f) * background.alpha
                if (layer.draw(canvas, location[0].toFloat(), location[1].toFloat(), left, top, right, bottom,
                        radii, config.strength, night, material.tintFactor, config.strength * 0.0115f * visible)) {
                    material.drawSurface(canvas, left, top, right, bottom, radii, config.strength, night,
                        fill = false, rim = true, opacity = visible * 0.58f)
                    val layoutName = child.javaClass.simpleName
                    val reported = if (popup) reportedPopupLayouts else reportedLayouts
                    if (reported.add(layoutName)) {
                        val kind = if (popup) "DETAILED_POPUP" else "NOTIFICATION_CARD"
                        HookEntry.log("GLASS_${kind}_REFRACTION $layoutName")
                    }
                }
            } finally {
                canvas.restoreToCount(save)
            }
            chain.proceed()
        }
    }

    fun refresh() {
        if (!appearance().notifications || appearance().strength == 0) softened.keys.toList().forEach(::restore)
        seen.keys.filter(View::isShown).forEach { background ->
            background.postInvalidateOnAnimation()
            (background.parent as? View)?.postInvalidateOnAnimation()
        }
    }

    private fun restore(drawable: Drawable) {
        val previous = softened.remove(drawable) ?: return
        if (drawable.alpha == previous.applied) drawable.alpha = previous.native
    }
}
