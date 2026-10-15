package net.ryuya_dev.oneuilivecolors;

import android.app.Dialog;
import android.content.*;
import android.content.res.Configuration;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.view.ContextThemeWrapper;
import de.robv.android.xposed.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;

/** Local configuration for native Samsung volume dialogs only, never global resources. */
final class VolumeHooks {
  private volatile int mode;
  private boolean failed;
  private Context context;
  private Handler main, worker;
  private WeakReference<Object> panel = new WeakReference<>(null);
  private Method recreate;

  void install(Context app, ClassLoader loader) throws Exception {
    context = app;
    main = new Handler(Looper.getMainLooper());
    Class<?> dependency =
        Class.forName("com.android.systemui.volume.VolumeDependency", false, loader);
    Class<?> panelClass =
        Class.forName("com.android.systemui.volume.VolumePanelImpl", false, loader);
    Class<?> window =
        Class.forName("com.android.systemui.volume.view.standard.VolumePanelWindow", false, loader);
    if (!Dialog.class.isAssignableFrom(window))
      throw new IllegalStateException("Volume window signature changed");
    recreate = panelClass.getDeclaredMethod("recreateVolumePanelForNewConfig");
    Method deopt = XposedBridge.class.getMethod("deoptimizeMethod", Member.class);
    // Confirmed constructor callers must not retain inlined dependency lookups.
    for (Constructor<?> constructor : window.getDeclaredConstructors())
      deopt.invoke(null, constructor);
    XposedBridge.hookMethod(
        dependency.getDeclaredMethod("get", Class.class),
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (failed
                || mode == 0
                || p.hasThrowable()
                || p.args[0] != Context.class
                || !(p.getResult() instanceof Context original)) return;
            try {
              boolean target = false;
              for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                String n = frame.getClassName();
                if (n.equals("com.android.systemui.volume.view.standard.VolumePanelWindow")
                    && frame.getMethodName().equals("<init>")) {
                  target = true;
                  break;
                }
              }
              if (!target) return;
              Configuration cfg = new Configuration(original.getResources().getConfiguration());
              cfg.uiMode =
                  (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                      | (mode == 2
                          ? Configuration.UI_MODE_NIGHT_YES
                          : Configuration.UI_MODE_NIGHT_NO);
              Context configured = original.createConfigurationContext(cfg);
              ContextThemeWrapper themed = new ContextThemeWrapper(configured, 0);
              themed.getTheme().setTo(original.getTheme());
              themed.getTheme().rebase();
              p.setResult(themed);
              HookEntry.log("VOLUME_CONTEXT mode=" + mode);
            } catch (Throwable t) {
              disable(t);
            }
          }
        });
    XposedBridge.hookAllConstructors(
        panelClass,
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (!p.hasThrowable()) panel = new WeakReference<>(p.thisObject);
          }
        });
    HandlerThread thread = new HandlerThread("OneUiVolumeConfig");
    thread.start();
    worker = new Handler(thread.getLooper());
    app.getContentResolver()
        .registerContentObserver(
            ConfigProvider.URI,
            false,
            new ContentObserver(worker) {
              @Override
              public void onChange(boolean selfChange, Uri uri) {
                refresh();
              }
            });
    worker.post(this::refresh);
    HookEntry.log("VOLUME_HOOK_READY native context + native recreate");
  }

  private void refresh() {
    if (failed) return;
    try (Cursor c =
        context.getContentResolver().query(ConfigProvider.URI, null, null, null, null)) {
      if (c == null || !c.moveToFirst()) throw new IllegalStateException("No configuration");
      boolean enabled =
          c.getInt(c.getColumnIndexOrThrow("enabled")) != 0
              && c.getInt(c.getColumnIndexOrThrow("systemui_enabled")) != 0;
      int next = enabled ? c.getInt(c.getColumnIndexOrThrow("volume")) : 0;
      if (next < 0 || next > 2) next = 0;
      int selected = next;
      main.post(
          () -> {
            if (mode == selected) {
              HostStatus.report(
                  context, enabled ? "ACTIVE" : "OFF", "Volume hook ready; mode=" + mode);
              return;
            }
            mode = selected;
            try {
              Object current = panel.get();
              if (current != null) recreate.invoke(current);
              HostStatus.report(context, enabled ? "ACTIVE" : "OFF", "Volume mode=" + mode);
            } catch (Throwable t) {
              disable(t);
            }
          });
    } catch (Throwable t) {
      main.post(
          () -> {
            mode = 0;
            disable(t);
          });
    }
  }

  private void disable(Throwable t) {
    boolean restore = !failed;
    failed = true;
    mode = 0;
    HostStatus.report(context, "ERROR", "Volume: " + t.getClass().getSimpleName());
    HookEntry.log("VOLUME_DISABLED " + t);
    if (restore && main != null)
      main.post(
          () -> {
            try {
              Object current = panel.get();
              if (current != null) recreate.invoke(current);
            } catch (Throwable restoreError) {
              HookEntry.log(
                  "VOLUME_NATIVE_RESTORE_ERROR " + restoreError.getClass().getSimpleName());
            }
          });
  }
}
