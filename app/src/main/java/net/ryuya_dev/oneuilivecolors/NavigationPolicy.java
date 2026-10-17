package net.ryuya_dev.oneuilivecolors;

/** Mode values verified against Samsung NavigationModeSource: 0/1 buttons, 2/3 gestures. */
final class NavigationPolicy {
  static boolean hide(boolean enabled, int mode, int hint, boolean mainFullscreen) {
    return enabled && mainFullscreen && (mode == 2 || mode == 3) && hint == 1;
  }

  static boolean restoreVisible(boolean nativeVisible, int mode, int hint) {
    return nativeVisible && !((mode == 2 || mode == 3) && hint == 0);
  }
}
