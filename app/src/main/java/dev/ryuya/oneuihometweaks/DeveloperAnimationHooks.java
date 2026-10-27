package dev.ryuya.oneuihometweaks;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/** Reviewed Settings 17 contract; Android 16 hosts retain their existing independent behavior. */
final class DeveloperAnimationHooks {
  private static final String[] CONTROLLERS = {
    "WindowAnimationScalePreferenceController",
    "TransitionAnimationScalePreferenceController",
    "AnimatorDurationScalePreferenceController"
  };
  private static final String[] KEYS = {
    "window_animation_scale", "transition_animation_scale", "animator_duration_scale_pref_key"
  };
  private static final String[] UPDATE = {
    "updateAnimationScaleValue$2", "updateAnimationScaleValue$1", "updateAnimationScaleValue"
  };
  private final Map<Object, Binding> bindings = new WeakHashMap<>();
  private final List<ModernHook.Handle> hooks = new ArrayList<>();
  private final Handler main = new Handler(Looper.getMainLooper());
  private Context context;
  private boolean enabled, failed;
  private AnimationScaleDialog dialog;

  void install(Context context, ClassLoader loader) {
    this.context = context;
    try {
      if (Build.VERSION.SDK_INT != 37
          || !Android17Hooks.BUILD.equals(Build.DISPLAY)
          || !"17"
              .equals(
                  context
                      .getPackageManager()
                      .getPackageInfo(context.getPackageName(), 0)
                      .versionName))
        throw new UnsupportedOperationException("Unreviewed Settings contract");
      Class<?> preference = Class.forName("androidx.preference.Preference", false, loader);
      Class<?> dialogPreference =
          Class.forName("androidx.preference.DialogPreference", false, loader);
      for (int i = 0; i < CONTROLLERS.length; i++) {
        final int index = i;
        Class<?> owner =
            Class.forName("com.android.settings.development." + CONTROLLERS[i], false, loader);
        Method update = owner.getDeclaredMethod(UPDATE[i]);
        Method changed = owner.getDeclaredMethod("onPreferenceChange", preference, Object.class);
        Field selected = field(owner, "mPreference");
        Field manager = field(owner, "mWindowManager");
        Method read = manager.getType().getMethod("getAnimationScale", int.class);
        hooks.add(
            ModernHook.hookMethod(
                update,
                new ModernHook.Callback() {
                  protected void afterHookedMethod(ModernHook.Param call) {
                    if (failed || call.hasThrowable()) return;
                    try {
                      Object item = selected.get(call.thisObject);
                      if (item == null
                          || !KEYS[index].equals(preference.getMethod("getKey").invoke(item)))
                        return;
                      Binding binding =
                          new Binding(item, call.thisObject, index, manager, read, changed, update);
                      bindings.put(item, binding);
                      if (enabled) binding.summary();
                    } catch (Exception error) {
                      fail(error);
                    }
                  }
                }));
        ModernHook.deoptimize(update);
      }
      hooks.add(
          ModernHook.findAndHookMethod(
              dialogPreference,
              "onClick",
              new ModernHook.Callback() {
                protected void beforeHookedMethod(ModernHook.Param call) {
                  Binding binding = bindings.get(call.thisObject);
                  if (!enabled || failed || binding == null) return;
                  try {
                    if (dialog != null && dialog.showing()) {
                      call.setResult(null);
                      return;
                    }
                    Context host =
                        (Context) preference.getMethod("getContext").invoke(call.thisObject);
                    CharSequence title =
                        (CharSequence) preference.getMethod("getTitle").invoke(call.thisObject);
                    dialog =
                        new AnimationScaleDialog(
                            host, title, binding.value(), value -> binding.save(value));
                    dialog.show();
                    call.setResult(null);
                  } catch (Exception error) {
                    fail(error);
                  }
                }
              }));
      readConfig();
      ((android.app.Application) context)
          .registerActivityLifecycleCallbacks(
              new android.app.Application.ActivityLifecycleCallbacks() {
                public void onActivityCreated(android.app.Activity activity, Bundle state) {}

                public void onActivityStarted(android.app.Activity activity) {}

                public void onActivityResumed(android.app.Activity activity) {}

                public void onActivityPaused(android.app.Activity activity) {}

                public void onActivityStopped(android.app.Activity activity) {
                  closeDialog();
                }

                public void onActivitySaveInstanceState(
                    android.app.Activity activity, Bundle state) {}

                public void onActivityDestroyed(android.app.Activity activity) {}
              });
      HandlerThread worker = new HandlerThread("OneUiSettingsConfig");
      worker.start();
      context
          .getContentResolver()
          .registerContentObserver(
              ConfigProvider.URI,
              false,
              new ContentObserver(new Handler(worker.getLooper())) {
                public void onChange(boolean self) {
                  main.post(DeveloperAnimationHooks.this::readConfig);
                }
              });
      HookEntry.log("ANIMATION_EDITOR_READY sdk=37");
    } catch (Exception error) {
      fail(error);
    }
  }

