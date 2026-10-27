package dev.ryuya.oneuihometweaks;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.Locale;

/** Uses the Settings host's Samsung dialog/theme; module resources supply only translated text. */
final class AnimationScaleDialog {
  interface Save {
    void apply(String value) throws Exception;
  }

  private final Context host, text;
  private final Save save;
  private final Locale locale;
  private final EditText input;
  private final SeekBar slider;
  private final Button minus, plus;
  private final Dialog dialog;
  private Button positive;
  private boolean valid;
  private int ticks;

  AnimationScaleDialog(Context host, CharSequence title, float current, Save save)
      throws Exception {
    this.host = host;
    this.text = host.createPackageContext(BuildConfig.APPLICATION_ID, 0);
    this.save = save;
    locale = host.getResources().getConfiguration().getLocales().get(0);
    ticks = AnimationScaleValue.ticks(current);
    LinearLayout panel = new LinearLayout(host);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(24), dp(8), dp(24), dp(8));
    TextView hint = new TextView(host);
    hint.setText(text.getString(R.string.animation_scale_hint));
    panel.addView(hint, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout number = new LinearLayout(host);
    number.setGravity(Gravity.CENTER_VERTICAL);
    panel.addView(number, new LinearLayout.LayoutParams(-1, -2));
    minus = button("−", text.getString(R.string.animation_scale_decrease));
    plus = button("+", text.getString(R.string.animation_scale_increase));
    input = new EditText(host);
    input.setSingleLine(true);
    input.setGravity(Gravity.CENTER);
    input.setTextSize(24);
    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(12)});
    input.setContentDescription(text.getString(R.string.animation_scale_value));
    number.addView(minus, new LinearLayout.LayoutParams(dp(56), dp(56)));
    number.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
    TextView suffix = new TextView(host);
    suffix.setText("×");
    suffix.setTextSize(20);
    number.addView(suffix);
    number.addView(plus, new LinearLayout.LayoutParams(dp(56), dp(56)));
    slider = new SeekBar(host);
    slider.setMax(AnimationScaleValue.MAX);
    slider.setKeyProgressIncrement(1);
    slider.setContentDescription(text.getString(R.string.animation_scale_value));
    panel.addView(slider, new LinearLayout.LayoutParams(-1, dp(56)));
    Class<?> builderType =
        Class.forName("androidx.appcompat.app.AlertDialog$Builder", false, host.getClassLoader());
    Object builder = builderType.getConstructor(Context.class).newInstance(host);
    // Samsung removes unused Builder overloads. Use the reviewed host parameter fields.
    Object parameters = builderType.getField("P").get(builder);
    parameters.getClass().getField("mTitle").set(parameters, title);
    parameters
        .getClass()
        .getField("mPositiveButtonText")
        .set(parameters, text.getString(R.string.animation_scale_save));
    parameters
        .getClass()
        .getField("mNegativeButtonText")
        .set(parameters, host.getString(android.R.string.cancel));
    parameters
        .getClass()
        .getField("mNeutralButtonText")
        .set(parameters, text.getString(R.string.animation_scale_default));
    ScrollView content = new ScrollView(host);
    content.setFillViewport(true);
    content.addView(panel);
    builderType.getMethod("setView", View.class).invoke(builder, content);
    dialog = (Dialog) builderType.getMethod("create").invoke(builder);
    dialog.setOnShowListener(
        ignored -> {
          try {
            positive = getButton(DialogInterface.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> commit());
            getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> setTicks(100));
            validate();
          } catch (Exception error) {
            HookEntry.log("ANIMATION_EDITOR_DIALOG_ERROR " + error.getClass().getSimpleName());
            dialog.dismiss();
          }
        });
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            validate();
          }

          public void afterTextChanged(Editable value) {}
        });
    slider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar bar) {}

          public void onStopTrackingTouch(SeekBar bar) {}

          public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
            if (fromUser) setTicks(value);
          }
        });
    minus.setOnClickListener(v -> setTicks(Math.max(0, ticks - 1)));
    plus.setOnClickListener(v -> setTicks(Math.min(AnimationScaleValue.MAX, ticks + 1)));
    setTicks(ticks);
  }

  void show() {
    dialog.show();
  }

  void dismiss() {
    dialog.dismiss();
  }

  boolean showing() {
    return dialog.isShowing();
  }

  private Button getButton(int which) throws Exception {
    return (Button) dialog.getClass().getMethod("getButton", int.class).invoke(dialog, which);
  }

  private void setTicks(int value) {
    input.setText(AnimationScaleValue.format(value, locale));
    input.setSelection(input.length());
  }

  private void validate() {
    try {
      ticks = AnimationScaleValue.parse(input.getText().toString());
      valid = true;
      input.setError(null);
      slider.setProgress(ticks);
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      valid = false;
      input.setError(text.getString(R.string.animation_scale_invalid));
    }
    if (positive != null) positive.setEnabled(valid);
    minus.setEnabled(valid && ticks > 0);
    plus.setEnabled(valid && ticks < AnimationScaleValue.MAX);
  }

  private void commit() {
    validate();
    if (!valid) return;
    try {
      save.apply(AnimationScaleValue.wire(ticks));
      dialog.dismiss();
    } catch (Exception error) {
      input.setError(text.getString(R.string.animation_scale_failed));
      HookEntry.log("ANIMATION_EDITOR_SAVE_ERROR " + error.getClass().getSimpleName());
    }
  }

  private Button button(String label, String description) {
    Button button = new Button(host);
    button.setText(label);
    button.setContentDescription(description);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    return button;
  }

  private int dp(int value) {
    return Math.round(value * host.getResources().getDisplayMetrics().density);
  }
}
