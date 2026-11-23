package dev.ryuya.oneuihometweaks

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Trace
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Future

/** Lets Settings render while its stock account profile query and image decode run off-main. */
internal class StockSettingsAccountProfileHooks(private val main: Handler) {
    private val sessions = WeakHashMap<Any, Session>()
    private var applying: ApplyingProfile? = null
    private var closed = false

    fun close() {
        closed = true
        sessions.values.forEach { it.task?.cancel(false) }
        sessions.clear()
    }

    fun install(loader: ClassLoader): Boolean {
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            val controller = DexSymbols.findClass(
                "com.samsung.android.settings.homepage.TopLevelSamsungAccountPreferenceController", false, loader,
            )
            val base = DexSymbols.findClass("com.android.settingslib.core.AbstractPreferenceController", false, loader)
            val contextField = base.compatibleDeclaredField("mContext").apply { isAccessible = true }
            val preference = controller.compatibleDeclaredField("mPreference").apply { isAccessible = true }
            val icon = controller.compatibleDeclaredField("mIcon").apply { isAccessible = true }
            val title = controller.compatibleDeclaredField("mTitle").apply { isAccessible = true }
            val summary = controller.compatibleDeclaredField("mSummary").apply { isAccessible = true }
            val update = controller.compatibleDeclaredMethod("updatePreference").apply { isAccessible = true }
            val accountExists = controller.compatibleDeclaredMethod("samsungAccountExists", Context::class.java)
            val queryProfile = controller.compatibleDeclaredMethod("getSamsungAccountProfile", Context::class.java)
                .apply { isAccessible = true }
            val constructor = controller.getDeclaredConstructor(Context::class.java, String::class.java)
            val threadUtils = DexSymbols.findClass("com.android.settingslib.utils.ThreadUtils", false, loader)
            val submit = threadUtils.compatibleDeclaredMethod("postOnBackgroundThread", Runnable::class.java)
            val rowClass = DexSymbols.findClass("androidx.preference.Preference", false, loader)
            val manager = DexSymbols.findClass("com.samsung.android.settings.mde.MDEServiceBindingManager", false, loader)
            val managerCallback = DexSymbols.findClass(
                "com.samsung.android.settings.mde.MDEServiceBindingManager\$CallbackHandler", false, loader,
            )
            check(contextField.type == Context::class.java && icon.type == Drawable::class.java)
            check(title.type == String::class.java && summary.type == String::class.java)
            check(Future::class.java.isAssignableFrom(submit.returnType))
            val callers = listOf(
                update,
                controller.compatibleDeclaredMethod("updateState", rowClass),
                controller.compatibleDeclaredMethod("lambda\$getProfileDataObserver\$1"),
                controller.compatibleDeclaredMethod("-\$\$Nest\$mupdatePreference", controller),
                manager.compatibleDeclaredMethod("deleteSuggestions"),
                managerCallback.compatibleDeclaredMethod("handleMessage", Message::class.java),
            )
            check(callers.all(ModernHook::deoptimize)) { "Stock account query may be inlined" }

            handles += ModernHook.aroundMethod(controller.compatibleDeclaredMethod("onStart")) { chain ->
                val owner = chain.thisObject
                if (owner != null && onMain()) sessions.getOrPut(owner) { Session() }
                chain.proceed()
            }
            handles += ModernHook.aroundMethod(controller.compatibleDeclaredMethod("onStop")) { chain ->
                if (onMain()) sessions.remove(chain.thisObject)?.task?.cancel(false)
                chain.proceed()
            }
            handles += ModernHook.aroundMethod(accountExists) { chain ->
                if (!onMain()) return@aroundMethod chain.proceed()
                val result = applying?.takeIf { it.owner === chain.thisObject }?.profile
                result?.let { it.accounts.isNotEmpty() } ?: chain.proceed()
            }
            handles += ModernHook.aroundMethod(queryProfile) { chain ->
                if (!onMain()) return@aroundMethod chain.proceed()
                val result = applying?.takeIf { it.owner === chain.thisObject }?.profile
                    ?: return@aroundMethod chain.proceed()
                title.set(chain.thisObject, result.title)
                summary.set(chain.thisObject, result.summary)
                result.icon?.let { icon.set(chain.thisObject, it) }
                null
            }
            handles += ModernHook.aroundMethod(update) { chain ->
                val owner = chain.thisObject ?: return@aroundMethod chain.proceed()
                if (!onMain() || closed || applying?.owner === owner) return@aroundMethod chain.proceed()
                val session = sessions[owner] ?: return@aroundMethod chain.proceed()
                val row = preference.get(owner) ?: return@aroundMethod chain.proceed()
                val context = contextField.get(owner) as? Context ?: return@aroundMethod chain.proceed()
                if (session.task != null) {
                    session.refresh = true
                    return@aroundMethod null
                }

                val snapshot = WeakReference(owner)
                val rowAtRequest = WeakReference(row)
                val configuration = Configuration(context.resources.configuration)
                val detached = constructor.newInstance(context, "top_level_samsung_account")
                val task = Runnable {
                    val result = try {
                        trace("OneUI Settings stock account profile query") {
                            val accounts = accounts(context)
                            if (accounts.isNotEmpty()) queryProfile.invoke(detached, context)
                            Profile(accounts, title.get(detached) as? String, summary.get(detached) as? String,
                                icon.get(detached) as? Drawable)
                        }
                    } catch (error: Exception) {
                        HookEntry.log("STOCK_SETTINGS_ACCOUNT_QUERY_FAILED ${error.javaClass.simpleName}")
                        null
                    }
                    main.post {
                        if (closed) return@post
                        val controllerInstance = snapshot.get() ?: return@post
                        if (sessions[controllerInstance] !== session) return@post
                        session.task = null
                        try {
                            val currentRow = rowAtRequest.get()
                            val unchanged = currentRow != null && currentRow === preference.get(controllerInstance) &&
                                configuration == context.resources.configuration
                            if (session.refresh || !unchanged) {
                                session.refresh = false
                                update.invoke(controllerInstance)
                                return@post
                            }
                            val verified = try {
                                result?.takeIf { it.accounts.contentEquals(accounts(context)) }
                            } catch (_: Exception) { null }
                            applying = ApplyingProfile(controllerInstance, verified)
                            trace("OneUI Settings apply account profile") { update.invoke(controllerInstance) }
                        } catch (error: Exception) {
                            HookEntry.log("STOCK_SETTINGS_ACCOUNT_APPLY_FAILED ${error.javaClass.simpleName}")
                        } finally {
                            applying = null
                        }
                    }
                }
                try {
                    val accepted = submit.invoke(null, task) as? Future<*> ?: return@aroundMethod chain.proceed()
                    session.task = accepted
                    null
                } catch (_: Exception) {
                    chain.proceed()
                }
            }
            HookEntry.log("STOCK_SETTINGS_ACCOUNT_PROFILE_WORKER_READY")
            return true
        } catch (error: Exception) {
            handles.asReversed().forEach { it.unhook() }
            HookEntry.log("STOCK_SETTINGS_ACCOUNT_PROFILE_WORKER_UNAVAILABLE ${error.javaClass.simpleName}")
            return false
        }
    }

    private fun onMain() = Looper.myLooper() === main.looper

    private fun accounts(context: Context): Array<Account> =
        AccountManager.get(context).getAccountsByType("com.osp.app.signin")

    private inline fun <T> trace(name: String, block: () -> T): T {
        if (!Trace.isEnabled()) return block()
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    private class Session(var task: Future<*>? = null, var refresh: Boolean = false)
    private class Profile(val accounts: Array<Account>, val title: String?, val summary: String?, val icon: Drawable?)
    private class ApplyingProfile(val owner: Any, val profile: Profile?)
}
