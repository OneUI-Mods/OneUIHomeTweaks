package net.ryuya_dev.oneuilivecolors;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;

public final class ConfigProvider extends ContentProvider {
  static final Uri URI = Uri.parse("content://net.ryuya_dev.oneuilivecolors.config/modes");
  static final Uri STATUS_URI = Uri.parse("content://net.ryuya_dev.oneuilivecolors.config/status");
  private RecentsFocusRegistry focus;
  static final String PREFS = "modes";
  static final String[] HOSTS = {
    "com.sec.android.app.launcher", "com.android.systemui", "com.samsung.android.app.dressroom"
  };

  @Override
  public boolean onCreate() {
    focus = new RecentsFocusRegistry(getContext());
    return true;
  }

  private String hostCaller() {
    int uid = Binder.getCallingUid();
    if (uid / 100000 != 0) throw new SecurityException("Owner user only");
    String caller = getCallingPackage();
    for (String host : HOSTS)
      try {
        if (host.equals(caller)
            && getContext().getPackageManager().getApplicationInfo(host, 0).uid == uid) return host;
      } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
      }
    throw new SecurityException("Unsupported caller");
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    if (!URI.equals(uri)) throw new IllegalArgumentException("Unknown URI");
    if (Binder.getCallingUid() != android.os.Process.myUid()) hostCaller();
    var p = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    MatrixCursor c =
        new MatrixCursor(
            new String[] {
              "label",
              "status",
              "enabled",
              "pages",
              "drawer",
              "volume",
              "preview",
              "home_enabled",
              "systemui_enabled",
              "preview_enabled",
              "recents_portrait",
              "recents_hide_bar",
              "clock_date_left",
              "diy_icon_grid",
              "recents_live_tile",
              "recents_focus_mode",
              "qs_enabled",
              "qs_brightness",
              "qs_volume",
              "qs_dark",
              "qs_sound",
              "qs_legacy_design"
            });
    c.addRow(
        new Object[] {
          p.getInt("label", ColorPolicy.LOCAL),
          ColorPolicy.OFF,
          p.getBoolean("enabled", true) ? 1 : 0,
          p.getBoolean("pages", true) ? 1 : 0,
          p.getInt("drawer", ColorPolicy.AUTO),
          p.getInt("volume", 0),
          p.getBoolean("preview", false) ? 1 : 0,
          p.getBoolean("home_enabled", true) ? 1 : 0,
          p.getBoolean("systemui_enabled", true) ? 1 : 0,
          p.getBoolean("preview_enabled", true) ? 1 : 0,
          0, // Retired orientation option: tell pre-0.11 Launcher hooks to restore native requests.
          p.getBoolean("recents_hide_bar", false) ? 1 : 0,
          p.getBoolean("clock_date_left", false) ? 1 : 0,
          p.getBoolean("diy_icon_grid", false) ? 1 : 0,
          p.getBoolean("recents_live_tile", false) ? 1 : 0,
          RecentsFocusPolicy.mode(p.getInt("recents_focus_mode", 0)),
          p.getBoolean("qs_enabled", false) ? 1 : 0,
          QuickPanelPolicy.percent(p.getInt("qs_brightness", 100)),
          QuickPanelPolicy.percent(p.getInt("qs_volume", 100)),
          QuickPanelPolicy.percent(p.getInt("qs_dark", 100)),
          QuickPanelPolicy.percent(p.getInt("qs_sound", 100)),
          p.getBoolean("qs_legacy_design", false) ? 1 : 0
        });
    return c;
  }

  @Override
  public Bundle call(String method, String arg, Bundle extras) {
    if ("focus_register".equals(method)) {
      if (!HookEntry.LAUNCHER.equals(hostCaller())) throw new SecurityException("Launcher only");
      try {
        return focus.register(extras);
      } catch (Exception error) {
        throw new IllegalStateException("Focus backend unavailable", error);
      }
    }
    if ("focus_status".equals(method) || "focus_apply".equals(method)) {
      if (Binder.getCallingUid() != android.os.Process.myUid())
        throw new SecurityException("Module app only");
      return "focus_apply".equals(method) ? focus.apply() : focus.status();
    }
    if ("take_grid_request".equals(method) || "grid_result".equals(method)) {
      if (!"com.sec.android.app.launcher".equals(hostCaller()))
        throw new SecurityException("Launcher only");
      return "take_grid_request".equals(method)
          ? GridSaveStore.take(getContext())
          : GridSaveStore.result(getContext(), extras);
    }
    if (!"report_status".equals(method) || extras == null)
      throw new IllegalArgumentException("Unsupported call");
    String host = hostCaller();
    if (!host.equals(extras.getString("host"))) throw new SecurityException("Host mismatch");
    String feature = extras.getString("feature");
    if (feature != null) {
      if (!"com.android.systemui".equals(host)
          || (!"clock_date".equals(feature) && !"quick_panel".equals(feature)))
        throw new SecurityException("Unsupported feature");
      host += "." + feature;
    }
    String state = extras.getString("state", "ERROR"), detail = extras.getString("detail", "");
    if (detail.length() > 512) detail = detail.substring(0, 512);
    getContext()
        .getSharedPreferences("host_status", Context.MODE_PRIVATE)
        .edit()
        .putString(host + ".state", state)
        .putString(host + ".detail", detail)
        .putLong(host + ".elapsed", extras.getLong("elapsed"))
        .putInt(host + ".pid", extras.getInt("pid"))
        .putInt(host + ".boot", extras.getInt("boot", -1))
        .apply();
    getContext().getContentResolver().notifyChange(STATUS_URI, null);
    Bundle result = new Bundle();
    result.putBoolean("saved", true);
    return result;
  }

  @Override
  public String getType(Uri u) {
    return "vnd.android.cursor.item/vnd.oneuilivecolors.modes";
  }

  @Override
  public Uri insert(Uri u, ContentValues v) {
    throw new SecurityException("Read only");
  }

  @Override
  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new SecurityException("Read only");
  }

  @Override
  public int delete(Uri u, String s, String[] a) {
    throw new SecurityException("Read only");
  }
}
