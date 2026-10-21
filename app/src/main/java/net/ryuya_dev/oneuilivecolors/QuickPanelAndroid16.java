package net.ryuya_dev.oneuilivecolors;

import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;

/** AZHL View contract. Does not share the Android 17 Compose assumptions. */
final class QuickPanelAndroid16 implements QuickPanelHooks.Adapter {
  private final QuickPanelHooks owner;
  private final Map<Object, Contract> bars = new WeakHashMap<>();
  private final Map<View, float[]> originalScales = new WeakHashMap<>();
  private boolean refreshing;

  private record Contract(
      int kind,
      Method update,
      Field container,
      Field tiles,
      Field brightnessController,
      Method sliderHeight,
      Field slider) {}

  QuickPanelAndroid16(QuickPanelHooks owner, ClassLoader loader) throws Exception {
    this.owner = owner;
    for (int kind = 0; kind < 2; kind++) {
      Class<?> type =
          Class.forName(
              "com.android.systemui.qs.bar." + (kind == 0 ? "BrightnessBar" : "VolumeBar"),
              false,
              loader);
      Field controller = kind == 0 ? type.getDeclaredField("mSecBrightnessSliderController") : null;
      Method height =
          controller != null
              ? controller.getType().getDeclaredMethod("updateSliderHeight", int.class)
              : null;
      Contract c =
          new Contract(
              kind,
              type.getDeclaredMethod("updateHeightMargins"),
              type.getDeclaredField("mSliderContainer"),
              type.getDeclaredField("mTileLayout"),
              controller,
              height,
              kind == 1 ? type.getDeclaredField("mSlider") : null);
      // Both native classes recreate their track geometry in updateLayout; clone paths use it too.
      Method layout = null;
      for (Method candidate : type.getDeclaredMethods()) {
        if (candidate.getName().equals("updateLayout") && candidate.getParameterCount() == 4)
          layout = candidate;
      }
      if (layout == null) throw new NoSuchMethodException("View bar layout");
      owner.add(
          ModernHook.hookMethod(
              layout,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (p.hasThrowable()) return;
                  bars.put(p.thisObject, c);
                  try {
                    apply(
                        p.thisObject,
                        c,
                        (ViewGroup) p.args[c.kind == 0 ? 3 : 2],
                        (LinearLayout) p.args[c.kind == 0 ? 2 : 1]);
                  } catch (Throwable error) {
                    owner.fail(error);
                  }
                }
              }));
    }
  }

  private void apply(Object bar, Contract c, ViewGroup track, LinearLayout tiles) throws Exception {
    if (track == null) return;
    ViewGroup.LayoutParams lp = track.getLayoutParams();
    if (lp.height <= 0) return;
    int nativeHeight = lp.height;
    int percent = owner.size(c.kind);
    if (percent != 100) {
      float density = track.getResources().getDisplayMetrics().density;
      int target =
          Math.round(QuickPanelPolicy.dimension(nativeHeight / density, percent) * density);
      lp.height = target;
      track.setLayoutParams(lp);
      if (c.brightnessController != null) {
        Object controller = c.brightnessController.get(bar);
        if (controller != null) c.sliderHeight.invoke(controller, target);
      } else {
        Object slider = c.slider.get(bar);
        if (slider instanceof android.widget.SeekBar seek) seek.setMaxHeight(target);
      }
    }
    if (tiles == null) return;
    int button = owner.size(c.kind + 2);
    for (int i = 0; i < tiles.getChildCount(); i++) {
      View child = tiles.getChildAt(i);
      if (button == 100) {
        float[] nativeScale = originalScales.remove(child);
        if (nativeScale != null) {
          child.setScaleX(nativeScale[0]);
          child.setScaleY(nativeScale[1]);
        }
      } else {
        float[] nativeScale =
            originalScales.computeIfAbsent(child, v -> new float[] {v.getScaleX(), v.getScaleY()});
        float factor = button / 100f;
        child.setScaleX(nativeScale[0] * factor);
        child.setScaleY(nativeScale[1] * factor);
      }
    }
  }

  @Override
  public void refresh() {
    if (refreshing) return;
    refreshing = true;
    try {
      for (var entry : new ArrayList<>(bars.entrySet()))
        entry.getValue().update.invoke(entry.getKey());
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException(error);
    } finally {
      refreshing = false;
    }
  }

  @Override
  public void close() {
    for (var entry : new ArrayList<>(originalScales.entrySet())) {
      entry.getKey().setScaleX(entry.getValue()[0]);
      entry.getKey().setScaleY(entry.getValue()[1]);
    }
    originalScales.clear();
    refresh();
  }
}
