package dev.ryuya.oneuihometweaks

import android.animation.AnimatorSet
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.reflect.Field
import kotlin.math.abs

/** Computes the stock single-window transform on an isolated copy of its input and output. */
internal class LauncherWindowGeometry(private val player: Class<*>, input: Class<*>) {
    private val outputField = player.field("outputData")
    private val output = outputField.type
    private val params = player.field("appTransitionParams")
    private val tracking = player.field("trackingData")
    private val isWidget = player.field("isWidget")
    private val getScreenSize = player.compatibleDeclaredMethod("getScreenSize")
    private val getRelayer = player.compatibleDeclaredMethod("getSpringRelayer")
    private val offsetX = tracking.type.compatibleDeclaredMethod("getOffsetX")
    private val offsetY = tracking.type.compatibleDeclaredMethod("getOffsetY")
    private val targetScale = tracking.type.compatibleDeclaredMethod("getTargetScale")

    // Constructor order in the supported build. No lifecycle callbacks are carried to the replay.
    private val inputFields = input.fieldsInOrder(
        "animatorSet", "startRect", "endRect", "cropRect", "startCornerRadius", "endCornerRadius", "isForward",
        "startWindowAlpha", "fgAlpha", "needTopWindowOnly", "isTranslucent", "isMultiTargets", "catchRunnable",
        "animSetEndRunnable", "startRotation", "endRotation", "progressCallback", "startFromLock", "startFromExtraDisplay",
    )
    private val inputConstructor = input.getDeclaredConstructor(*inputFields.map(Field::getType).toTypedArray())
    private val isMultiTarget = input.field("isMultiTargets")
    private val translucent = input.field("isTranslucent")

    private val outputFields = output.fieldsInOrder(
        "x", "width", "y", "height", "scale", "containerScale", "bgWidth", "bgHeight", "cropRect", "contentsInsets",
        "positionProgress", "containerAlpha", "fgAlpha", "fgScaleOffset", "bgAlpha", "radius", "outlineOffset",
        "radiusProgress", "currentRectF", "windowAlpha", "fvRealRect", "progress", "rotation", "shadowRadius", "headerDiff",
    )
    private val outputConstructor = output.getDeclaredConstructor(*outputFields.map(Field::getType).toTypedArray())
    private val cx = output.field("x")
    private val cy = output.field("y")
    private val crop = output.field("cropRect")
    private val scale = output.field("scale")
    private val alpha = output.field("windowAlpha")
    private val radius = output.field("radius")
    private val shadow = output.field("shadowRadius")
    private val foregroundAlpha = output.field("fgAlpha")
    private val foregroundScale = output.field("fgScaleOffset")
    private val groupAlpha = output.field("containerAlpha")
    private val groupScale = output.field("containerScale")
    private val currentBounds = output.field("currentRectF")
    private val backgroundWidth = output.field("bgWidth")
    private val backgroundHeight = output.field("bgHeight")
    private val backgroundAlpha = output.field("bgAlpha")
    private val outlineOffset = output.field("outlineOffset")
    private val rotation = output.field("rotation")
    private val progress = output.field("progress")

    fun snapshot(owner: Any, stockInput: Any): Snapshot? {
        if (isWidget.getBoolean(owner) || isMultiTarget.getBoolean(stockInput) || translucent.getBoolean(stockInput)) return null
        val screen = (getScreenSize.invoke(owner) as IntArray).copyOf()
        if (screen.size != 2 || screen.any { it <= 0 }) return null
        val liveTracking = readTracking(owner) ?: return null
        val outputData = outputField.get(owner) ?: return null
        val transitionParams = params.get(owner) ?: return null
        val inputs = inputFields.map { field ->
            when (field.name) {
                "animatorSet", "catchRunnable", "animSetEndRunnable", "progressCallback" -> null
                else -> copyValue(field.get(stockInput))
            }
        }.toTypedArray()
        return Snapshot(transitionParams, screen, inputs, outputFields.map { copyValue(it.get(outputData)) }.toTypedArray(),
            liveTracking, getRelayer.invoke(owner) != null)
    }

    fun readTracking(owner: Any, previous: Tracking? = null): Tracking? {
        val live = tracking.get(owner) ?: return null
        val x = offsetX.invoke(live) as Int
        val y = offsetY.invoke(live) as Int
        val scale = targetScale.invoke(live) as Float
        if (!scale.isFinite()) return null
        return previous?.takeIf { it.x == x && it.y == y && it.scale == scale } ?: Tracking(x, y, scale)
    }

