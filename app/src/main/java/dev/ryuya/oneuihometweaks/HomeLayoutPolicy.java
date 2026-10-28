package dev.ryuya.oneuihometweaks;

/** Pure geometry checks for the reviewed, centered-camera Galaxy phone profile. */
final class HomeLayoutPolicy {
  static final long SETTLE_MS = 750;
  static final long MAX_WAIT_MS = 10000;

  private HomeLayoutPolicy() {}

  static boolean consistent(int width, int height, int left, int top, int right, int bottom) {
    if (width <= 0 || height <= 0 || width == height) return false;
    return width < height ? left == 0 && right == 0 : top == 0 && bottom == 0;
  }

  static boolean sameBounds(int width, int height, int otherWidth, int otherHeight) {
    return width > 0 && height > 0 && width == otherWidth && height == otherHeight;
  }
}
