package net.ryuya_dev.oneuilivecolors;

import android.app.ActivityManager;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Common-viewport controller using the native portrait card as the motion target. */
final class RecentsViewportHooks {
  private final Consumer<Throwable> failure;
  private final RecentsViewportInsets insets = new RecentsViewportInsets();
  private final List<ModernHook.Handle> hooks = new ArrayList<>();
  private final Map<Object, Session> sessions = new WeakHashMap<>();
  private Field callbacks,
      action,
      managerDisplay,
      gestureDisplay,
      topSource,
      interaction,
      overlay,
      overlayRoot;
  private Method managerDesktop, gestureDesktop, topTask, taskConfig, windowMode, activityType;
  private Method controller,
      recentRoot,
      taskId,
      taskScene,
      hasTaskId,
      paints,
      data,
      thumbnail,
      snapshotScale,
      realSnapshot,
      lockedSnapshot;
  private Method targetMode, apps, snapshotRotation, basisMethod, translucent;
  private Method infoMatrix, infoCrop, infoCorner, infoAlpha, setHold, finishRequest, nativeHandoff;
  private Field playerField, targetView, holdField;
  private ClassLoader loader;
  private boolean bypassHandoff;
  private Class<?> gestureType, taskType, ghostListType;
  private boolean enabled, failed;

  private static final class Session {
    final Object manager, gesture;
    final RecentsHandoffGate.Ticket handoff;
    final ViewGroup overlayRoot;
    final int taskId;
    final long start = SystemClock.uptimeMillis();
    Rect source;
    View recentRoot, scene;
    RenderNode node;
    boolean entered, ready, holdTaken, originalHold, requestSeen;
    Object player, targets, currentInfo;
    SurfaceControl leash;
    Matrix basis, nativeMatrix, entryMatrix;
    Rect nativeCrop, entryCrop;
    float nativeCorner, entryCorner;
    long entryDeadline;
    RecentsCardSpace.Space sourceSpace;
    RecentsViewportMotion motion;
    long lastProbe;
    int pendingLogs;

    Session(Object manager, Object gesture, ViewGroup root, int taskId) {
      this.manager = manager;
      this.gesture = gesture;
      handoff = RecentsHandoffGate.SHARED.start(manager);
      overlayRoot = root;
      this.taskId = taskId;
    }

    void clear() {
      if (motion != null) motion.cancel();
      motion = null;
      if (node != null) node.discardDisplayList();
      node = null;
      scene = null;
      recentRoot = null;
    }
  }

  RecentsViewportHooks(Consumer<Throwable> failure) {
    this.failure = failure;
  }

