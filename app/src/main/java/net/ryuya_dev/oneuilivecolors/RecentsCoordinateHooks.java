package net.ryuya_dev.oneuilivecolors;

import java.util.function.Consumer;

/**
 * Opt-in experimental entry point. Native viewport dimensions and card targets are not rewritten.
 */
final class RecentsCoordinateHooks {
  private final RecentsViewportHooks viewport;

  RecentsCoordinateHooks(Consumer<Throwable> failure) {
    viewport = new RecentsViewportHooks(failure);
  }

  void install(String apk, ClassLoader loader) throws Exception {
    viewport.install(apk, loader);
  }

  void setEnabled(boolean enabled) {
    viewport.setEnabled(enabled);
  }

  void uninstall() {
    viewport.uninstall();
  }
}
