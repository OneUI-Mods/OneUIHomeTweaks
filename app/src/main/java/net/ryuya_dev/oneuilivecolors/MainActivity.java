package net.ryuya_dev.oneuilivecolors;

import android.content.*;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.*;
import androidx.core.graphics.Insets;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.*;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import java.util.concurrent.*;

public final class MainActivity extends AppCompatActivity {
  private final ExecutorService commands = Executors.newSingleThreadExecutor();
  private static boolean rootRequested;
  private View root;
  private SharedPreferences prefs;
  private HookStatusCard[] statusCards;
  private long statusRequest;
  private final int[] modes = new int[3];
  private final int[] modeIds = {R.id.label_mode, R.id.drawer_mode, R.id.volume_mode};
  private final String[] keys = {"label", "drawer", "volume"};
  private final String[] switches = {
    "enabled",
    "home_enabled",
    "systemui_enabled",
    "preview_enabled",
    "pages",
    "preview",
    "recents_portrait",
    "recents_hide_bar",
    "clock_date_left",
    "diy_icon_grid"
  };
  private final int[] switchIds = {
    R.id.master,
    R.id.home_enabled,
    R.id.systemui_enabled,
    R.id.preview_enabled,
    R.id.pages,
    R.id.preview,
    R.id.recents_portrait,
    R.id.recents_hide_bar,
    R.id.clock_date_left,
    R.id.diy_icon_grid
  };
  private final ContentObserver reports =
      new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean self, Uri uri) {
          showStatus();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    DynamicColors.applyToActivityIfAvailable(this);
    super.onCreate(state);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    setContentView(R.layout.activity_main);
    root = findViewById(R.id.root);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (view, insets) -> {
          Insets safe =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
          return WindowInsetsCompat.CONSUMED;
        });
    ViewCompat.requestApplyInsets(root);
    boolean night =
        (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    var controller = WindowCompat.getInsetsController(getWindow(), root);
    controller.setAppearanceLightStatusBars(!night);
    controller.setAppearanceLightNavigationBars(!night);
    prefs = getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE);
    for (int i = 0; i < modeIds.length; i++) {
      final int index = i;
      String[] labels =
          i == 2
              ? new String[] {
                getString(R.string.volume_system),
                getString(R.string.volume_light),
                getString(R.string.volume_dark)
              }
              : new String[] {
                getString(i == 1 ? R.string.mode_native_auto : R.string.mode_auto),
                getString(R.string.mode_black),
                getString(R.string.mode_white),
                getString(R.string.mode_off)
              };
      if (i == 0) {
        labels = java.util.Arrays.copyOf(labels, 5);
        labels[ColorPolicy.LOCAL] = getString(R.string.mode_local);
      }
      int fallback = i == 0 ? ColorPolicy.LOCAL : 0;
      modes[i] = prefs.getInt(keys[i], fallback);
      if (modes[i] < 0 || modes[i] >= labels.length) modes[i] = 0;
      MaterialAutoCompleteTextView selector = findViewById(modeIds[i]);
      selector.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
      selector.setText(labels[modes[i]], false);
      selector.setOnItemClickListener((p, v, position, id) -> modes[index] = position);
    }
    for (int i = 0; i < switches.length; i++) {
      final String key = switches[i];
      MaterialSwitch control = findViewById(switchIds[i]);
      control.setChecked(
          prefs.getBoolean(
              key,
              !key.equals("preview")
                  && !key.startsWith("recents_")
                  && !key.equals("clock_date_left")
                  && !key.equals("diy_icon_grid")));
      control.setOnCheckedChangeListener(
          (button, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            notifyConfig();
          });
    }
    View.OnClickListener save =
        v -> {
          var edit = prefs.edit();
          for (int i = 0; i < keys.length; i++) edit.putInt(keys[i], modes[i]);
          edit.apply();
          notifyConfig();
          snack(R.string.saved);
        };
    findViewById(R.id.save).setOnClickListener(save);
    findViewById(R.id.save_appearance).setOnClickListener(save);
    BottomNavigationView navigation = findViewById(R.id.navigation);
    int[] pageIds = {R.id.page_0, R.id.page_1, R.id.page_2, R.id.page_3};
    int[] navigationIds = {R.id.nav_home, R.id.nav_appearance, R.id.nav_status, R.id.nav_settings};
    navigation.setOnItemSelectedListener(
        item -> {
          for (int i = 0; i < pageIds.length; i++) {
            boolean selected = item.getItemId() == navigationIds[i];
            findViewById(pageIds[i]).setVisibility(selected ? View.VISIBLE : View.GONE);
            if (selected) prefs.edit().putInt("selected_tab", i).apply();
          }
          return true;
        });
    int selected = Math.max(0, Math.min(3, prefs.getInt("selected_tab", 0)));
    navigation.setSelectedItemId(navigationIds[selected]);
    findViewById(R.id.restart_home).setOnClickListener(v -> restartHome((MaterialButton) v));
    findViewById(R.id.reload_targets)
        .setOnClickListener(
            v ->
                new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.reload_title)
                    .setItems(
                        new String[] {
                          getString(R.string.reload_volume), getString(R.string.reload_preview)
                        },
                        (dialog, index) ->
                            new MaterialAlertDialogBuilder(this)
                                .setTitle(
                                    index == 0 ? R.string.reload_volume : R.string.reload_preview)
                                .setMessage(R.string.reload_warning)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(
                                    android.R.string.ok, (d, w) -> reloadTarget(index))
                                .show())
                    .show());
    findViewById(R.id.clear_guard)
        .setOnClickListener(
            v ->
                new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.clear_guard)
                    .setMessage(R.string.clear_guard_note)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (d, w) -> resetProtection())
                    .show());
    findViewById(R.id.help)
        .setOnClickListener(
            v ->
                new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.help)
                    .setMessage(R.string.help_text)
                    .setPositiveButton(R.string.close, null)
                    .show());
    findViewById(R.id.language).setOnClickListener(v -> language());
    findViewById(R.id.licenses)
        .setOnClickListener(v -> startActivity(new Intent(this, LicensesActivity.class)));
    getContentResolver().registerContentObserver(ConfigProvider.STATUS_URI, false, reports);
    LinearLayout cards = findViewById(R.id.status_cards);
    statusCards = new HookStatusCard[8];
    int[] titles = {
      R.string.scope_home,
      R.string.scope_volume,
      R.string.scope_preview,
      R.string.recents_portrait,
      R.string.recents_hide_bar,
      R.string.mode_local,
      R.string.clock_date_left,
      R.string.diy_icon_grid
    };
    for (int i = 0; i < titles.length; i++) {
      statusCards[i] = new HookStatusCard(this, titles[i]);
      cards.addView(statusCards[i]);
    }
    showStatus();
    if (!rootRequested) {
      rootRequested = true;
      requestRoot();
    } else ((TextView) findViewById(R.id.root_status)).setText(R.string.restart_note);
  }

  private void notifyConfig() {
    getContentResolver().notifyChange(ConfigProvider.URI, null);
    showStatus();
  }

  private void showStatus() {
    if (isDestroyed() || statusCards == null) return;
    var report = getSharedPreferences("host_status", MODE_PRIVATE);
    boolean dateOn =
        prefs.getBoolean("enabled", true)
            && prefs.getBoolean("systemui_enabled", true)
            && prefs.getBoolean("clock_date_left", false);
    String dateKey = "com.android.systemui.clock_date";
    String dateState = dateOn ? report.getString(dateKey + ".state", "") : "OFF";
    long dateReported = report.getLong(dateKey + ".elapsed", 0);
    if (dateOn
        && (dateReported > SystemClock.elapsedRealtime()
            || (dateReported < statusRequest
                && ("ACTIVE".equals(dateState) || "OFF".equals(dateState))))) dateState = "";
    int dateLabel =
        !dateOn
            ? R.string.status_off
            : switch (dateState) {
              case "ACTIVE" -> R.string.status_active;
              case "OFF" -> R.string.status_unused;
              case "UNSUPPORTED" -> R.string.status_unsupported;
              case "ERROR" -> R.string.status_error;
              default -> R.string.status_unconfirmed;
            };
    // A host-wide crash guard/compatibility gate also prevents this feature from loading.
    if (dateOn && dateState.isEmpty()) {
      String hostState = report.getString("com.android.systemui.state", "");
      if ("CRASH_GUARD".equals(hostState) || "UNSUPPORTED".equals(hostState)) {
        dateState = hostState;
        dateLabel =
            "CRASH_GUARD".equals(hostState) ? R.string.status_guard : R.string.status_unsupported;
      }
    }
    statusCards[6].update(
        dateLabel,
        dateState,
        dateOn,
        dateOn && !dateState.isEmpty() ? report.getString(dateKey + ".detail", "") : "");
    for (int i = 0; i < ConfigProvider.HOSTS.length; i++) {
      String host = ConfigProvider.HOSTS[i];
      boolean on = prefs.getBoolean("enabled", true) && prefs.getBoolean(switches[i + 1], true);
      String state = report.getString(host + ".state", "");
      long reported = report.getLong(host + ".elapsed", 0);
      // ACTIVE/OFF describe a live process and need a fresh response. Terminal diagnostics remain
      // useful when that process deliberately installs no observers (especially CRASH_GUARD).
      if (reported > SystemClock.elapsedRealtime()
          || (reported < statusRequest && ("ACTIVE".equals(state) || "OFF".equals(state))))
        state = "";
      int label =
          !on
              ? R.string.status_off
              : switch (state) {
                case "ACTIVE" -> R.string.status_active;
                case "OFF" -> R.string.status_unused;
                case "CRASH_GUARD" -> R.string.status_guard;
                case "UNSUPPORTED" -> R.string.status_unsupported;
                case "ERROR" -> R.string.status_error;
                default -> R.string.status_unconfirmed;
              };
      statusCards[i].update(
          label, state, on, on && !state.isEmpty() ? report.getString(host + ".detail", "") : "");
      if (i == 0) {
        String detail = report.getString(host + ".detail", "");
        boolean gridOn = on && prefs.getBoolean("diy_icon_grid", false);
        String gridState = gridOn ? state : "OFF";
        int gridLabel = gridOn ? label : R.string.status_off;
        if (gridOn && "ACTIVE".equals(state)) {
          if (detail.contains("diyGrid=error:")) {
            gridState = "ERROR";
            gridLabel = R.string.status_error;
          } else if (!detail.contains("diyGrid=active")) {
            gridState = "";
            gridLabel = R.string.status_unconfirmed;
          }
        }
        statusCards[7].update(
            gridLabel,
            gridState,
            gridOn,
            gridOn && !gridState.isEmpty()
                ? detail.substring(Math.max(0, detail.indexOf("diyGrid=")))
                : "");
        boolean localOn = on && prefs.getInt("label", ColorPolicy.LOCAL) == ColorPolicy.LOCAL;
        String localState = !localOn ? "OFF" : state;
        int localLabel = !localOn ? R.string.status_off : label;
        if (localOn && "ACTIVE".equals(state)) {
          if (detail.contains("localLabels=error:")) {
            localState = "ERROR";
            localLabel = R.string.status_error;
          } else if (detail.contains("localLabels=unavailable")) {
            localState = "UNSUPPORTED";
            localLabel = R.string.status_unsupported;
          } else if (detail.contains("wallpaper=static")) {
            localState = "OFF";
            localLabel = R.string.status_unused;
          } else if (detail.contains("localLabels=geometry-skipped")) {
            localState = "UNSUPPORTED";
            localLabel = R.string.status_unsupported;
          } else if (!detail.contains("localLabels=active")) {
            localState = "";
            localLabel = R.string.status_unconfirmed;
          }
        }
        statusCards[5].update(
            localLabel,
            localState,
            localOn,
            localOn && !localState.isEmpty()
                ? detail.substring(Math.max(0, detail.indexOf("localLabels="))).split(";", 2)[0]
                : "");
        boolean recentsOn = on && prefs.getBoolean("recents_portrait", false);
        String recentsState = state;
        int recentsLabel = label;
        if (!recentsOn) {
          recentsState = "OFF";
          recentsLabel = R.string.status_off;
        } else if ("ACTIVE".equals(state)) {
          if (detail.contains("recents=unavailable")) {
            recentsState = "UNSUPPORTED";
            recentsLabel = R.string.status_unsupported;
          } else if (detail.contains("recents=error:")) {
            recentsState = "ERROR";
            recentsLabel = R.string.status_error;
          } else if (!detail.contains("recents=portrait")) {
            recentsState = "";
            recentsLabel = R.string.status_unconfirmed;
          }
        }
        statusCards[3].update(
            recentsLabel,
            recentsState,
            recentsOn,
            recentsOn && !recentsState.isEmpty()
                ? detail.substring(Math.max(0, detail.indexOf("recents=")))
                : "");
        boolean barOn = on && prefs.getBoolean("recents_hide_bar", false);
        String barState = state;
        int barLabel = label;
        if (!barOn) {
          barState = "OFF";
          barLabel = R.string.status_off;
        } else if ("ACTIVE".equals(state)) {
          if (detail.contains("recentsBar=unavailable")) {
            barState = "UNSUPPORTED";
            barLabel = R.string.status_unsupported;
          } else if (detail.contains("recentsBar=error:")) {
            barState = "ERROR";
            barLabel = R.string.status_error;
          } else if (detail.contains("recentsBar=buttons-skipped")
              || detail.contains("recentsBar=system")) {
            barState = "OFF";
            barLabel = R.string.status_unused;
          } else if (!detail.contains("recentsBar=ready")
              && !detail.contains("recentsBar=hidden")) {
            barState = "";
            barLabel = R.string.status_unconfirmed;
          }
        }
        statusCards[4].update(
            barLabel,
            barState,
            barOn,
            barOn && !barState.isEmpty()
                ? detail.contains("recentsBar=buttons-skipped")
                    ? getString(R.string.recents_bar_buttons)
                    : detail.contains("recentsBar=system")
                        ? getString(R.string.recents_bar_system)
                        : detail.substring(Math.max(0, detail.indexOf("recentsBar=")))
                : "");
      }
    }
  }

  private void language() {
    String[] tags = {"", "en", "ja", "zh-CN", "zh-TW", "ko"};
    String[] names = {getString(R.string.follow_system), "English", "日本語", "简体中文", "繁體中文", "한국어"};
    String current = AppCompatDelegate.getApplicationLocales().toLanguageTags();
    int selected = 0;
    for (int i = 1; i < tags.length; i++) if (tags[i].equals(current)) selected = i;
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.language_title)
        .setSingleChoiceItems(
            names,
            selected,
            (d, index) -> {
              prefs.edit().putString("language", tags[index]).apply();
              d.dismiss();
              AppCompatDelegate.setApplicationLocales(
                  LocaleListCompat.forLanguageTags(tags[index]));
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void snack(int message) {
    if (!isDestroyed()) Snackbar.make(root, message, Snackbar.LENGTH_LONG).show();
  }

  private void requestRoot() {
    commands.execute(
        () -> {
          boolean result = command("id", 30, true);
          runOnUiThread(
              () -> {
                if (!isDestroyed())
                  ((TextView) findViewById(R.id.root_status))
                      .setText(result ? R.string.root_granted : R.string.root_missing);
              });
        });
  }

  private static boolean command(String cmd, int seconds, boolean verifyRoot) {
    java.lang.Process process = null;
    try {
      process = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
      if (!process.waitFor(seconds, TimeUnit.SECONDS) || process.exitValue() != 0) return false;
      if (!verifyRoot) return true;
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] b = new byte[1024];
      int n;
      while ((n = process.getInputStream().read(b)) != -1 && out.size() < 16384) out.write(b, 0, n);
      return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
          .contains("uid=0(");
    } catch (Exception e) {
      Log.w("OneUiLiveColorsUI", "Root operation failed", e);
      return false;
    } finally {
      if (process != null) process.destroy();
    }
  }

  private void restartHome(MaterialButton button) {
    button.setEnabled(false);
    button.setText(R.string.restart_busy);
    commands.execute(
        () -> {
          boolean success =
              command(
                  "am force-stop --user 0 com.sec.android.app.launcher && am start --user 0 -a"
                      + " android.intent.action.MAIN -c android.intent.category.HOME",
                  20,
                  false);
          runOnUiThread(
              () -> {
                if (isDestroyed()) return;
                button.setEnabled(true);
                button.setText(R.string.restart_home);
                snack(success ? R.string.restart_done : R.string.restart_failed);
              });
        });
  }

  private void reloadTarget(int index) {
    // Package names and operations are constants, never user-provided shell text.
    String cmd =
        index == 0
            ? "pid=$(pidof com.android.systemui); test -n \"$pid\" && kill -TERM $pid"
            : "am force-stop --user 0 com.samsung.android.app.dressroom";
    commands.execute(
        () -> {
          boolean success = command(cmd, 20, false);
          runOnUiThread(() -> snack(success ? R.string.reload_done : R.string.reload_failed));
        });
  }

  private void resetProtection() {
    StringBuilder cmd = new StringBuilder("rm -f");
    for (String root : new String[] {"/data/user/0/", "/data/user_de/0/"})
      for (String host : ConfigProvider.HOSTS)
        for (String suffix : new String[] {"", ".bak", ".new"})
          cmd.append(' ')
              .append(root)
              .append(host)
              .append("/files/")
              .append(CrashGuard.FILE)
              .append(suffix);
    commands.execute(
        () -> {
          boolean success = command(cmd.toString(), 20, false);
          runOnUiThread(
              () -> {
                if (success) {
                  getSharedPreferences("host_status", MODE_PRIVATE).edit().clear().apply();
                  showStatus();
                }
                snack(success ? R.string.guard_reset_done : R.string.guard_reset_failed);
              });
        });
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (prefs != null) {
      statusRequest = SystemClock.elapsedRealtime();
      getContentResolver().notifyChange(ConfigProvider.URI, null);
      showStatus();
    }
  }

  @Override
  protected void onDestroy() {
    getContentResolver().unregisterContentObserver(reports);
    commands.shutdown();
    super.onDestroy();
  }
}
