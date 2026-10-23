package dev.ryuya.oneuihometweaks;

/** Affine geometry for the live app Surface inside a native Recents card. */
final class RecentsAffine {
  record Point(double x, double y) {
    Point {
      finite(x, y);
    }
  }

  /** Affine matrix [a c tx; b d ty; 0 0 1]. then(B) means B * this. */
  record Transform(double a, double b, double c, double d, double tx, double ty) {
    Transform {
      finite(a, b, c, d, tx, ty);
      if (Math.abs(a * d - b * c) < 1e-12) throw new IllegalArgumentException("Singular transform");
    }

    Point map(double x, double y) {
      return new Point(a * x + c * y + tx, b * x + d * y + ty);
    }

    Transform then(Transform next) {
      return new Transform(
          next.a * a + next.c * b,
          next.b * a + next.d * b,
          next.a * c + next.c * d,
          next.b * c + next.d * d,
          next.a * tx + next.c * ty + next.tx,
          next.b * tx + next.d * ty + next.ty);
    }

    Transform inverse() {
      double determinant = a * d - b * c;
      return new Transform(
          d / determinant,
          -b / determinant,
          -c / determinant,
          a / determinant,
          (c * ty - d * tx) / determinant,
          (b * tx - a * ty) / determinant);
    }

    /**
     * Natural portrait pixels to logical display pixels, matching quarter-turn display rotations.
     */
    static Transform display(int rotation, double naturalWidth, double naturalHeight) {
      positive(naturalWidth, naturalHeight);
      return switch (rotation) {
        case 0 -> new Transform(1, 0, 0, 1, 0, 0);
        case 1 -> new Transform(0, -1, 1, 0, 0, naturalWidth);
        case 2 -> new Transform(-1, 0, 0, -1, naturalWidth, naturalHeight);
        case 3 -> new Transform(0, 1, -1, 0, naturalHeight, 0);
        default -> throw new IllegalArgumentException("Unknown display rotation");
      };
    }
  }

  private static void finite(double... values) {
    for (double value : values)
      if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite geometry");
  }

  private static void positive(double... values) {
    finite(values);
    for (double value : values)
      if (value <= 0) throw new IllegalArgumentException("Nonpositive geometry");
  }
}
