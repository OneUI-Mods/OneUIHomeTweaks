package dev.ryuya.oneuihometweaks

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator

internal data class GlassGlint(val x: Float, val y: Float, val amount: Float)

internal data class GlassPointer(val screenX: Float, val screenY: Float, val amount: Float) {
    fun onSurface(left: Float, top: Float, width: Float, height: Float, density: Float): GlassGlint? {
        if (width <= 0 || height <= 0) return null
        val x = screenX - left
        val y = screenY - top
        val localX = x.coerceIn(0f, width)
        val localY = y.coerceIn(0f, height)
        val outside = kotlin.math.hypot(x - localX, y - localY)
        val proximity = (1f - outside / (density * 95f)).coerceIn(0f, 1f)
        if (proximity <= 0f) return null
        return GlassGlint(localX / width, localY / height, amount * proximity * proximity)
    }
}

/** Follow the finger, then settle for a short time after release. Idle surfaces do no work. */
internal class GlassTouchLight(private val redraw: () -> Unit) {
    var pointer: GlassPointer? = null
        private set
    private var settle: ValueAnimator? = null

    fun touch(view: View, event: MotionEvent) {
        if (view.width <= 0 || view.height <= 0) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                settle?.cancel()
                pointer = GlassPointer(event.rawX, event.rawY, 1f)
                redraw()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val last = pointer ?: return
                settle?.cancel()
                settle = ValueAnimator.ofFloat(last.amount, 0f).apply {
                    duration = 210
                    interpolator = PathInterpolator(0.20f, 0.68f, 0.26f, 1f)
                    addUpdateListener {
                        val amount = it.animatedValue as Float
                        pointer = if (amount > 0.005f) last.copy(amount = amount) else null
                        redraw()
                    }
                    start()
                }
            }
        }
    }

    fun clear() {
        settle?.cancel()
        settle = null
        if (pointer == null) return
        pointer = null
        redraw()
    }
}
