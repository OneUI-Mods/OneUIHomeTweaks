package net.ryuya_dev.oneuilivecolors;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * User-initiated saves through the verified native editor, followed by read-only DB confirmation.
 */
final class GridSaveCoordinator {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ScheduledExecutorService io =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "OneUiGridSave");
            t.setDaemon(true);
            return t;
          });
  private final DiyGridBridge bridge;
  private boolean busy;

  GridSaveCoordinator(DiyGridBridge bridge) {
    this.bridge = bridge;
  }

  void accept(Context context, Bundle request) {
    if (request == null || request.isEmpty()) return;
    String token = request.getString("token", "");
    if (busy) {
      reply(context, token, "error", null, "busy");
      return;
    }
    busy = true;
    DiyGridBridge.PreparedSave prepared;
    try {
      prepared = bridge.prepareSave();
    } catch (Throwable error) {
      finish(context, token, null, "unavailable", error);
      return;
    }
    GridSavePlan plan = prepared.plan();
    boolean save = "save".equals(request.getString("action"));
    if (save && !plan.fingerprint().equals(request.getString("expected"))) {
      finish(context, token, null, "changed", null);
      return;
    }
    io.execute(
        () -> {
          try {
            if (!databaseMatches(context, plan, false)) {
              main.post(() -> finish(context, token, null, "changed", null));
              return;
            }
            main.post(
                () -> {
                  long age = SystemClock.elapsedRealtime() - request.getLong("time", 0);
                  if (age < 0 || age > GridSaveStore.MAX_AGE - 4000) {
                    finish(context, token, null, "timeout", null);
                    return;
                  }
                  if (!save) {
                    busy = false;
                    reply(context, token, "preview", plan, "");
                    return;
                  }
                  boolean mutationStarted = false;
                  try {
                    DiyGridBridge.PreparedSave current = bridge.prepareSave();
                    if (current.owner() != prepared.owner()
                        || !current.plan().fingerprint().equals(plan.fingerprint())) {
                      finish(context, token, null, "changed", null);
                      return;
                    }
                    mutationStarted = true;
                    bridge.applySave(current, false);
                    verify(context, token, current, 0);
                  } catch (Throwable error) {
                    if (mutationStarted) {
                      try {
                        bridge.applySave(prepared, true);
                      } catch (Throwable rollback) {
                        HookEntry.log("GRID_SAVE_ROLLBACK_ERROR " + rollback);
                      }
                    }
                    finish(context, token, null, "failed", error);
                  }
                });
          } catch (Throwable error) {
            main.post(() -> finish(context, token, null, "unavailable", error));
          }
        });
  }

  private void verify(
      Context context, String token, DiyGridBridge.PreparedSave prepared, int attempt) {
    long[] delays = {100, 300, 800, 1600};
    io.schedule(
        () -> {
          boolean saved = false;
          Throwable failure = null;
          try {
            saved = databaseMatches(context, prepared.plan(), true);
          } catch (Throwable error) {
            failure = error;
          }
          boolean confirmed = saved;
          Throwable error = failure;
          main.post(
              () -> {
                if (confirmed) {
                  busy = false;
                  reply(context, token, "saved", prepared.plan(), "");
                } else if (attempt + 1 < delays.length)
                  verify(context, token, prepared, attempt + 1);
                else finish(context, token, null, "failed", error);
              });
        },
        delays[attempt],
        TimeUnit.MILLISECONDS);
  }

  private static boolean databaseMatches(Context context, GridSavePlan plan, boolean saved) {
    String[] ids =
        plan.entries().stream().map(e -> Integer.toString(e.id())).toArray(String[]::new);
    String placeholders = String.join(",", Collections.nCopies(ids.length, "?"));
    Map<Integer, GridSavePlan.Entry> expected = new HashMap<>();
    for (var entry : plan.entries()) expected.put(entry.id(), entry);
    // This is the actual Launcher schema verified on the supported firmware. Never write SQL.
    try (SQLiteDatabase db =
            SQLiteDatabase.openDatabase(
                context.getDatabasePath("OneUI.db").getPath(), null, SQLiteDatabase.OPEN_READONLY);
        Cursor cursor =
            db.rawQuery(
                "SELECT id,type,container_id,position_x,position_y FROM item WHERE id IN ("
                    + placeholders
                    + ")",
                ids)) {
      int count = 0;
      while (cursor.moveToNext()) {
        GridSavePlan.Entry entry = expected.get(cursor.getInt(0));
        if (entry == null
            || !Set.of("app", "folder", "shortcut", "deepshortcut").contains(cursor.getString(1))
            || cursor.getInt(2) != entry.page()
            || cursor.getInt(3) != (saved ? entry.x() : entry.oldX())
            || cursor.getInt(4) != (saved ? entry.y() : entry.oldY())) return false;
        count++;
      }
      return count == expected.size();
    }
  }

  private void finish(
      Context context, String token, GridSavePlan plan, String reason, Throwable error) {
    busy = false;
    HookEntry.log("GRID_SAVE_ERROR " + reason + (error == null ? "" : " " + error));
    reply(context, token, "error", plan, reason);
  }

  private static void reply(
      Context context, String token, String state, GridSavePlan plan, String error) {
    try {
      Bundle result = new Bundle();
      result.putString("token", token);
      result.putString("state", state);
      result.putString("error", error);
      if (plan != null) {
        result.putInt("count", plan.changed());
        result.putInt("pages", plan.pages());
        result.putString("hash", plan.fingerprint());
      }
      context.getContentResolver().call(ConfigProvider.URI, "grid_result", null, result);
      HookEntry.log("GRID_SAVE " + state + (plan == null ? "" : " count=" + plan.changed()));
    } catch (Throwable failure) {
      HookEntry.log("GRID_SAVE_RESULT_UNAVAILABLE " + failure);
    }
  }
}
