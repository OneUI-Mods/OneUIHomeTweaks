package dev.ryuya.oneuihometweaks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.reflect.Proxy

/** Runs the launcher's own spring on independent geometry, without referencing any View or task. */
internal class LauncherSpringReplay(loader: ClassLoader) {
    val animationType = DexSymbols.findClass("com.honeyspace.transition.anim.RectFSpringAnim", loader)
    private val configType = DexSymbols.findClass("${animationType.name}\$SpringConfig", loader)
    private val emptyConfigType = DexSymbols.findClass("${animationType.name}\$ExtraDisplaySpringConfig", loader)
    private val updateType = DexSymbols.findClass("${animationType.name}\$OnUpdateListener", loader)
    private val newAnimation = animationType.getDeclaredConstructor(configType)
    private val newConfig = emptyConfigType.getDeclaredConstructor(
        Context::class.java, RectF::class.java, RectF::class.java, Int::class.javaPrimitiveType,
    )
    private val startBounds = animationType.compatibleDeclaredField("startRect").apply { isAccessible = true }
    private val targetBounds = animationType.compatibleDeclaredField("targetRect").apply { isAccessible = true }
    private val height = animationType.compatibleDeclaredField("displayHeight").apply { isAccessible = true }
    private val fields = listOf(
        "tracking", "minVisChange", "maxVelocityPxPerS", "stiffnessX", "stiffnessY", "rectStiffness",
        "dampingX", "dampingY", "rectDamping", "maxTopRatio", "frictionXY",
        "scaleInterpolatorX1", "scaleInterpolatorY1", "scaleInterpolatorX2", "scaleInterpolatorY2",
    ).map { name ->
        val read = animationType.compatibleDeclaredField(name).apply { isAccessible = true }
        val write = configType.compatibleDeclaredMethod("set${name.replaceFirstChar(Char::uppercaseChar)}", read.type)
        read to write
    }
    val startCallback = animationType.compatibleDeclaredMethod("start", Context::class.java, PointF::class.java)
    private val cancel = animationType.compatibleDeclaredMethod("cancel")
    private val addUpdate = animationType.compatibleDeclaredMethod("addOnUpdateListener", updateType)
    private val addAnimator = animationType.compatibleDeclaredMethod("addAnimatorListener", Animator.AnimatorListener::class.java)
    private val updateListeners = animationType.compatibleDeclaredField("onUpdateListeners").apply { isAccessible = true }
    private val animatorListeners = animationType.compatibleDeclaredField("animatorListeners").apply { isAccessible = true }
    private val horizontalState = runCatching { LauncherHorizontalSpringState(animationType) }.getOrNull()

    fun captureHorizontal(animation: Any): LauncherHorizontalSpringState.Snapshot? = horizontalState?.capture(animation)

    fun snapshot(stock: Any, context: Context, velocity: PointF): Snapshot? {
        val start = RectF(startBounds.get(stock) as RectF)
        val target = RectF(targetBounds.get(stock) as RectF)
        val displayHeight = height.getInt(stock)
        if (!start.isFiniteAndNonEmpty() || !target.isFiniteAndNonEmpty() || displayHeight <= 0 ||
            !velocity.x.isFinite() || !velocity.y.isFinite()) return null
        return Snapshot(context, PointF(velocity.x, velocity.y), start, target, displayHeight, fields.map { it.first.get(stock) })
    }

    private fun RectF.isFiniteAndNonEmpty() = !isEmpty && left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

    fun prepare(snapshot: Snapshot, onFrame: (RectF, Float) -> Unit, onFinished: () -> Unit): Motion {
        val config = newConfig.newInstance(snapshot.context, RectF(snapshot.start), RectF(snapshot.target), snapshot.displayHeight)
        // The constructor for secondary displays has no Home Up lookup. All tuning values come from the live spring.
        fields.forEachIndexed { index, (_, set) -> set.invoke(config, snapshot.values[index]) }
        val detached = newAnimation.newInstance(config)
        observe(detached, onFrame, onFinished)
        return Motion(detached, snapshot.context, snapshot.velocity)
    }

    fun observe(
        animation: Any,
        onFrame: (RectF, Float) -> Unit,
        onFinished: () -> Unit,
        beforeStock: Boolean = false,
    ): Observation {
        val updates = Proxy.newProxyInstance(updateType.classLoader, arrayOf(updateType)) { proxy, method, args ->
            when (method.name) {
                "onUpdate" -> {
                    val rectangle = args?.get(0) as? RectF
                    val progress = args?.get(1) as? Float
                    if (rectangle != null && progress != null) onFrame(rectangle, progress)
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "One UI spring update listener"
                else -> null
            }
        }
        val animator = object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = onFinished()
        }
        @Suppress("UNCHECKED_CAST")
        val updateList = updateListeners.get(animation) as MutableList<Any>
        @Suppress("UNCHECKED_CAST")
        val animatorList = animatorListeners.get(animation) as MutableList<Animator.AnimatorListener>
        if (beforeStock) {
            updateList.add(0, updates)
            animatorList.add(0, animator)
        } else {
            addUpdate.invoke(animation, updates)
            addAnimator.invoke(animation, animator)
        }
        return Observation {
            updateList.remove(updates)
            animatorList.remove(animator)
        }
    }

    fun interface Observation { fun detach() }

    internal data class Snapshot(
        val context: Context,
        val velocity: PointF,
        val start: RectF,
        val target: RectF,
        val displayHeight: Int,
        val values: List<Any?>,
    )

    internal inner class Motion(private val animation: Any, private val context: Context, private val velocity: PointF) {
        fun start() { startCallback.invoke(animation, context, velocity) }
        fun cancel() { cancel.invoke(animation) }
        fun captureHorizontal() = this@LauncherSpringReplay.captureHorizontal(animation)
        fun useHorizontal(state: LauncherHorizontalSpringState.Snapshot): Boolean = horizontalState?.apply(animation, state) == true
    }
}
