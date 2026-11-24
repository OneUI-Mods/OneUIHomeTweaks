package dev.ryuya.oneuihometweaks

import android.os.Build
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/**
 * BZI9 keeps up to 20 full-resolution task snapshots (about 18 MB of graphics memory each) for running
 * tasks. The stock cache already falls back to the persisted low-resolution copy when an entry is
 * missing, so keep only the most recent few and restore the stock limit when this generation retires.
 */
internal object StockSnapshotCacheTrim {
    const val KEY = "snapshot_keep"
    const val AOSP = 5
    const val MIN = 2
    const val MAX = 20

    /** Applies to the next snapshot the stock cache records. AOSP's default is five. */
    @Volatile var keep = AOSP
        set(value) {
            field = value.coerceIn(MIN, MAX)
        }

    fun install(loader: ClassLoader) {
        if (Build.VERSION.SDK_INT != 37) return
        val handles = mutableListOf<ModernHook.Handle>()
        var stockLimit = 0
        var limitField: java.lang.reflect.Field? = null
        try {
            val base = DexSymbols.findClass("com.android.server.wm.SnapshotCache", false, loader)
            val tasks = DexSymbols.findClass("com.android.server.wm.TaskSnapshotCache", false, loader)
            val entry = DexSymbols.findClass("com.android.server.wm.SnapshotCache\$CacheEntry", false, loader)
            val limit = base.compatibleDeclaredField("sMaxSnapshotCache").apply { isAccessible = true }
            val lock = base.compatibleDeclaredField("mLock").apply { isAccessible = true }
            val running = base.compatibleDeclaredField("mRunningCache").apply { isAccessible = true }
            val stamp = entry.compatibleDeclaredField("timestamp").apply { isAccessible = true }
            val remove = base.compatibleDeclaredMethod("removeRunningEntry", Integer::class.java)
                .apply { isAccessible = true }
            val put = tasks.declaredMethods.single { it.name == "putSnapshot" && !it.isBridge && !it.isSynthetic }
                .apply { isAccessible = true }
            limitField = limit
            stockLimit = limit.getInt(null)
            
            fun trim(cache: Any) {
                val target = keep
                limit.setInt(null, target)
                synchronized(lock.get(cache)!!) {
                    @Suppress("UNCHECKED_CAST")
                    val entries = running.get(cache) as android.util.ArrayMap<Int, Any>
                    while (entries.size > target) {
                        var oldest: Int? = null
                        var oldestAt = Long.MAX_VALUE
                        for (index in 0 until entries.size) {
                            val at = stamp.getLong(entries.valueAt(index))
                            if (at < oldestAt) {
                                oldestAt = at
                                oldest = entries.keyAt(index)
                            }
                        }
                        remove.invoke(cache, oldest ?: break)
                    }
                }
            }
            handles += ModernHook.aroundMethod(put) { chain ->
                val result = chain.proceed()
                try {
                    trim(chain.thisObject!!)
                } catch (error: Exception) {
                    HookEntry.log("STOCK_SNAPSHOT_TRIM_FAILED ${error.javaClass.simpleName}")
                }
                result
            }
            ModernHook.onGenerationRetired { limit.setInt(null, stockLimit) }
            HookEntry.log("STOCK_SNAPSHOT_TRIM_READY stock=$stockLimit keep=$keep")
        } catch (error: Exception) {
            handles.asReversed().forEach { it.unhook() }
            limitField?.let { runCatching { if (stockLimit > 0) it.setInt(null, stockLimit) } }
            HookEntry.log("STOCK_SNAPSHOT_TRIM_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
