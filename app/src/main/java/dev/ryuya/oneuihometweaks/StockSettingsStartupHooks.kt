package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Trace
import android.view.View
import android.view.ViewTreeObserver
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.util.Locale

/** Keeps the optional Samsung suggestions bind out of the Settings entrance animation. */
internal class StockSettingsStartupHooks {
    private val main = Handler(Looper.getMainLooper())
    private val suggestions = ComponentName(
        "com.samsung.android.smartsuggestions",
        "com.samsung.android.smartsuggestions.mde.MDEService",
    )
    private var starting: Start? = null
    private var pending: Binding? = null
    private var connecting: Binding? = null
    private var accountProfile: StockSettingsAccountProfileHooks? = null
    private var categoryQueries: StockSettingsCategoryQueries? = null

    fun readyForHotReload(): Boolean = pending == null && connecting == null

    fun close() {
        pending?.cancel()
        pending = null
        accountProfile?.close()
        accountProfile = null
        categoryQueries?.close()
        categoryQueries = null
    }

    fun install(app: Application, loader: ClassLoader) {
        if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.matchesClockBuild(37, Build.DISPLAY)) return
        @Suppress("DEPRECATION")
        if (app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode != 37L) return
        if (BuildConfig.STOCK_SETTINGS_ACCOUNT_PROFILE_WORKER) {
            accountProfile = StockSettingsAccountProfileHooks(main).takeIf { it.install(loader) }
        }
        if (BuildConfig.STOCK_SETTINGS_CATEGORY_QUERIES) {
            val queries = StockSettingsCategoryQueries()
            if (queries.install(loader)) categoryQueries = queries else queries.close()
        }
        try {
            installBindingHooks(loader)
        } catch (error: Exception) {
            HookEntry.log("STOCK_SETTINGS_SUGGESTIONS_UNAVAILABLE ${error.javaClass.simpleName}")
        }
        try {
            installHomePolicyLookup(loader)
        } catch (error: Exception) {
            HookEntry.log("STOCK_SETTINGS_HOME_POLICY_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun installHomePolicyLookup(loader: ClassLoader) {
        val manager = DexSymbols.findClass("com.samsung.android.settings.goodsettings.policy.PolicyManager", false, loader)
        val policy = DexSymbols.findClass("com.samsung.android.settings.goodsettings.policy.PolicyInfo", false, loader)
        val getPolicy = manager.compatibleDeclaredMethod("getPolicy", Context::class.java, String::class.java)
        val policies = manager.compatibleDeclaredField("mPolicies").apply { isAccessible = true }
        val locale = manager.compatibleDeclaredField("mPolicyLocale").apply { isAccessible = true }
        val lastUpdated = manager.compatibleDeclaredField("mLastUpdated").apply { isAccessible = true }
        val key = policy.compatibleDeclaredField("mKey").apply { isAccessible = true }
        check(getPolicy.returnType == policy && policies.type == ArrayList::class.java)
        check(locale.type == String::class.java && key.type == String::class.java && lastUpdated.type == Long::class.javaPrimitiveType)

        ModernHook.aroundMethod(getPolicy) { chain ->
            val name = "home_configuration_top_level_preferences"
            val owner = chain.thisObject
            if (chain.args[1] != name || owner == null || Looper.myLooper() !== main.looper) {
                return@aroundMethod chain.proceed()
            }
            val language = Locale.getDefault().language
            val current = try {
                val updatedAt = lastUpdated.getLong(owner)
                val all = policies.get(owner) as? ArrayList<*>
                if (locale.get(owner) == language && all != null) {
                    val match = all.firstOrNull { policy.isInstance(it) && key.get(it) == name }
                    match?.takeIf { lastUpdated.getLong(owner) == updatedAt && locale.get(owner) == language }
                } else null
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: ConcurrentModificationException) {
                null
            }
            if (current == null) return@aroundMethod chain.proceed()
            // The stock method builds all default preferences even when it returns a saved policy.
            // With no saved policy or a locale change, it still runs the original initialization.
            Trace.beginSection("OneUI Settings reused saved home policy")
            Trace.endSection()
            current
        }
        HookEntry.log("STOCK_SETTINGS_HOME_POLICY_READY")
    }

    private fun installBindingHooks(loader: ClassLoader) {
        val controller = DexSymbols.findClass(
            "com.samsung.android.settings.homepage.TopLevelSamsungAccountPreferenceController", false, loader,
        )
        val preference = DexSymbols.findClass(
            "com.android.settingslib.core.AbstractPreferenceController", false, loader,
        )
        val context = preference.compatibleDeclaredField("mContext").apply { isAccessible = true }
        val manager = controller.compatibleDeclaredField("mMDEServiceBindingManager").apply { isAccessible = true }
        val callbacks = manager.type.compatibleDeclaredField("mCallbacks").apply { isAccessible = true }
        val connection = manager.type.compatibleDeclaredField("mServiceConnection").apply { isAccessible = true }
        val clearSuggestions = manager.type.compatibleDeclaredMethod("deleteSuggestions").apply { isAccessible = true }
        val onStart = controller.compatibleDeclaredMethod("onStart")
        val onStop = controller.compatibleDeclaredMethod("onStop")
        val enterComplete = Activity::class.java.compatibleDeclaredMethod("dispatchEnterAnimationComplete")
        val onConnected = connection.type.compatibleDeclaredMethod(
            "onServiceConnected", ComponentName::class.java, IBinder::class.java,
        )
        val bind = DexSymbols.findClass("android.app.ContextImpl").compatibleDeclaredMethod(
            "bindService", Intent::class.java, ServiceConnection::class.java, Int::class.javaPrimitiveType,
        )
        check(bind.returnType == Boolean::class.javaPrimitiveType && clearSuggestions.returnType == Void.TYPE)
        check(context.type == Context::class.java && List::class.java.isAssignableFrom(callbacks.type))
        check(ServiceConnection::class.java.isAssignableFrom(connection.type))

        fun hasCallbacks(owner: Any): Boolean = (callbacks.get(owner) as List<*>).isNotEmpty()

        val bindHook = ModernHook.aroundMethod(bind) { chain ->
            if (Looper.myLooper() !== main.looper) return@aroundMethod chain.proceed()
            val start = starting ?: return@aroundMethod chain.proceed()
            val intent = chain.args[0] as? Intent ?: return@aroundMethod chain.proceed()
            val service = chain.args[1] as? ServiceConnection ?: return@aroundMethod chain.proceed()
            val flags = chain.args[2] as Int
            if (intent.component != suggestions || service !== start.connection ||
                flags != Context.BIND_AUTO_CREATE || pending != null
            ) return@aroundMethod chain.proceed()
            val bindingContext = chain.thisObject as? Context ?: return@aroundMethod chain.proceed()
            val binding = Binding(start, bindingContext, Intent(intent), flags,
                hasCallbacks = { hasCallbacks(start.manager) },
                onFailure = { clearSuggestions.invoke(start.manager) },
            )
            pending = binding
            binding.schedule()
            true
        }
        var startHook: ModernHook.Handle? = null
        var connectedHook: ModernHook.Handle? = null
        var enterHook: ModernHook.Handle? = null
        try {
            enterHook = ModernHook.aroundMethod(enterComplete) { chain ->
                val result = chain.proceed()
                if (Looper.myLooper() === main.looper) {
                    pending?.takeIf { it.start.activity === chain.thisObject }?.entranceComplete()
                }
                result
            }
            connectedHook = ModernHook.aroundMethod(onConnected) { chain ->
                try {
                    chain.proceed()
                } finally {
                    if (Looper.myLooper() === main.looper && connecting?.start?.connection === chain.thisObject) {
                        connecting = null
                    }
                }
            }
            startHook = ModernHook.aroundMethod(onStart) { chain ->
                if (Looper.myLooper() !== main.looper || starting != null) return@aroundMethod chain.proceed()
                val owner = chain.thisObject ?: return@aroundMethod chain.proceed()
                val nativeManager = manager.get(owner) ?: return@aroundMethod chain.proceed()
                val service = connection.get(nativeManager) as? ServiceConnection ?: return@aroundMethod chain.proceed()
                val activity = activityOf(context.get(owner) as? Context) ?: return@aroundMethod chain.proceed()
                val root = activity.window.peekDecorView() ?: return@aroundMethod chain.proceed()
                starting = Start(nativeManager, service, activity, root)
                try {
                    chain.proceed()
                } finally {
                    starting = null
                }
            }
            ModernHook.aroundMethod(onStop) { chain ->
                val result = chain.proceed()
                if (Looper.myLooper() === main.looper) {
                    val nativeManager = manager.get(chain.thisObject)
                    if (nativeManager != null && !hasCallbacks(nativeManager)) cancelFor(nativeManager)
                }
                result
            }
        } catch (error: Exception) {
            startHook?.unhook()
            connectedHook?.unhook()
            enterHook?.unhook()
            bindHook.unhook()
            throw error
        }
        HookEntry.log("STOCK_SETTINGS_SUGGESTIONS_READY")
    }

    private fun cancelFor(manager: Any) {
        pending?.takeIf { it.start.manager === manager }?.let { binding ->
            binding.cancel()
            pending = null
        }
        // Stock onStop only unbinds after onServiceConnected sets mBound.
        connecting?.takeIf { it.start.manager === manager }?.let { binding ->
            connecting = null
            binding.unbind()
        }
    }

    private fun activityOf(context: Context?): Activity? {
        var candidate = context
        repeat(8) {
            when (val current = candidate) {
                is Activity -> return current
                is ContextWrapper -> candidate = current.baseContext
                else -> return null
            }
        }
        return null
    }

    private data class Start(val manager: Any, val connection: ServiceConnection, val activity: Activity, val root: View)

    private inner class Binding(
        val start: Start,
        private val context: Context,
        private val intent: Intent,
        private val flags: Int,
        private val hasCallbacks: () -> Boolean,
        private val onFailure: () -> Unit,
    ) {
        private val run = Runnable { connect() }
        private val frameReady = Runnable {
            firstFrameCommitted = true
            connectIfReady()
        }
        private val committed = Runnable { main.post(frameReady) }
        private var tree: ViewTreeObserver? = null
        private var firstFrameCommitted = false
        private var entranceFinished = false

        fun schedule() {
            val observer = start.root.viewTreeObserver
            if (start.root.isHardwareAccelerated && observer.isAlive) {
                tree = observer
                observer.registerFrameCommitCallback(committed)
            }
            // An already-visible, covered, or software-rendered window may not send both callbacks.
            main.postDelayed(run, SUGGESTIONS_BIND_FALLBACK_MS)
            if (Trace.isEnabled()) Trace.setCounter("OneUI Settings suggestion bind deferred", 1)
        }

        fun entranceComplete() {
            entranceFinished = true
            connectIfReady()
        }

        private fun connectIfReady() {
            if (firstFrameCommitted && entranceFinished && pending === this) main.post(run)
        }

        fun cancel() {
            main.removeCallbacks(run)
            main.removeCallbacks(frameReady)
            tree?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(committed)
            tree = null
        }

        fun unbind() {
            try {
                context.unbindService(start.connection)
            } catch (error: IllegalArgumentException) {
                HookEntry.log("STOCK_SETTINGS_SUGGESTIONS_UNBIND ${error.javaClass.simpleName}")
            }
        }

        private fun connect() {
            if (pending !== this) return
            cancel()
            pending = null
            if (!hasCallbacks()) return
            Trace.beginSection("OneUI Settings suggestion bind after entrance")
            try {
                if (context.bindService(intent, start.connection, flags)) connecting = this else onFailure()
            } catch (error: Exception) {
                HookEntry.log("STOCK_SETTINGS_SUGGESTIONS_BIND_FAILED ${error.javaClass.simpleName}")
            } finally {
                Trace.endSection()
            }
        }
    }

    private companion object {
        const val SUGGESTIONS_BIND_FALLBACK_MS = 1_200L
    }
}
