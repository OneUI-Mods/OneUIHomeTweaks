package dev.ryuya.oneuihometweaks

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import dev.ryuya.oneuihometweaks.compat.DexRuntimeCache
import dev.ryuya.oneuihometweaks.compat.DexSymbols

/** Owns either the Settings editor or the wallpaper preview in its host process. */
internal object AuxiliaryHostRuntime {
    const val SETTINGS = "com.android.settings"
    const val PREVIEW = "com.samsung.android.app.dressroom"
    private val main = Handler(Looper.getMainLooper())
    private var host: Application? = null
    private var loader: ClassLoader? = null
    private var currentState: (() -> Array<*>)? = null
    private var readyToRetire: (() -> Boolean)? = null
    private var healthDeadline = 0L
    private var reconnectState: Array<*>? = null

    fun start(
        app: Application,
        classLoader: ClassLoader,
        guard: CrashGuard?,
        remainingHealthDelay: Long = 90_000,
        previousState: Array<*> = emptyArray<Any>(),
    ) {
        if (Looper.myLooper() !== main.looper) {
            main.post {
                runCatching { start(app, classLoader, guard, remainingHealthDelay, previousState) }
                    .onFailure { HookEntry.log("ANIMATION_EDITOR_START $it") }
            }
            return
        }
        if (host != null) return
        val scope = SystemUiHookScope { HookEntry.log("AUXILIARY_HOST_SCOPE $it") }
        try {
            if (app.packageName == SETTINGS) {
                val editor = DeveloperAnimationHooks()
                scope.onRetire(editor::close)
                check(SystemUiHookScopes.bind(scope) { editor.install(app, classLoader) })
                check(SystemUiHookScopes.trySwitch(scope) { it?.close() })
                editor.reattachControllers(previousState)
                currentState = editor::liveControllers
                readyToRetire = editor::readyForHotReload
            } else {
                check(app.packageName == PREVIEW)
                if (Build.VERSION.SDK_INT < 36) error("Wallpaper preview requires Android 16")
                val preview = PreviewHooks()
                scope.onRetire(preview::close)
                SystemUiHookScopes.bind(scope) { preview.install(app, classLoader) }
                check(SystemUiHookScopes.trySwitch(scope) { it?.close() })
                if (previousState.isNotEmpty()) preview.reconnect(previousState)
                currentState = preview::currentState
                readyToRetire = preview::readyForHotReload
            }
        } catch (error: Throwable) {
            // Remove partially registered hooks and any observers before a later retry.
            if (!SystemUiHookScopes.tryRetire {}) scope.close()
            throw error
        }
        host = app
        loader = classLoader
        if (guard != null) {
            healthDeadline = SystemClock.uptimeMillis() + remainingHealthDelay
            main.postDelayed({
                healthDeadline = 0
                guard.healthy()
            }, remainingHealthDelay)
        }
    }

    fun handOff(save: (Array<*>) -> Unit): Boolean = MainThreadReload.run(main) handOff@{
        val active = currentState
        if (active == null) {
            val state = reconnectState ?: return@handOff false
            save(state)
        } else {
            if (readyToRetire?.invoke() != true) return@handOff false
            if (!SystemUiHookScopes.tryRetire {
                    save(arrayOf(host, loader, active(),
                        DexRuntimeCache.exportProofs(), healthDeadline))
                }) return@handOff false
        }
        DexSymbols.detachObserver()
        main.removeCallbacksAndMessages(null)
        healthDeadline = 0
        host = null
        loader = null
        currentState = null
        readyToRetire = null
        reconnectState = null
        true
    }

    fun resume(state: Array<*>, discardPrevious: () -> Unit) {
        val app = requireNotNull(state.getOrNull(0) as? Application)
        val classLoader = requireNotNull(state.getOrNull(1) as? ClassLoader)
        check((app.packageName == SETTINGS || app.packageName == PREVIEW) && HostStartupGate.unlocked(app))
        check(MainThreadReload.run(main) {
            reconnectState = state
            DexRuntimeCache.importProofs(state.getOrNull(3))
            check(DexSymbols.canInstallNow(app))
            val deadline = state.getOrNull(4) as? Long ?: 0
            val guard = if (deadline > 0) CrashGuard.resume(app) else null
            start(app, classLoader, guard, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0),
                state.getOrNull(2) as? Array<*> ?: emptyArray<Any>())
            discardPrevious()
            reconnectState = null
            HookEntry.log("ONEUI_MODS_HOT_RELOAD package=${BuildConfig.APPLICATION_ID} " +
                "version=${BuildConfig.VERSION_CODE} pid=${Process.myPid()}")
            true
        }) { "Host main thread was unavailable for hook setup" }
    }
}
