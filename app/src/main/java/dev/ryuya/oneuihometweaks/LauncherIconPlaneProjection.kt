package dev.ryuya.oneuihometweaks

import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi

/** Projects a stock icon frame into its parent surface using a captured, axis-aligned hierarchy. */
@RequiresApi(29)
internal class LauncherIconPlaneProjection private constructor(
    private val windowToHome: Matrix,
    private val parentToSurface: Matrix,
    private val groupLayout: Layout,
    private val frontLayout: Layout,
    private val bufferScaleX: Float,
    private val bufferScaleY: Float,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val useCurrentDrawable: Boolean,
    private val fixedOrientation: Boolean,
    private val rtl: Boolean,
) {
    private val mappedBounds = RectF()
    private val transform = Matrix()
    private val matrixValues = FloatArray(9)

    fun copyForWorker() = LauncherIconPlaneProjection(Matrix(windowToHome), Matrix(parentToSurface), groupLayout,
        frontLayout, bufferScaleX, bufferScaleY, screenWidth, screenHeight, useCurrentDrawable, fixedOrientation, rtl)

    fun project(frame: Frame): Position? {
        if (!frame.groupScale.isFinite() || frame.groupScale <= 0f || frame.alpha !in 0f..1f ||
            !frame.foregroundScale.isFinite() || frame.foregroundScale <= 0f) return null
        setGroupTransform(frame)
        setForegroundTransform(frame)
        return readPosition(frame.alpha, bufferScaleX, bufferScaleY)
    }

    fun projectGroup(frame: Frame, alpha: Float): Position? {
        if (!frame.groupScale.isFinite() || frame.groupScale <= 0f || alpha !in 0f..1f) return null
        setGroupTransform(frame)
        return readPosition(alpha, 1f, 1f)
    }

    fun projectForegroundWithinGroup(frame: Frame): Position? {
        if (frame.alpha !in 0f..1f || !frame.foregroundScale.isFinite() || frame.foregroundScale <= 0f) return null
        transform.reset()
        setForegroundTransform(frame)
        return readPosition(frame.alpha, bufferScaleX, bufferScaleY)
    }

    private fun setGroupTransform(frame: Frame) {
        mappedBounds.set(frame.bounds)
        windowToHome.mapRect(mappedBounds)
        transform.set(parentToSurface)
        transform.preTranslate(groupLayout.left, groupLayout.top)
        applyTransform(groupLayout, mappedBounds.left, mappedBounds.top, frame.groupScale)
    }

    private fun setForegroundTransform(frame: Frame) {
        val foreground = calculateForeground(frame)
        transform.preTranslate(frontLayout.left, frontLayout.top)
        applyTransform(frontLayout, foreground.x, foreground.y, foreground.scale)
    }

    private fun readPosition(alpha: Float, widthScale: Float, heightScale: Float): Position? {
        transform.getValues(matrixValues)
        val x = matrixValues[Matrix.MTRANS_X]
        val y = matrixValues[Matrix.MTRANS_Y]
        val sx = matrixValues[Matrix.MSCALE_X] * widthScale
        val sy = matrixValues[Matrix.MSCALE_Y] * heightScale
        if (!x.isFinite() || !y.isFinite() || !sx.isFinite() || !sy.isFinite() || sx <= 0f || sy <= 0f) return null
        return Position(x, y, sx, sy, alpha)
    }

    private fun applyTransform(layout: Layout, translationX: Float, translationY: Float, scale: Float) {
        transform.preTranslate(translationX, translationY)
        transform.preScale(scale, scale, layout.pivotX, layout.pivotY)
    }

    private fun calculateForeground(frame: Frame): Foreground {
        if (useCurrentDrawable) return Foreground(0f, 0f, 1f)
        val scale = frame.foregroundScale
        val portrait = screenWidth < screenHeight
        val shortSide = if (portrait) screenWidth else screenHeight
        val background = if (portrait) frame.backgroundHeight else frame.backgroundWidth
        val expanded = maxOf(scale, scale * (background.toFloat() / shortSide))
        val fullOffset = (expanded - 1f) * shortSide
        val half = fullOffset / 2f
        val small = ((scale - 1f) * shortSide) / 2f
        if (portrait) return Foreground(if (fixedOrientation) -small else -half, if (fixedOrientation) -half else -small, expanded)
        val x = when {
            rtl && fixedOrientation -> half - fullOffset
            rtl -> small - fullOffset
            fixedOrientation -> -half
            else -> -small
        }
        return Foreground(x, if (fixedOrientation) -small else -half, expanded)
    }

    internal data class Frame(
        val bounds: RectF,
        val groupScale: Float,
        val foregroundScale: Float,
        val backgroundWidth: Int,
        val backgroundHeight: Int,
        val alpha: Float,
    )

    internal data class Position(val x: Float, val y: Float, val scaleX: Float, val scaleY: Float, val alpha: Float)
    private data class Layout(val left: Float, val top: Float, val pivotX: Float, val pivotY: Float)
    private data class Foreground(val x: Float, val y: Float, val scale: Float)

    companion object {
        fun capture(group: ViewGroup, front: View, bufferWidth: Int, bufferHeight: Int, windowToHome: Matrix,
            screenWidth: Int, screenHeight: Int, useCurrentDrawable: Boolean, fixedOrientation: Boolean): LauncherIconPlaneProjection? {
            val parent = group.parent as? View ?: return null
            if (front.parent !== group || !front.isAttachedToWindow || screenWidth <= 0 || screenHeight <= 0 ||
                bufferWidth <= 0 || bufferHeight <= 0 || front.width <= 0 || front.height <= 0 ||
                hasRotation(front) || hasRotation(group)) return null
            val root = front.rootView
            val origin = IntArray(2)
            root.getLocationInSurface(origin)
            val parentTransform = Matrix()
            parent.transformMatrixToGlobal(parentTransform)
            root.transformMatrixToLocal(parentTransform)
            parentTransform.postTranslate(origin[0].toFloat(), origin[1].toFloat())
            val values = FloatArray(9)
            parentTransform.getValues(values)
            if (!parentTransform.isAffine || values[Matrix.MSKEW_X] != 0f || values[Matrix.MSKEW_Y] != 0f ||
                values[Matrix.MSCALE_X] <= 0f || values[Matrix.MSCALE_Y] <= 0f || values.any { !it.isFinite() }) return null

            val groupLayout = Layout((group.left - parent.scrollX).toFloat(), (group.top - parent.scrollY).toFloat(),
                group.pivotX, group.pivotY)
            val frontLayout = Layout((front.left - group.scrollX).toFloat(), (front.top - group.scrollY).toFloat(),
                front.pivotX, front.pivotY)
            return LauncherIconPlaneProjection(Matrix(windowToHome), parentTransform, groupLayout, frontLayout,
                front.width.toFloat() / bufferWidth, front.height.toFloat() / bufferHeight, screenWidth, screenHeight,
                useCurrentDrawable, fixedOrientation, front.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        }

        private fun hasRotation(view: View): Boolean = view.rotation != 0f || view.rotationX != 0f || view.rotationY != 0f
    }
}
