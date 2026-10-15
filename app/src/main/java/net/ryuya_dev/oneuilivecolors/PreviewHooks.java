package net.ryuya_dev.oneuilivecolors;

import android.app.*;
import android.content.*;
import android.database.*;
import android.graphics.Rect;
import android.net.Uri;
import android.os.*;
import android.view.*;
import de.robv.android.xposed.*;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.StringReference;

/** Mirrors the existing home wallpaper. No secondary engine, offsets or visibility commands. */
@androidx.annotation.RequiresApi(36)
final class PreviewHooks {
  private final Map<SurfaceView, Session> views = new WeakHashMap<>();
  private final Map<Activity, Boolean> originalFlags = new WeakHashMap<>();
  private Handler main, worker;
  private Application app;
  private boolean enabled, failed;
  private Class<?> previewClass;
  private Method mirrorWallpaper, parentSurface;
  private Object windowService;

  void install(Application context, ClassLoader loader) throws Exception {
    app = context;
    main = new Handler(Looper.getMainLooper());
    List<Class<?>> previews = new ArrayList<>();
    var dex =
        DexFileFactory.loadDexContainer(
            new File(app.getApplicationInfo().sourceDir), Opcodes.getDefault());
    for (String entry : dex.getDexEntryNames())
      for (var c : dex.getEntry(entry).getDexFile().getClasses()) {
        boolean preview = false;
        for (var m : c.getMethods())
          if (m.getImplementation() != null)
            for (var i : m.getImplementation().getInstructions())
              if (i instanceof ReferenceInstruction r
                  && r.getReference() instanceof StringReference str
                  && "HomePreviewSurfaceView".equals(str.getString())) preview = true;
        if (!preview) continue;
        Class<?> actual =
            Class.forName(
                c.getType().substring(1, c.getType().length() - 1).replace('/', '.'),
                false,
                loader);
        if (SurfaceView.class.isAssignableFrom(actual)
            && SurfaceHolder.Callback.class.isAssignableFrom(actual)) previews.add(actual);
      }
    if (previews.size() != 1)
      throw new IllegalStateException("Ambiguous native preview DEX evidence");
    previewClass = previews.get(0);
    windowService =
        Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService")
            .invoke(null);
    mirrorWallpaper =
        Class.forName("android.view.IWindowManager").getMethod("mirrorWallpaperSurface", int.class);
    if (mirrorWallpaper.getReturnType() != SurfaceControl.class)
      throw new IllegalStateException("Wallpaper mirror contract changed");
    parentSurface = SurfaceView.class.getMethod("getRenderingSurfaceControl");
    XposedBridge.hookAllConstructors(
        previewClass,
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (!p.hasThrowable()) {
              SurfaceView view = (SurfaceView) p.thisObject;
              if (!views.containsKey(view)) {
                views.put(view, new Session());
                view.getViewTreeObserver()
                    .addOnWindowFocusChangeListener(
                        focus -> {
                          if (focus) safeStart(view);
                        });
              }
            }
          }
        });
    XC_MethodHook update =
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (!p.hasThrowable()) safeStart((SurfaceView) p.thisObject);
          }
        };
    XposedBridge.hookMethod(previewClass.getMethod("surfaceCreated", SurfaceHolder.class), update);
    XposedBridge.hookMethod(
        previewClass.getMethod(
            "surfaceChanged", SurfaceHolder.class, int.class, int.class, int.class),
        update);
    XposedBridge.hookMethod(
        previewClass.getMethod("surfaceDestroyed", SurfaceHolder.class),
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            stop((SurfaceView) p.thisObject);
          }
        });
    XposedHelpers.findAndHookMethod(
        SurfaceView.class,
        "setChildSurfacePackage",
        SurfaceControlViewHost.SurfacePackage.class,
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (previewClass.isInstance(p.thisObject) && !p.hasThrowable())
              try {
                layers((SurfaceView) p.thisObject);
              } catch (Throwable t) {
                disable(t);
              }
          }
        });
    HandlerThread thread = new HandlerThread("OneUiPreviewConfig");
    thread.start();
    worker = new Handler(thread.getLooper());
    app.getContentResolver()
        .registerContentObserver(
            ConfigProvider.URI,
            false,
            new ContentObserver(worker) {
              @Override
              public void onChange(boolean self, Uri uri) {
                refresh();
              }
            });
    app.registerReceiver(
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context c, Intent i) {
            refresh();
          }
        },
        new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
        Context.RECEIVER_NOT_EXPORTED);
    app.registerActivityLifecycleCallbacks(
        new Application.ActivityLifecycleCallbacks() {
          public void onActivityResumed(Activity a) {
            refresh();
          }

          public void onActivityPaused(Activity a) {}

          public void onActivityDestroyed(Activity a) {
            for (SurfaceView v : new ArrayList<>(views.keySet()))
              if (activity(v.getContext()) == a) stop(v);
            restoreFlag(a);
          }

          public void onActivityCreated(Activity a, Bundle b) {}

          public void onActivityStarted(Activity a) {}

          public void onActivityStopped(Activity a) {
            for (SurfaceView v : new ArrayList<>(views.keySet()))
              if (activity(v.getContext()) == a) stop(v);
          }

          public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        });
    worker.post(this::refresh);
    HookEntry.log(
        "PREVIEW_HOOK_READY view=" + previewClass.getName() + "; existing home wallpaper mirror");
  }

  private void refresh() {
    if (failed) return;
    try (Cursor c = app.getContentResolver().query(ConfigProvider.URI, null, null, null, null)) {
      if (c == null || !c.moveToFirst()) throw new IllegalStateException("No configuration");
      boolean on =
          c.getInt(c.getColumnIndexOrThrow("enabled")) != 0
              && c.getInt(c.getColumnIndexOrThrow("preview_enabled")) != 0
              && c.getInt(c.getColumnIndexOrThrow("preview")) != 0;
      WallpaperInfo info = WallpaperManager.getInstance(app).getWallpaperInfo();
      on &= info != null;
      boolean next = on;
      main.post(
          () -> {
            enabled = next;
            for (SurfaceView v : new ArrayList<>(views.keySet()))
              if (next) safeStart(v);
              else stop(v);
            if (!next) for (Activity a : new ArrayList<>(originalFlags.keySet())) restoreFlag(a);
            HostStatus.report(
                app,
                next ? "ACTIVE" : "OFF",
                "Existing wallpaper mirror " + (next ? "ready" : "disabled"));
          });
    } catch (Throwable t) {
      main.post(() -> disable(t));
    }
  }

  private static Activity activity(Context context) {
    for (int n = 0; n < 10; n++) {
      if (context instanceof Activity a) return a;
      if (!(context instanceof ContextWrapper w) || w.getBaseContext() == context) return null;
      context = w.getBaseContext();
    }
    return null;
  }

  private void safeStart(SurfaceView view) {
    try {
      start(view);
    } catch (Throwable t) {
      disable(t);
    }
  }

  private void start(SurfaceView view) throws Exception {
    if (!enabled
        || failed
        || !view.isAttachedToWindow()
        || !view.isShown()
        || !view.getHolder().getSurface().isValid()
        || view.getWidth() == 0
        || view.getHeight() == 0
        || view.getDisplay() == null
        || view.getDisplay().getDisplayId() != 0) return;
    if (app.getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
    Activity a = activity(view.getContext());
    if (a == null || a.isFinishing() || a.isDestroyed() || !a.hasWindowFocus()) return;
    Session s = views.computeIfAbsent(view, k -> new Session());
    if (s.mirror != null) {
      resize(view, s);
      return;
    }
    if (s.pending) return;
    originalFlags.putIfAbsent(
        a,
        (a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            != 0);
    // Keeps the existing home engine visible behind this opaque settings window.
    a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
    s.pending = true;
    int generation = ++s.generation;
    requestMirror(view, s, generation, 0);
  }

  private void requestMirror(SurfaceView view, Session s, int generation, int attempt) {
    worker.postDelayed(
        () -> {
          try {
            SurfaceControl mirror = (SurfaceControl) mirrorWallpaper.invoke(windowService, 0);
            main.post(
                () -> {
                  if (s.generation != generation
                      || !enabled
                      || failed
                      || !view.isAttachedToWindow()) {
                    if (mirror != null) mirror.release();
                    return;
                  }
                  if (mirror == null || !mirror.isValid()) {
                    if (mirror != null) mirror.release();
                    if (attempt < 2) requestMirror(view, s, generation, attempt + 1);
                    else {
                      s.pending = false;
                      HostStatus.report(app, "ERROR", "Existing wallpaper surface unavailable");
                    }
                    return;
                  }
                  try {
                    s.pending = false;
                    s.mirror = mirror;
                    resize(view, s);
                    layers(view);
                    HookEntry.log("PREVIEW_HOME_MIRROR_ATTACHED current home page");
                    HostStatus.report(
                        app, "ACTIVE", "Current home wallpaper mirrored below native icons");
                  } catch (Throwable t) {
                    disable(t);
                  }
                });
          } catch (Throwable t) {
            main.post(() -> disable(t));
          }
        },
        attempt == 0 ? 120 : 250);
  }

  private void resize(SurfaceView view, Session s) throws Exception {
    SurfaceControl parent = (SurfaceControl) parentSurface.invoke(view);
    if (parent == null || !parent.isValid())
      throw new IllegalStateException("Preview surface unavailable");
    Rect source = app.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
    if (source.width() == 0 || source.height() == 0)
      throw new IllegalStateException("Unknown source size");
    try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
      t.reparent(s.mirror, parent)
          .setLayer(s.mirror, 1)
          .setCrop(s.mirror, new Rect(0, 0, source.width(), source.height()))
          .setScale(
              s.mirror,
              view.getWidth() / (float) source.width(),
              view.getHeight() / (float) source.height())
          .setVisibility(s.mirror, true)
          .apply();
    }
  }

  private void layers(SurfaceView view) {
    Session s = views.get(view);
    if (s == null || s.mirror == null) return;
    var child = view.getChildSurfacePackage();
    if (child == null) return;
    SurfaceControl icons = child.getSurfaceControl();
    if (icons != null && icons.isValid())
      try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
        t.setLayer(icons, 2).apply();
        s.iconRaised = true;
      }
  }

  private void stop(SurfaceView view) {
    Session s = views.get(view);
    if (s == null) return;
    s.generation++;
    s.pending = false;
    if (s.mirror != null) {
      try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
        t.reparent(s.mirror, null).apply();
      } catch (Throwable ignored) {
      }
      s.mirror.release();
      s.mirror = null;
    }
    if (s.iconRaised)
      try {
        var child = view.getChildSurfacePackage();
        if (child != null)
          try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            t.setLayer(child.getSurfaceControl(), 0).apply();
          }
      } catch (Throwable ignored) {
      } finally {
        s.iconRaised = false;
      }
  }

  private void restoreFlag(Activity a) {
    Boolean original = originalFlags.remove(a);
    if (Boolean.FALSE.equals(original) && !a.isDestroyed())
      a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
  }

  private void disable(Throwable t) {
    failed = true;
    enabled = false;
    for (SurfaceView v : new ArrayList<>(views.keySet())) stop(v);
    for (Activity a : new ArrayList<>(originalFlags.keySet())) restoreFlag(a);
    HostStatus.report(app, "ERROR", "Preview: " + t.getClass().getSimpleName());
    HookEntry.log("PREVIEW_DISABLED " + t);
  }

  private static final class Session {
    SurfaceControl mirror;
    boolean pending, iconRaised;
    int generation;
  }
}
