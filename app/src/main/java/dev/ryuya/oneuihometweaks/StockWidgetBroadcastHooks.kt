package dev.ryuya.oneuihometweaks

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Trace
import android.os.UserHandle
import android.widget.RemoteViews
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.reflect.InvocationTargetException

/** Moves BZI9's widget broadcasts out of the critical section that prepares them. */
internal object StockWidgetBroadcastHooks {
    private const val LAUNCHER = "com.sec.android.app.launcher"
    @Volatile private var dispatcher: StockWidgetBroadcastQueue? = null

    fun hasPending(): Boolean = dispatcher?.hasPending() == true

    fun install(loader: ClassLoader) {
        if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.matchesClockBuild(37, Build.DISPLAY)) return
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            val service = DexSymbols.findClass("com.android.server.appwidget.AppWidgetServiceImpl", false, loader)
            val widget = DexSymbols.findClass("com.android.server.appwidget.AppWidgetServiceImpl\$Widget", false, loader)
            val update = service.compatibleDeclaredMethod(
                "updateAppWidgetOptions", String::class.java, Int::class.javaPrimitiveType, Bundle::class.java,
            )
            val send = service.compatibleDeclaredMethod(
                "sendBroadcastAsUser", Intent::class.java, UserHandle::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val optionsChanged = service.compatibleDeclaredMethod("sendOptionsChangedIntentLocked", widget)
            val lock = service.compatibleDeclaredField("mLock").apply { isAccessible = true }
            val background = service.compatibleDeclaredField("mSaveStateHandler").apply { isAccessible = true }
            val prefix = "com.android.server.appwidget.AppWidgetServiceImpl"
            val provider = DexSymbols.findClass("$prefix\$Provider", false, loader)
            val host = DexSymbols.findClass("$prefix\$Host", false, loader)
            val restore = DexSymbols.findClass("$prefix\$BackupRestoreController", false, loader)
            val callback = DexSymbols.findClass("com.android.internal.appwidget.IAppWidgetHost", false, loader)
            val callers = listOf(
                update, optionsChanged,
                service.compatibleDeclaredMethod("deleteAppWidgetLocked", widget),
                service.compatibleDeclaredMethod("sendEnableAndUpdateIntentLocked", provider, IntArray::class.java),
                service.compatibleDeclaredMethod("sendUpdateIntentLocked", provider, IntArray::class.java,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
                service.compatibleDeclaredMethod("-\$\$Nest\$mupdateWidgetPackageStoppedMaskedState",
                    service, Intent::class.java),
                service.compatibleDeclaredMethod("-\$\$Nest\$mhandleNotifyUpdateAppWidget",
                    service, host, callback, Int::class.javaPrimitiveType, RemoteViews::class.java,
                    Long::class.javaPrimitiveType),
                restore.compatibleDeclaredMethod("sendWidgetRestoreBroadcastLocked", String::class.java,
                    provider, host, IntArray::class.java, IntArray::class.java, UserHandle::class.java),
            )
            check(callers.all(ModernHook::deoptimize)) {
                "Widget service may inline its broadcast sender"
            }

            val launcherOptions = ThreadLocal<Any>()
            handles += ModernHook.aroundMethod(send) { chain ->
                val receiver = chain.thisObject ?: return@aroundMethod chain.proceed()
                val stateLock = lock.get(receiver) ?: return@aroundMethod chain.proceed()
                if (!Thread.holdsLock(stateLock)) return@aroundMethod chain.proceed()
                val intent = chain.args[0] as? Intent ?: return@aroundMethod chain.proceed()
                val startsBatch = launcherOptions.get() === receiver &&
                    intent.action == AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED
                if (!startsBatch && !hasPending()) return@aroundMethod chain.proceed()

                val user = chain.args[1] as? UserHandle ?: return@aroundMethod chain.proceed()
                val interactive = chain.args[2] as Boolean
                val queue = dispatcher ?: run {
                    val handler = background.get(receiver) as? Handler ?: return@aroundMethod chain.proceed()
                    StockWidgetBroadcastQueue(stateLock, handler) { error ->
                        val cause = (error as? InvocationTargetException)?.targetException ?: error
                        HookEntry.log("STOCK_WIDGET_BROADCAST_FAILED ${cause.javaClass.simpleName}")
                    }.also { dispatcher = it }
                }
                if (!queue.enqueue(stateLock, intent) { snapshot ->
                        trace("OneUI stock widget broadcast dispatch") {
                            send.invoke(receiver, snapshot, user, interactive)
                        }
                    }
                ) {
                    return@aroundMethod chain.proceed()
                }
                trace("OneUI stock widget broadcast queued") { null }
            }
            ModernHook.onGenerationRetired { dispatcher = null }
            handles += ModernHook.aroundMethod(update) { chain ->
                val receiver = chain.thisObject ?: return@aroundMethod chain.proceed()
                if (chain.args[0] != LAUNCHER || launcherOptions.get() != null) {
                    return@aroundMethod chain.proceed()
                }
                launcherOptions.set(receiver)
                try {
                    chain.proceed()
                } finally {
                    launcherOptions.remove()
                }
            }
            HookEntry.log("STOCK_WIDGET_BROADCAST_WORKER_READY")
        } catch (error: Exception) {
            handles.asReversed().forEach { it.unhook() }
            HookEntry.log("STOCK_WIDGET_BROADCAST_WORKER_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private inline fun <T> trace(name: String, block: () -> T): T {
        if (!Trace.isEnabled()) return block()
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }
}
