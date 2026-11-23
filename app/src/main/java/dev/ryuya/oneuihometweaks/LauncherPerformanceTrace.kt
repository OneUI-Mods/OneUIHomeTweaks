package dev.ryuya.oneuihometweaks

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RenderEffect
import android.os.Build
import android.os.Looper
import android.os.Trace
import android.view.View
import android.view.ViewGroup
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
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
        traceFloatingFrames(loader)
    }

    private fun traceFloatingFrames(loader: ClassLoader) {
        try {
            val player = DexSymbols.findClass("com.honeyspace.transition.anim.floating.PlayerImpl", false, loader)
            val apply = player.compatibleDeclaredMethod("apply", Float::class.javaPrimitiveType)
            val opening = player.compatibleDeclaredMethod("isForward").apply { isAccessible = true }
            val container = player.compatibleDeclaredMethod("getContainerView").apply { isAccessible = true }
            val original = player.compatibleDeclaredMethod("getOriginalView").apply { isAccessible = true }
            val widget = player.compatibleDeclaredField("containerWidgetView").apply { isAccessible = true }
            val output = player.compatibleDeclaredField("outputData").apply { isAccessible = true }
            val fields = listOf("PositionProgress", "WindowAlpha", "ContainerAlpha", "BgAlpha", "FgAlpha")
            val values = fields.map { output.type.compatibleDeclaredMethod("get$it").apply { isAccessible = true } }
            val counters = fields.map { "OneUI floating $it x1000" }
            ModernHook.aroundMethod(apply) { chain ->
                if (!Trace.isEnabled()) return@aroundMethod chain.proceed()
                val owner = chain.thisObject ?: return@aroundMethod chain.proceed()
                val data = output.get(owner) ?: return@aroundMethod chain.proceed()
                val icon = container.invoke(owner) as? ViewGroup
                val source = original.invoke(owner) as? View
                val isOpening = opening.invoke(owner) == true
                val orientation = source?.resources?.configuration?.orientation ?: 0
                val widgetView = widget.get(owner) as? View
                Trace.setCounter("OneUI floating branches", (
                    (if (isOpening) 1 else 0) or (if (icon != null) 2 else 0) or
                        (if (source != null) 4 else 0) or (if (widgetView != null) 8 else 0) or (orientation shl 4)
                    ).toLong())
                for (index in values.indices) {
                    Trace.setCounter(counters[index], ((values[index].invoke(data) as Float) * 1000).toLong())
                }
                val result = traceCall(chain, if (isOpening) "OneUI floating open draw" else "OneUI floating close draw")
                var visibleChildren = 0L
                if (icon != null && icon.isShown && icon.alpha > 0f) {
                    for (index in 0 until minOf(icon.childCount, 32)) {
                        val child = icon.getChildAt(index)
                        if (child.visibility == View.VISIBLE && child.alpha > 0f) {
                            visibleChildren = visibleChildren or (1L shl index)
                        }
                    }
                    if (icon.background != null) visibleChildren = visibleChildren or (1L shl 32)
                }
                Trace.setCounter("OneUI floating visible overlay children", visibleChildren)
                result
            }

            val applier = DexSymbols.findClass("com.honeyspace.transition.utils.SurfaceTransactionApplier", false, loader)
            val transaction = DexSymbols.findClass("com.honeyspace.transition.utils.SurfaceTransaction", false, loader)
            trace(applier.compatibleDeclaredMethod("scheduleApply", transaction)) { "OneUI send with next Launcher draw" }
            HookEntry.log("LAUNCHER_PERF_FLOATING_READY")
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_PERF_FLOATING_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
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
        val visibleBounds = Rect()
        val setRenderEffect = View::class.java.compatibleDeclaredMethod("setRenderEffect", RenderEffect::class.java)
        ModernHook.aroundMethod(setRenderEffect) { chain ->
            if (Looper.myLooper() !== main || !inBlur) return@aroundMethod chain.proceed()
            val view = chain.thisObject as? View ?: return@aroundMethod chain.proceed()
            if (view.width < 1000) return@aroundMethod chain.proceed()
            val visible = view.isShown && view.getGlobalVisibleRect(visibleBounds)
            val clipped = (view.parent as? ViewGroup)?.clipChildren == true
            val state = if (visible) "visible" else if (clipped) "offscreen clipped" else "offscreen unclipped"
            val flags = (if (chain.args[0] != null) 1 else 0) or (if (view.isAttachedToWindow) 2 else 0) or
                (if (view.isHardwareAccelerated) 4 else 0) or (if (view.isShown) 8 else 0)
            traceCall(chain, "OneUI home blur target $state $flags ${view.javaClass.simpleName} ${view.width}x${view.height}")
        }
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
