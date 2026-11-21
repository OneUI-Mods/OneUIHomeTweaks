package dev.ryuya.oneuihometweaks

import android.app.Application
import android.graphics.RenderEffect
import android.os.Build
import android.os.Looper
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** Reuses only the stock radius-only RenderEffect while One UI Home applies transition blur. */
internal object LauncherBlurCacheHooks {
    fun install(app: Application, loader: ClassLoader) {
        val sdk = Build.VERSION.SDK_INT
        if (sdk != 37 || !HookCompatibility.matchesClockBuild(sdk, Build.DISPLAY)) return
        try {
            @Suppress("DEPRECATION")
            val launcherVersion = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (!HookCompatibility.matchesLauncherVersion(sdk, launcherVersion)) return
            install(loader)
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_BLUR_CACHE_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun install(loader: ClassLoader) {
        val animator = DexSymbols.findClass("com.honeyspace.transition.anim.BlurAnimator", loader)
        val progressType = DexSymbols.findClass(
            "com.honeyspace.sdk.transition.entity.ProgressState\$Type", loader,
        )
        val applyBlur = animator.compatibleDeclaredMethod(
            "applyBlur", Float::class.javaPrimitiveType, progressType, Boolean::class.javaPrimitiveType,
        )
        val visibleViews = animator.compatibleDeclaredField("visibleChildViews").apply { isAccessible = true }
        val imageFilter = DexSymbols.findClass("com.samsung.android.graphics.RenderEffectImageFilter", loader)
        val build = imageFilter.compatibleDeclaredMethod(
            "build", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        val assembler = imageFilter.compatibleDeclaredField("assembler").apply { isAccessible = true }
        val activeFilters = assembler.type.compatibleDeclaredField("filterEffects").apply { isAccessible = true }
        val radiusOnly = DexSymbols.findClass("com.samsung.android.graphics.imagefilter.filters.BlurFilter", loader)
        check(build.returnType == RenderEffect::class.java && List::class.java.isAssignableFrom(visibleViews.type))

        val main = Looper.getMainLooper()
        val batch = LauncherBlurBuildBatch()
        var applyingBlur = false
        var disabled = false
        val effectHook = ModernHook.aroundMethod(build) { chain ->
            if (Looper.myLooper() !== main || !applyingBlur) return@aroundMethod chain.proceed()
            val filter = chain.thisObject ?: return@aroundMethod chain.proceed()
            val validSize = (chain.args[0] as Int) > 0 && (chain.args[1] as Int) > 0
            batch.build(filter, validSize, { chain.proceed() as RenderEffect? }) {
                try {
                    val filters = activeFilters.get(assembler.get(filter)) as? Map<*, *>
                    // A single blur calculates only radius and edge mode, independent of the view.
                    filters?.size == 1 && filters[1]?.javaClass == radiusOnly
                } catch (_: ReflectiveOperationException) {
                    false
                }
            }
        }
        try {
            ModernHook.aroundMethod(applyBlur) { chain ->
                if (Looper.myLooper() !== main || disabled) return@aroundMethod chain.proceed()
                if (applyingBlur) {
                    applyingBlur = false
                    disabled = true
                    return@aroundMethod chain.proceed()
                }
                val count = (visibleViews.get(chain.thisObject) as? List<*>)?.size ?: 0
                if (count < 3) return@aroundMethod chain.proceed()
                batch.begin(count)
                applyingBlur = true
                try {
                    chain.proceed()
                } finally {
                    applyingBlur = false
                    if (batch.nativeBuilds < batch.requests && batch.requests != count) {
                        disabled = true
                        HookEntry.log("LAUNCHER_BLUR_CACHE_LOOP_CHANGED expected=$count calls=${batch.requests}")
                    }
                    batch.finish()
                }
            }
        } catch (error: Exception) {
            effectHook.unhook()
            throw error
        }
        HookEntry.log("LAUNCHER_BLUR_CACHE_READY")
    }
}
