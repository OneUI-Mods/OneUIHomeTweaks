package dev.ryuya.oneuihometweaks

import android.app.Application
import dalvik.system.PathClassLoader
import dev.ryuya.oneuihometweaks.unlocklogic.SignedUnlockLogic

/** Stable host-side loader. New logic is accepted only from the installed release-signed APK. */
internal object UnlockLogicRuntime {
    private const val CODE_PACKAGE = "dev.ryuya.oneuihometweaks.unlocklogic."
    private const val ENTRY = CODE_PACKAGE + "SignedUnlockLogic"
    private val bundled = SignedUnlockLogic()
    private val builtIn = Generation(BuildConfig.VERSION_CODE.toLong(), bundled, bundled)
    private val worker by lazy { BackgroundExecutors.serial("OneUiUnlockCode") }

    @Volatile private var current = builtIn
    private var started = false
    private var observedVersion = builtIn.version

    fun capture() = current

    @Synchronized
    fun start(app: Application) {
        if (started) return
        try {
            SignedModuleApk.watch(app) { worker.execute { refresh(app) } }
            started = true
            worker.execute { refresh(app) }
        } catch (error: Exception) {
            HookEntry.log("UNLOCK_CODE_WATCH_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun refresh(app: Application) {
        try {
            val installed = SignedModuleApk.read(app)
            val version = installed.version
            if (version <= observedVersion) return
            val loaded = LogicClassLoader(installed.source, UnlockLogicV1::class.java.classLoader!!)
                .loadClass(ENTRY).asSubclass(UnlockLogicV1::class.java)
                .getDeclaredConstructor().newInstance()
            check(loaded.apiVersion == 1)
            for (stage in UnlockLogicV1.HOME..UnlockLogicV1.APP) check(loaded.duration(stage) in 1..2000)
            if (SignedModuleApk.read(app) != installed) return
            accept(version, loaded)
            observedVersion = version
            HookEntry.log("UNLOCK_CODE_RELOADED version=$version")
        } catch (error: Exception) {
            HookEntry.log("UNLOCK_CODE_RELOAD_REJECTED ${error.javaClass.simpleName}")
        } catch (error: LinkageError) {
            HookEntry.log("UNLOCK_CODE_RELOAD_REJECTED ${error.javaClass.simpleName}")
        }
    }

    @Synchronized
    private fun accept(version: Long, implementation: UnlockLogicV1) {
        current = Generation(version, implementation, current.implementation)
    }

    @Synchronized
    private fun reject(generation: Generation, error: Throwable) {
        if (current !== generation || generation === builtIn) return
        current = Generation(generation.version, generation.fallback, bundled)
        HookEntry.log("UNLOCK_CODE_ROLLBACK version=${generation.version} ${error.javaClass.simpleName}")
    }

    internal class Generation(
        val version: Long,
        internal val implementation: UnlockLogicV1,
        internal val fallback: UnlockLogicV1,
    ) {
        @Volatile private var preferred = implementation

        inline fun <T> call(action: (UnlockLogicV1) -> T): T {
            val logic = preferred
            try {
                return action(logic)
            } catch (error: RuntimeException) {
                if (logic === fallback) throw error
                reject(this, error)
            } catch (error: LinkageError) {
                if (logic === fallback) throw error
                reject(this, error)
            }
            preferred = fallback
            return action(fallback)
        }

        fun duration(stage: Int, limits: LongRange): Long =
            call { it.duration(stage).coerceIn(limits.first, limits.last) }
    }

    private class LogicClassLoader(apk: String, parent: ClassLoader) : PathClassLoader(apk, parent) {
        @Synchronized
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith(CODE_PACKAGE)) return super.loadClass(name, resolve)
            val type = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(type)
            return type
        }
    }
}

internal fun Float.validFraction(): Float {
    check(this in 0f..1f)
    return this
}
