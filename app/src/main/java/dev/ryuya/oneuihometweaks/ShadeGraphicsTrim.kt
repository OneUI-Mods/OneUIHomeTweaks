package dev.ryuya.oneuihometweaks

import android.content.ComponentCallbacks2
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.view.Display
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** Keeps the shade's GPU cache through a quick reopen on the measured SystemUI build. */
internal object ShadeGraphicsTrim {
    private const val CLOSED_IDLE_MS = 1_000L
    // In the physical trace, both OS visibility notices followed the close request within 17 ms.
    private const val RELATED_VISIBILITY_NOTICE_MS = 200L
    private const val UI_HIDDEN = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

    fun install(loader: ClassLoader) {
        if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.matchesClockBuild(37, Build.DISPLAY)) return
        try {
            installHooks(loader)
            HookEntry.log("SHADE_TRIM_DEBOUNCE_READY")
        } catch (error: Exception) {
            HookEntry.log("SHADE_TRIM_DEBOUNCE_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun installHooks(loader: ClassLoader) {
        val shade = DexSymbols.findClass("com.android.systemui.shade.ShadeControllerImpl", loader)
        val closeShade = shade.compatibleDeclaredMethod("makeExpandedInvisible")
        val displayId = shade.compatibleDeclaredMethod("getDisplayId\$2")
        val expandedVisible = shade.compatibleDeclaredField("mExpandedVisible")
        val keyguard = shade.compatibleDeclaredField("mKeyguardStateController")
        val keyguardImpl = DexSymbols.findClass(
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl", loader,
        )
        val showing = keyguardImpl.compatibleDeclaredField("mShowing")
        val trim = DexSymbols.findClass("android.view.WindowManagerGlobal", loader)
            .compatibleDeclaredMethod("trimMemory", Int::class.javaPrimitiveType)
        val activityThread = if (BuildConfig.SHADE_TRIM_MERGE_VISIBILITY) {
            DexSymbols.findClass("android.app.ActivityThread", loader).also { type ->
                ModernHook.deoptimize(type.compatibleDeclaredMethod("handleTrimMemory", Int::class.javaPrimitiveType))
            }
        } else {
            null
        }
        val main = Looper.getMainLooper()
        val handler = Handler(main)
        var closingShade: Any? = null
        var pendingTrim: Runnable? = null
        var trimScheduledAt = 0L

        ModernHook.onGenerationRetired {
            pendingTrim?.let(handler::removeCallbacks)
            pendingTrim = null
        }

        ModernHook.deoptimize(closeShade)
        val trimHook = ModernHook.aroundMethod(trim) { chain ->
            if (Looper.myLooper() !== main || chain.args[0] != UI_HIDDEN) return@aroundMethod chain.proceed()
            val controller = closingShade
            if (controller == null) {
                if (activityThread != null && pendingTrim != null &&
                    SystemClock.uptimeMillis() - trimScheduledAt <= RELATED_VISIBILITY_NOTICE_MS &&
                    Thread.currentThread().stackTrace.any { frame ->
                        frame.className == activityThread.name && frame.methodName == "handleTrimMemory"
                    }
                ) {
                    // ActivityThread has already delivered onTrimMemory to its components.
                    mark("OneUI shade trim coalesced with OS visibility notice")
                    return@aroundMethod null
                }
                return@aroundMethod chain.proceed()
            }
            val windowManager = chain.thisObject ?: return@aroundMethod chain.proceed()
            val defaultDisplay = runCatching { displayId.invoke(controller) == Display.DEFAULT_DISPLAY }
            if (defaultDisplay.getOrDefault(false) != true) return@aroundMethod chain.proceed()
            val delayed = Runnable {
                pendingTrim = null
                try {
                    if (!expandedVisible.getBoolean(controller) && !showing.getBoolean(keyguard.get(controller))) {
                        val traced = Trace.isEnabled()
                        if (traced) Trace.beginSection("OneUI shade trim after collapse idle")
                        try {
                            trim.invoke(windowManager, UI_HIDDEN)
                        } finally {
                            if (traced) Trace.endSection()
                        }
                    }
                } catch (error: ReflectiveOperationException) {
                    HookEntry.log("SHADE_TRIM_DEBOUNCE_FAILED ${error.javaClass.simpleName}")
                }
            }
            if (!handler.postDelayed(delayed, CLOSED_IDLE_MS)) return@aroundMethod chain.proceed()
            pendingTrim?.let(handler::removeCallbacks)
            pendingTrim = delayed
            trimScheduledAt = SystemClock.uptimeMillis()
            mark("OneUI shade trim postponed")
            null
        }
        try {
            ModernHook.aroundMethod(closeShade) { chain ->
                val controller = chain.thisObject
                if (Looper.myLooper() !== main || controller == null) {
                    return@aroundMethod chain.proceed()
                }
                val previous = closingShade
                closingShade = controller
                try {
                    chain.proceed()
                } finally {
                    closingShade = previous
                }
            }
        } catch (error: Exception) {
            trimHook.unhook()
            throw error
        }
    }

    private fun mark(name: String) {
        if (!Trace.isEnabled()) return
        Trace.beginSection(name)
        Trace.endSection()
    }
}
