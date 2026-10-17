package net.ryuya_dev.oneuilivecolors;

/** A virtual calculator size is insufficient: the owned view and native styler must agree. */
final class RecentsViewportContract {
  private RecentsViewportContract() {}

  static void verify(
      int portraitWidth,
      int portraitHeight,
      int viewWidth,
      int viewHeight,
      double styleLeft,
      double styleTop,
      double styleWidth,
      double styleHeight) {
    if (portraitWidth <= 0
        || portraitHeight <= portraitWidth
        || viewWidth != portraitWidth
        || viewHeight != portraitHeight
        || !Double.isFinite(styleLeft)
        || !Double.isFinite(styleTop)
        || !Double.isFinite(styleWidth)
        || !Double.isFinite(styleHeight)
        || Math.abs(styleLeft) > 1
        || Math.abs(styleTop) > 1
        || Math.abs(styleWidth - portraitWidth) > 1
        || Math.abs(styleHeight - portraitHeight) > 1) {
      throw new IllegalStateException(
          "Gesture viewport/styler mismatch: view="
              + viewWidth
              + "x"
              + viewHeight
              + " style="
              + styleLeft
              + ","
              + styleTop
              + "/"
              + styleWidth
              + "x"
              + styleHeight
              + " expected="
              + portraitWidth
              + "x"
              + portraitHeight);
    }
  }
}
