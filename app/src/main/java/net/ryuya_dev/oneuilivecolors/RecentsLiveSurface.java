package net.ryuya_dev.oneuilivecolors;

import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Experimental, session-owned live surface. Native card drawing supplies clipping and geometry. */
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
      hasTaskId,
      realSnapshot,
      locked;
  private int expectedTaskId;
  private final Method updaterTargets, recent, apps, mode, playerBasis, releaseCheck;
  private final Field updater, player;
  private final Constructor<?> transaction, applier;
  private final Method forSurface, setMatrix, setCrop, setAlpha, show, getTransaction, schedule;
  private final Field rawTransaction;
  private final Method relativeLayer;
  private final ModernHook.Handle drawHook;
  private View root, scene;
  private SurfaceControl appLeash, recentsLeash;
  private Rect source;
  private Matrix viewportToLeash;
  private Object syncApplier;
  private boolean updating, logged;
  private String lastGeometry;
  private int viewportWidth, viewportHeight;
  private final ViewTreeObserver.OnPreDrawListener preDraw = this::validateScene;

  private boolean validateScene() {
    try {
      if (root != null
          && (scene == null
              || !scene.isAttachedToWindow()
              || scene.getRootView() != root
              || root.getWidth() != viewportWidth
              || root.getHeight() != viewportHeight
              || !Boolean.TRUE.equals(hasTaskId.invoke(scene, expectedTaskId)))) unsupported.run();
    } catch (Throwable error) {
      unsupported.run();
    }
    return true;
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
        Class.forName("com.honeyspace.ui.common.taskScene.TaskSceneView", false, loader);
    paints = sceneType.getMethod("getForegroundPaints");
    hasTaskId = sceneType.getMethod("hasTaskId", int.class);
    tiltPath =
        sceneType.getDeclaredMethod(
            "createTiltPath", RectF.class, float[].class, PointF.class, double.class);
    tiltPath.setAccessible(true);
    tilt = sceneType.getMethod("getTiltRadian");
    sceneData = sceneType.getMethod("getTaskSceneData");
    Class<?> dataType =
        Class.forName("com.honeyspace.ui.common.taskScene.TaskSceneData", false, loader);
    bitmap = dataType.getMethod("getThumbnail");
    snapshotScale = dataType.getMethod("getScale");
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
                  if (!render(p.args)) {
                    unsupported.run();
                    return;
                  }
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
        || target.getDisplay().getRotation() != Surface.ROTATION_0
        || target.getWidth() >= target.getHeight()) return false;
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
    Object config = closing.getClass().getField("windowConfiguration").get(closing);
    int rotation = (Integer) config.getClass().getMethod("getRotation").invoke(config);
    var from =
        new RecentsCardSpace.Space(
            target.getWidth(), target.getHeight(), target.getDisplay().getRotation());
    var to = new RecentsCardSpace.Space(source.width(), source.height(), rotation);
    var expected = RecentsCardSpace.between(from, to);
    viewportToLeash = new Matrix((Matrix) playerBasis.invoke(nativePlayer));
    float[] basis = new float[9];
    viewportToLeash.getValues(basis);
    double[] measured = {
      expected.a(), expected.c(), expected.tx(), expected.b(), expected.d(), expected.ty(), 0, 0, 1
    };
    for (int i = 0; i < 9; i++) if (Math.abs(basis[i] - measured[i]) > .001) return false;
    if (source.left != 0 || source.top != 0) return false;
    appLeash = (SurfaceControl) closing.getClass().getField("leash").get(closing);
    recentsLeash = (SurfaceControl) r.getClass().getField("leash").get(r);
    scene = find(target, closingId, 0);
    if (scene == null || !appLeash.isValid() || !recentsLeash.isValid()) {
      clear();
      return false;
    }
    expectedTaskId = closingId;
    root = target;
    viewportWidth = root.getWidth();
    viewportHeight = root.getHeight();
    root.getViewTreeObserver().addOnPreDrawListener(preDraw);
    if (!blur.bind(root)) {
      clear();
      return false;
    }
    syncApplier = applier.newInstance(root);
    releaseCheck.invoke(targets, syncApplier);
    root.invalidate();
    scene.invalidate();
    HookEntry.log(
        "RECENTS_LIVE_SURFACE_BOUND source="
            + source.width()
            + "x"
            + source.height()
            + " basis="
            + viewportToLeash);
    return true;
  }

  private View find(View v, int id, int depth) throws Exception {
    if (depth > 32) return null;
    if (taskType.isInstance(v) && (Integer) taskId.invoke(v) == id)
      return (View) taskScene.invoke(v);
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        View found = find(g.getChildAt(i), id, depth + 1);
        if (found != null) return found;
      }
    return null;
  }

  private boolean render(Object[] args) throws Exception {
    List<?> values = (List<?>) args[1];
    if (!root.isAttachedToWindow()
        || !scene.isShown()
        || scene.getRootView() != root
        || !appLeash.isValid()
        || !recentsLeash.isValid()
        || values.size() != 1
        || !(values.get(0) instanceof Paint paint)
        || paint.getShader() == null) return false;
    List<?> data = (List<?>) sceneData.invoke(scene);
    if (!(Boolean) hasTaskId.invoke(scene, expectedTaskId)
        || data.size() != 1
        || !Boolean.TRUE.equals(realSnapshot.invoke(data.get(0)))
        || Boolean.TRUE.equals(locked.invoke(data.get(0)))
        || !(bitmap.invoke(data.get(0)) instanceof Bitmap image)) return false;
    float scale = ((Number) snapshotScale.invoke(data.get(0))).floatValue();
    Matrix shader = new Matrix();
    paint.getShader().getLocalMatrix(shader);
    Matrix global = new Matrix();
    scene.transformMatrixToGlobal(global);
    float[] sh = new float[9], gl = new float[9];
    shader.getValues(sh);
    global.getValues(gl);
    var transform =
        RecentsLiveGeometry.compose(
            source.width(), source.height(), image.getWidth(), image.getHeight(), scale, sh, gl);
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
    if (bounds.size() != 1 || corners.size() != 1) return false;
    Path path =
        new Path(
            (Path)
                tiltPath.invoke(scene, bounds.get(0), corners.get(0), args[4], tilt.invoke(scene)));
    String geometry =
        output.toString() + bounds.get(0) + java.util.Arrays.toString((float[]) corners.get(0));
    if (!blur.exclude(scene, path, geometry)) return false;
    if (geometry.equals(lastGeometry)) return true;
    Object tx = transaction.newInstance();
    Object props = forSurface.invoke(tx, appLeash);
    Matrix leashMatrix = new Matrix();
    leashMatrix.setConcat(viewportToLeash, output);
    setMatrix.invoke(props, leashMatrix);
    setCrop.invoke(props, source);
    setAlpha.invoke(props, 1f);
    show.invoke(props);
    SurfaceControl.Transaction raw =
        (SurfaceControl.Transaction) rawTransaction.get(getTransaction.invoke(tx));
    relativeLayer.invoke(raw, appLeash, recentsLeash, -1);
    schedule.invoke(syncApplier, tx);
    lastGeometry = geometry;
    if (!logged) {
      logged = true;
      HookEntry.log("RECENTS_LIVE_SURFACE_DRAW matrix=" + output);
    }
    return true;
  }

  void clear() {
    if (root != null && root.getViewTreeObserver().isAlive())
      root.getViewTreeObserver().removeOnPreDrawListener(preDraw);
    blur.clear();
    if (root != null) root.invalidate();
    root = scene = null;
    appLeash = recentsLeash = null;
    source = null;
    viewportToLeash = null;
    syncApplier = null;
    logged = false;
    lastGeometry = null;
  }

  void uninstall() {
    clear();
    drawHook.unhook();
  }
}
