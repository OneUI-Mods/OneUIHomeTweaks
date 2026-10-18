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
      RecentsAffine.Transform t;
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
}
