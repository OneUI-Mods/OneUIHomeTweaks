package dev.ryuya.oneuihometweaks

import android.graphics.Bitmap
import android.graphics.ColorFilter
import android.os.Handler
import androidx.annotation.RequiresApi
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Prepares hidden surfaces and icon images independently before the stock handoff. */
@RequiresApi(34)
internal class LauncherOpeningPreparation(private val main: Handler) {
    private val layerWorker = idleWorker("HomeOpeningLayers")
    private val imageWorker = idleWorker("HomeOpeningIconUpload")
    private val allocatingLayers = AtomicBoolean()
    private val pendingImages = AtomicInteger()
    @Volatile private var closed = false

    fun prepareLayers(job: () -> Unit): Boolean {
        if (closed || !allocatingLayers.compareAndSet(false, true)) return false
        try {
            layerWorker.execute {
                try {
                    job()
                } finally {
                    allocatingLayers.set(false)
                }
            }
            return true
        } catch (_: RejectedExecutionException) {
            allocatingLayers.set(false)
            return false
        }
    }

    fun upload(bitmap: Bitmap, filter: ColorFilter?, result: (LauncherIconBuffer?) -> Unit): Boolean {
        if (closed || pendingImages.get() >= 2) return false
        pendingImages.incrementAndGet()
        try {
            imageWorker.execute {
                try {
                    LauncherIconBuffer.render(bitmap, imageWorker, "One UI opening icon", filter) { image ->
                        completeImage()
                        if (!main.post { result(image) }) image?.close()
                    }
                } catch (failure: Exception) {
                    completeImage()
                    main.post { result(null) }
                    HookEntry.log("LAUNCHER_OPEN_ICON_UPLOAD_FAILED ${failure.javaClass.simpleName}")
                }
            }
            return true
        } catch (_: RejectedExecutionException) {
            completeImage()
            return false
        }
    }

    fun close() {
        closed = true
        layerWorker.shutdown()
        // HardwareBufferRenderer schedules completion on imageWorker after the upload job returns.
        if (pendingImages.get() == 0) imageWorker.shutdown()
    }

    private fun completeImage() {
        if (pendingImages.decrementAndGet() == 0 && closed) imageWorker.shutdown()
    }

    private fun idleWorker(name: String) =
        ThreadPoolExecutor(1, 1, 15L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) { job ->
            Thread(job, name)
        }.apply { allowCoreThreadTimeOut(true) }
}