    fun copyForAnimation(snapshot: Snapshot, animatorSet: AnimatorSet = AnimatorSet()): AnimationData {
        val inputs = snapshot.inputs.map(::copyValue).toTypedArray()
        inputs[0] = animatorSet
        val copiedInput = inputConstructor.newInstance(*inputs)
        val copiedOutput = outputConstructor.newInstance(*snapshot.outputs.map(::copyValue).toTypedArray())
        return AnimationData(copiedInput, copiedOutput, snapshot.transitionParams, snapshot.screen.copyOf())
    }

    fun springCalculator(loader: ClassLoader) = SpringCalculator(loader)
    fun openingReplay(loader: ClassLoader) = LauncherOpenValueReplay(loader, this, inputConstructor.declaringClass, output, params.type)

    fun readStock(owner: Any): Frame? = outputField.get(owner)?.let(::readOutput)

    fun readStockOpeningIcon(owner: Any): LauncherOpeningIconProjection.State? =
        outputField.get(owner)?.let(::readOpeningIcon)

    fun readOpeningIcon(data: Any): LauncherOpeningIconProjection.State = LauncherOpeningIconProjection.State(
        LauncherIconPlaneProjection.Frame(RectF(currentBounds.get(data) as RectF), groupScale.getFloat(data),
            foregroundScale.getFloat(data), backgroundWidth.getInt(data), backgroundHeight.getInt(data),
            foregroundAlpha.getFloat(data)),
        groupAlpha.getFloat(data), backgroundAlpha.getFloat(data), outlineOffset.getInt(data),
        scale.getFloat(data), radius.getFloat(data), rotation.getFloat(data),
    )

    fun readOutput(data: Any): Frame {
        val rect = crop.get(data) as Rect
        val ratio = scale.getFloat(data)
        return Frame(cx.getFloat(data) - ratio * rect.left, cy.getFloat(data) - ratio * rect.top,
            Rect(rect), ratio, alpha.getFloat(data), radius.getFloat(data), shadow.getFloat(data),
            foregroundAlpha.getFloat(data), foregroundScale.getFloat(data), groupAlpha.getFloat(data),
            groupScale.getFloat(data), progress.getFloat(data))
    }

    private fun copyValue(value: Any?): Any? = when (value) {
        is RectF -> RectF(value)
        is Rect -> Rect(value)
        else -> value
    }

    private fun Class<*>.field(name: String) = compatibleDeclaredField(name).apply { isAccessible = true }
    private fun Class<*>.fieldsInOrder(vararg names: String) = names.map { field(it) }

    internal class Snapshot(
        val transitionParams: Any,
        val screen: IntArray,
        val inputs: Array<Any?>,
        val outputs: Array<Any?>,
        val tracking: Tracking,
        val hasRelayer: Boolean,
    )

    internal data class Tracking(val x: Int, val y: Int, val scale: Float)

    internal class AnimationData(val input: Any, val output: Any, val params: Any, val screen: IntArray)

    internal class SpringSeed(val insets: RectF, val radiusDiff: Float)

    internal inner class SpringCalculator(loader: ClassLoader) {
        private val type = DexSymbols.findClass("com.honeyspace.transition.anim.floating.calculator.SpringCalculator", loader)
        private val constructor = type.getDeclaredConstructor(
            Context::class.java, params.type, inputConstructor.declaringClass, output, IntArray::class.java, Boolean::class.javaPrimitiveType,
        )
        private val calculate = type.compatibleDeclaredMethod(
            "calculate", RectF::class.java, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
        )
        private val seedFields = runCatching { SpringSeedFields(type) }.getOrNull()

        fun captureStockSeed(loader: ClassLoader): (Any, Any) -> SpringSeed? {
            checkNotNull(seedFields)
            val spring = player.field("springAnimPlayer")
            val animation = spring.type.field("springAnimator")
            val runner = spring.type.field("springRunner")
            val runnerType = DexSymbols.findClass(spring.type.name + "\$SpringAnimRunner", loader)
            val calculator = runnerType.field("springCalculator")
            check(calculator.type === type)
            return capture@{ owner, expectedAnimation ->
                val stockSpring = spring.get(owner) ?: return@capture null
                if (animation.get(stockSpring) !== expectedAnimation) return@capture null
                val stockRunner = runner.get(stockSpring) ?: return@capture null
                if (stockRunner.javaClass !== runnerType) return@capture null
                calculator.get(stockRunner)?.let(::readSeed)
            }
        }

        internal fun readSeed(calculator: Any): SpringSeed? = seedFields?.read(calculator)

        fun prepare(context: Context, snapshot: Snapshot): Calculator {
            val data = copyForAnimation(snapshot)
            val stock = constructor.newInstance(context, data.params, data.input, data.output, data.screen, false)
            return Calculator(stock, data.output, snapshot, calculate, seedFields?.let { fields ->
                { seed: SpringSeed -> fields.install(stock, seed) }
            })
        }
    }

