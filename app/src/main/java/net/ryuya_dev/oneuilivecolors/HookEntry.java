package net.ryuya_dev.oneuilivecolors;

import android.app.*;
import android.content.*;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Scoped host entry point. No system_server or Zygote hooks. */
public final class HookEntry extends XposedModule {
  public static final String LAUNCHER = "com.sec.android.app.launcher";
  private static final String FINGERPRINT =
      "samsung/m3qjpnw/m3q:16/BP4A.251205.006/S948QOPS1AZHL_SJP1AZHL:user/release-keys";
  private static final String LAUNCHER_SHA =
      "e8f0ac6818d066549720ebbeea6ac6c13e04e4ee93a26f5ea35589b6dc0bb291";
  private static final String FONT = "need_dark_font", STATUS = "need_dark_statusbar";
  private Class<?> dataSource;
  private ClassLoader launcherLoader;
  private final Map<String, Boolean> readerClasses = new ConcurrentHashMap<>();
  private volatile Snapshot snapshot = new Snapshot(null, null);
  private final List<Observed> observers = new ArrayList<>();
  private final List<ModernHook.Handle> hooks = new ArrayList<>();
  private final Map<String, String> lastRead = new HashMap<>();
  private Handler main, worker;
  private Context context;
  private WallpaperManager wm;
  private final PageBridge pages = new PageBridge();
  private final DrawerBridge drawer = new DrawerBridge();
  private final RecentsHooks recents = new RecentsHooks();
  private final RecentsBar recentsBar = new RecentsBar();
  private boolean initialized;
  private boolean hostAttached;
  private boolean earlyPages;
  private CrashGuard preparedGuard;
  private volatile boolean failed;
  private int failures;
  private WallpaperManager.OnColorsChangedListener listener;
  private String lastReport = "";
  private String processName;
  private final Runnable refresh = this::refresh;

