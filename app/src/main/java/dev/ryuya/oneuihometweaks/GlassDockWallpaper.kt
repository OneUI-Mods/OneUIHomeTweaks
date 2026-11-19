package dev.ryuya.oneuihometweaks

import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.PowerManager
import android.os.Trace
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import kotlin.math.ceil

/** A small wallpaper texture in display coordinates, owned by the dock's drawable. */
@RequiresApi(33)
internal class GlassDockWallpaper(
    view: View,
    private val main: Handler,
    private val worker: Executor,
    private val reader: Reader,
    private val changed: () -> Unit,
) {
    private data class Region(val width: Int, val height: Int, val crop: Rect)

    private sealed interface Update {
        data class Changed(val bitmap: Bitmap) : Update
        data object Unchanged : Update
        data object Unavailable : Update
    }

    private val host = WeakReference(view)
    private val choreographer = Choreographer.getInstance()
    private val cadence = GlassBackdropCadence()
    private val sample = Choreographer.FrameCallback { capture() }
    private val padding = ceil(44 * view.resources.displayMetrics.density).toInt()
    private val localToScreen = Matrix()
    private val screenToLocal = Matrix()
    private val shaderToLocal = Matrix()
    private val mappedBounds = RectF()
    private var region: Region? = null
    private var texture: Bitmap? = null
    private var shader: BitmapShader? = null
    @Volatile private var epoch = 0L
    private var running = false
    private var requested = false
    private var retry = false
    private var live: Boolean? = null
    private var paused = false
    private var unavailable = false
    private var motionReported = false

    val hasTexture get() = shader != null

    fun update(bounds: Rect) {
        val view = host.get()
        if (view == null || bounds.isEmpty) {
            clear()
            return
        }
        if (!visible(view)) {
            pause()
            return
        }
        val resuming = paused
        paused = false
        val display = view.context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds ?: return
        localToScreen.reset()
        view.transformMatrixToGlobal(localToScreen)
        mappedBounds.set(bounds)
        localToScreen.mapRect(mappedBounds)
        val crop = Rect()
        mappedBounds.roundOut(crop)
        crop.inset(-padding, -padding)
        if (!crop.intersect(0, 0, display.width(), display.height())) {
            clear()
            return
        }
        val next = Region(display.width(), display.height(), crop)
        if (region == next) {
            if (resuming) dirty()
            return
        }
        clear()
        paused = false
        region = next
        dirty()
    }

    fun dirty(wallpaperChanged: Boolean = false) {
        if (region == null) return
        if (wallpaperChanged) {
            epoch++
            live = null
        }
        requested = true
        retry = true
        cadence.reset()
        schedule(0L)
    }

    fun shaderFor(view: View): BitmapShader? {
        val image = texture ?: return null
        val crop = region?.crop ?: return null
        val result = shader ?: return null
        localToScreen.reset()
        view.transformMatrixToGlobal(localToScreen)
        if (!localToScreen.invert(screenToLocal)) return null
        shaderToLocal.setScale(crop.width() / image.width.toFloat(), crop.height() / image.height.toFloat())
        shaderToLocal.postTranslate(crop.left.toFloat(), crop.top.toFloat())
        shaderToLocal.postConcat(screenToLocal)
        result.setLocalMatrix(shaderToLocal)
        return result
    }

    fun clear() {
        if (region == null) return
        pause()
        region = null
        publish(null)
    }

    private fun pause() {
        if (paused) return
        paused = true
        epoch++
        requested = false
        retry = false
        choreographer.removeFrameCallback(sample)
    }

    private fun schedule(delay: Long) {
        if (paused || running) return
        choreographer.removeFrameCallback(sample)
        choreographer.postFrameCallbackDelayed(sample, delay)
    }

    private fun capture() {
        val current = region ?: return
        val view = host.get()
        if (view == null || !visible(view)) {
            pause()
            return
        }
        val version = epoch
        val knownLive = live
        val previous = texture
        requested = false
        running = true
        worker.execute {
            val update = if (epoch == version) readFrame(current, previous) else Update.Unavailable
            val liveWallpaper = knownLive ?: (epoch == version && reader.isLive())
            main.post { complete(version, liveWallpaper, update) }
        }
    }

    private fun readFrame(region: Region, previous: Bitmap?): Update {
        Trace.beginSection("GlassDockWallpaper.sample")
        try {
            val frame = reader.capture(region.width, region.height, region.crop) ?: return Update.Unavailable
            return if (frame.sameAs(previous)) {
                frame.recycle()
                Update.Unchanged
            } else {
                Update.Changed(frame)
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun complete(version: Long, liveWallpaper: Boolean, update: Update) {
        running = false
        val view = host.get()
        if (epoch != version || view == null || !visible(view)) {
            if (update is Update.Changed) update.bitmap.recycle()
            if (requested) schedule(0L)
            return
        }
        live = liveWallpaper
        when (update) {
            is Update.Changed -> {
                if (texture != null && !motionReported) {
                    motionReported = true
                    HookEntry.log("GLASS_HOME_DOCK_LIVE_UPDATED")
                }
                cadence.reset()
                retry = true
                publish(update.bitmap)
            }
            Update.Unchanged -> {
                if (!requested) cadence.recordIdenticalFrame()
                retry = true
            }
            Update.Unavailable -> {
                if (retry) {
                    retry = false
                    schedule(if (requested) 0L else 80L)
                    return
                }
                publish(null)
                if (!unavailable) {
                    unavailable = true
                    HookEntry.log("GLASS_HOME_DOCK_WALLPAPER_UNAVAILABLE")
                }
                return
            }
        }
        // Finish a static wallpaper's page movement before idling. A live wallpaper can move
        // on its own, so keep probing at the same idle cadence as notification glass.
        if (requested || liveWallpaper || !cadence.isIdle) {
            schedule(cadence.delayMillis(view.display?.refreshRate))
        }
    }

    private fun publish(frame: Bitmap?) {
        if (texture == null && frame == null) return
        texture = frame
        shader = frame?.let { image ->
            BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            }
        }
        changed()
    }

    private fun visible(view: View) =
        view.isAttachedToWindow && view.isShown && view.hasWindowFocus() && view.isHardwareAccelerated

    /** The same wallpaper-only WindowManager call the launcher uses for local label colors. */
    class Reader(context: Context) {
        private val power = context.getSystemService(PowerManager::class.java)
        private val keyguard = context.getSystemService(KeyguardManager::class.java)
        private val wallpapers = WallpaperManager.getInstance(context)
        private val service = DexSymbols.findClass("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService").invoke(null)
        private val screenshot = DexSymbols.findClass("android.view.IWindowManager")
            .getMethod("screenshotWallpaper")

        init {
            check(screenshot.returnType == Bitmap::class.java)
        }

        fun isLive(): Boolean = runCatching { wallpapers.wallpaperInfo != null }.getOrDefault(false)

        fun capture(width: Int, height: Int, crop: Rect): Bitmap? {
            return runCatching {
                if (power?.isInteractive != true || keyguard?.isKeyguardLocked != false) return null
                val source = screenshot.invoke(service) as? Bitmap ?: return null
                try {
                    if (source.width == width && source.height == height) cropTexture(source, crop) else null
                } finally {
                    source.recycle()
                }
            }.getOrNull()
        }
    }

    companion object {
        internal fun cropTexture(source: Bitmap, crop: Rect): Bitmap {
            val destination = Rect(0, 0, (crop.width() + 1) / 2, (crop.height() + 1) / 2)
            val picture = Picture()
            val recording: Canvas = picture.beginRecording(destination.width(), destination.height())
            try {
                recording.drawBitmap(source, crop, destination, Paint(Paint.FILTER_BITMAP_FLAG))
            } finally {
                picture.endRecording()
            }
            // Convert only the cropped texture so the worker can compare pixels without
            // reading a full-size hardware buffer back from the GPU.
            return Bitmap.createBitmap(picture, destination.width(), destination.height(), Bitmap.Config.ARGB_8888)
        }
    }
}
