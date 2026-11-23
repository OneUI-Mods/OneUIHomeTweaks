package dev.ryuya.oneuihometweaks

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.HardwareBufferRenderer
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.hardware.SyncFence
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

@RequiresApi(34)
internal class LauncherIconBuffer(val buffer: HardwareBuffer, val fence: SyncFence?) : AutoCloseable {
    val ready get() = fence?.let { !it.isValid || it.signalTime != SyncFence.SIGNAL_TIME_PENDING } ?: true

    override fun close() {
        fence?.close()
        buffer.close()
    }

    companion object {
        fun render(bitmap: Bitmap, executor: Executor, label: String, filter: ColorFilter? = null,
            result: (LauncherIconBuffer?) -> Unit) {
            val copied = LauncherIconCpuUpload.copyIfSupported(bitmap, filter)
            if (copied != null) result(LauncherIconBuffer(copied, null))
            else renderOnGpu(bitmap, executor, label, filter, result)
        }

        internal fun renderOnGpu(bitmap: Bitmap, executor: Executor, label: String, filter: ColorFilter? = null,
            result: (LauncherIconBuffer?) -> Unit) {
            val width = bitmap.width
            val height = bitmap.height
            val node = RenderNode(label).apply { setPosition(0, 0, width, height) }
            val canvas = node.beginRecording()
            try {
                canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
                val paint = filter?.let { Paint().apply { colorFilter = it } }
                canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), paint)
            } finally {
                node.endRecording()
            }
            val buffer = HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    HardwareBuffer.USAGE_COMPOSER_OVERLAY)
            val renderer = try {
                HardwareBufferRenderer(buffer)
            } catch (failure: RuntimeException) {
                buffer.close()
                throw failure
            }
            try {
                renderer.setContentRoot(node)
                renderer.obtainRenderRequest().draw(executor) { completed ->
                    val upload = LauncherIconBuffer(buffer, completed.fence)
                    renderer.close()
                    node.discardDisplayList()
                    if (completed.status == HardwareBufferRenderer.RenderResult.SUCCESS) result(upload) else {
                        upload.close()
                        result(null)
                    }
                }
            } catch (failure: RuntimeException) {
                renderer.close()
                node.discardDisplayList()
                buffer.close()
                throw failure
            }
        }
    }
}
