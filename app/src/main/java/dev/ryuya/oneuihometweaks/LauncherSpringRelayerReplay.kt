package dev.ryuya.oneuihometweaks

import android.animation.ValueAnimator
import android.graphics.RectF
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.reflect.Field
import java.lang.reflect.Proxy

/** Copies the catch gesture's second spring into the detached animation scheduler. */
internal class LauncherSpringRelayerReplay(val type: Class<*>, loader: ClassLoader) {
    private val currentRect = type.field("rectf")
    private val rectAnimation = type.field("rectAnim")
    private val progressAnimation = type.field("progressAnim")
    private val rectType = rectAnimation.type
    private val progressType = progressAnimation.type
    private val rectConstructor = rectType.getDeclaredConstructor(RectF::class.java)
    private val progressConstructor = progressType.getDeclaredConstructor(Float::class.javaPrimitiveType)
    private val stiffnessAnimator = rectType.field("stiffnessAnimator")
    private val getProgress = type.compatibleDeclaredMethod("getProgress").apply { isAccessible = true }
    private val rectSprings = rectType.compatibleDeclaredMethod("getSpringAnimations")
    private val progressSprings = progressType.compatibleDeclaredMethod("getSpringAnimations")
    private val function = Class.forName("kotlin.jvm.functions.Function1", false, type.classLoader)
    private val unit = Class.forName("kotlin.Unit", true, type.classLoader).getField("INSTANCE").get(null)
    private val rectListener = rectType.compatibleDeclaredMethod("addUpdateListener", function)
    private val progressListener = progressType.compatibleDeclaredMethod("addUpdateListener", function)
    private val spring = DexSymbols.findClass("androidx.dynamicanimation.animation.SpringAnimation", loader)
    private val dynamic = checkNotNull(spring.superclass)
    private val hostHandler = dynamic.field("mAnimationHandler")
    private val dynamicFields = dynamic.fields("mValue", "mVelocity", "mStartValueIsSet", "mLastFrameTime",
        "mMinValue", "mMaxValue", "mMinVisibleChange")
    private val springFields = spring.fields("mPendingPosition", "mEndRequested")
    private val force = spring.field("mSpring")
    private val forceFields = force.type.fields("mFinalPosition", "mDampingRatio", "mNaturalFreq", "mInitialized",
        "mGammaPlus", "mGammaMinus", "mDampedFreq", "mValueThreshold", "mVelocityThreshold")
    private val getHandler = dynamic.compatibleDeclaredMethod("getAnimationHandler")
    private val callbacks = getHandler.returnType.field("mAnimationCallbacks")
    private val isRunning = dynamic.compatibleDeclaredMethod("isRunning")
    private val start = spring.compatibleDeclaredMethod("start")
    private val cancel = spring.compatibleDeclaredMethod("cancel")
    private val animateAxis = spring.compatibleDeclaredMethod("animateToFinalPosition", Float::class.javaPrimitiveType)
    val deliver = type.compatibleDeclaredMethod("postUpdate\$lambda\$0", type).apply { isAccessible = true }

    fun snapshot(stock: Any): Snapshot? {
        val rect = rectAnimation.get(stock) ?: return null
        val progress = progressAnimation.get(stock) ?: return null
        return snapshot(rect, progress, currentRect.get(stock) as RectF, getProgress.invoke(stock) as Float)
    }

    private fun snapshot(rect: Any, progress: Any, position: RectF, progressValue: Float): Snapshot? {
        // This ValueAnimator has a separate clock and may still be changing the force.
        if ((stiffnessAnimator.get(rect) as? ValueAnimator)?.isStarted == true) return null
        val springs = children(rect, progress)
        if (springs.size != 5 || springs.any { hostHandler.get(it) != null }) return null
        val order = callbacks.get(getHandler.invoke(springs.first())) as List<*>
        val snapshots = springs.map { child ->
            val running = isRunning.invoke(child) == true
            val index = if (running) order.indexOfFirst { it === child } else -1
            if (running && index < 0) return null
            val springForce = force.get(child) ?: return null
            Axis(index, dynamicFields.read(child), springFields.read(child), forceFields.read(springForce))
        }
        return Snapshot(RectF(position), progressValue, snapshots)
    }

