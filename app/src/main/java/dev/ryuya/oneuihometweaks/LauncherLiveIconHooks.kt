package dev.ryuya.oneuihometweaks

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Looper
import android.os.Trace
import android.os.UserHandle
import android.view.View
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Method
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Runs the stock live icon's LauncherApps lookup outside the shared bitmap cache monitor. */
internal object LauncherLiveIconHooks {
    fun install(app: Application, loader: ClassLoader) {
        if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.matchesClockBuild(37, Build.DISPLAY)) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (!HookCompatibility.matchesLauncherVersion(37, version)) return
            install(loader)
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_LIVE_ICON_LOOKUP_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun install(loader: ClassLoader) {
        val manager = DexSymbols.findClass("com.honeyspace.common.iconview.LiveIconManager", loader)
        val type = DexSymbols.findClass("${manager.name}\$Type", loader)
        val key = DexSymbols.findClass("${manager.name}\$LiveIconKey", loader)
        val icon = DexSymbols.findClass("${manager.name}\$Icon", loader)
        val component = DexSymbols.findClass("com.honeyspace.sdk.source.entity.ComponentKey", loader)
        val companion = DexSymbols.findClass("com.honeyspace.ui.common.iconview.LiveIconSupplier\$Companion", loader)
        val getIcons = manager.compatibleDeclaredMethod("getIcons").apply { isAccessible = true }
        val managerInstance = manager.compatibleDeclaredField("INSTANCE").apply { isAccessible = true }
        val createKey = manager.compatibleDeclaredMethod("createKey", component).apply { isAccessible = true }
        val makeKey = key.getDeclaredConstructor(type, UserHandle::class.java).apply { isAccessible = true }
        val getType = key.compatibleDeclaredMethod("getType").apply { isAccessible = true }
        val updatedAt = icon.compatibleDeclaredMethod("getUpdatedDateTime").apply { isAccessible = true }
        val formatter = type.compatibleDeclaredMethod("formatter").apply { isAccessible = true }
        val getUser = component.compatibleDeclaredMethod("getUser").apply { isAccessible = true }
        val fetch = companion.compatibleDeclaredMethod("getIconFromLauncherApps", Context::class.java, component)
            .apply { isAccessible = true }
        val update = companion.compatibleDeclaredMethod(
            "updateIconsIfNeeded", Context::class.java, type, String::class.java, component,
        )
        val getIcon = companion.compatibleDeclaredMethod(
            "getIcon", Context::class.java, component, Boolean::class.javaPrimitiveType,
        )
        val supplier = DexSymbols.findClass("com.honeyspace.ui.common.iconview.LiveIconSupplier", loader)
        val updateView = supplier.compatibleDeclaredMethod("updateIcon", View::class.java)
        check(fetch.returnType == Drawable::class.java && update.returnType == Boolean::class.javaPrimitiveType)
        check(getIcon.returnType == Bitmap::class.java && createKey.returnType == key && getType.returnType == type)
        check(getUser.returnType == UserHandle::class.java && formatter.returnType == DateTimeFormatter::class.java)
        check(updatedAt.returnType == String::class.java)
        val nativeManager = managerInstance.get(null)
        @Suppress("UNCHECKED_CAST")
        val cache = getIcons.invoke(nativeManager) as MutableMap<Any, Any>
        val bitmaps = if (BuildConfig.LAUNCHER_PREPARE_LIVE_ICON_BITMAPS) {
            try {
                StockBitmaps(loader, manager, icon)
            } catch (failure: ReflectiveOperationException) {
                HookEntry.log("LAUNCHER_LIVE_ICON_BITMAP_PREPARATION_UNAVAILABLE ${failure.javaClass.simpleName}")
                null
            }
        } else null
        val main = Looper.getMainLooper()
        val clock = Any()
        val calendar = Any()
        val currentLookup = ThreadLocal<Lookup>()
        check(ModernHook.deoptimize(updateView) && ModernHook.deoptimize(update) && ModernHook.deoptimize(getIcon))
        if (bitmaps != null && BuildConfig.LAUNCHER_DEOPTIMIZE_LIVE_ICON_CALLERS) {
            deoptimizeSupplierCallers(loader, supplier)
        }

        fun canPrepare() = Looper.myLooper() !== main && !Thread.holdsLock(cache) && currentLookup.get() == null

        fun proceedWithLookup(
            chain: Chain, context: Context, target: Any, kind: Enum<*>, nativeKey: Any,
            proceed: () -> Any? = { chain.proceed() },
        ): Any? {
            val owner = chain.thisObject ?: return chain.proceed()
            val lock = when (kind.name) {
                "CLOCK" -> clock
                "CALENDAR" -> calendar
                else -> return chain.proceed()
            }
            return synchronized(lock) {
                val candidate = try {
                    val dateFormatter = formatter.invoke(kind) as DateTimeFormatter
                    val now = dateFormatter.format(LocalDateTime.now())
                    val entry = synchronized(cache) {
                        val current = cache[nativeKey]
                        CacheEntry(current, current?.let { updatedAt.invoke(it) as String }, bitmaps?.generation ?: 0L)
                    }
                    if (entry.timestamp == now) {
                        null
                    } else {
                        val configuration = Configuration(context.resources.configuration)
                        Trace.beginSection("One UI Home live icon lookup outside cache monitor")
                        try {
                            (fetch.invoke(owner, context, target) as? Drawable)?.let {
                                val prepared = try {
                                    bitmaps?.prepare(it)
                                } catch (_: ReflectiveOperationException) {
                                    null
                                }
                                Lookup(context, target, configuration, dateFormatter, now, it, nativeKey, entry, prepared)
                            }
                        } finally {
                            Trace.endSection()
                        }
                    }
                } catch (_: ReflectiveOperationException) {
                    if (Trace.isEnabled()) Trace.setCounter("One UI Home live icon lookup reflection fallback", 1L)
                    null
                }
                if (candidate == null) return@synchronized proceed()
                // Let the native caller decide whether to store the drawable and notify its observers.
                currentLookup.set(candidate)
                try {
                    proceed()
                } finally {
                    currentLookup.remove()
                }
            }
        }

        val lookupHook = ModernHook.aroundMethod(fetch) { chain ->
            val candidate = currentLookup.get()
            if (candidate != null && chain.args[0] === candidate.context && chain.args[1] === candidate.component &&
                candidate.context.resources.configuration == candidate.configuration &&
                candidate.formatter.format(LocalDateTime.now()) == candidate.timestamp &&
                (bitmaps == null || synchronized(cache) { bitmaps.generation == candidate.entry.generation })
            ) return@aroundMethod candidate.drawable
            if (bitmaps == null || !Thread.holdsLock(cache) || !Trace.isEnabled()) return@aroundMethod chain.proceed()
            Trace.beginSection("One UI Home native live icon lookup holding cache monitor")
            try {
                chain.proceed()
            } finally {
                Trace.endSection()
            }
        }
        var updateHook: ModernHook.Handle? = null
        var invalidateHook: ModernHook.Handle? = null
        try {
            if (bitmaps != null) {
                invalidateHook = ModernHook.aroundMethod(bitmaps.clearIconMap) { chain ->
                    synchronized(cache) {
                        bitmaps.invalidate()
                        chain.proceed()
                    }
                }
            }
            updateHook = ModernHook.aroundMethod(update) { chain ->
                val context = chain.args[0] as? Context ?: return@aroundMethod chain.proceed()
                val kind = chain.args[1] as? Enum<*> ?: return@aroundMethod chain.proceed()
                val target = chain.args[3] ?: return@aroundMethod chain.proceed()
                val lastUpdated = chain.args[2] as? String ?: return@aroundMethod chain.proceed()
                val lookup = currentLookup.get()
                if (lookup == null && !canPrepare()) return@aroundMethod chain.proceed()
                val nativeKey = makeKey.newInstance(kind, getUser.invoke(target))
                if (lookup != null) {
                    return@aroundMethod bitmaps?.publish(cache, nativeKey, context, target, lastUpdated, lookup, updatedAt)
                        ?: chain.proceed()
                }
                proceedWithLookup(chain, context, target, kind, nativeKey) {
                    val preparedLookup = currentLookup.get()
                    preparedLookup?.let {
                        bitmaps?.publish(cache, nativeKey, context, target, lastUpdated, it, updatedAt)
                    } ?: chain.proceed()
                }
            }
            ModernHook.aroundMethod(getIcon) { chain ->
                if (!canPrepare() || chain.args[2] == true) return@aroundMethod chain.proceed()
                val context = chain.args[0] as? Context ?: return@aroundMethod chain.proceed()
                val target = chain.args[1] ?: return@aroundMethod chain.proceed()
                val nativeKey = createKey.invoke(nativeManager, target) ?: return@aroundMethod chain.proceed()
                val kind = getType.invoke(nativeKey) as? Enum<*> ?: return@aroundMethod chain.proceed()
                proceedWithLookup(chain, context, target, kind, nativeKey)
            }
        } catch (error: Exception) {
            updateHook?.unhook()
            invalidateHook?.unhook()
            lookupHook.unhook()
            throw error
        }
        HookEntry.log("LAUNCHER_LIVE_ICON_LOOKUP_READY")
    }

