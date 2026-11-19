package dev.ryuya.oneuihometweaks

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** A local sample scene. Its glass can sample this known background directly. */
class GlassPreview(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val material = GlassMaterial(density)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val touchLight = GlassTouchLight(::postInvalidateOnAnimation)
    private val screenLocation = IntArray(2)
    private val previewClip = Path()
    private var sky: Shader? = null
    private val lens = if (Build.VERSION.SDK_INT >= 33) runCatching { GlassLens(density) }.getOrNull() else null
    internal var appearance = GlassAppearance()
        set(value) {
            field = value
            material.tint = value.tint
            invalidate()
        }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        touchLight.touch(this, event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        touchLight.clear()
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val artwork = Canvas(image)
        val width = w.toFloat()
        val height = h.toFloat()
        val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        artworkPaint.shader = LinearGradient(
            0f, height, width, 0f,
            intArrayOf(0xff082b4c.toInt(), 0xff20385d.toInt(), 0xff0c6665.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        artwork.drawPaint(artworkPaint)
        val ribbon = Path()
        for (layer in 0..3) {
            val offset = layer * height * 0.15f
            ribbon.rewind()
            ribbon.moveTo(-width * 0.12f, height * 0.62f + offset)
            ribbon.cubicTo(width * 0.26f, -height * 0.25f + offset,
                width * 0.89f, height * 1.03f + offset, width * 1.08f, height * 0.28f + offset)
            artworkPaint.style = Paint.Style.STROKE
            artworkPaint.strokeWidth = height * 0.10f
            artworkPaint.shader = LinearGradient(
                0f, height, width, 0f, 0xff44869b.toInt(), 0xff7ca68a.toInt(), Shader.TileMode.CLAMP,
            )
            artworkPaint.alpha = 184 - layer * 20
            artwork.drawPath(ribbon, artworkPaint)
            artworkPaint.shader = null
            artworkPaint.color = 0x66e2f0c8
            artworkPaint.strokeWidth = density
            artwork.drawPath(ribbon, artworkPaint)
        }
        sky = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        bounds.set(0f, 0f, width, height)
        previewClip.rewind()
        previewClip.addRoundRect(bounds, 24f * density, 24f * density, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0 || height <= 0) return
        val save = canvas.save()
        canvas.clipPath(previewClip)
        val background = sky
        paint.shader = background
        canvas.drawPaint(paint)
        paint.shader = null
        val side = width * 0.16f
        val panelLeft = width * 0.13f
        val tileTop = height * 0.11f
        val tileBottom = height * 0.34f
        val night = resources.configuration.isNightModeActive
        val foreground = if (night) 0xffe5f6f6.toInt() else 0xff13272e.toInt()
        for (index in 0..2) {
            val left = panelLeft + index * width * 0.21f
            plate(canvas, background, left, tileTop, left + side, tileBottom, appearance.quick, night, panel = true)
            paint.color = foreground
            canvas.drawCircle(left + side / 2f, (tileTop + tileBottom) / 2f, 3.7f * density, paint)
        }
        val first = height * 0.42f
        plate(canvas, background, panelLeft, first, width - panelLeft, height * 0.65f,
            appearance.notifications, night, panel = true)
        paint.color = foreground
        canvas.drawRoundRect(panelLeft + width * .065f, first + height * .075f, width * .61f,
            first + height * .105f, 3f * density, 3f * density, paint)
        paint.color = if (night) 0xffa5c6c4.toInt() else 0xff344f55.toInt()
        canvas.drawRoundRect(panelLeft + width * .065f, first + height * .13f, width * .49f,
            first + height * .153f, 3f * density, 3f * density, paint)
        val dockTop = height * .74f
        val dockBottom = height * .92f
        plate(canvas, background, width * .19f, dockTop, width * .81f, dockBottom,
            appearance.home, night, panel = false, dock = true)
        for (index in 0..3) {
            paint.color = when (index) {
                0 -> 0xff8ecba5.toInt()
                1 -> 0xffc6a7eb.toInt()
                2 -> 0xffffd39e.toInt()
                else -> 0xff8dcce6.toInt()
            }
            val x = width * (.29f + .14f * index)
            val y = (dockTop + dockBottom) / 2f
            val r = 6f * density
            canvas.drawRoundRect(x - r, y - r, x + r, y + r, r * .44f, r * .44f, paint)
        }
        val volumeLeft = width * .89f
        val volumeRight = width * .975f
        val volumeTop = height * .12f
        val volumeBottom = height * .62f
        val volumeNight = when (appearance.volumeTheme) {
            1 -> false
            2 -> true
            else -> night
        }
        plate(canvas, background, volumeLeft, volumeTop, volumeRight, volumeBottom,
            appearance.volume, volumeNight, panel = true)
        val inset = (volumeRight - volumeLeft) * .20f
        paint.color = if (volumeNight) 0xffe5f6f6.toInt() else 0xff13272e.toInt()
        paint.alpha = 176
        canvas.drawRoundRect(volumeLeft + inset, height * .38f, volumeRight - inset, volumeBottom - inset,
            inset, inset, paint)
        paint.alpha = 255
        canvas.restoreToCount(save)
    }

    private fun plate(
        canvas: Canvas, scene: Shader?, left: Float, top: Float, right: Float, bottom: Float,
        enabled: Boolean, night: Boolean, panel: Boolean, dock: Boolean = false,
    ) {
        val corner = ((bottom - top) * .46f).coerceAtMost((right - left) * .46f)
        val strength = if (enabled) appearance.strength else 0
        paint.color = if (night) 0xff193642.toInt() else 0xffd4e5ea.toInt()
        val refracted = if (Build.VERSION.SDK_INT >= 33 && strength > 0 && scene != null &&
            lens != null && canvas.isHardwareAccelerated) {
            lens.draw(canvas, scene, left, top, right, bottom, corner, strength, dark = night,
                opacity = if (dock) (strength / 45f).coerceAtMost(1f) else 1f,
                tintFactor = material.tintFactor, panel = panel, dock = dock)
            true
        } else {
            canvas.drawRoundRect(left, top, right, bottom, corner, corner, paint)
            false
        }
        val light = touchLight.pointer?.let {
            getLocationOnScreen(screenLocation)
            it.onSurface(left + screenLocation[0], top + screenLocation[1], right - left, bottom - top, density)
        }
        material.draw(canvas, left, top, right, bottom, corner, corner, strength, dark = night,
            fill = dock || !refracted, glint = light)
    }

}
