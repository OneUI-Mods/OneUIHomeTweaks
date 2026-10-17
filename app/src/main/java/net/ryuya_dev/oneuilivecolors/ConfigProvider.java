package net.ryuya_dev.oneuilivecolors;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;

public final class ConfigProvider extends ContentProvider {
  static final Uri URI = Uri.parse("content://net.ryuya_dev.oneuilivecolors.config/modes");
  static final Uri STATUS_URI = Uri.parse("content://net.ryuya_dev.oneuilivecolors.config/status");
  static final String PREFS = "modes";
  static final String[] HOSTS = {
    "com.sec.android.app.launcher", "com.android.systemui", "com.samsung.android.app.dressroom"
  };

  @Override
  public boolean onCreate() {
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
              "diy_icon_grid"
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
          p.getBoolean("recents_portrait", false) ? 1 : 0,
          p.getBoolean("recents_hide_bar", false) ? 1 : 0,
          p.getBoolean("clock_date_left", false) ? 1 : 0,
          p.getBoolean("diy_icon_grid", false) ? 1 : 0
        });
    return c;
  }

  @Override
  public Bundle call(String method, String arg, Bundle extras) {
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
      if (!"com.android.systemui".equals(host) || !"clock_date".equals(feature))
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
