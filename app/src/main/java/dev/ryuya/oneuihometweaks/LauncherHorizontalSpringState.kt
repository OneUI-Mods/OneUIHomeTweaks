package dev.ryuya.oneuihometweaks

import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.reflect.Field

/** Transfers the running horizontal axis to a replay that has reached the same animation phase. */
internal class LauncherHorizontalSpringState(animation: Class<*>) {
    private val center = animation.field("currentCenterX")
    private val wrapper = animation.field("rectXAnim")
    private val target = wrapper.type.field("targetPosition")
    private val fling = wrapper.type.field("flingAnim")
    private val spring = wrapper.type.field("springAnim")
    private val dynamic = checkNotNull(fling.type.superclass)
    private val running = dynamic.compatibleDeclaredMethod("isRunning").apply { isAccessible = true }
    private val animationHandler = dynamic.field("mAnimationHandler")
    private val values = dynamic.fields("mValue", "mVelocity", "mStartValueIsSet", "mLastFrameTime", "mMinValue", "mMaxValue",
        "mMinVisibleChange")
    private val flingForce = fling.type.field("mFlingForce")
    private val flingValues = flingForce.type.fields("mFriction", "mVelocityThreshold")
    private val springFlags = spring.type.fields("mPendingPosition", "mEndRequested")
    private val springForce = spring.type.field("mSpring")
    private val springValues = springForce.type.fields("mFinalPosition", "mDampingRatio", "mNaturalFreq", "mInitialized",
        "mGammaPlus", "mGammaMinus", "mDampedFreq", "mValueThreshold", "mVelocityThreshold")

    fun capture(animation: Any): Snapshot? {
        val axis = wrapper.get(animation) ?: return null
        val (phase, active) = readActive(axis) ?: return null
        val forceField = if (phase == Phase.SPRING) springForce else flingForce
        val force = forceField.get(active) ?: return null
        val forceValues = if (phase == Phase.SPRING) springValues else flingValues
        return Snapshot(phase, center.getFloat(animation), target.getFloat(axis), values.read(active), forceValues.read(force),
            if (phase == Phase.SPRING) springFlags.read(active) else emptyList())
    }

    fun apply(animation: Any, snapshot: Snapshot): Boolean {
        val axis = wrapper.get(animation) ?: return false
        val (phase, active) = readActive(axis) ?: return false
        if (phase != snapshot.phase || target.getFloat(axis) != snapshot.target) return false
        val forceField = if (phase == Phase.SPRING) springForce else flingForce
        val force = forceField.get(active) ?: return false
        val forceValues = if (phase == Phase.SPRING) springValues else flingValues

        center.setFloat(animation, snapshot.center)
        values.write(active, snapshot.values)
        forceValues.write(force, snapshot.force)
        if (phase == Phase.SPRING) springFlags.write(active, snapshot.flags)
        return true
    }

    private fun readActive(axis: Any): Pair<Phase, Any>? {
        val activeSpring = spring.get(axis)?.takeIf { running.invoke(it) == true }
        val activeFling = fling.get(axis)?.takeIf { running.invoke(it) == true }
        if ((activeSpring == null) == (activeFling == null)) return null
        val active = activeSpring ?: checkNotNull(activeFling)
        if (animationHandler.get(active) != null) return null
        return (if (activeSpring != null) Phase.SPRING else Phase.FLING) to active
    }

    internal enum class Phase { FLING, SPRING }

    internal class Snapshot(val phase: Phase, val center: Float, val target: Float, val values: List<Any>,
        val force: List<Any>, val flags: List<Any>)

    private fun Class<*>.field(name: String) = compatibleDeclaredField(name).apply { isAccessible = true }
    private fun Class<*>.fields(vararg names: String) = names.map { field(it) }
    private fun List<Field>.read(target: Any) = map { checkNotNull(it.get(target)) }
    private fun List<Field>.write(target: Any, values: List<Any>) = forEachIndexed { index, field -> field.set(target, values[index]) }
}
