package dev.ryuya.oneuihometweaks

import android.animation.Animator
import android.app.Application
import android.os.Build
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** Sends the wakeup-end transaction queued by One UI Home's overlay blur listener. */
internal object LauncherEarlyWakeupHooks {
    private const val OVERLAY_BLUR_END_LISTENER =
        "com.honeyspace.gesture.recentsanimation.LeashUpdater" +
            "\$leashOverLayBlurAnimation\$lambda\$0\$1\$\$inlined\$doOnEnd\$1"

    fun install(app: Application, loader: ClassLoader?) {
        val sdk = Build.VERSION.SDK_INT
        if (sdk != 37 || !HookCompatibility.matchesClockBuild(sdk, Build.DISPLAY)) return
        try {
            @Suppress("DEPRECATION")
            val launcherVersion = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (!HookCompatibility.matchesLauncherVersion(sdk, launcherVersion)) return
            installListener(loader)
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_EARLY_WAKEUP_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun installListener(loader: ClassLoader?) {
        val listener = DexSymbols.findClass(OVERLAY_BLUR_END_LISTENER, loader)
        val surfaceTransaction = DexSymbols.findClass(
            "com.honeyspace.transition.utils.SurfaceTransaction", loader,
        )
        val captured = listener.declaredFields.single { it.type == surfaceTransaction }
            .apply { isAccessible = true }
        val getTransaction = surfaceTransaction.compatibleDeclaredMethod("getTransaction")
            .apply { isAccessible = true }
        val apply = getTransaction.returnType.compatibleDeclaredMethod("apply")
            .apply { isAccessible = true }
        val onEnd = listener.compatibleDeclaredMethod("onAnimationEnd", Animator::class.java)
        check(Animator.AnimatorListener::class.java.isAssignableFrom(listener))
        check(apply.returnType == Void.TYPE && onEnd.returnType == Void.TYPE)

        ModernHook.aroundMethod(onEnd) { chain ->
            val owner = chain.thisObject ?: return@aroundMethod chain.proceed()
            val pending = try {
                captured.get(owner)?.let { getTransaction.invoke(it) }
            } catch (_: ReflectiveOperationException) {
                null
            }
            val result = chain.proceed()
            if (pending != null) {
                try {
                    // The listener already submitted any final layer changes. Its wakeup end
                    // is queued afterward, and no remaining animation frame submits it.
                    apply.invoke(pending)
                } catch (error: ReflectiveOperationException) {
                    HookEntry.log("LAUNCHER_EARLY_WAKEUP_END_UNAVAILABLE ${error.javaClass.simpleName}")
                }
            }
            result
        }
        HookEntry.log("LAUNCHER_EARLY_WAKEUP_END_READY")
    }
}
