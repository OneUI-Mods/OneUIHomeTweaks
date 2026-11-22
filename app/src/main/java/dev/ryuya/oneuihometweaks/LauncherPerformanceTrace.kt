package dev.ryuya.oneuihometweaks

import android.graphics.Canvas
import android.os.Build
import android.os.Looper
import android.os.Trace
import android.view.View
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/** Local measurements of the stock transition and widget drawing on the investigated build. */
internal object LauncherPerformanceTrace {
    fun install(loader: ClassLoader) {
        if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.matchesClockBuild(37, Build.DISPLAY)) return
        try {
            val blur = DexSymbols.findClass("com.honeyspace.transition.anim.BlurAnimator", loader)
            val progressType = DexSymbols.findClass(
                "com.honeyspace.sdk.transition.entity.ProgressState\$Type", loader,
            )
            val applyBlur = blur.compatibleDeclaredMethod(
                "applyBlur", Float::class.javaPrimitiveType, progressType, Boolean::class.javaPrimitiveType,
            )
            traceBlurFilter(loader, applyBlur)
            trace(blur.compatibleDeclaredMethod("reset")) { "OneUI home BlurAnimator.reset" }
            val widget = DexSymbols.findClass(
                "com.honeyspace.ui.common.widget.HoneyAppWidgetHostView", loader,
            )
            trace(widget.compatibleDeclaredMethod("draw", Canvas::class.java)) { chain ->
                val view = chain.thisObject as View
                "OneUI home widget draw ${view.width}x${view.height}"
            }
            HookEntry.log("LAUNCHER_PERF_TRACE_READY")
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_PERF_TRACE_UNAVAILABLE ${error.javaClass.simpleName}")
        }
        trackRecentsHandoff(loader)
    }

    private fun trackRecentsHandoff(loader: ClassLoader) {
        try {
            val overlay = DexSymbols.findClass("com.honeyspace.gesture.overlaywindow.LeashOverlayWindowImpl", loader)
            val recents = DexSymbols.findClass("com.android.quickstep.RecentsActivity", loader)
            val boundaries = listOf(
                overlay.compatibleDeclaredMethod("addGestureTaskOverlay") to "LeashOverlayWindow.addGestureTaskOverlay",
                overlay.compatibleDeclaredMethod("removeOverlayWindow") to "LeashOverlayWindow.removeOverlayWindow",
                recents.compatibleDeclaredMethod("onPause") to "RecentsActivity.onPause",
                recents.compatibleDeclaredMethod("onStop") to "RecentsActivity.onStop",
            )
            for ((method, label) in boundaries) {
                trace(method) { "OneUI home $label" }
            }
            HookEntry.log("LAUNCHER_PERF_HANDOFF_READY")
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_PERF_HANDOFF_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun traceBlurFilter(loader: ClassLoader, applyBlur: Method) {
        val filter = DexSymbols.findClass("com.samsung.android.graphics.RenderEffectImageFilter", loader)
        val build = filter.compatibleDeclaredMethod("build", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val main = Looper.getMainLooper()
        var inBlur = false
        var buildCount = 0
        ModernHook.aroundMethod(build) { chain ->
            if (Looper.myLooper() !== main || !inBlur) return@aroundMethod chain.proceed()
            buildCount++
            val width = chain.args[0] as Int
            if (width < 1000) return@aroundMethod chain.proceed()
            traceCall(chain, "OneUI home widget-sized blur ${width}x${chain.args[1]}")
        }
        ModernHook.aroundMethod(applyBlur) { chain ->
            if (Looper.myLooper() !== main || !Trace.isEnabled() || inBlur) {
                return@aroundMethod chain.proceed()
            }
            inBlur = true
            buildCount = 0
            try {
                traceCall(chain, "OneUI home BlurAnimator.applyBlur ${chain.args[1]}")
            } finally {
                inBlur = false
                Trace.setCounter("OneUI home blur filter builds", buildCount.toLong())
            }
        }
    }

    private fun trace(method: Method, label: (XposedInterface.Chain) -> String) {
        ModernHook.aroundMethod(method) { chain ->
            if (!Trace.isEnabled()) return@aroundMethod chain.proceed()
            traceCall(chain, label(chain))
        }
    }

    private fun traceCall(chain: XposedInterface.Chain, label: String): Any? {
        Trace.beginSection(label)
        try {
            return chain.proceed()
        } finally {
            Trace.endSection()
        }
    }
}
