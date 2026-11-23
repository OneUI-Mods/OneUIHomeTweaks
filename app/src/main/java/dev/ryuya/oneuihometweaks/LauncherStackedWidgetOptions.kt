package dev.ryuya.oneuihometweaks

import android.app.Application
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Trace
import android.util.Size
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** BZI9's ten-argument size update can replace, or disregard, the old options. */
internal object LauncherStackedWidgetOptions {
    fun install(app: Application, loader: ClassLoader) {
        val sdk = Build.VERSION.SDK_INT
        if (sdk != 37 || !HookCompatibility.matchesClockBuild(sdk, Build.DISPLAY)) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (HookCompatibility.matchesLauncherVersion(sdk, version)) install(loader)
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_STACK_WIDGET_OPTIONS_UNAVAILABLE ${failure.javaClass.simpleName}: ${failure.message}")
        }
    }

    private fun install(loader: ClassLoader) {
        val widgetCondition = DexSymbols.findClass("com.honeyspace.common.data.widget.WidgetCondition", loader)
        val standardized = widgetCondition.compatibleDeclaredMethod("isStandardized").apply { isAccessible = true }
        val sizeUtility = DexSymbols.findClass("com.honeyspace.ui.common.widget.WidgetSizeUtilImpl", loader)
        val sizeParameters = arrayOf(
            Int::class.javaPrimitiveType, Context::class.java,
            Point::class.java, Point::class.java, Size::class.java, Boolean::class.javaPrimitiveType,
            widgetCondition, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        val updateSizes = sizeUtility.compatibleDeclaredMethod("updateWidgetSizeRanges", *sizeParameters)
        val updateMultipleSizes = sizeUtility.compatibleDeclaredMethod(
            "updateWidgetSizeRanges", *sizeParameters, Boolean::class.javaPrimitiveType,
        )
        val honeyWidgetHost = DexSymbols.findClass("com.honeyspace.ui.common.widget.HoneyAppWidgetHostView", loader)
        val bindParameters = arrayOf(Int::class.javaPrimitiveType, AppWidgetProviderInfo::class.java)
        val bind = honeyWidgetHost.compatibleDeclaredMethod("setAppWidget", *bindParameters)
        val baseBind = AppWidgetHostView::class.java.compatibleDeclaredMethod("setAppWidget", *bindParameters)
        val manager = AppWidgetManager::class.java
        val readOptions = manager.compatibleDeclaredMethod("getAppWidgetOptions", Int::class.javaPrimitiveType)
        val sendOptions = manager.compatibleDeclaredMethod(
            "updateAppWidgetOptions", Int::class.javaPrimitiveType, Bundle::class.java,
        )
        val label = AppWidgetProviderInfo::class.java.compatibleDeclaredMethod("loadLabel", PackageManager::class.java)
        check(ModernHook.deoptimize(updateMultipleSizes)) { "Widget sizing may inline the options read" }

        val main = Looper.getMainLooper()
        var tracingBind = false
        var options: OptionsRead? = null
        val installed = mutableListOf<ModernHook.Handle>()
        try {
            installed += ModernHook.aroundMethod(bind) { chain ->
                if (!Trace.isEnabled() || tracingBind || Looper.myLooper() !== main) return@aroundMethod chain.proceed()
                tracingBind = true
                try {
                    chain.proceed()
                } finally {
                    tracingBind = false
                }
            }
            for ((method, name) in listOf(baseBind to "OneUI widget platform bind", label to "OneUI widget load label")) {
                installed += ModernHook.aroundMethod(method) { chain ->
                    if (tracingBind && Looper.myLooper() === main) trace(name) { chain.proceed() }
                    else chain.proceed()
                }
            }
            for (method in listOf(updateSizes, updateMultipleSizes)) {
                val multipleSizeOverload = method === updateMultipleSizes
                val traceName = if (multipleSizeOverload) "OneUI widget size update 10" else "OneUI widget size update 9"
                installed += ModernHook.aroundMethod(method) { chain ->
                    if (options != null || Looper.myLooper() !== main) return@aroundMethod chain.proceed()
                    val args = chain.args
                    val id = args[0] as Int
                    if (id <= 0) return@aroundMethod chain.proceed()
                    val skipRead = multipleSizeOverload && (args[8] == true ||
                        (args[5] == true && args[9] == true && standardized.invoke(args[6]) == true))
                    if (!skipRead && !Trace.isEnabled()) return@aroundMethod chain.proceed()
                    options = OptionsRead(id, skipRead)
                    try {
                        trace(traceName) { chain.proceed() }
                    } finally {
                        options = null
                    }
                }
            }
            installed += ModernHook.aroundMethod(readOptions) { chain ->
                val call = options
                if (call == null || Looper.myLooper() !== main || chain.args[0] != call.id) {
                    return@aroundMethod chain.proceed()
                }
                if (call.skipOnce) {
                    call.skipOnce = false
                    trace("OneUI widget omit unused options read") { Bundle.EMPTY }
                } else {
                    trace("OneUI widget read options") { chain.proceed() }
                }
            }
            installed += ModernHook.aroundMethod(sendOptions) { chain ->
                if (options?.id == chain.args[0] && Looper.myLooper() === main) {
                    trace("OneUI widget send options") { chain.proceed() }
                } else chain.proceed()
            }
        } catch (failure: Exception) {
            installed.asReversed().forEach { it.unhook() }
            throw failure
        }
        HookEntry.log("LAUNCHER_STACK_WIDGET_OPTIONS_READY")
    }

    private class OptionsRead(val id: Int, var skipOnce: Boolean)

    private inline fun <T> trace(name: String, call: () -> T): T {
        if (!Trace.isEnabled()) return call()
        Trace.beginSection(name)
        try {
            return call()
        } finally {
            Trace.endSection()
        }
    }
}
