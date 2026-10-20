package net.ryuya_dev.oneuilivecolors;

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
