package net.ryuya_dev.oneuilivecolors;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import java.util.*;

/** Configuration and failure boundary shared by independent Android 16 and 17 adapters. */
final class QuickPanelHooks {
  interface Adapter {
    void refresh();

    void close();
  }

  private final List<ModernHook.Handle> handles = new ArrayList<>();
  private final Handler main = new Handler(Looper.getMainLooper());
  private Context context;
  private Adapter adapter;
  private volatile boolean failed;
  private volatile int[] sizes = {100, 100, 100, 100};
  private volatile boolean enabled;
  private volatile boolean legacy;
  private volatile boolean legacyFailed;
  private boolean touched;
  private String lastReport = "";

  int size(int index) {
    return enabled && !failed ? sizes[index] : 100;
  }

  boolean legacy() {
    return enabled && legacy && !failed && !legacyFailed;
  }

  boolean needsRefresh() {
    return touched;
  }

  void add(ModernHook.Handle handle) {
    handles.add(handle);
  }

  void install(Context app, ClassLoader loader) {
    context = app;
    try {
      if (!HookCompatibility.matchesClockBuild(Build.VERSION.SDK_INT, Build.DISPLAY))
        throw new UnsupportedOperationException("Unreviewed Quick Panel firmware");
      adapter =
          switch (Build.VERSION.SDK_INT) {
            case 36 -> new QuickPanelAndroid16(this, loader);
            case 37 -> new QuickPanelAndroid17(this, loader);
            default -> throw new UnsupportedOperationException("Unreviewed Quick Panel platform");
          };
      HandlerThread thread = new HandlerThread("OneUiQuickPanelConfig");
      thread.start();
      Handler worker = new Handler(thread.getLooper());
      context
          .getContentResolver()
          .registerContentObserver(
              ConfigProvider.URI,
              false,
              new ContentObserver(worker) {
                @Override
                public void onChange(boolean self, Uri uri) {
                  read();
                }
              });
      context.registerReceiver(
          new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
              worker.post(QuickPanelHooks.this::read);
            }
          },
          new IntentFilter(Intent.ACTION_USER_UNLOCKED),
          Context.RECEIVER_NOT_EXPORTED);
      worker.post(this::read);
      HookEntry.log("QUICK_PANEL_READY platform=" + Build.VERSION.SDK_INT);
    } catch (Throwable error) {
      fail(error);
    }
  }

  private void read() {
    if (failed) return;
    try (Cursor c =
        context.getContentResolver().query(ConfigProvider.URI, null, null, null, null)) {
      if (c == null || !c.moveToFirst()) throw new IllegalStateException("No configuration");
      boolean on =
          c.getInt(c.getColumnIndexOrThrow("enabled")) != 0
              && c.getInt(c.getColumnIndexOrThrow("systemui_enabled")) != 0
              && c.getInt(c.getColumnIndexOrThrow("qs_enabled")) != 0;
      boolean oldDesign = c.getInt(c.getColumnIndexOrThrow("qs_legacy_design")) != 0;
      int[] next = new int[4];
      for (int i = 0; i < 4; i++)
        next[i] =
            QuickPanelPolicy.percent(c.getInt(c.getColumnIndexOrThrow(QuickPanelPolicy.KEYS[i])));
      main.post(
          () -> {
            if (failed) return;
            touched |= on;
            sizes = next;
            enabled = on;
            legacy = oldDesign;
            try {
              adapter.refresh();
              report(
                  on && oldDesign && legacyFailed ? "PARTIAL" : on ? "ACTIVE" : "OFF",
                  on ? Arrays.toString(next) : "Native Quick Panel sizes");
            } catch (Throwable error) {
              fail(error);
            }
          });
    } catch (Throwable error) {
      main.post(
          () -> {
            enabled = false;
            try {
              if (adapter != null) adapter.refresh();
            } catch (Throwable restore) {
              fail(restore);
              return;
            }
            report("ERROR", "Configuration unavailable; native sizes restored");
          });
    }
  }

  void report(String state, String detail) {
    String value = state + ":" + detail;
    if (value.equals(lastReport)) return;
    lastReport = value;
    HostStatus.reportFeature(context, "quick_panel", state, detail);
  }

  void stopLegacy(Throwable error) {
    legacyFailed = true;
    report("PARTIAL", "Legacy design unavailable; size controls remain active");
    HookEntry.log("QUICK_PANEL_LEGACY_DISABLED " + error);
  }

  void fail(Throwable error) {
    if (failed) return;
    failed = true;
    enabled = false;
    for (ModernHook.Handle h : handles)
      try {
        h.unhook();
      } catch (Throwable ignored) {
      }
    main.post(
        () -> {
          try {
            if (adapter != null) adapter.close();
          } catch (Throwable ignored) {
          }
          report(
              error instanceof ReflectiveOperationException
                      || error instanceof UnsupportedOperationException
                  ? "UNSUPPORTED"
                  : "ERROR",
              "Quick Panel stopped: " + error.getClass().getSimpleName());
        });
    HookEntry.log("QUICK_PANEL_DISABLED " + error);
  }
}
