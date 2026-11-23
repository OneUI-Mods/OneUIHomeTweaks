package dev.ryuya.oneuihometweaks

import android.os.Build
import android.os.Looper
import android.os.Trace
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.reflect.InvocationTargetException

/** Keeps BZI9's pass-through driver write on the observer thread, outside BatteryService's lock. */
internal object StockBatteryLockHooks {
    fun install(loader: ClassLoader) {
        if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.matchesClockBuild(37, Build.DISPLAY)) return
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            val battery = DexSymbols.findClass("com.android.server.BatteryService", false, loader)
            val observer = DexSymbols.findClass("com.android.server.BatteryService\$16", false, loader)
            val runnable = DexSymbols.findClass("com.android.server.BatteryService\$27", false, loader)
            val applySetting = runnable.compatibleDeclaredMethod(
                "run\$com\$android\$server\$BatteryService\$PassThroughSettingsObserver\$1",
            )
            val write = battery.compatibleDeclaredMethod("setPassThrough", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val owner = observer.compatibleDeclaredField("this\$0").apply { isAccessible = true }
            val callback = runnable.compatibleDeclaredField("val\$intent").apply { isAccessible = true }
            val lock = battery.compatibleDeclaredField("mLock").apply { isAccessible = true }
            check(ModernHook.deoptimize(applySetting)) { "Observer may inline the driver write" }
            check(ModernHook.deoptimize(runnable.compatibleDeclaredMethod("run"))) {
                "Observer dispatch may inline the settings callback"
            }

            val pending = ThreadLocal<PendingWrite>()
            handles += ModernHook.aroundMethod(write) { chain ->
                val writeInProgress = pending.get()
                if (writeInProgress == null || writeInProgress.requested != null ||
                    !Thread.holdsLock(writeInProgress.lock)
                ) return@aroundMethod chain.proceed()
                writeInProgress.requested = chain.args[0] as Boolean
                null
            }
            handles += ModernHook.aroundMethod(applySetting) { chain ->
                if (Looper.myLooper() !== Looper.getMainLooper() || pending.get() != null) {
                    return@aroundMethod chain.proceed()
                }
                val observerInstance = callback.get(chain.thisObject)
                if (!observer.isInstance(observerInstance)) return@aroundMethod chain.proceed()
                val batteryLock = lock.get(owner.get(observerInstance)) ?: return@aroundMethod chain.proceed()
                if (Thread.holdsLock(batteryLock)) return@aroundMethod chain.proceed()
                val deferred = PendingWrite(batteryLock)
                pending.set(deferred)
                try {
                    chain.proceed()
                } finally {
                    pending.remove()
                    deferred.requested?.let { requested ->
                        val tracing = Trace.isEnabled()
                        if (tracing) Trace.beginSection("OneUI battery pass-through write outside lock")
                        try {
                            write.invoke(null, requested)
                        } catch (wrapped: InvocationTargetException) {
                            throw wrapped.targetException
                        } finally {
                            if (tracing) Trace.endSection()
                        }
                    }
                }
            }
            HookEntry.log("STOCK_BATTERY_LOCK_READY")
        } catch (error: Exception) {
            handles.asReversed().forEach { it.unhook() }
            HookEntry.log("STOCK_BATTERY_LOCK_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private class PendingWrite(val lock: Any, var requested: Boolean? = null)
}
