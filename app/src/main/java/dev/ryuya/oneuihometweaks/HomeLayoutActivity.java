package dev.ryuya.oneuihometweaks;

import android.content.*;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User controls and an explicit, local-only export for home layout diagnostics. */
public final class HomeLayoutActivity extends AppCompatActivity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private TextView status;

  @Override
  public void onCreate(Bundle saved) {
    AppUiTheme.apply(this);
    super.onCreate(saved);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    MaterialToolbar toolbar = new MaterialToolbar(this);
    toolbar.setTitle(R.string.layout_diagnostics);
    toolbar.setNavigationIcon(R.drawable.ic_back);
    toolbar.setNavigationContentDescription(R.string.back);
    toolbar.setNavigationOnClickListener(v -> finish());
    root.addView(toolbar);
    ScrollView scroll = new ScrollView(this);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (20 * getResources().getDisplayMetrics().density);
    content.setPadding(padding, padding, padding, padding);
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView note = new TextView(this);
    note.setText(R.string.layout_note);
    note.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
    content.addView(note);
    addSwitch(content, "layout_protect", R.string.layout_protect);
    addSwitch(content, "layout_logging", R.string.layout_logging);
    TextView privacy = new TextView(this);
    privacy.setText(R.string.layout_privacy);
    privacy.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    content.addView(privacy);
    status = new TextView(this);
    status.setPadding(0, padding, 0, padding);
    status.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    content.addView(status);
    MaterialButton share = new MaterialButton(this);
    share.setText(R.string.layout_share);
    share.setOnClickListener(v -> export());
    content.addView(share);
    MaterialButton clear =
        new MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    clear.setText(R.string.layout_clear);
    clear.setOnClickListener(
        v ->
            new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.layout_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                    android.R.string.ok,
                    (d, w) ->
                        worker.execute(
                            () -> {
                              try {
                                HomeLayoutJournal.clear(this);
                                runOnUiThread(this::refresh);
                              } catch (Exception error) {
                                fail();
                              }
                            }))
                .show());
    content.addView(clear);
    setContentView(root);
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
    refresh();
  }

  private void addSwitch(LinearLayout content, String key, int title) {
    SharedPreferences prefs = getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE);
    MaterialSwitch control = new MaterialSwitch(this);
    control.setText(title);
    control.setMinHeight((int) (64 * getResources().getDisplayMetrics().density));
    control.setChecked(prefs.getBoolean(key, true));
    control.setOnCheckedChangeListener(
        (button, checked) -> {
          prefs.edit().putBoolean(key, checked).apply();
          getContentResolver().notifyChange(ConfigProvider.URI, null);
        });
    content.addView(control);
  }

  private void refresh() {
    worker.execute(
        () -> {
          long bytes = HomeLayoutJournal.bytes(this);
          runOnUiThread(() -> status.setText(getString(R.string.layout_log_size, bytes / 1024)));
        });
  }

  private void export() {
    worker.execute(
        () -> {
          try {
            var file = HomeLayoutJournal.export(this);
            var uri = FileProvider.getUriForFile(this, getPackageName() + ".diagnostics", file);
            Intent share =
                new Intent(Intent.ACTION_SEND)
                    .setType("application/x-ndjson")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri("Home layout diagnostics", uri));
            runOnUiThread(
                () -> startActivity(Intent.createChooser(share, getString(R.string.layout_share))));
          } catch (Exception error) {
            fail();
          }
        });
  }

  private void fail() {
    runOnUiThread(() -> Toast.makeText(this, R.string.layout_io_error, Toast.LENGTH_LONG).show());
  }

  @Override
  protected void onDestroy() {
    worker.shutdown();
    super.onDestroy();
  }
}
