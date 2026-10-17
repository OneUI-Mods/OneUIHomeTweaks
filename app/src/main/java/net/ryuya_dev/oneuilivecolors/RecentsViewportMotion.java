package net.ryuya_dev.oneuilivecolors;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

/** One continuous portrait pose shared by the live app and the replacement gesture thumbnail. */
final class RecentsViewportMotion {
  private final View root, scene, ghostList;
  private final ViewGroup overlay;
  private final Rect source;
  private final RecentsCardSpace.Space sourceSpace;
  private final Matrix windowBasis, portraitToOverlay;
  private final SurfaceControl leash;
  private final Consumer<Throwable> failure;
  private final Runnable ready;
  private final int taskId;
  private final Method data, paints, thumbnail, scale, rotation, real, locked, hasTask;
  private final Method state, ratio, invoke, bounds, radii, effect, tilt, path;
  private final Constructor<?> transaction;
  private final Method forSurface, setMatrix, setCrop, setRadius, setAlpha, show, schedule;
  private final Object applier;
  private final RecentsChase chase;
  private RenderNode node, listNode;
  private View list;
  private Matrix listGlobal;
  private boolean groupDrawLogged;
  private Matrix goal, sceneGlobal, portraitMatrix, surfaceMatrix;
  private Rect crop;
  private float corner;
  private double[] target;
  private long previousFrame, startedFrame;
  private boolean stopped, handedOff, deferred, active;
  private final Choreographer.FrameCallback frame = this::frame;

  RecentsViewportMotion(
      ClassLoader loader,
      View root,
      View scene,
      ViewGroup overlay,
      View ghostList,
      int taskId,
      Rect source,
      RecentsCardSpace.Space sourceSpace,
      Matrix basis,
      SurfaceControl leash,
      Object targets,
      Matrix nativeMatrix,
      Rect nativeCrop,
      float nativeRadius,
      Runnable ready,
      Consumer<Throwable> failure)
      throws Exception {
    this.root = root;
    this.scene = scene;
    this.overlay = overlay;
    this.ghostList = ghostList;
    this.taskId = taskId;
    this.source = new Rect(source);
    this.sourceSpace = sourceSpace;
    windowBasis = new Matrix(basis);
    this.leash = leash;
    this.ready = ready;
    this.failure = failure;
    var from =
        new RecentsCardSpace.Space(
            root.getWidth(), root.getHeight(), root.getDisplay().getRotation());
    var to =
        new RecentsCardSpace.Space(
            overlay.getWidth(), overlay.getHeight(), overlay.getDisplay().getRotation());
    portraitToOverlay = matrix(RecentsCardSpace.between(from, to));
    Class<?> cls = Class.forName("com.honeyspace.ui.common.taskScene.TaskSceneView", false, loader);
    data = cls.getMethod("getTaskSceneData");
    paints = cls.getMethod("getForegroundPaints");
    hasTask = cls.getMethod("hasTaskId", int.class);
    state = cls.getMethod("getSceneStateInfo");
    ratio = cls.getMethod("getDrawingRatio");
    radii = cls.getMethod("getForegroundCornerRadii");
    effect = cls.getMethod("getViewScaleEffect");
    tilt = cls.getMethod("getTiltRadian");
    invoke = Class.forName("kotlin.jvm.functions.Function0", false, loader).getMethod("invoke");
    bounds = state.getReturnType().getMethod("getDestCropBounds", float.class);
    path =
        cls.getDeclaredMethod(
            "createTiltPath", RectF.class, float[].class, PointF.class, double.class);
    path.setAccessible(true);
    Class<?> d = Class.forName("com.honeyspace.ui.common.taskScene.TaskSceneData", false, loader);
    thumbnail = d.getMethod("getThumbnail");
    scale = d.getMethod("getScale");
    rotation = d.getMethod("getRotation");
    real = d.getMethod("isRealSnapshot");
    locked = d.getMethod("getAppLocked");
    Class<?>
        tx = Class.forName("com.honeyspace.transition.utils.SurfaceTransaction", false, loader),
        props =
            Class.forName(
                "com.honeyspace.transition.utils.SurfaceTransaction$SurfaceProperties",
                false,
                loader);
    transaction = tx.getConstructor();
    forSurface = tx.getMethod("forSurface", SurfaceControl.class);
    setMatrix = props.getMethod("setMatrix", Matrix.class);
    setCrop = props.getMethod("setWindowCrop", Rect.class);
    setRadius = props.getMethod("setCornerRadius", float.class);
    setAlpha = props.getMethod("setAlpha", float.class);
    show = props.getMethod("setShow");
    Class<?> ap =
        Class.forName("com.honeyspace.transition.utils.SurfaceTransactionApplier", false, loader);
    applier = ap.getConstructor(View.class).newInstance(overlay);
    schedule = ap.getMethod("scheduleApply", tx);
    Class<?>
        targetType =
            Class.forName("com.honeyspace.sdk.transition.TransitionTargets", false, loader),
        check =
            Class.forName(
                "com.honeyspace.sdk.transition.TransitionTargets$ReleaseCheck", false, loader);
    targetType.getMethod("addReleaseCheck", check).invoke(targets, applier);
    Matrix inverse = new Matrix();
    if (!basis.invert(inverse)) throw new IllegalArgumentException("Invalid source basis");
    Matrix initial = new Matrix();
    initial.setConcat(inverse, nativeMatrix);
    var pose =
        RecentsLeashSpace.pixelMatchedPose(transform(initial), source.width(), source.height(), .1);
    double[] start = {
      pose.x(),
      pose.y(),
      Math.log(pose.scale()),
      pose.angle(),
      nativeCrop.left,
      nativeCrop.top,
      nativeCrop.right,
      nativeCrop.bottom,
      nativeRadius
    };
    chase = new RecentsChase(18, start, new double[start.length]);
    Class<?> taskType =
        Class.forName("com.honeyspace.ui.honeypots.tasklist.presentation.TaskView", false, loader);
    android.view.ViewParent parent = scene.getParent();
    while (parent instanceof View v && !taskType.isInstance(v)) parent = v.getParent();
    if (!(parent instanceof View taskView))
      throw new IllegalArgumentException("Missing native task owner");
    Object actualList = taskType.getMethod("getRecentsView").invoke(taskView);
    if (!(actualList instanceof View nativeList))
      throw new IllegalArgumentException("Missing native task list");
    list = nativeList;
    refreshGoal(pose.angle());
    chase.target(target);
    setPose(start);
    active = true;
    Choreographer.getInstance().postFrameCallback(frame);
    HookEntry.log("RECENTS_VIEWPORT_MOTION_BEGIN initial=" + initial + " target=" + goal);
  }

