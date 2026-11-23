package dev.ryuya.oneuihometweaks

import android.graphics.Bitmap
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.hardware.HardwareBuffer
import android.os.Trace
import dev.ryuya.oneuihometweaks.compat.DexKitNative
import java.io.IOException
import java.nio.ByteBuffer

/** Copies the existing premultiplied texture on the upload worker, leaving RenderThread available for home. */
internal object LauncherIconCpuUpload {
    private const val MAX_COPY_BYTES = 512 * 512 * 4
    private const val NIGHT_TINT = 0x19000000
    val stockNightBackgroundFilter = PorterDuffColorFilter(NIGHT_TINT, PorterDuff.Mode.SRC_ATOP)
    private val pixels = ThreadLocal<ByteBuffer>()
    private val available by lazy {
        try {
            DexKitNative.loadModuleLibrary("oneui_icon_upload")
            true
        } catch (failure: LinkageError) {
            HookEntry.log("LAUNCHER_ICON_CPU_UPLOAD_UNAVAILABLE ${failure.javaClass.simpleName}")
            false
        } catch (failure: IOException) {
            HookEntry.log("LAUNCHER_ICON_CPU_UPLOAD_UNAVAILABLE ${failure.javaClass.simpleName}")
            false
        }
    }

    fun copyIfSupported(bitmap: Bitmap, filter: ColorFilter? = null): HardwareBuffer? {
        if (!BuildConfig.LAUNCHER_CPU_ICON_UPLOAD || bitmap.isRecycled || bitmap.config != Bitmap.Config.ARGB_8888 ||
            bitmap.width !in 1..512 || bitmap.height !in 1..512 ||
            bitmap.byteCount !in 1..MAX_COPY_BYTES || bitmap.colorSpace?.isSrgb != true ||
            (bitmap.hasAlpha() && !bitmap.isPremultiplied)
        ) return null
        val colorScale = when {
            filter == null -> 255
            filter.javaClass === PorterDuffColorFilter::class.java && stockNightBackgroundFilter == filter ->
                255 - (NIGHT_TINT ushr 24)
            else -> return null
        }
        if (!available) return null
        Trace.beginSection(if (filter == null) "OneUI icon CPU upload" else "OneUI icon CPU night upload")
        return try {
            val count = bitmap.byteCount
            val scratch = pixels.get()?.takeIf { it.capacity() >= count }
                ?: ByteBuffer.allocateDirect(count).also(pixels::set)
            scratch.clear()
            // The NDK bitmap unlock can invalidate the stock bitmap's generation even after a read.
            bitmap.copyPixelsToBuffer(scratch)
            uploadPremultiplied(scratch, bitmap.width, bitmap.height, bitmap.rowBytes, !bitmap.hasAlpha(), colorScale)
        } catch (failure: LinkageError) {
            HookEntry.log("LAUNCHER_ICON_CPU_UPLOAD_FAILED ${failure.javaClass.simpleName}")
            null
        } catch (failure: RuntimeException) {
            HookEntry.log("LAUNCHER_ICON_CPU_UPLOAD_FAILED ${failure.javaClass.simpleName}")
            null
        } finally {
            Trace.endSection()
        }
    }

    private external fun uploadPremultiplied(
        pixels: ByteBuffer, width: Int, height: Int, stride: Int, opaque: Boolean, colorScale: Int,
    ): HardwareBuffer?
}
