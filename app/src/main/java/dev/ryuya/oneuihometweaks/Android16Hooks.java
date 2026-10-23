package dev.ryuya.oneuihometweaks;

/** Frozen Android 16 admission rules; hook sources are recorded in docs/compatibility. */
final class Android16Hooks {
  static final String TOP_TASK_METHOD = "getTopTask";
  static final boolean TOP_TASK_TAKES_DISPLAY = false;
  static final boolean FINISH_TAKES_TO_HOME = false;
  static final String FINGERPRINT =
      "samsung/m3qjpnw/m3q:16/BP4A.251205.006/S948QOPS1AZHL_SJP1AZHL:user/release-keys";
  static final long LAUNCHER_VERSION = 1750305027L;
  static final String TASK_SCENE_VIEW = "com.honeyspace.ui.common.taskScene.TaskSceneView";
  static final String TASK_SCENE_DATA = "com.honeyspace.ui.common.taskScene.TaskSceneData";
  static final String BUILD = "BP4A.251205.006.S948QOPS1AZHL";

  static boolean supportsRecentsFocus(int sdk, String fingerprint) {
    return sdk == 36 && FINGERPRINT.equals(fingerprint);
  }

  static boolean supports(
      int sdk, String manufacturer, String model, String device, String display) {
    return sdk == 36;
  }
}
