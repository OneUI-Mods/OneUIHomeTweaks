package dev.ryuya.oneuihometweaks;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.*;
import java.util.*;

/** Reorders only the date that the native status-bar clock already chose to display. */
final class ClockDateHooks {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Map<TextView, Boolean> clocks = new WeakHashMap<>();
  private final List<ModernHook.Handle> hooks = new ArrayList<>();
  private Context context;
  private Handler worker;
  private Method notifyTime, dateText;
  private Field lastSound, timeText, spokenTime;
  private boolean enabled;
  private volatile boolean failed;
  private String lastReport = "";

  void install(Context app, ClassLoader loader) {
    context = app;
    try {
      // This feature uses the verified firmware contract, independently of launcher/volume hooks.
      if (!HookCompatibility.matchesClockBuild(Build.VERSION.SDK_INT, Build.DISPLAY))
        throw new UnsupportedOperationException("Unverified SystemUI build");
      Class<?> clock =
          Class.forName(
              "com.android.systemui.statusbar.policy.QSClockIndicatorView", false, loader);
      Class<?> sound =
          Class.forName("com.android.systemui.statusbar.policy.QSClockBellSound", false, loader);
      if (!TextView.class.isAssignableFrom(clock))
        throw new UnsupportedOperationException("Clock type changed");
      notifyTime = clock.getDeclaredMethod("notifyTimeChanged", sound);
      dateText = clock.getDeclaredMethod("getDateText", sound);
      lastSound = clock.getDeclaredField("lastQSClockBellSound");
      timeText = sound.getDeclaredField("TimeText");
      spokenTime = sound.getDeclaredField("TimeContentDescription");
      if (dateText.getReturnType() != String.class
          || lastSound.getType() != sound
          || timeText.getType() != String.class
          || spokenTime.getType() != String.class)
        throw new UnsupportedOperationException("Clock date contract changed");
      hooks.add(
          ModernHook.hookMethod(
              notifyTime,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (failed
                      || p.hasThrowable()
                      || !(p.thisObject instanceof TextView view)
                      || Looper.myLooper() != Looper.getMainLooper()) return;
                  try {
                    clocks.put(view, true);
                    if (enabled) reorder(view, p.args[0]);
                  } catch (Throwable error) {
                    disable(error);
                  }
                }
              }));
      hooks.add(
          ModernHook.hookMethod(
              clock.getDeclaredMethod("onAttachedToWindow"),
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  if (failed || p.hasThrowable()) return;
                  TextView view = (TextView) p.thisObject;
                  clocks.put(view, true);
                  main.post(() -> refreshClock(view));
                }
              }));
      HandlerThread thread = new HandlerThread("OneUiClockDateConfig");
      thread.start();
      worker = new Handler(thread.getLooper());
      app.getContentResolver()
          .registerContentObserver(
              ConfigProvider.URI,
              false,
              new ContentObserver(worker) {
                @Override
                public void onChange(boolean self, Uri uri) {
                  readConfig();
                }
              });
      worker.post(this::readConfig);
      HookEntry.log("CLOCK_DATE_READY native notifyTimeChanged + getDateText");
    } catch (Throwable error) {
      disable(error);
    }
  }

  private void readConfig() {
    if (failed) return;
    try (Cursor c =
        context.getContentResolver().query(ConfigProvider.URI, null, null, null, null)) {
      if (c == null || !c.moveToFirst()) throw new IllegalStateException("No configuration");
      boolean on =
          c.getInt(c.getColumnIndexOrThrow("enabled")) != 0
              && c.getInt(c.getColumnIndexOrThrow("systemui_enabled")) != 0
              && c.getInt(c.getColumnIndexOrThrow("clock_date_left")) != 0;
      main.post(
          () -> {
            if (failed) return;
            enabled = on;
            lastReport = ""; // Configuration notifications also request a fresh hook response.
            report(on ? "ACTIVE" : "OFF", on ? "Clock date ready" : "Native clock order");
            for (TextView clock : new ArrayList<>(clocks.keySet())) refreshClock(clock);
          });
    } catch (Throwable error) {
      main.post(
          () -> {
            if (failed) return;
            enabled = false;
            for (TextView clock : new ArrayList<>(clocks.keySet())) refreshClock(clock);
            report("ERROR", "Clock configuration unavailable; native order restored");
          });
    }
  }

  private void refreshClock(TextView view) {
    if (failed || !view.isAttachedToWindow()) return;
    try {
      Object sound = lastSound.get(view);
      if (sound != null) notifyTime.invoke(view, sound);
    } catch (Throwable error) {
      disable(error);
    }
  }

  private void reorder(TextView view, Object sound) throws ReflectiveOperationException {
    if (!view.isAttachedToWindow()
        || view.getDisplay() == null
        || view.getDisplay().getDisplayId() != 0) return;
    if (view.getLayoutDirection() != View.LAYOUT_DIRECTION_LTR) {
      report("UNSUPPORTED", "RTL clock keeps native order");
      return;
    }
    String date = (String) dateText.invoke(view, sound);
    String time = (String) timeText.get(sound), spoken = (String) spokenTime.get(sound);
    CharSequence original = view.getText();
    ClockDateOrder.Parts parts = ClockDateOrder.parts(original.toString(), time, spoken, date);
    if (parts == null) {
      // Date OFF produces time only: do not manufacture a date or override QuickStar settings.
      boolean hidden = original.toString().equals(time) || original.toString().equals(spoken);
      report(
          hidden ? "ACTIVE" : "UNSUPPORTED",
          hidden
              ? "Date hidden by system settings"
              : "Unrecognized native clock format; unchanged");
      return;
    }
    view.setText(move(original, parts));
    CharSequence description = view.getContentDescription();
    if (description != null) {
      ClockDateOrder.Parts spokenParts =
          ClockDateOrder.parts(description.toString(), spoken, spoken, date);
      if (spokenParts != null) view.setContentDescription(move(description, spokenParts));
    }
    report("ACTIVE", "Date before time; native date visibility and format retained");
  }

  private static CharSequence move(CharSequence original, ClockDateOrder.Parts parts) {
    return new SpannableStringBuilder()
        .append(original, parts.dateStart(), original.length())
        .append(original, parts.timeEnd(), parts.dateStart())
        .append(original, 0, parts.timeEnd());
  }

  private void disable(Throwable error) {
    if (failed) return;
    failed = true;
    enabled = false;
    for (ModernHook.Handle hook : hooks)
      try {
        hook.unhook();
      } catch (Throwable ignored) {
      }
    // Regenerate the native string after unhooking. Never touch the time source or global settings.
    main.post(
        () -> {
          for (TextView view : new ArrayList<>(clocks.keySet()))
            try {
              Object sound = lastSound.get(view);
              if (view.isAttachedToWindow() && sound != null) notifyTime.invoke(view, sound);
            } catch (Throwable ignored) {
            }
          report(
              error instanceof UnsupportedOperationException ? "UNSUPPORTED" : "ERROR",
              "Clock date disabled: " + error.getClass().getSimpleName());
        });
    HookEntry.log("CLOCK_DATE_DISABLED " + error);
  }

  private void report(String state, String detail) {
    String next = state + ":" + detail;
    if (next.equals(lastReport)) return;
    lastReport = next;
    HostStatus.reportFeature(context, "clock_date", state, detail);
    HookEntry.log("CLOCK_DATE " + next);
  }
}
