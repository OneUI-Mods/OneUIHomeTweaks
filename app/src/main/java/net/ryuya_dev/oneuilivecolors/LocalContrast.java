package net.ryuya_dev.oneuilivecolors;

import java.util.Arrays;

/** Contrast of real sRGB pixels; unknown/transparent regions never invent a color. */
final class LocalContrast {
  static final int BLACK = 0xff000000, WHITE = 0xffffffff;

  /** The same local pixels must settle before replacing a label's last displayed color. */
  static boolean settled(int[] before, int[] after) {
    if (before == null || after == null || before.length != after.length || after.length < 8)
      return false;
    long difference = 0;
    for (int i = 0; i < before.length; i++) {
      difference += Math.abs((before[i] & 255) - (after[i] & 255));
      difference += Math.abs(((before[i] >> 8) & 255) - ((after[i] >> 8) & 255));
      difference += Math.abs(((before[i] >> 16) & 255) - ((after[i] >> 16) & 255));
    }
    return difference <= after.length * 3L * 4; // Average change at most four sRGB levels/channel.
  }

  static Integer choose(int[] pixels, Integer previous) {
    double[] black = new double[pixels.length], white = new double[pixels.length];
    int count = 0;
    for (int pixel : pixels) {
      if ((pixel >>> 24) < 240) continue;
      double luminance =
          0.2126 * linear((pixel >> 16) & 255)
              + 0.7152 * linear((pixel >> 8) & 255)
              + 0.0722 * linear(pixel & 255);
      black[count] = (luminance + 0.05) / 0.05;
      white[count++] = 1.05 / (luminance + 0.05);
    }
    if (count < 8 || count < pixels.length * 0.75) return null;
    Arrays.sort(black, 0, count);
    Arrays.sort(white, 0, count);
    // Favor contrast in the harder fifth of the label area, rather than a single mean color.
    int lowerFifth = (count - 1) / 5;
    double b = black[lowerFifth], w = white[lowerFifth];
    // A small margin prevents rapid flips around equal contrast during wallpaper transitions.
    if (previous != null) {
      if (previous == BLACK && w <= b * 1.12) return BLACK;
      if (previous == WHITE && b <= w * 1.12) return WHITE;
    }
    return b >= w ? BLACK : WHITE;
  }

  private static double linear(int component) {
    double value = component / 255.0;
    return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
  }
}