  void install(String apk, ClassLoader loader) throws Exception {
    this.loader = loader;
    if (android.os.Build.VERSION.SDK_INT != 36)
      throw new IllegalStateException("Viewport controller requires renewed OS validation");
    Class<?> manager =
        type(loader, "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager");
    gestureType = type(loader, "com.honeyspace.gesture.session.AnimationSession");
    callbacks = field(manager, "onStartCallbacks");
    action = field(gestureType, "recentsAnimationAction");
    managerDisplay = field(manager, "displayId");
    gestureDisplay = field(gestureType, "displayId");
    topSource = field(manager, "topTaskUseCase");
    topTask = topSource.getType().getDeclaredMethod("getTopTask");
    topTask.setAccessible(true);
    taskConfig =
        type(loader, "com.honeyspace.common.reflection.TaskInfoReflectionKt")
            .getDeclaredMethod("getWindowConfiguration", type(loader, "android.app.TaskInfo"));
    taskConfig.setAccessible(true);
    Class<?> config = taskConfig.getReturnType();
    windowMode = config.getMethod("getWindowingMode");
    activityType = config.getMethod("getActivityType");
    managerDesktop = method(manager, "isInDexDisplay");
    gestureDesktop = method(gestureType, "isInDexDisplay");
    interaction = field(gestureType, "taskViewInteraction");
    overlay = field(interaction.getType(), "leashOverlayWindow");
    overlayRoot = field(overlay.getType(), "rootView");
    controller = method(manager, "getSystemController");
    recentRoot = controller.getReturnType().getMethod("getCurrentRecentTarget");
    taskType = type(loader, "com.honeyspace.ui.honeypots.tasklist.presentation.TaskView");
    taskId = taskType.getMethod("getFirstTaskId");
    taskScene = taskType.getMethod("getTaskSceneView");
    Class<?> sceneType = type(loader, "com.honeyspace.ui.common.taskScene.TaskSceneView");
    hasTaskId = sceneType.getMethod("hasTaskId", int.class);
    paints = sceneType.getMethod("getForegroundPaints");
    data = sceneType.getMethod("getTaskSceneData");
    Class<?> sceneData = type(loader, "com.honeyspace.ui.common.taskScene.TaskSceneData");
    thumbnail = sceneData.getMethod("getThumbnail");
    snapshotScale = sceneData.getMethod("getScale");
    realSnapshot = sceneData.getMethod("isRealSnapshot");
    lockedSnapshot = sceneData.getMethod("getAppLocked");
    snapshotRotation = sceneData.getMethod("getRotation");
    Class<?> targets = type(loader, "com.honeyspace.transition.remote.RemoteAnimationTargets");
    targetMode = targets.getMethod("getTargetMode");
    apps = targets.getMethod("getApps");
    Method begin =
        method(
            manager, "start", int.class, Point.class, boolean.class, boolean.class, boolean.class);
    Method decision = method(manager, "needRecentsActivityCall", boolean.class);
    Method create =
        method(
            manager,
            "createFloatingPlayer",
            targets,
            type(loader, "android.window.TransitionInfo"));
    Method enter =
        method(
            manager,
            "startRecentsEnterAnimation",
            type(loader, "com.honeyspace.sdk.source.entity.TaskViewInfo"),
            int.class);
    Method handoff = method(manager, "showRecentsWhenEndRecentsEntering"),
        invalidate = method(manager, "invalidate");
    Class<?> ghostType = type(loader, "com.honeyspace.ui.common.taskScene.GestureTaskSceneView");
    Method draw = method(ghostType, "onDraw", Canvas.class);
    ghostListType = type(loader, "com.honeyspace.gesture.presentation.GestureTaskListView");
    Method dispatchDraw = ViewGroup.class.getDeclaredMethod("dispatchDraw", Canvas.class);
    dispatchDraw.setAccessible(true);
    playerField = field(manager, "appPlayer");
    targetView = field(manager, "targetView");
    Class<?> playerType = type(loader, "com.honeyspace.transition.anim.floating.PlayerImpl");
    basisMethod = playerType.getMethod("getHomeToWindowMatrix");
    translucent = playerType.getMethod("isTranslucent");
    Class<?> txType = type(loader, "com.honeyspace.transition.utils.SurfaceTransaction");
    Method make = method(playerType, "makeUpdateInfo", txType);
    Class<?> infoType = type(loader, "com.honeyspace.transition.anim.floating.utils.UpdateInfo");
    infoMatrix = infoType.getMethod("asMatrix");
    infoCrop = infoType.getMethod("getCrop");
    infoCorner = infoType.getMethod("getCornerRadius");
    infoAlpha = infoType.getMethod("getAlpha");
    holdField = field(gestureType, "dontFinishByCommand");
    setHold = method(gestureType, "setDontFinishByCommand", boolean.class);
    finishRequest = method(gestureType, "requestFinishRecentAnimation");
    nativeHandoff = handoff;
    DexEvidence.deoptimizeRecentsCoordinateCalls(
        apk,
        loader,
        List.of(begin, decision, create, enter, handoff, invalidate, draw, make, infoMatrix));
    try {
      insets.install(apk, loader);
      hook(begin, false, this::begin);
      hook(
          decision,
          true,
          p -> {
            if (sessions.containsKey(p.thisObject) && !Boolean.TRUE.equals(p.args[0]))
              p.setResult(true);
          });
      hook(create, true, this::bindSource);
      hook(
          enter,
          false,
          p -> {
            Session s = sessions.get(p.thisObject);
            if (s != null) {
              s.entered = true;
              if (s.nativeMatrix != null && s.nativeCrop != null) {
                s.entryMatrix = new Matrix(s.nativeMatrix);
                s.entryCrop = new Rect(s.nativeCrop);
                s.entryCorner = s.nativeCorner;
                s.entryDeadline = SystemClock.uptimeMillis() + 34;
              }
              traceCards(s, "enter");
              HookEntry.log(
                  "RECENTS_VIEWPORT_ENTER elapsed="
                      + (SystemClock.uptimeMillis() - s.start)
                      + " ready="
                      + s.ready);
            }
          });
      hook(draw, false, this::observeScene);
      hook(
          dispatchDraw,
          false,
          p -> {
            if (!ghostListType.isInstance(p.thisObject)) return;
            View view = (View) p.thisObject;
            for (Session ss : new ArrayList<>(sessions.values()))
              if (ss.motion != null
                  && view.getRootView() == ss.overlayRoot
                  && ss.motion.drawList((Canvas) p.args[0], view)) {
                p.setResult(null);
                return;
              }
          });
      hook(
          make,
          true,
          p -> {
            for (Session ss : new ArrayList<>(sessions.values()))
              if (playerField.get(ss.manager) == p.thisObject) {
                Object value = p.getResult();
                ss.nativeMatrix = new Matrix((Matrix) infoMatrix.invoke(value));
                ss.nativeCrop = new Rect((Rect) infoCrop.invoke(value));
                ss.nativeCorner = ((Number) infoCorner.invoke(value)).floatValue();
                ss.currentInfo = value;
                break;
              }
          });
      hook(
          infoMatrix,
          true,
          p -> {
            Session ss = ownerInfo(p.thisObject);
            if (ss != null && ss.motion != null && ss.motion.overriding())
              p.setResult(ss.motion.surfaceMatrix());
            else if (entryPosePending(ss)) p.setResult(new Matrix(ss.entryMatrix));
          });
      hook(
          infoCrop,
          true,
          p -> {
            Session ss = ownerInfo(p.thisObject);
            if (ss != null && ss.motion != null && ss.motion.overriding())
              p.setResult(ss.motion.crop());
            else if (entryPosePending(ss)) p.setResult(new Rect(ss.entryCrop));
          });
      hook(
          infoCorner,
          true,
          p -> {
            Session ss = ownerInfo(p.thisObject);
            if (ss != null && ss.motion != null && ss.motion.overriding())
              p.setResult(ss.motion.corner());
            else if (entryPosePending(ss)) p.setResult(ss.entryCorner);
          });
      hook(
          infoAlpha,
          true,
          p -> {
            Session ss = ownerInfo(p.thisObject);
            if (ss != null && ss.motion != null && ss.motion.overriding()) p.setResult(1f);
          });
      hooks.add(
          ModernHook.hookMethod(
              handoff,
              new ModernHook.Callback() {
                @Override
                protected void beforeHookedMethod(ModernHook.Param p) {
                  if (!enabled || failed || Looper.myLooper() != Looper.getMainLooper()) return;
                  Session ss = sessions.get(p.thisObject);
                  if (ss == null || ss.motion == null || bypassHandoff) return;
                  if (!ss.motion.settled()) {
                    ss.motion.defer();
                    p.setObjectExtra("viewportDeferred", true);
                    p.setResult(null);
                    HookEntry.log("RECENTS_VIEWPORT_HANDOFF_WAIT continuing rendered motion");
                  }
                }

                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (!enabled || failed || Looper.myLooper() != Looper.getMainLooper()) return;
                  Session ss = sessions.get(p.thisObject);
                  if (ss == null || Boolean.TRUE.equals(p.getObjectExtra("viewportDeferred")))
                    return;
                  try {
                    if (p.hasThrowable())
                      rollback(ss, new IllegalStateException("Native handoff failed"));
                    else {
                      traceCards(ss, "handoff");
                      for (long delay :
                          BuildConfig.RECENTS_MOTION_TRACE
                              ? new long[] {100, 400, 1000}
                              : new long[0])
                        ss.overlayRoot.postDelayed(() -> traceCards(ss, "after" + delay), delay);
                      if (ss.motion != null) ss.motion.handoff();
                      restoreHold(ss);
                      ss.overlayRoot.postOnAnimation(
                          () -> {
                            if (sessions.get(ss.manager) == ss) {
                              sessions.remove(ss.manager);
                              ss.clear();
                              RecentsHandoffGate.SHARED.complete(ss.handoff, true);
                            }
                          });
                    }
                  } catch (Throwable error) {
                    rollback(ss, error);
                  }
                }
              }));
      hook(
          finishRequest,
          true,
          p -> {
            for (Session ss : new ArrayList<>(sessions.values()))
              if (ss.gesture == p.thisObject) ss.requestSeen = true;
          });
      hook(
          invalidate,
          true,
          p -> {
            Session ss = sessions.remove(p.thisObject);
            if (ss != null) {
              restoreHold(ss);
              ss.clear();
              RecentsHandoffGate.SHARED.discard(ss.handoff);
            }
          });
      HookEntry.log("RECENTS_VIEWPORT_READY continuous viewport 0.10");
    } catch (Throwable error) {
      uninstall();
      if (error instanceof Exception e) throw e;
      throw new IllegalStateException(error);
    }
  }

  private void begin(ModernHook.Param p) throws Exception {
    if (Boolean.TRUE.equals(p.args[2])
        || Boolean.TRUE.equals(p.args[3])
        || Boolean.TRUE.equals(p.args[4])
        || managerDisplay.getInt(p.thisObject) != 0
        || Boolean.TRUE.equals(managerDesktop.invoke(p.thisObject))
        || sessions.containsKey(p.thisObject)) return;
    Object task = topTask.invoke(topSource.get(p.thisObject));
    if (!(task instanceof ActivityManager.RunningTaskInfo info)
        || info.taskId != (Integer) p.args[0]
        || task.getClass().getField("displayId").getInt(task) != 0
        || task.getClass().getField("userId").getInt(task) != android.os.Process.myUid() / 100000)
      return;
    Object config = taskConfig.invoke(null, task);
    if ((Integer) windowMode.invoke(config) != 1 || (Integer) activityType.invoke(config) != 1)
      return;
    Object owner = null;
    for (Object cb : (List<?>) callbacks.get(p.thisObject))
      for (Field f : cb.getClass().getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers())) continue;
        f.setAccessible(true);
        Object value = f.get(cb);
        if (gestureType.isInstance(value) && action.get(value) == p.thisObject) {
          if (owner != null && owner != value)
            throw new IllegalStateException("Ambiguous viewport owner");
          owner = value;
        }
      }
    if (owner == null
        || gestureDisplay.getInt(owner) != 0
        || Boolean.TRUE.equals(gestureDesktop.invoke(owner))) return;
    Object o = overlay.get(interaction.get(owner));
    Object root = overlayRoot.get(o);
    if (!(root instanceof ViewGroup group)) return;
    if (!NavigationPolicy.gestures(group.getContext())) return;
    sessions.put(p.thisObject, new Session(p.thisObject, owner, group, info.taskId));
    HookEntry.log("RECENTS_VIEWPORT_BEGIN source=" + p.args[1]);
  }

  private void bindSource(ModernHook.Param p) throws Exception {
    Session s = sessions.get(p.thisObject);
    if (s == null) return;
    int mode = (Integer) targetMode.invoke(p.args[0]);
    int matches = 0;
    for (Object target : (Object[]) apps.invoke(p.args[0])) {
      Class<?> c = target.getClass();
      if (c.getField("mode").getInt(target) != mode) continue;
      if (c.getField("taskId").getInt(target) != s.taskId) {
        s.clear();
        sessions.remove(s.manager);
        RecentsHandoffGate.SHARED.complete(s.handoff, false);
        return;
      }
      matches++;
      s.source = new Rect((Rect) c.getField("sourceContainerBounds").get(target));
    }
    if (matches != 1 || s.source == null || s.source.left != 0 || s.source.top != 0) {
      s.clear();
      sessions.remove(s.manager);
      RecentsHandoffGate.SHARED.complete(s.handoff, false);
      return;
    }
    s.player = playerField.get(s.manager);
    s.targets = p.args[0];
    if (s.player == null || Boolean.TRUE.equals(translucent.invoke(s.player))) {
      sessions.remove(s.manager);
      s.clear();
      RecentsHandoffGate.SHARED.complete(s.handoff, false);
      return;
    }
    for (Object item : (Object[]) apps.invoke(p.args[0]))
      if (item.getClass().getField("taskId").getInt(item) == s.taskId
          && item.getClass().getField("mode").getInt(item) == mode)
        s.leash = (SurfaceControl) item.getClass().getField("leash").get(item);
    s.recentRoot = (View) recentRoot.invoke(controller.invoke(s.manager));
    if (s.recentRoot == null
        || targetView.get(s.manager) != s.recentRoot
        || s.recentRoot.getDisplay() == null
        || s.recentRoot.getWidth() < 1
        || s.recentRoot.getHeight() < 1) {
      sessions.remove(s.manager);
      s.clear();
      RecentsHandoffGate.SHARED.complete(s.handoff, false);
      return;
    }
    s.basis = new Matrix((Matrix) basisMethod.invoke(s.player));
    float[] measured = new float[9];
    s.basis.getValues(measured);
    s.sourceSpace =
        RecentsLeashSpace.resolve(
            new RecentsCardSpace.Space(
                s.recentRoot.getWidth(),
                s.recentRoot.getHeight(),
                s.recentRoot.getDisplay().getRotation()),
            s.source.width(),
            s.source.height(),
            measured);

    HookEntry.log(
        "RECENTS_VIEWPORT_SOURCE elapsed="
            + (SystemClock.uptimeMillis() - s.start)
            + " pixels="
            + s.source.width()
            + "x"
            + s.source.height()
            + " root="
            + (s.recentRoot == null
                ? "null"
                : s.recentRoot.getWidth() + "x" + s.recentRoot.getHeight()));
  }

  private void observeScene(ModernHook.Param p) throws Exception {
    View ghost = (View) p.thisObject;
    for (Session s : new ArrayList<>(sessions.values())) {
      if (ghost.getRootView() == s.overlayRoot
          && Boolean.TRUE.equals(hasTaskId.invoke(ghost, s.taskId))
          && s.motion != null) {
        if (s.motion.draw((Canvas) p.args[0], ghost)) p.setResult(null);
        continue;
      }
      if (s.ready
          || s.source == null
          || ghost.getRootView() != s.overlayRoot
          || !Boolean.TRUE.equals(hasTaskId.invoke(ghost, s.taskId))
          || !((Canvas) p.args[0]).isHardwareAccelerated()) continue;
      if (s.recentRoot == null)
        s.recentRoot = (View) recentRoot.invoke(controller.invoke(s.manager));
      if (s.recentRoot == null || s.recentRoot.getWidth() >= s.recentRoot.getHeight()) continue;
      if (SystemClock.uptimeMillis() - s.lastProbe < 24) continue;
      s.lastProbe = SystemClock.uptimeMillis();
      View actual = findScene(s.recentRoot, s.taskId, 0);
      if (actual == null || actual.getWidth() < 1 || actual.getHeight() < 1) continue;
      if (s.node != null) s.node.discardDisplayList();
      s.node = new RenderNode("OneUiRecentsSceneProbe");
      Canvas canvas = s.node.beginRecording(actual.getWidth(), actual.getHeight());
      try {
        actual.draw(canvas);
      } finally {
        s.node.endRecording();
      }
      List<?> snapshots = (List<?>) data.invoke(actual),
          foreground = (List<?>) paints.invoke(actual);
      if (snapshots.size() != 1
          || foreground.size() != 1
          || !(foreground.get(0) instanceof Paint paint)
          || paint.getShader() == null) continue;
      if (!Boolean.TRUE.equals(realSnapshot.invoke(snapshots.get(0)))
          || Boolean.TRUE.equals(lockedSnapshot.invoke(snapshots.get(0)))) {
        sessions.remove(s.manager);
        s.clear();
        RecentsHandoffGate.SHARED.complete(s.handoff, false);
        HookEntry.log("RECENTS_VIEWPORT_NATIVE protected or non-real thumbnail");
        continue;
      }
      if (!(thumbnail.invoke(snapshots.get(0)) instanceof android.graphics.Bitmap image)) continue;
      Matrix shader = new Matrix(), global = new Matrix();
      paint.getShader().getLocalMatrix(shader);
      actual.transformMatrixToGlobal(global);
      float[] sh = new float[9], gl = new float[9];
      shader.getValues(sh);
      global.getValues(gl);
      try {
        var geometry =
            RecentsLiveGeometry.compose(
                s.sourceSpace,
                image.getWidth(),
                image.getHeight(),
                (Integer) snapshotRotation.invoke(snapshots.get(0)),
                ((Number) snapshotScale.invoke(snapshots.get(0))).doubleValue(),
                sh,
                gl);
        s.scene = actual;
        if (!s.entered || s.nativeMatrix == null || s.nativeCrop == null || s.leash == null)
          continue;
        View ancestor = ghost;
        while (!ghostListType.isInstance(ancestor) && ancestor.getParent() instanceof View parent)
          ancestor = parent;
        if (!ghostListType.isInstance(ancestor))
          throw new IllegalArgumentException("Missing gesture list owner");
        s.motion =
            new RecentsViewportMotion(
                loader,
                s.recentRoot,
                actual,
                s.overlayRoot,
                ancestor,
                s.taskId,
                s.source,
                s.sourceSpace,
                s.basis,
                s.leash,
                s.targets,
                entryPosePending(s) ? s.entryMatrix : s.nativeMatrix,
                entryPosePending(s) ? s.entryCrop : s.nativeCrop,
                entryPosePending(s) ? s.entryCorner : s.nativeCorner,
                () -> completeDeferred(s),
                error -> rollback(s, error));
        s.originalHold = holdField.getBoolean(s.gesture);
        setHold.invoke(s.gesture, true);
        s.holdTaken = true;
        s.ready = true;
        if (s.node != null) {
          s.node.discardDisplayList();
          s.node = null;
        }
        if (s.motion.draw((Canvas) p.args[0], ghost)) p.setResult(null);
        HookEntry.log(
            "RECENTS_VIEWPORT_SCENE_READY elapsed="
                + (SystemClock.uptimeMillis() - s.start)
                + " entered="
                + s.entered
                + " scene="
                + actual.getWidth()
                + "x"
                + actual.getHeight()
                + " shader="
                + shader
                + " global="
                + global
                + " content="
                + geometry);
      } catch (IllegalArgumentException error) {
        if (s.pendingLogs++ < 6)
          HookEntry.log("RECENTS_VIEWPORT_SCENE_PENDING " + error.getMessage());
      }
    }
  }

  private void traceCards(Session ss, String stage) {
    if (!BuildConfig.RECENTS_MOTION_TRACE) return;
    try {
      View root = (View) recentRoot.invoke(controller.invoke(ss.manager));
      StringBuilder out =
          new StringBuilder("RECENTS_VIEWPORT_CARDS ")
              .append(stage)
              .append(" root=")
              .append(System.identityHashCode(root))
              .append(" size=")
              .append(root == null ? "null" : root.getWidth() + "x" + root.getHeight())
              .append(" original=")
              .append(System.identityHashCode(ss.recentRoot));
      appendCards(root, out, 0);
      HookEntry.log(out.toString());
    } catch (Throwable error) {
      HookEntry.log("RECENTS_VIEWPORT_TRACE_ERROR " + error);
    }
  }

  private void appendCards(View view, StringBuilder out, int depth) throws Exception {
    if (view == null || depth > 32 || out.length() > 3400) return;
    if (taskType.isInstance(view)) {
      Matrix matrix = new Matrix();
      view.transformMatrixToGlobal(matrix);
      float[] xy = {view.getWidth() * .5f, view.getHeight() * .5f};
      matrix.mapPoints(xy);
      out.append(" [")
          .append(taskId.invoke(view))
          .append(" @")
          .append(Math.round(xy[0]))
          .append(',')
          .append(Math.round(xy[1]))
          .append(" a=")
          .append(view.getAlpha())
          .append(" v=")
          .append(view.getVisibility())
          .append(']');
    }
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        appendCards(group.getChildAt(i), out, depth + 1);
  }

  private boolean entryPosePending(Session ss) {
    return ss != null
        && ss.motion == null
        && ss.entryMatrix != null
        && ss.entryCrop != null
        && SystemClock.uptimeMillis() <= ss.entryDeadline;
  }

  private Session ownerInfo(Object info) {
    for (Session ss : sessions.values()) if (ss.currentInfo == info) return ss;
    return null;
  }

  private void restoreHold(Session ss) throws Exception {
    if (ss.holdTaken) {
      setHold.invoke(ss.gesture, ss.originalHold);
      ss.holdTaken = false;
    }
  }

  private void completeDeferred(Session ss) {
    if (sessions.get(ss.manager) != ss || ss.motion == null) return;
    try {
      bypassHandoff = true;
      try {
        nativeHandoff.invoke(ss.manager);
      } finally {
        bypassHandoff = false;
      }
      if (ss.requestSeen && sessions.get(ss.manager) == ss) finishRequest.invoke(ss.gesture);
    } catch (Throwable error) {
      rollback(ss, error);
    }
  }

  private void rollback(Session ss, Throwable error) {
    HookEntry.log("RECENTS_VIEWPORT_ROLLBACK " + error);
    boolean deferred = ss.motion != null && ss.motion.deferred();
    try {
      if (ss.motion != null) ss.motion.cancel();
      restoreHold(ss);
      if (deferred) {
        bypassHandoff = true;
        try {
          nativeHandoff.invoke(ss.manager);
        } finally {
          bypassHandoff = false;
        }
      }
      if (ss.requestSeen) finishRequest.invoke(ss.gesture);
    } catch (Throwable cleanup) {
      HookEntry.log("RECENTS_VIEWPORT_CLEANUP_ERROR " + cleanup);
    } finally {
      if (sessions.get(ss.manager) == ss) sessions.remove(ss.manager);
      ss.clear();
      RecentsHandoffGate.SHARED.complete(ss.handoff, false);
    }
  }

  private View findScene(View v, int id, int depth) throws Exception {
    if (depth > 32) return null;
    if (taskType.isInstance(v) && (Integer) taskId.invoke(v) == id)
      return (View) taskScene.invoke(v);
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        View found = findScene(g.getChildAt(i), id, depth + 1);
        if (found != null) return found;
      }
    return null;
  }

  void setEnabled(boolean value) {
    enabled = value && !failed;
    insets.setEnabled(enabled);
    if (!enabled) {
      for (Session s : new ArrayList<>(sessions.values()))
        rollback(s, new IllegalStateException("Viewport disabled"));
      sessions.clear();
    }
  }

  void uninstall() {
    setEnabled(false);
    insets.uninstall();
    for (var h : hooks)
      try {
        h.unhook();
      } catch (Throwable ignored) {
      }
    hooks.clear();
  }

  private interface Action {
    void run(ModernHook.Param p) throws Exception;
  }

  private void hook(Method m, boolean after, Action action) {
    hooks.add(
        ModernHook.hookMethod(
            m,
            new ModernHook.Callback() {
              private void run(ModernHook.Param p) {
                if (!enabled
                    || failed
                    || p.hasThrowable()
                    || Looper.myLooper() != Looper.getMainLooper()) return;
                try {
                  action.run(p);
                } catch (Throwable error) {
                  failed = true;
                  uninstall();
                  failure.accept(error);
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

  private static Field field(Class<?> c, String n) throws Exception {
    Field f = c.getDeclaredField(n);
    f.setAccessible(true);
    return f;
  }

  private static Method method(Class<?> c, String n, Class<?>... p) throws Exception {
    Method m = c.getDeclaredMethod(n, p);
    m.setAccessible(true);
    return m;
  }
}
