package dev.ryuya.oneuihometweaks

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.WindowManager
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** Slides out the existing lock screen before Samsung hands the scene to Home. */
@RequiresApi(33)
internal class LauncherLockscreenExit private constructor(
    controller: Class<*>,
    surfaceController: Class<*>,
    stateController: Class<*>,
    private val enabled: () -> Boolean,
) {
    private val play = controller.compatibleDeclaredMethod("playCannedUnlockAnimation")
    private val handoff = controller.compatibleDeclaredMethod(
        "access\$playUnlockAnimationForLauncher", controller, Boolean::class.javaPrimitiveType,
    ).apply { isAccessible = true }
    private val firstHide = surfaceController.compatibleDeclaredMethod(
        "setKeyguardSurfaceAppearAmount", Float::class.javaPrimitiveType,
        SurfaceControl.Transaction::class.java, Boolean::class.javaPrimitiveType,
    )
    private val defaultHide = surfaceController.compatibleDeclaredMethod(
        "setKeyguardSurfaceAppearAmount\$default", surfaceController, Float::class.javaPrimitiveType,
        SurfaceControl.Transaction::class.java, Int::class.javaPrimitiveType,
    )
    private val restore = surfaceController.compatibleDeclaredMethod("restoreKeyguardSurfaceByTransaction")
        .apply { isAccessible = true }
    private val getSurface = controller.compatibleDeclaredMethod("getKeyguardSurface").apply { isAccessible = true }
    private val lockStar = controller.compatibleDeclaredMethod("isUnlockStarEnabled").apply { isAccessible = true }
    private val state = controller.getField("keyguardStateController")
    private val goingAway = stateController.getField("mKeyguardGoingAway")
    private val dismissed = stateController.getField("mCanDismissLockScreen")
    private val showing = stateController.getField("mShowing")
    private val launcher = controller.getField("isLauncherActivity")
    private val bouncer = controller.getField("isPrimaryBouncerShowing")
    private val canned = controller.getField("playingCannedUnlockAnimation")
    private val targets = controller.getField("surfacesBehind")
    private val windows = controller.getField("windowManager")
    private val main = Handler(Looper.getMainLooper())

    private var starting: Any? = null
    private var active: Departure? = null
    private var reported = false

    private fun install() {
        check(play.returnType == Void.TYPE && handoff.returnType == Void.TYPE)
        check(firstHide.returnType == Void.TYPE && restore.returnType == Void.TYPE)
        check(getSurface.returnType == SurfaceControl::class.java && windows.type == WindowManager::class.java)
        check(goingAway.type == Boolean::class.javaPrimitiveType && dismissed.type == goingAway.type)
        check(state.type.isAssignableFrom(goingAway.declaringClass))
        check(ModernHook.deoptimize(play) && ModernHook.deoptimize(defaultHide))

        ModernHook.onGenerationRetired { active?.finish(deliverHandoff = true) }
        val hooks = ArrayList<ModernHook.Handle>()
        try {
            hooks += ModernHook.aroundMethod(handoff) { chain ->
                val departure = active
                if (Looper.myLooper() !== Looper.getMainLooper() || departure == null ||
                    departure.owner !== chain.args[0]
                ) {
                    return@aroundMethod chain.proceed()
                }
                if (chain.args[1] == true || !departure.current() ||
                    SystemClock.uptimeMillis() >= departure.deadline
                ) {
                    departure.finish(deliverHandoff = false)
                    return@aroundMethod chain.proceed()
                }

                departure.ready = true
                null
            }
            hooks += ModernHook.aroundMethod(play) { chain ->
                if (Looper.myLooper() !== Looper.getMainLooper() || !enabled() ||
                    !ValueAnimator.areAnimatorsEnabled()
                ) return@aroundMethod chain.proceed()

                active?.finish(deliverHandoff = false, superseded = true)
                val prior = starting
                starting = chain.thisObject
                try {
                    chain.proceed()
                } finally {
                    starting = prior
                }
            }
            hooks += ModernHook.aroundMethod(firstHide) { chain ->
                val owner = starting ?: return@aroundMethod chain.proceed()
                val host = chain.thisObject ?: return@aroundMethod chain.proceed()
                if (Looper.myLooper() !== Looper.getMainLooper() ||
                    chain.args[0] != HIDDEN_ALPHA || chain.args[1] != null || chain.args[2] != false ||
                    !launcher.getBoolean(owner) || bouncer.getBoolean(owner) ||
                    !accepted(owner) || lockStar.invoke(owner) == true
                ) return@aroundMethod chain.proceed()

                if (startDeparture(owner, host)) null else chain.proceed()
            }
        } catch (error: Throwable) {
            hooks.asReversed().forEach { it.unhook() }
            throw error
        }
    }

    private fun accepted(owner: Any): Boolean {
        val keyguard = state.get(owner) ?: return false
        return goingAway.getBoolean(keyguard) &&
            (!showing.getBoolean(keyguard) || dismissed.getBoolean(keyguard))
    }

    private fun stillLocked(owner: Any): Boolean {
        val keyguard = state.get(owner) ?: return false
        return showing.getBoolean(keyguard) && !dismissed.getBoolean(keyguard)
    }

    private fun startDeparture(owner: Any, host: Any): Boolean {
        if (active != null) return false
        val targetSet = targets.get(owner) ?: return false
        val surface = getSurface.invoke(owner) as? SurfaceControl ?: return false
        if (!surface.isValid) return false
        val wm = windows.get(owner) as WindowManager
        val screen = wm.maximumWindowMetrics.bounds
        if (screen.isEmpty || screen.left != 0 || screen.top != 0 || screen.width() >= screen.height())
            return false

        val now = SystemClock.uptimeMillis()
        val generation = UnlockLogicRuntime.capture()
        val duration = (generation.duration(UnlockLogicV1.LOCKSCREEN, 60L..280L) *
            ValueAnimator.getDurationScale()).toLong().coerceIn(100L, 240L)
        val departure = Departure(owner, host, targetSet, surface, screen.height(), now, duration, generation)
        active = departure
        departure.start()
        if (!reported) {
            reported = true
            HookEntry.log("LAUNCHER_LOCKSCREEN_EXIT_STARTED duration=${duration}ms")
        }
        return true
    }

    private inner class Departure(
        val owner: Any,
        private val host: Any,
        private val targetSet: Any,
        private val surface: SurfaceControl,
        private val height: Int,
        private val start: Long,
        private val duration: Long,
        private val generation: UnlockLogicRuntime.Generation,
    ) : Choreographer.FrameCallback, Runnable {
        val deadline = start + duration
        var ready = false
        private val compositor = SurfaceControl.Transaction()
        private val frame = UnlockLockscreenFrame()
        private val choreographer = Choreographer.getInstance()

        fun start() {
            choreographer.postFrameCallback(this)
            main.postAtTime(this, deadline)
        }

        private fun ownsTransition(): Boolean =
            targets.get(owner) === targetSet && canned.getBoolean(owner) && launcher.getBoolean(owner)

        fun current(): Boolean =
            ownsTransition() && surface.isValid && getSurface.invoke(owner) === surface && accepted(owner)

        override fun doFrame(frameTimeNanos: Long) {
            if (active !== this) return
            try {
                if (!enabled() || !current()) {
                    finish(deliverHandoff = true)
                    return
                }
                val progress = ((frameTimeNanos / 1_000_000L - start).toFloat() / duration).coerceIn(0f, 1f)
                generation.call {
                    it.lockscreenFrame(progress, frame)
                    frame.slide.validFraction()
                    frame.alpha.validFraction()
                }
                compositor.setPosition(surface, 0f, -height * frame.slide)
                compositor.setAlpha(surface, frame.alpha.coerceAtLeast(HIDDEN_ALPHA))
                compositor.apply()
                choreographer.postFrameCallback(this)
            } catch (error: Exception) {
                HookEntry.log("LAUNCHER_LOCKSCREEN_EXIT_INTERRUPTED ${error.javaClass.simpleName}")
                finish(deliverHandoff = true)
            }
        }

        override fun run() = finish(deliverHandoff = true)

        fun finish(deliverHandoff: Boolean, superseded: Boolean = false) {
            if (active !== this) return
            active = null
            choreographer.removeFrameCallback(this)
            main.removeCallbacks(this)
            val proceed = !superseded && ownsTransition() && accepted(owner)
            try {
                if (surface.isValid) {
                    compositor.setPosition(surface, 0f, 0f)
                    if (proceed && getSurface.invoke(owner) === surface)
                        compositor.setAlpha(surface, HIDDEN_ALPHA)
                    compositor.apply()
                    if (!proceed && stillLocked(owner) && getSurface.invoke(owner) === surface) restore.invoke(host)
                }
            } catch (error: Exception) {
                HookEntry.log("LAUNCHER_LOCKSCREEN_EXIT_RESTORE ${error.javaClass.simpleName}")
            } finally {
                compositor.close()
            }
            if (deliverHandoff && ready && proceed) {
                try {
                    handoff.invoke(null, owner, false)
                } catch (error: Exception) {
                    HookEntry.log("LAUNCHER_LOCKSCREEN_EXIT_HANDOFF ${error.javaClass.simpleName}")
                }
            }
        }
    }

    companion object {
        private const val HIDDEN_ALPHA = 0.001f

        fun install(controller: Class<*>, loader: ClassLoader, enabled: () -> Boolean) {
            try {
                val surface = ModernHook.findClass("com.android.systemui.keyguard.KeyguardSurfaceControllerImpl", loader)
                val state = ModernHook.findClass("com.android.systemui.statusbar.policy.KeyguardStateControllerImpl", loader)
                LauncherLockscreenExit(controller, surface, state, enabled).install()
                HookEntry.log("LAUNCHER_LOCKSCREEN_EXIT_READY")
            } catch (error: Exception) {
                HookEntry.log("LAUNCHER_LOCKSCREEN_EXIT_UNAVAILABLE ${error.javaClass.simpleName}")
            } catch (error: LinkageError) {
                HookEntry.log("LAUNCHER_LOCKSCREEN_EXIT_UNAVAILABLE ${error.javaClass.simpleName}")
            }
        }
    }
}
