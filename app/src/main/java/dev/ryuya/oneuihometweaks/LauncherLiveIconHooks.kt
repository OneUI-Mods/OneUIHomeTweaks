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
import dev.ryuya.oneuihometweaks.compat.compatibleMethod
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Runs the stock live icon's LauncherApps lookup outside the shared bitmap cache monitor. */
internal object LauncherLiveIconHooks {
    private const val HOME_UP = "com.samsung.android.app.homestar"
    private const val HOME_UP_VERSION = 1800005036L
    private const val MANAGER = "com.honeyspace.common.iconview.LiveIconManager"
    private const val SUPPLIER = "com.honeyspace.ui.common.iconview.LiveIconSupplier"

    // Home Up bundles its own LiveIconSupplier copy, while its loader may resolve LiveIconManager to the launcher's.
    private val hookedSuppliers = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Class<*>, Boolean>())
    // One lookup lock and bitmap generation per stock cache, shared by every supplier copy using that cache.
    private val managerStates = java.util.WeakHashMap<Class<*>, ManagerState>()

    private class ManagerState(val bitmaps: StockBitmaps?) {
        val clock = Any()
        val calendar = Any()
    }

    fun install(app: Application, loader: ClassLoader) {
        if (!matchesLauncher(app)) return
        try {
            install(loader, "LAUNCHER", folderObserver = true)
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_LIVE_ICON_LOOKUP_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /** Called with a class loaded by the Home Up plugin loader once Home Up's workspace is discovered. */
    fun installHomeUp(app: Application, pluginClass: Class<*>) {
        val loader = pluginClass.classLoader ?: return
        try {
            if (!matchesLauncher(app)) return
            @Suppress("DEPRECATION")
            if (app.packageManager.getPackageInfo(HOME_UP, 0).longVersionCode != HOME_UP_VERSION) return
            val supplier = DexSymbols.findClass(SUPPLIER, loader)
            if (synchronized(hookedSuppliers) { supplier in hookedSuppliers }) {
                HookEntry.log("LAUNCHER_LIVE_ICON_HOME_UP_SHARED")
                return
            }
            install(loader, "HOME_UP", folderObserver = false)
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_LIVE_ICON_HOME_UP_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun matchesLauncher(app: Application): Boolean {
        if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.matchesClockBuild(37, Build.DISPLAY)) return false
        return try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            HookCompatibility.matchesLauncherVersion(37, version)
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_LIVE_ICON_LOOKUP_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
            false
        }
    }

    private fun install(loader: ClassLoader, host: String, folderObserver: Boolean) {
        val tag = if (host == "LAUNCHER") "" else " host=$host"
        val manager = DexSymbols.findClass(MANAGER, loader)
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
        val supplier = DexSymbols.findClass(SUPPLIER, loader)
        val updateView = supplier.compatibleDeclaredMethod("updateIcon", View::class.java)
        check(fetch.returnType == Drawable::class.java && update.returnType == Boolean::class.javaPrimitiveType)
        check(getIcon.returnType == Bitmap::class.java && createKey.returnType == key && getType.returnType == type)
        check(getUser.returnType == UserHandle::class.java && formatter.returnType == DateTimeFormatter::class.java)
        check(updatedAt.returnType == String::class.java)
        val nativeManager = managerInstance.get(null)
        @Suppress("UNCHECKED_CAST")
        val cache = getIcons.invoke(nativeManager) as MutableMap<Any, Any>
        synchronized(hookedSuppliers) {
            check(hookedSuppliers.add(supplier)) { "supplier already hooked" }
        }
        var sharedCache = true
        val state = synchronized(managerStates) {
            managerStates.getOrPut(manager) {
                sharedCache = false
                ManagerState(if (BuildConfig.LAUNCHER_PREPARE_LIVE_ICON_BITMAPS) {
                    try {
                        StockBitmaps(loader, manager, icon)
                    } catch (failure: ReflectiveOperationException) {
                        HookEntry.log("LAUNCHER_LIVE_ICON_BITMAP_PREPARATION_UNAVAILABLE ${failure.javaClass.simpleName}")
                        null
                    }
                } else null)
            }
        }
        if (sharedCache) HookEntry.log("LAUNCHER_LIVE_ICON_CACHE_SHARED$tag")
        val bitmaps = state.bitmaps
        val main = Looper.getMainLooper()
        val clock = state.clock
        val calendar = state.calendar
        val currentLookup = ThreadLocal<Lookup>()
        val traceBoundaries = BuildConfig.LAUNCHER_DEOPTIMIZE_LIVE_ICON_CALLERS
        check(ModernHook.deoptimize(updateView) && ModernHook.deoptimize(update) && ModernHook.deoptimize(getIcon))
        fun canPrepare() = Looper.myLooper() !== main && !Thread.holdsLock(cache) && currentLookup.get() == null

        fun proceedWithLookup(
            owner: Any, context: Context, target: Any, kind: Enum<*>, nativeKey: Any,
            proceed: () -> Any?,
        ): Any? {
            val lock = when (kind.name) {
                "CLOCK" -> clock
                "CALENDAR" -> calendar
                else -> return proceed()
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
                        if (traceBoundaries) mark("One UI Home live icon current $kind")
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

        fun proceedWithLookup(chain: Chain, context: Context, target: Any, kind: Enum<*>, nativeKey: Any,
            proceed: () -> Any? = { chain.proceed() }): Any? {
            val owner = chain.thisObject ?: return chain.proceed()
            return proceedWithLookup(owner, context, target, kind, nativeKey, proceed)
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
            if (bitmaps != null && !sharedCache) {
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
                if (traceBoundaries) mark("One UI Home live icon cache updater $kind")
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
        if (bitmaps != null && BuildConfig.LAUNCHER_DEOPTIMIZE_LIVE_ICON_CALLERS) {
            try {
                val appItem = DexSymbols.findClass("com.honeyspace.sdk.source.entity.AppItem", loader)
                val componentField = supplier.compatibleDeclaredField("appItem").apply { isAccessible = true }
                val readComponent = appItem.compatibleMethod("getComponent").apply { isAccessible = true }
                val supplierType = supplier.compatibleDeclaredMethod("getType").apply { isAccessible = true }
                val companionField = supplier.declaredFields.single {
                    Modifier.isStatic(it.modifiers) && it.type == companion
                }.apply { isAccessible = true }
                val companionInstance = companionField.get(null)
                check(WeakReference::class.java.isAssignableFrom(componentField.type))
                check(readComponent.returnType == component && supplierType.returnType == type)
                check(companion.isInstance(companionInstance))
                deoptimizeSupplierCallers(loader, supplier, host, folderObserver)
                ModernHook.aroundMethod(updateView) { chain ->
                    if (!canPrepare()) {
                        mark("One UI Home live icon supplier under prior scope$tag")
                        return@aroundMethod chain.proceed()
                    }
                    val instance = chain.thisObject ?: return@aroundMethod chain.proceed()
                    val view = chain.args[0] as? View ?: return@aroundMethod chain.proceed()
                    val item = (componentField.get(instance) as? WeakReference<*>)?.get()
                    if (!appItem.isInstance(item)) return@aroundMethod chain.proceed()
                    val target = readComponent.invoke(item) ?: return@aroundMethod chain.proceed()
                    val kind = supplierType.invoke(instance) as? Enum<*> ?: return@aroundMethod chain.proceed()
                    mark("One UI Home live icon supplier $kind$tag")
                    val nativeKey = makeKey.newInstance(kind, getUser.invoke(target))
                    // Keep the stock supplier responsible for binding and notifying the View.
                    // Preparing at this boundary also covers a compiled call to the cache updater.
                    proceedWithLookup(companionInstance, view.context, target, kind, nativeKey) { chain.proceed() }
                }
                HookEntry.log("LAUNCHER_LIVE_ICON_SUPPLIER_BOUNDARY_READY$tag")
            } catch (error: Exception) {
                HookEntry.log("LAUNCHER_LIVE_ICON_SUPPLIER_BOUNDARY_UNAVAILABLE$tag ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        HookEntry.log("LAUNCHER_LIVE_ICON_LOOKUP_READY$tag")
    }

    private fun mark(name: String) {
        if (!Trace.isEnabled()) return
        Trace.beginSection(name)
        Trace.endSection()
    }

    private fun deoptimizeSupplierCallers(loader: ClassLoader, supplier: Class<*>, host: String, folderObserver: Boolean) {
        try {
            // These two BZI9 coroutine methods call the synthetic accessor instead of calling updateIcon directly.
            val accessor = supplier.getDeclaredMethod("access\$updateIcon", supplier, View::class.java)
            val callers = listOf("\$get\$1\$1", "\$setUpdateJob\$1").map { suffix ->
                DexSymbols.findClass(supplier.name + suffix, loader).getDeclaredMethod("invokeSuspend", Any::class.java)
            }
            // Only the launcher's own dex has the folder observer that calls the cache updater directly.
            val observer = if (folderObserver) {
                listOf(DexSymbols.findClass("jb.b", loader).getDeclaredMethod("invoke", Any::class.java))
            } else emptyList()
            val methods = callers + accessor + observer
            val deoptimized = methods.count(ModernHook::deoptimize)
            val tag = if (host == "LAUNCHER") "" else " host=$host"
            HookEntry.log("LAUNCHER_LIVE_ICON_CALLERS_DEOPTIMIZED count=$deoptimized expected=${methods.size}$tag")
        } catch (failure: ReflectiveOperationException) {
            HookEntry.log("LAUNCHER_LIVE_ICON_CALLERS_UNAVAILABLE ${failure.javaClass.simpleName} host=$host")
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
