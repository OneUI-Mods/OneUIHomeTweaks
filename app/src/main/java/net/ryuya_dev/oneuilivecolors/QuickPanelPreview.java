package net.ryuya_dev.oneuilivecolors;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import com.google.android.material.color.MaterialColors;

/** Illustrative size preview; never reads or changes actual device brightness or audio volume. */
public final class QuickPanelPreview extends View {
  private final Paint paint = new Paint(3);
  private boolean legacy;

  void setLegacy(boolean value) {
    legacy = value;
    invalidate();
  }

  private int[] sizes = {100, 100, 100, 100};

  public QuickPanelPreview(Context context, AttributeSet attrs) {
    super(context, attrs);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  void setSizes(int[] value) {
    sizes = value.clone();
    invalidate();
  }

  private int color(int attr) {
    return MaterialColors.getColor(this, attr);
  }

  private void rounded(Canvas c, float x, float y, float w, float h, int color) {
    paint.setColor(color);
    c.drawRoundRect(x, y, x + w, y + h, Math.min(w, h) / 2, Math.min(w, h) / 2, paint);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float unit = Math.min(getWidth() / 320f, getHeight() / 180f);
    canvas.save();
    canvas.translate((getWidth() - 320 * unit) / 2, (getHeight() - 180 * unit) / 2);
    canvas.scale(unit, unit);
    int track = color(com.google.android.material.R.attr.colorSurfaceContainerHighest);
    int fill = color(androidx.appcompat.R.attr.colorPrimary);
    int fg = color(com.google.android.material.R.attr.colorOnPrimary);
    for (int i = 0; i < 2; i++) {
      float cx = 108 + i * 104;
      if (legacy)
        rounded(
            canvas,
            cx - 40,
            0,
            80,
            180,
            color(com.google.android.material.R.attr.colorSurfaceContainerHigh));
      float w = (legacy ? 34 : 56) * sizes[i] / 100f;
      rounded(canvas, cx - w / 2, 12, w, 104, track);
      rounded(
          canvas,
          cx - w / 2,
          12 + 104 * (i == 0 ? .3f : .55f),
          w,
          104 * (i == 0 ? .7f : .45f),
          fill);
      float r = (legacy ? 17 : 22) * sizes[i + 2] / 100f;
      rounded(canvas, cx - r, 150 - r, 2 * r, 2 * r, fill);
      paint.setColor(fg);
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTypeface(Typeface.DEFAULT);
      paint.setTextSize(22);
      canvas.drawText(i == 0 ? "☾" : "♪", cx, 158, paint);
    }
    canvas.restore();
  }
}
