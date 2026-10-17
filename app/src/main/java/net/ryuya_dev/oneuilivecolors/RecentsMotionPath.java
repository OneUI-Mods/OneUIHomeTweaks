package net.ryuya_dev.oneuilivecolors;

/**
 * Gesture-driven similarity transforms in one stable coordinate space. This is independent of
 * Android's changing display configuration. A runtime adapter must supply verified leash bases;
 * display rotation alone is not sufficient to identify a Shell transition's parent transform.
 */
final class RecentsMotionPath {
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

  /** Center in natural portrait pixels, uniform scale, unwrapped radians. */
  record Pose(double x, double y, double scale, double angle) {
    Pose {
      finite(x, y, scale, angle);
      positive(scale);
    }

    Transform matrix(double cropCenterX, double cropCenterY) {
      finite(cropCenterX, cropCenterY);
      double c = Math.cos(angle) * scale;
      double s = Math.sin(angle) * scale;
      return new Transform(
          c,
          s,
          -s,
          c,
          x - c * cropCenterX + s * cropCenterY,
          y - s * cropCenterX - c * cropCenterY);
    }

    /**
     * Refuse skew, nonuniform scaling and reflection rather than silently distorting app content.
     */
    static Pose from(Transform matrix, double cropCenterX, double cropCenterY) {
      double sx = Math.hypot(matrix.a, matrix.b);
      double sy = Math.hypot(matrix.c, matrix.d);
      if (matrix.a * matrix.d - matrix.b * matrix.c <= 0
          || Math.abs(sx - sy) > Math.max(sx, sy) * 1e-5
          || Math.abs(matrix.a * matrix.c + matrix.b * matrix.d) > sx * sy * 1e-5)
        throw new IllegalArgumentException("Not a similarity transform");
      Point center = matrix.map(cropCenterX, cropCenterY);
      return new Pose(center.x, center.y, sx, Math.atan2(matrix.b, matrix.a));
    }
  }

  private final Pose start, end;
  private final double endAngle;

  RecentsMotionPath(Pose start, Pose end) {
    this.start = start;
    this.end = end;
    double delta = Math.IEEEremainder(end.angle - start.angle, Math.PI * 2);
    endAngle = start.angle + delta;
  }

  /** No wall-clock animator: reversing the same gesture fraction retraces exactly the same path. */
  Pose at(double fraction) {
    finite(fraction);
    double p = Math.max(0, Math.min(1, fraction));
    return new Pose(
        interpolate(start.x, end.x, p),
        interpolate(start.y, end.y, p),
        Math.exp(interpolate(Math.log(start.scale), Math.log(end.scale), p)),
        interpolate(start.angle, endAngle, p));
  }

  /** Project the same pose into a verified leash basis without changing the trajectory itself. */
  Transform surfaceAt(
      double fraction, double cropCenterX, double cropCenterY, Transform leashToPortrait) {
    return at(fraction).matrix(cropCenterX, cropCenterY).then(leashToPortrait.inverse());
  }

  private static double interpolate(double start, double end, double p) {
    return start + (end - start) * p;
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
