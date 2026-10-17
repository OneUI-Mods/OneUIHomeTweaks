package net.ryuya_dev.oneuilivecolors;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Looper;
import android.view.Display;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Activity-local orientation override; app tasks and global rotation settings are untouched. */
final class RecentsHooks {
  private final Map<Activity, Integer> nativeRequests = new WeakHashMap<>();
  private final List<ModernHook.Handle> handles = new ArrayList<>();
  private final ThreadLocal<Boolean> restoring = ThreadLocal.withInitial(() -> false);
  private final RecentsMotionTrace motion = new RecentsMotionTrace();
  private final RecentsViewportHooks coordinates = new RecentsViewportHooks(this::disable);
  private Class<?> recentsClass;
  private volatile boolean enabled;
  private volatile boolean failed;
  private String diagnostic = "unavailable";

  void install(String apk, ClassLoader loader) throws Exception {
    recentsClass = ModernHook.findClass("com.android.quickstep.RecentsActivity", loader);
    if (!Activity.class.isAssignableFrom(recentsClass)
        || recentsClass.getMethod("setRequestedOrientation", int.class).getDeclaringClass()
            != Activity.class
        || recentsClass.getDeclaredMethod("onCreate", Bundle.class).getReturnType() != void.class
        || recentsClass.getDeclaredMethod("onResume").getReturnType() != void.class)
      throw new IllegalStateException("Recents Activity orientation contract changed");
    DexEvidence.verifyRecentsOrientation(apk, loader);
    try {
      handles.add(
          ModernHook.findAndHookMethod(
              Activity.class,
              "setRequestedOrientation",
              int.class,
              new ModernHook.Callback() {
                @Override
                protected void beforeHookedMethod(ModernHook.Param p) {
                  if (failed
                      || restoring.get()
                      || Looper.myLooper() != Looper.getMainLooper()
                      || !recentsClass.isInstance(p.thisObject)) return;
                  Activity a = (Activity) p.thisObject;
                  nativeRequests.put(a, (Integer) p.args[0]);
                  if (enabled && eligible(a)) {
                    HookEntry.log("RECENTS_NATIVE_REQUEST " + p.args[0] + " -> portrait");
                    p.args[0] = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                  }
                }
              }));
      ModernHook.Callback enter =
          new ModernHook.Callback() {
            @Override
            protected void beforeHookedMethod(ModernHook.Param p) {
              apply((Activity) p.thisObject);
            }
          };
      handles.add(ModernHook.findAndHookMethod(recentsClass, "onCreate", Bundle.class, enter));
      handles.add(ModernHook.findAndHookMethod(recentsClass, "onResume", enter));
      handles.add(
          ModernHook.findAndHookMethod(
              Activity.class,
              "onMultiWindowModeChanged",
              boolean.class,
              Configuration.class,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (!p.hasThrowable() && recentsClass.isInstance(p.thisObject))
                    apply((Activity) p.thisObject);
                }
              }));
      coordinates.install(apk, loader);
      if (BuildConfig.RECENTS_MOTION_TRACE) motion.install(apk, loader);
      diagnostic = "off";
      HookEntry.log("RECENTS_HOOK_READY Activity-local portrait policy");
    } catch (Throwable e) {
      coordinates.uninstall();
      motion.uninstall();
      for (var handle : handles) handle.unhook();
      handles.clear();
      if (e instanceof Exception exception) throw exception;
      throw new IllegalStateException(e);
    }
  }

  void setEnabled(boolean value) {
    if (Looper.myLooper() != Looper.getMainLooper())
      throw new IllegalStateException("Recents policy requires main thread");
    enabled = value && !failed && recentsClass != null && !handles.isEmpty();
    coordinates.setEnabled(enabled);
    motion.setEnabled(enabled);
    for (Activity a : new ArrayList<>(nativeRequests.keySet())) apply(a);
    if (!failed && !handles.isEmpty()) diagnostic = enabled ? "portrait" : "off";
  }

  String diagnostic() {
    return diagnostic;
  }

  private boolean eligible(Activity a) {
    Display display = a.getDisplay();
    return display != null
        && display.getDisplayId() == Display.DEFAULT_DISPLAY
        && !a.isInMultiWindowMode()
        && !a.isInPictureInPictureMode();
  }

  private void apply(Activity a) {
    if (failed
        || recentsClass == null
        || !recentsClass.isInstance(a)
        || a.isDestroyed()
        || a.isFinishing()) return;
    try {
      boolean force = enabled && eligible(a);
      if (force) nativeRequests.putIfAbsent(a, a.getRequestedOrientation());
      Integer original = nativeRequests.get(a);
      if (original == null) return;
      int requested = force ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : original;
      if (a.getRequestedOrientation() == requested) return;
      restoring.set(true);
      try {
        a.setRequestedOrientation(requested);
      } finally {
        restoring.remove();
      }
      HookEntry.log("RECENTS_ORIENTATION " + (force ? "portrait" : "native=" + original));
    } catch (Throwable e) {
      disable(e);
    }
  }

  private void disable(Throwable e) {
    if (failed) return;
    failed = true;
    enabled = false;
    coordinates.uninstall();
    motion.uninstall();
    diagnostic = "error:" + e.getClass().getSimpleName();
    for (var handle : handles) {
      try {
        handle.unhook();
      } catch (Throwable ignored) {
      }
    }
    handles.clear();
    // Removing hooks restores future native requests. Restore any request already overridden.
    for (var entry : new ArrayList<>(nativeRequests.entrySet())) {
      try {
        if (!entry.getKey().isDestroyed()) entry.getKey().setRequestedOrientation(entry.getValue());
      } catch (Throwable ignored) {
      }
    }
    HookEntry.log("RECENTS_DISABLED " + e);
  }
}
