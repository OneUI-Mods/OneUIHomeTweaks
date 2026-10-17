package net.ryuya_dev.oneuilivecolors;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Experimental, session-scoped bridge between the gesture's start axes, portrait card coordinates
 * and the native app player's window coordinates. Not installed by normal builds. Native spring,
 * crop, Surface transactions and completion remain in Samsung's player.
 */
final class RecentsCoordinateHooks {
  private final Consumer<Throwable> failure;
  private final List<ModernHook.Handle> hooks = new ArrayList<>();
  private final Map<Object, Boolean> gestureOwners =
      Collections.synchronizedMap(new WeakHashMap<>());
  private final Map<Object, Session> sessions = new WeakHashMap<>();
  private final Map<Object, Session> gestures = new WeakHashMap<>();
  private final Map<View, WindowManager.LayoutParams> windows = new WeakHashMap<>();
  private final ThreadLocal<Session> dispatch = new ThreadLocal<>();
  private Field managerContext, managerDisplay, action, calculator, down, region, gestureDisplay;
  private Field gestureInteraction, interactionOverlay;
  private Method calculatorStyler, stylerData, styleBounds, playerCurrentRect;
  private Field appPlayer,
      finalPosition,
      overlayRoot,
      topTaskSource,
      touchSlop,
      managerSize,
      startCallbacks;
  private Class<?> gestureClass;
  private Method topTask, windowConfiguration, windowMode, activityType, remoteApps, remoteMode;
  private Object unit;
  private Method managerDesktop, gestureDesktop, regionPosition, fullSize, topDrawn;
  private Method playerBasis, finishController, overlayDisplay, overlayDesktop;
  private Method target, targetView, radius, animation, shrink, alpha, hidden, spring, copy, invoke;
  private Class<?> function1, regionBottom, regionLeft, regionRight;
  private ClassLoader loader;
  private boolean enabled, failed;

  private static final class Session {
    final Object manager, gesture, calculator, overlay;
    final RecentsGestureCoordinates coordinates;
    final Point source, portrait;
    final RectF originalDrawn;
    final int startRotation;
    int taskId;
    final long started = SystemClock.uptimeMillis();
    Matrix toWindow, toCards;
    boolean requested, rotated, active, ending;

    Session(
        Object manager,
        Object gesture,
        Object calculator,
        Object overlay,
        RecentsGestureCoordinates coordinates,
        Point source,
        int rotation,
        RectF originalDrawn) {
      this.manager = manager;
      this.gesture = gesture;
      this.calculator = calculator;
      this.overlay = overlay;
      this.coordinates = coordinates;
      this.source = new Point(source);
      this.portrait = new Point(source.y, source.x);
      this.startRotation = rotation;
      this.originalDrawn = new RectF(originalDrawn);
    }
  }

  RecentsCoordinateHooks(Consumer<Throwable> failure) {
    this.failure = failure;
  }

