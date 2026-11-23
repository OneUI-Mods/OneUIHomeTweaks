package dev.ryuya.oneuihometweaks

import android.os.Looper
import android.os.Trace
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.reflect.Method
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/** Keeps the stock shutdown receiver and moves its synchronous registration off the UI thread. */
internal class LauncherFreeGridShutdownReceivers {
    private val pending = AtomicInteger()
    private var installed = false
    private val worker = BackgroundExecutors.serial("FreeGridShutdownReceiver", idleMillis = 10_000)

    fun idleForHotReload() = pending.get() == 0

    fun close() = worker.shutdown()

    @Synchronized
    fun install(workspace: Class<*>) {
        if (installed) return
        try {
            val loader = workspace.classLoader ?: return
            val viewModel = DexSymbols.findClass("com.honeyspace.ui.honeypots.freegrid.viewmodel.FreeGridViewModel", false, loader)
            val pot = DexSymbols.findClass("com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridWorkspacePot", false, loader)
            val register = viewModel.compatibleDeclaredMethod("registerBroadcast")
            val unregister = viewModel.compatibleDeclaredMethod("unRegisterBroadcast")
            val preview = viewModel.compatibleDeclaredMethod("isPreview").apply { isAccessible = true }
            check(register.returnType == Void.TYPE && unregister.returnType == Void.TYPE)
            check(preview.returnType == Boolean::class.javaPrimitiveType)
            check(ModernHook.deoptimize(pot.compatibleDeclaredMethod("createView")))
            check(ModernHook.deoptimize(viewModel.compatibleDeclaredMethod("onCleared")))

            val cleanup = installHook(unregister, preview, "OneUI FreeGrid unregister shutdown receiver")
            try {
                installHook(register, preview, "OneUI FreeGrid register shutdown receiver")
            } catch (error: Exception) {
                cleanup.unhook()
                throw error
            }
            installed = true
            HookEntry.log("FREEGRID_SHUTDOWN_RECEIVER_READY")
        } catch (error: Exception) {
            HookEntry.log("FREEGRID_SHUTDOWN_RECEIVER_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun installHook(method: Method, preview: Method, label: String): ModernHook.Handle {
        method.isAccessible = true
        return ModernHook.aroundMethod(method) { chain ->
            val owner = chain.thisObject ?: return@aroundMethod chain.proceed()
            if (Looper.myLooper() !== Looper.getMainLooper() || preview.invoke(owner) == true) {
                return@aroundMethod chain.proceed()
            }
            // Both lifecycle operations use the same queue to preserve their ordering.
            pending.incrementAndGet()
            try {
                worker.execute {
                    val tracing = Trace.isEnabled()
                    if (tracing) Trace.beginSection(label)
                    try {
                        method.invoke(owner)
                    } catch (error: ReflectiveOperationException) {
                        HookEntry.log("FREEGRID_SHUTDOWN_RECEIVER_FAILED ${error.javaClass.simpleName}")
                    } finally {
                        if (tracing) Trace.endSection()
                        pending.decrementAndGet()
                    }
                }
                null
            } catch (_: RejectedExecutionException) {
                pending.decrementAndGet()
                chain.proceed()
            }
        }
    }
}
