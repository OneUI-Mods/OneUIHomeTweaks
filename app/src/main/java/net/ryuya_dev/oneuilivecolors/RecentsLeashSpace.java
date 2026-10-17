package net.ryuya_dev.oneuilivecolors;

/**
 * Resolves the effective source space from the actual player basis, including normalized leashes.
 */
final class RecentsLeashSpace {
  static RecentsCardSpace.Space resolve(
      RecentsCardSpace.Space root, int width, int height, float[] basis) {
    if (basis == null || basis.length != 9)
      throw new IllegalArgumentException("Unmeasured player basis");
    for (float f : basis)
      if (!Float.isFinite(f)) throw new IllegalArgumentException("Nonfinite basis");
    RecentsCardSpace.Space match = null;
    for (int rotation = 0; rotation < 4; rotation++) {
      var candidate = new RecentsCardSpace.Space(width, height, rotation);
      RecentsMotionPath.Transform t;
      try {
        t = RecentsCardSpace.between(root, candidate);
      } catch (IllegalArgumentException mismatch) {
        continue;
      }
      double[] values = {t.a(), t.c(), t.tx(), t.b(), t.d(), t.ty(), 0, 0, 1};
      boolean equal = true;
      for (int i = 0; i < 9; i++) equal &= Math.abs(values[i] - basis[i]) <= .001;
      if (equal) {
        if (match != null) throw new IllegalArgumentException("Ambiguous basis");
        match = candidate;
      }
    }
    if (match == null)
      throw new IllegalArgumentException("Player basis does not match measured source bounds");
    return match;
  }

  static RecentsMotionPath.Pose pixelMatchedPose(
      RecentsMotionPath.Transform transform, int width, int height, double maximumError) {
    if (width <= 0
        || height <= 0
        || !Double.isFinite(maximumError)
        || maximumError < 0
        || transform.a() * transform.d() - transform.b() * transform.c() <= 0)
      throw new IllegalArgumentException("Invalid card pose");
    double c = (transform.a() + transform.d()) * .5, s = (transform.b() - transform.c()) * .5;
    double scale = Math.hypot(c, s);
    var center = transform.map(width * .5, height * .5);
    var pose = new RecentsMotionPath.Pose(center.x(), center.y(), scale, Math.atan2(s, c));
    var approximation = pose.matrix(width * .5, height * .5);
    for (int x : new int[] {0, width})
      for (int y : new int[] {0, height}) {
        var exact = transform.map(x, y);
        var projected = approximation.map(x, y);
        if (Math.hypot(exact.x() - projected.x(), exact.y() - projected.y()) > maximumError)
          throw new IllegalArgumentException("Card would deform beyond pixel tolerance");
      }
    return pose;
  }
}