    private inner class SpringSeedFields(type: Class<*>) {
        private val insets = type.field("startContentsInset")
        private val radius = type.field("radiusDiff\$delegate")
        private val initialized = radius.type.getMethod("isInitialized")
        private val value = radius.type.getMethod("getValue")
        private val initializedLazy = Class.forName("kotlin.InitializedLazyImpl", false, radius.type.classLoader)
            .getDeclaredConstructor(Any::class.java).apply { isAccessible = true }

        fun read(calculator: Any): SpringSeed? {
            val stockInsets = insets.get(calculator) as? RectF ?: return null
            val stockRadius = radius.get(calculator)
            if (initialized.invoke(stockRadius) != true) return null
            val difference = value.invoke(stockRadius) as? Float ?: return null
            return SpringSeed(RectF(stockInsets), difference)
        }

        fun install(calculator: Any, seed: SpringSeed) {
            // The model owns this calculator. Never write into the active stock calculator or reuse its Lazy instance.
            insets.set(calculator, RectF(seed.insets))
            radius.set(calculator, initializedLazy.newInstance(seed.radiusDiff))
        }
    }

    internal inner class Calculator(
        private val stock: Any,
        private val outputData: Any,
        private val snapshot: Snapshot,
        private val calculate: java.lang.reflect.Method,
        private val installSeed: ((SpringSeed) -> Unit)?,
    ) {
        private val rect = RectF()
        private var tracking = snapshot.tracking

        fun useStockSeed(seed: SpringSeed) = checkNotNull(installSeed)(seed)

        fun useTracking(live: Tracking): Boolean {
            if (tracking == live) return false
            tracking = live
            return true
        }

        fun update(bounds: RectF, progress: Float): Frame {
            val scaleAtProgress = 1f - ((1f - tracking.scale) * progress)
            rect.set(bounds)
            rect.offset(tracking.x * progress, tracking.y * progress)
            rect.right = rect.left + rect.width() * scaleAtProgress
            rect.bottom = rect.top + rect.height() * scaleAtProgress
            calculate.invoke(stock, rect, progress, -1f, 1f)
            return readOutput(outputData)
        }

        fun updateSurfaces(bounds: RectF, progress: Float, icon: LauncherIconPlaneProjection): SurfaceFrame? {
            val window = update(bounds, progress)
            return projectSurfaces(window, icon)
        }

        fun projectSurfaces(window: Frame, icon: LauncherIconPlaneProjection): SurfaceFrame? {
            val iconBounds = currentBounds.get(outputData) as RectF
            val position = icon.project(LauncherIconPlaneProjection.Frame(iconBounds, window.groupScale,
                window.foregroundScale, backgroundWidth.getInt(outputData), backgroundHeight.getInt(outputData),
                window.foregroundAlpha * window.groupAlpha)) ?: return null
            return SurfaceFrame(window, position)
        }

    }

    internal data class SurfaceFrame(val window: Frame, val icon: LauncherIconPlaneProjection.Position) {
        fun matches(other: SurfaceFrame): Boolean {
            val target = other.icon
            return window.matches(other.window) &&
                abs(icon.x - target.x) <= 0.02f && abs(icon.y - target.y) <= 0.02f &&
                abs(icon.scaleX - target.scaleX) <= 0.00001f && abs(icon.scaleY - target.scaleY) <= 0.00001f &&
                abs(icon.alpha - target.alpha) <= 0.000001f
        }
    }

    internal data class Frame(
        val x: Float,
        val y: Float,
        val crop: Rect,
        val scale: Float,
        val alpha: Float,
        val radius: Float,
        val shadowRadius: Float,
        val foregroundAlpha: Float,
        val foregroundScale: Float,
        val groupAlpha: Float,
        val groupScale: Float,
        val progress: Float,
    ) {
        fun matches(other: Frame): Boolean = crop == other.crop &&
            abs(x - other.x) <= 0.02f && abs(y - other.y) <= 0.02f &&
            abs(scale - other.scale) <= 0.00001f && abs(alpha - other.alpha) <= 0.000001f &&
            abs(radius - other.radius) <= 0.02f && abs(progress - other.progress) <= 0.000001f
    }

}
