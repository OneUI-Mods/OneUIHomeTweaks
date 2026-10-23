package dev.ryuya.oneuihometweaks;

/** Selects a single version profile; unsupported versions never inherit another profile. */
final class HookCompatibility {
  enum Platform {
    ANDROID_16,
    ANDROID_17,
    UNSUPPORTED
  }

  @androidx.annotation.ChecksSdkIntAtLeast(api = 36)
  static boolean isCurrentSupported() {
    return supports(
        android.os.Build.VERSION.SDK_INT,
        android.os.Build.MANUFACTURER,
        android.os.Build.MODEL,
        android.os.Build.DEVICE,
        android.os.Build.DISPLAY);
  }

  static boolean matchesLauncherVersion(int sdk, long version) {
    return switch (platform(sdk)) {
      case ANDROID_16 -> version == Android16Hooks.LAUNCHER_VERSION;
      case ANDROID_17 -> version == Android17Hooks.LAUNCHER_VERSION;
      case UNSUPPORTED -> false;
    };
  }

  static String taskSceneView(int sdk) {
    return switch (platform(sdk)) {
      case ANDROID_16 -> Android16Hooks.TASK_SCENE_VIEW;
      case ANDROID_17 -> Android17Hooks.TASK_SCENE_VIEW;
      case UNSUPPORTED -> throw new UnsupportedOperationException("Unreviewed task scene platform");
    };
  }

  record RecentsCalls(
      String topTaskMethod, boolean topTaskTakesDisplay, boolean finishTakesToHome) {}

  static RecentsCalls recentsCalls(int sdk) {
    return switch (platform(sdk)) {
      case ANDROID_16 ->
          new RecentsCalls(
              Android16Hooks.TOP_TASK_METHOD,
              Android16Hooks.TOP_TASK_TAKES_DISPLAY,
              Android16Hooks.FINISH_TAKES_TO_HOME);
      case ANDROID_17 ->
          new RecentsCalls(
              Android17Hooks.TOP_TASK_METHOD,
              Android17Hooks.TOP_TASK_TAKES_DISPLAY,
              Android17Hooks.FINISH_TAKES_TO_HOME);
      case UNSUPPORTED -> throw new UnsupportedOperationException("Unreviewed recents platform");
    };
  }

  static String taskSceneData(int sdk) {
    return switch (platform(sdk)) {
      case ANDROID_16 -> Android16Hooks.TASK_SCENE_DATA;
      case ANDROID_17 -> Android17Hooks.TASK_SCENE_DATA;
      case UNSUPPORTED -> throw new UnsupportedOperationException("Unreviewed task scene platform");
    };
  }

  static boolean matchesClockBuild(int sdk, String display) {
    return switch (platform(sdk)) {
      case ANDROID_16 -> Android16Hooks.BUILD.equals(display);
      case ANDROID_17 -> Android17Hooks.BUILD.equals(display);
      case UNSUPPORTED -> false;
    };
  }

  static Platform platform(int sdk) {
    return switch (sdk) {
      case 36 -> Platform.ANDROID_16;
      case 37 -> Platform.ANDROID_17;
      default -> Platform.UNSUPPORTED;
    };
  }

  static boolean supportsRecentsFocus(int sdk, String fingerprint) {
    return switch (platform(sdk)) {
      case ANDROID_16 -> Android16Hooks.supportsRecentsFocus(sdk, fingerprint);
      case ANDROID_17 -> Android17Hooks.supportsRecentsFocus(sdk, fingerprint);
      case UNSUPPORTED -> false;
    };
  }

  static boolean supports(
      int sdk, String manufacturer, String model, String device, String display) {
    return switch (platform(sdk)) {
      case ANDROID_16 -> Android16Hooks.supports(sdk, manufacturer, model, device, display);
      case ANDROID_17 -> Android17Hooks.supports(sdk, manufacturer, model, device, display);
      case UNSUPPORTED -> false;
    };
  }
}
