package net.ryuya_dev.oneuilivecolors;

import android.content.Context;
import android.os.*;

/**
 * App-owned broker. The backend is discovered by Launcher; source apps cannot register or use it.
 */
final class RecentsFocusRegistry {
  private final Context context;
  private final IBinder anchor = new Binder();
  private IBinder backend;

  RecentsFocusRegistry(Context context) {
    this.context = context;
  }

  synchronized Bundle register(Bundle data) throws Exception {
    IBinder candidate = data == null ? null : data.getBinder("backend");
    if (candidate == null || !candidate.isBinderAlive())
      throw new IllegalArgumentException("Live backend required");
    RecentsFocusWire.call(candidate, RecentsFocusWire.STATUS, null);
    backend = candidate;
    Bundle result = new Bundle();
    result.putBinder("anchor", anchor);
    return result;
  }

  synchronized Bundle status() {
    try {
      if (backend != null) return RecentsFocusWire.call(backend, RecentsFocusWire.STATUS, null);
    } catch (Exception ignored) {
      backend = null;
    }
    Bundle result = new Bundle();
    result.putString("state", "backend-missing");
    return result;
  }

  synchronized Bundle apply() {
    if (backend == null) return status();
    var p = context.getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE);
    int mode =
        p.getBoolean("enabled", true)
                && p.getBoolean("home_enabled", true)
                && p.getBoolean("recents_live_tile", false)
            ? RecentsFocusPolicy.mode(p.getInt("recents_focus_mode", 0))
            : 0;
    Bundle data = new Bundle();
    data.putInt("mode", mode);
    try {
      return RecentsFocusWire.call(
          backend, mode == 0 ? RecentsFocusWire.STOP : RecentsFocusWire.ARM, data);
    } catch (Exception ignored) {
      backend = null;
      return status();
    }
  }
}
