package dev.ryuya.oneuihometweaks;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import com.google.android.material.color.MaterialColors;

/** A synthetic label sample, with no wallpaper access or real app information. */
public final class HomeAppearancePreview extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path clip = new Path();

  public HomeAppearancePreview(Context context, AttributeSet attrs) {
    super(context, attrs);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float d = getResources().getDisplayMetrics().density;
    float w = getWidth(), h = getHeight();
    clip.reset();
    clip.addRoundRect(new RectF(0, 0, w, h), 24 * d, 24 * d, Path.Direction.CW);
    canvas.save();
    canvas.clipPath(clip);
    canvas.drawColor(Color.rgb(235, 238, 245));
    paint.setColor(Color.rgb(25, 28, 36));
    canvas.drawRect(w / 2, 0, w, h, paint);
    int mode =
        getContext()
            .getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
            .getInt("label", ColorPolicy.LOCAL);
    int tint = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary);
    for (int i = 0; i < 4; i++) {
      float x = w * (i + .5f) / 4;
      paint.setColor(tint);
      canvas.drawRoundRect(
          x - 19 * d, h / 2 - 30 * d, x + 19 * d, h / 2 + 8 * d, 12 * d, 12 * d, paint);
      paint.setColor(
          MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary));
      canvas.drawCircle(x, h / 2 - 11 * d, 7 * d, paint);
      paint.setColor(
          mode == ColorPolicy.BLACK
              ? Color.BLACK
              : mode == ColorPolicy.WHITE ? Color.WHITE : i < 2 ? Color.BLACK : Color.WHITE);
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      paint.setTextSize(16 * d);
      canvas.drawText("Aa", x, h / 2 + 31 * d, paint);
    }
    canvas.restore();
  }
}
