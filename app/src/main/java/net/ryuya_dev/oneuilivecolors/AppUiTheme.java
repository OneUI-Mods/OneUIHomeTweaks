package net.ryuya_dev.oneuilivecolors;

import android.app.Activity;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.color.DynamicColors;

/** UI preferences belong to the manager app, never any hooked host. */
final class AppUiTheme {
  static void apply(Activity activity) {
    var p = activity.getSharedPreferences("app_ui", Activity.MODE_PRIVATE);
    int mode = p.getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    if (mode != AppCompatDelegate.MODE_NIGHT_NO && mode != AppCompatDelegate.MODE_NIGHT_YES)
      mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    AppCompatDelegate.setDefaultNightMode(mode);
    if (p.getBoolean("dynamic", false)) DynamicColors.applyToActivityIfAvailable(activity);
  }
}
