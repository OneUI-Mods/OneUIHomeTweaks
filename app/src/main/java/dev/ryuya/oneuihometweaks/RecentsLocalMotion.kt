package dev.ryuya.oneuihometweaks

import android.annotation.TargetApi
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import android.view.View
import java.lang.reflect.Method
import kotlin.jvm.JvmStatic
import kotlin.math.abs

/* Rebuilds one owned event so raw deltas use the same configured window axes as local deltas. */
@TargetApi(36)
object RecentsLocalMotion {
    private val APPLY: Method
    private val DISPLAY_ID: Method

    init {
        try {
            APPLY = MotionEvent::class.java.getMethod("applyTransform", Matrix::class.java)
            DISPLAY_ID = MotionEvent::class.java.getMethod("getDisplayId")
        } catch (e: ReflectiveOperationException) {
            throw ExceptionInInitializerError(e)
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun rebase(view: View, original: MotionEvent): MotionEvent? {
        val localToWindow = Matrix()
        view.transformMatrixToGlobal(localToWindow)
        val point = floatArrayOf(original.getX(), original.getY())
        localToWindow.mapPoints(point)
        if (abs(point[0] - original.getRawX()) < .01f && abs(point[1] - original.getRawY()) < .01f)
            return null
        val windowToLocal = Matrix()
        require(localToWindow.invert(windowToLocal)) { "Input matrix is singular" }
        val count = original.getPointerCount()
        val history = original.getHistorySize()
        require(!(count < 1 || count > 32 || history > 256)) { "Input batch changed" }
        val properties = arrayOfNulls<PointerProperties>(count)
        val coords = arrayOfNulls<PointerCoords>(count)
        for (i in 0 until count) {
            properties[i] = PointerProperties()
            original.getPointerProperties(i, properties[i])
            coords[i] = PointerCoords()
        }
        RecentsLocalMotion.sample(original, coords, if (history == 0) -1 else 0)
        val firstTime: Long =
            if (history == 0) original.getEventTime() else original.getHistoricalEventTime(0)
        val copy =
            MotionEvent.obtain(
                original.getDownTime(),
                firstTime,
                original.getAction(),
                count,
                properties,
                coords,
                original.getMetaState(),
                original.getButtonState(),
                original.getXPrecision(),
                original.getYPrecision(),
                original.getDeviceId(),
                original.getEdgeFlags(),
                original.getSource(),
                RecentsLocalMotion.DISPLAY_ID.invoke(original) as Int,
                original.getFlags(),
                original.getClassification(),
            )
        checkNotNull(copy) { "Input copy failed" }
        try {
            for (i in 1..history) {
                RecentsLocalMotion.sample(original, coords, if (i == history) -1 else i)
                copy.addBatch(
                    if (i == history) original.getEventTime()
                    else original.getHistoricalEventTime(i),
                    coords,
                    original.getMetaState(),
                )
            }
            // applyTransform changes all stored samples (including raw axes); transform changes
            // only
            // the local transform. Pairing them preserves local points while rebasing raw
            // coordinates.
            RecentsLocalMotion.APPLY.invoke(copy, localToWindow)
            copy.transform(windowToLocal)
            for (i in 0 until count) check(
                !(abs(copy.getX(i) - original.getX(i)) > .01f ||
                    abs(copy.getY(i) - original.getY(i)) > .01f)
            ) {
                "Local input changed"
            }
            return copy
        } catch (e: Throwable) {
            copy.recycle()
            if (e is Exception) throw e
            throw IllegalStateException(e)
        }
    }

    @JvmStatic
    private fun sample(e: MotionEvent, coords: Array<PointerCoords?>, history: Int) {
        for (i in coords.indices) if (history < 0) e.getPointerCoords(i, coords[i])
        else e.getHistoricalPointerCoords(i, history, coords[i])
    }
}
