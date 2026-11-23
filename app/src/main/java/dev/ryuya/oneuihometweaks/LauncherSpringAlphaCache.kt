package dev.ryuya.oneuihometweaks

import android.app.Application
import android.graphics.RectF
import android.os.Build
import android.os.Looper
import android.view.animation.PathInterpolator
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import dev.ryuya.oneuihometweaks.compat.compatibleMethod

/** Keeps the stock cubic interpolation while avoiding a new Android Path on every spring frame. */
internal object LauncherSpringAlphaCache {
    fun install(app: Application, loader: ClassLoader) {
        val sdk = Build.VERSION.SDK_INT
        if (sdk != 37 || !HookCompatibility.matchesClockBuild(sdk, Build.DISPLAY)) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (HookCompatibility.matchesLauncherVersion(sdk, version)) install(loader)
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_SPRING_ALPHA_CACHE_UNAVAILABLE ${failure.javaClass.simpleName}")
        }
    }

    private fun install(loader: ClassLoader) {
        val calculator = DexSymbols.findClass("com.honeyspace.transition.anim.floating.calculator.SpringCalculator", loader)
        val floatType = Float::class.javaPrimitiveType
        val calculate = calculator.compatibleDeclaredMethod("calculate", RectF::class.java, floatType, floatType, floatType)
        val tuneAlpha = calculator.compatibleDeclaredMethod("alphaTuning", floatType)
        val preferences = calculator.compatibleDeclaredMethod("getPreferenceDataSource").apply { isAccessible = true }
        val homeUp = preferences.returnType.compatibleMethod("getHomeUp")
        val gestureSettings = homeUp.returnType.compatibleMethod("getGestureSettings")
        val settingsValue = gestureSettings.returnType.compatibleMethod("getValue")
        val settingsData = DexSymbols.findClass("com.honeyspace.sdk.source.HomeUpDataSource\$GestureSettingsData", loader)
        val gestureTuning = settingsData.compatibleMethod("getGestureTuningData")
        val gestureAnimation = gestureTuning.returnType.compatibleMethod("getGestureAnimationData")
        val animationData = gestureAnimation.returnType
        val readX1 = animationData.compatibleMethod("getWindowAlphaInterpolatorX1")
        val readY1 = animationData.compatibleMethod("getWindowAlphaInterpolatorY1")
        val readX2 = animationData.compatibleMethod("getWindowAlphaInterpolatorX2")
        val readY2 = animationData.compatibleMethod("getWindowAlphaInterpolatorY2")
        check(ModernHook.deoptimize(calculate)) { "calculate may inline alphaTuning" }

        val main = Looper.getMainLooper()
        val curve = SpringAlphaCurve()
        var lastSettings: Any? = null
        var disabled = false
        ModernHook.aroundMethod(tuneAlpha) { chain ->
            val progress = chain.args[0] as? Float
            val owner = chain.thisObject
            if (owner == null || progress == null || disabled || Looper.myLooper() !== main) {
                return@aroundMethod chain.proceed()
            }
            try {
                val source = homeUp.invoke(preferences.invoke(owner))
                val settings = checkNotNull(settingsValue.invoke(gestureSettings.invoke(source)))
                // The supported build replaces immutable Home Up settings when any control point changes.
                if (lastSettings !== settings) {
                    val animation = gestureAnimation.invoke(gestureTuning.invoke(settings))
                    curve.update(readX1.invoke(animation) as Float, readY1.invoke(animation) as Float,
                        readX2.invoke(animation) as Float, readY2.invoke(animation) as Float)
                    lastSettings = settings
                }
                curve.interpolate(progress)
            } catch (failure: Exception) {
                disabled = true
                HookEntry.log("LAUNCHER_SPRING_ALPHA_CACHE_FALLBACK ${failure.javaClass.simpleName}")
                chain.proceed()
            }
        }
        HookEntry.log("LAUNCHER_SPRING_ALPHA_CACHE_READY")
    }
}

internal class SpringAlphaCurve {
    private var interpolator: PathInterpolator? = null
    private var x1 = 0f
    private var y1 = 0f
    private var x2 = 0f
    private var y2 = 0f

    fun update(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (interpolator != null && this.x1.toRawBits() == x1.toRawBits() && this.y1.toRawBits() == y1.toRawBits() &&
            this.x2.toRawBits() == x2.toRawBits() && this.y2.toRawBits() == y2.toRawBits()
        ) return

        val next = PathInterpolator(x1, y1, x2, y2)
        this.x1 = x1
        this.y1 = y1
        this.x2 = x2
        this.y2 = y2
        interpolator = next
    }

    fun interpolate(progress: Float): Float = checkNotNull(interpolator).getInterpolation(progress)
}
