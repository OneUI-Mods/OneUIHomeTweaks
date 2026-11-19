package dev.ryuya.oneuihometweaks

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.view.Choreographer
import android.view.Display
import android.view.View
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import kotlin.math.min

/** Shares one reduced-size source below the shade between its glass surfaces. */
@RequiresApi(33)
internal class GlassPanelBackdrop(
    private val main: Handler,
    private val worker: Executor,
    private val density: Float,
    private val invalidate: () -> Unit,
    private val windowType: Int = NOTIFICATION_SHADE_TYPE,
    private val live: Boolean = false,
    private val useDisplayBounds: Boolean = false,
) {
    private class DisplayFrame(root: View, val bounds: Rect, val rotation: Int, live: Boolean) {
        val root = WeakReference(root)
        val diffusion = GlassPanelDiffusion(skipUnchanged = live)
        val cadence = GlassBackdropCadence()
        @Volatile var active = true
        var retried = false
        var lastPolicy: Int? = null
    }

    private data class CaptureResult(val bitmap: Bitmap? = null, val missingWindow: Boolean = false, val policy: Int? = null)

    private sealed interface Update {
        data class Changed(val bitmaps: GlassPanelDiffusion.Backdrops) : Update
        data object Unchanged : Update
        data class Unavailable(val missingWindow: Boolean = false, val policy: Int? = null) : Update
    }

    private class Source(bitmaps: GlassPanelDiffusion.Backdrops, val display: DisplayFrame) {
        val width = bitmaps.diffuse.width
        val height = bitmaps.diffuse.height
        val diffuse = shader(bitmaps.diffuse)
        val bevel = shader(bitmaps.bevel)

        private fun shader(bitmap: Bitmap) = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
    }

    private val capture = runCatching { ShadeCapture() }.onFailure {
        HookEntry.log("GLASS_PANEL_SOURCE_UNAVAILABLE ${it.javaClass.simpleName}")
    }.getOrNull()
    private var frame: DisplayFrame? = null
    private var source: Source? = null
    private var mix = 0f
    private var fade: ValueAnimator? = null
    private var inFlight = false
    private var lens: GlassLens? = null
    private var lensUnavailable = false
    private var reported = false
    private var reportedMotion = false
    private val choreographer = if (live) Choreographer.getInstance() else null
    private val captureNextFrame = Choreographer.FrameCallback { requestSource() }
    private val capturePopup = Runnable { requestSource() }
    private val transform = Matrix()
    private val rect = RectF()
    private val path = Path()
    private val displaySize = Point()

    fun isDrawReady(): Boolean = source != null && mix > 0f && !lensUnavailable

    fun observe(view: View, open: Boolean) {
        if (!open || view.display?.displayId != Display.DEFAULT_DISPLAY) {
            clear()
            return
        }
        val display = view.display ?: return
        if (capture == null || lensUnavailable) return
        // Use the display frame, which includes the portions behind the status and navigation bars.
        val root = view.rootView
        if (useDisplayBounds) {
            // The volume dialog can be smaller than the display and slide relative to it.
            @Suppress("DEPRECATION")
            display.getRealSize(displaySize)
        } else {
            displaySize.set(root.width, root.height)
        }
        if (displaySize.x <= 0 || displaySize.y <= 0) return
        val current = frame
        if (current != null && current.root.get() === root && current.rotation == display.rotation &&
            current.bounds.width() == displaySize.x && current.bounds.height() == displaySize.y) return
        clear()
        frame = DisplayFrame(root, Rect(0, 0, displaySize.x, displaySize.y), display.rotation, live)
        // Wait for WindowManager to publish the shade surface before requesting the layer below it.
        schedule(OPEN_DELAY)
    }

    private fun schedule(delay: Long) {
        if (choreographer != null) {
            // Use the display's frame clock. A busy worker will only request another frame
            // after completing the current capture, so obsolete work never queues up.
            choreographer.removeFrameCallback(captureNextFrame)
            choreographer.postFrameCallbackDelayed(captureNextFrame, delay)
        } else {
            main.removeCallbacks(capturePopup)
            main.postDelayed(capturePopup, delay)
        }
    }

    private fun requestSource() {
        val session = frame ?: return
        val capture = capture ?: return
        if (inFlight) return
        if (!hasVisibleRoot(session)) {
            clear()
            return
        }
        inFlight = true
        worker.execute {
            val update = if (!session.active) Update.Unavailable() else readSource(capture, session)
            main.post { complete(session, update) }
        }
    }

    private fun readSource(capture: ShadeCapture, session: DisplayFrame): Update {
        return try {
            val result = capture.read(session.bounds, windowType)
            val bitmap = result.bitmap
            if (bitmap == null) {
                session.diffusion.forgetPrevious()
                return Update.Unavailable(result.missingWindow, result.policy)
            }
            try {
                if (!session.active) return Update.Unavailable()
                val bitmaps = session.diffusion.apply(bitmap) ?: return Update.Unchanged
                Update.Changed(bitmaps)
            } finally {
                bitmap.recycle()
            }
        } catch (error: Exception) {
            session.diffusion.forgetPrevious()
            Update.Unavailable()
        }
    }

    private fun complete(session: DisplayFrame, update: Update) {
        inFlight = false
        if (frame !== session || !hasVisibleRoot(session)) {
            if (update is Update.Changed) {
                update.bitmaps.diffuse.recycle()
                update.bitmaps.bevel.recycle()
            }
            // A new observation may have tried to start while the old capture was draining.
            if (frame === session) clear() else if (frame != null) schedule(OPEN_DELAY)
            return
        }
        when (update) {
            is Update.Changed -> {
                session.cadence.reset()
                session.lastPolicy = null
                val first = source == null
                source = Source(update.bitmaps, session)
                if (!reported) {
                    reported = true
                    HookEntry.log("GLASS_PANEL_SOURCE type=$windowType ${update.bitmaps.diffuse.width}x${update.bitmaps.diffuse.height}")
                }
                if (first) {
                    fade = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 120L
                        addUpdateListener {
                            mix = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
                } else {
                    if (live && !reportedMotion) {
                        reportedMotion = true
                        HookEntry.log("GLASS_PANEL_LIVE_UPDATED")
                    }
                    invalidate()
                }
            }
            Update.Unchanged -> session.cadence.recordIdenticalFrame()
            is Update.Unavailable -> {
                session.cadence.reset()
                if (source != null) {
                    clearSource()
                    invalidate()
                }
                if (update.policy != null && update.policy != session.lastPolicy) {
                    HookEntry.log("GLASS_PANEL_SOURCE_SKIPPED policy=${update.policy}")
                }
                session.lastPolicy = update.policy
                if (update.missingWindow && !session.retried) {
                    session.retried = true
                    schedule(80L)
                    return
                }
            }
        }
        if (live && frame === session) {
            val delay = if (update is Update.Unavailable) UNAVAILABLE_RETRY
                else session.cadence.delayMillis(session.root.get()?.display?.refreshRate)
            schedule(delay)
        }
    }

    private fun hasVisibleRoot(session: DisplayFrame): Boolean {
        val root = session.root.get() ?: return false
        return root.isAttachedToWindow && (!useDisplayBounds || root.isShown)
    }

    fun draw(
        canvas: Canvas, screenX: Float, screenY: Float, left: Float, top: Float, right: Float, bottom: Float,
        radii: FloatArray, strength: Int, dark: Boolean, tint: Float, opacity: Float,
        screenToLocal: Matrix? = null,
    ): Boolean {
        val source = source ?: return false
        if (lensUnavailable || mix <= 0f || opacity <= 0f || right <= left || bottom <= top) return false
        return try {
            val glass = lens ?: GlassLens(density).also { lens = it }
            val bounds = source.display.bounds
            transform.setScale(bounds.width().toFloat() / source.width, bounds.height().toFloat() / source.height)
            if (screenToLocal == null) {
                transform.postTranslate(bounds.left - screenX, bounds.top - screenY)
            } else {
                transform.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
                transform.postConcat(screenToLocal)
            }
            source.diffuse.setLocalMatrix(transform)
            source.bevel.setLocalMatrix(transform)
            rect.set(left, top, right, bottom)
            path.rewind()
            path.addRoundRect(rect, radii, Path.Direction.CW)
            glass.draw(canvas, source.diffuse, left, top, right, bottom, min(radii[0], radii[1]),
                strength, dark, opacity = opacity * mix, tintFactor = tint, panel = true, outline = path,
                bevelBackdrop = source.bevel, radii = radii)
            true
        } catch (error: RuntimeException) {
            lensUnavailable = true
            lens = null
            source.diffuse.setLocalMatrix(null)
            source.bevel.setLocalMatrix(null)
            clear()
            HookEntry.log("GLASS_PANEL_LENS_UNAVAILABLE ${error.javaClass.simpleName}")
            false
        }
    }

    fun clear() {
        if (frame == null && source == null) return
        frame?.active = false
        frame = null
        choreographer?.removeFrameCallback(captureNextFrame)
        main.removeCallbacks(capturePopup)
        clearSource()
    }

    private fun clearSource() {
        fade?.cancel()
        fade = null
        mix = 0f
        source = null
        lens?.clearBackdrop()
    }

    /** Uses the Samsung screenshot policy and rejects a missing or protected target. */
    private class ShadeCapture {
        private val global = Class.forName("android.view.WindowManagerGlobal")
        private val manager = requireNotNull(global.getMethod("getWindowManagerService").invoke(null))
        private val method = Class.forName("android.view.IWindowManager").getMethod(
            "takeScreenshotToTargetWindow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Rect::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        private val failedReason = method.returnType.getMethod("getFailedReason")
        private val targetName = method.returnType.getMethod("getTargetWindowName")
        private val secureName = method.returnType.getMethod("getSecuredWindowName")
        private val capturedBitmap = method.returnType.getMethod("getCapturedBitmap")

        fun read(bounds: Rect, windowType: Int): CaptureResult {
            val result = method.invoke(manager, Display.DEFAULT_DISPLAY, windowType,
                false, bounds, bounds.width() / 6, bounds.height() / 6, false, false) ?: return CaptureResult()
            val failure = failedReason.invoke(result) as Int
            val isProtected = !(secureName.invoke(result) as? String).isNullOrEmpty()
            if (failure != 0 || isProtected) {
                return CaptureResult(missingWindow = !isProtected && failure == INVALID_SYSTEM_WINDOW, policy = failure)
            }
            val target = targetName.invoke(result) as? String
            if (target.isNullOrBlank() || target.contains("EdgeLightingWindow")) return CaptureResult()
            if (windowType == VOLUME_WINDOW_TYPE && (target.contains("VolumePanelWindow") ||
                    target.contains("VolumePanelExpandWindow") || target.contains("VolumeStarStandardWindow"))) {
                return CaptureResult()
            }
            if (target.contains("NotificationShade")) {
                if (windowType == BRIEF_POPUP_WINDOW_TYPE) return read(bounds, NOTIFICATION_SHADE_TYPE)
                if (windowType == NOTIFICATION_SHADE_TYPE) return CaptureResult()
            }
            return CaptureResult(bitmap = capturedBitmap.invoke(result) as? Bitmap)
        }
    }

    companion object {
        private const val OPEN_DELAY = 32L
        private const val UNAVAILABLE_RETRY = 500L
        private const val NOTIFICATION_SHADE_TYPE = 2040
        private const val INVALID_SYSTEM_WINDOW = 2
        // Screen-on EdgeLightingWindow type on the supported One UI build.
        const val BRIEF_POPUP_WINDOW_TYPE = 2227
        const val VOLUME_WINDOW_TYPE = 2020
    }
}
