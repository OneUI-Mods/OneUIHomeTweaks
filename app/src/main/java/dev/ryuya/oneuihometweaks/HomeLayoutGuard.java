package dev.ryuya.oneuihometweaks;

import android.app.Activity;
import android.app.Application;
import android.content.*;
import android.database.ContentObserver;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.*;
import android.os.Process;
import android.view.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;

/**
 * BZI9-only layout stabilization. Never edits the layout database or restores guessed coordinates.
 */
final class HomeLayoutGuard {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Map<Object, Pending> pending = new WeakHashMap<>();
  private final Set<ViewGroup> layouts = Collections.newSetFromMap(new WeakHashMap<>());
  private final List<ModernHook.Handle> handles = new ArrayList<>();
  private final ArrayDeque<String> journal = new ArrayDeque<>();
  private Application app;
  private Handler io;
  private Class<?> itemType, spannableType;
  private Method updateAll, createParams, updateStyle, getBounds;
  private volatile boolean enabled = true, logging = true;
  private boolean replaying, installing, installed;
  private volatile boolean stopped;
  private final Map<Integer, String> previousItems = new HashMap<>();
  private String observed = "";
  private final Set<Class<?>> repositoryTypes = new HashSet<>();
  private long sequence, geometryChanged, dropped;
  private String geometry = "";
  private WeakReference<Object> nativeBounds = new WeakReference<>(null);
  private boolean touchDown;
  private final String session = UUID.randomUUID().toString();
  private final Runnable drain = this::flush;

  void start(Application application) {
    app = application;
    HandlerThread thread =
        new HandlerThread("HomeLayoutJournal", Process.THREAD_PRIORITY_BACKGROUND);
    thread.start();
    io = new Handler(thread.getLooper());
    readConfig();
    app.getContentResolver()
        .registerContentObserver(
            ConfigProvider.URI,
            false,
            new ContentObserver(io) {
              @Override
              public void onChange(boolean selfChange) {
                readConfig();
              }
            });
    io.post(drain);
  }

  void observe(ViewGroup workspace) {
    if (app == null || stopped) return;
    if (!installed) {
      if (!installing) install(workspace.getClass().getClassLoader());
      if (!installed) return;
    }
    try {
      var config = workspace.getResources().getConfiguration();
      String key =
          workspace.getWidth()
              + "x"
              + workspace.getHeight()
              + "/"
              + workspace.getChildCount()
              + "/"
              + config.orientation
              + "/"
              + config.screenWidthDp
              + "/"
              + config.screenHeightDp
              + "/"
              + config.densityDpi;
      if (key.equals(observed)) return;
      observed = key;
      collectLayouts(workspace);
      Set<Object> owners = Collections.newSetFromMap(new IdentityHashMap<>());
      for (ViewGroup layout : new ArrayList<>(layouts)) {
        Object vm = call(layout, "getViewModel");
        if (Boolean.TRUE.equals(call(vm, "getIsPreview"))) continue;
        observeRepository(vm);
        if (enabled && owners.add(vm)) updateAll.invoke(vm);
      }
    } catch (Throwable error) {
      disableRepair(error);
    }
  }

  private void collectLayouts(ViewGroup view) {
    if (view.getClass()
        .getName()
        .equals("com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridCellLayout")) {
      layouts.add(view);
      return;
    }
    for (int i = 0; i < view.getChildCount(); i++)
      if (view.getChildAt(i) instanceof ViewGroup group) collectLayouts(group);
  }

