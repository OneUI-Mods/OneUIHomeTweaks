package net.ryuya_dev.oneuilivecolors;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Bounded, event-driven geometry measurements for the new motion adapter. No Surface transaction,
 * View property, input event, orientation, native return value, or callback is modified here. Never
 * infer a leash's parent basis from display rotation; verify the measured endpoints first.
 */
final class RecentsMotionTrace {
  private static final int LIMIT = 64;
  private static final long SAMPLE_MS = 24;
  private final List<ModernHook.Handle> handles = new ArrayList<>();
  private final Map<Object, Session> managers = new WeakHashMap<>();
  private final Map<Object, Session> players = new WeakHashMap<>();
  private Field playerField, displayField, homeField;
  private Method desktop, basis, crop, taskRect, taskView, animation, shrink, infoMatrix;
  private Method systemController, recentTarget, firstTaskId, taskScene, sceneState, scenePosition;
  private Class<?> cardClass;
  private boolean enabled, failed;
  private long nextId;
  private Session latest;

  private static final class Session {
    final long id, start = SystemClock.uptimeMillis();
    final ArrayDeque<String> frames = new ArrayDeque<>();
    long lastSample;
    String task = "none", input = "none";
    int dropped;

    Session(long id) {
      this.id = id;
    }

    void add(String value) {
      if (frames.size() == LIMIT) {
        frames.removeFirst();
        dropped++;
      }
      frames.addLast((SystemClock.uptimeMillis() - start) + "ms " + value);
    }
  }

