package net.ryuya_dev.oneuilivecolors;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Adapt final-card rectangles to the native gesture window; never resize the gesture viewport. */
final class RecentsCoordinateHooks {
  private final Consumer<Throwable> failure;
  private final List<ModernHook.Handle> hooks = new ArrayList<>();
  private final Map<Object, Boolean> owners = Collections.synchronizedMap(new WeakHashMap<>());
  private final Map<Object, Session> sessions = new WeakHashMap<>();
  private final Map<Object, Session> interactions = new WeakHashMap<>();
  private final RecentsCardSourceCache sourceSpaces = new RecentsCardSourceCache();
  private final ThreadLocal<RecentsCardSpace.Space> producingSpace = new ThreadLocal<>();
  private Class<?> taskViewType;
  private Method taskViewId, taskScene;
  private Field action, interaction, overlay, overlayRoot, calculator, appPlayer;
  private Field managerDisplay, gestureDisplay, startCallbacks, topTaskSource, configWindow;
  private Method managerDesktop, gestureDesktop, topTask, taskConfig, windowMode, activityType;
  private Method fullSize, calcStyler, styleData, styleBounds, configRotation;
  private Method controller, recentRoot, playerSize, apps, targetMode;
  private Method taskMap, deskMap, infoCopy, closeAnimator, iconAnimator, subSupported;
  private Method mainInterpolator, subInterpolator, activeDesk, childCopy;
  private Method toRect, subFrom, subTo, duration, toAlpha, tilt, subDuration, subDelay;
  private Class<?> gestureClass;
  private boolean enabled, failed;

  private static final class Session {
    final Object manager, gesture, interaction, calculator, overlay;
    final Point source;
    final int taskId;
    Rect remoteBounds;
    int remoteRotation = -1;

    Session(
        Object manager,
        Object gesture,
        Object interaction,
        Object calculator,
        Object overlay,
        Point source,
        int taskId) {
      this.manager = manager;
      this.gesture = gesture;
      this.interaction = interaction;
      this.calculator = calculator;
      this.overlay = overlay;
      this.source = new Point(source);
      this.taskId = taskId;
    }
  }

  RecentsCoordinateHooks(Consumer<Throwable> failure) {
    this.failure = failure;
  }

