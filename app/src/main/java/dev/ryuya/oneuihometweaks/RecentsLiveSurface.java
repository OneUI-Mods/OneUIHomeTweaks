package dev.ryuya.oneuihometweaks;

import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Session-owned live surface. Native card drawing supplies clipping and geometry. */
final class RecentsLiveSurface {
  private final Runnable unsupported;
  private final RecentsLiveBlur blur;
  private final Method tiltPath, tilt;
  private final Class<?> taskType;
  private final Method taskId,
      taskScene,
      paints,
      sceneData,
      bitmap,
      snapshotScale,
      snapshotRotation,
      hasTaskId,
      realSnapshot,
      locked;
  private int expectedTaskId;
  private final Method updaterTargets, recent, apps, mode, playerBasis, releaseCheck;
  private final Field updater, player;
  private final Constructor<?> transaction, applier;
  private final Method forSurface,
      setMatrix,
      setCrop,
      setRadius,
      setAlpha,
      show,
      getTransaction,
      schedule;
  private final Field rawTransaction;
  private final Method relativeLayer;
  private final ModernHook.Handle drawHook;
  private View root, scene;
  private SurfaceControl appLeash, recentsLeash;
  private Rect source;
  private Matrix viewportToLeash;
  private RecentsCardSpace.Space sourceSpace;
  private Object syncApplier;

  private enum Draw {
    LIVE,
    SNAPSHOT,
    INVALID
  }

  private boolean updating, logged, visualSuspended;
  private String lastGeometry;
  private int viewportWidth, viewportHeight, viewportRotation, perspectiveSamples;
  private final ViewTreeObserver.OnPreDrawListener preDraw = this::validateScene;

  private boolean validateScene() {
    try {
      if (root == null) return true;
      if (!root.isAttachedToWindow()
          || root.getWidth() != viewportWidth
          || root.getHeight() != viewportHeight
          || root.getDisplay() == null
          || root.getDisplay().getRotation() != viewportRotation
          || appLeash == null
          || !appLeash.isValid()
          || recentsLeash == null
          || !recentsLeash.isValid()) {
        unsupported.run();
        return true;
      }
      if (scene == null
          || !scene.isAttachedToWindow()
          || !scene.isShown()
          || scene.getRootView() != root
          || !Boolean.TRUE.equals(hasTaskId.invoke(scene, expectedTaskId))) {
        suspendVisual("RECENTS_LIVE_OFFSCREEN app continues drawing");
        scene = find(root, expectedTaskId, 0);
        if (scene != null) scene.invalidate();
      }
    } catch (Throwable error) {
      HookEntry.log("RECENTS_LIVE_VALIDATION_ERROR " + error);
      unsupported.run();
    }
    return true;
  }

  private void suspendVisual(String reason) throws Exception {
    if (visualSuspended) return;
    Object tx = transaction.newInstance(), props = forSurface.invoke(tx, appLeash);
    blur.restore((SurfaceControl.Transaction) rawTransaction.get(getTransaction.invoke(tx)));
    setAlpha.invoke(props, 0f);
    schedule.invoke(syncApplier, tx);
    visualSuspended = true;
    lastGeometry = null;
    HookEntry.log(reason);
  }

