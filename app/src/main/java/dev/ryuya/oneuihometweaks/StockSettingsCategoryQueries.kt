package dev.ryuya.oneuihometweaks

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.os.Trace
import android.os.UserHandle
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Overlaps the two PackageManager reads in the initial Samsung Settings category scan. */
internal class StockSettingsCategoryQueries {
    private val discovery = ThreadLocal<Discovery>()
    private val worker = ThreadPoolExecutor(
        0, 1, 5L, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "SettingsProviderQuery") },
    )

    fun close() = worker.shutdown()

    fun install(loader: ClassLoader): Boolean {
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            val task = DexSymbols.findClass("com.android.settings.core.CategoryMixin\$CategoriesUpdateTask", false, loader)
            val initial = DexSymbols.findClass("com.android.settings.core.CategoryMixin\$SecCategoriesUpdateTask", false, loader)
            val tiles = DexSymbols.findClass("com.samsung.android.settings.core.SecTileUtils", false, loader)
            val packageManager = DexSymbols.findClass("android.app.ApplicationPackageManager", false, loader)
            val integer = Int::class.javaPrimitiveType
            val background = task.compatibleDeclaredMethod("doInBackground", Array<Any>::class.java)
            val scan = tiles.compatibleDeclaredMethod("getCategories", Context::class.java, Map::class.java)
            val action = tiles.compatibleDeclaredMethod("loadTilesForAction", Context::class.java, UserHandle::class.java,
                String::class.java, Map::class.java, List::class.java, Boolean::class.javaPrimitiveType)
            val activities = packageManager.compatibleDeclaredMethod("queryIntentActivitiesAsUser",
                Intent::class.java, integer, integer)
            val providers = packageManager.compatibleDeclaredMethod("queryIntentContentProvidersAsUser",
                Intent::class.java, integer, integer).apply { isAccessible = true }
            check(initial.superclass === task && action.returnType == Void.TYPE)
            check(listOf(activities, providers).all { List::class.java.isAssignableFrom(it.returnType) })
            check(listOf(scan, action, background).all(ModernHook::deoptimize))

            handles += ModernHook.aroundMethod(background) { chain ->
                val flags = chain.args.firstOrNull() as? Array<*>
                if (chain.thisObject?.javaClass !== initial || flags?.firstOrNull() != false ||
                    Looper.myLooper() === Looper.getMainLooper() || discovery.get() != null
                ) return@aroundMethod chain.proceed()
                discovery.set(Discovery())
                try {
                    trace("OneUI Settings initial category scan") { chain.proceed() }
                } finally {
                    discovery.remove()
                }
            }
            handles += ModernHook.aroundMethod(action) { chain ->
                val scope = discovery.get() ?: return@aroundMethod chain.proceed()
                if (scope.action != null) return@aroundMethod chain.proceed()
                val name = chain.args[2] as? String ?: return@aroundMethod chain.proceed()
                if (chain.args[1] !is UserHandle) return@aroundMethod chain.proceed()
                val inSettings = chain.args[5] == true
                val current = TileAction(name, if (inSettings) "com.android.settings" else null)
                scope.action = current
                try {
                    chain.proceed()
                } finally {
                    current.query?.result?.cancel(false)
                    scope.action = null
                }
            }
            handles += ModernHook.aroundMethod(activities) { chain ->
                val current = discovery.get()?.action ?: return@aroundMethod chain.proceed()
                val manager = chain.thisObject ?: return@aroundMethod chain.proceed()
                val intent = chain.args[0] as? Intent ?: return@aroundMethod chain.proceed()
                val userId = chain.args[2] as? Int ?: return@aroundMethod chain.proceed()
                if (current.query == null && chain.args[1] == PackageManager.GET_META_DATA && current.matches(intent)) {
                    val snapshot = Intent(intent)
                    val result = FutureTask<List<*>?> {
                        trace("OneUI Settings provider lookup in parallel") {
                            providers.invoke(manager, snapshot, 0, userId) as? List<*>
                        }
                    }
                    try {
                        worker.execute(result)
                        current.query = ProviderQuery(manager, intent, userId, result)
                    } catch (_: RejectedExecutionException) {
                        // Let Settings use its original lookup if the previous read is still in flight.
                    }
                }
                chain.proceed()
            }
            handles += ModernHook.aroundMethod(providers) { chain ->
                val current = discovery.get()?.action ?: return@aroundMethod chain.proceed()
                val query = current.query ?: return@aroundMethod chain.proceed()
                val intent = chain.args[0] as? Intent ?: return@aroundMethod chain.proceed()
                if (query.manager !== chain.thisObject || query.intent !== intent ||
                    chain.args[1] != 0 || chain.args[2] != query.userId || !current.matches(intent)
                ) return@aroundMethod chain.proceed()
                current.query = null
                val result = try {
                    // If the worker has not started, execute once on this thread instead.
                    query.result.run()
                    trace("OneUI Settings await providers") { query.result.get() }
                } catch (_: ExecutionException) {
                    null
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                }
                result ?: chain.proceed()
            }
            HookEntry.log("STOCK_SETTINGS_CATEGORY_QUERIES_READY")
            return true
        } catch (error: Exception) {
            handles.asReversed().forEach { it.unhook() }
            HookEntry.log("STOCK_SETTINGS_CATEGORY_QUERIES_UNAVAILABLE ${error.javaClass.simpleName}")
            return false
        }
    }

    private class Discovery {
        var action: TileAction? = null
    }

    private class TileAction(val name: String, val packageName: String?) {
        var query: ProviderQuery? = null

        fun matches(intent: Intent): Boolean =
            intent.action == name && intent.`package` == packageName && intent.data == null && intent.type == null &&
                intent.component == null && intent.categories == null && intent.selector == null &&
                intent.flags == 0 && intent.extras == null
    }

    private class ProviderQuery(
        val manager: Any,
        val intent: Intent,
        val userId: Int,
        val result: FutureTask<List<*>?>,
    )

    private inline fun <T> trace(name: String, action: () -> T): T {
        Trace.beginSection(name)
        return try {
            action()
        } finally {
            Trace.endSection()
        }
    }
}