  private void closeDialog() {
    if (dialog == null) return;
    dialog.dismiss();
    dialog = null;
  }

  private void readConfig() {
    if (failed) return;
    try (Cursor c =
        context.getContentResolver().query(ConfigProvider.URI, null, null, null, null)) {
      if (c == null || !c.moveToFirst()) throw new IllegalStateException("Missing settings");
      enabled =
          c.getInt(c.getColumnIndexOrThrow("enabled")) != 0
              && c.getInt(c.getColumnIndexOrThrow("settings_enabled")) != 0;
      if (!enabled) closeDialog();
      for (Binding binding : new ArrayList<>(bindings.values())) binding.refresh();
      HostStatus.report(context, enabled ? "ACTIVE" : "OFF", "Developer animation scale editor");
    } catch (Exception error) {
      fail(error);
    }
  }

  private void fail(Exception error) {
    failed = true;
    enabled = false;
    closeDialog();
    for (ModernHook.Handle hook : hooks) hook.unhook();
    hooks.clear();
    HostStatus.report(
        context,
        error instanceof UnsupportedOperationException ? "UNSUPPORTED" : "ERROR",
        "Animation editor: " + error.getClass().getSimpleName());
    HookEntry.log("ANIMATION_EDITOR_DISABLED " + error);
  }

  private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
    for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
      try {
        Field value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value;
      } catch (NoSuchFieldException ignored) {
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static final class Binding {
    final WeakReference<Object> preference, controller;
    final int index;
    final Field manager;
    final Method read, changed, update;

    Binding(
        Object preference,
        Object controller,
        int index,
        Field manager,
        Method read,
        Method changed,
        Method update) {
      this.preference = new WeakReference<>(preference);
      this.controller = new WeakReference<>(controller);
      this.index = index;
      this.manager = manager;
      this.read = read;
      this.changed = changed;
      this.update = update;
    }

    float value() throws Exception {
      Object owner = controller.get();
      if (owner == null) throw new IllegalStateException("Settings closed");
      return (float) read.invoke(manager.get(owner), index);
    }

    void summary() throws Exception {
      Object item = preference.get();
      if (item == null) return;
      Context context = (Context) item.getClass().getMethod("getContext").invoke(item);
      String summary =
          AnimationScaleValue.format(
                  AnimationScaleValue.ticks(value()),
                  context.getResources().getConfiguration().getLocales().get(0))
              + "×";
      item.getClass().getMethod("setSummary", CharSequence.class).invoke(item, summary);
    }

    void refresh() throws Exception {
      Object owner = controller.get();
      if (owner != null) update.invoke(owner);
    }

    void save(String value) throws Exception {
      Object owner = controller.get(), item = preference.get();
      if (owner == null || item == null) throw new IllegalStateException("Settings closed");
      if (!Boolean.TRUE.equals(item.getClass().getMethod("isEnabled").invoke(item)))
        throw new IllegalStateException("Preference disabled");
      if (!Boolean.TRUE.equals(changed.invoke(owner, item, value)))
        throw new IllegalStateException("Native change rejected");
      if (Math.abs(value() - Float.parseFloat(value)) > 0.0001f)
        throw new IllegalStateException("Native value not applied");
    }
  }
}
