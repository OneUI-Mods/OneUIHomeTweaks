package dev.ryuya.oneuihometweaks;

import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Places the owned backdrop blur below the live app, so video colors cannot bleed outside its card.
 */
final class RecentsLiveBlur {
  private final Class<?> blurType, builderType;
  private final Method setRadius,
      setBackgroundBlur,
      setParent,
      setName,
      setEffect,
      build,
      cropLayer,
      relativeLayer,
      showLayer,
      hideLayer,
      removeLayer;
  private final Method viewRoot, rootSurface, rootView;
  private final Field radius, viewPath;
  private final List<Backdrop> backdrops = new ArrayList<>();
  private SurfaceControl effectLayer, appLeash;
  private Rect viewport;
  private boolean active;
  private int blurRadius;

  private record Backdrop(View view, Drawable drawable, int radius) {}

  RecentsLiveBlur(ClassLoader loader) throws Exception {
    blurType =
        Class.forName(
            "com.android.internal.graphics.drawable.BackgroundBlurDrawable", false, loader);
    setRadius = blurType.getMethod("setBlurRadius", int.class);
    radius = blurType.getDeclaredField("mBlurRadius");
    radius.setAccessible(true);
    viewPath = View.class.getDeclaredField("mClipPath");
    viewPath.setAccessible(true);
    viewRoot = View.class.getDeclaredMethod("getViewRootImpl");
    viewRoot.setAccessible(true);
    rootSurface = viewRoot.getReturnType().getMethod("getSurfaceControl");
    rootView = viewRoot.getReturnType().getMethod("getView");
    builderType = Class.forName("android.view.SurfaceControl$Builder", false, loader);
    builderType.getConstructor();
    setParent = builderType.getMethod("setParent", SurfaceControl.class);
    setName = builderType.getMethod("setName", String.class);
    setEffect = builderType.getMethod("setEffectLayer");
    build = builderType.getMethod("build");
    cropLayer =
        SurfaceControl.Transaction.class.getMethod(
            "setWindowCrop", SurfaceControl.class, Rect.class);
    relativeLayer =
        SurfaceControl.Transaction.class.getMethod(
            "setRelativeLayer", SurfaceControl.class, SurfaceControl.class, int.class);
    showLayer = SurfaceControl.Transaction.class.getMethod("show", SurfaceControl.class);
    hideLayer = SurfaceControl.Transaction.class.getMethod("hide", SurfaceControl.class);
    removeLayer = SurfaceControl.Transaction.class.getMethod("remove", SurfaceControl.class);
    setBackgroundBlur =
        SurfaceControl.Transaction.class.getMethod(
            "setBackgroundBlurRadius", SurfaceControl.class, int.class);
  }

  boolean bind(View root, SurfaceControl app) throws Exception {
    clear();
    collect(root, root, 0);
    if (backdrops.isEmpty()) return false;
    for (Backdrop b : backdrops) {
      if ((Path) viewPath.get(b.view()) != null || b.radius() < 0 || b.radius() > 1000)
        return false;
      if (blurRadius != 0 && b.radius() != blurRadius) return false;
      blurRadius = b.radius();
    }
    viewport = new Rect(0, 0, root.getWidth(), root.getHeight());
    appLeash = app;
    if (blurRadius > 0) {
      Object builder = builderType.getConstructor().newInstance();
      setName.invoke(builder, "OneUiRecentsWallpaperBlur");
      setEffect.invoke(builder);
      Object owner = viewRoot.invoke(root);
      if (owner == null) return false;
      SurfaceControl parent = (SurfaceControl) rootSurface.invoke(owner);
      if (rootView.invoke(owner) != root || parent == null || !parent.isValid()) return false;
      setParent.invoke(builder, parent);
      effectLayer = (SurfaceControl) build.invoke(builder);
      if (!effectLayer.isValid()) return false;
    }
    return true;
  }

  private void collect(View root, View view, int depth) throws Exception {
    if (depth > 32) return;
    Drawable drawable = view.getBackground();
    if (blurType.isInstance(drawable)
        && view.getWidth() >= root.getWidth() * .9f
        && view.getHeight() >= root.getHeight() * .9f)
      backdrops.add(new Backdrop(view, drawable, radius.getInt(drawable)));
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) collect(root, group.getChildAt(i), depth + 1);
  }

  boolean valid() {
    for (Backdrop b : backdrops) if (b.view().getBackground() != b.drawable()) return false;
    return !backdrops.isEmpty() && (effectLayer == null || effectLayer.isValid());
  }

  void apply(SurfaceControl.Transaction tx) throws Exception {
    if (!valid()) throw new IllegalStateException("Native backdrop changed");
    boolean first = !active;
    active = true;
    if (first) for (Backdrop b : backdrops) setRadius.invoke(b.drawable(), 0);
    if (effectLayer != null) {
      cropLayer.invoke(tx, effectLayer, viewport);
      setBackgroundBlur.invoke(tx, effectLayer, blurRadius);
      relativeLayer.invoke(tx, effectLayer, appLeash, -1);
      tx.setAlpha(effectLayer, 1f);
      showLayer.invoke(tx, effectLayer);
    }
    if (first) HookEntry.log("RECENTS_LIVE_BLUR_SEPARATED radius=" + blurRadius);
  }

  void restore(SurfaceControl.Transaction tx) throws Exception {
    if (!active) return;
    for (Backdrop b : backdrops)
      if (b.view().getBackground() == b.drawable()) setRadius.invoke(b.drawable(), b.radius());
    if (effectLayer != null && effectLayer.isValid()) hideLayer.invoke(tx, effectLayer);
    active = false;
  }

  void clear() {
    try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
      try {
        restore(tx);
      } catch (Throwable error) {
        HookEntry.log("RECENTS_LIVE_BLUR_RESTORE_ERROR " + error);
      }
      if (effectLayer != null && effectLayer.isValid()) removeLayer.invoke(tx, effectLayer);
      tx.apply();
    } catch (Throwable error) {
      HookEntry.log("RECENTS_LIVE_BLUR_REMOVE_ERROR " + error);
    }
    if (effectLayer != null) effectLayer.release();
    effectLayer = appLeash = null;
    viewport = null;
    backdrops.clear();
    blurRadius = 0;
    active = false;
  }
}
