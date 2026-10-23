package dev.ryuya.oneuihometweaks;

/** Composes measured snapshot pixels and native card geometry without changing its aspect/style. */
final class RecentsLiveGeometry {
  static final class PerspectiveCard extends IllegalArgumentException {
    PerspectiveCard() {
      super("Perspective card");
    }
  }

  static RecentsAffine.Transform compose(
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
    return composeMeasured(
        new RecentsAffine.Transform(scale, 0, 0, scale, 0, 0), width, height, shader, global);
  }

  static RecentsAffine.Transform compose(
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
    return composeMeasured(
        RecentsCardSpace.between(source, snapshot)
            .then(new RecentsAffine.Transform(scale, 0, 0, scale, 0, 0)),
        source.width(),
        source.height(),
        shader,
        global);
  }

  private static RecentsAffine.Transform composeMeasured(
      RecentsAffine.Transform source, int width, int height, float[] shader, float[] global) {
    double[] m =
        multiply(
            matrix(global),
            multiply(
                matrix(shader),
                new double[] {
                  source.a(), source.c(), source.tx(), source.b(), source.d(), source.ty(), 0, 0, 1
                }));
    if (!Double.isFinite(m[8]) || Math.abs(m[8]) < 1e-12) throw new PerspectiveCard();
    double divisor = m[8];
    for (int i = 0; i < 9; i++) {
      m[i] /= divisor;
      if (!Double.isFinite(m[i])) throw new IllegalArgumentException("Nonfinite composed matrix");
    }
    // Home Up leaves tiny projective residuals even after a card settles. Bound the error over
    // the full source rectangle before dropping those terms. A real perspective stays a snapshot.
    double minW = Double.POSITIVE_INFINITY, maxW = Double.NEGATIVE_INFINITY, maxNumerator = 0;
    for (int x : new int[] {0, width})
      for (int y : new int[] {0, height}) {
        double w = m[6] * x + m[7] * y + 1;
        minW = Math.min(minW, w);
        maxW = Math.max(maxW, w);
        maxNumerator =
            Math.max(
                maxNumerator, Math.hypot(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5]));
      }
    // Linear denominators and affine numerators attain their interval/norm bounds at corners;
    // this bounds every interior pixel too, not just the sampled corners.
    double error = maxNumerator * Math.max(Math.abs(minW - 1), Math.abs(maxW - 1)) / minW;
    if (minW <= 0 || !Double.isFinite(error) || error > .5) throw new PerspectiveCard();
    return new RecentsAffine.Transform(m[0], m[3], m[1], m[4], m[2], m[5]);
  }

  private static double[] matrix(float[] m) {
    if (m == null || m.length != 9) throw new IllegalArgumentException("Unknown matrix");
    double[] result = new double[9];
    for (int i = 0; i < 9; i++) {
      if (!Float.isFinite(m[i])) throw new IllegalArgumentException("Nonfinite matrix");
      result[i] = m[i];
    }
    return result;
  }

  private static double[] multiply(double[] a, double[] b) {
    double[] r = new double[9];
    for (int row = 0; row < 3; row++)
      for (int col = 0; col < 3; col++)
        for (int k = 0; k < 3; k++) r[row * 3 + col] += a[row * 3 + k] * b[k * 3 + col];
    return r;
  }
}
