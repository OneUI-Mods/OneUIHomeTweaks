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
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Owns Samsung's native live-tile lifetime without suppressing app lifecycle methods. */
final class RecentsLiveTileHooks {
  private final RecentsFocusClient focus = new RecentsFocusClient();
  private final Map<Object, RecentsFocusClient.Lease> focusOwners = new WeakHashMap<>();
  private int focusMode;

  void connectFocus(Context context) {
    focus.connect(context);
  }

  String focusDiagnostic() {
    return focus.diagnostic();
  }

  void setFocusMode(int mode) {
    int next = RecentsFocusPolicy.mode(mode);
    if (focusMode != next) {
      for (RecentsFocusClient.Lease owner : new ArrayList<>(focusOwners.values())) focus.end(owner);
      focusOwners.clear();
      focusMode = next;
    }
  }

  private final Map<Object, Session> sessions = new WeakHashMap<>();
  private final Map<Object, Session> gestures = new WeakHashMap<>();
  private final List<ModernHook.Handle> hooks = new ArrayList<>();
  private Field callbacks, action, managerDisplay, gestureDisplay, topSource;
  private Field nativeHold, willGo;
  private Method desktop, gestureDesktop, topTask, taskConfig, windowMode, activityType;
  private Method setHold, finish, systemController, recentTarget, taskId, taskScene;
  private Method handlerDisplay, handlerKeyInjector, injectKey;
  private Object mainKeyInjector;
  private Class<?> handlerType;
  private Field inputProxy, proxyRecents, proxyHome, proxySize, appPlayer;
  private Class<?> taskViewType;
  private RecentsLiveSurface surface;
  private Class<?> gestureType;
  private Context context;
  private volatile boolean enabled, failed;
  private boolean releasing;
  private Application application;
  private Class<?> recentsType;
  private Activity foregroundRecents;
  private final Application.ActivityLifecycleCallbacks lifecycle =
      new Application.ActivityLifecycleCallbacks() {
        public void onActivityCreated(Activity a, Bundle b) {}

        public void onActivityStarted(Activity a) {}

        public void onActivityResumed(Activity a) {
          if (recentsType != null && recentsType.isInstance(a)) foregroundRecents = a;
          if (recentsType != null
              && !recentsType.isInstance(a)
              && sessions.values().stream().anyMatch(s -> s.held))
            release("other activity resumed");
        }

        public void onActivityPaused(Activity a) {
          releaseActivity(a, "recents paused");
          if (foregroundRecents == a) foregroundRecents = null;
        }

        public void onActivityStopped(Activity a) {
          releaseActivity(a, "recents stopped");
        }

        public void onActivitySaveInstanceState(Activity a, Bundle b) {}

        public void onActivityDestroyed(Activity a) {
          releaseActivity(a, "recents destroyed");
          if (foregroundRecents == a) foregroundRecents = null;
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
    long heldAt;
    RecentsNavigationGesture navigation;
    Object navigationInjector;
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
    if (!HookCompatibility.isCurrentSupported())
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
    HookCompatibility.RecentsCalls recentsCalls =
        HookCompatibility.recentsCalls(android.os.Build.VERSION.SDK_INT);
    topTask =
        method(
            top,
            recentsCalls.topTaskMethod(),
            ActivityManager.RunningTaskInfo.class,
            recentsCalls.topTaskTakesDisplay() ? new Class<?>[] {int.class} : new Class<?>[0]);
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
    handlerType = type(loader, "com.honeyspace.gesture.GestureInputHandler");
    handlerDisplay = method(handlerType, "getDisplayId", int.class);
    Method nativeInput =
        method(handlerType, "inputHandle", void.class, int.class, android.view.MotionEvent.class);
    Class<?> injectorType = type(loader, "com.honeyspace.gesture.keyinject.KeyInjector");
    handlerKeyInjector = method(handlerType, "getKeyInjector", injectorType);
    injectKey = method(injectorType, "injectKey", void.class, int.class, int.class, int.class);
    Method input = method(proxy, "sendToInputProxy", boolean.class, InputEvent.class);
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
    Method request =
        method(
            gestureType,
            "requestFinishRecentAnimation",
            void.class,
            recentsCalls.finishTakesToHome() ? new Class<?>[] {boolean.class} : new Class<?>[0]);
    Method finishController =
        method(manager, "finishController", void.class, boolean.class, boolean.class);
    Method gestureLaunch =
        method(
            type(loader, "com.honeyspace.gesture.utils.LaunchTaskHelper"),
            "launchTask",
            List.class,
            type(loader, "com.honeyspace.sdk.transition.ShellTransition$TaskInfo"));
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
    Method launchRequest =
        method(
            shell,
            "getLaunchOptions",
            type(loader, "com.honeyspace.transition.utils.ActivityOptionsWrapper"),
            launchInfo,
            boolean.class,
            boolean.class,
            type(loader, "kotlin.jvm.functions.Function0"));
    Class<?> recents = type(loader, "com.android.quickstep.RecentsActivity");
    Method back = recents.getMethod("onBackPressed");
    if (back.getReturnType() != void.class)
      throw new IllegalStateException("Back contract changed");
    recentsType = recents;
    Class<?> taskMessages =
        type(loader, "com.android.systemui.shared.system.TaskStackChangeListeners$Impl");
    Method taskMessage =
        method(taskMessages, "handleMessage", boolean.class, android.os.Message.class);
    int movedToFront = field(taskMessages, "ON_TASK_MOVED_TO_FRONT", int.class).getInt(null);
    Field movedDisplay = ActivityManager.RunningTaskInfo.class.getField("displayId");
    DexEvidence.deoptimizeRecentsLiveCalls(
        apk,
        loader,
        List.of(
            begin,
            decision,
            request,
            invalidate,
            setHold,
            finish,
            finishController,
            gestureLaunch,
            launch,
            launchRequest,
            input,
            taskMessage,
            nativeInput));
    try {
      surface = new RecentsLiveSurface(loader, () -> fallback("unsupported live surface"));
      hook(
          nativeInput,
          false,
          p -> {
            if ((Integer) p.args[0] == 0
                && ((android.view.MotionEvent) p.args[1]).getActionMasked()
                    == android.view.MotionEvent.ACTION_DOWN
                && (Integer) handlerDisplay.invoke(p.thisObject) == 0)
              mainKeyInjector = handlerKeyInjector.invoke(p.thisObject);
          });
      hook(
          taskMessage,
          false,
          p -> {
            android.os.Message message = (android.os.Message) p.args[0];
            if (message.what != movedToFront
                || !(message.obj instanceof ActivityManager.RunningTaskInfo task)
                || movedDisplay.getInt(task) != 0) return;
            for (Session s : new ArrayList<>(sessions.values())) {
              Activity activity = foregroundRecents;
              if (s.held
                  && message.getWhen() >= s.heldAt
                  && activity != null
                  && task.taskId != activity.getTaskId()) {
                release("foreground task changed");
                break;
              }
            }
          });
      hook(
          input,
          false,
          p -> {
            if (!(p.args[0] instanceof KeyEvent key) || key.getKeyCode() != KeyEvent.KEYCODE_BACK)
              return;
            for (Session s : new ArrayList<>(sessions.values())) {
              Activity activity = foregroundRecents;
              if (!s.held
                  || inputProxy.get(s.manager()) != p.thisObject
                  || activity == null
                  || activity.getWindow().getDecorView() != s.boundRoot) continue;
              p.setResult(true);
              if (key.getAction() == KeyEvent.ACTION_UP && !key.isCanceled()) {
                s.boundRoot.post(
                    () -> {
                      if (sessions.get(s.manager()) != s || foregroundRecents != activity) return;
                      release("back key");
                      try {
                        back.invoke(activity);
                      } catch (Throwable error) {
                        HookEntry.log("RECENTS_LIVE_BACK_ERROR " + error);
                      }
                    });
              }
              break;
            }
          });
      hook(begin, false, this::begin);
      hooks.add(
          ModernHook.hookMethod(
              begin,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (p.hasThrowable()) focus.end(focusOwners.remove(p.thisObject));
                }
              }));
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
                        var navigation = navigation(ss, v, copy == null ? original : copy);
                        if (navigation.consume()) p.setResult(true);
                        if (navigation.command() != 0) {
                          if (!navigation.consume()) {
                            if (copy == null) copy = android.view.MotionEvent.obtain(original);
                            copy.setAction(android.view.MotionEvent.ACTION_CANCEL);
                          }
                          p.setObjectExtra("liveNavigationHome", ss);
                          p.setObjectExtra("liveNavigationKey", navigation.command());
                        }
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
                  if (p.getObjectExtra("liveNavigationHome") instanceof Session s
                      && sessions.get(s.manager()) == s
                      && s.held) {
                    s.boundRoot.post(
                        () -> {
                          if (sessions.get(s.manager()) != s || !s.held) return;
                          Object injector = s.navigationInjector;
                          int key = (Integer) p.getObjectExtra("liveNavigationKey");
                          Activity activity = foregroundRecents;
                          release(
                              key == KeyEvent.KEYCODE_HOME
                                  ? "native navigation home"
                                  : "native navigation back");
                          try {
                            if (key == KeyEvent.KEYCODE_BACK && activity != null)
                              back.invoke(activity);
                            else
                              injectKey.invoke(
                                  injector, key, android.os.Process.myUid() / 100000, 0);
                          } catch (Throwable error) {
                            HookEntry.log("RECENTS_LIVE_NAVIGATION_ERROR " + error);
                          }
                        });
                  }
                  Object copy = p.getObjectExtra("liveRawEvent");
                  if (copy instanceof android.view.MotionEvent e) {
                    if (BuildConfig.RECENTS_LIVE_TRACE
                        && e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
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
      // Return/cancel decisions must restore focus policy before native finish returns focus.
      hook(
          request,
          false,
          p -> {
            Session s = gestures.get(p.thisObject);
            if (s != null && !willGo.getBoolean(s.gesture()))
              focus.end(focusOwners.remove(s.manager()));
          });
      hook(finish, false, p -> focus.end(focusOwners.remove(p.thisObject)));
      // Native onGestureEnd/onSettledOnTarget may bypass both public finish entry points.
      // This shared gate runs before finishGestureTransition can return focus to the app.
      hook(finishController, false, p -> focus.end(focusOwners.remove(p.thisObject)));
      // Home Up's gesture page can relaunch the task before any animation-finish callback.
      // Restore only our focus leases before its coroutine dispatches the native task launch.
      hook(
          gestureLaunch,
          false,
          p -> {
            for (RecentsFocusClient.Lease owner : new ArrayList<>(focusOwners.values()))
              focus.end(owner);
            focusOwners.clear();
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
              s.heldAt = android.os.SystemClock.uptimeMillis();
              s.boundRoot = (View) recentTarget.invoke(systemController.invoke(s.manager()));
              HookEntry.log("RECENTS_LIVE_HELD native controller retained");
              activateSurface(s);
            }
          });
      // Suggested-app icons enter getLaunchOptions before getActivityLaunchOptions. Its native
      // transitionRunning gate otherwise waits for the very controller we retained, so the old
      // later hook never executes and multiple taps queue launches until another exit releases it.
      hook(
          launchRequest, false, p -> releaseForLaunch(launchView, p.args[0], "recents app launch"));
      // Task cards also have direct getActivityLaunchOptions callers; retain their completion path.
      hook(launch, false, p -> releaseForLaunch(launchView, p.args[0], "recents task launch"));
      // Restore native focus retention before invalidate can return focus to the source Activity.
      // Restoring afterwards misses DecorView.mStayFocus initialization on a cancelled gesture.
      hook(invalidate, false, p -> focus.end(focusOwners.remove(p.thisObject)));
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
      HookEntry.log("RECENTS_LIVE_READY native orientation lifecycle 0.11");
    } catch (Throwable t) {
      uninstall();
      if (t instanceof Exception e) throw e;
      throw new IllegalStateException(t);
    }
  }

