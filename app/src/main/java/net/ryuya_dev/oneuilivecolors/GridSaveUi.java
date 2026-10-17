package net.ryuya_dev.oneuilivecolors;

import android.app.Activity;
import android.os.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

/** A read-only preview precedes every explicit confirmation to persist icon positions. */
final class GridSaveUi {
  private final Activity activity;
  private final MaterialButton button;
  private final Handler main = new Handler(Looper.getMainLooper());
  private String waiting = "";

  GridSaveUi(Activity activity) {
    this.activity = activity;
    button = activity.findViewById(R.id.save_grid_positions);
    button.setOnClickListener(v -> request("preview", ""));
    var p = GridSaveStore.prefs(activity);
    if (GridSaveStore.valid(activity) && !p.getBoolean("ui_done", false)) {
      waiting = p.getString("token", "");
      String token = waiting;
      main.post(this::result);
      main.postDelayed(
          () -> {
            if (token.equals(waiting)) {
              waiting = "";
              availability();
              show(R.string.grid_save_unavailable);
            }
          },
          Math.max(
              1, GridSaveStore.MAX_AGE - (SystemClock.elapsedRealtime() - p.getLong("time", 0))));
    }
    availability();
  }

  void availability() {
    var p = activity.getSharedPreferences(ConfigProvider.PREFS, Activity.MODE_PRIVATE);
    button.setEnabled(
        waiting.isEmpty()
            && p.getBoolean("enabled", true)
            && p.getBoolean("home_enabled", true)
            && p.getBoolean("diy_icon_grid", false));
  }

  private void request(String action, String fingerprint) {
    button.setEnabled(false);
    try {
      waiting = GridSaveStore.enqueue(activity, action, fingerprint);
      String token = waiting;
      Snackbar.make(button, R.string.grid_save_wait, Snackbar.LENGTH_SHORT).show();
      main.postDelayed(
          () -> {
            if (token.equals(waiting)) {
              waiting = "";
              availability();
              show(R.string.grid_save_unavailable);
            }
          },
          GridSaveStore.MAX_AGE);
    } catch (Throwable error) {
      waiting = "";
      availability();
      show(R.string.grid_save_failed);
    }
  }

  void result() {
    if (waiting.isEmpty() || activity.isFinishing() || activity.isDestroyed()) return;
    var p = GridSaveStore.prefs(activity);
    if (!waiting.equals(p.getString("token", ""))) return;
    String state = p.getString("state", "");
    if (state.isEmpty()) return;
    p.edit().putBoolean("ui_done", true).apply();
    waiting = "";
    availability();
    int count = p.getInt("count", 0);
    if ("preview".equals(state)) {
      if (count == 0) {
        show(R.string.grid_save_nochanges);
        return;
      }
      String hash = p.getString("hash", "");
      if (!hash.matches("[0-9a-f]{64}")) {
        show(R.string.grid_save_failed);
        return;
      }
      new MaterialAlertDialogBuilder(activity)
          .setTitle(R.string.save_grid_positions)
          .setMessage(activity.getString(R.string.grid_save_confirm, count, p.getInt("pages", 0)))
          .setNegativeButton(android.R.string.cancel, null)
          .setPositiveButton(R.string.grid_save_commit, (d, w) -> request("save", hash))
          .show();
    } else if ("saved".equals(state)) {
      new MaterialAlertDialogBuilder(activity)
          .setTitle(R.string.save_grid_positions)
          .setMessage(activity.getString(R.string.grid_save_done, count))
          .setPositiveButton(R.string.close, null)
          .show();
    } else {
      String error = p.getString("error", "failed");
      show(
          "changed".equals(error)
              ? R.string.grid_save_changed
              : "unavailable".equals(error) || "timeout".equals(error)
                  ? R.string.grid_save_unavailable
                  : R.string.grid_save_failed);
    }
  }

  private void show(int message) {
    if (activity.isFinishing() || activity.isDestroyed()) return;
    new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.save_grid_positions)
        .setMessage(message)
        .setPositiveButton(R.string.close, null)
        .show();
  }

  void destroy() {
    main.removeCallbacksAndMessages(null);
  }
}
