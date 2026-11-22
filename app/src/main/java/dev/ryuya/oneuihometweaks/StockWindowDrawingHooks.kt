package dev.ryuya.oneuihometweaks

import android.animation.ValueAnimator
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.animation.Animation
import android.view.animation.Transformation
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.util.function.BiConsumer
import java.util.function.Consumer

/** Stock Shell callbacks that submit their own layer changes within one animation update. */
@RequiresApi(37)
internal object StockWindowDrawingHooks {
    private val floatType = Float::class.javaPrimitiveType!!
    private val transactionType = SurfaceControl.Transaction::class.java

    fun installActivityEmbedding(loader: ClassLoader, frames: StockFrameTransactions) {
        val adapter = ModernHook.findClass(
            "com.android.wm.shell.activityembedding.ActivityEmbeddingAnimationAdapter", loader,
        )
        val listener = ModernHook.findClass(
            "com.android.wm.shell.activityembedding.ActivityEmbeddingAnimationRunner\$\$ExternalSyntheticLambda0",
            loader,
        )
        val adapters = listener.animationField("f\$0", List::class.java)
        val timer = listener.animationField("f\$1", ValueAnimator::class.java)
        val transform = adapter.animationField("mTransformation", Transformation::class.java)
        val animation = adapter.animationField("mAnimation", Animation::class.java)
        val advance = adapter.compatibleDeclaredMethod("onAnimationUpdateInner", transactionType)
        check(advance.returnType == Void.TYPE)
        val onUpdate = listener.valueAnimatorUpdate()

        val getVsync = Choreographer::class.java.compatibleDeclaredMethod("getVsyncId")
            .apply { isAccessible = true }
        val setVsync = transactionType.compatibleDeclaredMethod(
            "setFrameTimelineVsync", Long::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
        check(getVsync.returnType == Long::class.javaPrimitiveType && setVsync.returnType == transactionType)

        ModernHook.aroundMethod(onUpdate) { chain ->
            val owner = chain.thisObject ?: return@aroundMethod chain.proceed()
            val active = adapters.get(owner) as List<*>
            val player = timer.get(owner) as ValueAnimator
            val vsync = getVsync.invoke(Choreographer.getInstance()) as Long
            frames.apply { transaction ->
                setVsync.invoke(transaction, vsync)
                for (item in active) {
                    val currentTime = player.currentPlayTime
                    val state = transform.get(item) as Transformation
                    state.clear()
                    val animator = animation.get(item) as Animation
                    animator.getTransformation(minOf(currentTime, animator.duration), state)
                    advance.invoke(item, transaction)
                }
            }
            null
        }
    }

    fun installOneHanded(loader: ClassLoader, frames: StockFrameTransactions) {
        val animatorName =
            "com.android.wm.shell.onehanded.OneHandedAnimationController\$OneHandedTransitionAnimator"
        val animator = ModernHook.findClass(animatorName, loader)
        val yOffsetAnimator = ModernHook.findClass("$animatorName\$1", loader)
        check(yOffsetAnimator.superclass == animator)
        val callback = ModernHook.findClass("com.android.wm.shell.onehanded.OneHandedAnimationCallback", loader)
        val leash = animator.animationField("mLeash", SurfaceControl::class.java)
        val callbacks = animator.animationField("mOneHandedAnimationCallbacks", List::class.java)
        val offset = animator.animationField("mCurrentValue", floatType)
        val notify = callback.compatibleDeclaredMethod("onAnimationUpdate", floatType)
        val applyOffset = yOffsetAnimator.compatibleDeclaredMethod(
            "applySurfaceControlTransaction", SurfaceControl::class.java, transactionType, floatType,
        ).apply { isAccessible = true }
        check(applyOffset.returnType == Void.TYPE && notify.returnType == Void.TYPE)
        val onUpdate = animator.valueAnimatorUpdate()

        ModernHook.aroundMethod(onUpdate) { chain ->
            val owner = chain.thisObject ?: return@aroundMethod chain.proceed()
            if (owner.javaClass != yOffsetAnimator) return@aroundMethod chain.proceed()
            val ordered = callbacks.get(owner) as ArrayList<*>
            val player = chain.args[0] as ValueAnimator

            frames.submit { transaction ->
                ordered.forEach(Consumer { notify.invoke(it, offset.getFloat(owner)) })
                // The verified Y offset animator applies its transaction itself.
                applyOffset.invoke(owner, leash.get(owner), transaction, player.animatedFraction)
            }
            null
        }
    }

    fun installFreeformIme(loader: ClassLoader, frames: StockFrameTransactions, cleanup: Boolean) {
        val name = "com.android.wm.shell.freeform.ime.FreeformImeController"
        val controller = ModernHook.findClass(name, loader)
        val target = ModernHook.findClass("com.android.wm.shell.freeform.ime.FreeformImeTargetImpl", loader)
        val animationClass = if (cleanup) "\$cleanUpShowingImeTargets\$3\$1" else "\$adjustFreeformPosition\$2\$1"
        val listener = ModernHook.findClass(name + animationClass, loader)
        val controllerField = listener.animationField("this\$0", controller)
        val targets = controller.animationField("taskIdToImeTarget", Map::class.java)
        val updatePosition = target.compatibleDeclaredMethod("updateCurrentPosition", floatType, transactionType)
        check(updatePosition.returnType == Void.TYPE)
        val onUpdate = listener.valueAnimatorUpdate()
        if (cleanup) {
            val closing = listener.animationField("\$taskIdsToRemove", List::class.java)
            ModernHook.aroundMethod(onUpdate) { chain ->
                val owner = chain.thisObject ?: return@aroundMethod chain.proceed()
                val player = chain.args[0] as ValueAnimator
                val pendingIds = closing.get(owner) as List<*>
                val host = controllerField.get(owner)
                for (taskId in pendingIds) {
                    frames.apply { transaction ->
                        val item = (targets.get(host) as Map<*, *>)[(taskId as Number).toInt()]
                        if (item != null) updatePosition.invoke(item, player.animatedValue as Float, transaction)
                    }
                }
                null
            }
        } else {
            ModernHook.aroundMethod(onUpdate) { chain ->
                val owner = chain.thisObject ?: return@aroundMethod chain.proceed()
                val player = chain.args[0] as ValueAnimator
                val active = targets.get(controllerField.get(owner)) as LinkedHashMap<*, *>
                active.forEach(BiConsumer { _, item ->
                    frames.apply { transaction ->
                        updatePosition.invoke(item, player.animatedValue as Float, transaction)
                    }
                })
                null
            }
        }
    }

    private fun Class<*>.valueAnimatorUpdate() =
        compatibleDeclaredMethod("onAnimationUpdate", ValueAnimator::class.java).also {
            check(ValueAnimator.AnimatorUpdateListener::class.java.isAssignableFrom(this) && it.returnType == Void.TYPE)
        }
}
