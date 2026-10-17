package net.ryuya_dev.oneuilivecolors;

/** Exact critically damped motion for a measured, possibly moving destination. */
final class RecentsChase {
  private final double omega;
  private final double[] position, velocity, target;

  RecentsChase(double omega, double[] start, double[] initialVelocity) {
    if (!Double.isFinite(omega)
        || omega <= 0
        || start.length == 0
        || start.length != initialVelocity.length)
      throw new IllegalArgumentException("Invalid motion state");
    this.omega = omega;
    position = start.clone();
    velocity = initialVelocity.clone();
    target = start.clone();
    finite(position);
    finite(velocity);
  }

  void target(double[] value) {
    if (value.length != target.length)
      throw new IllegalArgumentException("Changed motion dimensions");
    finite(value);
    System.arraycopy(value, 0, target, 0, target.length);
  }

  void advance(double seconds) {
    if (!Double.isFinite(seconds) || seconds < 0)
      throw new IllegalArgumentException("Invalid elapsed time");
    double decay = Math.exp(-omega * seconds);
    for (int i = 0; i < position.length; i++) {
      double distance = position[i] - target[i], term = (velocity[i] + omega * distance) * seconds;
      position[i] = target[i] + (distance + term) * decay;
      velocity[i] = (velocity[i] - omega * term) * decay;
    }
  }

  double[] position() {
    return position.clone();
  }

  double[] velocity() {
    return velocity.clone();
  }

  private static void finite(double[] a) {
    for (double x : a)
      if (!Double.isFinite(x)) throw new IllegalArgumentException("Nonfinite motion");
  }
}
