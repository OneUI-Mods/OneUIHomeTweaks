package dev.ryuya.oneuihometweaks;

import android.app.Activity;
import android.app.Application;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Recents-window-only gesture bar policy. Never writes Samsung's global hint setting. */
final class RecentsBar {
  private final Map<WindowInsetsController, State> states = new WeakHashMap<>();
  private final List<ModernHook.Handle> hooks = new ArrayList<>();
  private final ThreadLocal<Boolean> ownRequest = ThreadLocal.withInitial(() -> false);
  private Class<?> recentsClass;
  private Method requestedTypes;
  private volatile boolean enabled;
  private volatile boolean failed;
  private String diagnostic = "unavailable";
  private volatile WeakReference<Activity> gestureOwner = new WeakReference<>(null);

  void install(Application app, ClassLoader loader) throws Exception {
    recentsClass = ModernHook.findClass("com.android.quickstep.RecentsActivity", loader);
    Class<?> controllerClass = ModernHook.findClass("android.view.InsetsController", null);
    requestedTypes = WindowInsetsController.class.getDeclaredMethod("getRequestedVisibleTypes");
    requestedTypes.setAccessible(true);
    if (!Activity.class.isAssignableFrom(recentsClass)
        || !WindowInsetsController.class.isAssignableFrom(controllerClass)
        || requestedTypes.getReturnType() != int.class
        || controllerClass.getDeclaredMethod("show", int.class).getReturnType() != void.class
        || controllerClass.getDeclaredMethod("hide", int.class).getReturnType() != void.class)
      throw new IllegalStateException("Recents insets contract changed");
    try {
      Class<?> gesture =
          ModernHook.findClass("com.honeyspace.gesture.usecase.SystemGestureUseCase", loader);
      Class<?> animation =
          ModernHook.findClass(
              "com.honeyspace.gesture.inputconsumer.RecentsAnimationState", loader);
      Method allowHidden = gesture.getDeclaredMethod("isAllowGestureIgnoringBarVisibility");
      if (allowHidden.getReturnType() != boolean.class)
        throw new IllegalStateException("Gesture visibility contract changed");
      for (Method caller :
          new Method[] {
            gesture.getDeclaredMethod("canStartSystemGesture", boolean.class),
            gesture.getDeclaredMethod("canStartWithNavHidden"),
            animation.getDeclaredMethod("canStartSystemGesture"),
            animation.getDeclaredMethod("canStartWithNavHidden")
          })
        if (!ModernHook.deoptimize(caller))
          throw new IllegalStateException("Cannot deoptimize gesture visibility caller");
      hooks.add(
          ModernHook.hookMethod(
              allowHidden,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (p.hasThrowable() || Boolean.TRUE.equals(p.getResult()) || failed) return;
                  Activity a = gestureOwner.get();
                  if (a != null
                      && !a.isDestroyed()
                      && a.hasWindowFocus()
                      && shouldHide(a)
                      && !a.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked())
                    p.setResult(true);
                }
              }));
      for (boolean show : new boolean[] {true, false})
        hooks.add(
            ModernHook.findAndHookMethod(
                controllerClass,
                show ? "show" : "hide",
                int.class,
                new ModernHook.Callback() {
                  @Override
                  protected void beforeHookedMethod(ModernHook.Param p) {
                    if (failed || ownRequest.get() || Looper.myLooper() != Looper.getMainLooper())
                      return;
                    State state = states.get(p.thisObject);
                    if (state == null
                        || ((int) p.args[0] & WindowInsets.Type.navigationBars()) == 0) return;
                    state.nativeVisible = show;
                    Activity a = state.activity.get();
                    if (show && state.active && a != null && shouldHide(a))
                      p.args[0] = (int) p.args[0] & ~WindowInsets.Type.navigationBars();
                  }
                }));
      hooks.add(
          ModernHook.findAndHookMethod(
              recentsClass,
              "onResume",
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (!p.hasThrowable()) apply((Activity) p.thisObject);
                }
              }));
      hooks.add(
          ModernHook.findAndHookMethod(
              Activity.class,
              "onWindowFocusChanged",
              boolean.class,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (p.hasThrowable() || !recentsClass.isInstance(p.thisObject)) return;
                  if ((boolean) p.args[0]) apply((Activity) p.thisObject);
                  else if (gestureOwner.get() == p.thisObject)
                    gestureOwner = new WeakReference<>(null);
                }
              }));
      hooks.add(
          ModernHook.findAndHookMethod(
              recentsClass,
              "onConfigurationChanged",
              android.content.res.Configuration.class,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (!p.hasThrowable()) apply((Activity) p.thisObject);
                }
              }));
      ContentObserver observer =
          new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean self, Uri uri) {
              refresh();
            }
          };
      app.getContentResolver()
          .registerContentObserver(Settings.Secure.getUriFor("navigation_mode"), false, observer);
      app.getContentResolver()
          .registerContentObserver(
              Settings.Global.getUriFor("navigation_bar_gesture_hint"), false, observer);
      diagnostic = "off";
      HookEntry.log("RECENTS_BAR_HOOK_READY window-local gesture bar policy");
    } catch (Throwable error) {
      for (var hook : hooks) hook.unhook();
      hooks.clear();
      if (error instanceof Exception e) throw e;
      throw new IllegalStateException(error);
    }
  }

  void setEnabled(boolean value) {
    enabled = value && !failed && !hooks.isEmpty();
    if (!enabled) gestureOwner = new WeakReference<>(null);
    if (!failed && !hooks.isEmpty()) diagnostic = enabled ? "ready" : "off";
    refresh();
  }

  String diagnostic() {
    return diagnostic;
  }

  private void refresh() {
    for (State s : new ArrayList<>(states.values())) {
      Activity a = s.activity.get();
      if (a != null && !a.isDestroyed()) apply(a);
    }
  }

  private static int mode(Activity a) {
    var r = a.getResources();
    int id = r.getIdentifier("config_navBarInteractionMode", "integer", "android");
    return id == 0 ? -1 : r.getInteger(id);
  }

  private boolean shouldHide(Activity a) {
    int mode = mode(a);
    Display d = a.getDisplay();
    return NavigationPolicy.hide(
        enabled,
        mode,
        Settings.Global.getInt(a.getContentResolver(), "navigation_bar_gesture_hint", -1),
        !a.isInMultiWindowMode()
            && !a.isInPictureInPictureMode()
            && d != null
            && d.getDisplayId() == Display.DEFAULT_DISPLAY);
  }

  private void apply(Activity a) {
    if (failed || a.isDestroyed() || !recentsClass.isInstance(a)) return;
    try {
      WindowInsetsController controller = a.getWindow().getInsetsController();
      if (controller == null) return;
      State state = states.get(controller);
      if (state == null) {
        // PhoneWindow exposes a PendingInsetsController before attachment. Its requested mask is
        // replayed into a new real controller. Keep the pre-override state across that handoff;
        // otherwise our own hidden mask would be mistaken for the original native preference.
        var entries = states.entrySet().iterator();
        while (entries.hasNext()) {
          var entry = entries.next();
          if (entry.getValue().activity.get() == a) {
            state = entry.getValue();
            entries.remove();
            break;
          }
        }
        if (state != null) states.put(controller, state);
      }
      boolean hide = shouldHide(a);
      if (!hide) {
        if (state != null && state.active) restore(controller, state);
        if (enabled) diagnostic = (mode(a) == 0 || mode(a) == 1) ? "buttons-skipped" : "system";
        return;
      }
      if (state == null) {
        boolean nativeVisible =
            ((int) requestedTypes.invoke(controller) & WindowInsets.Type.navigationBars()) != 0;
        state = new State(a, nativeVisible, controller.getSystemBarsBehavior());
        states.put(controller, state);
      }
      if (!state.active) {
        state.nativeBehavior = controller.getSystemBarsBehavior();
        state.active = true;
        HookEntry.log("RECENTS_BAR hide; nativeVisible=" + state.nativeVisible);
      }
      ownRequest.set(true);
      try {
        controller.setSystemBarsBehavior(
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsets.Type.navigationBars());
      } finally {
        ownRequest.remove();
      }
      diagnostic = "hidden";
      if (a.hasWindowFocus()) gestureOwner = new WeakReference<>(a);
    } catch (Throwable error) {
      failed = true;
      enabled = false;
      gestureOwner = new WeakReference<>(null);
      diagnostic = "error:" + error.getClass().getSimpleName();
      for (var hook : hooks) {
        try {
          hook.unhook();
        } catch (Throwable ignored) {
        }
      }
      hooks.clear();
      for (var entry : new ArrayList<>(states.entrySet())) {
        try {
          restore(entry.getKey(), entry.getValue());
        } catch (Throwable ignored) {
        }
      }
      HookEntry.log("RECENTS_BAR_DISABLED " + error);
    }
  }

  private void restore(WindowInsetsController controller, State state) {
    if (!state.active) return;
    state.active = false;
    Activity a = state.activity.get();
    if (gestureOwner.get() == a) gestureOwner = new WeakReference<>(null);
    ownRequest.set(true);
    try {
      controller.setSystemBarsBehavior(state.nativeBehavior);
      // A global hint-off setting may have been selected while the override was active.
      boolean visible =
          a != null
              && NavigationPolicy.restoreVisible(
                  state.nativeVisible,
                  mode(a),
                  Settings.Global.getInt(
                      a.getContentResolver(), "navigation_bar_gesture_hint", -1));
      if (visible) controller.show(WindowInsets.Type.navigationBars());
      else controller.hide(WindowInsets.Type.navigationBars());
    } finally {
      ownRequest.remove();
    }
    HookEntry.log("RECENTS_BAR restored native request");
  }

  private static final class State {
    final WeakReference<Activity> activity;
    boolean nativeVisible;
    int nativeBehavior;
    boolean active;

    State(Activity a, boolean visible, int behavior) {
      activity = new WeakReference<>(a);
      nativeVisible = visible;
      nativeBehavior = behavior;
    }
  }
}
