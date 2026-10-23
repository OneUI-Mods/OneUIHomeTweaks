package dev.ryuya.oneuihometweaks;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Folder-return policy and native close animation repair, confined to One UI Home. */
final class FolderHooks {
  private final List<ModernHook.Handle> hooks = new ArrayList<>();
  private final ThreadLocal<Boolean> homeWhileFocused = ThreadLocal.withInitial(() -> false);
  private WeakReference<Object> returnedManager = new WeakReference<>(null);
  private volatile boolean enabled;
  private volatile boolean returnToFolder = true;
  private boolean failed;
  private boolean ownRequest;
  private Method currentState, goTo, animate, isOpen;
  private Object homeNormal, appsNormal;
  private String diagnostic = "unavailable";

  void install(ClassLoader loader) throws Exception {
    FolderContracts contract = FolderContracts.forSdk(Build.VERSION.SDK_INT);
    Class<?> manager = Class.forName(contract.manager(), false, loader);
    Class<?> animator = Class.forName(contract.animator(), false, loader);
    Class<?> state = Class.forName("com.honeyspace.sdk.HoneyState", false, loader);
    Class<?> key = Class.forName("com.honeyspace.sdk.transition.CloseTarget$Key", false, loader);
    Class<?> launcher = Class.forName("com.sec.android.app.launcher.Launcher", false, loader);
    homeNormal = singleton(loader, "HomeScreen$Normal");
    appsNormal = singleton(loader, "AppScreen$Normal");
    currentState = manager.getMethod("getCurrentHoneyScreenState");
    goTo = manager.getDeclaredMethod("gotoScreen", state);
    isOpen = manager.getMethod("isOpenFolderMode");
    animate =
        manager.getDeclaredMethod(
            "gotoScreenWithAnimation",
            state,
            float.class,
            boolean.class,
            boolean.class,
            boolean.class,
            boolean.class,
            boolean.class,
            long.class,
            float.class);
    Method target = manager.getDeclaredMethod("findCloseAnimationTarget", key);
    Method prepare = animator.getDeclaredMethod(contract.prepare(), long.class, boolean.class);
    Method reset = animator.getDeclaredMethod(contract.resetGeometry());
    Field viewModel = animator.getDeclaredField(contract.viewModelField());
    Method managerGetter = viewModel.getType().getMethod(contract.managerGetter());
    Method newIntent = launcher.getDeclaredMethod("onNewIntent", Intent.class);
    if (!Activity.class.isAssignableFrom(launcher)
        || isOpen.getReturnType() != boolean.class
        || prepare.getReturnType() != void.class
        || reset.getReturnType() != void.class
        || !Class.forName("com.honeyspace.sdk.HoneyScreenManager", false, loader)
            .isAssignableFrom(managerGetter.getReturnType()))
      throw new IllegalStateException("Folder contract changed");
    viewModel.setAccessible(true);
    managerGetter.setAccessible(true);
    reset.setAccessible(true);
    try {
      hooks.add(
          ModernHook.hookMethod(
              target,
              new ModernHook.Callback() {
                @Override
                protected void beforeHookedMethod(ModernHook.Param p) {
                  if (!active() || ownRequest) return;
                  try {
                    if (!Boolean.TRUE.equals(isOpen.invoke(p.thisObject))) return;
                    if (returnToFolder) {
                      returnedManager = new WeakReference<>(p.thisObject);
                    } else {
                      // Use the same native state change as Home, before Samsung resolves its
                      // closing target. The app then shrinks towards the closed folder icon.
                      Object previous = currentState.invoke(p.thisObject);
                      Object normal =
                          previous.getClass().getName().contains(".AppScreen$")
                              ? appsNormal
                              : homeNormal;
                      ownRequest = true;
                      try {
                        goTo.invoke(p.thisObject, normal);
                      } finally {
                        ownRequest = false;
                      }
                      returnedManager.clear();
                      diagnostic = "home after folder launch";
                    }
                  } catch (Throwable error) {
                    disable(error);
                  }
                }
              }));
      hooks.add(
          ModernHook.hookMethod(
              prepare,
              new ModernHook.Callback() {
                @Override
                protected void beforeHookedMethod(ModernHook.Param p) {
                  if (!active() || !returnToFolder || Boolean.TRUE.equals(p.args[1])) return;
                  try {
                    Object owner = managerGetter.invoke(viewModel.get(p.thisObject));
                    if (owner != returnedManager.get()) return;
                    // Native caching compares only the icon's x/y. App gestures can change
                    // the root scale without moving the icon; rebuild native geometry.
                    reset.invoke(p.thisObject);
                    returnedManager.clear();
                    diagnostic = "native close geometry refreshed";
                  } catch (Throwable error) {
                    disable(error);
                  }
                }
              }));
      hooks.add(
          ModernHook.hookMethod(
              newIntent,
              new ModernHook.Callback() {
                @Override
                protected void beforeHookedMethod(ModernHook.Param p) {
                  Intent intent = (Intent) p.args[0];
                  p.setObjectExtra("folderHomeFocused", homeWhileFocused.get());
                  homeWhileFocused.set(
                      active()
                          && ((Activity) p.thisObject).hasWindowFocus()
                          && intent != null
                          && Intent.ACTION_MAIN.equals(intent.getAction())
                          && intent.hasCategory(Intent.CATEGORY_HOME));
                }

                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  homeWhileFocused.set(Boolean.TRUE.equals(p.getObjectExtra("folderHomeFocused")));
                }
              }));
      hooks.add(
          ModernHook.hookMethod(
              goTo,
              new ModernHook.Callback() {
                @Override
                protected void beforeHookedMethod(ModernHook.Param p) {
                  if (!active()
                      || !returnToFolder
                      || ownRequest
                      || !homeWhileFocused.get()
                      || p.thisObject != returnedManager.get()
                      || p.args[0] != homeNormal) return;
                  try {
                    if (!Boolean.TRUE.equals(isOpen.invoke(p.thisObject))) return;
                    ownRequest = true;
                    try {
                      // A second Home gesture must close the folder with Samsung's own
                      // animation, rather than taking the immediate state-change path.
                      animate.invoke(
                          p.thisObject, homeNormal, 0f, true, true, false, false, true, 350L, -1f);
                    } finally {
                      ownRequest = false;
                    }
                    p.setResult(null);
                    diagnostic = "native animated home gesture";
                  } catch (Throwable error) {
                    disable(error);
                  }
                }
              }));
      diagnostic = "ready";
      HookEntry.log("FOLDER_HOOKS_READY sdk=" + Build.VERSION.SDK_INT);
    } catch (Throwable error) {
      for (ModernHook.Handle hook : hooks) hook.unhook();
      hooks.clear();
      throw error instanceof Exception exception ? exception : new IllegalStateException(error);
    }
  }

  void configure(boolean active, boolean keepFolder) {
    enabled = active;
    returnToFolder = keepFolder;
    if (!active || !keepFolder) returnedManager.clear();
  }

  String diagnostic() {
    return failed ? "stopped after error" : enabled ? diagnostic : "off";
  }

  private boolean active() {
    return enabled && !failed && Looper.myLooper() == Looper.getMainLooper();
  }

  private void disable(Throwable error) {
    failed = true;
    returnedManager.clear();
    HookEntry.log("FOLDER_DISABLED " + error);
  }

  private static Object singleton(ClassLoader loader, String name) throws Exception {
    return Class.forName("com.honeyspace.sdk." + name, false, loader)
        .getField("INSTANCE")
        .get(null);
  }
}
