package dev.ryuya.oneuihometweaks

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** Adds light to the shade's own backdrop, below the header and notification stack. */
internal class GlassNotificationShade(
    loader: ClassLoader,
    private val level: () -> Int,
    private val tintFactor: () -> Float,
    private val dark: () -> Boolean,
    private val density: Float,
    private val onExpansion: (View, Boolean) -> Unit,
    private val onDrawn: () -> Unit,
) {
    private var panel = WeakReference<View>(null)
    private var controller = WeakReference<Any>(null)
    private data class EdgeKey(val height: Int, val night: Boolean)

    private var edgeKey: EdgeKey? = null
    private var edgeDepth = 0f
    private var reported = false
    private var reportedHandoff = false
    private val edgePaint = Paint()
    private val bodyPaint = Paint()

    init {
        fun type(name: String) = DexSymbols.findClass("com.android.systemui.$name", false, loader)
        val panelType = type("shade.NotificationPanelView").asSubclass(View::class.java)
        val controllerType = type("shade.NotificationPanelViewController")
        val viewField = controllerType.getField("mView")
        val barState = controllerType.getField("mBarState")
        val expansion = controllerType.getField("mExpandedFraction")
        val splitField = controllerType.getField("mPanelSplitHelper")
        val qsField = controllerType.getField("mQsController")
        val quickExpansion = qsField.type.getMethod("computeExpansionFraction")
        val splitHelper = splitField.type
        val splitEnabled = splitHelper.getField("enabled")
        val panelState = splitHelper.getField("currentState")
        val stateOnDown = splitHelper.getField("stateOnDown")
        val stateToChange = splitHelper.getField("stateToChange")
        val dragged = splitHelper.getField("draggedFraction")
        val updateSplit = splitHelper.getDeclaredMethod("updateState", Int::class.javaPrimitiveType)
        val updateSlide = splitHelper.getDeclaredMethod("setDraggedFraction", Float::class.javaPrimitiveType)
        val update = controllerType.getDeclaredMethod("updateExpansionAndVisibility")
        val draw = panelType.getDeclaredMethod("dispatchDraw", Canvas::class.java)
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            for (method in listOf(updateSplit, updateSlide)) {
                ModernHook.deoptimize(method)
                handles += ModernHook.aroundMethod(method) { chain ->
                    val result = chain.proceed()
                    if (level() > 0) refresh()
                    result
                }
            }
            ModernHook.deoptimize(update)
            handles += ModernHook.aroundMethod(update) { chain ->
                val result = chain.proceed()
                val view = viewField.get(chain.thisObject) as? View
                if (view?.display?.displayId == 0) {
                    if (panel.get() !== view) panel = WeakReference(view)
                    if (controller.get() !== chain.thisObject) controller = WeakReference(chain.thisObject)
                    onExpansion(view, barState.getInt(chain.thisObject) == 0 && expansion.getFloat(chain.thisObject) > 0f)
                    if (level() > 0) refresh()
                }
                result
            }
            ModernHook.deoptimize(draw)
            handles += ModernHook.aroundMethod(draw) { chain ->
                val view = chain.thisObject as View
                val strength = level()
                val owner = controller.get()
                val canvas = chain.args[0] as Canvas
                if (strength <= 0 || !canvas.isHardwareAccelerated || owner == null ||
                    panel.get() !== view || view.width <= 0 || view.height <= 0 || barState.getInt(owner) != 0) {
                    return@aroundMethod chain.proceed()
                }
                val shade = expansion.getFloat(owner).coerceIn(0f, 1f)
                val split = splitField.get(owner)
                val notificationShare = if (split != null && splitEnabled.getBoolean(split)) {
                    when (panelState.getInt(split)) {
                        NOTIFICATIONS -> 1f
                        SLIDING -> {
                            val progress = dragged.getFloat(split).coerceIn(0f, 1f)
                            when {
                                stateOnDown.getInt(split) == NOTIFICATIONS -> 1f - progress
                                stateToChange.getInt(split) == NOTIFICATIONS -> progress
                                else -> 0f
                            }
                        }
                        else -> 0f
                    }
                } else {
                    val quick = (quickExpansion.invoke(qsField.get(owner)) as Float).coerceIn(0f, 1f)
                    1f - quick
                }
                if (shade > 0f && notificationShare > 0f) {
                    drawBackdrop(canvas, view, strength, shade, notificationShare)
                    if (!reported) {
                        reported = true
                        HookEntry.log("GLASS_NOTIFICATION_SHADE_DRAWN")
                    }
                    if (!reportedHandoff && notificationShare < 1f && split != null &&
                        panelState.getInt(split) == SLIDING) {
                        reportedHandoff = true
                        HookEntry.log("GLASS_NOTIFICATION_SHADE_HANDOFF")
                    }
                    onDrawn()
                }
                chain.proceed()
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach { it.unhook() }
            throw error
        }
    }

    fun refresh() = panel.get()?.postInvalidateOnAnimation()

    private fun drawBackdrop(canvas: Canvas, view: View, strength: Int, expansion: Float, share: Float) {
        val night = dark()
        val key = EdgeKey(view.height, night)
        if (edgeKey != key) {
            edgeKey = key
            edgeDepth = (view.height * 0.21f).coerceAtMost(144f * density)
            edgePaint.shader = LinearGradient(
                0f, 0f, 0f, edgeDepth,
                if (night) intArrayOf(0x8c161a20.toInt(), 0x29161a20, 0x00161a20)
                else intArrayOf(0x9ef5f6f9.toInt(), 0x3af5f6f9, 0x00f5f6f9),
                floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP,
            )
            bodyPaint.color = if (night) 0xff181a20.toInt() else 0xfff2f4f8.toInt()
        }
        val intensity = share * strength / 100f
        val width = view.width.toFloat()
        bodyPaint.alpha = (28f * tintFactor() * expansion * intensity).roundToInt()
        canvas.drawRect(0f, 0f, width, view.height.toFloat(), bodyPaint)

        // CoverSheet brings in the edge effect over the first quarter of an unscaled pull.
        // Keep Samsung's native blur and apply this timing to the glass lighting only.
        edgePaint.alpha = (255f * (4f * expansion).coerceAtMost(1f) * intensity).roundToInt()
        canvas.drawRect(0f, 0f, width, edgeDepth, edgePaint)
    }

    private companion object {
        const val NOTIFICATIONS = 1
        const val SLIDING = 2
    }
}