    private fun deoptimizeSupplierCallers(loader: ClassLoader, supplier: Class<*>) {
        try {
            // These two BZI9 coroutine methods call the synthetic accessor instead of calling updateIcon directly.
            val accessor = supplier.getDeclaredMethod("access\$updateIcon", supplier, View::class.java)
            val callers = listOf("\$get\$1\$1", "\$setUpdateJob\$1").map { suffix ->
                DexSymbols.findClass(supplier.name + suffix, loader).getDeclaredMethod("invokeSuspend", Any::class.java)
            }
            val deoptimized = (callers + accessor).count(ModernHook::deoptimize)
            HookEntry.log("LAUNCHER_LIVE_ICON_CALLERS_DEOPTIMIZED count=$deoptimized expected=3")
        } catch (failure: ReflectiveOperationException) {
            HookEntry.log("LAUNCHER_LIVE_ICON_CALLERS_UNAVAILABLE ${failure.javaClass.simpleName}")
        }
    }

    private data class Lookup(
        val context: Context,
        val component: Any,
        val configuration: Configuration,
        val formatter: DateTimeFormatter,
        val timestamp: String,
        val drawable: Drawable,
        val key: Any,
        val entry: CacheEntry,
        val prepared: PreparedBitmap?,
    )

    private data class CacheEntry(val icon: Any?, val timestamp: String?, val generation: Long)

