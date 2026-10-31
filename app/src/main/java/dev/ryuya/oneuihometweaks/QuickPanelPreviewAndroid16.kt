package dev.ryuya.oneuihometweaks

import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams
import java.lang.reflect.Method
import kotlin.jvm.JvmStatic
import kotlin.math.min

/* Draws retained native View controls into a second canvas without reparenting or relayout. */
class QuickPanelPreviewAndroid16(loader: ClassLoader?, ready: Runnable) :
    QuickPanelNativePreview.Renderer {
    private val tracks: Array<View?> = arrayOfNulls<View>(2)
    private val buttons: Array<View?> = arrayOfNulls<View>(2)

    init {
        for (kind in 0..1) {
            val index: Int = kind
            val type =
                Class.forName(
                    "com.android.systemui.qs.bar." +
                        (if (kind == 0) "BrightnessBar" else "VolumeBar"),
                    false,
                    loader,
                )
            var layout: Method? = null
            for (candidate in type.getDeclaredMethods()) if (
                candidate.getName() == "updateLayout" && candidate.getParameterCount() == 4
            )
                layout = candidate
            if (layout == null) throw NoSuchMethodException("Native View layout")
            ModernHook.hookMethod(
                layout,
                object : ModernHook.Callback() {
                    override fun afterHookedMethod(p: ModernHook.Param) {
                        if (p.hasThrowable()) return
                        tracks[index] = p.args[if (index == 0) 3 else 2] as View?
                        buttons[index] = p.args[if (index == 0) 2 else 1] as View?
                        ready.run()
                    }
                },
            )
        }
    }

    override fun ready(): Boolean {
        return tracks[0] != null && tracks[1] != null
    }

    override fun populate(root: FrameLayout?) {
        root!!.addView(
            object : View(root!!.getContext()) {
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    for (i in 0..1) {
                        val width: Float = getWidth() / 2f
                        QuickPanelPreviewAndroid16.drawNative(
                            canvas,
                            tracks[i],
                            i * width,
                            0f,
                            width,
                            getHeight() * .76f,
                        )
                        QuickPanelPreviewAndroid16.drawNative(
                            canvas,
                            buttons[i],
                            i * width,
                            getHeight() * .76f,
                            width,
                            getHeight() * .24f,
                        )
                    }
                }
            },
            LayoutParams(-1, -1),
        )
    }

    override fun clear(root: FrameLayout?) {
        root!!.removeAllViews()
    }

    companion object {
        @JvmStatic
        private fun drawNative(
            canvas: Canvas,
            source: View?,
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) {
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) return
            val bounds = RectF(0f, 0f, source.getWidth().toFloat(), source.getHeight().toFloat())
            source.getMatrix().mapRect(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) return
            val scale = min(w / bounds.width(), h / bounds.height())
            canvas.save()
            canvas.translate(
                x + (w - bounds.width() * scale) / 2,
                y + (h - bounds.height() * scale) / 2,
            )
            canvas.scale(scale, scale)
            canvas.translate(-bounds.left, -bounds.top)
            canvas.concat(source.getMatrix())
            source.draw(canvas)
            canvas.restore()
        }
    }
}
