package net.ryuya_dev.oneuilivecolors;

import android.content.*;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

/** Dedicated controls keep the appearance tab short and the four independent values visible. */
public final class QuickPanelActivity extends AppCompatActivity {
  private SharedPreferences prefs;
  private QuickPanelPreview preview;
  private final java.util.Set<Slider> dragging = new java.util.HashSet<>();
  private final Slider[] sliders = new Slider[4];
  private final TextView[] labels = new TextView[4];
  private final ContentObserver status =
      new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean self, Uri uri) {
          showStatus();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    AppUiTheme.apply(this);
    super.onCreate(state);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    setContentView(R.layout.activity_quick_panel);
    View root = findViewById(R.id.qs_root);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          Insets safe =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
          return WindowInsetsCompat.CONSUMED;
        });
    ViewCompat.requestApplyInsets(root);
    MaterialToolbar toolbar = findViewById(R.id.qs_toolbar);
    toolbar.setNavigationOnClickListener(v -> finish());
    prefs = getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE);
    preview = findViewById(R.id.qs_preview);
    int[] ids = {R.id.qs_brightness, R.id.qs_volume, R.id.qs_dark, R.id.qs_sound};
    int[] values = {
      R.id.qs_brightness_value, R.id.qs_volume_value, R.id.qs_dark_value, R.id.qs_sound_value
    };
    for (int i = 0; i < 4; i++) {
      final int index = i;
      Slider slider = findViewById(ids[i]);
      sliders[i] = slider;
      labels[i] = findViewById(values[i]);
      slider.setValueFrom(QuickPanelPolicy.MIN);
      slider.setValueTo(QuickPanelPolicy.MAX);
      slider.setStepSize(QuickPanelPolicy.STEP);
      slider.setValue(QuickPanelPolicy.percent(prefs.getInt(QuickPanelPolicy.KEYS[i], 100)));
      slider.setLabelFormatter(value -> getString(R.string.qs_percent, (int) value));
      slider.addOnChangeListener((s, value, user) -> updatePreview());
      slider.addOnSliderTouchListener(
          new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider s) {
              dragging.add(s);
            }

            @Override
            public void onStopTrackingTouch(Slider s) {
              dragging.remove(s);
              save(index);
            }
          });
      // Keyboard and accessibility increments do not dispatch touch-end callbacks.
      slider.addOnChangeListener(
          (s, value, user) -> {
            if (user && !dragging.contains(s)) save(index);
          });
    }
    MaterialSwitch master = findViewById(R.id.qs_enabled);
    master.setChecked(prefs.getBoolean("qs_enabled", false));
    master.setOnCheckedChangeListener(
        (button, checked) -> {
          prefs.edit().putBoolean("qs_enabled", checked).apply();
          notifyConfig();
          setControls(checked);
        });
    MaterialSwitch design = findViewById(R.id.qs_legacy_design);
    design.setChecked(
        android.os.Build.VERSION.SDK_INT == 36 || prefs.getBoolean("qs_legacy_design", false));
    design.setEnabled(android.os.Build.VERSION.SDK_INT == 37);
    design.setOnCheckedChangeListener(
        (button, checked) -> {
          prefs.edit().putBoolean("qs_legacy_design", checked).apply();
          notifyConfig();
          setControls(master.isChecked());
          updatePreview();
        });
    findViewById(R.id.qs_legacy_preset)
        .setOnClickListener(v -> applySizes(new int[] {110, 110, 105, 105}));
    setControls(master.isChecked());
    findViewById(R.id.qs_reset).setOnClickListener(v -> applySizes(new int[] {100, 100, 100, 100}));
    updatePreview();
  }

  private void applySizes(int[] values) {
    SharedPreferences.Editor edit = prefs.edit();
    for (int i = 0; i < sliders.length; i++) {
      sliders[i].setValue(values[i]);
      edit.putInt(QuickPanelPolicy.KEYS[i], values[i]);
    }
    edit.apply();
    notifyConfig();
    updatePreview();
  }

  private void save(int index) {
    prefs.edit().putInt(QuickPanelPolicy.KEYS[index], (int) sliders[index].getValue()).apply();
    notifyConfig();
  }

  private void notifyConfig() {
    getContentResolver().notifyChange(ConfigProvider.URI, null);
  }

  private void setControls(boolean enabled) {
    for (Slider slider : sliders) slider.setEnabled(enabled);
    findViewById(R.id.qs_legacy_design)
        .setEnabled(enabled && android.os.Build.VERSION.SDK_INT == 37);
    findViewById(R.id.qs_legacy_preset)
        .setEnabled(
            enabled
                && (android.os.Build.VERSION.SDK_INT == 36
                    || prefs.getBoolean("qs_legacy_design", false)));
  }

  private void updatePreview() {
    int[] sizes = new int[4];
    for (int i = 0; i < 4; i++) {
      if (sliders[i] == null) return;
      sizes[i] = (int) sliders[i].getValue();
      labels[i].setText(getString(R.string.qs_percent, sizes[i]));
    }
    preview.setLegacy(
        android.os.Build.VERSION.SDK_INT == 36 || prefs.getBoolean("qs_legacy_design", false));
    preview.setSizes(sizes);
  }

  private void showStatus() {
    var statusPrefs = getSharedPreferences("host_status", MODE_PRIVATE);
    String state = statusPrefs.getString("com.android.systemui.quick_panel.state", "WAITING");
    if ("CRASH_GUARD".equals(statusPrefs.getString("com.android.systemui.state", "")))
      state = "CRASH_GUARD";
    int message =
        switch (state) {
          case "ACTIVE" -> R.string.qs_active;
          case "PARTIAL" -> R.string.qs_legacy_unavailable;
          case "OFF" -> R.string.qs_off;
          case "UNSUPPORTED", "ERROR", "CRASH_GUARD" -> R.string.qs_unavailable;
          default -> R.string.qs_waiting;
        };
    ((TextView) findViewById(R.id.qs_status)).setText(message);
  }

  @Override
  protected void onStart() {
    super.onStart();
    getContentResolver().registerContentObserver(ConfigProvider.STATUS_URI, false, status);
    showStatus();
  }

  @Override
  protected void onStop() {
    getContentResolver().unregisterContentObserver(status);
    super.onStop();
  }
}