  private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);

  RecentsLiveSurface(ClassLoader loader, Runnable unsupported) throws Exception {
    this.unsupported = unsupported;
    blur = new RecentsLiveBlur(loader);
    taskType =
        Class.forName("com.honeyspace.ui.honeypots.tasklist.presentation.TaskView", false, loader);
    taskId = taskType.getMethod("getFirstTaskId");
    taskScene = taskType.getMethod("getTaskSceneView");
    Class<?> sceneType =
        Class.forName(
            HookCompatibility.taskSceneView(android.os.Build.VERSION.SDK_INT), false, loader);
    paints = sceneType.getMethod("getForegroundPaints");
    hasTaskId = sceneType.getMethod("hasTaskId", int.class);
    tiltPath =
        sceneType.getDeclaredMethod(
            "createTiltPath", RectF.class, float[].class, PointF.class, double.class);
    tiltPath.setAccessible(true);
    tilt = sceneType.getMethod("getTiltRadian");
    sceneData = sceneType.getMethod("getTaskSceneData");
    Class<?> dataType =
        Class.forName(
            HookCompatibility.taskSceneData(android.os.Build.VERSION.SDK_INT), false, loader);
    bitmap = dataType.getMethod("getThumbnail");
    snapshotScale = dataType.getMethod("getScale");
    snapshotRotation = dataType.getMethod("getRotation");
    realSnapshot = dataType.getMethod("isRealSnapshot");
    locked = dataType.getMethod("getAppLocked");
    Class<?> manager =
        Class.forName(
            "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager", false, loader);
    updater = manager.getDeclaredField("leashUpdater");
    updater.setAccessible(true);
    player = manager.getDeclaredField("appPlayer");
    player.setAccessible(true);
    Class<?> updaterType =
        Class.forName("com.honeyspace.gesture.recentsanimation.LeashUpdater", false, loader);
    updaterTargets = updaterType.getMethod("getTargets");
    Class<?> targets =
        Class.forName("com.honeyspace.sdk.transition.TransitionTargets", false, loader);
    recent = targets.getMethod("getRecentTarget");
    apps = targets.getMethod("getApps");
    mode = targets.getMethod("getTargetMode");
    releaseCheck =
        targets.getMethod(
            "addReleaseCheck",
            Class.forName(
                "com.honeyspace.sdk.transition.TransitionTargets$ReleaseCheck", false, loader));
    playerBasis =
        Class.forName("com.honeyspace.transition.anim.floating.Player", false, loader)
            .getMethod("getHomeToWindowMatrix");
    Class<?> tx =
        Class.forName("com.honeyspace.transition.utils.SurfaceTransaction", false, loader);
    Class<?> props =
        Class.forName(
            "com.honeyspace.transition.utils.SurfaceTransaction$SurfaceProperties", false, loader);
    transaction = tx.getConstructor();
    forSurface = tx.getMethod("forSurface", SurfaceControl.class);
    setMatrix = props.getMethod("setMatrix", Matrix.class);
    setCrop = props.getMethod("setWindowCrop", Rect.class);
    setRadius = props.getMethod("setCornerRadius", float.class);
    setAlpha = props.getMethod("setAlpha", float.class);
    show = props.getMethod("setShow");
    getTransaction = tx.getMethod("getTransaction");
    rawTransaction =
        Class.forName("com.android.systemui.shared.launcher.ScTransactionCompat", false, loader)
            .getField("mTransaction");
    relativeLayer =
        SurfaceControl.Transaction.class.getMethod(
            "setRelativeLayer", SurfaceControl.class, SurfaceControl.class, int.class);
    Class<?> applierType =
        Class.forName("com.honeyspace.transition.utils.SurfaceTransactionApplier", false, loader);
    applier = applierType.getConstructor(View.class);
    schedule = applierType.getMethod("scheduleApply", tx);
    clear.setBlendMode(BlendMode.CLEAR);
    Method draw =
        sceneType.getMethod("draw", Canvas.class, List.class, List.class, List.class, PointF.class);
    drawHook =
        ModernHook.hookMethod(
            draw,
            new ModernHook.Callback() {
              @Override
              protected void beforeHookedMethod(ModernHook.Param p) {
                if (scene == null || p.thisObject != scene || updating) return;
                try {
                  if (p.args[1] != paints.invoke(scene)) return;
                  updating = true;
                  Draw result = render(p.args);
                  if (result == Draw.INVALID) {
                    unsupported.run();
                    return;
                  }
                  if (result == Draw.SNAPSHOT) return;
                  // The original path retains Samsung's rounded corners, tilt and style-specific
                  // clipping.
                  // Clear through the Recents window to its owned live app underneath.
                  p.args[1] = List.of(clear);
                } catch (Throwable error) {
                  HookEntry.log("RECENTS_LIVE_SURFACE_ERROR " + error);
                  unsupported.run();
                } finally {
                  updating = false;
                }
              }
            });
  }

  boolean bind(Object manager, View target, int closingId) throws Exception {
    clear();
    if (target == null
        || target.getDisplay() == null
        || target.getDisplay().getDisplayId() != 0
        || target.getWidth() <= 0
        || target.getHeight() <= 0) return false;
    Object nativePlayer = player.get(manager);
    if (nativePlayer == null) return false;
    Object u = updater.get(manager);
    if (u == null) return false;
    Object targets = updaterTargets.invoke(u);
    Object r = recent.invoke(targets);
    if (r == null) return false;
    Object closing = null;
    int closingMode = (Integer) mode.invoke(targets);
    for (Object a : (Object[]) apps.invoke(targets)) {
      Class<?> c = a.getClass();
      if (c.getField("mode").getInt(a) != closingMode) continue;
      if (closing != null || c.getField("taskId").getInt(a) != closingId) return false;
      closing = a;
    }
    if (closing == null) return false;
    source = new Rect((Rect) closing.getClass().getField("sourceContainerBounds").get(closing));
    viewportToLeash = new Matrix((Matrix) playerBasis.invoke(nativePlayer));
    float[] basis = new float[9];
    viewportToLeash.getValues(basis);
    sourceSpace =
        RecentsLeashSpace.resolve(
            new RecentsCardSpace.Space(
                target.getWidth(), target.getHeight(), target.getDisplay().getRotation()),
            source.width(),
            source.height(),
            basis);
    if (source.left != 0 || source.top != 0) return false;
    appLeash = (SurfaceControl) closing.getClass().getField("leash").get(closing);
    recentsLeash = (SurfaceControl) r.getClass().getField("leash").get(r);
    scene = find(target, closingId, 0);
    if (scene == null || !appLeash.isValid() || !recentsLeash.isValid()) {
      clear();
      return false;
    }
    List<?> sourceData = (List<?>) sceneData.invoke(scene);
    if (sourceData.size() != 1
        || !Boolean.TRUE.equals(realSnapshot.invoke(sourceData.get(0)))
        || Boolean.TRUE.equals(locked.invoke(sourceData.get(0)))
        || !(bitmap.invoke(sourceData.get(0)) instanceof Bitmap)) {
      clear();
      return false;
    }
    expectedTaskId = closingId;
    root = target;
    viewportWidth = root.getWidth();
    viewportHeight = root.getHeight();
    viewportRotation = root.getDisplay().getRotation();
    root.getViewTreeObserver().addOnPreDrawListener(preDraw);
    if (!blur.bind(root, appLeash)) {
      clear();
      return false;
    }
    syncApplier = applier.newInstance(root);
    releaseCheck.invoke(targets, syncApplier);
    root.invalidate();
    scene.invalidate();
    HookEntry.log(
        "RECENTS_LIVE_SURFACE_BOUND viewport="
            + viewportWidth
            + "x"
            + viewportHeight
            + " rotation="
            + viewportRotation
            + " source="
            + source.width()
            + "x"
            + source.height()
            + " basis="
            + viewportToLeash);
    return true;
  }

  private View find(View v, int id, int depth) throws Exception {
    if (depth > 32 || v.getVisibility() != View.VISIBLE) return null;
    if (taskType.isInstance(v) && (Integer) taskId.invoke(v) == id)
      return (View) taskScene.invoke(v);
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        View found = find(g.getChildAt(i), id, depth + 1);
        if (found != null) return found;
      }
    return null;
  }

  private Draw render(Object[] args) throws Exception {
    List<?> values = (List<?>) args[1];
    if (!root.isAttachedToWindow()
        || !scene.isShown()
        || scene.getRootView() != root
        || !appLeash.isValid()
        || !recentsLeash.isValid()
        || values.size() != 1
        || !(values.get(0) instanceof Paint paint)
        || paint.getShader() == null) return Draw.INVALID;
    List<?> data = (List<?>) sceneData.invoke(scene);
    if (!(Boolean) hasTaskId.invoke(scene, expectedTaskId)
        || data.size() != 1
        || !Boolean.TRUE.equals(realSnapshot.invoke(data.get(0)))
        || Boolean.TRUE.equals(locked.invoke(data.get(0)))
        || !(bitmap.invoke(data.get(0)) instanceof Bitmap image)) return Draw.INVALID;
    float scale = ((Number) snapshotScale.invoke(data.get(0))).floatValue();
    Matrix shader = new Matrix();
    paint.getShader().getLocalMatrix(shader);
    Matrix global = new Matrix();
    scene.transformMatrixToGlobal(global);
    float[] sh = new float[9], gl = new float[9];
    shader.getValues(sh);
    global.getValues(gl);
    RecentsAffine.Transform transform;
    try {
      transform =
          RecentsLiveGeometry.compose(
              sourceSpace,
              image.getWidth(),
              image.getHeight(),
              (Integer) snapshotRotation.invoke(data.get(0)),
              scale,
              sh,
              gl);
    } catch (RecentsLiveGeometry.PerspectiveCard unsupportedGeometry) {
      if (BuildConfig.RECENTS_LIVE_TRACE && perspectiveSamples++ < 48)
        HookEntry.log(
            "RECENTS_LIVE_PROJECTIVE_MATRIX shader="
                + java.util.Arrays.toString(sh)
                + " global="
                + java.util.Arrays.toString(gl));
      suspendVisual("RECENTS_LIVE_NATIVE_PERSPECTIVE app continues drawing");
      return Draw.SNAPSHOT;
    }
    Matrix output = new Matrix();
    output.setValues(
        new float[] {
          (float) transform.a(),
          (float) transform.c(),
          (float) transform.tx(),
          (float) transform.b(),
          (float) transform.d(),
          (float) transform.ty(),
          0,
          0,
          1
        });
    List<?> bounds = (List<?>) args[2], corners = (List<?>) args[3];
    if (bounds.size() != 1 || corners.size() != 1) return Draw.INVALID;
    Path path =
        new Path(
            (Path)
                tiltPath.invoke(scene, bounds.get(0), corners.get(0), args[4], tilt.invoke(scene)));
    String geometry =
        output.toString() + bounds.get(0) + java.util.Arrays.toString((float[]) corners.get(0));
    if (!blur.valid()) return Draw.INVALID;
    if (geometry.equals(lastGeometry)) return Draw.LIVE;
    Matrix inverse = new Matrix();
    if (!output.invert(inverse)) return Draw.INVALID;
    Matrix localToSource = new Matrix();
    localToSource.setConcat(inverse, global);
    Path sourceClip = new Path(path);
    sourceClip.transform(localToSource);
    RectF cropBounds = new RectF();
    sourceClip.computeBounds(cropBounds, true);
    if (!cropBounds.intersect(new RectF(source))) return Draw.INVALID;
    Rect sourceCrop =
        new Rect(
            Math.max(source.left, (int) Math.floor(cropBounds.left)),
            Math.max(source.top, (int) Math.floor(cropBounds.top)),
            Math.min(source.right, (int) Math.ceil(cropBounds.right)),
            Math.min(source.bottom, (int) Math.ceil(cropBounds.bottom)));
    if (!(corners.get(0) instanceof float[] rr) || rr.length != 8) return Draw.INVALID;
    for (float corner : rr)
      if (!Float.isFinite(corner) || Math.abs(corner - rr[0]) > .1) return Draw.INVALID;
    Matrix relative = new Matrix(), inverseGlobal = new Matrix();
    if (!global.invert(inverseGlobal)) return Draw.INVALID;
    relative.setConcat(inverseGlobal, output);
    float[] relativeValues = new float[9];
    relative.getValues(relativeValues);
    double contentScale =
        Math.sqrt(
            Math.abs(
                relativeValues[0] * relativeValues[4] - relativeValues[1] * relativeValues[3]));
    if (!Double.isFinite(contentScale) || contentScale <= 0) return Draw.INVALID;
    Object tx = transaction.newInstance();
    Object props = forSurface.invoke(tx, appLeash);
    Matrix leashMatrix = new Matrix();
    leashMatrix.setConcat(viewportToLeash, output);
    setMatrix.invoke(props, leashMatrix);
    setCrop.invoke(props, sourceCrop);
    setRadius.invoke(props, (float) (rr[0] / contentScale));
    setAlpha.invoke(props, 1f);
    show.invoke(props);
    SurfaceControl.Transaction raw =
        (SurfaceControl.Transaction) rawTransaction.get(getTransaction.invoke(tx));
    relativeLayer.invoke(raw, appLeash, recentsLeash, -1);
    blur.apply(raw);
    schedule.invoke(syncApplier, tx);
    lastGeometry = geometry;
    if (visualSuspended) {
      visualSuspended = false;
      HookEntry.log("RECENTS_LIVE_RESUMED native geometry available");
    }
    if (!logged) {
      logged = true;
      HookEntry.log("RECENTS_LIVE_SURFACE_DRAW matrix=" + output);
    }
    return Draw.LIVE;
  }

  void clear() {
    if (visualSuspended && appLeash != null && appLeash.isValid()) {
      try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
        tx.setAlpha(appLeash, 1f).apply();
      } catch (Throwable error) {
        HookEntry.log("RECENTS_LIVE_ALPHA_RESTORE_ERROR " + error);
      }
    }
    visualSuspended = false;
    if (root != null && root.getViewTreeObserver().isAlive())
      root.getViewTreeObserver().removeOnPreDrawListener(preDraw);
    blur.clear();
    if (root != null) root.invalidate();
    root = scene = null;
    appLeash = recentsLeash = null;
    source = null;
    viewportToLeash = null;
    sourceSpace = null;
    syncApplier = null;
    logged = false;
    lastGeometry = null;
  }

  void uninstall() {
    clear();
    drawHook.unhook();
  }
}
