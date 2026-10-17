package net.ryuya_dev.oneuilivecolors;

/** Composes measured snapshot pixels and native card geometry without changing its aspect/style. */
final class RecentsLiveGeometry {
  static RecentsMotionPath.Transform compose(
      int width,
      int height,
      int snapshotWidth,
      int snapshotHeight,
      double scale,
      float[] shader,
      float[] global) {
    if (width <= 0
        || height <= 0
        || snapshotWidth <= 0
        || snapshotHeight <= 0
        || !Double.isFinite(scale)
        || scale <= 0
        || Math.abs(snapshotWidth - width * scale) > 1
        || Math.abs(snapshotHeight - height * scale) > 1)
      throw new IllegalArgumentException("Snapshot does not cover the remote app");
    return new RecentsMotionPath.Transform(scale, 0, 0, scale, 0, 0)
        .then(affine(shader))
        .then(affine(global));
  }

  static RecentsMotionPath.Transform compose(
      RecentsCardSpace.Space source,
      int bitmapWidth,
      int bitmapHeight,
      int snapshotRotation,
      double scale,
      float[] shader,
      float[] global) {
    if (!Double.isFinite(scale) || scale <= 0 || snapshotRotation < 0 || snapshotRotation > 3)
      throw new IllegalArgumentException("Invalid snapshot metadata");
    int naturalWidth = source.rotation() % 2 == 0 ? source.width() : source.height();
    int naturalHeight = source.rotation() % 2 == 0 ? source.height() : source.width();
    int width = snapshotRotation % 2 == 0 ? naturalWidth : naturalHeight;
    int height = snapshotRotation % 2 == 0 ? naturalHeight : naturalWidth;
    if (bitmapWidth <= 0
        || bitmapHeight <= 0
        || Math.abs(bitmapWidth - width * scale) > 1
        || Math.abs(bitmapHeight - height * scale) > 1)
      throw new IllegalArgumentException("Snapshot does not match source display geometry");
    var snapshot = new RecentsCardSpace.Space(width, height, snapshotRotation);
    return RecentsCardSpace.between(source, snapshot)
        .then(new RecentsMotionPath.Transform(scale, 0, 0, scale, 0, 0))
        .then(affine(shader))
        .then(affine(global));
  }

  private static RecentsMotionPath.Transform affine(float[] m) {
    if (m == null || m.length != 9) throw new IllegalArgumentException("Unknown matrix");
    for (float f : m)
      if (!Float.isFinite(f)) throw new IllegalArgumentException("Nonfinite matrix");
    if (m[6] != 0 || m[7] != 0 || m[8] != 1) throw new IllegalArgumentException("Perspective card");
    return new RecentsMotionPath.Transform(m[0], m[3], m[1], m[4], m[2], m[5]);
  }
}
