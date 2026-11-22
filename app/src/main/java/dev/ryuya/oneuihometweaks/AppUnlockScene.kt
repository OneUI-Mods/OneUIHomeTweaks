package dev.ryuya.oneuihometweaks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.Application
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.SurfaceControl
import android.view.WindowManager
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.reflect.Field

/** Geometry for the same APP unlock transaction that Samsung uses for its crossfade. */
@RequiresApi(33)
internal class AppUnlockScene private constructor(
    controller: Class<*>,
    target: Class<*>,
    private val app: Application,
) {
    private val apply = controller.compatibleDeclaredMethod("applyTransaction")
    private val content = controller.field("surfacesBehind")
    private val wallpapers = controller.field("openingWallpapers")
    private val wallpaperLeash = controller.field("openingWallpaperLeash")
    private val stockTransaction = controller.field("curTransaction")
    private val keyguard = controller.field("keyguardSurface")
    private val alphaAnimator = controller.field("alphaAnimator")
    private val requestedAlpha = controller.field("reqLeashAlpha")
    private val currentAlpha = controller.field("curLeashAlpha")
    private val requestedKeyguardAlpha = controller.field("reqKeyguardAlpha")
    private val currentKeyguardAlpha = controller.field("curKeyguardAlpha")
    private val leash = target.field("leash")
    private val bounds = target.field("screenSpaceBounds")
    private val localBounds = target.field("localBounds")
    private val position = target.field("position")
    private val rotation = target.field("rotationChange")
    private val taskInfo = target.field("taskInfo")
    private val taskDisplay = ActivityManager.RunningTaskInfo::class.java.field("displayId")
    private val frames = ThreadLocal<Flight>()
    private var reported = false

    fun begin(owner: Any, set: AnimatorSet, generation: UnlockLogicRuntime.Generation = UnlockLogicRuntime.capture()) {
        val display = app.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val context = app.createDisplayContext(display)
        val screen = context.getSystemService(WindowManager::class.java)
            ?.maximumWindowMetrics?.bounds ?: return
        if (screen.isEmpty || screen.left != 0 || screen.top != 0 || screen.width() >= screen.height()) return
        val targets = content.get(owner) as? Array<*> ?: return
        val first = targets.firstOrNull() ?: return
        val firstTask = taskInfo.get(first) as? ActivityManager.RunningTaskInfo ?: return
        if (taskDisplay.getInt(firstTask) != Display.DEFAULT_DISPLAY || rotation.getInt(first) != 0) return
        val transaction = stockTransaction.get(owner) as? SurfaceControl.Transaction ?: return
        val alpha = alphaAnimator.get(owner) as? ValueAnimator ?: return
        val primary = fullscreenLeash(first, screen)
        val wallpaper = (wallpapers.get(owner) as? Array<*>)?.firstOrNull()
            ?.let { fullscreenLeash(it, screen) }
            ?.takeIf { it === wallpaperLeash.get(owner) }
        val existing = frames.get()
        if (existing != null) existing.finish()
        val flight = Flight(owner, set, transaction, alpha, screen, primary, wallpaper, generation)
        frames.set(flight)
        set.addListener(flight)
        if (!reported) {
            reported = true
            HookEntry.log("APP_UNLOCK_SCENE arms=${targets.size} content=${primary != null} wallpaper=${wallpaper != null}")
        }
    }

    private fun fullscreenLeash(surface: Any, screen: Rect): SurfaceControl? {
        if (rotation.getInt(surface) != 0 || bounds.get(surface) != screen ||
            localBounds.get(surface) != screen
        ) return null
        val origin = position.get(surface) as? Point
        if (origin != null && (origin.x != 0 || origin.y != 0)) return null
        return (leash.get(surface) as? SurfaceControl)?.takeIf { it.isValid }
    }

    private fun install() {
        check(apply.returnType == Void.TYPE)
        check(alphaAnimator.type == ValueAnimator::class.java)
        check(stockTransaction.type == SurfaceControl.Transaction::class.java)
        check(leash.type == SurfaceControl::class.java && keyguard.type == SurfaceControl::class.java)
        ModernHook.hookMethod(apply, object : ModernHook.Callback() {
            override fun beforeHookedMethod(param: ModernHook.Param) {
                val flight = frames.get() ?: return
                if (flight.owner !== param.thisObject || stockTransaction.get(flight.owner) !== flight.transaction) return
                if (!willApply(flight.owner)) return
                flight.update()
            }
        })
    }

    private fun willApply(owner: Any): Boolean {
        val appAlpha = requestedAlpha.getFloat(owner)
        val lockAlpha = requestedKeyguardAlpha.getFloat(owner)
        return (appAlpha >= 0f && appAlpha != currentAlpha.getFloat(owner)) ||
            (lockAlpha >= 0f && lockAlpha != currentKeyguardAlpha.getFloat(owner))
    }

    private inner class Flight(
        val owner: Any,
        private val set: AnimatorSet,
        val transaction: SurfaceControl.Transaction,
        private val alpha: ValueAnimator,
        private val screen: Rect,
        private val content: SurfaceControl?,
        private val wallpaper: SurfaceControl?,
        private val generation: UnlockLogicRuntime.Generation,
    ) : AnimatorListenerAdapter() {
        private var outgoing: SurfaceControl? = null
        private val frame = UnlockAppFrame()
        private var progress = -1f
        private var touched = false

        fun update() {
            if (alpha.duration <= 0) return
            val fraction = (alpha.currentPlayTime.toFloat() / alpha.duration).coerceIn(0f, 1f)
            if (progress == fraction) return
            val lock = keyguard.get(owner) as? SurfaceControl
            // If WindowManager replaced the lock surface mid-flight, retain its stock position.
            if (outgoing == null && lock?.isValid == true) outgoing = lock
            val previous = outgoing
            if (previous != null && lock !== previous && previous.isValid) {
                transaction.setPosition(previous, 0f, 0f)
                outgoing = null
            }
            generation.call {
                it.appFrame(fraction, frame)
                check(frame.lift in 0f..1f && frame.drop in -0.25f..0.25f &&
                    frame.zoom in 0f..0.40f && frame.wallpaperZoom in 0f..0.40f)
            }
            if (lock === outgoing && lock?.isValid == true) {
                val lift = if (fraction == 1f) 0f else -screen.height() * frame.lift
                transaction.setPosition(lock, 0f, lift)
            }
            place(transaction, content, if (fraction == 1f) 0f else frame.zoom, if (fraction == 1f) 0f else frame.drop)
            place(transaction, wallpaper, if (fraction == 1f) 0f else frame.wallpaperZoom, 0f)
            progress = fraction
            touched = fraction < 1f
        }

        private fun place(
            targetTransaction: SurfaceControl.Transaction,
            surface: SurfaceControl?,
            extraScale: Float,
            down: Float,
        ) {
            if (surface?.isValid != true) return
            targetTransaction.setScale(surface, 1f + extraScale, 1f + extraScale)
            targetTransaction.setPosition(
                surface,
                -extraScale * screen.width() / 2f,
                screen.height() * (down - extraScale / 2f),
            )
        }

        override fun onAnimationCancel(animation: Animator) = finish()

        override fun onAnimationEnd(animation: Animator) = finish()

        fun finish() {
            if (frames.get() !== this) return
            frames.remove()
            set.removeListener(this)
            if (!touched || stockTransaction.get(owner) !== transaction) return
            try {
                SurfaceControl.Transaction().use { restore ->
                    outgoing?.takeIf { it.isValid }?.let { restore.setPosition(it, 0f, 0f) }
                    place(restore, content, 0f, 0f)
                    place(restore, wallpaper, 0f, 0f)
                    restore.apply()
                }
            } catch (error: RuntimeException) {
                HookEntry.log("APP_UNLOCK_SCENE_RESTORE ${error.javaClass.simpleName}")
            }
        }
    }

    companion object {
        fun install(controller: Class<*>, loader: ClassLoader, app: Application): AppUnlockScene? = try {
            val target = ModernHook.findClass("com.android.wm.shell.shared.compat.AnimatedSurface", loader)
            AppUnlockScene(controller, target, app).apply { install() }
        } catch (error: Exception) {
            HookEntry.log("APP_UNLOCK_SCENE_UNAVAILABLE ${error.javaClass.simpleName}")
            null
        } catch (error: LinkageError) {
            HookEntry.log("APP_UNLOCK_SCENE_UNAVAILABLE ${error.javaClass.simpleName}")
            null
        }

        private fun Class<*>.field(name: String): Field {
            var owner: Class<*>? = this
            while (owner != null) {
                try {
                    return owner.getDeclaredField(name).apply { isAccessible = true }
                } catch (_: NoSuchFieldException) {
                    owner = owner.superclass
                }
            }
            throw NoSuchFieldException(name)
        }
    }
}
