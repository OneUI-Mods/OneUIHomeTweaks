package dev.ryuya.oneuihometweaks

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnPreDrawListener
import java.lang.Boolean as JavaBoolean
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.List
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/* Session-owned live surface. Native card drawing supplies clipping and geometry. */
class RecentsLiveSurface(loader: ClassLoader?, unsupported: Runnable) {
    private val unsupported: Runnable
    private val blur: RecentsLiveBlur
    private val tiltPath: Method
    private val tilt: Method
    private val taskType: Class<*>
    private val taskId: Method
    private val taskScene: Method
    private val paints: Method
    private val sceneData: Method
    private val bitmap: Method
    private val snapshotScale: Method
    private val snapshotRotation: Method
    private val hasTaskId: Method
    private val realSnapshot: Method
    private val locked: Method
    private var expectedTaskId: Int = 0
    private val updaterTargets: Method
    private val recent: Method
    private val apps: Method
    private val mode: Method
    private val playerBasis: Method
    private val releaseCheck: Method
    private val updater: Field
    private val player: Field
    private val transaction: Constructor<*>
    private val applier: Constructor<*>
    private val forSurface: Method
    private val setMatrix: Method
    private val setCrop: Method
    private val setRadius: Method
    private val setAlpha: Method
    private val show: Method
    private val getTransaction: Method
    private val schedule: Method
    private val rawTransaction: Field
    private val relativeLayer: Method
    private val drawHook: ModernHook.Handle
    private var root: View? = null
    private var scene: View? = null
    private var appLeash: SurfaceControl? = null
    private var recentsLeash: SurfaceControl? = null
    private var source: Rect? = null
    private var viewportToLeash: Matrix? = null
    private var sourceSpace: RecentsCardSpace.Space? = null
    private var syncApplier: Any? = null

    private enum class Draw {
        LIVE,
        SNAPSHOT,
        INVALID,
    }

    private var updating: Boolean = false
    private var logged: Boolean = false
    private var visualSuspended: Boolean = false
    private var lastGeometry: String? = null
    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0
    private var viewportRotation: Int = 0
    private var perspectiveSamples: Int = 0
    private val preDraw: OnPreDrawListener = OnPreDrawListener { this.validateScene() }

    private fun validateScene(): Boolean {
        try {
            if (root == null) return true
            if (
                !root!!.isAttachedToWindow() ||
                    root!!.getWidth() != viewportWidth ||
                    root!!.getHeight() != viewportHeight ||
                    root!!.getDisplay() == null ||
                    root!!.getDisplay().getRotation() != viewportRotation ||
                    appLeash == null ||
                    !appLeash!!.isValid() ||
                    recentsLeash == null ||
                    !recentsLeash!!.isValid()
            ) {
                unsupported.run()
                return true
            }
            if (
                scene == null ||
                    !scene!!.isAttachedToWindow() ||
                    !scene!!.isShown() ||
                    scene!!.getRootView() !== root ||
                    (JavaBoolean.TRUE != hasTaskId.invoke(scene, expectedTaskId))
            ) {
                suspendVisual("RECENTS_LIVE_OFFSCREEN app continues drawing")
                scene = find(root!!, expectedTaskId, 0)
                if (scene != null) scene!!.invalidate()
            }
        } catch (error: Throwable) {
            HookEntry.log("RECENTS_LIVE_VALIDATION_ERROR " + error)
            unsupported.run()
        }
        return true
    }

    @Throws(Exception::class)
    private fun suspendVisual(reason: String?) {
        if (visualSuspended) return
        val tx = transaction.newInstance()
        val props = forSurface.invoke(tx, appLeash)
        blur.restore(rawTransaction.get(getTransaction.invoke(tx)) as Transaction?)
        setAlpha.invoke(props, 0f)
        schedule.invoke(syncApplier, tx)
        visualSuspended = true
        lastGeometry = null
        HookEntry.log(reason)
    }

