package net.ryuya_dev.oneuilivecolors;

import android.graphics.Color;
import android.view.*;
import de.robv.android.xposed.*;
import java.lang.reflect.Method;
import java.util.*;

/** Optional drawer-only custom label override using Samsung's own icon API. */
final class DrawerBridge {
  private int mode = ColorPolicy.OFF;
  private boolean active;
  private Method setColor, getColor;
  private final Map<View, Saved> views = new WeakHashMap<>();

  void install(ClassLoader loader) throws Exception {
    Class<?> icon = Class.forName("com.honeyspace.ui.common.iconview.IconViewImpl", false, loader);
    Class<?> style = Class.forName("com.honeyspace.sdk.source.entity.ItemStyle", false, loader);
    setColor = icon.getMethod("setCustomLabelColor", Integer.class);
    getColor = icon.getMethod("getCustomLabelColor");
    XposedBridge.hookAllConstructors(
        icon,
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (p.hasThrowable()) return;
            View view = (View) p.thisObject;
            views.put(view, new Saved());
            view.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                  @Override
                  public void onViewAttachedToWindow(View v) {
                    apply(v);
                  }

                  @Override
                  public void onViewDetachedFromWindow(View v) {}
                });
          }
        });
    XposedBridge.hookMethod(
        icon.getMethod("setItemStyle", style),
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (!p.hasThrowable()) apply((View) p.thisObject);
          }
        });
    HookEntry.log("DRAWER_HOOK_READY native custom-label API");
  }

  void setMode(int mode, boolean active) {
    this.mode = mode;
    this.active = active;
    for (View view : new ArrayList<>(views.keySet())) apply(view);
  }

  private boolean drawer(View view) {
    for (ViewParent parent = view.getParent();
        parent instanceof View;
        parent = parent.getParent()) {
      String name = parent.getClass().getName();
      if (name.startsWith("com.honeyspace.ui.honeypots.applist.presentation.")
          || name.startsWith("com.honeyspace.ui.honeypots.verticalapplist.presentation."))
        return true;
    }
    return false;
  }

  private void apply(View view) {
    if (!view.isAttachedToWindow()) return;
    try {
      Saved saved = views.get(view);
      if (saved == null) {
        saved = new Saved();
        views.put(view, saved);
      }
      Integer desired = null;
      if (active && drawer(view)) {
        if (mode == ColorPolicy.BLACK) desired = Integer.valueOf(Color.BLACK);
        else if (mode == ColorPolicy.WHITE) desired = Integer.valueOf(Color.WHITE);
      }
      Integer current = (Integer) getColor.invoke(view);
      if (desired != null) {
        if (saved.applied == null || !Objects.equals(current, saved.applied))
          saved.original = current;
        setColor.invoke(view, desired);
        saved.applied = desired;
      } else if (saved.applied != null) {
        if (Objects.equals(current, saved.applied)) setColor.invoke(view, saved.original);
        saved.applied = null;
      }
    } catch (Throwable t) {
      HookEntry.log("DRAWER_ERROR " + t.getClass().getSimpleName());
    }
  }

  private static final class Saved {
    Integer original, applied;
  }
}
