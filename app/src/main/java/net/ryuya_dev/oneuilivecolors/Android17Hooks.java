package net.ryuya_dev.oneuilivecolors;

/** Android 17 is reviewed independently; never fall back to Android 16 on a mismatch. */
final class Android17Hooks {
  static final String TOP_TASK_METHOD = "runningTaskForDisplayId";
  static final boolean TOP_TASK_TAKES_DISPLAY = true;
  static final boolean FINISH_TAKES_TO_HOME = true;
  static final long LAUNCHER_VERSION = 1800705053L;
  static final String TASK_SCENE_VIEW = "com.honeyspace.common.taskscene.TaskSceneView";
  static final String TASK_SCENE_DATA = "com.honeyspace.common.taskscene.TaskSceneData";
  static final String BUILD = "CP2A.260605.016.S948QOPU1BZI9";
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
