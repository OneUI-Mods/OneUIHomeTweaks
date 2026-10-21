package net.ryuya_dev.oneuilivecolors;

/** Limits are independent of density, rotation, and the two firmware implementations. */
final class QuickPanelPolicy {
  static final String[] KEYS = {"qs_brightness", "qs_volume", "qs_dark", "qs_sound"};
  static final int MIN = 50, MAX = 120;

  static int percent(int value) {
    return Math.max(MIN, Math.min(MAX, value));
  }

  static float dimension(float nativeDp, int percent) {
    if (!Float.isFinite(nativeDp) || nativeDp <= 0) return nativeDp;
    return Math.max(48f, nativeDp * percent(percent) / 100f);
  }

  static float legacyDimension(float nativeDp, int percent) {
    if (!Float.isFinite(nativeDp) || nativeDp <= 0) return nativeDp;
    return Math.max(24f, nativeDp * percent(percent) / 100f);
  }

  static long thickness(long size, boolean vertical, float ratio) {
    float width = Float.intBitsToFloat((int) (size >>> 32));
    float height = Float.intBitsToFloat((int) size);
    if (!Float.isFinite(width)
        || !Float.isFinite(height)
        || width <= 0
        || height <= 0
        || !Float.isFinite(ratio)
        || ratio <= 0) return size;
    if (vertical) width *= ratio;
    else height *= ratio;
    return ((long) Float.floatToRawIntBits(width) << 32)
        | (Float.floatToRawIntBits(height) & 0xffffffffL);
  }
}