  void install(String apk, ClassLoader loader) throws Exception {
    Class<?> manager =
        type(loader, "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager");
    gestureClass = type(loader, "com.honeyspace.gesture.session.AnimationSession");
    Class<?> interactionType =
        type(loader, "com.honeyspace.gesture.recentinteraction.TaskViewInteraction");
    Class<?> overlayType =
        type(loader, "com.honeyspace.gesture.overlaywindow.LeashOverlayWindowImpl");
    Class<?> calc = type(loader, "com.honeyspace.gesture.recentinteraction.LeashSizeCalculator");
    Class<?> styler = type(loader, "com.honeyspace.common.recentstyler.RecentStylerV2");
    Class<?> style = type(loader, "com.honeyspace.common.data.RecentStyleData");
    Class<?> info = type(loader, "com.honeyspace.sdk.source.entity.TaskViewInfo");
    Class<?> child = type(loader, "com.honeyspace.sdk.source.entity.TaskChildViewInfo");
    Class<?> window = type(loader, "android.app.WindowConfiguration");
    Class<?> player = type(loader, "com.honeyspace.transition.anim.floating.PlayerImpl");
    Class<?> targets = type(loader, "com.honeyspace.transition.remote.RemoteAnimationTargets");
    Class<?> controllerType = type(loader, "com.honeyspace.sdk.HoneySystemController");
    Class<?> top = type(loader, "com.honeyspace.gesture.usecase.TopTaskUseCase");
    action =
        field(
            gestureClass,
            "recentsAnimationAction",
            type(loader, "com.honeyspace.gesture.recentsanimation.RecentsAnimationAction"));
    interaction = field(gestureClass, "taskViewInteraction", interactionType);
    calculator = field(gestureClass, "leashSizeCalculator", calc);
    overlay = field(interactionType, "leashOverlayWindow", overlayType);
    overlayRoot = field(overlayType, "rootView", android.widget.FrameLayout.class);
    managerDisplay = field(manager, "displayId", int.class);
    gestureDisplay = field(gestureClass, "displayId", int.class);
    startCallbacks = field(manager, "onStartCallbacks", List.class);
    topTaskSource = field(manager, "topTaskUseCase", top);
    appPlayer =
        field(manager, "appPlayer", type(loader, "com.honeyspace.transition.anim.floating.Player"));
    configWindow = field(Configuration.class, "windowConfiguration", window);
    configRotation = method(window, "getRotation", int.class);
    managerDesktop = method(manager, "isInDexDisplay", boolean.class);
    gestureDesktop = method(gestureClass, "isInDexDisplay", boolean.class);
    topTask = method(top, "getTopTask", ActivityManager.RunningTaskInfo.class);
    taskConfig =
        method(
            type(loader, "com.honeyspace.common.reflection.TaskInfoReflectionKt"),
            "getWindowConfiguration",
            window,
            type(loader, "android.app.TaskInfo"));
    windowMode = method(window, "getWindowingMode", int.class);
    activityType = method(window, "getActivityType", int.class);
    fullSize = method(calc, "getFullDisplaySize", Point.class);
    calcStyler = method(calc, "getStyler", styler);
    styleData = styler.getMethod("getStyleData");
    styleBounds = method(style, "getBounds", RectF.class);
    controller = method(manager, "getSystemController", controllerType);
    recentRoot = controllerType.getMethod("getCurrentRecentTarget");
    if (recentRoot.getReturnType() != View.class)
      throw new IllegalStateException("Recents root contract changed");
    playerSize = method(player, "getScreenSize", int[].class);
    apps =
        method(
            targets,
            "getApps",
            java.lang.reflect.Array.newInstance(
                    type(loader, "android.view.RemoteAnimationTarget"), 0)
                .getClass());
    targetMode = method(targets, "getTargetMode", int.class);
    taskMap = method(info, "getTaskChildViewInfoMap", Map.class);
    deskMap = method(info, "getDeskChildViewInfoMap", Map.class);
    closeAnimator = method(info, "getCloseAllAnimator", ValueAnimator.class);
    iconAnimator = method(info, "getTaskIconAnimator", ValueAnimator.class);
    subSupported = method(info, "getSubAnimSupported", boolean.class);
    mainInterpolator = method(info, "getMainAnimInterpolator", PathInterpolator.class);
    subInterpolator = method(info, "getSubAnimInterpolator", PathInterpolator.class);
    activeDesk = method(info, "getActiveDeskId", int.class);
    infoCopy =
        method(
            info,
            "copy",
            info,
            ValueAnimator.class,
            ValueAnimator.class,
            boolean.class,
            PathInterpolator.class,
            PathInterpolator.class,
            int.class,
            Map.class,
            Map.class);
    toRect = method(child, "getToRect", RectF.class);
    subFrom = method(child, "getSubAnimTaskFromRect", RectF.class);
    subTo = method(child, "getSubAnimTaskToRect", RectF.class);
    duration = method(child, "getToMoveDuration", long.class);
    toAlpha = method(child, "getToAlpha", float.class);
    tilt = method(child, "getTiltScrollProgress", double.class);
    subDuration = method(child, "getSubAnimMoveDuration", long.class);
    subDelay = method(child, "getSubAnimStartDelay", long.class);
    childCopy =
        method(
            child,
            "copy",
            child,
            RectF.class,
            long.class,
            float.class,
            double.class,
            RectF.class,
            RectF.class,
            long.class,
            long.class);
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
    Method create =
        method(
            manager,
            "createFloatingPlayer",
            void.class,
            targets,
            type(loader, "android.window.TransitionInfo"));
    Method enter = method(interactionType, "startRecentEnterAnimation", void.class, info);
    taskViewType = type(loader, "com.honeyspace.ui.honeypots.tasklist.presentation.TaskView");
    taskViewId = method(taskViewType, "getFirstTaskId", int.class);
    taskScene =
        method(
            taskViewType,
            "getTaskSceneView",
            type(loader, "com.honeyspace.ui.common.taskScene.TaskSceneView"));
    Method invalidate = method(manager, "invalidate", void.class);
    Method decision = method(manager, "needRecentsActivityCall", boolean.class, boolean.class);
    DexEvidence.deoptimizeRecentsCoordinateCalls(
        apk, loader, List.of(begin, create, enter, invalidate, decision));
    try {
      for (Method producer : DexEvidence.recentsCardProducers(apk, loader))
        hooks.add(
            ModernHook.hookMethod(
                producer,
                new ModernHook.Callback() {
                  @Override
                  protected void beforeHookedMethod(ModernHook.Param p) {
                    if (!enabled || Looper.myLooper() != Looper.getMainLooper()) return;
                    p.setObjectExtra("cardSpaceEntered", true);
                    p.setObjectExtra("previousCardSpace", producingSpace.get());
                    producingSpace.remove();
                    try {
                      View root = null;
                      for (Field f : p.thisObject.getClass().getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            || !View.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        Object value = f.get(p.thisObject);
                        if (value instanceof View view) {
                          View candidate = view.getRootView();
                          if (root != null && root != candidate)
                            throw new IllegalStateException("Ambiguous card producer roots");
                          root = candidate;
                        }
                      }
                      if (root != null) producingSpace.set(space(root));
                    } catch (Throwable error) {
                      HookEntry.log("RECENTS_CARD_SOURCE_UNAVAILABLE " + error);
                    }
                  }

                  @Override
                  protected void afterHookedMethod(ModernHook.Param p) {
                    if (!Boolean.TRUE.equals(p.getObjectExtra("cardSpaceEntered"))) return;
                    if (p.getObjectExtra("previousCardSpace") instanceof RecentsCardSpace.Space old)
                      producingSpace.set(old);
                    else producingSpace.remove();
                  }
                }));
      hooks.addAll(
          ModernHook.hookAllConstructors(
              info,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (enabled && !p.hasThrowable() && producingSpace.get() != null)
                    sourceSpaces.put(p.thisObject, producingSpace.get());
                }
              }));
      hooks.addAll(
          ModernHook.hookAllConstructors(
              gestureClass,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (!p.hasThrowable()) owners.put(p.thisObject, true);
                }
              }));
      hook(begin, false, this::begin);
      // Make the actual destination available for its geometry; retain the native gesture
      // window, calculator and input axes. The branch alone is not the coordinate correction.
      hook(
          decision,
          true,
          p -> {
            if (sessions.containsKey(p.thisObject) && !Boolean.TRUE.equals(p.args[0])) {
              p.setResult(true);
              HookEntry.log("RECENTS_COORDINATES_DESTINATION_REQUEST");
            }
          });
      hook(create, true, this::bindSource);
      hook(enter, false, this::mapDestination);
      hook(invalidate, true, p -> end(sessions.remove(p.thisObject)));
      HookEntry.log("RECENTS_COORDINATES_READY native viewport; destination rectangles only");
    } catch (Throwable t) {
      uninstall();
      if (t instanceof Exception e) throw e;
      throw new IllegalStateException(t);
    }
  }

  private void begin(ModernHook.Param p) throws Exception {
    if (Boolean.TRUE.equals(p.args[2])
        || Boolean.TRUE.equals(p.args[3])
        || Boolean.TRUE.equals(p.args[4])
        || managerDisplay.getInt(p.thisObject) != 0
        || Boolean.TRUE.equals(managerDesktop.invoke(p.thisObject))
        || !(p.args[1] instanceof Point size)
        || size.x <= size.y
        || size.y <= 0
        || appPlayer.get(p.thisObject) != null
        || sessions.containsKey(p.thisObject)) return;
    Object task = topTask.invoke(topTaskSource.get(p.thisObject));
    if (task == null
        || task.getClass().getField("taskId").getInt(task) != (Integer) p.args[0]
        || task.getClass().getField("displayId").getInt(task) != 0) return;
    Object config = taskConfig.invoke(null, task);
    if (config == null
        || (Integer) windowMode.invoke(config) != 1
        || (Integer) activityType.invoke(config) != 1) return;
    Object owner = findOwner(p.thisObject);
    if (owner == null
        || gestureDisplay.getInt(owner) != 0
        || Boolean.TRUE.equals(gestureDesktop.invoke(owner))) return;
    Object c = calculator.get(owner), i = interaction.get(owner);
    if (!size.equals(fullSize.invoke(c))) return;
    Session s = new Session(p.thisObject, owner, i, c, overlay.get(i), size, (Integer) p.args[0]);
    sessions.put(p.thisObject, s);
    interactions.put(i, s);
    HookEntry.log("RECENTS_COORDINATES_BEGIN native=" + size);
  }

  private Object findOwner(Object manager) throws Exception {
    Object found = null;
    synchronized (owners) {
      for (Object candidate : new ArrayList<>(owners.keySet()))
        if (action.get(candidate) == manager) {
          if (found != null && found != candidate)
            throw new IllegalStateException("Ambiguous gesture owner");
          found = candidate;
        }
    }
    if (found != null) return found;
    for (Object callback : (List<?>) startCallbacks.get(manager))
      for (Field f : callback.getClass().getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
        f.setAccessible(true);
        Object value = f.get(callback);
        if (gestureClass.isInstance(value) && action.get(value) == manager) {
          if (found != null && found != value)
            throw new IllegalStateException("Ambiguous gesture callback");
          found = value;
        }
      }
    return found;
  }

  private void bindSource(ModernHook.Param p) throws Exception {
    Session s = sessions.get(p.thisObject);
    if (s == null) return;
    int matches = 0, mode = (Integer) targetMode.invoke(p.args[0]);
    for (Object app : (Object[]) apps.invoke(p.args[0])) {
      if (app.getClass().getField("mode").getInt(app) != mode
          || app.getClass().getField("taskId").getInt(app) != s.taskId) continue;
      Object config = app.getClass().getField("windowConfiguration").get(app);
      if ((Integer) windowMode.invoke(config) != 1 || (Integer) activityType.invoke(config) != 1)
        continue;
      s.remoteBounds = new Rect((Rect) app.getClass().getField("sourceContainerBounds").get(app));
      s.remoteRotation = (Integer) configRotation.invoke(config);
      matches++;
    }
    if (matches != 1) {
      skip(s, "closing source changed");
      return;
    }
    HookEntry.log(
        "RECENTS_COORDINATES_SOURCE bounds=" + s.remoteBounds + " rotation=" + s.remoteRotation);
  }

  private void mapDestination(ModernHook.Param p) throws Exception {
    Session s = interactions.get(p.thisObject);
    if (s == null) return;
    Object info = p.args[0];
    Map<?, ?> tasks = (Map<?, ?>) taskMap.invoke(info);
    if (!tasks.containsKey(s.taskId) || !((Map<?, ?>) deskMap.invoke(info)).isEmpty()) {
      skip(s, "no matching fullscreen app or desktop target");
      return;
    }
    View cardRoot = (View) overlayRoot.get(s.overlay);
    View destination = (View) recentRoot.invoke(controller.invoke(s.manager));
    if (cardRoot == null) {
      skip(s, "unattached native gesture root");
      return;
    }
    var from = sourceSpaces.get(info);
    if (from == null) {
      skip(s, "unverified TaskViewInfo producer space");
      return;
    }
    var to = space(cardRoot);
    Map<Integer, RectF> observed = new LinkedHashMap<>();
    RecentsCardSpace.Space destinationSpace = null;
    if (destination != null && destination.isAttachedToWindow()) {
      destinationSpace = space(destination);
      readCardRects(destination, observed, 0);
      HookEntry.log(
          "RECENTS_CARD_ENDPOINT source="
              + from
              + " gesture="
              + to
              + " destination="
              + destinationSpace
              + " original="
              + toRect.invoke(tasks.get(s.taskId))
              + " observed="
              + observed.get(s.taskId)
              + " available="
              + observed.size());
    }
    Point size = (Point) fullSize.invoke(s.calculator);
    RectF bounds = (RectF) styleBounds.invoke(styleData.invoke(calcStyler.invoke(s.calculator)));
    Object player = appPlayer.get(s.manager);
    int[] window = player == null ? null : (int[]) playerSize.invoke(player);
    if (!measuredNativeViewport(to, size, bounds)
        || s.remoteBounds == null
        || window == null
        || window.length != 2
        || window[0] != to.width()
        || window[1] != to.height()
        || s.remoteBounds.left != 0
        || s.remoteBounds.top != 0
        || s.remoteBounds.width() != to.width()
        || s.remoteBounds.height() != to.height()
        || s.remoteRotation != to.rotation()) {
      skip(
          s,
          "native viewport/window mismatch root="
              + to
              + " calc="
              + size
              + " style="
              + bounds
              + " remote="
              + s.remoteBounds
              + " rotation="
              + s.remoteRotation);
      return;
    }
    boolean actualEndpoints =
        destinationSpace != null && !destinationSpace.equals(to) && observed.containsKey(s.taskId);
    if (from.equals(to) && !actualEndpoints) {
      HookEntry.log("RECENTS_COORDINATES_NATIVE matching producer/gesture spaces");
      return;
    }
    Matrix matrix = matrix(RecentsCardSpace.between(from, to));
    Matrix endpointMatrix =
        actualEndpoints ? matrix(RecentsCardSpace.between(destinationSpace, to)) : null;
    Map<Object, Object> copies = new LinkedHashMap<>();
    for (var entry : tasks.entrySet()) {
      Object child = entry.getValue();
      RectF endpoint = actualEndpoints ? observed.get(entry.getKey()) : null;
      copies.put(
          entry.getKey(),
          childCopy.invoke(
              child,
              endpoint == null
                  ? map(matrix, (RectF) toRect.invoke(child))
                  : map(endpointMatrix, endpoint),
              duration.invoke(child),
              toAlpha.invoke(child),
              tilt.invoke(child),
              actualEndpoints ? new RectF() : map(matrix, (RectF) subFrom.invoke(child)),
              actualEndpoints ? new RectF() : map(matrix, (RectF) subTo.invoke(child)),
              subDuration.invoke(child),
              subDelay.invoke(child)));
    }
    Object original = tasks.get(s.taskId), mapped = copies.get(s.taskId);
    Object replacement =
        infoCopy.invoke(
            info,
            closeAnimator.invoke(info),
            iconAnimator.invoke(info),
            actualEndpoints ? false : subSupported.invoke(info),
            mainInterpolator.invoke(info),
            subInterpolator.invoke(info),
            activeDesk.invoke(info),
            copies,
            deskMap.invoke(info));
    HookEntry.log(
        "RECENTS_COORDINATES_DESTINATION from="
            + from
            + " to="
            + to
            + " rect="
            + toRect.invoke(original)
            + " mapped="
            + toRect.invoke(mapped));
    sourceSpaces.put(replacement, to);
    p.args[0] = replacement;
  }

  private void readCardRects(View view, Map<Integer, RectF> output, int depth) throws Exception {
    if (depth > 30 || output.size() > 64) return;
    if (taskViewType.isInstance(view)) {
      int id = (Integer) taskViewId.invoke(view);
      View scene = (View) taskScene.invoke(view);
      if (scene != null && scene.getMeasuredWidth() > 0 && scene.getMeasuredHeight() > 0) {
        int[] p = new int[2];
        scene.getLocationOnScreen(p);
        output.put(
            id,
            new RectF(
                p[0],
                p[1],
                p[0] + scene.getMeasuredWidth() * view.getScaleX(),
                p[1] + scene.getMeasuredHeight() * view.getScaleY()));
      }
    }
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        readCardRects(group.getChildAt(i), output, depth + 1);
  }

  private RecentsCardSpace.Space space(View root) throws Exception {
    if (!root.isAttachedToWindow()
        || root.getDisplay() == null
        || root.getDisplay().getDisplayId() != 0)
      throw new IllegalStateException("Unattached/secondary card window");
    int[] origin = new int[2];
    root.getLocationOnScreen(origin);
    if (origin[0] != 0
        || origin[1] != 0
        || root.getScaleX() != 1f
        || root.getScaleY() != 1f
        || root.getRotation() != 0f)
      throw new IllegalStateException("Unsupported card window origin/transform");
    Object config = configWindow.get(root.getResources().getConfiguration());
    int rotation = (Integer) configRotation.invoke(config);
    return new RecentsCardSpace.Space(root.getWidth(), root.getHeight(), rotation);
  }

  private static boolean measuredNativeViewport(
      RecentsCardSpace.Space root, Point size, RectF style) {
    return size.x == root.width()
        && size.y == root.height()
        && Float.isFinite(style.left)
        && Float.isFinite(style.top)
        && Float.isFinite(style.width())
        && Float.isFinite(style.height())
        && Math.abs(style.left) < 1
        && Math.abs(style.top) < 1
        && Math.abs(style.width() - root.width()) <= 1
        && Math.abs(style.height() - root.height()) <= 1;
  }

  private static RectF map(Matrix matrix, RectF src) {
    RectF rect = new RectF(src);
    if (!rect.isEmpty()) matrix.mapRect(rect);
    return rect;
  }

  private static Matrix matrix(RecentsMotionPath.Transform t) {
    Matrix m = new Matrix();
    m.setValues(
        new float[] {
          (float) t.a(),
          (float) t.c(),
          (float) t.tx(),
          (float) t.b(),
          (float) t.d(),
          (float) t.ty(),
          0,
          0,
          1
        });
    return m;
  }

  private void skip(Session s, String reason) {
    end(sessions.remove(s.manager));
    HookEntry.log("RECENTS_COORDINATES_SKIP " + reason);
  }

  private void end(Session s) {
    if (s != null) interactions.remove(s.interaction);
  }

  void setEnabled(boolean value) {
    enabled = value && !failed && !hooks.isEmpty();
    if (!enabled) {
      sessions.clear();
      interactions.clear();
    }
  }

  void uninstall() {
    enabled = false;
    for (var hook : hooks)
      try {
        hook.unhook();
      } catch (Throwable ignored) {
      }
    hooks.clear();
    owners.clear();
    sourceSpaces.clear();
    producingSpace.remove();
    sessions.clear();
    interactions.clear();
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
                Object[] original = p.args.clone();
                try {
                  action.run(p);
                } catch (Throwable error) {
                  System.arraycopy(original, 0, p.args, 0, original.length);
                  failed = true;
                  uninstall();
                  failure.accept(error);
                  HookEntry.log("RECENTS_COORDINATES_DISABLED " + error);
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

  private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
    return Class.forName(name, false, loader);
  }

  private static Field field(Class<?> owner, String name, Class<?> expected) throws Exception {
    Field f = owner.getDeclaredField(name);
    if (f.getType() != expected) throw new IllegalStateException("Field contract changed: " + name);
    f.setAccessible(true);
    return f;
  }

  private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... args)
      throws Exception {
    Method m = owner.getDeclaredMethod(name, args);
    if (m.getReturnType() != result)
      throw new IllegalStateException("Method contract changed: " + name);
    m.setAccessible(true);
    return m;
  }
}
