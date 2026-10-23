package dev.ryuya.oneuihometweaks;

import android.annotation.TargetApi;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Method;

/** Rebuilds one owned event so raw deltas use the same configured window axes as local deltas. */
@TargetApi(36)
final class RecentsLocalMotion {
  private static final Method APPLY, DISPLAY_ID;

  static {
    try {
      APPLY = MotionEvent.class.getMethod("applyTransform", Matrix.class);
      DISPLAY_ID = MotionEvent.class.getMethod("getDisplayId");
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static MotionEvent rebase(View view, MotionEvent original) throws Exception {
    Matrix localToWindow = new Matrix();
    view.transformMatrixToGlobal(localToWindow);
    float[] point = {original.getX(), original.getY()};
    localToWindow.mapPoints(point);
    if (Math.abs(point[0] - original.getRawX()) < .01f
        && Math.abs(point[1] - original.getRawY()) < .01f) return null;
    Matrix windowToLocal = new Matrix();
    if (!localToWindow.invert(windowToLocal))
      throw new IllegalArgumentException("Input matrix is singular");
    int count = original.getPointerCount(), history = original.getHistorySize();
    if (count < 1 || count > 32 || history > 256)
      throw new IllegalArgumentException("Input batch changed");
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
    for (int i = 0; i < count; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      original.getPointerProperties(i, properties[i]);
      coords[i] = new MotionEvent.PointerCoords();
    }
    sample(original, coords, history == 0 ? -1 : 0);
    long firstTime = history == 0 ? original.getEventTime() : original.getHistoricalEventTime(0);
    MotionEvent copy =
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
            (Integer) DISPLAY_ID.invoke(original),
            original.getFlags(),
            original.getClassification());
    if (copy == null) throw new IllegalStateException("Input copy failed");
    try {
      for (int i = 1; i <= history; i++) {
        sample(original, coords, i == history ? -1 : i);
        copy.addBatch(
            i == history ? original.getEventTime() : original.getHistoricalEventTime(i),
            coords,
            original.getMetaState());
      }
      // applyTransform changes all stored samples (including raw axes); transform changes only
      // the local transform. Pairing them preserves local points while rebasing raw coordinates.
      APPLY.invoke(copy, localToWindow);
      copy.transform(windowToLocal);
      for (int i = 0; i < count; i++)
        if (Math.abs(copy.getX(i) - original.getX(i)) > .01f
            || Math.abs(copy.getY(i) - original.getY(i)) > .01f)
          throw new IllegalStateException("Local input changed");
      return copy;
    } catch (Throwable e) {
      copy.recycle();
      if (e instanceof Exception x) throw x;
      throw new IllegalStateException(e);
    }
  }

  private static void sample(MotionEvent e, MotionEvent.PointerCoords[] coords, int history) {
    for (int i = 0; i < coords.length; i++)
      if (history < 0) e.getPointerCoords(i, coords[i]);
      else e.getHistoricalPointerCoords(i, history, coords[i]);
  }
}
