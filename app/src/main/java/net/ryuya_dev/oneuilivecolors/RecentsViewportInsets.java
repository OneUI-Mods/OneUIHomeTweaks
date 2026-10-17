package net.ryuya_dev.oneuilivecolors;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Insets;
import android.view.WindowInsets;
import android.view.WindowManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Supplies window-local insets only to the portrait Activity's synchronous style calculation. */
final class RecentsViewportInsets {
  private final List<ModernHook.Handle> hooks = new ArrayList<>();
  private final ThreadLocal<Activity> calculating = new ThreadLocal<>();
  private boolean enabled;
  private Class<?> recents;
  private Field calculator, sceneCalculator, layoutCalculator;
  private Method standardMask, sceneMask, stageMask, calculateScene, calculateLayout, mutableState;
  private Constructor<?> insetsData;
  private int logs;

  void install(String apk, ClassLoader loader) throws Exception {
    recents = Class.forName("com.android.quickstep.RecentsActivity", false, loader);
    Class<?> styler =
        Class.forName("com.honeyspace.ui.common.recentstyler.RecentStyler", false, loader);
    Class<?> manager =
        Class.forName(
            "com.honeyspace.ui.common.recents.repository.insetsmanager.RecentInsetsManagerImpl",
            false,
            loader);
    Method create = declared(styler, "createStyleData", Context.class);
    Method state = manager.getMethod("getInsetsDataState");
    calculator = field(manager, "insetsDataCalculator");
    Class<?> impl =
        Class.forName(
            "com.honeyspace.ui.common.recents.repository.insetsmanager.InsetsDataCalculatorImpl",
            false,
            loader);
    sceneCalculator = field(impl, "sceneInsetsCalculator");
    layoutCalculator = field(impl, "layoutInsetsCalculator");
    calculateScene =
        sceneCalculator.getType().getMethod("calculate", WindowInsets.class, int.class, int.class);
    calculateLayout = layoutCalculator.getType().getMethod("calculate", Insets.class);
    standardMask = declared(manager, "getStandardInsetsTypeMask", boolean.class);
    sceneMask = declared(manager, "getSceneInsetsTypeMask");
    stageMask = declared(manager, "getStageTypeMask");
    insetsData =
        Class.forName("com.honeyspace.common.recents.insetsmanager.InsetsData", false, loader)
            .getConstructor(Insets.class, Insets.class, Insets.class, Insets.class);
    mutableState =
        Class.forName("kotlinx.coroutines.flow.StateFlowKt", false, loader)
            .getMethod("MutableStateFlow", Object.class);
    DexEvidence.deoptimizeRecentsCoordinateCalls(apk, loader, List.of(create, state));
    hooks.add(
        ModernHook.hookMethod(
            create,
            new ModernHook.Callback() {
              @Override
              protected void beforeHookedMethod(ModernHook.Param p) {
                p.setObjectExtra("previousActivity", calculating.get());
                if (enabled
                    && p.args[0] instanceof Activity a
                    && recents.isInstance(a)
                    && a.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    && a.getDisplay() != null
                    && a.getDisplay().getDisplayId() == 0
                    && !a.isInMultiWindowMode()
                    && !a.isInPictureInPictureMode()) calculating.set(a);
                else calculating.remove();
              }

              @Override
              protected void afterHookedMethod(ModernHook.Param p) {
                Activity previous = (Activity) p.getObjectExtra("previousActivity");
                if (previous == null) calculating.remove();
                else calculating.set(previous);
              }
            }));
    hooks.add(
        ModernHook.hookMethod(
            state,
            new ModernHook.Callback() {
              @Override
              protected void afterHookedMethod(ModernHook.Param p) {
                Activity a = calculating.get();
                if (!enabled || a == null || p.hasThrowable()) return;
                try {
                  var metrics = a.getSystemService(WindowManager.class).getCurrentWindowMetrics();
                  var bounds = metrics.getBounds();
                  WindowInsets window = metrics.getWindowInsets();
                  Insets system =
                      window.getInsetsIgnoringVisibility(
                          WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
                  // Reject metrics still expressed in the physical landscape display's axes.
                  if (bounds.width() >= bounds.height()
                      || system.top == 0
                      || system.left != 0
                      || system.right != 0) {
                    if (logs++ < 8)
                      HookEntry.log(
                          "RECENTS_VIEWPORT_INSETS_PENDING bounds=" + bounds + " system=" + system);
                    return;
                  }
                  Object calc = calculator.get(p.thisObject);
                  Insets standard =
                      window.getInsetsIgnoringVisibility(
                          (Integer) standardMask.invoke(p.thisObject, true));
                  Insets scene =
                      (Insets)
                          calculateScene.invoke(
                              sceneCalculator.get(calc),
                              window,
                              (Integer) sceneMask.invoke(p.thisObject),
                              0);
                  Insets layout =
                      (Insets) calculateLayout.invoke(layoutCalculator.get(calc), standard);
                  Insets stage =
                      window.getInsetsIgnoringVisibility((Integer) stageMask.invoke(p.thisObject));
                  Object value = insetsData.newInstance(standard, scene, layout, stage);
                  p.setResult(mutableState.invoke(null, value));
                  if (logs++ < 8) HookEntry.log("RECENTS_VIEWPORT_INSETS_LOCAL " + value);
                } catch (Throwable error) {
                  // This call uses its original native state when the scoped calculation is
                  // unavailable.
                  if (logs++ < 8) HookEntry.log("RECENTS_VIEWPORT_INSETS_NATIVE " + error);
                }
              }
            }));
  }

  void setEnabled(boolean value) {
    enabled = value;
  }

  void uninstall() {
    enabled = false;
    calculating.remove();
    for (var hook : hooks)
      try {
        hook.unhook();
      } catch (Throwable ignored) {
      }
    hooks.clear();
  }

  private static Method declared(Class<?> type, String name, Class<?>... args) throws Exception {
    Method method = type.getDeclaredMethod(name, args);
    method.setAccessible(true);
    return method;
  }

  private static Field field(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }
}