  void install(String apk, ClassLoader loader) {
    try {
      Class<?> manager =
          type(loader, "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager");
      Class<?> player = type(loader, "com.honeyspace.transition.anim.floating.PlayerImpl");
      Class<?> targets = type(loader, "com.honeyspace.transition.remote.RemoteAnimationTargets");
      Class<?> leash = type(loader, "com.honeyspace.sdk.source.entity.LeashTask");
      Class<?> info = type(loader, "com.honeyspace.transition.anim.floating.utils.UpdateInfo");
      Class<?> transaction = type(loader, "com.honeyspace.transition.utils.SurfaceTransaction");
      Method create =
          method(
              manager,
              "createFloatingPlayer",
              targets,
              type(loader, "android.window.TransitionInfo"));
      Method move = method(manager, "move", leash);
      Method moving = method(player, "moving", RectF.class, float.class, float.class, float.class);
      Method make = method(player, "makeUpdateInfo", transaction);
      Method handoff = method(manager, "showRecentsWhenEndRecentsEntering");
      Method invalidate = method(manager, "invalidate");
      playerField = field(manager, "appPlayer");
      displayField = field(manager, "displayId");
      homeField = field(manager, "startFromHome");
      desktop = method(manager, "isInDexDisplay");
      basis = player.getMethod("getHomeToWindowMatrix");
      crop = player.getMethod("getCurrentCropRect");
      taskRect = leash.getMethod("getTargetRect");
      taskView = leash.getMethod("getTargetView");
      animation = leash.getMethod("getAnimationProgress");
      shrink = leash.getMethod("getFullScreenProgress");
      infoMatrix = info.getMethod("asMatrix");
      systemController = method(manager, "getSystemController");
      recentTarget =
          type(loader, "com.honeyspace.sdk.HoneySystemController")
              .getMethod("getCurrentRecentTarget");
      cardClass = type(loader, "com.honeyspace.ui.honeypots.tasklist.presentation.TaskView");
      firstTaskId = cardClass.getMethod("getFirstTaskId");
      taskScene = cardClass.getMethod("getTaskSceneView");
      sceneState =
          type(loader, "com.honeyspace.ui.common.taskScene.TaskSceneView")
              .getMethod("getSceneStateInfo");
      scenePosition =
          type(loader, "com.honeyspace.ui.common.taskScene.SceneStateInfo")
              .getMethod("getPositionMatrix", float.class);
      if (create.getReturnType() != void.class
          || move.getReturnType() != void.class
          || moving.getReturnType() != void.class
          || make.getReturnType() != info
          || basis.getReturnType() != Matrix.class
          || crop.getReturnType() != Rect.class
          || taskRect.getReturnType() != Rect.class
          || taskView.getReturnType() != View.class
          || animation.getReturnType() != float.class
          || shrink.getReturnType() != float.class
          || displayField.getType() != int.class
          || homeField.getType() != boolean.class
          || desktop.getReturnType() != boolean.class
          || infoMatrix.getReturnType() != Matrix.class)
        throw new IllegalStateException("Recents motion measurement contract changed");
      DexEvidence.verifyRecentsMotion(apk, loader);
      Class<?> animationSession = type(loader, "com.honeyspace.gesture.session.AnimationSession");
      Method displacement =
          method(animationSession, "getDisplacement", android.view.MotionEvent.class);
      Method sendMove =
          method(
              animationSession,
              "sendMoveEventToGestureTaskListView",
              android.view.MotionEvent.class);
      if (!ModernHook.deoptimize(sendMove))
        throw new IllegalStateException("Cannot measure gesture input");
      handles.add(
          ModernHook.hookMethod(
              displacement,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (!active() || p.hasThrowable() || latest == null) return;
                  android.view.MotionEvent event = (android.view.MotionEvent) p.args[0];
                  latest.input =
                      "input="
                          + event.getX()
                          + ","
                          + event.getY()
                          + " displacement="
                          + p.getResult();
                }
              }));
      handles.add(
          ModernHook.hookMethod(
              create,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (!active() || p.hasThrowable()) return;
                  try {
                    if (displayField.getInt(p.thisObject) != 0
                        || homeField.getBoolean(p.thisObject)
                        || Boolean.TRUE.equals(desktop.invoke(p.thisObject))) return;
                    Object current = playerField.get(p.thisObject);
                    if (!player.isInstance(current)) return;
                    Session old = managers.remove(p.thisObject);
                    if (old != null) finish(old, "replaced");
                    Session session = new Session(++nextId);
                    latest = session;
                    managers.put(p.thisObject, session);
                    players.put(current, session);
                    session.add(
                        "begin basis="
                            + matrix((Matrix) basis.invoke(current))
                            + " crop="
                            + crop.invoke(current));
                    HookEntry.log("RECENTS_MOTION_BEGIN id=" + session.id + " observational=true");
                  } catch (Throwable error) {
                    stop(error);
                  }
                }
              }));
      handles.add(
          ModernHook.hookMethod(
              move,
              new ModernHook.Callback() {
                @Override
                protected void beforeHookedMethod(ModernHook.Param p) {
                  if (!active()) return;
                  Session session = managers.get(p.thisObject);
                  if (session == null) return;
                  try {
                    Object task = p.args[0];
                    Object value = taskView.invoke(task);
                    String view = "null";
                    if (value instanceof View target) {
                      int[] location = new int[2];
                      target.getLocationOnScreen(location);
                      View root = target.getRootView();
                      view =
                          location[0]
                              + ","
                              + location[1]
                              + "/"
                              + target.getWidth()
                              + "x"
                              + target.getHeight()
                              + " scale="
                              + target.getScaleX()
                              + ","
                              + target.getScaleY()
                              + " root="
                              + root.getWidth()
                              + "x"
                              + root.getHeight()
                              + " rotation="
                              + (target.getDisplay() == null
                                  ? -1
                                  : target.getDisplay().getRotation());
                    }
                    session.task =
                        "target="
                            + taskRect.invoke(task)
                            + " animation="
                            + animation.invoke(task)
                            + " shrink="
                            + shrink.invoke(task)
                            + " view="
                            + view;
                  } catch (Throwable error) {
                    stop(error);
                  }
                }
              }));
      handles.add(
          ModernHook.hookMethod(
              moving,
              new ModernHook.Callback() {
                @Override
                protected void beforeHookedMethod(ModernHook.Param p) {
                  if (!active()) return;
                  Session session = players.get(p.thisObject);
                  if (session == null) return;
                  long now = SystemClock.uptimeMillis();
                  if (now - session.lastSample < SAMPLE_MS) return;
                  session.lastSample = now;
                  session.add(
                      "moving="
                          + p.args[0]
                          + " cropProgress="
                          + p.args[3]
                          + " "
                          + session.task
                          + " "
                          + session.input);
                }
              }));
      handles.add(
          ModernHook.hookMethod(
              make,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (!active() || p.hasThrowable()) return;
                  Session session = players.get(p.thisObject);
                  if (session == null || SystemClock.uptimeMillis() - session.lastSample > 4)
                    return;
                  try {
                    Matrix nativeMatrix = (Matrix) infoMatrix.invoke(p.getResult());
                    Rect currentCrop = (Rect) crop.invoke(p.thisObject);
                    session.add(
                        "surface="
                            + matrix(nativeMatrix)
                            + " crop="
                            + currentCrop
                            + " homeBasis="
                            + matrix((Matrix) basis.invoke(p.thisObject)));
                  } catch (Throwable error) {
                    stop(error);
                  }
                }
              }));
      handles.add(
          ModernHook.hookMethod(
              handoff,
              new ModernHook.Callback() {
                @Override
                protected void beforeHookedMethod(ModernHook.Param p) {
                  if (!active()) return;
                  Session session = managers.get(p.thisObject);
                  if (session != null) {
                    try {
                      Object root = recentTarget.invoke(systemController.invoke(p.thisObject));
                      if (root instanceof View view) recordCards(session, view, new int[] {0});
                    } catch (Throwable error) {
                      session.add("card-read-error=" + error.getClass().getSimpleName());
                    }
                    flush(session, "handoff");
                  }
                }
              }));
      handles.add(
          ModernHook.hookMethod(
              invalidate,
              new ModernHook.Callback() {
                @Override
                protected void beforeHookedMethod(ModernHook.Param p) {
                  if (!active()) return;
                  Session session = managers.remove(p.thisObject);
                  if (session != null) finish(session, "invalidate");
                }
              }));
      HookEntry.log("RECENTS_MOTION_TRACE_READY geometry only; no animation replacement applied");
    } catch (Throwable error) {
      stop(error);
    }
  }

  void setEnabled(boolean value) {
    enabled = value && !failed && !handles.isEmpty();
    if (!enabled) {
      managers.clear();
      players.clear();
      latest = null;
    }
  }

  void uninstall() {
    enabled = false;
    for (var handle : handles)
      try {
        handle.unhook();
      } catch (Throwable ignored) {
      }
    handles.clear();
    managers.clear();
    players.clear();
    latest = null;
  }

  private void recordCards(Session session, View view, int[] visited) throws Exception {
    if (++visited[0] > 256) return;
    if (cardClass.isInstance(view)) {
      Object scene = taskScene.invoke(view);
      if (scene instanceof View child) {
        Matrix global = new Matrix();
        child.transformMatrixToGlobal(global);
        session.add(
            "portrait-card task="
                + firstTaskId.invoke(view)
                + " root="
                + view.getRootView().getWidth()
                + "x"
                + view.getRootView().getHeight()
                + " scene="
                + child.getWidth()
                + "x"
                + child.getHeight()
                + " global="
                + matrix(global)
                + " content="
                + scenePosition.invoke(sceneState.invoke(scene), 1f));
      }
    }
    if (view instanceof android.view.ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        recordCards(session, group.getChildAt(i), visited);
  }

  private boolean active() {
    return enabled && !failed && Looper.myLooper() == Looper.getMainLooper();
  }

  private void finish(Session session, String reason) {
    if (latest == session) latest = null;
    players.values().removeIf(value -> value == session);
    flush(session, reason);
  }

  private void flush(Session session, String reason) {
    HookEntry.log(
        "RECENTS_MOTION_TRACE id="
            + session.id
            + " event="
            + reason
            + " dropped="
            + session.dropped
            + " "
            + session.task
            + " "
            + session.input);
    for (String frame : session.frames)
      HookEntry.log("RECENTS_MOTION_FRAME id=" + session.id + " " + frame);
    session.frames.clear();
  }

  private void stop(Throwable error) {
    failed = true;
    uninstall();
    HookEntry.log("RECENTS_MOTION_TRACE_DISABLED " + error);
  }

  private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
    return Class.forName(name, false, loader);
  }

  private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
    Method method = type.getDeclaredMethod(name, args);
    method.setAccessible(true);
    return method;
  }

  private static Field field(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static String matrix(Matrix matrix) {
    float[] m = new float[9];
    matrix.getValues(m);
    // Also reject geometry the replacement engine cannot represent. This is measurement only.
    var affine = new RecentsMotionPath.Transform(m[0], m[3], m[1], m[4], m[2], m[5]);
    return affine.toString();
  }
}
