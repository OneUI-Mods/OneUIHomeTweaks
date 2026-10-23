package dev.ryuya.oneuihometweaks;

import android.graphics.Canvas;
import android.view.*;
import android.widget.FrameLayout;
import java.lang.reflect.Method;

/** Draws retained native View controls into a second canvas without reparenting or relayout. */
final class QuickPanelPreviewAndroid16 implements QuickPanelNativePreview.Renderer {
  private final View[] tracks = new View[2], buttons = new View[2];

  QuickPanelPreviewAndroid16(ClassLoader loader, Runnable ready) throws Exception {
    for (int kind = 0; kind < 2; kind++) {
      final int index = kind;
      Class<?> type =
          Class.forName(
              "com.android.systemui.qs.bar." + (kind == 0 ? "BrightnessBar" : "VolumeBar"),
              false,
              loader);
      Method layout = null;
      for (Method candidate : type.getDeclaredMethods())
        if (candidate.getName().equals("updateLayout") && candidate.getParameterCount() == 4)
          layout = candidate;
      if (layout == null) throw new NoSuchMethodException("Native View layout");
      ModernHook.hookMethod(
          layout,
          new ModernHook.Callback() {
            @Override
            protected void afterHookedMethod(ModernHook.Param p) {
              if (p.hasThrowable()) return;
              tracks[index] = (View) p.args[index == 0 ? 3 : 2];
              buttons[index] = (View) p.args[index == 0 ? 2 : 1];
              ready.run();
            }
          });
    }
  }

  @Override
  public boolean ready() {
    return tracks[0] != null && tracks[1] != null;
  }

  @Override
  public void populate(FrameLayout root) {
    root.addView(
        new View(root.getContext()) {
          @Override
          protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            for (int i = 0; i < 2; i++) {
              float width = getWidth() / 2f;
              drawNative(canvas, tracks[i], i * width, 0, width, getHeight() * .76f);
              drawNative(
                  canvas, buttons[i], i * width, getHeight() * .76f, width, getHeight() * .24f);
            }
          }
        },
        new FrameLayout.LayoutParams(-1, -1));
  }

  private static void drawNative(Canvas canvas, View source, float x, float y, float w, float h) {
    if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) return;
    android.graphics.RectF bounds =
        new android.graphics.RectF(0, 0, source.getWidth(), source.getHeight());
    source.getMatrix().mapRect(bounds);
    if (bounds.width() <= 0 || bounds.height() <= 0) return;
    float scale = Math.min(w / bounds.width(), h / bounds.height());
    canvas.save();
    canvas.translate(x + (w - bounds.width() * scale) / 2, y + (h - bounds.height() * scale) / 2);
    canvas.scale(scale, scale);
    canvas.translate(-bounds.left, -bounds.top);
    canvas.concat(source.getMatrix());
    source.draw(canvas);
    canvas.restore();
  }

  @Override
  public void clear(FrameLayout root) {
    root.removeAllViews();
  }
}
