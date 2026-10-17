package net.ryuya_dev.oneuilivecolors;

import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Excludes only the live card from the owned Recents backdrop's native blur. */
final class RecentsLiveBlur {
  private final Class<?> blurType;
  private final Method setPath;
  private final Field viewPath;
  private final List<Backdrop> backdrops = new ArrayList<>();
  private String previous;

  private record Backdrop(View view, Drawable drawable, Path original) {}

  RecentsLiveBlur(ClassLoader loader) throws Exception {
    blurType =
        Class.forName(
            "com.android.internal.graphics.drawable.BackgroundBlurDrawable", false, loader);
    setPath = blurType.getMethod("setClipPath", Path.class);
    viewPath = View.class.getDeclaredField("mClipPath");
    viewPath.setAccessible(true);
  }

  boolean bind(View root) throws Exception {
    clear();
    collect(root, root, 0);
    return !backdrops.isEmpty();
  }

  private void collect(View root, View v, int depth) throws Exception {
    if (depth > 32) return;
    Drawable d = v.getBackground();
    if (blurType.isInstance(d)
        && v.getWidth() >= root.getWidth() * .9f
        && v.getHeight() >= root.getHeight() * .9f) {
      Path original = (Path) viewPath.get(v);
      backdrops.add(new Backdrop(v, d, original == null ? null : new Path(original)));
    }
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) collect(root, g.getChildAt(i), depth + 1);
  }

  boolean exclude(View scene, Path cardPath, String geometry) throws Exception {
    if (backdrops.isEmpty()) return false;
    for (Backdrop b : backdrops) if (b.view().getBackground() != b.drawable()) return false;
    if (geometry.equals(previous)) return true;
    Matrix sceneGlobal = new Matrix();
    scene.transformMatrixToGlobal(sceneGlobal);
    Path global = new Path(cardPath);
    global.transform(sceneGlobal);
    for (Backdrop b : backdrops) {
      Matrix bgGlobal = new Matrix();
      b.view().transformMatrixToGlobal(bgGlobal);
      Matrix inverse = new Matrix();
      if (!bgGlobal.invert(inverse)) return false;
      Path hole = new Path(global);
      hole.transform(inverse);
      Path region = new Path();
      region.addRect(0, 0, b.view().getWidth(), b.view().getHeight(), Path.Direction.CW);
      if (b.original() != null && !region.op(b.original(), Path.Op.INTERSECT)) return false;
      if (!region.op(hole, Path.Op.DIFFERENCE)) return false;
      setPath.invoke(b.drawable(), region);
      b.drawable().invalidateSelf();
    }
    previous = geometry;
    return true;
  }

  void clear() {
    for (Backdrop b : backdrops)
      try {
        if (b.view().getBackground() == b.drawable()) {
          setPath.invoke(b.drawable(), b.original());
          b.drawable().invalidateSelf();
        }
      } catch (Throwable error) {
        HookEntry.log("RECENTS_LIVE_BLUR_RESTORE_ERROR " + error);
      }
    backdrops.clear();
    previous = null;
  }
}
