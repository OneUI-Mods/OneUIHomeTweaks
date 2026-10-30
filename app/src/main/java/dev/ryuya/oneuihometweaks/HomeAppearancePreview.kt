package dev.ryuya.oneuihometweaks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.Path
import android.graphics.Path.Direction
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/* A synthetic label sample, with no wallpaper access or real app information. */
class HomeAppearancePreview(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip: Path = Path()

    init {
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = getResources().getDisplayMetrics().density
        val w = getWidth().toFloat()
        val h = getHeight().toFloat()
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, w, h), 24 * d, 24 * d, Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawColor(Color.rgb(235, 238, 245))
        paint.setColor(Color.rgb(25, 28, 36))
        canvas.drawRect(w / 2, 0f, w, h, paint)
        val mode =
            getContext()
                .getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
                .getInt("label", ColorPolicy.LOCAL)
        val tint = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        for (i in 0..3) {
            val x: Float = w * (i + .5f) / 4
            paint.setColor(tint)
            canvas.drawRoundRect(
                x - 19 * d,
                h / 2 - 30 * d,
                x + 19 * d,
                h / 2 + 8 * d,
                12 * d,
                12 * d,
                paint,
            )
            paint.setColor(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
            )
            canvas.drawCircle(x, h / 2 - 11 * d, 7 * d, paint)
            paint.setColor(
                if (mode == ColorPolicy.BLACK) Color.BLACK
                else if (mode == ColorPolicy.WHITE) Color.WHITE
                else if (i < 2) Color.BLACK else Color.WHITE
            )
            paint.setTextAlign(Align.CENTER)
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            paint.setTextSize(16 * d)
            canvas.drawText("Aa", x, h / 2 + 31 * d, paint)
        }
        canvas.restore()
    }
}
