package dev.ryuya.oneuihometweaks

import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Handler
import android.view.View
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executor
import kotlin.math.min

@RequiresApi(33)
internal class GlassNotificationPopups(
    loader: ClassLoader,
    private val main: Handler,
    worker: Executor,
    density: Float,
    private val appearance: () -> GlassAppearance,
    private val refreshUnlock: () -> Unit,
    private val dark: () -> Boolean,
    private val material: GlassMaterial,
    private val briefOnly: Boolean,
) {
    private val detailed = WeakHashMap<View, Unit>()
    private val detached = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = releaseDetailed(view)
    }
    private val detailedBackdrop by lazy { GlassPanelBackdrop(main, worker, density, ::refreshDetailed) }
    private val briefBackdrop by lazy {
        GlassPanelBackdrop(main, worker, density, ::refreshBrief, GlassPanelBackdrop.BRIEF_POPUP_WINDOW_TYPE)
    }
    private var shownBrief: BriefView? = null
    private var morph = WeakReference<View>(null)
    private var brief = WeakReference<BriefBackground>(null)
    private var briefDrawn = false
    private var briefRefracted = false

    init {
        if (briefOnly) {
            runCatching { installBrief(loader) }
                .onSuccess { HookEntry.log("GLASS_BRIEF_HOST_READY") }
                .onFailure { HookEntry.log("GLASS_BRIEF_POPUP_UNAVAILABLE ${it.javaClass.simpleName}: ${it.message}") }
        } else {
            runCatching { installDetailedLifecycle(loader) }.onFailure {
                HookEntry.log("GLASS_DETAILED_POPUP_LIFECYCLE_UNAVAILABLE ${it.javaClass.simpleName}")
            }
        }
    }

    fun detailedSurface(row: View): GlassPanelBackdrop {
        if (!detailed.containsKey(row) && row.isAttachedToWindow) {
            detailed[row] = Unit
            row.addOnAttachStateChangeListener(detached)
        }
        detailedBackdrop.observe(row, row.isAttachedToWindow)
        return detailedBackdrop
    }

    private fun releaseDetailed(row: View) {
        if (detailed.remove(row) != null) row.removeOnAttachStateChangeListener(detached)
        if (detailed.isEmpty()) detailedBackdrop.clear()
    }

    fun refresh() {
        if (briefOnly) {
            if (enabled()) {
                shownBrief?.let(::applyBrief)
                refreshBrief()
            } else {
                clearBrief()
            }
            return
        }
        if (!enabled()) {
            val active = detailed.keys.toList()
            active.forEach(::releaseDetailed)
            active.forEach(View::postInvalidateOnAnimation)
        } else {
            refreshDetailed()
        }
    }

    private fun enabled(): Boolean = appearance().let { it.notifications && it.strength > 0 }

    private fun refreshDetailed() {
        detailed.keys.forEach(View::postInvalidateOnAnimation)
    }

    private fun refreshBrief() {
        brief.get()?.invalidateSelf()
    }

    private fun installDetailedLifecycle(loader: ClassLoader) {
        val headsUpEntry = DexSymbols.findClass(
            "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl\$HeadsUpEntry", false, loader,
        )
        val setPinned = headsUpEntry.declaredMethods.single { it.name == "setRowPinnedStatus" && it.parameterCount == 1 }
        val entry = headsUpEntry.getField("mEntry")
        val row = entry.type.getField("row")
        val pinned = row.type.getMethod("isPinned")
        val leaving = row.type.getMethod("isHeadsUpAnimatingAway")
        val setLeaving = row.type.getMethod("setHeadsUpAnimatingAway", Boolean::class.javaPrimitiveType)

        fun finishIfIdle(view: View) {
            if (detailed.containsKey(view) && pinned.invoke(view) == false && leaving.invoke(view) == false) {
                releaseDetailed(view)
                view.postInvalidateOnAnimation()
            }
        }

        val handles = mutableListOf<ModernHook.Handle>()
        try {
            ModernHook.deoptimize(setPinned)
            handles += ModernHook.aroundMethod(setPinned) { chain ->
                val result = chain.proceed()
                val view = entry.get(chain.thisObject)?.let { row.get(it) } as? View
                if (view != null) {
                    if (pinned.invoke(view) == true) {
                        refreshUnlock()
                        if (enabled()) detailedSurface(view)
                    } else if (detailed.containsKey(view)) main.post { finishIfIdle(view) }
                }
                result
            }
            ModernHook.deoptimize(setLeaving)
            handles += ModernHook.aroundMethod(setLeaving) { chain ->
                val result = chain.proceed()
                finishIfIdle(chain.thisObject as View)
                result
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach(ModernHook.Handle::unhook)
            throw error
        }
    }

    private fun installBrief(loader: ClassLoader) {
        val type = Class.forName("com.android.systemui.edgelighting.effect.view.MorphView", false, loader)
        val rootLayout = type.getField("mToastRootLayout")
        val screenOn = type.getField("mIsScreenOn")
        val show = type.getMethod("show")
        val setAlpha = type.getMethod("setAlpha", Float::class.javaPrimitiveType)

        val handles = mutableListOf<ModernHook.Handle>()
        try {
            for (method in listOf(type.getMethod("initialize"), type.getMethod("reset"), type.getDeclaredMethod("onDetachedFromWindow"))) {
                ModernHook.deoptimize(method)
                handles += ModernHook.aroundMethod(method) { chain ->
                    if (shownBrief?.view?.get() === chain.thisObject) shownBrief = null
                    if (morph.get() === chain.thisObject) clearBrief()
                    chain.proceed()
                }
            }
            ModernHook.deoptimize(show)
            handles += ModernHook.aroundMethod(show) { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as View
                if (screenOn.getBoolean(view)) {
                    refreshUnlock()
                    val root = rootLayout.get(view) as? View
                    if (root != null) {
                        val shown = BriefView(WeakReference(view), WeakReference(root))
                        shownBrief = shown
                        if (enabled()) applyBrief(shown)
                    }
                }
                result
            }
            handles += ModernHook.aroundMethod(setAlpha) { chain ->
                val result = chain.proceed()
                if (morph.get() === chain.thisObject) refreshBrief()
                result
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach(ModernHook.Handle::unhook)
            throw error
        }
    }

    private class BriefView(val view: WeakReference<View>, val root: WeakReference<View>)

    private fun applyBrief(shown: BriefView) {
        val view = shown.view.get() ?: return
        val root = shown.root.get() ?: return
        if (morph.get() === view && root.background === brief.get()) return
        clearBrief()
        val stock = root.background ?: return
        val wrapper = BriefBackground(root, stock)
        morph = WeakReference(view)
        brief = WeakReference(wrapper)
        root.background = wrapper
        stock.callback = wrapper
        if (root.isAttachedToWindow) briefBackdrop.observe(root, true)
    }

    private fun clearBrief() {
        val previous = brief.get()
        if (morph.get() != null || previous != null) briefBackdrop.clear()
        morph.clear()
        previous?.restore()
        brief.clear()
    }

    private inner class BriefBackground(view: View, private val stock: Drawable) : DrawableWrapper(stock) {
        private val view = WeakReference(view)
        private val location = IntArray(2)
        private val radii = FloatArray(8)
        private val outline = Outline()

        fun restore() {
            view.get()?.takeIf { it.background === this }?.background = stock
        }

        override fun draw(canvas: Canvas) {
            super.draw(canvas)
            val target = view.get() ?: return
            val config = appearance()
            if (!config.notifications || config.strength <= 0 || !canvas.isHardwareAccelerated || bounds.isEmpty) return
            briefBackdrop.observe(target, target.isAttachedToWindow)
            target.getLocationOnScreen(location)
            stock.getOutline(outline)
            val cornerLimit = min(bounds.width(), bounds.height()) / 2f
            val radius = outline.radius.let { if (it > 0f) min(it, cornerLimit) else cornerLimit }
            radii.fill(radius)
            val night = dark()
            val left = bounds.left.toFloat()
            val top = bounds.top.toFloat()
            val right = bounds.right.toFloat()
            val bottom = bounds.bottom.toFloat()
            val refracted = briefBackdrop.draw(canvas, location[0].toFloat(), location[1].toFloat(),
                left, top, right, bottom, radii, config.strength, night, material.tintFactor, config.strength * 0.0105f)
            material.drawSurface(canvas, left, top, right, bottom, radii, config.strength, night,
                fill = !refracted, rim = true, profile = GlassMaterial.Profile.NOTIFICATION)
            if (!briefDrawn) {
                briefDrawn = true
                HookEntry.log("GLASS_BRIEF_POPUP_DRAWN")
            }
            if (refracted && !briefRefracted) {
                briefRefracted = true
                HookEntry.log("GLASS_BRIEF_POPUP_REFRACTION")
            }
        }
    }
}