  private void releaseForLaunch(Method targetView, Object info, String reason) throws Exception {
    View target = (View) targetView.invoke(info);
    if (target == null) return;
    View root = target.getRootView();
    for (Session s : new ArrayList<>(sessions.values()))
      if (s.held && s.boundRoot != null && root == s.boundRoot) {
        release(reason);
        return;
      }
  }

  private void activateSurface(Session s) throws Exception {
    if (BuildConfig.RECENTS_LIVE_TRACE) inspect(s);
    View root = (View) recentTarget.invoke(systemController.invoke(s.manager()));
    if (root != null) {
      int bottom = navigationBottom(root);
      if (bottom <= 0 || bottom >= root.getHeight() * .1f) {
        fallback("native navigation region unavailable");
        return;
      }
      WindowInsets windowInsets = root.getRootWindowInsets();
      android.graphics.Insets edges =
          windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures());
      HookEntry.log("RECENTS_LIVE_NAVIGATION_REGIONS " + edges + " bottom=" + bottom);
      try {
        s.navigation =
            new RecentsNavigationGesture(
                root.getWidth(),
                root.getHeight(),
                edges.left,
                edges.right,
                bottom,
                ViewConfiguration.get(root.getContext()).getScaledTouchSlop());
      } catch (IllegalArgumentException unsupported) {
        fallback("native back region unavailable");
        return;
      }
      s.navigationInjector = mainKeyInjector;
      if (s.navigationInjector == null) {
        fallback("native home navigation unavailable");
        return;
      }
    }
    if (!surface.bind(s.manager(), root, s.taskId)) fallback("unsupported live binding");
    else s.boundRoot = root;
  }

  private static int navigationBottom(View root) {
    WindowInsets insets = root.getRootWindowInsets();
    return insets == null
        ? 0
        : insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.navigationBars() | WindowInsets.Type.mandatorySystemGestures())
            .bottom;
  }

  private RecentsNavigationGesture.Decision navigation(
      Session s, View view, android.view.MotionEvent event) throws Exception {
    if (s.navigation == null) return RecentsNavigationGesture.NONE;
    float[] xy = {event.getX(), event.getY()};
    android.graphics.Matrix matrix = new android.graphics.Matrix();
    view.transformMatrixToGlobal(matrix);
    matrix.mapPoints(xy);
    return s.navigation.event(
        event.getDownTime(), event.getActionMasked(), event.getPointerCount(), xy[0], xy[1]);
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
    if (!NavigationPolicy.gestures(context)
        || Boolean.TRUE.equals(p.args[2])
        || Boolean.TRUE.equals(p.args[3])
        || Boolean.TRUE.equals(p.args[4])
        || managerDisplay.getInt(p.thisObject) != 0
        || Boolean.TRUE.equals(desktop.invoke(p.thisObject))
        || sessions.containsKey(p.thisObject)) return;
    Object source = topSource.get(p.thisObject);
    Object task =
        topTask.getParameterCount() == 0
            ? topTask.invoke(source)
            : topTask.invoke(source, managerDisplay.getInt(p.thisObject));
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
    if (focusMode != 0) {
      RecentsFocusClient.Lease ownerToken = focus.begin(s.taskId, focusMode);
      if (ownerToken != null) focusOwners.put(p.thisObject, ownerToken);
    }
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
    focus.end(focusOwners.remove(s.manager()));
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
    for (RecentsFocusClient.Lease owner : new ArrayList<>(focusOwners.values())) focus.end(owner);
    focusOwners.clear();
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

  private void fallback(String reason) {
    release(reason);
    setDiagnostic("unavailable:" + reason);
  }

  void setEnabled(boolean value) {
    if (hooks.isEmpty()) {
      enabled = false;
      setDiagnostic("unavailable");
      return;
    }
    boolean next = value && !failed;
    if (next == enabled) return;
    enabled = next;
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
    foregroundRecents = null;
    mainKeyInjector = null;
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
