package net.ryuya_dev.oneuilivecolors;

/** Android 17 is reviewed independently; never fall back to Android 16 on a mismatch. */
final class Android17Hooks {
  static final String CANDIDATE_FIRMWARE = "S948QOPU1BZI9";

  static boolean supportsRecentsFocus(int sdk, String fingerprint) {
    return false;
  }

  static boolean supports(
      int sdk, String manufacturer, String model, String device, String display) {
    // A ROM name alone does not establish the runtime hook contracts.
    // Remains off until its classes, signatures and data flow have been reviewed.
    return false;
  }
}
