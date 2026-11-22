package dev.ryuya.oneuihometweaks

import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.Interpolator
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy

/** Drives One UI Home's existing wallpaper animator for the normal unlock-to-home route. */
internal class HomeUnlockWallpaper private constructor(
    private val animatorClass: Class<*>,
    private val callbackType: Class<*>,
) {
    private val close = animatorClass.compatibleDeclaredMethod(
        "closeStart", Rect::class.java, Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        callbackType, Boolean::class.javaPrimitiveType,
    )
    private val start = animatorClass.compatibleDeclaredMethod("startWallpaperAnimation", Boolean::class.javaPrimitiveType)
    private val end = animatorClass.compatibleDeclaredMethod("end")
    private val open = animatorClass.getDeclaredField("isOpen").apply { isAccessible = true }
    private val pending = animatorClass.getDeclaredField("currentRequest").apply { isAccessible = true }
    private val runner = animatorClass.getDeclaredField("runner").apply { isAccessible = true }
    private val running = animatorClass.getDeclaredField("runningAnim").apply { isAccessible = true }
    private val finalScale = floatField("finalValue")
    private val initialScale = floatField("startValue")
    private val maxScale = floatField("maxScale")
    private val duration = animatorClass.getDeclaredField("finalDuration").apply { isAccessible = true }
    private val interpolator = animatorClass.getDeclaredField("finalInterpolator").apply { isAccessible = true }
    private val cancel = close.returnType.compatibleDeclaredMethod("cancel")
    private val noOp = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, method, _ ->
        if (method.name == "invoke") unit else null
    }
    private val unit = Class.forName("kotlin.Unit", false, callbackType.classLoader)
        .getField("INSTANCE").get(null)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var available: WeakReference<Any>? = null
    private var flight: Flight? = null
    private var failed = false
    private var reported = false

    fun play(root: View, generation: UnlockLogicRuntime.Generation = UnlockLogicRuntime.capture()) {
        if (failed) return
        try {
            val animator = available?.get() ?: return
            if (root.width <= 0 || root.height <= 0 || running.get(animator) != null ||
                runner.get(animator) != null || flight != null
            ) return
            val bounds = Rect()
            if (!root.rootView.getGlobalVisibleRect(bounds) || bounds.isEmpty) return

            val request = Flight(animator, root, SystemClock.uptimeMillis(), generation)
            flight = request
            try {
                root.addOnAttachStateChangeListener(request)
                request.token = close.invoke(animator, bounds, true, false, true, noOp, true)
                main.postDelayed(request, request.duration + CLEANUP_MARGIN_MS)
            } finally {
                request.requesting = false
            }
        } catch (error: Exception) {
            stop()
            failed = true
            HookEntry.log("HOME_UNLOCK_WALLPAPER_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    fun stop() {
        flight?.let { finish(it) }
    }

    private fun finish(request: Flight) {
        if (flight !== request) return
        flight = null
        main.removeCallbacks(request)
        request.root.removeOnAttachStateChangeListener(request)
        val token = request.token ?: return
        try {
            if (pending.get(request.animator) !== token || open.getBoolean(request.animator)) return
            cancel.invoke(token)
            val animation = request.animation
            if (animation != null && running.get(request.animator) === animation) end.invoke(request.animator)
        } catch (error: Exception) {
            HookEntry.log("HOME_UNLOCK_WALLPAPER_CLEANUP ${error.javaClass.simpleName}")
        }
    }

    private fun install() {
        check(callbackType.isInterface && close.returnType.isInterface)
        check(cancel.returnType == Void.TYPE && start.returnType == Void.TYPE && end.returnType == Void.TYPE)
        check(close.returnType.isAssignableFrom(pending.type))
        check(duration.type == Long::class.javaPrimitiveType && interpolator.type == Interpolator::class.java)
        check(running.type == ValueAnimator::class.java && open.type == Boolean::class.javaPrimitiveType)
        check(ModernHook.deoptimize(start))
        val constructors = ModernHook.hookAllConstructors(animatorClass, object : ModernHook.Callback() {
            override fun afterHookedMethod(param: ModernHook.Param) {
                if (!param.hasThrowable()) available = WeakReference(param.thisObject)
            }
        })
        try {
            ModernHook.hookMethod(start, object : ModernHook.Callback() {
                override fun beforeHookedMethod(param: ModernHook.Param) {
                    val request = flight ?: return
                    val animator = param.thisObject
                    if (param.args[0] != true || animator !== request.animator ||
                        (!request.requesting && pending.get(animator) !== request.token) ||
                        finalScale.getFloat(animator) != 1f || running.get(animator) != null
                    ) return

                    // The wallpaper surface can arrive after the home animation has already started.
                    val elapsed = (SystemClock.uptimeMillis() - request.startedAt).coerceIn(0L, request.duration)
                    val initial = request.generation.call {
                        val zoom = it.wallpaperZoom()
                        check(zoom in 0f..0.30f)
                        1f + zoom * (1f - it.wallpaperProgress(elapsed.toFloat() / request.duration).validFraction())
                    }
                    initialScale.setFloat(animator, initial)
                    maxScale.setFloat(animator, initial)
                    duration.setLong(animator, (request.duration - elapsed).coerceAtLeast(1L))
                    interpolator.set(animator, Interpolator { progress ->
                        request.generation.call { it.wallpaperProgress(progress).validFraction() }
                    })
                    request.started = true
                    if (!reported) {
                        reported = true
                        HookEntry.log("HOME_UNLOCK_WALLPAPER_STARTED elapsed=${elapsed}ms")
                    }
                }

                override fun afterHookedMethod(param: ModernHook.Param) {
                    val request = flight ?: return
                    if (!param.hasThrowable() && param.args[0] == true &&
                        request.started && param.thisObject === request.animator &&
                        (request.requesting || pending.get(request.animator) === request.token)
                    ) request.animation = running.get(request.animator) as? ValueAnimator
                }
            })
        } catch (error: Throwable) {
            constructors.forEach { it.unhook() }
            throw error
        }
    }

    private fun floatField(name: String) = animatorClass.getDeclaredField(name).apply {
        check(type == Float::class.javaPrimitiveType)
        isAccessible = true
    }

    private inner class Flight(
        val animator: Any, val root: View, val startedAt: Long,
        val generation: UnlockLogicRuntime.Generation,
    ) :
        Runnable, View.OnAttachStateChangeListener {
        val duration = generation.duration(UnlockLogicV1.WALLPAPER, 80L..700L)
        var token: Any? = null
        var requesting = true
        var started = false
        var animation: ValueAnimator? = null

        override fun run() = finish(this)
        override fun onViewDetachedFromWindow(view: View) = finish(this)
        override fun onViewAttachedToWindow(view: View) = Unit
    }

    companion object {
        private const val CLEANUP_MARGIN_MS = 350L

        fun install(loader: ClassLoader): HomeUnlockWallpaper? = try {
            val type = ModernHook.findClass("com.honeyspace.transition.anim.WallpaperAnimator", loader)
            val callback = ModernHook.findClass("kotlin.jvm.functions.Function0", loader)
            HomeUnlockWallpaper(type, callback).apply { install() }
        } catch (error: Exception) {
            HookEntry.log("HOME_UNLOCK_WALLPAPER_UNAVAILABLE ${error.javaClass.simpleName}")
            null
        } catch (error: LinkageError) {
            HookEntry.log("HOME_UNLOCK_WALLPAPER_UNAVAILABLE ${error.javaClass.simpleName}")
            null
        }
    }
}
