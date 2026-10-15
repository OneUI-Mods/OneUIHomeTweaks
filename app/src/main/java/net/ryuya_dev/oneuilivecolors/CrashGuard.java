package net.ryuya_dev.oneuilivecolors;

import android.app.*;
import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import java.io.*;
import java.util.Properties;

/** Host-private persistent startup protection; never touches system partitions. */
final class CrashGuard {
  static final String FILE = "net.ryuya_dev.oneuilivecolors.guard";
  private final AtomicFile file;
  private final Properties state = new Properties();

  private CrashGuard(Context context) {
    file = new AtomicFile(new File(context.getFilesDir(), FILE));
  }

  static CrashGuard enter(Context context) throws Exception {
    CrashGuard guard = new CrashGuard(context);
    try (InputStream in = guard.file.openRead()) {
      guard.state.load(in);
    } catch (FileNotFoundException ignored) {
    }
    long now = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
    int count = Integer.parseInt(guard.state.getProperty("attempts", "0"));
    long previous = Long.parseLong(guard.state.getProperty("started", "0"));
    boolean healthy = Boolean.parseBoolean(guard.state.getProperty("healthy", "true"));
    if (!Boolean.parseBoolean(guard.state.getProperty("blocked", "false"))) {
      StartupPolicy.Exit lastExit = StartupPolicy.Exit.UNKNOWN;
      // Confirm real crash reason where Android provides it; don't count intentional restarts.
      try {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        for (ApplicationExitInfo exit : am.getHistoricalProcessExitReasons(null, 0, 8)) {
          if (!context.getPackageName().equals(exit.getProcessName())
              || exit.getTimestamp() < previous) continue;
          int reason = exit.getReason();
          lastExit =
              (reason == ApplicationExitInfo.REASON_CRASH
                      || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                      || reason == ApplicationExitInfo.REASON_ANR)
                  ? StartupPolicy.Exit.CRASH
                  : StartupPolicy.Exit.INTENTIONAL;
          break;
        }
      } catch (RuntimeException ignored) {
        /* unfinished-start fallback remains */
      }
      count =
          StartupPolicy.nextCount(
              count,
              healthy,
              Long.parseLong(guard.state.getProperty("elapsed", "0")),
              elapsed,
              lastExit);
      guard.state.setProperty("blocked", Boolean.toString(count >= 3));
    }
    guard.state.setProperty("attempts", Integer.toString(count));
    guard.state.setProperty("started", Long.toString(now));
    guard.state.setProperty("elapsed", Long.toString(elapsed));
    guard.state.setProperty("healthy", "false");
    guard.save();
    return guard;
  }

  boolean blocked() {
    return Boolean.parseBoolean(state.getProperty("blocked", "false"));
  }

  synchronized void healthy() {
    if (blocked()) return;
    state.setProperty("healthy", "true");
    state.setProperty("attempts", "0");
    try {
      save();
    } catch (Exception e) {
      HookEntry.log("GUARD_HEALTH_WRITE_ERROR " + e.getClass().getSimpleName());
    }
  }

  private synchronized void save() throws IOException {
    FileOutputStream out = null;
    try {
      out = file.startWrite();
      state.store(out, "One UI Live Colors startup protection");
      file.finishWrite(out);
    } catch (IOException e) {
      if (out != null) file.failWrite(out);
      throw e;
    }
  }
}