  @Override
  public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
    ModernHook.attach(this);
    processName = param.getProcessName();
    log(
        "MODULE_LOADED api="
            + ModernHook.apiVersion()
            + " framework="
            + ModernHook.frameworkDescription()
            + " process="
            + processName);
  }

  @Override
  public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
    if (!param.isFirstPackage()) return;
    handleLoadPackage(
        new TargetPackage(
            param.getPackageName(),
            processName,
            param.getApplicationInfo(),
            param.getClassLoader()));
  }

  private void handleLoadPackage(TargetPackage p) {
    if (!p.packageName.equals(p.processName)
        || !Arrays.asList(ConfigProvider.HOSTS).contains(p.packageName)) return;
    ModernHook.findAndHookMethod(
        Application.class,
        "attach",
        Context.class,
        new ModernHook.Callback() {
          @Override
          protected void beforeHookedMethod(ModernHook.Param param) {
            if (hostAttached
                || !LAUNCHER.equals(p.packageName)
                || Build.VERSION.SDK_INT != 36
                || !"SM-S948Q".equals(Build.MODEL)
                || android.os.Process.myUid() / 100000 != 0) return;
            Context base = (Context) param.args[0];
            if (!LAUNCHER.equals(base.getPackageName())) return;
            try {
              preparedGuard = CrashGuard.enter(base);
              if (preparedGuard.blocked()) return;
              DexEvidence.verifyScrollContract(
                  p.appInfo.sourceDir,
                  "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceFastRecyclerView");
              DexEvidence.workspaceVerified = true;
              pages.install(p.classLoader);
              earlyPages = true;
            } catch (Throwable t) {
              log("EARLY_PAGE_UNSUPPORTED " + t);
            }
          }

          @Override
          protected void afterHookedMethod(ModernHook.Param param) {
            if (param.hasThrowable()) return;
            Application app = (Application) param.thisObject;
            if (hostAttached || !p.packageName.equals(app.getPackageName())) return;
            hostAttached = true;
            context = app;
            try {
              if (Build.VERSION.SDK_INT != 36
                  || !"SM-S948Q".equals(Build.MODEL)
                  || android.os.Process.myUid() / 100000 != 0) {
                HostStatus.report(app, "UNSUPPORTED", "SDK/model/user compatibility gate");
                return;
              }
              CrashGuard guard = preparedGuard != null ? preparedGuard : CrashGuard.enter(app);
              if (guard.blocked()) {
                HostStatus.report(
                    app,
                    "CRASH_GUARD",
                    "Three confirmed crash starts; clear protection before retrying");
                log("CRASH_GUARD blocked " + p.packageName);
                return;
              }
              if (LAUNCHER.equals(p.packageName)) installLauncher(p, app);
              else if ("com.android.systemui".equals(p.packageName))
                new VolumeHooks().install(app, p.classLoader);
              else new PreviewHooks().install(app, p.classLoader);
              new Handler(Looper.getMainLooper()).postDelayed(guard::healthy, 90000);
            } catch (Throwable t) {
              HostStatus.report(app, "ERROR", t.getClass().getSimpleName() + ": " + t.getMessage());
              log("HOST_DISABLED " + t);
            }
          }
        });
  }

  private void installLauncher(TargetPackage p, Application app) {
    if (!LAUNCHER.equals(p.packageName) || !LAUNCHER.equals(p.processName)) return;
    try {
      if (Build.VERSION.SDK_INT != 36
          || !"samsung".equalsIgnoreCase(Build.MANUFACTURER)
          || !"SM-S948Q".equals(Build.MODEL)
          || android.os.Process.myUid() / 100000 != 0
          || p.appInfo == null) {
        log("UNSUPPORTED: manufacturer, model, SDK or owner user. No hooks installed.");
        return;
      }
      launcherLoader = p.classLoader;
      dataSource =
          ModernHook.findClass(
              "com.honeyspace.sdk.source.GlobalSettingsDataSource", launcherLoader);
      Class<?> key =
          ModernHook.findClass("com.honeyspace.sdk.source.entity.SettingsKey", launcherLoader);
      Class<?> updater =
          ModernHook.findClass(
              "com.honeyspace.common.interfaces.WhiteBgColorUpdater", launcherLoader);
      if (!dataSource.isInterface()
          || !updater.isInterface()
          || !"kotlinx.coroutines.flow.StateFlow"
              .equals(dataSource.getMethod("get", key).getReturnType().getName())
          || !"kotlinx.coroutines.flow.StateFlow"
              .equals(updater.getMethod("getDarkFont").getReturnType().getName())
          || !"kotlinx.coroutines.flow.StateFlow"
              .equals(updater.getMethod("getDarkStatusBar").getReturnType().getName())) {
        HostStatus.report(app, "UNSUPPORTED", "Samsung interface mismatch");
        log("UNSUPPORTED: stable Samsung interface mismatch");
        return;
      }
      DexEvidence.verifyAndDeoptimize(p.appInfo.sourceDir, launcherLoader);
      ModernHook.Callback reads =
          new ModernHook.Callback() {
            @Override
            protected void afterHookedMethod(ModernHook.Param param) {
              try {
                if (failed || param.hasThrowable()) return;
                String key = (String) param.args[1];
                if (!FONT.equals(key) && !STATUS.equals(key)) return;
                if (param.args.length == 3 && (int) param.args[2] != 0) return;
                // Revalidate the actual consumer, even after obfuscation changes.
                if (!isSamsungDataSourceRead()) return;
                Snapshot s = snapshot;
                Integer value = FONT.equals(key) ? s.font : STATUS.equals(key) ? s.status : null;
                if (value == null) return;
                String replacement = value.toString();
                if (replacement.equals(param.getResult())) return;
                String evidence = param.getResult() + " -> " + replacement;
                synchronized (lastRead) {
                  if (!evidence.equals(lastRead.put(key, evidence)))
                    log("READ " + key + " " + evidence);
                }
                param.setResult(replacement);
              } catch (Throwable t) {
                fail("read callback", t);
              }
            }
          };
      hooks.add(
          ModernHook.findAndHookMethod(
              Settings.System.class, "getString", ContentResolver.class, String.class, reads));
      hooks.add(
          ModernHook.findAndHookMethod(
              Settings.System.class,
              "getStringForUser",
              ContentResolver.class,
              String.class,
              int.class,
              reads));
      ModernHook.Callback registrations =
          new ModernHook.Callback() {
            @Override
            protected void afterHookedMethod(ModernHook.Param param) {
              try {
                if (failed || param.hasThrowable()) return;
                Uri uri = (Uri) param.args[0];
                String key =
                    Settings.System.getUriFor(FONT).equals(uri)
                        ? FONT
                        : Settings.System.getUriFor(STATUS).equals(uri) ? STATUS : null;
                if (key == null) return;
                if (param.args.length == 4 && (int) param.args[3] != 0 && (int) param.args[3] != -2)
                  return;
                ContentObserver observer = (ContentObserver) param.args[2];
                synchronized (observers) {
                  observers.removeIf(o -> o.ref.get() == null);
                  for (Observed o : observers)
                    if (o.ref.get() == observer && o.key.equals(key)) return;
                  observers.add(new Observed(key, observer));
                }
                log("OBSERVE " + key);
              } catch (Throwable t) {
                fail("observer callback", t);
              }
            }
          };
      hooks.add(
          ModernHook.findAndHookMethod(
              ContentResolver.class,
              "registerContentObserver",
              Uri.class,
              boolean.class,
              ContentObserver.class,
              registrations));
      hooks.add(
          ModernHook.findAndHookMethod(
              ContentResolver.class,
              "registerContentObserver",
              Uri.class,
              boolean.class,
              ContentObserver.class,
              int.class,
              registrations));
      hooks.add(
          ModernHook.findAndHookMethod(
              ContentResolver.class,
              "unregisterContentObserver",
              ContentObserver.class,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param param) {
                  if (param.hasThrowable()) return;
                  synchronized (observers) {
                    observers.removeIf(o -> o.ref.get() == null || o.ref.get() == param.args[0]);
                  }
                }
              }));
      if (!earlyPages)
        try {
          pages.install(p.classLoader);
        } catch (Throwable t) {
          log("PAGE_UNSUPPORTED " + t);
        }
      try {
        drawer.install(p.classLoader);
      } catch (Throwable t) {
        log("DRAWER_UNSUPPORTED " + t);
      }
      initialize(app);
      boolean exact =
          FINGERPRINT.equals(Build.FINGERPRINT) && LAUNCHER_SHA.equals(sha256(p.appInfo.sourceDir));
      log(
          "HOOKS_READY: "
              + (exact
                  ? "verified APK"
                  : "updated APK; structural checks passed; consumer read verification required")
              + "; modern Xposed API="
              + ModernHook.apiVersion());
    } catch (Throwable t) {
      for (var hook : hooks) {
        try {
          hook.unhook();
        } catch (Throwable ignored) {
        }
      }
      fail("install; rolled back", t);
    }
  }

  private boolean isSamsungDataSourceRead() {
    for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
      String name = frame.getClassName();
      if (name.startsWith("android.")
          || name.startsWith("java.")
          || name.startsWith("net.ryuya_dev.")) continue;
      Boolean matched = readerClasses.get(name);
      if (matched == null) {
        try {
          matched = dataSource.isAssignableFrom(Class.forName(name, false, launcherLoader));
        } catch (Throwable ignored) {
          matched = false;
        }
        readerClasses.put(name, matched);
        if (matched) log("VERIFIED_CONSUMER " + name);
      }
      if (matched) return true;
    }
    return false;
  }

  private void initialize(Application app) throws Exception {
    if (initialized) return;
    context = app;
    try {
      recents.install(app.getApplicationInfo().sourceDir, launcherLoader);
    } catch (Throwable t) {
      log("RECENTS_UNSUPPORTED " + t);
    }
    try {
      recentsBar.install(app, launcherLoader);
    } catch (Throwable t) {
      log("RECENTS_BAR_UNSUPPORTED " + t);
    }
    // Samsung's actual WhichChecker and public constants were verified on this device.
    Class<?> wc = Class.forName("com.samsung.android.wallpaper.utils.WhichChecker");
    if (!Integer.valueOf(1).equals(wc.getMethod("getType", int.class).invoke(null, 5))
        || !Integer.valueOf(4).equals(wc.getMethod("getMode", int.class).invoke(null, 5)))
      throw new IllegalStateException("which=5 incompatible");
    main = new Handler(Looper.getMainLooper());
    HandlerThread thread = new HandlerThread("OneUiLiveColors");
    thread.start();
    worker = new Handler(thread.getLooper());
    wm = WallpaperManager.getInstance(app);
    listener =
        (colors, which) -> {
          if (ColorPolicy.isMainHomeEvent(which)) {
            log(
                "COLOR_EVENT which="
                    + which
                    + " hints="
                    + (colors == null ? "null" : colors.getColorHints()));
            requestRefresh();
          }
        };
    wm.addOnColorsChangedListener(listener, main);
    context
        .getContentResolver()
        .registerContentObserver(
            ConfigProvider.URI,
            false,
            new ContentObserver(worker) {
              @Override
              public void onChange(boolean selfChange, Uri uri) {
                requestRefresh();
              }
            });
    context.registerReceiver(
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context c, Intent intent) {
            requestRefresh();
          }
        },
        new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
        Context.RECEIVER_NOT_EXPORTED);
    app.registerActivityLifecycleCallbacks(
        new Application.ActivityLifecycleCallbacks() {
          @Override
          public void onActivityResumed(Activity a) {
            pages.setHomeWindow(a);
            requestRefresh();
          }

          @Override
          public void onActivityCreated(Activity a, Bundle b) {}

          @Override
          public void onActivityStarted(Activity a) {}

          @Override
          public void onActivityPaused(Activity a) {}

          @Override
          public void onActivityStopped(Activity a) {}

          @Override
          public void onActivitySaveInstanceState(Activity a, Bundle b) {}

          @Override
          public void onActivityDestroyed(Activity a) {}
        });
    initialized = true;
    requestRefresh();
    log("INITIALIZED: owner user, main home; event-driven, no polling");
  }

  private void requestRefresh() {
    if (failed || worker == null) return;
    worker.removeCallbacks(refresh);
    worker.postDelayed(refresh, 120);
  }

  private void refresh() {
    if (failed) return;
    try {
      int label = ColorPolicy.OFF, status = ColorPolicy.OFF, drawerMode = ColorPolicy.AUTO;
      boolean master = false, pageMode = false, recentsPortrait = false, hideRecentsBar = false;
      try (Cursor cursor =
          context.getContentResolver().query(ConfigProvider.URI, null, null, null, null)) {
        if (cursor == null || !cursor.moveToFirst())
          throw new IllegalStateException("No configuration");
        master =
            cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) != 0
                && cursor.getInt(cursor.getColumnIndexOrThrow("home_enabled")) != 0;
        pageMode = cursor.getInt(cursor.getColumnIndexOrThrow("pages")) != 0;
        recentsPortrait =
            master && cursor.getInt(cursor.getColumnIndexOrThrow("recents_portrait")) != 0;
        hideRecentsBar =
            master && cursor.getInt(cursor.getColumnIndexOrThrow("recents_hide_bar")) != 0;
        drawerMode = cursor.getInt(cursor.getColumnIndexOrThrow("drawer"));
        label = cursor.getInt(cursor.getColumnIndexOrThrow("label"));
        status = cursor.getInt(cursor.getColumnIndexOrThrow("status"));
      }
      WallpaperInfo info = wm.getWallpaperInfo();
      boolean target = info != null;
      WallpaperColors colors = target ? wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) : null;
      Integer hints = colors == null ? null : colors.getColorHints();
      if (!master) {
        label = ColorPolicy.OFF;
        status = ColorPolicy.OFF;
      }
      boolean apply = master && target;
      int currentDrawerMode = drawerMode;
      boolean currentPages = pageMode;
      boolean currentRecentsPortrait = recentsPortrait;
      boolean currentHideRecentsBar = hideRecentsBar;
      boolean currentMaster = master;
      main.post(
          () -> {
            pages.setEnabled(apply && currentPages);
            drawer.setMode(currentDrawerMode, apply);
            recents.setEnabled(currentRecentsPortrait);
            recentsBar.setEnabled(currentHideRecentsBar);
            HostStatus.report(
                context,
                currentMaster ? "ACTIVE" : "OFF",
                "Launcher bridge; wallpaper="
                    + (target ? "live" : "static")
                    + "; hints="
                    + hints
                    + "; recents="
                    + recents.diagnostic()
                    + "; recentsBar="
                    + recentsBar.diagnostic());
          });
      Snapshot next =
          new Snapshot(
              ColorPolicy.darkValue(label, target, hints),
              ColorPolicy.darkValue(status, target, hints));
      String report =
          "STATE wallpaper="
              + (info == null ? "static/none" : info.getComponent())
              + " hints="
              + hints
              + " labelMode="
              + label
              + " statusMode="
              + status
              + " darkFont="
              + next.font
              + " darkStatus="
              + next.status;
      if (!report.equals(lastReport)) {
        log(report);
        lastReport = report;
      }
      publish(next);
      failures = 0;
    } catch (Throwable t) {
      publish(new Snapshot(null, null));
      log(
          "REFRESH_ERROR, native behavior restored: "
              + t.getClass().getSimpleName()
              + ": "
              + t.getMessage());
      if (++failures >= 3) fail("three consecutive refresh errors", t);
    }
  }

  private void publish(Snapshot next) {
    Snapshot before = snapshot;
    snapshot = next;
    boolean fontChanged = !Objects.equals(before.font, next.font);
    boolean statusChanged = !Objects.equals(before.status, next.status);
    if (main == null || (!fontChanged && !statusChanged)) return;
    main.post(
        () -> {
          try {
            List<Observed> copy;
            synchronized (observers) {
              observers.removeIf(o -> o.ref.get() == null);
              copy = new ArrayList<>(observers);
            }
            int count = 0;
            for (Observed o : copy) {
              if ((FONT.equals(o.key) && fontChanged) || (STATUS.equals(o.key) && statusChanged)) {
                ContentObserver observer = o.ref.get();
                if (observer != null) {
                  observer.dispatchChange(false, android.provider.Settings.System.getUriFor(o.key));
                  count++;
                }
              }
            }
            log("DISPATCH local observers=" + count);
          } catch (Throwable t) {
            fail("local dispatch", t);
          }
        });
  }

  private void fail(String stage, Throwable t) {
    // Restore the original reads and let native observers reload their saved values.
    failed = true;
    HostStatus.report(context, "ERROR", stage + ": " + t.getClass().getSimpleName());
    if (main != null)
      main.post(
          () -> {
            pages.setEnabled(false);
            drawer.setMode(ColorPolicy.OFF, false);
            recents.setEnabled(false);
            recentsBar.setEnabled(false);
          });
    publish(new Snapshot(null, null));
    log("DISABLED " + stage + ": " + t);
  }

  static void log(String message) {
    android.util.Log.i("OneUiLiveColors", message);
    ModernHook.log(message);
  }

  private static String sha256(String path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[65536];
    try (FileInputStream stream = new FileInputStream(path)) {
      int n;
      while ((n = stream.read(buffer)) != -1) digest.update(buffer, 0, n);
    }
    StringBuilder result = new StringBuilder();
    for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255));
    return result.toString();
  }

  private static final class Observed {
    final String key;
    final WeakReference<ContentObserver> ref;

    Observed(String key, ContentObserver observer) {
      this.key = key;
      ref = new WeakReference<>(observer);
    }
  }

  private static final class Snapshot {
    final Integer font, status;

    Snapshot(Integer font, Integer status) {
      this.font = font;
      this.status = status;
    }
  }

  private static final class TargetPackage {
    final String packageName;
    final String processName;
    final android.content.pm.ApplicationInfo appInfo;
    final ClassLoader classLoader;

    TargetPackage(
        String packageName,
        String processName,
        android.content.pm.ApplicationInfo appInfo,
        ClassLoader classLoader) {
      this.packageName = packageName;
      this.processName = processName;
      this.appInfo = appInfo;
      this.classLoader = classLoader;
    }
  }
}
