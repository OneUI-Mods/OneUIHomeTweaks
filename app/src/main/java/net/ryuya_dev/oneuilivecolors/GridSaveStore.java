package net.ryuya_dev.oneuilivecolors;

import android.content.*;
import android.os.*;
import android.provider.Settings;
import java.util.UUID;

/** The app owns the command queue; only the Launcher may consume it or submit a result. */
final class GridSaveStore {
  static final long MAX_AGE = 20000;

  static int boot(Context c) {
    return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
  }

  static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("grid_save", Context.MODE_PRIVATE);
  }

  static String enqueue(Context c, String action, String expected) {
    String token = UUID.randomUUID().toString();
    if (!prefs(c)
        .edit()
        .clear()
        .putString("token", token)
        .putString("action", action)
        .putString("expected", expected)
        .putLong("time", SystemClock.elapsedRealtime())
        .putInt("boot", boot(c))
        .putBoolean("taken", false)
        .commit()) throw new IllegalStateException("Command persistence unavailable");
    c.getContentResolver().notifyChange(ConfigProvider.URI, null);
    return token;
  }

  static boolean valid(Context c) {
    SharedPreferences p = prefs(c);
    long age = SystemClock.elapsedRealtime() - p.getLong("time", -MAX_AGE);
    return boot(c) >= 0 && boot(c) == p.getInt("boot", -1) && age >= 0 && age < MAX_AGE;
  }

  static synchronized Bundle take(Context c) {
    SharedPreferences p = prefs(c);
    if (p.getBoolean("taken", true) || !valid(c)) return Bundle.EMPTY;
    String action = p.getString("action", "");
    if (!action.equals("preview") && !action.equals("save")) return Bundle.EMPTY;
    Bundle b = new Bundle();
    b.putString("token", p.getString("token", ""));
    b.putString("action", action);
    b.putString("expected", p.getString("expected", ""));
    b.putLong("time", p.getLong("time", 0));
    if (!p.edit().putBoolean("taken", true).commit()) return Bundle.EMPTY;
    return b;
  }

  static synchronized Bundle result(Context c, Bundle b) {
    SharedPreferences p = prefs(c);
    if (b == null
        || !valid(c)
        || !p.getBoolean("taken", false)
        || !p.getString("token", "").equals(b.getString("token")))
      throw new SecurityException("Expired/unrelated grid result");
    String state = b.getString("state", "error"), hash = b.getString("hash", "");
    if (!java.util.Set.of("preview", "saved", "error").contains(state)
        || (!hash.isEmpty() && !hash.matches("[0-9a-f]{64}")))
      throw new IllegalArgumentException("Invalid grid result");
    p.edit()
        .putString("state", state)
        .putString("hash", hash)
        .putString("error", b.getString("error", "failed"))
        .putInt("count", Math.max(0, Math.min(500, b.getInt("count"))))
        .putInt("pages", Math.max(0, Math.min(500, b.getInt("pages"))))
        .commit();
    c.getContentResolver().notifyChange(ConfigProvider.STATUS_URI, null);
    return Bundle.EMPTY;
  }
}
