package dev.ryuya.oneuihometweaks

import android.app.Application
import android.os.Build
import android.os.Looper
import android.os.Trace
import android.view.View
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** Requests the stock synchronized frame without discarding the root View's display list. */
internal object LauncherTransitionDisplayListHooks {
    fun install(app: Application, loader: ClassLoader) {
        val sdk = Build.VERSION.SDK_INT
        if (sdk != 37 || !HookCompatibility.matchesClockBuild(sdk, Build.DISPLAY)) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (HookCompatibility.matchesLauncherVersion(sdk, version)) install(loader)
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_TRANSITION_DISPLAY_LIST_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun install(loader: ClassLoader) {
        val player = DexSymbols.findClass("com.honeyspace.transition.anim.floating.PlayerImpl", loader)
        val playFrame = player.compatibleDeclaredMethod("apply", Float::class.javaPrimitiveType)
        val applier = DexSymbols.findClass("com.honeyspace.transition.utils.SurfaceTransactionApplier", loader)
        val transaction = DexSymbols.findClass("com.honeyspace.transition.utils.SurfaceTransaction", loader)
        val schedule = applier.compatibleDeclaredMethod("scheduleApply", transaction)
        val root = applier.compatibleDeclaredField("targetViewRootImpl").apply { isAccessible = true }
        val rootView = root.type.compatibleDeclaredMethod("getView").apply { isAccessible = true }
        val viewRoot = View::class.java.compatibleDeclaredMethod("getViewRootImpl").apply { isAccessible = true }
        val requestFrame = viewRoot.returnType.compatibleDeclaredMethod("invalidate").apply { isAccessible = true }
        val invalidateView = View::class.java.compatibleDeclaredMethod("invalidate")
        check(ModernHook.deoptimize(schedule)) { "scheduleApply may inline View.invalidate()" }

        val main = Looper.getMainLooper()
        var drawingFloatingFrame = false
        var target: View? = null
        var disabled = false
        val installed = mutableListOf<ModernHook.Handle>()
        try {
            installed += ModernHook.aroundMethod(invalidateView) { chain ->
                val view = target ?: return@aroundMethod chain.proceed()
                if (chain.thisObject !== view || Looper.myLooper() !== main || disabled) {
                    return@aroundMethod chain.proceed()
                }
                // Consume only the root invalidation requested by scheduleApply. Independent child
                // damage and the stock RenderThread callback still follow the normal path.
                target = null
                val androidRoot = viewRoot.invoke(view) ?: return@aroundMethod chain.proceed()
                val tracing = Trace.isEnabled()
                if (tracing) Trace.beginSection("OneUI floating reuse root display list")
                try {
                    requestFrame.invoke(androidRoot)
                } catch (error: ReflectiveOperationException) {
                    disabled = true
                    HookEntry.log("LAUNCHER_TRANSITION_DISPLAY_LIST_FALLBACK ${error.javaClass.simpleName}")
                    chain.proceed()
                } finally {
                    if (tracing) Trace.endSection()
                }
            }
            installed += ModernHook.aroundMethod(schedule) { chain ->
                if (Looper.myLooper() !== main || !drawingFloatingFrame || disabled || target != null) {
                    return@aroundMethod chain.proceed()
                }
                val view = rootView.invoke(root.get(chain.thisObject)) as? View
                if (view == null || !view.isHardwareAccelerated || !view.isAttachedToWindow) {
                    return@aroundMethod chain.proceed()
                }
                target = view
                try {
                    chain.proceed()
                } finally {
                    target = null
                }
            }
            installed += ModernHook.aroundMethod(playFrame) { chain ->
                if (Looper.myLooper() !== main || drawingFloatingFrame || disabled) return@aroundMethod chain.proceed()
                drawingFloatingFrame = true
                try {
                    chain.proceed()
                } finally {
                    drawingFloatingFrame = false
                }
            }
        } catch (error: Exception) {
            installed.asReversed().forEach { it.unhook() }
            throw error
        }
        HookEntry.log("LAUNCHER_TRANSITION_DISPLAY_LIST_READY")
    }
}
