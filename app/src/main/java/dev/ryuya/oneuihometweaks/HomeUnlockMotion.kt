package dev.ryuya.oneuihometweaks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Application
import android.os.Build
import android.os.Looper
import android.view.Display
import android.view.View
import android.os.SystemClock
import java.util.WeakHashMap
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** A visual adjustment to the existing post-authentication Launcher transition. */
internal class HomeUnlockMotion {
    private data class Pending(
        val generation: UnlockLogicRuntime.Generation, val duration: Long, val expires: Long,
    )
    private val pending = WeakHashMap<View, Pending>()
    private var enabled = false
    private var active: HomeUnlockReveal? = null
    private var wallpaper: HomeUnlockWallpaper? = null
    private var reported = false

    internal fun idleForHotReload(): Boolean {
        val now = SystemClock.uptimeMillis()
        pending.values.removeAll { it.expires <= now }
        return active == null && pending.isEmpty()
    }

    fun configure(value: Boolean) {
        if (enabled != value) HookEntry.log("HOME_UNLOCK_MOTION_CONFIG enabled=$value")
        enabled = value
        if (!value) {
            pending.clear()
            active?.finish()
            wallpaper?.stop()
        }
    }

    fun install(app: Application, loader: ClassLoader) {
        if (!supportsDevice()) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (!HookCompatibility.matchesLauncherVersion(37, version)) return
            installHooks(loader)
            wallpaper = HomeUnlockWallpaper.install(loader)
            // Launcher code is replaced as a whole generation by API 102.
            HookEntry.log("HOME_UNLOCK_MOTION_READY")
        } catch (error: Exception) {
            HookEntry.log("HOME_UNLOCK_MOTION_UNAVAILABLE ${error.javaClass.simpleName}")
        } catch (error: LinkageError) {
            HookEntry.log("HOME_UNLOCK_MOTION_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun installHooks(loader: ClassLoader) {
        val bridge = ModernHook.findClass("v5.t", loader)
        val listener = ModernHook.findClass("v5.q", loader)
        check(
            bridge.superclass?.name ==
                "com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController\$Stub",
        )
        check(AnimatorListenerAdapter::class.java.isAssignableFrom(listener))
        val rootView = listener.getDeclaredField("c").apply { isAccessible = true }
        check(rootView.type == View::class.java)
        val play = bridge.compatibleDeclaredMethod(
            "e", View::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        )
        val start = listener.compatibleDeclaredMethod(
            "onAnimationStart", Animator::class.java, Boolean::class.javaPrimitiveType,
        )
        check(play.returnType == Void.TYPE && start.returnType == Void.TYPE)

        val startHook = ModernHook.hookMethod(start, object : ModernHook.Callback() {
            override fun beforeHookedMethod(param: ModernHook.Param) {
                if (!enabled || Looper.myLooper() !== Looper.getMainLooper() || param.args[1] == true) return
                val animator = param.args[0] as? ValueAnimator ?: return
                if (!ValueAnimator.areAnimatorsEnabled()) return
                val root = rootView.get(param.thisObject) as? View ?: return
                val selection = pending.remove(root) ?: return
                if (!eligible(root) || animator.duration != selection.duration ||
                    SystemClock.uptimeMillis() > selection.expires
                ) return

                val generation = selection.generation
                active?.finish()
                animator.interpolator = android.animation.TimeInterpolator { progress ->
                    generation.call { it.homeOpacity(progress).validFraction() }
                }
                val reveal = HomeUnlockReveal(root, animator, generation) {
                    if (active === it) active = null
                }
                active = reveal
                reveal.start()
                wallpaper?.play(root, generation)
                if (!reported) {
                    reported = true
                    HookEntry.log("HOME_UNLOCK_MOTION_STARTED ${reveal.description} code=${generation.version}")
                }
            }
        })
        try {
            ModernHook.hookMethod(play, object : ModernHook.Callback() {
                override fun beforeHookedMethod(param: ModernHook.Param) {
                    if (!enabled || Looper.myLooper() !== Looper.getMainLooper()) return
                    if (param.args[1] != SAMSUNG_DURATION || (param.args[2] as Long) < 0L) return
                    val root = param.args[0] as? View ?: return
                    if (!eligible(root) || !ValueAnimator.areAnimatorsEnabled()) return
                    val generation = UnlockLogicRuntime.capture()
                    val duration = generation.duration(UnlockLogicV1.HOME, 100L..850L)
                    pending[root] = Pending(generation, duration, SystemClock.uptimeMillis() + 4000L)
                    param.args[1] = duration
                }
            })
        } catch (error: Throwable) {
            startHook.unhook()
            throw error
        }
    }

    private fun eligible(root: View): Boolean =
        root.isAttachedToWindow && root.display?.displayId == Display.DEFAULT_DISPLAY

    companion object {
        const val KEY = "home_unlock_motion"
        const val DEFAULT_ENABLED = true
        private const val SAMSUNG_DURATION = 200L

        fun supportsDevice(): Boolean =
            Build.VERSION.SDK_INT == 37 && HookCompatibility.supportsRecentsFocus(37, Build.FINGERPRINT)
    }
}
