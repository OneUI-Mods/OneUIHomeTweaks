package net.ryuya_dev.oneuilivecolors;

import android.app.Dialog;
import android.content.*;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;
import de.robv.android.xposed.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.Set;
import java.util.function.Supplier;

/** Local configuration for native Samsung volume dialogs only, never global resources. */
final class VolumeHooks {
  private volatile int mode;
  private boolean failed;
  private Context context;
  private Handler main, worker;
  private WeakReference<Object> panel = new WeakReference<>(null);
  private Method recreate;
  private int lightPreset, darkPreset;
  private String lastBlur = "";
  private final VolumeStarHooks volumeStar = new VolumeStarHooks();

  void install(Context app, ClassLoader loader) throws Exception {
    context = app;
    main = new Handler(Looper.getMainLooper());
    Class<?> dependency =
        Class.forName("com.android.systemui.volume.VolumeDependency", false, loader);
    Class<?> panelClass =
        Class.forName("com.android.systemui.volume.VolumePanelImpl", false, loader);
    Class<?> window =
        Class.forName("com.android.systemui.volume.view.standard.VolumePanelWindow", false, loader);
    Class<?> expanded =
        Class.forName(
            "com.android.systemui.volume.view.expand.VolumePanelExpandWindow", false, loader);
    Class<?> blur = Class.forName("com.android.systemui.volume.util.BlurEffect", false, loader);
    Class<?> colors = Class.forName("com.android.systemui.volume.util.ColorUtils", false, loader);
    Class<?> sem = Class.forName("android.view.SemBlurInfo");
    lightPreset = sem.getField("BLUR_UI_MEDIUM_ULTRA_THIN_LIGHT").getInt(null);
    darkPreset = sem.getField("BLUR_UI_MEDIUM_ULTRA_THIN_DARK").getInt(null);
    volumeStar.install(app, loader, lightPreset, darkPreset);
    if (lightPreset == darkPreset)
      throw new IllegalStateException("Ambiguous Samsung blur presets");
    if (!Dialog.class.isAssignableFrom(window) || !Dialog.class.isAssignableFrom(expanded))
      throw new IllegalStateException("Volume window signature changed");
    recreate = panelClass.getDeclaredMethod("recreateVolumePanelForNewConfig");
    Method deopt = XposedBridge.class.getMethod("deoptimizeMethod", Member.class);
    // Confirmed constructor callers must not retain inlined dependency lookups.
    for (Class<?> target : new Class<?>[] {window, expanded})
      for (Constructor<?> constructor : target.getDeclaredConstructors())
        deopt.invoke(null, constructor);
    DexEvidence.deoptimizeVolumeCalls(app.getApplicationInfo().sourceDir, loader);
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
                if ((n.equals("com.android.systemui.volume.view.standard.VolumePanelWindow")
                        || n.equals(
                            "com.android.systemui.volume.view.expand.VolumePanelExpandWindow"))
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
    XposedBridge.hookMethod(
        blur.getDeclaredMethod("setRealTimeBlur", View.class, int.class, float.class, int.class),
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (failed || mode == 0 || !(p.args[0] instanceof View view) || !volumeView(view))
              return;
            try {
              p.args[3] = selectedPreset();
            } catch (Throwable t) {
              disable(t);
            }
          }
        });
    XposedBridge.hookMethod(
        blur.getDeclaredMethod("setCapturedBlur", ImageView.class, int.class, Supplier.class),
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (failed || mode == 0 || !(p.args[0] instanceof View view) || !volumeView(view))
              return;
            try {
              p.args[1] = selectedPreset();
            } catch (Throwable t) {
              disable(t);
            }
          }
        });
    Set<String> foreground =
        Set.of(
            "volume_icon_color",
            "volume_icon_caption_color",
            "volume_icon_muted_color",
            "volume_icon_setting_color",
            "volume_detail_icon_color",
            "volume_panel_bluetooth_device_icon_color",
            "volume_panel_title_color",
            "volume_panel_status_message_color",
            "volume_panel_fg_color");
    XposedBridge.hookMethod(
        colors.getDeclaredMethod("getSingleColorStateList", int.class, Context.class),
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (failed
                || mode == 0
                || p.hasThrowable()
                || !(p.getResult() instanceof ColorStateList original)) return;
            try {
              Context ctx = (Context) p.args[1];
              if (ctx.getDisplay() == null
                  || ctx.getDisplay().getDisplayId() != 0
                  || !foreground.contains(ctx.getResources().getResourceEntryName((int) p.args[0])))
                return;
              int rgb = mode == 1 ? 0x00252525 : 0x00f5f5f5;
              p.setResult(ColorStateList.valueOf((original.getDefaultColor() & 0xff000000) | rgb));
            } catch (Throwable t) {
              disable(t);
            }
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

  private boolean volumeView(View view) {
    if (view.getDisplay() == null || view.getDisplay().getDisplayId() != 0) return false;
    for (View current = view; current != null; ) {
      String name = current.getClass().getName();
      if (name.equals("com.android.systemui.volume.view.standard.VolumePanelView")
          || name.equals("com.android.systemui.volume.view.expand.VolumePanelExpandView"))
        return true;
      ViewParent parent = current.getParent();
      current = parent instanceof View ? (View) parent : null;
    }
    return false;
  }

  private int selectedPreset() {
    int preset = mode == 1 ? lightPreset : darkPreset;
    String report = "VOLUME_BLUR mode=" + mode + " preset=" + preset;
    if (!report.equals(lastBlur)) {
      lastBlur = report;
      HookEntry.log(report);
    }
    return preset;
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
                  context,
                  enabled ? (volumeStar.hasError() ? "ERROR" : "ACTIVE") : "OFF",
                  "Volume hook ready; mode=" + mode);
              return;
            }
            mode = selected;
            volumeStar.setMode(selected);
            try {
              Object current = panel.get();
              if (current != null) recreate.invoke(current);
              HostStatus.report(
                  context,
                  enabled ? (volumeStar.hasError() ? "ERROR" : "ACTIVE") : "OFF",
                  "Volume mode=" + mode);
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
    volumeStar.setMode(0);
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