  private void refreshGoal(double referenceAngle) throws Exception {
    if (!root.isAttachedToWindow()
        || !scene.isAttachedToWindow()
        || scene.getRootView() != root
        || !overlay.isAttachedToWindow()
        || !leash.isValid()
        || !Boolean.TRUE.equals(hasTask.invoke(scene, taskId)))
      throw new IllegalStateException("Viewport ownership changed");
    List<?> values = (List<?>) data.invoke(scene);
    if (values.size() != 1
        || !Boolean.TRUE.equals(real.invoke(values.get(0)))
        || Boolean.TRUE.equals(locked.invoke(values.get(0)))
        || !(thumbnail.invoke(values.get(0)) instanceof Bitmap image))
      throw new IllegalArgumentException("Native thumbnail is unavailable or redacted");
    if (node == null) node = new RenderNode("OneUiPortraitRecentsCard");
    node.setPosition(0, 0, scene.getWidth(), scene.getHeight());
    node.setClipToBounds(false);
    Canvas recorder = node.beginRecording(scene.getWidth(), scene.getHeight());
    try {
      scene.draw(recorder);
    } finally {
      node.endRecording();
    }
    if (list.getRootView() != root
        || !list.isAttachedToWindow()
        || list.getWidth() < 1
        || list.getHeight() < 1)
      throw new IllegalArgumentException("Native task list ownership changed");
    if (listNode == null) listNode = new RenderNode("OneUiPortraitRecentsList");
    listNode.setPosition(0, 0, list.getWidth(), list.getHeight());
    listNode.setClipToBounds(false);
    Canvas listRecorder = listNode.beginRecording(list.getWidth(), list.getHeight());
    try {
      list.draw(listRecorder);
    } finally {
      listNode.endRecording();
    }
    listGlobal = new Matrix();
    list.transformMatrixToGlobal(listGlobal);
    List<?> foreground = (List<?>) paints.invoke(scene);
    if (foreground.size() != 1
        || !(foreground.get(0) instanceof Paint paint)
        || paint.getShader() == null)
      throw new IllegalArgumentException("Unsupported thumbnail painter");
    Matrix shader = new Matrix();
    paint.getShader().getLocalMatrix(shader);
    sceneGlobal = new Matrix();
    scene.transformMatrixToGlobal(sceneGlobal);
    var exact =
        RecentsLiveGeometry.compose(
            sourceSpace,
            image.getWidth(),
            image.getHeight(),
            (Integer) rotation.invoke(values.get(0)),
            ((Number) scale.invoke(values.get(0))).doubleValue(),
            values(shader),
            values(sceneGlobal));
    goal = matrix(exact);
    var p = RecentsLeashSpace.pixelMatchedPose(exact, source.width(), source.height(), 1);
    float fraction = ((Number) invoke.invoke(ratio.invoke(scene))).floatValue();
    List<?> clips = (List<?>) bounds.invoke(state.invoke(scene), fraction),
        corners = (List<?>) radii.invoke(scene);
    if (clips.size() != 1
        || corners.size() != 1
        || !(clips.get(0) instanceof RectF box)
        || !(corners.get(0) instanceof float[] rr)
        || rr.length != 8) throw new IllegalArgumentException("Unsupported card crop");
    for (float r : rr)
      if (!Float.isFinite(r) || Math.abs(r - rr[0]) > .1)
        throw new IllegalArgumentException("Asymmetric card corners");
    Path clip =
        new Path(
            (Path)
                path.invoke(
                    scene,
                    box,
                    rr,
                    (PointF) invoke.invoke(effect.invoke(scene)),
                    tilt.invoke(scene)));
    Matrix inv = new Matrix();
    if (!goal.invert(inv)) throw new IllegalArgumentException("Invalid card geometry");
    Matrix localToSource = new Matrix();
    localToSource.setConcat(inv, sceneGlobal);
    clip.transform(localToSource);
    RectF rect = new RectF();
    clip.computeBounds(rect, true);
    if (!rect.intersect(new RectF(source)) || rect.width() < 1 || rect.height() < 1)
      throw new IllegalArgumentException("Invalid source crop");
    Matrix relative = new Matrix();
    Matrix inverseGlobal = new Matrix();
    if (!sceneGlobal.invert(inverseGlobal))
      throw new IllegalArgumentException("Invalid scene geometry");
    relative.setConcat(inverseGlobal, goal);
    float[] m = values(relative);
    double relativeScale = Math.sqrt(Math.abs(m[0] * m[4] - m[1] * m[3]));
    double angle = referenceAngle + Math.IEEEremainder(p.angle() - referenceAngle, Math.PI * 2);
    target =
        new double[] {
          p.x(),
          p.y(),
          Math.log(p.scale()),
          angle,
          rect.left,
          rect.top,
          rect.right,
          rect.bottom,
          rr[0] / relativeScale
        };
  }

