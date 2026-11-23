package dev.ryuya.oneuihometweaks

import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Trace
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** Samples the stock icon planes in the coordinate space of the window's SurfaceControl. */
internal class LauncherIconGeometry {
    private val viewToSurface = Matrix()
    private val values = FloatArray(9)
    private val surfaceOrigin = IntArray(2)
    private val viewOrigin = IntArray(2)
    private val outline = Outline()
    private val rect = Rect()

    fun trace(transition: Int, frame: Int, container: ViewGroup) {
        if (!container.isAttachedToWindow) return
        val window = container.rootView
        window.getLocationInSurface(surfaceOrigin)
        val label = "OneUI plane $transition.$frame"
        traceView("$label group", window, container)
        container.getChildAt(0)?.let { traceView("$label back", window, it) }
        container.getChildAt(1)?.let { traceView("$label front", window, it) }
        container.getChildAt(2)?.let { traceView("$label center", window, it) }
    }

    private fun traceView(label: String, window: View, view: View) {
        viewToSurface.reset()
        view.transformMatrixToGlobal(viewToSurface)
        window.transformMatrixToLocal(viewToSurface)
        viewToSurface.postTranslate(surfaceOrigin[0].toFloat(), surfaceOrigin[1].toFloat())
        viewToSurface.getValues(values)
        if (!viewToSurface.isAffine || values.any { !it.isFinite() }) return
        view.getLocationInSurface(viewOrigin)
        val a = (values[Matrix.MSCALE_X] * 1000f).roundToInt()
        val b = (values[Matrix.MSKEW_X] * 1000f).roundToInt()
        val c = (values[Matrix.MSKEW_Y] * 1000f).roundToInt()
        val d = (values[Matrix.MSCALE_Y] * 1000f).roundToInt()
        val x = values[Matrix.MTRANS_X].roundToInt()
        val y = values[Matrix.MTRANS_Y].roundToInt()
        val dx = x - viewOrigin[0]
        val dy = y - viewOrigin[1]
        val alpha = (view.alpha * 1000f).roundToInt()
        mark("$label transform $a,$b,$c,$d,$x,$y $dx,$dy $alpha ${view.visibility}")

        val asset = when (val drawable = view.background) {
            is BitmapDrawable -> drawable.bitmap?.let { "bitmap-${it.config}-${it.width}x${it.height}" } ?: "no-bitmap"
            is ColorDrawable -> if (drawable.alpha == 0) "transparent" else "color"
            null -> "none"
            else -> drawable.javaClass.simpleName
        }
        mark("$label asset ${view.width}x${view.height} $asset")
        if (!view.clipToOutline) return
        outline.setEmpty()
        view.outlineProvider?.getOutline(view, outline)
        if (!outline.getRect(rect)) {
            mark("$label clip ${if (outline.isEmpty) "empty" else "path"}")
            return
        }
        mark("$label clip ${rect.left},${rect.top},${rect.right},${rect.bottom} ${(outline.radius * 100f).roundToInt()}")
    }

    private fun mark(value: String) {
        Trace.beginSection(value)
        Trace.endSection()
    }
}
