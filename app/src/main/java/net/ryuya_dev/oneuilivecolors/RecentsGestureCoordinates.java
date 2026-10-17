package net.ryuya_dev.oneuilivecolors;

/**
 * Retain the gesture's original axes while a landscape app's display becomes portrait. This is
 * geometry only: it never requests a rotation or assumes the parent transform of an app leash.
 */
final class RecentsGestureCoordinates {
  enum Region {
    BOTTOM,
    LEFT,
    RIGHT
  }

  record Position(RecentsMotionPath.Point displacement, RecentsMotionPath.Point virtualPointer) {}

  private final double naturalWidth, naturalHeight, initialWidth, initialHeight;
  private final RecentsMotionPath.Transform initialDisplay;
  private final RecentsMotionPath.Point down;
  private final Region region;

  RecentsGestureCoordinates(
      int rotation, double width, double height, double downX, double downY, Region region) {
    if (rotation != 1 && rotation != 3
        || !Double.isFinite(width)
        || !Double.isFinite(height)
        || width <= height
        || height <= 0
        || downX < 0
        || downY < 0
        || downX > width
        || downY > height
        || region == null) throw new IllegalArgumentException("Not a fullscreen landscape gesture");
    this.naturalWidth = height;
    this.naturalHeight = width;
    this.initialWidth = width;
    this.initialHeight = height;
    this.initialDisplay =
        RecentsMotionPath.Transform.display(rotation, naturalWidth, naturalHeight);
    this.down = new RecentsMotionPath.Point(downX, downY);
    this.region = region;
  }

  /**
   * Map each raw pointer back into the captured start display before taking displacement. Never
   * accumulate deltas across a rotation. The final virtual pointer belongs to the portrait cards.
   */
  Position pointer(int currentRotation, double x, double y) {
    var current = RecentsMotionPath.Transform.display(currentRotation, naturalWidth, naturalHeight);
    var original = current.inverse().then(initialDisplay).map(x, y);
    double dx, dy;
    // These are the verified native AnimationSessionKt.displacement axis conventions.
    if (region == Region.BOTTOM) {
      dx = original.x() - down.x();
      dy = original.y() - down.y();
    } else {
      dx = down.y() - original.y();
      dy = original.x() - down.x();
    }
    dx *= naturalWidth / initialWidth;
    dy *= naturalHeight / initialHeight;
    var displacement = new RecentsMotionPath.Point(dx, dy);
    var pointer =
        new RecentsMotionPath.Point(
            down.x() * naturalWidth / initialWidth + (region == Region.LEFT ? -dx : dx),
            naturalHeight + dy);
    return new Position(displacement, pointer);
  }

  /** Apply to the whole cloned MotionEvent, including all pointers and historical samples. */
  RecentsMotionPath.Transform pointerTransform(int currentRotation) {
    var current = RecentsMotionPath.Transform.display(currentRotation, naturalWidth, naturalHeight);
    var original = current.inverse().then(initialDisplay);
    double kx = naturalWidth / initialWidth, ky = naturalHeight / initialHeight;
    RecentsMotionPath.Transform virtual;
    if (region == Region.BOTTOM)
      virtual = new RecentsMotionPath.Transform(kx, 0, 0, ky, 0, naturalHeight - down.y() * ky);
    else if (region == Region.LEFT)
      virtual =
          new RecentsMotionPath.Transform(
              0, ky, kx, 0, kx * (down.x() - down.y()), naturalHeight - down.x() * ky);
    else
      virtual =
          new RecentsMotionPath.Transform(
              0, ky, -kx, 0, kx * (down.x() + down.y()), naturalHeight - down.x() * ky);
    return original.then(virtual);
  }
}