    private data class PreparedBitmap(val size: Int, val bitmap: Bitmap)

    private class StockBitmaps(loader: ClassLoader, manager: Class<*>, icon: Class<*>) {
        val clearIconMap = manager.getDeclaredMethod("clearIconMap", String::class.java,
            DexSymbols.findClass("kotlin.jvm.functions.Function2", loader))
        var generation = 0L
            private set

        fun invalidate() {
            generation++
        }

        private val iconConstructor = icon.getDeclaredConstructor(Bitmap::class.java, String::class.java)
        private val setIcon = icon.getDeclaredMethod("setIcon", Bitmap::class.java)
        private val setTimestamp = icon.getDeclaredMethod("setUpdatedDateTime", String::class.java)
        private val sizing = DexSymbols.findClass("com.honeyspace.common.utils.IconBaseInfo", loader)
        private val sizingInstance = sizing.compatibleDeclaredField("INSTANCE").get(null)
        private val readSize = sizing.getDeclaredMethod("getRequiredIconSize")
        private val convert = DexSymbols.findClass("androidx.core.graphics.drawable.DrawableKt", loader)
            .getDeclaredMethod("toBitmap\$default", Drawable::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Bitmap.Config::class.java, Int::class.javaPrimitiveType, Any::class.java)

        private fun iconSize() = readSize.invoke(sizingInstance) as Int

        fun prepare(drawable: Drawable): PreparedBitmap {
            val size = iconSize()
            Trace.beginSection("One UI Home live icon bitmap outside cache monitor")
            try {
                val bitmap = convert.invoke(null, drawable, size, size, null, 4, null) as Bitmap
                return PreparedBitmap(size, bitmap)
            } finally {
                Trace.endSection()
            }
        }

        fun publish(cache: MutableMap<Any, Any>, key: Any, context: Context, component: Any,
            lastUpdated: String, lookup: Lookup, readTimestamp: Method): Boolean? {
            val prepared = lookup.prepared ?: return null
            if (lookup.context !== context || lookup.component !== component || lookup.key != key) return null
            synchronized(cache) {
                if (generation != lookup.entry.generation ||
                    context.resources.configuration != lookup.configuration || prepared.size != iconSize() ||
                    lookup.formatter.format(LocalDateTime.now()) != lookup.timestamp || prepared.bitmap.isRecycled
                ) return null
                val current = cache[key]
                val timestamp = current?.let { readTimestamp.invoke(it) as String }
                if (timestamp == lookup.timestamp) return lastUpdated != timestamp
                if (current !== lookup.entry.icon || timestamp != lookup.entry.timestamp) return null
                // Keep the stock entry identity and the supplier's Boolean update contract.
                if (current == null) {
                    cache[key] = iconConstructor.newInstance(prepared.bitmap, lookup.timestamp)
                } else {
                    setIcon.invoke(current, prepared.bitmap)
                    setTimestamp.invoke(current, lookup.timestamp)
                }
                if (Trace.isEnabled()) {
                    Trace.beginSection("One UI Home prepared live icon bitmap committed")
                    Trace.endSection()
                }
                return true
            }
        }
    }
}
