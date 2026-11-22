package dev.ryuya.oneuihometweaks

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.animation.PathInterpolator
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference

/** Reduces allocations in verified Samsung animations without changing their submission times. */
@RequiresApi(37)
internal object StockSurfaceAnimationHooks {
    private val floatType = Float::class.javaPrimitiveType!!
    private val transactionType = SurfaceControl.Transaction::class.java
    private val surfaceType = SurfaceControl::class.java

    fun install(app: Application, loader: ClassLoader) {
        if (!HookCompatibility.supportsRecentsFocus(37, Build.FINGERPRINT)) return
        val version = try {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
        } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
            HookEntry.log("STOCK_SURFACE_ANIMATION_UNAVAILABLE ${error.javaClass.simpleName}")
            return
        }
        when {
            app.packageName == "com.android.systemui" && version == 37L -> {
                val frames = StockFrameTransactions()
                ModernHook.onGenerationRetired(frames::close)
                installSafely("SPLIT_DIM") { installSplitDim(loader, frames) }
                installSafely("KEYGUARD_SCALE") { installKeyguardScale(loader, frames) }
                installSafely("ACTIVITY_EMBEDDING") { StockWindowDrawingHooks.installActivityEmbedding(loader, frames) }
                installSafely("ONE_HANDED") { StockWindowDrawingHooks.installOneHanded(loader, frames) }
                installSafely("FREEFORM_IME_MOVE") { StockWindowDrawingHooks.installFreeformIme(loader, frames, cleanup = false) }
                installSafely("FREEFORM_IME_CLEANUP") { StockWindowDrawingHooks.installFreeformIme(loader, frames, cleanup = true) }
                installSafely("NAV_BUTTON_TINT") { StockNavigationTintHooks.install(loader) }
            }
            app.packageName == HookEntry.LAUNCHER &&
                HookCompatibility.matchesLauncherVersion(37, version) -> {
                installSafely("PIP_HOME") { installPipHome(loader) }
            }
        }
    }

    private inline fun installSafely(name: String, install: () -> Unit) {
        try {
            install()
            HookEntry.log("STOCK_${name}_REUSE_READY")
        } catch (error: Exception) {
            HookEntry.log("STOCK_${name}_REUSE_UNAVAILABLE ${error.javaClass.simpleName}")
        } catch (error: LinkageError) {
            HookEntry.log("STOCK_${name}_REUSE_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun installSplitDim(loader: ClassLoader, frames: StockFrameTransactions) {
        val listener = ModernHook.findClass(
            "com.android.wm.shell.common.split.ResizingEffectPolicy\$\$ExternalSyntheticLambda0",
            loader,
        )
        val surface = listener.animationField("f\$0", surfaceType)
        val onUpdate = listener.compatibleDeclaredMethod("onAnimationUpdate", ValueAnimator::class.java)
        check(onUpdate.returnType == Void.TYPE)
        check(ValueAnimator.AnimatorUpdateListener::class.java.isAssignableFrom(listener))

        ModernHook.aroundMethod(onUpdate) { chain ->
            val target = surface.get(chain.thisObject) as? SurfaceControl
            val value = (chain.args[0] as ValueAnimator).animatedValue as? Float
            if (target == null || value == null) return@aroundMethod chain.proceed()

            frames.apply { it.setAlpha(target, value) }
            null
        }
    }

    private fun installPipHome(loader: ClassLoader) {
        val animator = ModernHook.findClass("com.honeyspace.transition.anim.SwipePipToHomeAnimator", loader)
        val launcherEnded = animator.animationField("mHasAnimationEnded", Boolean::class.javaPrimitiveType!!)
        val windowMap = animator.animationField("mHomeToWindowPositionMap", Matrix::class.java)
        val mappedBounds = animator.animationField("mCurrentBoundsF", RectF::class.java)
        val wallpaper = animator.animationField("mWallpaper", surfaceType)
        val listener = ModernHook.findClass(
            "com.honeyspace.transition.anim.SwipePipToHomeAnimator\$2", loader,
        )
        val frameOwner = listener.animationField("this\$0", animator)
        val onUpdate = listener.compatibleDeclaredMethod("onUpdate", RectF::class.java, floatType)
        val updateApp = animator.compatibleDeclaredMethod(
            "onAnimationUpdate", transactionType, RectF::class.java, floatType,
        ).apply { isAccessible = true }
        val updateWallpaper = animator.compatibleDeclaredMethod(
            "onWallpaperScaleUpdate", transactionType, floatType,
        ).apply { isAccessible = true }
        val pipTransaction = ModernHook.findClass("android.window.PictureInPictureSurfaceTransaction", loader)
        check(onUpdate.returnType == Void.TYPE && updateApp.returnType == pipTransaction)
        check(updateWallpaper.returnType == pipTransaction)

        val readVsyncId = Choreographer::class.java.compatibleDeclaredMethod("getVsyncId")
            .apply { isAccessible = true }
        val setVsync = transactionType.compatibleDeclaredMethod(
            "setFrameTimelineVsync", Long::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
        check(readVsyncId.returnType == Long::class.javaPrimitiveType && setVsync.returnType == transactionType)

        val frames = StockFrameTransactions()
        ModernHook.aroundMethod(onUpdate) { chain ->
            val owner = frameOwner.get(chain.thisObject)
            val input = chain.args[0] as? RectF
            if (owner?.javaClass != animator || input == null) return@aroundMethod chain.proceed()
            if (launcherEnded.getBoolean(owner)) return@aroundMethod null

            val map = windowMap.get(owner) as Matrix
            val output = mappedBounds.get(owner) as RectF
            val progress = chain.args[1] as Float
            val vsyncId = readVsyncId.invoke(Choreographer.getInstance()) as Long
            frames.apply { transaction ->
                setVsync.invoke(transaction, vsyncId)
                map.mapRect(output, input)
                updateApp.invoke(owner, transaction, output, progress)
                // Both stock transactions were tagged with this frame and merged before submission.
                if (wallpaper.get(owner) != null) updateWallpaper.invoke(owner, transaction, progress)
            }
            null
        }
    }

    private fun installKeyguardScale(loader: ClassLoader, frames: StockFrameTransactions) {
        val listener = ModernHook.findClass(
            "com.android.systemui.keyguard.KeyguardCustomAnimation\$startScaleAnimation\$1", loader,
        )
        val leash = listener.animationField("\$leash", surfaceType)
        val bounds = listener.animationField("\$windowBounds", Rect::class.java)
        val scaleInterpolator = listener.animationField("\$scaleInterpolator", PathInterpolator::class.java)
        val radiusInterpolator = listener.animationField("\$radiusInterpolator", PathInterpolator::class.java)
        val startScale = listener.animationField("\$startScale", floatType)
        val endScale = listener.animationField("\$endScale", floatType)
        val corner = listener.animationField("\$windowCornerRadius", floatType)
        val pivotX = listener.animationField("\$pivotX", floatType)
        val pivotY = listener.animationField("\$pivotY", floatType)
        val onUpdate = listener.compatibleDeclaredMethod("onAnimationUpdate", ValueAnimator::class.java)
        check(onUpdate.returnType == Void.TYPE)
        check(ValueAnimator.AnimatorUpdateListener::class.java.isAssignableFrom(listener))

        val setCornerRadius = transactionType.compatibleDeclaredMethod("setCornerRadius", surfaceType, floatType)
            .apply { isAccessible = true }
        val setMatrix = transactionType.compatibleDeclaredMethod(
            "setMatrix", surfaceType, Matrix::class.java, FloatArray::class.java,
        ).apply { isAccessible = true }
        check(setCornerRadius.returnType == transactionType && setMatrix.returnType == transactionType)

        val scratch = ThreadLocal.withInitial(::KeyguardScaleFrame)
        ModernHook.aroundMethod(onUpdate) { chain ->
            val owner = chain.thisObject
            val target = leash.get(owner) as? SurfaceControl
            val value = (chain.args[0] as ValueAnimator).animatedValue as? Float
            if (target == null || value == null) return@aroundMethod chain.proceed()

            val scaleProgress = (scaleInterpolator.get(owner) as PathInterpolator).getInterpolation(value)
            val radiusProgress = (radiusInterpolator.get(owner) as PathInterpolator).getInterpolation(value)
            val start = startScale.getFloat(owner)
            val scale = (endScale.getFloat(owner) - start) * scaleProgress + start
            val radius = (1f - radiusProgress) * corner.getFloat(owner)
            val window = bounds.get(owner) as Rect
            val frame = checkNotNull(scratch.get())
            frame.transform(window, scale, pivotX.getFloat(owner), pivotY.getFloat(owner))
            frames.apply { transaction ->
                setCornerRadius.invoke(transaction, target, radius)
                setMatrix.invoke(transaction, target, frame.matrix, frame.values)
                transaction.setAlpha(target, 1f)
                    .setOpaque(target, false)
                    .setLayer(target, Int.MAX_VALUE - 10)
                    .setVisibility(target, true)
            }
            null
        }
    }

}

internal fun Class<*>.animationField(name: String, type: Class<*>) = getDeclaredField(name).apply {
    check(this.type == type && !Modifier.isStatic(modifiers))
    isAccessible = true
}

internal class KeyguardScaleFrame {
    val matrix = Matrix()
    val values = FloatArray(9)

    fun transform(window: Rect, scale: Float, pivotX: Float, pivotY: Float) {
        matrix.setTranslate(window.left.toFloat(), window.top.toFloat())
        matrix.postScale(scale, scale, pivotX, pivotY)
    }
}

/** Only synchronous, successfully submitted transactions can return to the single-entry pool. */
internal class StockFrameTransactions {
    private val spare = AtomicReference<SurfaceControl.Transaction?>()

    fun close() {
        spare.getAndSet(null)?.close()
    }

    inline fun apply(update: (SurfaceControl.Transaction) -> Unit) {
        submit { transaction ->
            update(transaction)
            transaction.apply()
        }
    }

    inline fun submit(submitFrame: (SurfaceControl.Transaction) -> Unit) {
        val transaction = spare.getAndSet(null) ?: SurfaceControl.Transaction()
        var submitted = false
        try {
            submitFrame(transaction)
            submitted = true
        } finally {
            if (!submitted || !spare.compareAndSet(null, transaction)) transaction.close()
        }
    }
}
