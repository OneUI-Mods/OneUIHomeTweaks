package net.ryuya_dev.oneuilivecolors;

import android.content.Context;
import android.content.res.ColorStateList;
import android.widget.*;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;

/** Accessible state shape, icon and badge; never relies on color alone. */
final class HookStatusCard extends MaterialCardView {
  private final TextView detail;
  private final Chip badge;
  private final ImageView icon;

  HookStatusCard(Context context, int title) {
    super(context);
    setRadius(dp(20));
    setStrokeWidth(0);
    setCardElevation(0);
    setCardBackgroundColor(
        MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurfaceContainerHigh));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(12);
    setLayoutParams(params);
    LinearLayout box = new LinearLayout(context);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(16), dp(16), dp(16), dp(16));
    addView(box);
    LinearLayout row = new LinearLayout(context);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    box.addView(row);
    icon = new ImageView(context);
    row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
    TextView name = new TextView(context);
    name.setText(title);
    name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
    LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
    nameParams.setMarginStart(dp(12));
    row.addView(name, nameParams);
    badge = new Chip(context);
    badge.setCheckable(false);
    badge.setClickable(false);
    badge.setFocusable(false);
    badge.setEnsureMinTouchTargetSize(false);
    box.addView(badge, new LinearLayout.LayoutParams(-2, -2));
    detail = new TextView(context);
    detail.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    detail.setTextIsSelectable(true);
    box.addView(detail);
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  void update(int label, String state, boolean on, String info) {
    int image = R.drawable.status_wait;
    int color =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant);
    int background =
        MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurfaceContainerHighest);
    if (!on || "OFF".equals(state)) image = R.drawable.status_off;
    else if ("ACTIVE".equals(state)) {
      image = R.drawable.status_ok;
      color =
          MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer);
      background =
          MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer);
    } else if ("CRASH_GUARD".equals(state)
        || "ERROR".equals(state)
        || "UNSUPPORTED".equals(state)) {
      image = "CRASH_GUARD".equals(state) ? R.drawable.status_shield : R.drawable.status_error;
      color =
          MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnErrorContainer);
      background =
          MaterialColors.getColor(this, com.google.android.material.R.attr.colorErrorContainer);
    }
    icon.setImageResource(image);
    icon.setImageTintList(ColorStateList.valueOf(color));
    icon.setContentDescription(getResources().getString(label));
    badge.setText(label);
    badge.setTextColor(color);
    badge.setChipBackgroundColor(ColorStateList.valueOf(background));
    detail.setText(
        info.contains("hints=null") && !info.contains("localLabels=active")
            ? R.string.status_no_colors
            : "ACTIVE".equals(state) ? R.string.status_response : R.string.status_open_details);
    detail.setVisibility(info.isEmpty() ? GONE : VISIBLE);
    setClickable(!info.isEmpty());
    setFocusable(!info.isEmpty());
    setOnClickListener(
        v -> {
          if (!info.isEmpty())
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.status_details)
                .setMessage(info)
                .setPositiveButton(R.string.close, null)
                .show();
        });
    setStateDescription(getResources().getString(label));
  }
}
