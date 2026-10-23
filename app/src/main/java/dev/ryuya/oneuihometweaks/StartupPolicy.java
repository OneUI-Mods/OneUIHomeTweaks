package dev.ryuya.oneuihometweaks;

/** Monotonic-time startup policy, independent of host APIs. */
final class StartupPolicy {
  enum Exit {
    CRASH,
    INTENTIONAL,
    UNKNOWN
  }

  static int nextCount(
      int previousCount, boolean healthy, long previousElapsed, long elapsed, Exit exit) {
    int count = Math.max(0, Math.min(3, previousCount));
    if (healthy
        || previousElapsed <= 0
        || elapsed < previousElapsed
        || elapsed - previousElapsed > 600000
        || exit != Exit.CRASH) return 0;
    return Math.min(3, count + 1);
  }
}
