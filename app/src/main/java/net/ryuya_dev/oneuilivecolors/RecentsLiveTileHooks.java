package net.ryuya_dev.oneuilivecolors;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Local prototype of Samsung's native live-tile lifetime. No app lifecycle methods are suppressed.
 */
final class RecentsLiveTileHooks {
  private final Map<Object, Session> sessions = new WeakHashMap<>();
  private final Map<Object, Session> gestures = new WeakHashMap<>();
  private final List<ModernHook.Handle> hooks = new ArrayList<>();
  private Field callbacks, action, managerDisplay, gestureDisplay, topSource;
  private Field nativeHold, willGo;
  private Method desktop, gestureDesktop, topTask, taskConfig, windowMode, activityType;
  private Method setHold, finish, systemController, recentTarget, taskId, taskScene;
  private Field inputProxy, proxyRecents, proxyHome, proxySize, appPlayer;
  private Class<?> taskViewType;
  private RecentsLiveSurface surface;
  private Class<?> gestureType;
  private Context context;
  private volatile boolean enabled, failed;
  private boolean releasing;
  private Application application;
  private Class<?> recentsType;
  private final Application.ActivityLifecycleCallbacks lifecycle =
      new Application.ActivityLifecycleCallbacks() {
        public void onActivityCreated(Activity a, Bundle b) {}

        public void onActivityStarted(Activity a) {}

        public void onActivityResumed(Activity a) {
          if (recentsType != null
              && !recentsType.isInstance(a)
              && sessions.values().stream().anyMatch(s -> s.held))
            release("other activity resumed");
        }

        public void onActivityPaused(Activity a) {}

        public void onActivityStopped(Activity a) {
          releaseActivity(a, "recents stopped");
        }

        public void onActivitySaveInstanceState(Activity a, Bundle b) {}

        public void onActivityDestroyed(Activity a) {
          releaseActivity(a, "recents destroyed");
        }
      };
  private String diagnostic = "unavailable";
  private String error;
  private Runnable stateChanged = () -> {};

  void setStateListener(Runnable callback) {
    stateChanged = callback;
  }

  private void setDiagnostic(String value) {
    String next = error == null ? value : "error:" + error;
    if (!next.equals(diagnostic)) {
      diagnostic = next;
      stateChanged.run();
    }
  }

