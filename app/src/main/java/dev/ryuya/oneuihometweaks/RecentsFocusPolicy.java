package dev.ryuya.oneuihometweaks;

/** Identity-based leases prevent an old gesture/death callback from ending a newer gesture. */
final class RecentsFocusPolicy {
  static final int NATIVE = 0, NOTIFY = 1, PRESERVE = 2;

  static int mode(int value) {
    return value == NOTIFY || value == PRESERVE ? value : NATIVE;
  }

  static boolean canOverride(int mode, boolean nativeStayFocus) {
    // With native retention off, setting it after focus gain cannot initialize
    // DecorView.mStayFocus.
    return mode(mode) == NOTIFY || (mode(mode) == PRESERVE && nativeStayFocus);
  }

  static boolean allowedUid(int uid, int moduleUid, int launcherUid, boolean appOnly) {
    return uid >= 10000 && uid <= 19999 && (uid == moduleUid || (!appOnly && uid == launcherUid));
  }

  record Target(int task, int uid, int user, int display, int windowMode, int activityType) {
    boolean eligible() {
      return task >= 0
          && uid >= 10000
          && uid <= 19999
          && user == 0
          && display == 0
          && windowMode == 1
          && activityType == 1;
    }
  }

  record Lease(Object owner, Target target, int mode) {}

  private Lease current;

  Lease current() {
    return current;
  }

  boolean begin(Object owner, Target target, int mode) {
    if (owner == null || target == null || !target.eligible() || mode(mode) == NATIVE) return false;
    current = new Lease(owner, target, mode);
    return true;
  }

  Lease end(Object owner) {
    if (current == null || current.owner() != owner) return null;
    Lease ended = current;
    current = null;
    return ended;
  }
}
