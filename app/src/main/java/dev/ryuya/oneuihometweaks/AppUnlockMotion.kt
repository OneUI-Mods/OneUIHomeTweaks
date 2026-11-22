package dev.ryuya.oneuihometweaks

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Application
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** The stock SystemUI transition for unlocking back into an app, including multi-window. */
internal object AppUnlockMotion {
    fun install(app: Application, loader: ClassLoader) {
        if (Build.VERSION.SDK_INT < 33 || !HomeUnlockMotion.supportsDevice()) return
        try {
            @Suppress("DEPRECATION")
            if (app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode != 37L) return
            installHooks(app, loader)
            HookEntry.log("APP_UNLOCK_MOTION_READY")
        } catch (error: Exception) {
            HookEntry.log("APP_UNLOCK_MOTION_UNAVAILABLE ${error.javaClass.simpleName}")
        } catch (error: LinkageError) {
            HookEntry.log("APP_UNLOCK_MOTION_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    @RequiresApi(33)
    private fun installHooks(app: Application, loader: ClassLoader) {
        val controller = ModernHook.findClass(
            "com.android.systemui.keyguard.KeyguardSecLegacyUnlockAnimationControllerImpl", loader,
        )
        val unlock = controller.compatibleDeclaredMethod("playAppUnlockAnimation")
        val animation = controller.getDeclaredField("cannedAnimatorSet").apply { isAccessible = true }
        val delay = controller.getDeclaredField("animStartDelay").apply { isAccessible = true }
        val start = AnimatorSet::class.java.getDeclaredMethod("start")
        check(unlock.returnType == Void.TYPE && animation.type == AnimatorSet::class.java)
        check(delay.type == Long::class.javaPrimitiveType)

        val activeController = ThreadLocal<Any?>()
        val settings = UnlockSettings(app)
        val scene = AppUnlockScene.install(controller, loader, app)
        val startHook = ModernHook.aroundMethod(start) { chain ->
            val owner = activeController.get() ?: return@aroundMethod chain.proceed()
            val set = chain.thisObject as AnimatorSet
            if (animation.get(owner) === set && set.duration > 0L) {
                val originalBase = if (delay.getLong(owner) > 0L) 250L else 350L
                val generation = UnlockLogicRuntime.capture()
                val duration = generation.duration(UnlockLogicV1.APP, 100L..700L)
                // Preserve the user's transition animation scale already reflected in this duration.
                set.duration = (set.duration * duration.toDouble() / originalBase).toLong().coerceAtLeast(1L)
                set.interpolator = android.animation.TimeInterpolator { progress ->
                    generation.call { it.appOpacity(progress).validFraction() }
                }
                try {
                    scene?.begin(owner, set, generation)
                } catch (error: Exception) {
                    HookEntry.log("APP_UNLOCK_SCENE_NOT_STARTED ${error.javaClass.simpleName}")
                }
            }
            chain.proceed()
        }
        var unlockHook: ModernHook.Handle? = null
        try {
            // The vendor callback must reach the scoped AnimatorSet.start interceptor.
            check(ModernHook.deoptimize(unlock))
            unlockHook = ModernHook.aroundMethod(unlock) { chain ->
                if (!settings.enabled || !ValueAnimator.areAnimatorsEnabled()) {
                    return@aroundMethod chain.proceed()
                }
                val previous = activeController.get()
                activeController.set(chain.thisObject)
                try {
                    chain.proceed()
                } finally {
                    if (previous == null) activeController.remove() else activeController.set(previous)
                }
            }
            settings.observe()
            LauncherLockscreenExit.install(controller, loader) { settings.enabled }
        } catch (error: Throwable) {
            unlockHook?.unhook()
            startHook.unhook()
            throw error
        }
    }

    private class UnlockSettings(private val app: Application) {
        @Volatile private var retired = false
        @Volatile var enabled = false
            private set

        fun observe() {
            val uri = requireNotNull(ConfigProvider.URI)
            val worker = BackgroundExecutors.serial("OneUiUnlockConfig")
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    if (!retired) worker.execute { read() }
                }
            }
            ModernHook.onGenerationRetired {
                retired = true
                enabled = false
                runCatching { app.contentResolver.unregisterContentObserver(observer) }
                worker.shutdown()
            }
            ModernHook.afterGenerationActivation {
                app.contentResolver.registerContentObserver(uri, false, observer)
                worker.execute { read() }
            }
        }

        private fun read() {
            if (retired) return
            val next = runCatching {
                app.contentResolver.query(requireNotNull(ConfigProvider.URI), null, null, null, null)
                    ?.use { cursor ->
                        cursor.moveToFirst() &&
                            cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) != 0 &&
                            cursor.getInt(cursor.getColumnIndexOrThrow("systemui_enabled")) != 0 &&
                            cursor.getInt(cursor.getColumnIndexOrThrow(HomeUnlockMotion.KEY)) != 0
                    } == true
            }.getOrDefault(false)
            if (!retired) enabled = next
        }
    }
}
