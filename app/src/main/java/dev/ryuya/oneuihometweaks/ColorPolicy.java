package dev.ryuya.oneuihometweaks;

/** Pure policy: unknown colors and unsupported context always use the original value. */
public final class ColorPolicy {
  public static final int AUTO = 0, BLACK = 1, WHITE = 2, OFF = 3, LOCAL = 4;

  private ColorPolicy() {}

  public static Integer darkValue(int mode, boolean supportedWallpaper, Integer hints) {
    if (!supportedWallpaper || mode == OFF) return null;
    if (mode == BLACK) return 1;
    if (mode == WHITE) return 0;
    if (mode != AUTO || hints == null) return null;
    return (hints & 1) != 0 ? 1 : 0;
  }

  public static boolean isMainHomeEvent(int which) {
    int mode = which & 60;
    return (which & 1) != 0 && (mode == 0 || mode == 4);
  }
}
