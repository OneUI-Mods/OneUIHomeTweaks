package dev.ryuya.oneuihometweaks

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import dev.ryuya.oneuihometweaks.compat.DexRuntimeCache
import dev.ryuya.oneuihometweaks.compat.DexSymbols

/** Owns one generation of SystemUI hooks. API 102 supplies and loads the next generation. */
internal object SystemUiCodeRuntime {
    private val main = Handler(Looper.getMainLooper())
    private var host: Application? = null
    private var hostLoader: ClassLoader? = null
    private var markHealthy: Runnable? = null
    private var healthDeadline = 0L
    @Volatile private var reconnectState: Array<*>? = null

    fun start(app: Application, loader: ClassLoader, guard: CrashGuard? = null, healthDelay: Long = 90_000L) {
        if (Looper.myLooper() !== main.looper) {
            main.post { reportFailure { start(app, loader, guard, healthDelay) } }
            return
        }
        if (host != null) return
        val scope = SystemUiHookScope {
            HookEntry.log("SYSTEMUI_CODE_LIFECYCLE_ERROR ${it.javaClass.simpleName}: ${it.message}")
        }
        try {
            SystemUiHookScopes.bind(scope) { SignedSystemUiHooks().install(app, loader) }
            check(SystemUiHookScopes.trySwitch(scope) { it?.close() })
        } catch (error: Throwable) {
            scope.close()
            throw error
        }
        host = app
        hostLoader = loader
        if (guard != null) {
            val task = Runnable {
                markHealthy = null
                healthDeadline = 0L
                guard.healthy()
            }
            markHealthy = task
            healthDeadline = SystemClock.uptimeMillis() + healthDelay
            main.postDelayed(task, healthDelay)
        }
        HookEntry.log("SYSTEMUI_CODE_READY version=${BuildConfig.VERSION_CODE}")
    }

    fun handOff(save: (Array<*>) -> Unit): Boolean {
        return MainThreadReload.run(main) handOff@{
            val app = host
            val loader = hostLoader
            if (app == null || loader == null) {
                val state = reconnectState ?: return@handOff false
                save(state)
                DexSymbols.detachObserver()
                reconnectState = null
                return@handOff true
            }
            SystemUiHookScopes.tryRetire {
                // Only the live Android objects and serialized evidence cross class loaders.
                save(arrayOf(app, loader, SystemUiLiveObjects.exportState(),
                    DexRuntimeCache.exportProofs(), healthDeadline))
            }.also { retired ->
                if (retired) {
                    markHealthy?.let(main::removeCallbacks)
                    markHealthy = null
                    healthDeadline = 0L
                    DexSymbols.detachObserver()
                    host = null
                    hostLoader = null
                }
            }
        }
    }

    fun resume(state: Array<*>, finished: () -> Unit) {
        val app = requireNotNull(state.getOrNull(0) as? Application)
        val loader = requireNotNull(state.getOrNull(1) as? ClassLoader)
        check(app.packageName == "com.android.systemui" && HostStartupGate.unlocked(app))
        reconnectState = state
        check(MainThreadReload.run(main) {
            check(host == null)
            SystemUiLiveObjects.importState(state.getOrNull(2))
            DexRuntimeCache.importProofs(state.getOrNull(3))
            check(DexSymbols.canInstallNow(app)) { "SystemUI discovery is still preparing" }
            val deadline = state.getOrNull(4) as? Long ?: 0L
            val guard = if (deadline > 0) CrashGuard.resume(app) else null
            start(app, loader, guard, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))
            reconnectState = null
            finished()
            HookEntry.log(
                "ONEUI_MODS_HOT_RELOAD package=${BuildConfig.APPLICATION_ID} " +
                    "version=${BuildConfig.VERSION_CODE} pid=${Process.myPid()}"
            )
            true
        }) { "SystemUI main thread was unavailable for hook setup" }
    }

    private inline fun reportFailure(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            HookEntry.log("SYSTEMUI_CODE_INSTALL_REJECTED ${error.javaClass.simpleName}: ${error.message}")
        } catch (error: LinkageError) {
            HookEntry.log("SYSTEMUI_CODE_INSTALL_REJECTED ${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
