package net.ryuya_dev.oneuilivecolors;

/** Terminal diagnostics survive a closed process, but never an unrelated device boot. */
final class StatusFreshness {
  static boolean usable(
      String state, int reportBoot, int boot, long reported, long now, long requested) {
    if (reported <= 0 || reported > now || (reportBoot >= 0 && reportBoot != boot)) return false;
    boolean live = "ACTIVE".equals(state) || "OFF".equals(state);
    if (live) return reported >= requested;
    // Legacy reports have no boot identity: do not revive an old crash/compatibility diagnostic.
    return reportBoot >= 0 && boot >= 0;
  }
}