    private val clear: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        this.unsupported = unsupported
        blur = RecentsLiveBlur(loader)
        taskType =
            Class.forName(
                "com.honeyspace.ui.honeypots.tasklist.presentation.TaskView",
                false,
                loader,
            )
        taskId = taskType.getMethod("getFirstTaskId")
        taskScene = taskType.getMethod("getTaskSceneView")
        val sceneType =
            Class.forName(
                HookCompatibility.taskSceneView(Build.VERSION.SDK_INT),
                false,
                loader,
            )
        paints = sceneType.getMethod("getForegroundPaints")
        hasTaskId = sceneType.getMethod("hasTaskId", Int::class.javaPrimitiveType)
        tiltPath =
            sceneType.getDeclaredMethod(
                "createTiltPath",
                RectF::class.java,
                FloatArray::class.java,
                PointF::class.java,
                Double::class.javaPrimitiveType,
            )
        tiltPath.setAccessible(true)
        tilt = sceneType.getMethod("getTiltRadian")
        sceneData = sceneType.getMethod("getTaskSceneData")
        val dataType =
            Class.forName(
                HookCompatibility.taskSceneData(Build.VERSION.SDK_INT),
                false,
                loader,
            )
        bitmap = dataType.getMethod("getThumbnail")
        snapshotScale = dataType.getMethod("getScale")
        snapshotRotation = dataType.getMethod("getRotation")
        realSnapshot = dataType.getMethod("isRealSnapshot")
        locked = dataType.getMethod("getAppLocked")
        val manager =
            Class.forName(
                "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager",
                false,
                loader,
            )
        updater = manager.getDeclaredField("leashUpdater")
        updater.setAccessible(true)
        player = manager.getDeclaredField("appPlayer")
        player.setAccessible(true)
        val updaterType =
            Class.forName("com.honeyspace.gesture.recentsanimation.LeashUpdater", false, loader)
        updaterTargets = updaterType.getMethod("getTargets")
        val targets =
            Class.forName("com.honeyspace.sdk.transition.TransitionTargets", false, loader)
        recent = targets.getMethod("getRecentTarget")
        apps = targets.getMethod("getApps")
        mode = targets.getMethod("getTargetMode")
        releaseCheck =
            targets.getMethod(
                "addReleaseCheck",
                Class.forName(
                    "com.honeyspace.sdk.transition.TransitionTargets\$ReleaseCheck",
                    false,
                    loader,
                ),
            )
        playerBasis =
            Class.forName("com.honeyspace.transition.anim.floating.Player", false, loader)
                .getMethod("getHomeToWindowMatrix")
        val tx = Class.forName("com.honeyspace.transition.utils.SurfaceTransaction", false, loader)
        val props =
            Class.forName(
                "com.honeyspace.transition.utils.SurfaceTransaction\$SurfaceProperties",
                false,
                loader,
            )
        transaction = tx.getConstructor()
        forSurface = tx.getMethod("forSurface", SurfaceControl::class.java)
        setMatrix = props.getMethod("setMatrix", Matrix::class.java)
        setCrop = props.getMethod("setWindowCrop", Rect::class.java)
        setRadius = props.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
        setAlpha = props.getMethod("setAlpha", Float::class.javaPrimitiveType)
        show = props.getMethod("setShow")
        getTransaction = tx.getMethod("getTransaction")
        rawTransaction =
            Class.forName("com.android.systemui.shared.launcher.ScTransactionCompat", false, loader)
                .getField("mTransaction")
        relativeLayer =
            Transaction::class
                .java
                .getMethod(
                    "setRelativeLayer",
                    SurfaceControl::class.java,
                    SurfaceControl::class.java,
                    Int::class.javaPrimitiveType,
                )
        val applierType =
            Class.forName(
                "com.honeyspace.transition.utils.SurfaceTransactionApplier",
                false,
                loader,
            )
        applier = applierType.getConstructor(View::class.java)
        schedule = applierType.getMethod("scheduleApply", tx)
        clear.setBlendMode(BlendMode.CLEAR)
        val draw =
            sceneType.getMethod(
                "draw",
                Canvas::class.java,
                MutableList::class.java,
                MutableList::class.java,
                MutableList::class.java,
                PointF::class.java,
            )
        drawHook =
            ModernHook.hookMethod(
                draw,
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        if (scene == null || p.thisObject !== scene || updating) return
                        try {
                            if (p.args[1] !== paints.invoke(scene)) return
                            updating = true
                            val result = render(p.args)
                            if (result == RecentsLiveSurface.Draw.INVALID) {
                                unsupported.run()
                                return
                            }
                            if (result == RecentsLiveSurface.Draw.SNAPSHOT) return
                            // The original path retains Samsung's rounded corners, tilt and
                            // style-specific
                            // clipping.
                            // Clear through the Recents window to its owned live app underneath.
                            p.args[1] = List.of<Paint?>(clear)
                        } catch (error: Throwable) {
                            HookEntry.log("RECENTS_LIVE_SURFACE_ERROR " + error)
                            unsupported.run()
                        } finally {
                            updating = false
                        }
                    }
                },
            )
    }

    @Throws(Exception::class)
    fun bind(manager: Any?, target: View?, closingId: Int): Boolean {
        clear()
        if (
            target == null ||
                target.getDisplay() == null ||
                target.getDisplay().getDisplayId() != 0 ||
                target.getWidth() <= 0 ||
                target.getHeight() <= 0
        )
            return false
        val nativePlayer = player.get(manager)
        if (nativePlayer == null) return false
        val u = updater.get(manager)
        if (u == null) return false
        val targets = updaterTargets.invoke(u)
        val r = recent.invoke(targets)
        if (r == null) return false
        var closing: Any? = null
        val closingMode: Int = mode.invoke(targets) as Int
        for (a in (apps.invoke(targets) as Array<Any?>?)!!) {
            val c = a!!.javaClass
            if (c.getField("mode").getInt(a) != closingMode) continue
            if (closing != null || c.getField("taskId").getInt(a) != closingId) return false
            closing = a
        }
        if (closing == null) return false
        source = Rect(closing.javaClass.getField("sourceContainerBounds").get(closing) as Rect?)
        viewportToLeash = Matrix(playerBasis.invoke(nativePlayer) as Matrix?)
        val basis = FloatArray(9)
        viewportToLeash!!.getValues(basis)
        sourceSpace =
            RecentsLeashSpace.resolve(
                RecentsCardSpace.Space(
                    target.getWidth(),
                    target.getHeight(),
                    target.getDisplay().getRotation(),
                ),
                source!!.width(),
                source!!.height(),
                basis,
            )
        if (source!!.left != 0 || source!!.top != 0) return false
        appLeash = closing.javaClass.getField("leash").get(closing) as SurfaceControl?
        recentsLeash = r.javaClass.getField("leash").get(r) as SurfaceControl?
        scene = find(target, closingId, 0)
        if (scene == null || !appLeash!!.isValid() || !recentsLeash!!.isValid()) {
            clear()
            return false
        }
        val sourceData: MutableList<*>? = sceneData.invoke(scene) as MutableList<*>?
        if (
            sourceData!!.size != 1 ||
                (JavaBoolean.TRUE != realSnapshot.invoke(sourceData!!.get(0))) ||
                JavaBoolean.TRUE == locked.invoke(sourceData!!.get(0)) ||
                (bitmap.invoke(sourceData!!.get(0)) !is Bitmap)
        ) {
            clear()
            return false
        }
        expectedTaskId = closingId
        root = target
        viewportWidth = root!!.getWidth()
        viewportHeight = root!!.getHeight()
        viewportRotation = root!!.getDisplay().getRotation()
        root!!.getViewTreeObserver().addOnPreDrawListener(preDraw)
        if (!blur.bind(root!!, appLeash)) {
            clear()
            return false
        }
        syncApplier = applier.newInstance(root)
        releaseCheck.invoke(targets, syncApplier)
        root!!.invalidate()
        scene!!.invalidate()
        HookEntry.log(
            ("RECENTS_LIVE_SURFACE_BOUND viewport=" +
                viewportWidth +
                "x" +
                viewportHeight +
                " rotation=" +
                viewportRotation +
                " source=" +
                source!!.width() +
                "x" +
                source!!.height() +
                " basis=" +
                viewportToLeash)
        )
        return true
    }

    @Throws(Exception::class)
    private fun find(v: View, id: Int, depth: Int): View? {
        if (depth > 32 || v.getVisibility() != View.VISIBLE) return null
        if (taskType.isInstance(v) && taskId.invoke(v) as Int? == id)
            return taskScene.invoke(v) as View?
        if (v is ViewGroup)
            for (i in 0 until v.getChildCount()) {
                val found = find(v.getChildAt(i), id, depth + 1)
                if (found != null) return found
            }
        return null
    }

    @Throws(Exception::class)
    private fun render(args: Array<Any?>): RecentsLiveSurface.Draw? {
        val values: MutableList<*>? = args[1] as MutableList<*>?
        val patternPrefix0 =
            !root!!.isAttachedToWindow() ||
                !scene!!.isShown() ||
                scene!!.getRootView() !== root ||
                !appLeash!!.isValid() ||
                !recentsLeash!!.isValid() ||
                values!!.size != 1
        val paint = if (!patternPrefix0) values!!.get(0) as? Paint else null
        if (patternPrefix0 || paint == null || paint.getShader() == null)
            return RecentsLiveSurface.Draw.INVALID
        val data: MutableList<*>? = sceneData.invoke(scene) as MutableList<*>?
        val patternPrefix1 =
            !(hasTaskId.invoke(scene, expectedTaskId) as Boolean) ||
                data!!.size != 1 ||
                JavaBoolean.TRUE != realSnapshot.invoke(data!!.get(0)) ||
                JavaBoolean.TRUE == locked.invoke(data!!.get(0))
        val image = if (!patternPrefix1) bitmap.invoke(data!!.get(0)) as? Bitmap else null
        if (patternPrefix1 || image == null) return RecentsLiveSurface.Draw.INVALID
        val scale = (snapshotScale.invoke(data!!.get(0)) as Number).toFloat()
        val shader = Matrix()
        paint.getShader().getLocalMatrix(shader)
        val global = Matrix()
        scene!!.transformMatrixToGlobal(global)
        val sh = FloatArray(9)
        val gl = FloatArray(9)
        shader.getValues(sh)
        global.getValues(gl)
        val transform: RecentsAffine.Transform?
        try {
            transform =
                RecentsLiveGeometry.compose(
                    sourceSpace!!,
                    image.getWidth(),
                    image.getHeight(),
                    snapshotRotation.invoke(data!!.get(0)) as Int,
                    scale.toDouble(),
                    sh,
                    gl,
                )
        } catch (unsupportedGeometry: RecentsLiveGeometry.PerspectiveCard) {
            if (BuildConfig.RECENTS_LIVE_TRACE && perspectiveSamples++ < 48)
                HookEntry.log(
                    ("RECENTS_LIVE_PROJECTIVE_MATRIX shader=" +
                        sh.contentToString() +
                        " global=" +
                        gl.contentToString())
                )
            suspendVisual("RECENTS_LIVE_NATIVE_PERSPECTIVE app continues drawing")
            return RecentsLiveSurface.Draw.SNAPSHOT
        }
        val output = Matrix()
        output.setValues(
            floatArrayOf(
                (transform!!.a).toFloat(),
                (transform!!.c).toFloat(),
                (transform!!.tx).toFloat(),
                (transform!!.b).toFloat(),
                (transform!!.d).toFloat(),
                (transform!!.ty).toFloat(),
                (0).toFloat(),
                (0).toFloat(),
                (1).toFloat(),
            )
        )
        val bounds: MutableList<*>? = args[2] as MutableList<*>?
        val corners: MutableList<*>? = args[3] as MutableList<*>?
        if (bounds!!.size != 1 || corners!!.size != 1) return RecentsLiveSurface.Draw.INVALID
        val path =
            Path(
                tiltPath.invoke(
                    scene,
                    bounds!!.get(0),
                    corners!!.get(0),
                    args[4],
                    tilt.invoke(scene),
                ) as Path?
            )
        val geometry: String =
            output.toString() +
                bounds!!.get(0) +
                (corners!!.get(0) as FloatArray?).contentToString()
        if (!blur.valid()) return RecentsLiveSurface.Draw.INVALID
        if (geometry == lastGeometry) return RecentsLiveSurface.Draw.LIVE
        val inverse = Matrix()
        if (!output.invert(inverse)) return RecentsLiveSurface.Draw.INVALID
        val localToSource = Matrix()
        localToSource.setConcat(inverse, global)
        val sourceClip = Path(path)
        sourceClip.transform(localToSource)
        val cropBounds = RectF()
        sourceClip.computeBounds(cropBounds, true)
        if (!cropBounds.intersect(RectF(source))) return RecentsLiveSurface.Draw.INVALID
        val sourceCrop =
            Rect(
                max(source!!.left, (floor(cropBounds.left)).toInt()),
                max(source!!.top, (floor(cropBounds.top)).toInt()),
                min(source!!.right, (ceil(cropBounds.right)).toInt()),
                min(source!!.bottom, (ceil(cropBounds.bottom)).toInt()),
            )
        val rr = corners!!.get(0) as? FloatArray
        if (rr == null || rr.size != 8) return RecentsLiveSurface.Draw.INVALID
        for (corner in rr) if (!java.lang.Float.isFinite(corner) || abs(corner - rr[0]) > .1)
            return RecentsLiveSurface.Draw.INVALID
        val relative = Matrix()
        val inverseGlobal = Matrix()
        if (!global.invert(inverseGlobal)) return RecentsLiveSurface.Draw.INVALID
        relative.setConcat(inverseGlobal, output)
        val relativeValues = FloatArray(9)
        relative.getValues(relativeValues)
        val contentScale =
            sqrt(abs(relativeValues[0] * relativeValues[4] - relativeValues[1] * relativeValues[3]))
        if (!java.lang.Double.isFinite((contentScale).toDouble()) || contentScale <= 0)
            return RecentsLiveSurface.Draw.INVALID
        val tx = transaction.newInstance()
        val props = forSurface.invoke(tx, appLeash)
        val leashMatrix = Matrix()
        leashMatrix.setConcat(viewportToLeash, output)
        setMatrix.invoke(props, leashMatrix)
        setCrop.invoke(props, sourceCrop)
        setRadius.invoke(props, (rr[0] / contentScale).toFloat())
        setAlpha.invoke(props, 1f)
        show.invoke(props)
        val raw: Transaction? = rawTransaction.get(getTransaction.invoke(tx)) as Transaction?
        relativeLayer.invoke(raw, appLeash, recentsLeash, -1)
        blur.apply(raw!!)
        schedule.invoke(syncApplier, tx)
        lastGeometry = geometry
        if (visualSuspended) {
            visualSuspended = false
            HookEntry.log("RECENTS_LIVE_RESUMED native geometry available")
        }
        if (!logged) {
            logged = true
            HookEntry.log("RECENTS_LIVE_SURFACE_DRAW matrix=" + output)
        }
        return RecentsLiveSurface.Draw.LIVE
    }

    fun clear() {
        if (visualSuspended && appLeash != null && appLeash!!.isValid()) {
            try {
                Transaction().use { tx ->
                    tx.setAlpha(appLeash!!, 1f).apply()
                }
            } catch (error: Throwable) {
                HookEntry.log("RECENTS_LIVE_ALPHA_RESTORE_ERROR " + error)
            }
        }
        visualSuspended = false
        if (root != null && root!!.getViewTreeObserver().isAlive())
            root!!.getViewTreeObserver().removeOnPreDrawListener(preDraw)
        blur.clear()
        if (root != null) root!!.invalidate()
        scene = null
        root = scene
        recentsLeash = null
        appLeash = recentsLeash
        source = null
        viewportToLeash = null
        sourceSpace = null
        syncApplier = null
        logged = false
        lastGeometry = null
    }

    fun uninstall() {
        clear()
        drawHook.unhook()
    }
}
