package dev.ryuya.oneuihometweaks

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Trace
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** Gives adjacent launcher size updates one stock AppWidgetService state snapshot. */
internal object StockWidgetSaveHooks {
    private const val LAUNCHER = "com.sec.android.app.launcher"
    private const val SAVE_WINDOW_MS = 32L

    fun install(loader: ClassLoader) {
        if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.matchesClockBuild(37, Build.DISPLAY)) return
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            val service = DexSymbols.findClass("com.android.server.appwidget.AppWidgetServiceImpl", false, loader)
            val saveState = service.compatibleDeclaredMethod("saveGroupStateAsync", Int::class.javaPrimitiveType)
            val updateOptions = service.compatibleDeclaredMethod(
                "updateAppWidgetOptions", String::class.java, Int::class.javaPrimitiveType, Bundle::class.java,
            )
            val lock = service.compatibleDeclaredField("mLock").apply { isAccessible = true }
            val saveHandler = service.compatibleDeclaredField("mSaveStateHandler").apply { isAccessible = true }
            check(ModernHook.deoptimize(updateOptions)) { "Widget size updates may inline the save request" }

            val launcherUpdate = ThreadLocal<LauncherUpdate>()
            handles += ModernHook.aroundMethod(saveState) { chain ->
                val instance = chain.thisObject ?: return@aroundMethod chain.proceed()
                val update = launcherUpdate.get() ?: return@aroundMethod chain.proceed()
                if (update.service !== instance) return@aroundMethod chain.proceed()
                val stateLock = lock.get(instance) ?: return@aroundMethod chain.proceed()
                if (!Thread.holdsLock(stateLock)) return@aroundMethod chain.proceed()
                // Let the service validate the caller and merge its Bundle before reading it.
                val options = update.options
                if (!options.containsKey("appWidgetSizes") ||
                    options.containsKey("appWidgetCategory") ||
                    options.containsKey("appWidgetRestoreCompleted") ||
                    options.getBoolean("android.appwidget.action.APPWIDGET_UPDATE", false)
                ) return@aroundMethod chain.proceed()
                val handler = saveHandler.get(instance) as? Handler ?: return@aroundMethod chain.proceed()
                val userId = chain.args[0] as Int

                // The save callback reads the latest state under this same lock, even if it
                // dequeues the pending message before this Binder call releases the lock.
                if (handler.hasMessages(userId)) {
                    mark("OneUI widget size save joined")
                } else if (handler.sendEmptyMessageDelayed(userId, SAVE_WINDOW_MS)) {
                    mark("OneUI widget size save queued")
                } else {
                    return@aroundMethod chain.proceed()
                }
                null
            }
            handles += ModernHook.aroundMethod(updateOptions) { chain ->
                val instance = chain.thisObject ?: return@aroundMethod chain.proceed()
                val options = chain.args[2] as? Bundle ?: return@aroundMethod chain.proceed()
                if (chain.args[0] != LAUNCHER || launcherUpdate.get() != null) {
                    return@aroundMethod chain.proceed()
                }

                launcherUpdate.set(LauncherUpdate(instance, options))
                try {
                    chain.proceed()
                } finally {
                    launcherUpdate.remove()
                }
            }
            HookEntry.log("STOCK_WIDGET_SIZE_SAVE_READY")
        } catch (error: Exception) {
            handles.asReversed().forEach { it.unhook() }
            HookEntry.log("STOCK_WIDGET_SIZE_SAVE_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private class LauncherUpdate(val service: Any, val options: Bundle)

    private fun mark(name: String) {
        if (Trace.isEnabled()) {
            Trace.beginSection(name)
            Trace.endSection()
        }
    }
}