  void install(String apk, ClassLoader loader) throws Exception {
    this.loader = loader;
    Class<?> manager = type("com.honeyspace.gesture.recentsanimation.RecentsAnimationManager");
    Class<?> gesture = type("com.honeyspace.gesture.session.AnimationSession");
    gestureClass = gesture;
    startCallbacks = field(manager, "onStartCallbacks", List.class);
    Class<?> leash = type("com.honeyspace.sdk.source.entity.LeashTask");
    Class<?> calc = type("com.honeyspace.gesture.recentinteraction.LeashSizeCalculator");
    Class<?> player = type("com.honeyspace.transition.anim.floating.PlayerImpl");
    Class<?> overlay = type("com.honeyspace.gesture.overlaywindow.LeashOverlayWindowImpl");
    Class<?> device = type("com.honeyspace.gesture.entity.DeviceState");
    Class<?> interaction = type("com.honeyspace.gesture.recentinteraction.TaskViewInteraction");
    gestureInteraction = field(gesture, "taskViewInteraction", interaction);
    interactionOverlay = field(interaction, "leashOverlayWindow", overlay);
    Class<?> styler = type("com.honeyspace.common.recentstyler.RecentStylerV2");
    Class<?> style = type("com.honeyspace.common.data.RecentStyleData");
    calculatorStyler = method(calc, "getStyler", styler);
    stylerData = styler.getMethod("getStyleData");
    styleBounds = method(style, "getBounds", RectF.class);
    playerCurrentRect = method(player, "getCurrentRectF", RectF.class);
    Class<?> targets = type("com.honeyspace.transition.remote.RemoteAnimationTargets");
    function1 = type("kotlin.jvm.functions.Function1");
    unit = type("kotlin.Unit").getField("INSTANCE").get(null);
    Class<?> topType = type("com.honeyspace.gesture.usecase.TopTaskUseCase");
    topTaskSource = field(manager, "topTaskUseCase", topType);
    topTask = method(topType, "getTopTask", android.app.ActivityManager.RunningTaskInfo.class);
    Class<?> windowType = type("android.app.WindowConfiguration");
    windowConfiguration =
        method(
            type("com.honeyspace.common.reflection.TaskInfoReflectionKt"),
            "getWindowConfiguration",
            windowType,
            type("android.app.TaskInfo"));
    windowMode = method(windowType, "getWindowingMode", int.class);
    activityType = method(windowType, "getActivityType", int.class);
    remoteApps =
        method(
            targets,
            "getApps",
            java.lang.reflect.Array.newInstance(type("android.view.RemoteAnimationTarget"), 0)
                .getClass());
    remoteMode = method(targets, "getTargetMode", int.class);
    regionBottom = type("com.honeyspace.gesture.region.RegionPosition$BOTTOM");
    regionLeft = type("com.honeyspace.gesture.region.RegionPosition$LEFT");
    regionRight = type("com.honeyspace.gesture.region.RegionPosition$RIGHT");
    managerContext = field(manager, "context", Context.class);
    managerDisplay = field(manager, "displayId", int.class);
    managerSize = field(manager, "displaySize", Point.class);
    managerDesktop = method(manager, "isInDexDisplay", boolean.class);
    action =
        field(
            gesture,
            "recentsAnimationAction",
            type("com.honeyspace.gesture.recentsanimation.RecentsAnimationAction"));
    calculator = field(gesture, "leashSizeCalculator", calc);
    down = field(gesture, "downPos", PointF.class);
    touchSlop = field(gesture, "extraTouchSlop", int.class);
    region = field(gesture, "regionManager", type("com.honeyspace.gesture.region.RegionManager"));
    gestureDisplay = field(gesture, "displayId", int.class);
    gestureDesktop = method(gesture, "isInDexDisplay", boolean.class);
    regionPosition = region.getType().getDeclaredMethod("getRegionPosition");
    regionPosition.setAccessible(true);
    fullSize = method(calc, "getFullDisplaySize", Point.class);
    topDrawn = method(calc, "getTopDrawnRect", RectF.class);
    appPlayer = field(manager, "appPlayer", type("com.honeyspace.transition.anim.floating.Player"));
    finalPosition = field(manager, "finalDrawPosition", RectF.class);
    playerBasis = method(player, "getHomeToWindowMatrix", Matrix.class);
    finishController =
        method(manager, "finishController", void.class, boolean.class, boolean.class);
    target = method(leash, "getTargetRect", Rect.class);
    targetView = method(leash, "getTargetView", View.class);
    radius = method(leash, "getCornerRadius", float.class);
    animation = method(leash, "getAnimationProgress", float.class);
    shrink = method(leash, "getFullScreenProgress", float.class);
    alpha = method(leash, "getAlpha", float.class);
    hidden = method(leash, "getCanHidden", boolean.class);
    spring = method(leash, "getSpringRectCallback", function1);
    copy =
        method(
            leash,
            "copy",
            leash,
            View.class,
            float.class,
            Rect.class,
            float.class,
            float.class,
            float.class,
            boolean.class,
            function1);
    invoke = function1.getMethod("invoke", Object.class);
    overlayRoot = field(overlay, "rootView", android.widget.FrameLayout.class);
    overlayDisplay = method(overlay, "getDisplayId", int.class);
    overlayDesktop = method(overlay, "isInDexDisplay", boolean.class);
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
    Method create =
        method(
            manager,
            "createFloatingPlayer",
            void.class,
            targets,
            type("android.window.TransitionInfo"));
    Method move = method(manager, "move", void.class, leash);
    Method threshold = method(manager, "updateSwipeUpThresholdState", void.class, RectF.class);
    Method invalidate = method(manager, "invalidate", void.class);
    Method displacement = method(gesture, "getDisplacement", PointF.class, MotionEvent.class);
    Method send =
        method(gesture, "sendMoveEventToGestureTaskListView", void.class, MotionEvent.class);
    Method obtain =
        method(
            type("com.honeyspace.gesture.session.AnimationSessionKt"),
            "obtain",
            MotionEvent.class,
            MotionEvent.class,
            type("com.honeyspace.gesture.region.RegionPosition"));
    var displayEmitter =
        DexEvidence.capturedEmitter(
            apk,
            loader,
            gesture,
            device,
            "StartHomeScreenAnimation, display size changed, before",
            "getFullDisplaySize");
    var layoutEmitter =
        DexEvidence.capturedEmitter(
            apk, loader, overlay, WindowManager.LayoutParams.class, null, "updateViewLayout");
    Method displaySize = method(device, "getDisplaySize", Point.class);
    DexEvidence.verifyRecentsMotion(apk, loader);
    DexEvidence.deoptimizeRecentsCoordinateCalls(
        apk,
        loader,
        List.of(
            begin,
            decision,
            create,
            move,
            threshold,
            invalidate,
            displacement,
            send,
            obtain,
            finishController));
    try {
      hooks.addAll(
          ModernHook.hookAllConstructors(
              gesture,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (!p.hasThrowable()) gestureOwners.put(p.thisObject, true);
                }
              }));
      hook(begin, p -> begin(p), null);
      hook(
          decision,
          null,
          p -> {
            Session s = sessions.get(p.thisObject);
            if (s == null || s.ending || Boolean.TRUE.equals(p.args[0])) return;
            s.requested = true;
            prepareWindows();
            HookEntry.log("RECENTS_COORDINATES_REQUEST portrait activity and owned overlay");
            p.setResult(true);
          });
      hook(
          layoutEmitter.emit(),
          p -> {
            if (sessions.isEmpty()) return;
            Object owner = layoutEmitter.owner().get(p.thisObject);
            if (sessions.values().stream().noneMatch(s -> s.overlay == owner && !s.ending)) return;
            if ((Integer) overlayDisplay.invoke(owner) != 0
                || Boolean.TRUE.equals(overlayDesktop.invoke(owner))) return;
            if (overlayRoot.get(owner) instanceof View view
                && p.args[0] instanceof WindowManager.LayoutParams nativeParams
                && nativeParams.width == -1
                && nativeParams.height == -1
                && validWindow(nativeParams)) p.args[0] = portraitParams(view, nativeParams);
          },
          null);
      hook(
          displayEmitter.emit(),
          p -> {
            Session s = gestures.get(displayEmitter.owner().get(p.thisObject));
            if (s == null || !s.active || !s.requested || s.ending) return;
            Point incoming = (Point) displaySize.invoke(p.args[0]);
            int rotation = rotation(s);
            Point mutable = (Point) fullSize.invoke(s.calculator);
            if (rotation == 0 && incoming.equals(s.portrait)) {
              mutable.set(s.portrait.x, s.portrait.y);
              managerSize.set(s.manager, new Point(s.portrait));
              s.rotated = true;
            } else if (incoming.equals(s.source)
                && (rotation == 0
                    || (!s.rotated
                        && rotation == s.startRotation
                        && SystemClock.uptimeMillis() - s.started < 2000))) {
              // During our pending orientation request only, compare real sizes in the native
              // collector.
              // Restore the virtual card size immediately; other rotation changes retain native
              // fallback.
              p.setObjectExtra("virtualSize", new Point(mutable));
              p.setObjectExtra("calculator", s.calculator);
              mutable.set(incoming.x, incoming.y);
            }
          },
          p -> {
            if (p.getObjectExtra("virtualSize") instanceof Point saved)
              ((Point) fullSize.invoke(p.getObjectExtra("calculator"))).set(saved.x, saved.y);
          });
      hook(create, null, p -> bindPlayer(p));
      hook(
          displacement,
          null,
          p -> {
            Session s = gestures.get(p.thisObject);
            if (s == null || !s.active || s.ending) return;
            var position = pointer(s, (MotionEvent) p.args[0]);
            double dy = position.displacement().y();
            int slop = touchSlop.getInt(s.gesture);
            if (slop > 0) dy = Math.min(0, dy + slop * (double) s.portrait.y / s.source.y);
            p.setResult(new PointF((float) position.displacement().x(), (float) dy));
          });
      hook(
          send,
          p -> {
            p.setObjectExtra("previous", dispatch.get());
            dispatch.set(gestures.get(p.thisObject));
          },
          p -> {
            Object previous = p.getObjectExtra("previous");
            if (previous instanceof Session s) dispatch.set(s);
            else dispatch.remove();
          });
      hook(
          obtain,
          null,
          p -> {
            Session s = dispatch.get();
            if (s == null || !s.active || s.ending) return;
            MotionEvent original = (MotionEvent) p.args[0],
                transformed = (MotionEvent) p.getResult();
            // Undo the native translation, then transform every pointer and historical sample.
            transformed.offsetLocation(
                original.getX() - transformed.getX(), original.getY() - transformed.getY());
            transformed.transform(matrix(s.coordinates.pointerTransform(rotation(s))));
          });
      hook(
          move,
          p -> {
            Session s = sessions.get(p.thisObject);
            if (s == null || !s.active || s.ending) return;
            verifyViewport(s);
            Object nativeTask = p.args[0];
            Rect mapped = new Rect();
            RectF rect = new RectF((Rect) target.invoke(nativeTask));
            s.toWindow.mapRect(rect);
            rect.roundOut(mapped);
            Object callback = spring.invoke(nativeTask);
            p.args[0] =
                copy.invoke(
                    nativeTask,
                    targetView.invoke(nativeTask),
                    radius.invoke(nativeTask),
                    mapped,
                    animation.invoke(nativeTask),
                    shrink.invoke(nativeTask),
                    alpha.invoke(nativeTask),
                    hidden.invoke(nativeTask),
                    callback == null ? null : callback(s, callback));
          },
          null);
      hook(
          threshold,
          p -> {
            Session s = sessions.get(p.thisObject);
            if (s == null || s.ending || s.toCards == null) return;
            RectF rect = new RectF((RectF) p.args[0]);
            s.toCards.mapRect(rect);
            p.args[0] = rect;
          },
          null);
      hook(
          finishController,
          p -> {
            Session s = sessions.get(p.thisObject);
            if (s != null) s.ending = true;
          },
          null);
      hook(invalidate, null, p -> end(sessions.remove(p.thisObject)));
      HookEntry.log("RECENTS_COORDINATES_READY experimental session bridge; no masking");
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
        || !(p.args[1] instanceof Point size)
        || size.x <= size.y
        || size.y <= 0) return;
    // A native catch/re-entry already owns a player; leave that gesture entirely native.
    if (appPlayer.get(p.thisObject) != null || sessions.containsKey(p.thisObject)) return;
    Object sourceTask = topTask.invoke(topTaskSource.get(p.thisObject));
    if (sourceTask == null
        || sourceTask.getClass().getField("taskId").getInt(sourceTask) != (Integer) p.args[0]
        || sourceTask.getClass().getField("displayId").getInt(sourceTask) != 0) return;
    Object sourceConfiguration = windowConfiguration.invoke(null, sourceTask);
    if (sourceConfiguration == null
        || (Integer) windowMode.invoke(sourceConfiguration) != 1
        || (Integer) activityType.invoke(sourceConfiguration) != 1) return;
    Object owner = null;
    synchronized (gestureOwners) {
      for (Object candidate : new ArrayList<>(gestureOwners.keySet()))
        if (action.get(candidate) == p.thisObject) {
          if (owner != null) throw new IllegalStateException("Ambiguous gesture owner");
          owner = candidate;
        }
    }
    if (owner == null) {
      // Recover an already-created session from the verified native start callbacks.
      // Compiler-merged
      // lambdas may store captures as Object; inspect values by actual type, never obfuscated
      // names.
      for (Object callback : (List<?>) startCallbacks.get(p.thisObject)) {
        for (Field captured : callback.getClass().getDeclaredFields()) {
          if (java.lang.reflect.Modifier.isStatic(captured.getModifiers())) continue;
          captured.setAccessible(true);
          Object value = captured.get(callback);
          if (!gestureClass.isInstance(value) || action.get(value) != p.thisObject) continue;
          if (owner != null && owner != value)
            throw new IllegalStateException("Ambiguous native callback owner");
          owner = value;
        }
      }
    }
    if (owner == null) {
      HookEntry.log("RECENTS_COORDINATES_SKIP no verified gesture owner");
      return;
    }
    if (owner == null
        || gestureDisplay.getInt(owner) != 0
        || Boolean.TRUE.equals(gestureDesktop.invoke(owner))) return;
    Context context = (Context) managerContext.get(p.thisObject);
    int rotation = context.getSystemService(DisplayManager.class).getDisplay(0).getRotation();
    if (rotation != 1 && rotation != 3) return;
    Object position = regionPosition.invoke(region.get(owner));
    RecentsGestureCoordinates.Region r =
        regionBottom.isInstance(position)
            ? RecentsGestureCoordinates.Region.BOTTOM
            : regionLeft.isInstance(position)
                ? RecentsGestureCoordinates.Region.LEFT
                : regionRight.isInstance(position) ? RecentsGestureCoordinates.Region.RIGHT : null;
    // Only the input region measured on this device is enabled for the first experiment.
    if (r != RecentsGestureCoordinates.Region.BOTTOM) return;
    PointF initial = new PointF((PointF) down.get(owner));
    Object calc = calculator.get(owner);
    var coordinates =
        new RecentsGestureCoordinates(rotation, size.x, size.y, initial.x, initial.y, r);
    end(sessions.remove(p.thisObject));
    Point actualSize = (Point) fullSize.invoke(calc);
    if (!actualSize.equals(size)) return;
    RectF beforeDrawn = new RectF((RectF) topDrawn.invoke(calc));
    Session session =
        new Session(
            p.thisObject,
            owner,
            calc,
            interactionOverlay.get(gestureInteraction.get(owner)),
            coordinates,
            size,
            rotation,
            beforeDrawn);
    p.args[1] = new Point(size);
    session.taskId = (Integer) p.args[0];
    sessions.put(p.thisObject, session);
    gestures.put(owner, session);
    // Request preparation first. Do not mutate the calculator or input while its actual
    // viewport/styler are still landscape. A virtual Point alone cannot change native layout.
    HookEntry.log("RECENTS_COORDINATES_BEGIN source=" + size + " rotation=" + rotation);
  }

  private void bindPlayer(ModernHook.Param p) throws Exception {
    Session s = sessions.get(p.thisObject);
    if (s == null || s.ending) return;
    int matching = 0, targetMode = (Integer) remoteMode.invoke(p.args[0]);
    Rect normalizedSource = null;
    for (Object app : (Object[]) remoteApps.invoke(p.args[0])) {
      if (app.getClass().getField("mode").getInt(app) != targetMode) continue;
      Object config = app.getClass().getField("windowConfiguration").get(app);
      int kind = (Integer) activityType.invoke(config);
      if (kind == 2 || kind == 3) continue;
      if ((Integer) windowMode.invoke(config) != 1)
        throw new IllegalStateException("Unsupported closing app mode");
      if (app.getClass().getField("taskId").getInt(app) != s.taskId)
        throw new IllegalStateException("Closing app identity changed");
      normalizedSource = new Rect((Rect) app.getClass().getField("sourceContainerBounds").get(app));
      matching++;
    }
    if (matching != 1)
      throw new IllegalStateException("Only one fullscreen closing app is supported");
    Object player = appPlayer.get(p.thisObject);
    if (player == null) return;
    Matrix toWindow = new Matrix((Matrix) playerBasis.invoke(player)), inverse = new Matrix();
    RectF bounds = new RectF(0, 0, s.portrait.x, s.portrait.y);
    if (!toWindow.rectStaysRect() || !toWindow.invert(inverse))
      throw new IllegalStateException("Unsupported player basis");
    toWindow.mapRect(bounds);
    if (Math.abs(bounds.left) > 1
        || Math.abs(bounds.top) > 1
        || normalizedSource == null
        || normalizedSource.left != 0
        || normalizedSource.top != 0
        || Math.abs(bounds.right - normalizedSource.width()) > 1
        || Math.abs(bounds.bottom - normalizedSource.height()) > 1)
      throw new IllegalStateException("Player basis does not match source geometry");
    verifyViewport(s);
    // The source/crop may already have been normalized by Shell. Rebase from the actual
    // native player's current rectangle, never from a pending rectangle computed in old axes.
    RectF current = new RectF((RectF) playerCurrentRect.invoke(player));
    if (current.isEmpty()) throw new IllegalStateException("Empty native player pose");
    RectF inCards = new RectF(current);
    inverse.mapRect(inCards);
    // Mark ownership before the first mutation so partial failure still restores this session.
    s.toWindow = toWindow;
    s.toCards = inverse;
    s.active = true;
    ((RectF) topDrawn.invoke(s.calculator)).set(inCards);
    ((Point) fullSize.invoke(s.calculator)).set(s.portrait.x, s.portrait.y);
    managerSize.set(s.manager, new Point(s.portrait));
    finalPosition.set(s.manager, current);
    HookEntry.log("RECENTS_COORDINATES_BOUND verified source/card transform");
  }

  private Object callback(Session s, Object nativeCallback) {
    return Proxy.newProxyInstance(
        loader,
        new Class<?>[] {function1},
        (proxy, method, args) -> {
          if (method.getName().equals("invoke")
              && args != null
              && args.length == 1
              && args[0] instanceof RectF rect) {
            try {
              if (Looper.myLooper() != Looper.getMainLooper())
                throw new IllegalStateException("Spring callback is not on main thread");
              RectF converted = new RectF(rect);
              s.toCards.mapRect(converted);
              return invoke.invoke(nativeCallback, converted);
            } catch (Throwable error) {
              if (Looper.myLooper() == Looper.getMainLooper()) fail(error);
              else new android.os.Handler(Looper.getMainLooper()).post(() -> fail(error));
              return unit;
            }
          }
          return switch (method.getName()) {
            case "toString" -> "RecentsCoordinateCallback";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length == 1 && proxy == args[0];
            default -> throw new UnsupportedOperationException("Unknown callback method");
          };
        });
  }

  private RecentsGestureCoordinates.Position pointer(Session s, MotionEvent event)
      throws Exception {
    return s.coordinates.pointer(rotation(s), event.getX(), event.getY());
  }

  private int rotation(Session s) throws Exception {
    return ((Context) managerContext.get(s.manager))
        .getSystemService(DisplayManager.class)
        .getDisplay(0)
        .getRotation();
  }

  private void verifyViewport(Session s) throws Exception {
    Object styler = calculatorStyler.invoke(s.calculator);
    RectF bounds = (RectF) styleBounds.invoke(stylerData.invoke(styler));
    View root = (View) overlayRoot.get(s.overlay);
    if (root == null || !root.isAttachedToWindow())
      throw new IllegalStateException("Owned gesture viewport is not attached");
    if (root.getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT)
      throw new IllegalStateException("Owned gesture resources are not portrait");
    RecentsViewportContract.verify(
        s.portrait.x,
        s.portrait.y,
        root.getWidth(),
        root.getHeight(),
        bounds.left,
        bounds.top,
        bounds.width(),
        bounds.height());
  }

  private void prepareWindows() throws Exception {
    for (Session s : new ArrayList<>(sessions.values())) {
      Object owner = s.overlay;
      if (s.ending
          || (Integer) overlayDisplay.invoke(owner) != 0
          || Boolean.TRUE.equals(overlayDesktop.invoke(owner))) continue;
      if (overlayRoot.get(owner) instanceof View view
          && view.isAttachedToWindow()
          && view.getLayoutParams() instanceof WindowManager.LayoutParams p
          && validWindow(p)) {
        view.getContext()
            .getSystemService(WindowManager.class)
            .updateViewLayout(view, portraitParams(view, p));
        HookEntry.log(
            "RECENTS_COORDINATES_WINDOW viewport="
                + view.getWidth()
                + "x"
                + view.getHeight()
                + " orientationRequested=portrait");
      }
    }
  }

  private WindowManager.LayoutParams portraitParams(
      View view, WindowManager.LayoutParams nativeParams) {
    windows.computeIfAbsent(
        view,
        key -> {
          var saved = new WindowManager.LayoutParams();
          saved.copyFrom(nativeParams);
          return saved;
        });
    var applied = new WindowManager.LayoutParams();
    applied.copyFrom(nativeParams);
    applied.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    applied.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
    return applied;
  }

  private static boolean validWindow(WindowManager.LayoutParams p) {
    return p.type == 2632 && "RecentsTransitionOverlay".contentEquals(p.getTitle());
  }

  private void end(Session s) {
    if (s == null) return;
    gestures.remove(s.gesture);
    s.ending = true;
    if (sessions.isEmpty()) restoreWindows();
  }

  private void restoreWindows() {
    for (var entry : new ArrayList<>(windows.entrySet()))
      try {
        View view = entry.getKey();
        if (!view.isAttachedToWindow()
            || !(view.getLayoutParams() instanceof WindowManager.LayoutParams current)) continue;
        var restored = new WindowManager.LayoutParams();
        restored.copyFrom(current);
        restored.screenOrientation = entry.getValue().screenOrientation;
        restored.rotationAnimation = entry.getValue().rotationAnimation;
        view.getContext().getSystemService(WindowManager.class).updateViewLayout(view, restored);
      } catch (Throwable error) {
        HookEntry.log("RECENTS_COORDINATES_RESTORE_ERROR " + error);
      }
    windows.clear();
  }

  void setEnabled(boolean value) {
    enabled = value && !failed && !hooks.isEmpty();
    if (!enabled) cancelSessions();
  }

  void uninstall() {
    enabled = false;
    cancelSessions();
    for (var hook : hooks)
      try {
        hook.unhook();
      } catch (Throwable ignored) {
      }
    hooks.clear();
    gestureOwners.clear();
    dispatch.remove();
  }

  private void cancelSessions() {
    // Native finish can synchronously invalidate/remove the session. Keep the same snapshot
    // for restoration rather than reading the now-empty map a second time.
    List<Session> cancelled = new ArrayList<>(sessions.values());
    for (Session s : cancelled)
      try {
        if (!s.ending) finishController.invoke(s.manager, false, false);
      } catch (Throwable error) {
        HookEntry.log("RECENTS_COORDINATES_CANCEL_ERROR " + error);
      }
    for (Session s : cancelled)
      try {
        if (s.active && rotation(s) == s.startRotation) {
          ((Point) fullSize.invoke(s.calculator)).set(s.source.x, s.source.y);
          ((RectF) topDrawn.invoke(s.calculator)).set(s.originalDrawn);
          managerSize.set(s.manager, new Point(s.source));
        }
      } catch (Throwable error) {
        HookEntry.log("RECENTS_COORDINATES_INPUT_RESTORE_ERROR " + error);
      }
    sessions.clear();
    gestures.clear();
    restoreWindows();
  }

  private void fail(Throwable error) {
    if (failed) return;
    failed = true;
    uninstall();
    failure.accept(error);
    HookEntry.log("RECENTS_COORDINATES_DISABLED " + error);
  }

  private interface Action {
    void run(ModernHook.Param p) throws Exception;
  }

  private void hook(Method method, Action before, Action after) {
    hooks.add(
        ModernHook.hookMethod(
            method,
            new ModernHook.Callback() {
              @Override
              protected void beforeHookedMethod(ModernHook.Param p) {
                if (!enabled
                    || failed
                    || Looper.myLooper() != Looper.getMainLooper()
                    || before == null) return;
                Object[] original = p.args.clone();
                try {
                  before.run(p);
                  p.setObjectExtra("coordinateEntered", true);
                } catch (Throwable error) {
                  System.arraycopy(original, 0, p.args, 0, original.length);
                  fail(error);
                }
              }

              @Override
              protected void afterHookedMethod(ModernHook.Param p) {
                if (after == null || Looper.myLooper() != Looper.getMainLooper()) return;
                boolean paired = Boolean.TRUE.equals(p.getObjectExtra("coordinateEntered"));
                if (!paired && (!enabled || failed || p.hasThrowable())) return;
                try {
                  after.run(p);
                } catch (Throwable error) {
                  fail(error);
                }
              }
            }));
  }

  private Class<?> type(String name) throws ClassNotFoundException {
    return Class.forName(name, false, loader);
  }

  private static Field field(Class<?> owner, String name, Class<?> expected) throws Exception {
    Field f = owner.getDeclaredField(name);
    if (f.getType() != expected) throw new IllegalStateException("Field contract changed: " + name);
    f.setAccessible(true);
    return f;
  }

  private static Method method(Class<?> owner, String name, Class<?> returns, Class<?>... args)
      throws Exception {
    Method m = owner.getDeclaredMethod(name, args);
    if (m.getReturnType() != returns)
      throw new IllegalStateException("Method contract changed: " + name);
    m.setAccessible(true);
    return m;
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
}