  private void frame(long nanos) {
    if (stopped || handedOff) return;
    try {
      if (startedFrame == 0) startedFrame = nanos;
      double elapsed = previousFrame == 0 ? 0 : (nanos - previousFrame) / 1e9;
      previousFrame = nanos;
      refreshGoal(chase.position()[3]);
      chase.target(target);
      chase.advance(elapsed);
      setPose(chase.position());
      Object tx = transaction.newInstance(), properties = forSurface.invoke(tx, leash);
      setMatrix.invoke(properties, surfaceMatrix);
      setCrop.invoke(properties, crop);
      setRadius.invoke(properties, corner);
      setAlpha.invoke(properties, 1f);
      show.invoke(properties);
      schedule.invoke(applier, tx);
      ghostList.invalidate();
      overlay.invalidate();
      if (deferred && settled()) {
        ready.run();
        return;
      }
      if ((nanos - startedFrame) > 2_000_000_000L)
        throw new IllegalStateException("Portrait handoff did not settle");
      Choreographer.getInstance().postFrameCallback(frame);
    } catch (Throwable error) {
      failure.accept(error);
    }
  }

  private void setPose(double[] p) {
    var pose = new RecentsMotionPath.Pose(p[0], p[1], Math.exp(p[2]), p[3]);
    portraitMatrix = matrix(pose.matrix(source.width() * .5, source.height() * .5));
    surfaceMatrix = new Matrix();
    surfaceMatrix.setConcat(windowBasis, portraitMatrix);
    crop =
        new Rect(
            Math.max(0, (int) Math.floor(p[4])),
            Math.max(0, (int) Math.floor(p[5])),
            Math.min(source.width(), (int) Math.ceil(p[6])),
            Math.min(source.height(), (int) Math.ceil(p[7])));
    if (crop.isEmpty()) throw new IllegalArgumentException("Empty animated crop");
    corner = (float) Math.max(0, Math.min(p[8], Math.min(crop.width(), crop.height()) * .5));
  }

