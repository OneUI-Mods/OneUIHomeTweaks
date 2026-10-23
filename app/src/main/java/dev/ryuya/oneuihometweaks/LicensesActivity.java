package dev.ryuya.oneuihometweaks;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import com.google.android.material.appbar.MaterialToolbar;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class LicensesActivity extends AppCompatActivity {
  @Override
  public void onCreate(Bundle saved) {
    AppUiTheme.apply(this);
    super.onCreate(saved);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(
        com.google.android.material.color.MaterialColors.getColor(
            root, com.google.android.material.R.attr.colorSurface));
    MaterialToolbar bar = new MaterialToolbar(this);
    bar.setTitle(R.string.license_title);
    bar.setNavigationIcon(R.drawable.ic_back);
    bar.setNavigationContentDescription(R.string.back);
    bar.setNavigationOnClickListener(v -> finish());
    root.addView(bar);
    ScrollView scroll = new ScrollView(this);
    TextView text = new TextView(this);
    text.setTextIsSelectable(true);
    int p = (int) (20 * getResources().getDisplayMetrics().density);
    text.setPadding(p, p, p, p);
    text.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    scroll.addView(text);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
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
    try {
      text.setText(
          getString(R.string.own_license)
              + "\n\n"
              + asset("APP_LICENSE.txt")
              + "\n\n"
              + asset("THIRD_PARTY_NOTICES.txt"));
    } catch (IOException e) {
      text.setText(R.string.license_unavailable);
    }
  }

  private String asset(String name) throws IOException {
    try (InputStream stream = getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[4096];
      int n;
      while ((n = stream.read(b)) != -1) out.write(b, 0, n);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
