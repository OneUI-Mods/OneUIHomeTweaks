package dev.ryuya.oneuihometweaks;

import android.content.Context;
import android.os.*;

final class HostStatus {
  static void report(Context context, String state, String detail) {
    reportFeature(context, null, state, detail);
  }

  static void reportFeature(Context context, String feature, String state, String detail) {
    if (context == null) return;
    try {
      Bundle b = new Bundle();
      b.putString("state", state);
      b.putString("detail", detail);
      b.putInt("pid", android.os.Process.myPid());
      b.putLong("elapsed", SystemClock.elapsedRealtime());
      b.putInt(
          "boot",
          android.provider.Settings.Global.getInt(
              context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1));
      b.putString("host", context.getPackageName());
      if (feature != null) b.putString("feature", feature);
      context.getContentResolver().call(ConfigProvider.URI, "report_status", null, b);
    } catch (Throwable e) {
      android.util.Log.w(
          "OneUIHomeTweaks", "Status report unavailable: " + e.getClass().getSimpleName());
    }
  }
}