  boolean draw(Canvas canvas, View ghost) {
    if (!active || stopped || node == null || !canvas.isHardwareAccelerated()) return false;
    Matrix ghostGlobal = new Matrix();
    ghost.transformMatrixToGlobal(ghostGlobal);
    Matrix inverseGhost = new Matrix(), inverseGoal = new Matrix();
    if (!ghostGlobal.invert(inverseGhost) || !goal.invert(inverseGoal)) return false;
    Matrix delta = new Matrix();
    delta.setConcat(portraitMatrix, inverseGoal);
    Matrix local = new Matrix();
    local.setConcat(delta, sceneGlobal);
    int save = canvas.save();
    try {
      canvas.concat(inverseGhost);
      canvas.concat(portraitToOverlay);
      canvas.concat(local);
      canvas.drawRenderNode(node);
    } finally {
      canvas.restoreToCount(save);
    }
    return true;
  }

  boolean drawList(Canvas canvas, View nativeGhostList) {
    if (!active || stopped || listNode == null || !canvas.isHardwareAccelerated()) return false;
    Matrix ghostGlobal = new Matrix(), inverseGhost = new Matrix(), inverseGoal = new Matrix();
    nativeGhostList.transformMatrixToGlobal(ghostGlobal);
    if (!ghostGlobal.invert(inverseGhost) || !goal.invert(inverseGoal)) return false;
    Matrix delta = new Matrix(), local = new Matrix();
    delta.setConcat(portraitMatrix, inverseGoal);
    local.setConcat(delta, listGlobal);
    int save = canvas.save();
    try {
      // View.draw has already translated dispatchDraw by the View's own scroll.
      // transformMatrixToGlobal excludes that self scroll, so cancel it exactly once.
      canvas.translate(nativeGhostList.getScrollX(), nativeGhostList.getScrollY());
      canvas.concat(inverseGhost);
      canvas.concat(portraitToOverlay);
      canvas.concat(local);
      canvas.drawRenderNode(listNode);
    } finally {
      canvas.restoreToCount(save);
    }
    if (!groupDrawLogged) {
      groupDrawLogged = true;
      HookEntry.log(
          "RECENTS_VIEWPORT_LIST_REPLAY native portrait cards list="
              + list.getWidth()
              + "x"
              + list.getHeight()
              + " ghost="
              + nativeGhostList.getWidth()
              + "x"
              + nativeGhostList.getHeight()
              + " clip="
              + canvas.getClipBounds()
              + " local="
              + local);
    }
    return true;
  }

  boolean settled() {
    if (!active || target == null) return false;
    double[] p = chase.position();
    var a =
        new RecentsMotionPath.Pose(p[0], p[1], Math.exp(p[2]), p[3])
            .matrix(source.width() * .5, source.height() * .5);
    var b =
        new RecentsMotionPath.Pose(target[0], target[1], Math.exp(target[2]), target[3])
            .matrix(source.width() * .5, source.height() * .5);
    for (int x : new int[] {0, source.width()})
      for (int y : new int[] {0, source.height()}) {
        var c = a.map(x, y);
        var d = b.map(x, y);
        if (Math.hypot(c.x() - d.x(), c.y() - d.y()) > .35) return false;
      }
    for (int i = 4; i < p.length; i++) if (Math.abs(p[i] - target[i]) > .5) return false;
    return true;
  }

  void defer() {
    deferred = true;
  }

  boolean deferred() {
    return deferred;
  }

  void handoff() {
    handedOff = true;
    Choreographer.getInstance().removeFrameCallback(frame);
    HookEntry.log("RECENTS_VIEWPORT_MOTION_HANDOFF settled=" + settled());
  }

  boolean overriding() {
    return active && !stopped;
  }

  Matrix surfaceMatrix() {
    return new Matrix(surfaceMatrix);
  }

  Rect crop() {
    return new Rect(crop);
  }

  float corner() {
    return corner;
  }

  void cancel() {
    stopped = true;
    active = false;
    Choreographer.getInstance().removeFrameCallback(frame);
    if (node != null) node.discardDisplayList();
    node = null;
    if (listNode != null) listNode.discardDisplayList();
    listNode = null;
  }

  private static float[] values(Matrix m) {
    float[] a = new float[9];
    m.getValues(a);
    return a;
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

  private static RecentsMotionPath.Transform transform(Matrix m) {
    float[] a = values(m);
    if (a[6] != 0 || a[7] != 0 || a[8] != 1)
      throw new IllegalArgumentException("Perspective app surface");
    return new RecentsMotionPath.Transform(a[0], a[3], a[1], a[4], a[2], a[5]);
  }
}
