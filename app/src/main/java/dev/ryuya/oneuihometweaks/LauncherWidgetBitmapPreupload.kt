package dev.ryuya.oneuihometweaks

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Trace
import android.widget.ImageView

/**
 * Starts the GPU upload of large widget bitmaps as soon as they are applied.
 *
 * Bitmap-rendered widgets (for example KWGT) send a new full-size bitmap whenever Home becomes visible.
 * Without a hint, HWUI uploads it inside the first Home frame, which the stock close transition waits for.
 */
internal object LauncherWidgetBitmapPreupload {
    private const val MIN_BYTES = 1 shl 20

    fun install(app: Application) {
        if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.matchesClockBuild(37, Build.DISPLAY)) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (!HookCompatibility.matchesLauncherVersion(37, version)) return
            val setDrawable = ImageView::class.java.getDeclaredMethod("setImageDrawable", Drawable::class.java)
            // These entry points build a drawable and pass it to setImageDrawable.
            ModernHook.deoptimize(ImageView::class.java.getDeclaredMethod("setImageBitmap", Bitmap::class.java))
            ModernHook.deoptimize(ImageView::class.java.getDeclaredMethod("setImageIcon", Icon::class.java))
            ModernHook.aroundMethod(setDrawable) { chain ->
                val result = chain.proceed()
                val bitmap = (chain.args[0] as? BitmapDrawable)?.bitmap
                if (bitmap != null && bitmap.allocationByteCount >= MIN_BYTES &&
                    !bitmap.isRecycled && bitmap.config != Bitmap.Config.HARDWARE
                ) {
                    if (Trace.isEnabled()) Trace.beginSection("OneUI home widget bitmap preupload ${bitmap.width}x${bitmap.height}")
                    try {
                        bitmap.prepareToDraw()
                    } finally {
                        if (Trace.isEnabled()) Trace.endSection()
                    }
                }
                result
            }
            HookEntry.log("LAUNCHER_WIDGET_BITMAP_PREUPLOAD_READY")
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_WIDGET_BITMAP_PREUPLOAD_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
