package dev.ryuya.oneuihometweaks

import android.os.Build
import android.os.Looper
import android.os.Trace
import android.view.View
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** Skips BZI9's framework caller logging when its base View state would be identical. */
internal object ShadeFrameRateRequests {
    private const val FORCED_RATE = 1 shl 30
    private const val SELF_REQUESTED_RATE = Int.MIN_VALUE

    fun install(loader: ClassLoader) {
        if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.matchesClockBuild(37, Build.DISPLAY)) return
        try {
            val composeHost = DexSymbols.findClass("androidx.compose.ui.platform.AndroidComposeView", loader)
            val view = View::class.java
            val setter = view.compatibleDeclaredMethod("setRequestedFrameRate", Float::class.javaPrimitiveType)
            val preferred = view.compatibleDeclaredField("mPreferredFrameRate")
            val flags = view.compatibleDeclaredField("mPrivateFlags4")
            val setterEnabled = view.compatibleDeclaredField("sToolkitSetFrameRateReadOnlyFlagValue")
            val groupApi = view.compatibleDeclaredField("sToolkitViewGroupFrameRateApiFlagValue")
            check(setter.returnType == Void.TYPE && preferred.type == Float::class.javaPrimitiveType &&
                flags.type == Int::class.javaPrimitiveType)

            val main = Looper.getMainLooper()
            var redundantRequests = 0L
            ModernHook.aroundMethod(setter) { chain ->
                val target = chain.thisObject
                if (Looper.myLooper() !== main || !composeHost.isInstance(target) ||
                    !setterEnabled.getBoolean(null)
                ) {
                    return@aroundMethod chain.proceed()
                }
                val requested = chain.args[0] as Float
                val hasGroupApi = groupApi.getBoolean(null)
                val viewFlags = if (hasGroupApi) flags.getInt(target) else 0
                if (!isRedundant(preferred.getFloat(target), requested, hasGroupApi, viewFlags)) {
                    return@aroundMethod chain.proceed()
                }
                // ViewGroup's override continues after this base call, including its own flag writes.
                redundantRequests++
                if (redundantRequests % 32L == 1L && Trace.isEnabled()) {
                    Trace.setCounter("OneUI shade redundant frame rate requests", redundantRequests)
                }
                null
            }
            HookEntry.log("SHADE_FRAME_RATE_CACHE_READY")
        } catch (error: Exception) {
            HookEntry.log("SHADE_FRAME_RATE_CACHE_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    internal fun isRedundant(
        current: Float,
        requested: Float,
        hasGroupApi: Boolean,
        viewFlags: Int,
    ): Boolean {
        if (current.toRawBits() != requested.toRawBits()) return false
        if (!hasGroupApi) return true
        if ((viewFlags and FORCED_RATE) != 0) return false
        val selfRequested = (viewFlags and SELF_REQUESTED_RATE) != 0
        return selfRequested == !requested.isNaN()
    }
}