  private final BroadcastReceiver screenOff =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
          if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) release("screen off");
        }
      };

  private static final class Session {
    final Object manager, gesture;
    final boolean originalHold;
    boolean held;
    int taskId;
    View boundRoot;

    Session(Object m, Object g, boolean original) {
      manager = m;
      gesture = g;
      originalHold = original;
    }

    Object manager() {
      return manager;
    }

    Object gesture() {
      return gesture;
    }

    boolean originalHold() {
      return originalHold;
    }
  }

  void install(Context app, String apk, ClassLoader loader) throws Exception {
    if (android.os.Build.VERSION.SDK_INT != 36)
      throw new IllegalStateException("Live tile requires renewed OS validation");
    Class<?> manager =
        type(loader, "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager");
    gestureType = type(loader, "com.honeyspace.gesture.session.AnimationSession");
    callbacks = field(manager, "onStartCallbacks", List.class);
    action =
        field(
            gestureType,
            "recentsAnimationAction",
            type(loader, "com.honeyspace.gesture.recentsanimation.RecentsAnimationAction"));
    managerDisplay = field(manager, "displayId", int.class);
    gestureDisplay = field(gestureType, "displayId", int.class);
    Class<?> top = type(loader, "com.honeyspace.gesture.usecase.TopTaskUseCase");
    topSource = field(manager, "topTaskUseCase", top);
    topTask = method(top, "getTopTask", ActivityManager.RunningTaskInfo.class);
    Class<?> window = type(loader, "android.app.WindowConfiguration");
    taskConfig =
        method(
            type(loader, "com.honeyspace.common.reflection.TaskInfoReflectionKt"),
            "getWindowConfiguration",
            window,
            type(loader, "android.app.TaskInfo"));
    windowMode = method(window, "getWindowingMode", int.class);
    activityType = method(window, "getActivityType", int.class);
    desktop = method(manager, "isInDexDisplay", boolean.class);
    gestureDesktop = method(gestureType, "isInDexDisplay", boolean.class);
    nativeHold = field(gestureType, "dontFinishByCommand", boolean.class);
    willGo = field(gestureType, "willGoToRecents", boolean.class);
    setHold = method(gestureType, "setDontFinishByCommand", void.class, boolean.class);
    finish = method(manager, "finish", void.class);
    Class<?> controllerType = type(loader, "com.honeyspace.sdk.HoneySystemController");
    systemController = method(manager, "getSystemController", controllerType);
    recentTarget = controllerType.getMethod("getCurrentRecentTarget");
    if (recentTarget.getReturnType() != View.class)
      throw new IllegalStateException("Recent target changed");
    Class<?> proxy = type(loader, "com.honeyspace.gesture.inputconsumer.InputConsumerProxy");
    inputProxy = field(manager, "inputConsumerProxy", proxy);
    proxyRecents = field(proxy, "sendRecents", boolean.class);
    proxyHome = field(proxy, "sendLauncher", boolean.class);
    proxySize = field(proxy, "displaySize", Point.class);
    appPlayer =
        field(manager, "appPlayer", type(loader, "com.honeyspace.transition.anim.floating.Player"));
    taskViewType = type(loader, "com.honeyspace.ui.honeypots.tasklist.presentation.TaskView");
    taskId = taskViewType.getMethod("getFirstTaskId");
    taskScene = taskViewType.getMethod("getTaskSceneView");
    Method dispatch =
        taskViewType
            .getMethod("getRecentsView")
            .getReturnType()
            .getDeclaredMethod("dispatchTouchEvent", android.view.MotionEvent.class);
    dispatch.setAccessible(true);
    Method begin =
        method(
            manager,
            "start",
            void.class,
            int.class,
            Point.class,
            boolean.class,
            boolean.class,
            boolean.class);
    Method decision = method(manager, "needRecentsActivityCall", boolean.class, boolean.class);
    Method request = method(gestureType, "requestFinishRecentAnimation", void.class);
    Method invalidate = method(manager, "invalidate", void.class);
    Class<?> shell = type(loader, "com.honeyspace.transition.ShellTransitionManager");
    Class<?> launchInfo = type(loader, "com.honeyspace.sdk.transition.ShellTransition$Info");
    Method launch =
        method(
            shell,
            "getActivityLaunchOptions",
            type(loader, "com.honeyspace.transition.utils.ActivityOptionsWrapper"),
            launchInfo,
            type(loader, "com.honeyspace.transition.ShellAnimationRunner"));
    Method launchView = launchInfo.getMethod("getTargetView");
    Class<?> recents = type(loader, "com.android.quickstep.RecentsActivity");
    recentsType = recents;
    DexEvidence.deoptimizeRecentsCoordinateCalls(
        apk, loader, List.of(begin, decision, request, invalidate, setHold, finish, launch));
    try {
      surface = new RecentsLiveSurface(loader, () -> release("unsupported live surface"));
      hook(begin, false, this::begin);
      hooks.add(
          ModernHook.hookMethod(
              dispatch,
              new ModernHook.Callback() {
                @Override
                protected void beforeHookedMethod(ModernHook.Param p) {
                  if (!enabled || failed || Looper.myLooper() != Looper.getMainLooper()) return;
                  View v = (View) p.thisObject;
                  for (Session ss : new ArrayList<>(sessions.values()))
                    if (ss.held && v.getRootView() == ss.boundRoot) {
                      try {
                        android.view.MotionEvent original = (android.view.MotionEvent) p.args[0];
                        android.view.MotionEvent copy = RecentsLocalMotion.rebase(v, original);
                        if (copy != null) {
                          p.setObjectExtra("liveRawEvent", copy);
                          p.args[0] = copy;
                          if (copy.getActionMasked() == android.view.MotionEvent.ACTION_DOWN)
                            HookEntry.log(
                                "RECENTS_LIVE_INPUT_REBASED local="
                                    + copy.getX()
                                    + ","
                                    + copy.getY()
                                    + " raw="
                                    + copy.getRawX()
                                    + ","
                                    + copy.getRawY());
                        }
                      } catch (Throwable error) {
                        HookEntry.log("RECENTS_LIVE_INPUT_ERROR " + error);
                        release("input axes changed");
                      }
                      break;
                    }
                }

                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  Object copy = p.getObjectExtra("liveRawEvent");
                  if (copy instanceof android.view.MotionEvent e) {
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                      View view = (View) p.thisObject;
                      for (Session ss : new ArrayList<>(sessions.values()))
                        if (ss.held && view.getRootView() == ss.boundRoot)
                          view.postDelayed(
                              () -> {
                                if (sessions.get(ss.manager()) == ss)
                                  try {
                                    inspect(ss);
                                  } catch (Throwable error) {
                                    HookEntry.log("RECENTS_LIVE_INSPECT_ERROR " + error);
                                  }
                              },
                              750);
                    }
                    e.recycle();
                  }
                }
              }));
      hook(
          decision,
          true,
          p -> {
            if (sessions.containsKey(p.thisObject) && !Boolean.TRUE.equals(p.args[0]))
              p.setResult(true);
          });
      hook(
          request,
          true,
          p -> {
            Session s = gestures.get(p.thisObject);
            if (s != null && willGo.getBoolean(s.gesture()) && nativeHold.getBoolean(s.gesture())) {
              if (s.held) return;
              setDiagnostic("held");
              s.held = true;
              s.boundRoot = (View) recentTarget.invoke(systemController.invoke(s.manager()));
              HookEntry.log("RECENTS_LIVE_HELD native controller retained");
              if (RecentsHandoffGate.SHARED.await(
                  s.manager(),
                  success -> {
                    if (sessions.get(s.manager()) != s || !enabled) return;
                    if (!success) {
                      release("viewport handoff unavailable");
                      return;
                    }
                    try {
                      activateSurface(s);
                    } catch (Throwable error) {
                      HookEntry.log("RECENTS_LIVE_HANDOFF_ERROR " + error);
                      release("live handoff failed");
                    }
                  })) HookEntry.log("RECENTS_LIVE_WAIT_VIEWPORT owned handoff pending");
              else activateSurface(s);
            }
          });
      hook(
          launch,
          false,
          p -> {
            View target = (View) launchView.invoke(p.args[0]);
            if (target == null) return;
            for (Session s : new ArrayList<>(sessions.values())) {
              View root = (View) recentTarget.invoke(systemController.invoke(s.manager()));
              if (s.held && root != null && target.getRootView() == root) {
                release("recents task launch");
                break;
              }
            }
          });
      hook(invalidate, true, p -> end(sessions.remove(p.thisObject)));
      // initialize() can run before ActivityThread publishes mInitialApplication. The supplied
      // Application context is already attached; getApplicationContext() can still return null.
      context = app;
      if (!(app instanceof Application a))
        throw new IllegalStateException("Attached Application required");
      application = a;
      application.registerActivityLifecycleCallbacks(lifecycle);
      context.registerReceiver(
          screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
      setDiagnostic("off");
      HookEntry.log("RECENTS_LIVE_READY prototype configurable live tile v18");
    } catch (Throwable t) {
      uninstall();
      if (t instanceof Exception e) throw e;
      throw new IllegalStateException(t);
    }
  }

  private void activateSurface(Session s) throws Exception {
    inspect(s);
    View root = (View) recentTarget.invoke(systemController.invoke(s.manager()));
    if (!surface.bind(s.manager(), root, s.taskId)) release("unsupported live binding");
    else s.boundRoot = root;
  }

  private void releaseActivity(Activity a, String reason) {
    if (a.getWindow() == null) return;
    View root = a.getWindow().getDecorView();
    for (Session s : new ArrayList<>(sessions.values()))
      if (s.held && s.boundRoot == root) {
        release(reason);
        return;
      }
  }

  private void begin(ModernHook.Param p) throws Exception {
    if (Boolean.TRUE.equals(p.args[2])
        || Boolean.TRUE.equals(p.args[3])
        || Boolean.TRUE.equals(p.args[4])
        || managerDisplay.getInt(p.thisObject) != 0
        || Boolean.TRUE.equals(desktop.invoke(p.thisObject))
        || sessions.containsKey(p.thisObject)) return;
    Object task = topTask.invoke(topSource.get(p.thisObject));
    if (task == null
        || task.getClass().getField("taskId").getInt(task) != (Integer) p.args[0]
        || task.getClass().getField("displayId").getInt(task) != 0
        || task.getClass().getField("userId").getInt(task) != android.os.Process.myUid() / 100000)
      return;
    Object config = taskConfig.invoke(null, task);
    if (config == null
        || (Integer) windowMode.invoke(config) != 1
        || (Integer) activityType.invoke(config) != 1) return;
    Object owner = null;
    for (Object cb : (List<?>) callbacks.get(p.thisObject))
      for (Field f : cb.getClass().getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
        f.setAccessible(true);
        Object value = f.get(cb);
        if (gestureType.isInstance(value) && action.get(value) == p.thisObject) {
          if (owner != null && owner != value)
            throw new IllegalStateException("Ambiguous live gesture owner");
          owner = value;
        }
      }
    if (owner == null
        || gestureDisplay.getInt(owner) != 0
        || Boolean.TRUE.equals(gestureDesktop.invoke(owner))) return;
    Session s = new Session(p.thisObject, owner, nativeHold.getBoolean(owner));
    s.taskId = (Integer) p.args[0];
    sessions.put(p.thisObject, s);
    gestures.put(owner, s);
    setHold.invoke(owner, true);
    setDiagnostic("gesture");
    HookEntry.log("RECENTS_LIVE_BEGIN native transient recents");
  }

  private void inspect(Session s) throws Exception {
    Object proxy = inputProxy.get(s.manager());
    View root = (View) recentTarget.invoke(systemController.invoke(s.manager()));
    HookEntry.log(
        "RECENTS_LIVE_STATE recentsInput="
            + proxyRecents.getBoolean(proxy)
            + " homeInput="
            + proxyHome.getBoolean(proxy)
            + " inputSize="
            + proxySize.get(proxy)
            + " player="
            + (appPlayer.get(s.manager()) != null)
            + " root="
            + (root == null ? "null" : root.getWidth() + "x" + root.getHeight()));
    if (root != null) inspectCards(root, 0);
  }

  private void inspectCards(View v, int depth) throws Exception {
    if (depth > 32) return;
    if (taskViewType.isInstance(v)) {
      View scene = (View) taskScene.invoke(v);
      int[] xy = new int[2];
      if (scene != null) scene.getLocationOnScreen(xy);
      String metadata = "";
      if (scene != null) {
        List<?> data = (List<?>) scene.getClass().getMethod("getTaskSceneData").invoke(scene);
        for (Object datum : data) {
          Object bitmap = datum.getClass().getMethod("getThumbnail").invoke(datum);
          metadata +=
              " snapshot="
                  + (bitmap instanceof android.graphics.Bitmap image
                      ? image.getWidth() + "x" + image.getHeight()
                      : "none")
                  + " rotation="
                  + datum.getClass().getMethod("getRotation").invoke(datum)
                  + " real="
                  + datum.getClass().getMethod("isRealSnapshot").invoke(datum)
                  + " locked="
                  + datum.getClass().getMethod("getAppLocked").invoke(datum);
        }
        metadata +=
            " contextRotation="
                + scene.getContext().getDisplay().getRotation()
                + " rootRotation="
                + scene.getRootView().getDisplay().getRotation();
      }
      HookEntry.log(
          "RECENTS_LIVE_CARD id="
              + taskId.invoke(v)
              + " visible="
              + v.getVisibility()
              + " alpha="
              + v.getAlpha()
              + " scene="
              + (scene == null
                  ? "null"
                  : xy[0] + "," + xy[1] + " " + scene.getWidth() + "x" + scene.getHeight())
              + metadata);
      return;
    }
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) inspectCards(g.getChildAt(i), depth + 1);
  }

  private void end(Session s) throws Exception {
    if (s == null) return;
    if (surface != null) surface.clear();
    gestures.remove(s.gesture());
    setHold.invoke(s.gesture(), s.originalHold());
    setDiagnostic(enabled ? "ready" : "off");
    HookEntry.log("RECENTS_LIVE_END native invalidation");
  }

  private void release(String reason) {
    if (releasing) return;
    if (sessions.isEmpty()) {
      setDiagnostic(enabled ? "ready" : "off");
      return;
    }
    releasing = true;
    if (surface != null) surface.clear();
    try {
      for (Session s : new ArrayList<>(sessions.values()))
        try {
          setHold.invoke(s.gesture(), s.originalHold());
          finish.invoke(s.manager());
        } catch (Throwable t) {
          HookEntry.log("RECENTS_LIVE_RELEASE_ERROR " + t);
        }
      sessions.clear();
      gestures.clear();
      setDiagnostic(enabled ? "ready" : "off");
      HookEntry.log("RECENTS_LIVE_RELEASE " + reason);
    } finally {
      releasing = false;
    }
  }

  void setEnabled(boolean value) {
    if (hooks.isEmpty()) {
      enabled = false;
      setDiagnostic("unavailable");
      return;
    }
    enabled = value && !failed;
    if (!enabled) release("disabled");
    else if (sessions.isEmpty()) setDiagnostic("ready");
  }

  String diagnostic() {
    return diagnostic;
  }

  void uninstall() {
    enabled = false;
    release("uninstall");
    for (var h : hooks)
      try {
        h.unhook();
      } catch (Throwable ignored) {
      }
    hooks.clear();
    if (surface != null) {
      surface.uninstall();
      surface = null;
    }
    if (context != null)
      try {
        context.unregisterReceiver(screenOff);
      } catch (Throwable ignored) {
      }
    context = null;
    if (application != null) {
      application.unregisterActivityLifecycleCallbacks(lifecycle);
      application = null;
    }
  }

  private interface Action {
    void run(ModernHook.Param p) throws Exception;
  }

  private void hook(Method method, boolean after, Action action) {
    hooks.add(
        ModernHook.hookMethod(
            method,
            new ModernHook.Callback() {
              private void run(ModernHook.Param p) {
                if (!enabled
                    || failed
                    || p.hasThrowable()
                    || Looper.myLooper() != Looper.getMainLooper()) return;
                try {
                  action.run(p);
                } catch (Throwable t) {
                  failed = true;
                  error = t.getClass().getSimpleName();
                  uninstall();
                  setDiagnostic("error:" + t.getClass().getSimpleName());
                  HookEntry.log("RECENTS_LIVE_DISABLED " + t);
                }
              }

              @Override
              protected void beforeHookedMethod(ModernHook.Param p) {
                if (!after) run(p);
              }

              @Override
              protected void afterHookedMethod(ModernHook.Param p) {
                if (after) run(p);
              }
            }));
  }

  private static Class<?> type(ClassLoader l, String n) throws Exception {
    return Class.forName(n, false, l);
  }

  private static Field field(Class<?> c, String n, Class<?> t) throws Exception {
    Field f = c.getDeclaredField(n);
    if (f.getType() != t) throw new IllegalStateException("Live field changed: " + n);
    f.setAccessible(true);
    return f;
  }

  private static Method method(Class<?> c, String n, Class<?> t, Class<?>... p) throws Exception {
    Method m = c.getDeclaredMethod(n, p);
    if (m.getReturnType() != t) throw new IllegalStateException("Live method changed: " + n);
    m.setAccessible(true);
    return m;
  }
}
