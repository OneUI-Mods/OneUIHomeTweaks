package dev.ryuya.oneuihometweaks

import android.app.Application
import android.os.Build
import android.os.Trace
import android.view.SurfaceControl
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/** Correlates a stock launcher transaction's writer, renderer callback, and ViewRoot merge. */
internal object LauncherStockTransactionTrace {
    fun install(app: Application, loader: ClassLoader) {
        if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.supportsRecentsFocus(37, Build.FINGERPRINT)) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (!HookCompatibility.matchesLauncherVersion(37, version)) return
            install(loader)
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_STOCK_TRANSACTION_TRACE_UNAVAILABLE ${error.javaClass.simpleName}")
        } catch (error: LinkageError) {
            HookEntry.log("LAUNCHER_STOCK_TRANSACTION_TRACE_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun install(loader: ClassLoader) {
        val applier = DexSymbols.findClass("com.honeyspace.transition.utils.SurfaceTransactionApplier", false, loader)
        val params = DexSymbols.findClass("com.honeyspace.transition.utils.SurfaceTransaction", false, loader)
        val schedule = applier.compatibleDeclaredMethod("scheduleApply", params)
        val wrapper = params.compatibleDeclaredMethod("getTransaction").apply { isAccessible = true }
        val native = wrapper.returnType.compatibleDeclaredField("mTransaction").apply { isAccessible = true }
        val id = SurfaceControl.Transaction::class.java.compatibleDeclaredMethod("getId").apply { isAccessible = true }
        val callback = applier.compatibleDeclaredMethod("scheduleApply\$lambda\$0",
            applier, Int::class.javaPrimitiveType, wrapper.returnType, Long::class.javaPrimitiveType)
        check(native.type == SurfaceControl.Transaction::class.java && id.returnType == Long::class.javaPrimitiveType)
        check(schedule.returnType == Void.TYPE && callback.returnType == Void.TYPE)

        val writers = listOf(
            Writer("com.honeyspace.gesture.recentsanimation.LeashUpdater", "apply", 2, "recents-leash"),
            Writer("com.honeyspace.transition.anim.floating.PlayerImpl", "setupTopWindowUpdater\$lambda\$2\$2",
                7, "floating-window"),
            Writer("com.honeyspace.transition.anim.floating.PlayerImpl", "setupDeskTopWindowUpdater\$lambda\$0\$0",
                3, "desktop-window"),
            Writer("com.honeyspace.transition.anim.floating.PlayerImpl", "targetsUpdate", 2, "floating-targets"),
            Writer("com.honeyspace.transition.utils.TaskLaunchTransition", "createTaskLeashAnimator\$lambda\$0",
                8, "task-launch"),
            Writer("com.honeyspace.transition.utils.DeskTaskLaunchTransition", "createTaskLeashAnimator\$lambda\$3",
                26, "desktop-task-launch"),
        ).mapNotNull { writer ->
            try {
                val type = DexSymbols.findClass(writer.owner, false, loader)
                val method = type.declaredMethods.singleOrNull {
                    it.name == writer.method && it.parameterCount == writer.parameters
                }
                if (method != null && ModernHook.deoptimize(method)) method to writer.label else null
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: LinkageError) {
                null
            }
        }
        check(writers.isNotEmpty()) { "No stock writer signatures were resolved" }
        val source = ThreadLocal<String>()
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            for ((method, label) in writers) {
                handles += ModernHook.aroundMethod(method, XposedInterface.PRIORITY_HIGHEST) { chain ->
                    if (!Trace.isEnabled()) return@aroundMethod chain.proceed()
                    val previous = source.get()
                    source.set(label)
                    try {
                        chain.proceed()
                    } finally {
                        if (previous == null) source.remove() else source.set(previous)
                    }
                }
            }
            handles += ModernHook.aroundMethod(schedule, XposedInterface.PRIORITY_HIGHEST) { chain ->
                if (!Trace.isEnabled()) return@aroundMethod chain.proceed()
                val key = try {
                    transactionKey(native.get(wrapper.invoke(chain.args[0])), id)
                } catch (_: ReflectiveOperationException) {
                    null
                } ?: return@aroundMethod chain.proceed()
                trace("OneUI stock tx schedule $key writer=${source.get() ?: "other"}") { chain.proceed() }
            }
            handles += ModernHook.aroundMethod(callback) { chain ->
                if (!Trace.isEnabled()) return@aroundMethod chain.proceed()
                val key = try {
                    transactionKey(native.get(chain.args[2]), id)
                } catch (_: ReflectiveOperationException) {
                    null
                } ?: return@aroundMethod chain.proceed()
                trace("OneUI stock tx render $key frame=${chain.args[3]} seq=${chain.args[1]}") { chain.proceed() }
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach { it.unhook() }
            throw error
        }
        HookEntry.log("LAUNCHER_STOCK_TRANSACTION_TRACE_READY ${writers.joinToString { it.second }}")
    }

    private fun transactionKey(value: Any?, id: Method): String? {
        val transaction = value as? SurfaceControl.Transaction ?: return null
        return "id=${id.invoke(transaction)} ref=${System.identityHashCode(transaction)}"
    }

    private inline fun <T> trace(label: String, action: () -> T): T {
        Trace.beginSection(label)
        try {
            return action()
        } finally {
            Trace.endSection()
        }
    }

    private class Writer(val owner: String, val method: String, val parameters: Int, val label: String)
}
