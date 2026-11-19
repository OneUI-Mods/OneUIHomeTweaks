package dev.ryuya.oneuihometweaks

import android.app.Dialog
import android.content.Context
import android.content.res.Resources
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Handler
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexRuntimeCache
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executor
import kotlin.math.min

/** Draws only into Samsung's native background layers, below the volume controls. */
@RequiresApi(33)
internal class GlassVolumePanel(
    app: Context,
    loader: ClassLoader,
    main: Handler,
    worker: Executor,
    private val material: GlassMaterial,
    private val strength: () -> Int,
    private val dark: (View) -> Boolean,
    private val refreshUnlock: () -> Unit,
    private val onDrawn: () -> Unit,
) {
    private enum class Role { COMPACT, EFFECT, DUAL, EXPANDED }

    private val panels = WeakHashMap<Dialog, Panel>()
    private var active: Panel? = null
    private val drawn = mutableSetOf<Role>()
    private val refracted = mutableSetOf<Role>()
    private val reportedWindows = mutableSetOf<String>()
    private val density = app.resources.displayMetrics.density
    private val backdrop by lazy {
        GlassPanelBackdrop(main, worker, density, ::invalidatePanels,
            GlassPanelBackdrop.VOLUME_WINDOW_TYPE, live = true, useDisplayBounds = true)
    }
    private val nativeIds = AnchorIds.read(app.resources, app.packageName, "volume_panel", "volume_panel_dual_view_background")
    private var reduceTransparency = reducedTransparency(app)

    init {
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            for ((name, expanded) in listOf("standard.VolumePanelWindow" to false, "expand.VolumePanelExpandWindow" to true)) {
                val type = DexSymbols.findClass("com.android.systemui.volume.view.$name", false, loader)
                    .asSubclass(Dialog::class.java)
                val onStop = type.getDeclaredMethod("onStop")
                handles += watchStart(type, expanded, nativeIds)
                handles += ModernHook.aroundMethod(onStop) { chain ->
                    safely { stop(chain.thisObject as Dialog) }
                    chain.proceed()
                }
            }
            VolumeStarHooks.observeGlassPlugin { context, pluginLoader ->
                worker.execute { prepareSoundAssistant(context, pluginLoader, main) }
            }
            app.contentResolver.registerContentObserver(Settings.System.getUriFor(REDUCE_TRANSPARENCY), false,
                object : ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) {
                        reduceTransparency = reducedTransparency(app)
                        refresh()
                    }
                })
        } catch (error: Throwable) {
            handles.asReversed().forEach(ModernHook.Handle::unhook)
            throw error
        }
    }

    private data class AnchorIds(
        val compact: Int, val dual: Int, val expanded: Int, val stroke: Int, val effect: Int,
        val compactRadius: Float, val expandedRadius: Float,
    ) {
        companion object {
            fun read(resources: Resources, owner: String, prefix: String, dual: String): AnchorIds {
                fun id(name: String) = resources.getIdentifier(name, "id", owner).also {
                    check(it != 0) { "Missing volume view $name" }
                }
                fun radius(name: String) = resources.getIdentifier(name, "dimen", owner).let {
                    if (it != 0) resources.getDimension(it) else Float.NaN
                }
                return AnchorIds(id("volume_seekbar_background"), id(dual), id("${prefix}_expand_view_background"),
                    id("${prefix}_expand_view_background_stroke"), resources.getIdentifier("effect_render_view", "id", owner),
                    radius("volume_seekbar_radius"),
                    radius("${prefix}_expand_view_radius"))
            }
        }
    }

    private fun watchStart(type: Class<out Dialog>, expanded: Boolean, ids: AnchorIds): ModernHook.Handle {
        val method = type.getDeclaredMethod("onStart")
        ModernHook.deoptimize(method)
        return ModernHook.aroundMethod(method) { chain ->
            val result = chain.proceed()
            safely { show(chain.thisObject as Dialog, expanded, ids) }
            result
        }
    }

    private fun prepareSoundAssistant(context: Context, loader: ClassLoader, main: Handler) {
        runCatching {
            val resources = context.resources
            val ids = AnchorIds.read(resources, SOUND_ASSISTANT, "volume_star", "volume_star_dual_view")
            val expandedLayout = resources.getIdentifier("volume_star_expand_view", "layout", SOUND_ASSISTANT)
            val expandedRoot = resources.getIdentifier("volume_star_expand_view", "id", SOUND_ASSISTANT)
            check(expandedLayout != 0 && expandedRoot != 0)
            val windows = DexRuntimeCache.query(context.applicationInfo.sourceDir, "volumestar-glass-v2") { bridge ->
                val standard = bridge.findClass {
                    matcher {
                        superClass(Dialog::class.java.name)
                        addUsingString("VolumeStarStandardWindow", StringMatchType.Equals)
                    }
                }.single().name
                val expanded = bridge.findClass {
                    matcher {
                        superClass(Dialog::class.java.name)
                        addMethod {
                            name("<init>")
                            usingNumbers(expandedLayout, expandedRoot)
                        }
                    }
                }.single().name
                mapOf("standard" to standard, "expanded" to expanded)
            }
            val standard = Class.forName(requireNotNull(windows["standard"]), false, loader).asSubclass(Dialog::class.java)
            val expanded = Class.forName(requireNotNull(windows["expanded"]), false, loader).asSubclass(Dialog::class.java)
            check(standard != expanded)
            main.post {
                runCatching {
                    val standardHook = watchStart(standard, expanded = false, ids)
                    try {
                        watchStart(expanded, expanded = true, ids)
                    } catch (error: Throwable) {
                        standardHook.unhook()
                        throw error
                    }
                    if (ids.effect != 0) watchSoundAssistantEffect(loader)
                    HookEntry.log("GLASS_SOUND_ASSISTANT_READY")
                }.onFailure { HookEntry.log("GLASS_SOUND_ASSISTANT_UNAVAILABLE ${it.javaClass.simpleName}: ${it.message}") }
            }
        }.onFailure { HookEntry.log("GLASS_SOUND_ASSISTANT_UNAVAILABLE ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun watchSoundAssistantEffect(loader: ClassLoader) {
        runCatching {
            val type = Class.forName("com.samsung.systemui.volumestar.extension.variation.effectbar.row.EffectRenderView",
                false, loader).asSubclass(View::class.java)
            val onDraw = type.getDeclaredMethod("onDraw", Canvas::class.java)
            ModernHook.deoptimize(onDraw)
            ModernHook.aroundMethod(onDraw) { chain ->
                val result = chain.proceed()
                active?.drawEffect(chain.thisObject as View, chain.args[0] as Canvas)
                result
            }
        }.onFailure { HookEntry.log("GLASS_VOLUME_EFFECT_UNAVAILABLE ${it.javaClass.simpleName}") }
    }

    private fun show(dialog: Dialog, expanded: Boolean, ids: AnchorIds) {
        val window = dialog.window ?: return
        if (window.attributes.type != GlassPanelBackdrop.VOLUME_WINDOW_TYPE) return
        panels[dialog]?.let {
            it.update()
            return
        }
        val decor = window.decorView
        val owner = WeakReference(dialog)
        val panel = Panel(decor, expanded, ids) { owner.get()?.let(::stop) }
        panels[dialog] = panel
        active = panel
        // Dialog.onStart runs before the window is added. post() runs after it is attached.
        decor.post {
            if (panels[dialog] !== panel || !dialog.isShowing) return@post
            safely {
                refreshUnlock()
                val name = dialog.javaClass.name
                if (reportedWindows.add(name)) HookEntry.log("GLASS_VOLUME_WINDOW_ATTACHED $name")
                panel.start(decor)
                panel.update()
            }
        }
    }

    private fun stop(dialog: Dialog) {
        val panel = panels.remove(dialog) ?: return
        if (active === panel) {
            active = null
            backdrop.clear()
        }
        panel.stop()
    }

    fun refresh() {
        if (active != null && currentStrength() <= 0) backdrop.clear()
        panels.values.toList().forEach { panel -> safely { panel.update() } }
    }

    private fun invalidatePanels() = panels.values.forEach { it.invalidate() }

    private fun currentStrength() = if (reduceTransparency) 0 else strength()

    private fun reducedTransparency(app: Context) =
        Settings.System.getInt(app.contentResolver, REDUCE_TRANSPARENCY, 0) != 0

    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            HookEntry.log("GLASS_VOLUME_PANEL_ERROR ${error.javaClass.simpleName}")
        }
    }

    private inner class Panel(decor: View, private val expanded: Boolean, private val ids: AnchorIds, onDetach: () -> Unit) {
        private val decorView = WeakReference(decor)
        private var observer: ViewTreeObserver? = null
        private var plate: Plate? = null
        private val layout = ViewTreeObserver.OnGlobalLayoutListener { safely { update() } }
        private val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = onDetach()
        }

        fun start(decor: View) {
            observer = decor.viewTreeObserver.also { it.addOnGlobalLayoutListener(layout) }
            decor.addOnAttachStateChangeListener(attach)
        }

        fun update() {
            val decor = decorView.get() ?: return
            if (currentStrength() <= 0 || !decor.isAttachedToWindow || decor.display?.displayId != Display.DEFAULT_DISPLAY) {
                restore()
                if (active === this) backdrop.clear()
                return
            }
            val (anchor, role) = findAnchor(decor)
            if (anchor == null || !anchor.isShown) {
                restore()
                if (active === this) backdrop.clear()
                return
            }
            if (plate?.matches(anchor, role) != true) {
                restore()
                val stock = if (role == Role.EFFECT) null else anchor.background
                val radius = when (role) {
                    Role.COMPACT -> ids.compactRadius
                    Role.EFFECT -> Float.NaN
                    else -> ids.expandedRadius
                }
                val wrapper = Plate(anchor, stock, role, radius)
                plate = wrapper
                if (role != Role.EFFECT) {
                    anchor.background = wrapper
                    stock?.callback = wrapper
                }
            }
            if (active === this) backdrop.observe(anchor, true)
            invalidate()
        }

        private fun findAnchor(decor: View): Pair<View?, Role> {
            if (expanded) {
                val background = decor.findViewById<View>(ids.expanded)
                return background?.findViewById<View>(ids.stroke) to Role.EXPANDED
            }
            val dual = decor.findViewById<View>(ids.dual)
            if (dual?.isShown == true) return dual.findViewById<View>(ids.stroke) to Role.DUAL
            if (ids.effect != 0) {
                val effect = decor.findViewById<View>(ids.effect)
                if (effect?.isShown == true) return effect to Role.EFFECT
            }
            return decor.findViewById<View>(ids.compact) to Role.COMPACT
        }

        fun invalidate() = plate?.invalidatePlate()

        fun drawEffect(target: View, canvas: Canvas) {
            val effect = plate?.takeIf { it.matches(target, Role.EFFECT) } ?: return
            effect.setBounds(0, 0, target.width, target.height)
            effect.draw(canvas)
        }

        fun stop() {
            observer?.takeIf(ViewTreeObserver::isAlive)?.removeOnGlobalLayoutListener(layout)
            observer = null
            decorView.get()?.removeOnAttachStateChangeListener(attach)
            restore()
        }

        private fun restore() {
            plate?.restore()
            plate = null
        }
    }

    private inner class Plate(
        view: View, private val stock: Drawable?, private val role: Role, private val fallbackRadius: Float,
    ) : DrawableWrapper(stock) {
        private val view = WeakReference(view)
        private val radii = FloatArray(8)
        private val outline = Outline()
        private val visibleBounds = Rect()
        private val localToScreen = Matrix()
        private val screenToLocal = Matrix()

        fun matches(target: View, role: Role) = view.get() === target && this.role == role &&
            (role == Role.EFFECT || target.background === this)

        fun invalidatePlate() {
            if (role == Role.EFFECT) view.get()?.postInvalidateOnAnimation() else invalidateSelf()
        }

        fun restore() {
            view.get()?.takeIf { it.background === this }?.background = stock
        }

        override fun draw(canvas: Canvas) {
            val target = view.get()
            val power = currentStrength()
            if (target == null || power <= 0 || !canvas.isHardwareAccelerated || bounds.isEmpty) {
                super.draw(canvas)
                return
            }
            // Compact's background provides the native idle track. The wider layouts already
            // have a base blur below this stroke view, and use the glass rim here.
            if (role == Role.COMPACT) super.draw(canvas)
            outline.setEmpty()
            stock?.getOutline(outline)
            if (!outline.getRect(visibleBounds) || visibleBounds.isEmpty) visibleBounds.set(bounds)
            val halfMinorSide = min(visibleBounds.width(), visibleBounds.height()) / 2f
            val nativeRadius = outline.radius.takeIf { it >= 0f } ?: fallbackRadius
            val radius = if (nativeRadius.isFinite()) min(nativeRadius, halfMinorSide) else halfMinorSide
            radii.fill(radius)
            val night = dark(target)
            val left = visibleBounds.left.toFloat()
            val top = visibleBounds.top.toFloat()
            val right = visibleBounds.right.toFloat()
            val bottom = visibleBounds.bottom.toFloat()
            localToScreen.reset()
            target.transformMatrixToGlobal(localToScreen)
            val bent = localToScreen.invert(screenToLocal) && backdrop.draw(canvas, 0f, 0f,
                left, top, right, bottom, radii, power, night, material.tintFactor, power * 0.014f, screenToLocal)
            material.drawSurface(canvas, left, top, right, bottom, radii, power, night,
                fill = !bent, rim = true, profile = GlassMaterial.Profile.VOLUME)
            if (drawn.add(role)) HookEntry.log("GLASS_VOLUME_PANEL_DRAWN ${role.name}")
            if (bent && refracted.add(role)) HookEntry.log("GLASS_VOLUME_PANEL_REFRACTION ${role.name}")
            onDrawn()
        }
    }

    private companion object {
        const val SOUND_ASSISTANT = "com.samsung.android.soundassistant"
        const val REDUCE_TRANSPARENCY = "accessibility_reduce_transparency"
    }
}