  private void install(ClassLoader loader) {
    installing = true;
    try {
      long version =
          app.getPackageManager().getPackageInfo(app.getPackageName(), 0).getLongVersionCode();
      if (Build.VERSION.SDK_INT != 37
          || version != Android17Hooks.LAUNCHER_VERSION
          || app.getPackageManager()
                  .getPackageInfo("com.samsung.android.app.homestar", 0)
                  .getLongVersionCode()
              != 1800005036L)
        throw new UnsupportedOperationException(
            "No reviewed layout-repair profile for this launcher");
      Class<?> bounds = Class.forName("z5.b", false, app.getClassLoader());
      Method updateBounds = bounds.getDeclaredMethod("j", Context.class, WindowInsets.class);
      getBounds = bounds.getDeclaredMethod("b", android.content.res.Configuration.class);
      Class<?> vm =
          Class.forName(
              "com.honeyspace.ui.honeypots.freegrid.viewmodel.FreeGridViewModel", false, loader);
      Class<?> layout =
          Class.forName(
              "com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridCellLayout",
              false,
              loader);
      itemType =
          Class.forName(
              "com.honeyspace.ui.honeypots.freegrid.domain.model.FreeGridItem", false, loader);
      spannableType = Class.forName("com.honeyspace.common.widget.SpannableView", false, loader);
      updateAll = vm.getDeclaredMethod("updateAllItemByLayoutChanged");
      createParams = layout.getDeclaredMethod("createLayoutParams", itemType);
      updateStyle =
          layout.getDeclaredMethod(
              "updateItemStyle", View.class, itemType, float.class, boolean.class);
      vm.getDeclaredMethod("getBaseCellSize");
      vm.getDeclaredField("uiContext");
      // Resolve every repair contract before intercepting any behavior.
      hookBounds(updateBounds);
      hookLayoutUpdate();
      handles.addAll(
          ModernHook.hookAllConstructors(
              layout,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (!p.hasThrowable()) layouts.add((ViewGroup) p.thisObject);
                }
              }));
      hookSanitizer(loader);
      installDiagnostics(vm, loader);
      String apk =
          app.getPackageManager()
              .getApplicationInfo("com.samsung.android.app.homestar", 0)
              .sourceDir;
      io.post(
          () -> {
            try {
              int count = DexEvidence.deoptimizeLayoutCalls(apk, loader);
                  count += DexEvidence.deoptimizeLayoutCalls(app.getApplicationInfo().sourceDir, app.getClassLoader());
              record("callsites_ready", "count", count);
            } catch (Throwable error) {
              main.post(() -> disableRepair(error));
            }
          });
      installed = true;
      record("ready", "profile", "android17-bzi9", "version", BuildConfig.VERSION_NAME);
    } catch (Throwable error) {
      for (ModernHook.Handle handle : handles) handle.unhook();
      handles.clear();
      stopped = true;
      record("unsupported", "error", error.toString());
      ModernHook.log("HOME_LAYOUT_UNSUPPORTED " + error);
    }
  }

  private void hookBounds(Method method) {
    handles.add(
        ModernHook.hookMethod(
            method,
            new ModernHook.Callback() {
              @Override
              protected void beforeHookedMethod(ModernHook.Param p) {
                try {
                  nativeBounds = new WeakReference<>(p.thisObject);
                  Context context = (Context) p.args[0];
                  Rect bounds = configurationBounds(context);
                  WindowInsets supplied = (WindowInsets) p.args[1];
                  boolean valid = consistent(bounds, supplied);
                  if (!valid && enabled && !stopped) {
                    WindowMetrics current =
                        context.getSystemService(WindowManager.class).getCurrentWindowMetrics();
                    if (same(bounds, current.getBounds())
                        && consistent(bounds, current.getWindowInsets())) {
                      p.args[1] = current.getWindowInsets();
                      record(
                          "insets_replaced",
                          "bounds",
                          bounds,
                          "oldCutout",
                          cutout(supplied),
                          "newCutout",
                          cutout((WindowInsets) p.args[1]));
                    } else {
                      // Retain the last native geometry; deferred sanitation cannot persist this
                      // transition.
                      p.setResult(null);
                      record(
                          "insets_rejected",
                          "bounds",
                          bounds,
                          "cutout",
                          cutout(supplied),
                          "metrics",
                          current.getBounds());
                    }
                  }
                  String key = bounds + "/" + cutout((WindowInsets) p.args[1]);
                  if (!key.equals(geometry)) {
                    geometry = key;
                    geometryChanged = SystemClock.uptimeMillis();
                    record("window", "bounds", bounds, "cutout", cutout(supplied), "valid", valid);
                  }
                } catch (Throwable error) {
                  disableRepair(error);
                }
              }
            }));
  }

  private void hookLayoutUpdate() {
    handles.add(
        ModernHook.hookMethod(
            updateAll,
            new ModernHook.Callback() {
              @Override
              protected void beforeHookedMethod(ModernHook.Param p) {
                if (!enabled || stopped || replaying || Looper.myLooper() != Looper.getMainLooper())
                  return;
                try {
                  if (Boolean.TRUE.equals(call(p.thisObject, "getIsPreview"))) return;
                  observeRepository(p.thisObject);
                  Pending state = pending.get(p.thisObject);
                  if (state == null) {
                    state = new Pending(p.thisObject);
                    pending.put(p.thisObject, state);
                  }
                  state.request();
                  p.setResult(null);
                } catch (Throwable error) {
                  disableRepair(error);
                }
              }
            }));
  }

  private void hookSanitizer(ClassLoader loader) throws ReflectiveOperationException {
    Class<?> type =
        Class.forName(
            "com.honeyspace.ui.honeypots.freegrid.domain.model.FreeGridDataSanitizer",
            false,
            loader);
    Method method = null;
    for (Method candidate : type.getDeclaredMethods())
      if (candidate.getName().equals("sanitizeOnCellLayoutSizeChanged")
          && candidate.getParameterCount() == 5) method = candidate;
    if (method == null) throw new NoSuchMethodException("Layout sanitizer");
    handles.add(
        ModernHook.hookMethod(
            method,
            new ModernHook.Callback() {
              @Override
              protected void beforeHookedMethod(ModernHook.Param p) {
                try {
                  Context context = (Context) p.args[0];
                  boolean valid = metricsValid(context) && nativeGeometryValid(context)
                  && SystemClock.uptimeMillis() - geometryChanged >= HomeLayoutPolicy.SETTLE_MS;
                  record(
                      "sanitize",
                      "reason",
                      p.args[4],
                      "orientation",
                      p.args[1],
                      "size",
                      p.args[2],
                      "valid",
                      valid,
                      "caller",
                      stack());
                  if (enabled && !stopped && !valid) {
                    p.setResult(new PointF(1, 1));
                    record("sanitize_blocked", "reason", p.args[4], "size", p.args[2]);
                  }
                } catch (Throwable error) {
                  disableRepair(error);
                }
              }

              @Override
              protected void afterHookedMethod(ModernHook.Param p) {
                record("sanitize_result", "ratio", p.getResult(), "failed", p.hasThrowable());
              }
            }));
  }

  private final class Pending implements Runnable {
    final WeakReference<Object> owner;
    long started;
    Point candidate;

    Pending(Object vm) {
      owner = new WeakReference<>(vm);
    }

    void request() throws ReflectiveOperationException {
      if (started == 0) {
        started = SystemClock.uptimeMillis();
        record("layout_deferred", "size", call(owner.get(), "getCellLayoutSize"));
      }
      candidate = new Point((Point) call(owner.get(), "getCellLayoutSize"));
      main.removeCallbacks(this);
      main.postDelayed(this, HomeLayoutPolicy.SETTLE_MS);
    }

    @Override
    public void run() {
      Object vm = owner.get();
      if (vm == null) return;
      try {
        Context context = uiContext(vm);
        Point current = new Point((Point) call(vm, "getCellLayoutSize"));
        boolean stable =
            candidate.equals(current)
                && SystemClock.uptimeMillis() - geometryChanged >= HomeLayoutPolicy.SETTLE_MS
                && metricsValid(context)
                && nativeGeometryValid(context)
                && measuredGeometryValid(vm, current)
                && !touchDown;
        if (enabled && !stopped && !stable) {
          if (SystemClock.uptimeMillis() - started >= HomeLayoutPolicy.MAX_WAIT_MS) {
            record(
                "layout_abandoned", "size", current, "reason", "geometry did not settle; no write");
            pending.remove(vm);
            return;
          }
          candidate = current;
          main.postDelayed(this, HomeLayoutPolicy.SETTLE_MS);
          return;
        }
        pending.remove(vm);
        replaying = true;
        try {
          updateAll.invoke(vm);
          if (enabled && !stopped) refreshWidgets(vm, context);
          record("layout_applied", "size", current);
        } finally {
          replaying = false;
        }
      } catch (Throwable error) {
        disableRepair(error);
      }
    }
  }

  private void refreshWidgets(Object vm, Context context) throws ReflectiveOperationException {
    Object style = call(vm, "getItemLayoutStyle");
    Object option = call(call(vm, "getCommonStyleOption"), "getValue");
    Method refresh = named(style.getClass(), "updateCommonItemStyle", 4);
    refresh.invoke(style, option, call(vm, "getBaseCellSize"), context, "HomeLayoutGuard-stable");
    Map<Integer, Object> items = new HashMap<>();
    for (Object item : (Iterable<?>) call(vm, "getItems")) items.put(number(item, "getId"), item);
    int count = 0;
    for (ViewGroup layout : new ArrayList<>(layouts)) {
      if (!layout.isAttachedToWindow() || call(layout, "getViewModel") != vm) continue;
      for (int i = 0; i < layout.getChildCount(); i++) {
        View view = layout.getChildAt(i);
        if (!spannableType.isInstance(view)) continue;
        Object item = items.get(number(view, "getItemId"));
        if (item == null || !item.getClass().getSimpleName().contains("Widget")) continue;
        ViewGroup.LayoutParams params = (ViewGroup.LayoutParams) createParams.invoke(layout, item);
        updateStyle.invoke(
            layout, view, item, ((Number) call(item, "getScale")).floatValue(), false);
        view.setLayoutParams(params);
        record(
            "widget_refreshed",
            "item",
            snapshot(item),
            "width",
            params.width,
            "height",
            params.height);
        count++;
      }
    }
    record("widgets_refreshed", "count", count);
  }

  private void installDiagnostics(Class<?> vm, ClassLoader loader)
      throws ReflectiveOperationException {
    app.registerActivityLifecycleCallbacks(
        new Application.ActivityLifecycleCallbacks() {
          @Override
          public void onActivityResumed(Activity a) {
            activity(a, "resumed");
          }

          @Override
          public void onActivityPaused(Activity a) {
            activity(a, "paused");
          }

          @Override
          public void onActivityCreated(Activity a, Bundle b) {
            activity(a, "created");
          }

          @Override
          public void onActivityDestroyed(Activity a) {
            activity(a, "destroyed");
          }

          @Override
          public void onActivityStarted(Activity a) {}

          @Override
          public void onActivityStopped(Activity a) {}

          @Override
          public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        });
    handles.add(
        ModernHook.findAndHookMethod(
            Activity.class,
            "dispatchTouchEvent",
            MotionEvent.class,
            new ModernHook.Callback() {
              private float downX, downY;
              private long downAt;

              @Override
              protected void beforeHookedMethod(ModernHook.Param p) {
                Activity activity = (Activity) p.thisObject;
                if (!app.getPackageName().equals(activity.getPackageName())) return;
                MotionEvent event = (MotionEvent) p.args[0];
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                  touchDown = true;
                  downX = event.getX();
                  downY = event.getY();
                  downAt = event.getEventTime();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                  touchDown = false;
                  float dx = event.getX() - downX, dy = event.getY() - downY;
                  float slop = ViewConfiguration.get(activity).getScaledTouchSlop();
                  record(
                      "home_input",
                      "kind",
                      Math.hypot(dx, dy) <= slop ? "tap" : "swipe_or_drag",
                      "dx",
                      Math.round(dx),
                      "dy",
                      Math.round(dy),
                      "durationMs",
                      event.getEventTime() - downAt);
                } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                  touchDown = false;
                }
              }
            }));
    handles.add(
        ModernHook.findAndHookMethod(
            Activity.class,
            "startActivityForResult",
            Intent.class,
            int.class,
            Bundle.class,
            new ModernHook.Callback() {
              @Override
              protected void beforeHookedMethod(ModernHook.Param p) {
                Activity activity = (Activity) p.thisObject;
                if (!app.getPackageName().equals(activity.getPackageName())) return;
                Intent intent = (Intent) p.args[0];
                if (intent != null)
                  record(
                      "launch", "component", intent.getComponent(), "action", intent.getAction());
              }
            }));
  }

  private void observeRepository(Object vm) throws ReflectiveOperationException {
    Field field = vm.getClass().getDeclaredField("freeGridRepository");
    field.setAccessible(true);
    Object repository = field.get(vm);
    if (!repositoryTypes.add(repository.getClass())) return;
    for (Object item : (Iterable<?>) call(vm, "getItems")) {
      previousItems.put(number(item, "getId"), snapshot(item));
      record("item_baseline", "item", snapshot(item));
    }
    for (Method update : repository.getClass().getDeclaredMethods()) {
      if (!update.getName().equals("update")
          || update.getParameterCount() == 0
          || update.getParameterTypes()[0] != itemType
          || Modifier.isAbstract(update.getModifiers())) continue;
      handles.add(
          ModernHook.hookMethod(
              update,
              new ModernHook.Callback() {
                @Override
                protected void beforeHookedMethod(ModernHook.Param p) {
                  if (!logging) return;
                  try {
                    int id = number(p.args[0], "getId");
                    String next = snapshot(p.args[0]);
                    String old = previousItems.put(id, next);
                    record("item_write", "before", old, "after", next, "caller", stack());
                  } catch (Throwable error) {
                    record("item_write", "item", snapshot(p.args[0]), "caller", stack());
                  }
                }

                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (p.hasThrowable()) record("item_write_failed", "error", "native exception");
                }
              }));
    }
    record("repository_observed", "class", repository.getClass().getName());
  }

  private void activity(Activity activity, String event) {
    record(
        "activity",
        "event",
        event,
        "class",
        activity.getClass().getName(),
        "orientation",
        activity.getResources().getConfiguration().orientation,
        "rotation",
        activity.getDisplay() == null ? -1 : activity.getDisplay().getRotation());
  }

  private void disableRepair(Throwable error) {
    stopped = true;
    record("repair_disabled", "error", error.toString(), "caller", stack());
    ModernHook.log("HOME_LAYOUT_DISABLED " + error);
  }

  private void readConfig() {
    try {
      Bundle config =
          app.getContentResolver().call(ConfigProvider.URI, "layout_config", null, null);
      if (config == null) return;
      enabled = config.getBoolean("protect", true);
      logging = config.getBoolean("logging", true);
      record("config", "protect", enabled, "logging", logging);
    } catch (Throwable error) {
      ModernHook.log("HOME_LAYOUT_CONFIG " + error.getClass().getSimpleName());
    }
  }

  private synchronized void record(String event, Object... fields) {
    if (!logging) return;
    try {
      JSONObject row = new JSONObject();
      row.put("time", System.currentTimeMillis());
      row.put("uptime", SystemClock.uptimeMillis());
      row.put("session", session);
      row.put("pid", Process.myPid());
      row.put("seq", ++sequence);
      row.put("event", event);
      for (int i = 0; i + 1 < fields.length; i += 2)
        row.put(
            String.valueOf(fields[i]),
            fields[i + 1] == null ? JSONObject.NULL : fields[i + 1].toString());
      if (journal.size() >= 256) {
        journal.removeFirst();
        dropped++;
      }
      journal.addLast(row + "\n");
    } catch (Throwable ignored) {
      /* Logging must never interrupt the launcher. */
    }
  }

  private void flush() {
    StringBuilder batch = new StringBuilder();
    synchronized (this) {
      if (dropped > 0) {
        record("records_dropped", "count", dropped);
        dropped = 0;
      }
      while (!journal.isEmpty() && batch.length() + journal.peekFirst().length() <= 44000)
        batch.append(journal.removeFirst());
    }
    if (batch.length() > 0) {
      try {
        Bundle data = new Bundle();
        data.putString("records", batch.toString());
        app.getContentResolver().call(ConfigProvider.URI, "layout_append", null, data);
      } catch (Throwable error) {
        ModernHook.log("HOME_LAYOUT_JOURNAL " + error.getClass().getSimpleName());
      }
    }
    io.postDelayed(drain, 2000);
  }

  private Rect configurationBounds(Context context) throws ReflectiveOperationException {
    return new Rect((Rect) getBounds.invoke(null, context.getResources().getConfiguration()));
  }

  private boolean metricsValid(Context context) throws ReflectiveOperationException {
    WindowMetrics metrics = context.getSystemService(WindowManager.class).getCurrentWindowMetrics();
    Rect bounds = configurationBounds(context);
    return same(bounds, metrics.getBounds()) && consistent(bounds, metrics.getWindowInsets());
  }

  private boolean nativeGeometryValid(Context context) throws ReflectiveOperationException {
    Object bounds = nativeBounds.get();
    if (bounds == null)
      return true; // Before the first observed bounds update, check measured layout too.
    Rect current = (Rect) call(bounds, "getBounds");
    Rect safe = (Rect) call(bounds, "getCutout");
    return same(current, configurationBounds(context))
        && HomeLayoutPolicy.consistent(
            current.width(), current.height(), safe.left, safe.top, safe.right, safe.bottom);
  }

  private boolean measuredGeometryValid(Object vm, Point expected)
      throws ReflectiveOperationException {
    boolean found = false;
    for (ViewGroup layout : new ArrayList<>(layouts)) {
      if (!layout.isAttachedToWindow() || call(layout, "getViewModel") != vm) continue;
      if (layout.getWidth() == 0 || layout.getHeight() == 0) continue;
      if (!HomeLayoutPolicy.sameBounds(
          expected.x, expected.y, layout.getWidth(), layout.getHeight())) return false;
      found = true;
    }
    return found;
  }

  private static boolean same(Rect a, Rect b) {
    return HomeLayoutPolicy.sameBounds(a.width(), a.height(), b.width(), b.height());
  }

  private static boolean consistent(Rect bounds, WindowInsets insets) {
    if (insets == null) return false;
    DisplayCutout cutout = insets.getDisplayCutout();
    return HomeLayoutPolicy.consistent(
        bounds.width(),
        bounds.height(),
        cutout == null ? 0 : cutout.getSafeInsetLeft(),
        cutout == null ? 0 : cutout.getSafeInsetTop(),
        cutout == null ? 0 : cutout.getSafeInsetRight(),
        cutout == null ? 0 : cutout.getSafeInsetBottom());
  }

  private static String cutout(WindowInsets insets) {
    DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
    return cutout == null
        ? "none"
        : new Rect(
                cutout.getSafeInsetLeft(),
                cutout.getSafeInsetTop(),
                cutout.getSafeInsetRight(),
                cutout.getSafeInsetBottom())
            .toShortString();
  }

  private static Context uiContext(Object vm) throws ReflectiveOperationException {
    Field field = vm.getClass().getDeclaredField("uiContext");
    field.setAccessible(true);
    return (Context) field.get(vm);
  }

  private static Object call(Object owner, String name) throws ReflectiveOperationException {
    Method method = owner.getClass().getMethod(name);
    method.setAccessible(true);
    return method.invoke(owner);
  }

  private static Method named(Class<?> type, String name, int parameters)
      throws NoSuchMethodException {
    for (Method method : type.getDeclaredMethods())
      if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
    throw new NoSuchMethodException(name);
  }

  private static int number(Object item, String name) throws ReflectiveOperationException {
    return ((Number) call(item, name)).intValue();
  }

  private static String snapshot(Object item) {
    try {
      return "id="
          + number(item, "getId")
          + ",type="
          + item.getClass().getSimpleName()
          + ",page="
          + number(item, "getPageId")
          + ",x="
          + number(item, "getX")
          + ",y="
          + number(item, "getY")
          + ",span="
          + number(item, "getSpanX")
          + "x"
          + number(item, "getSpanY")
          + ",scale="
          + call(item, "getScale");
    } catch (Throwable error) {
      return item.getClass().getName() + ":snapshot unavailable";
    }
  }

  private static String stack() {
    StringBuilder trace = new StringBuilder();
    for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
      String name = frame.getClassName();
      if (name.startsWith("com.honeyspace.") || name.startsWith("com.sec.android.app.launcher.")) {
        if (trace.length() > 0) trace.append(" <- ");
        trace
            .append(name)
            .append('.')
            .append(frame.getMethodName())
            .append(':')
            .append(frame.getLineNumber());
        if (trace.length() > 1800) break;
      }
    }
    return trace.toString();
  }
}
