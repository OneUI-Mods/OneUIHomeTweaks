package dev.ryuya.oneuihometweaks

import android.app.Application
import android.os.Build
import android.view.View
import android.view.ViewGroup
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField

/**
 * Optionally skips the stock per-icon blur during Home app transitions; the wallpaper blur is unchanged.
 *
 * Stock BlurAnimator sets a RenderEffect on every visible icon and widget, so HWUI allocates and re-renders
 * about fifty layers on each return to Home. Without it the first Home frames need only a few GPU allocations.
 */
internal object LauncherTransitionIconBlur {
    private const val ANIMATOR = "com.honeyspace.transition.anim.BlurAnimator"

    fun install(app: Application, loader: ClassLoader) {
        if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.matchesClockBuild(37, Build.DISPLAY)) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (!HookCompatibility.matchesLauncherVersion(37, version)) return
            val animator = DexSymbols.findClass(ANIMATOR, loader)
            val start = animator.declaredMethods.single {
                it.name == "start" && it.parameterTypes.size == 3 && it.parameterTypes[0] == ViewGroup::class.java
            }
            val children = animator.compatibleDeclaredField("visibleChildViews").apply { isAccessible = true }
            check(List::class.java.isAssignableFrom(children.type))
            ModernHook.aroundMethod(start) { chain ->
                val result = chain.proceed()
                // The stock animator then drives only the wallpaper blur and clears an empty icon list.
                chain.thisObject?.let { children.set(it, mutableListOf<View>()) }
                result
            }
            HookEntry.log("LAUNCHER_SKIP_ICON_BLUR_READY")
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_SKIP_ICON_BLUR_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
