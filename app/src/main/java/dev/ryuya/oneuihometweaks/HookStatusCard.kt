package dev.ryuya.oneuihometweaks

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.View.OnClickListener
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.round

/* Accessible state shape, icon and badge; never relies on color alone. */
class HookStatusCard(context: Context?, title: Int) : MaterialCardView(context) {
    private val detail: TextView
    private val badge: Chip
    private val icon: ImageView

    init {
        setRadius(dp(28).toFloat())
        setStrokeWidth(0)
        setCardElevation(0f)
        setCardBackgroundColor(
            MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorSurfaceContainerLow,
            )
        )
        val params = LayoutParams(-1, -2)
        params.topMargin = dp(12)
        setLayoutParams(params)
        val box = LinearLayout(context)
        box.setOrientation(LinearLayout.VERTICAL)
        box.setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(box)
        val row = LinearLayout(context)
        row.setGravity(Gravity.CENTER_VERTICAL)
        box.addView(row)
        icon = ImageView(context)
        row.addView(icon, LayoutParams(dp(32), dp(32)))
        val name = TextView(context)
        name.setText(title)
        name.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_TitleSmall
        )
        val nameParams = LayoutParams(0, -2, 1f)
        nameParams.setMarginStart(dp(12))
        row.addView(name, nameParams)
        badge = Chip(context)
        badge.setCheckable(false)
        badge.setClickable(false)
        badge.setFocusable(false)
        badge.setEnsureMinTouchTargetSize(false)
        box.addView(badge, LayoutParams(-2, -2))
        detail = TextView(context)
        detail.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall
        )
        detail.setTextIsSelectable(true)
        box.addView(detail)
    }

    private fun dp(n: Int): Int {
        return Math.round(n * getResources().getDisplayMetrics().density)
    }

    fun update(label: Int, state: String, on: Boolean, info: String) {
        var image = R.drawable.status_wait
        var color =
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        var background =
            MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorSurfaceContainerHighest,
            )
        if (!on || "OFF" == state) image = R.drawable.status_off
        else if ("ACTIVE" == state) {
            image = R.drawable.status_ok
            color =
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnPrimaryContainer,
                )
            background =
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorPrimaryContainer,
                )
        } else if ("CRASH_GUARD" == state || "ERROR" == state || "UNSUPPORTED" == state) {
            image =
                if ("CRASH_GUARD" == state) R.drawable.status_shield else R.drawable.status_error
            color =
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnErrorContainer,
                )
            background =
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorErrorContainer,
                )
        }
        icon.setImageResource(image)
        icon.setImageTintList(ColorStateList.valueOf(color))
        icon.setContentDescription(getResources().getString(label))
        badge.setText(label)
        badge.setTextColor(color)
        badge.setChipBackgroundColor(ColorStateList.valueOf(background))
        val waiting: Boolean = on && state.isEmpty()
        detail.setText(
            if (waiting) R.string.status_waiting_note
            else if (info.contains("hints=null") && !info.contains("localLabels=active"))
                R.string.status_no_colors
            else if ("ACTIVE" == state) R.string.status_response else R.string.status_open_details
        )
        detail.setVisibility(
            if ((info.isEmpty() && !waiting) || "ACTIVE" == state) View.GONE else View.VISIBLE
        )
        setClickable(!info.isEmpty())
        setFocusable(!info.isEmpty())
        setOnClickListener(
            OnClickListener { v: View? ->
                if (!info.isEmpty())
                    MaterialAlertDialogBuilder(getContext())
                        .setTitle(R.string.status_details)
                        .setMessage(info)
                        .setPositiveButton(R.string.close, null)
                        .show()
            }
        )
        setStateDescription(getResources().getString(label))
    }
}