    fun prepare(snapshot: Snapshot): Playback {
        val rect = RectF(snapshot.rect)
        val animation = rectConstructor.newInstance(rect)
        val progress = progressConstructor.newInstance(snapshot.progress)
        val springs = children(animation, progress)
        val playback = Playback(rect, springs, snapshot)
        val listener = Proxy.newProxyInstance(function.classLoader, arrayOf(function)) { proxy, method, arguments ->
            when (method.name) {
                "invoke" -> {
                    playback.markUpdated()
                    unit
                }
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "One UI relayer update listener"
                else -> error("Unexpected relayer listener method ${method.name}")
            }
        }
        rectListener.invoke(animation, listener)
        progressListener.invoke(progress, listener)
        return playback
    }

    private fun children(rect: Any, progress: Any): List<Any> {
        @Suppress("UNCHECKED_CAST")
        return (rectSprings.invoke(rect) as List<Any>) + (progressSprings.invoke(progress) as List<Any>)
    }

    internal inner class Playback(
        private val position: RectF,
        private val springs: List<Any>,
        private val snapshot: Snapshot,
    ) {
        private val safe = RectF()
        private var dirty = false
        private var offered = false
        private var lastProgress = snapshot.progress

        fun start() {
            val indices = snapshot.axes.indices.sortedBy { snapshot.axes[it].order }
            for (index in indices) {
                val child = springs[index]
                val state = snapshot.axes[index]
                val targetForce = checkNotNull(force.get(child))
                forceFields.write(targetForce, state.force)
                dynamicFields.write(child, state.dynamic)
                springFields.write(child, state.spring)
                if (state.order >= 0) {
                    // Let the stock scheduler register this child, then restore the exact state it overwrote.
                    start.invoke(child)
                    dynamicFields.write(child, state.dynamic)
                    forceFields.write(targetForce, state.force)
                }
            }
        }

        /** Join a stock tick without adding work to the next frame or changing already-running callback order. */
        fun rebase(stock: Snapshot, progress: Float, onFrame: (RectF, Float) -> Unit) {
            check(stock.axes.size == springs.size)
            for (index in stock.axes.indices.sortedBy { stock.axes[it].order }) {
                val child = springs[index]
                val axis = stock.axes[index]
                val running = isRunning.invoke(child) == true
                val shouldRun = axis.order >= 0
                if (!shouldRun && running) cancel.invoke(child)
                val targetForce = checkNotNull(force.get(child))
                forceFields.write(targetForce, axis.force)
                dynamicFields.write(child, axis.dynamic)
                springFields.write(child, axis.spring)
                if (shouldRun && !running) {
                    start.invoke(child)
                    dynamicFields.write(child, axis.dynamic)
                    springFields.write(child, axis.spring)
                    forceFields.write(targetForce, axis.force)
                }
            }
            position.set(stock.rect)
            lastProgress = progress
            offered = true
            dirty = true
            flush(onFrame)
        }

        fun offer(rect: RectF, progress: Float) {
            offered = true
            lastProgress = progress
            // The enclosing stock method also emits logs and starts an async trace for the same four calls.
            animateAxis.invoke(springs[0], rect.left)
            animateAxis.invoke(springs[1], rect.top)
            animateAxis.invoke(springs[2], rect.right)
            animateAxis.invoke(springs[3], rect.bottom)
        }

        fun markUpdated() { dirty = true }

        fun flush(onFrame: (RectF, Float) -> Unit) {
            if (!dirty) return
            dirty = false
            if (!offered) return
            val x = position.centerX()
            val y = position.centerY()
            val halfWidth = maxOf(0f, position.width()) / 2f
            val halfHeight = maxOf(0f, position.height()) / 2f
            safe.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
            onFrame(safe, lastProgress)
        }

        fun isRunning() = springs.any { isRunning.invoke(it) == true }

        fun close() {
            for (child in springs) cancel.invoke(child)
            dirty = false
        }
    }

    internal class Snapshot(val rect: RectF, val progress: Float, val axes: List<Axis>)
    internal class Axis(val order: Int, val dynamic: List<Any>, val spring: List<Any>, val force: List<Any>)

    private fun Class<*>.field(name: String) = compatibleDeclaredField(name).apply { isAccessible = true }
    private fun Class<*>.fields(vararg names: String) = names.map { field(it) }
    private fun List<Field>.read(target: Any) = map { checkNotNull(it.get(target)) }
    private fun List<Field>.write(target: Any, values: List<Any>) = forEachIndexed { index, field -> field.set(target, values[index]) }
}
