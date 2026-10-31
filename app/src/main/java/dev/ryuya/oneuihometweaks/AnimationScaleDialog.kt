package dev.ryuya.oneuihometweaks

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.DialogInterface.OnShowListener
import android.text.Editable
import android.text.InputFilter
import android.text.InputFilter.LengthFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.View.OnClickListener
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import java.util.Locale
import kotlin.math.log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/* Uses the Settings host's Samsung dialog/theme; module resources supply only translated text. */
class AnimationScaleDialog(
    host: Context,
    title: CharSequence?,
    current: Float,
    save: AnimationScaleDialog.Save,
) {
    fun interface Save {
        @Throws(Exception::class) fun apply(value: String?)
    }

    private val host: Context
    private val text: Context
    private val save: AnimationScaleDialog.Save
    private val locale: Locale
    private val input: EditText
    private val slider: SeekBar
    private val minus: Button
    private val plus: Button
    private val dialog: Dialog
    private var positive: Button? = null
    private var valid: Boolean = false
    private var ticks: Int

    init {
        this.host = host
        this.text = host.createPackageContext(BuildConfig.APPLICATION_ID, 0)
        this.save = save
        locale = host.getResources().getConfiguration().getLocales().get(0)
        ticks = AnimationScaleValue.ticks(current)
        val panel = LinearLayout(host)
        panel.setOrientation(LinearLayout.VERTICAL)
        panel.setPadding(dp(24), dp(8), dp(24), dp(8))
        val hint = TextView(host)
        hint.setText(text.getString(R.string.animation_scale_hint))
        panel.addView(hint, LayoutParams(-1, -2))
        val number = LinearLayout(host)
        number.setGravity(Gravity.CENTER_VERTICAL)
        panel.addView(number, LayoutParams(-1, -2))
        minus = button("−", text.getString(R.string.animation_scale_decrease))
        plus = button("+", text.getString(R.string.animation_scale_increase))
        input = EditText(host)
        input.setSingleLine(true)
        input.setGravity(Gravity.CENTER)
        input.setTextSize(24f)
        input.setInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        input.setFilters(arrayOf<InputFilter>(LengthFilter(12)))
        input.setContentDescription(text.getString(R.string.animation_scale_value))
        number.addView(minus, LayoutParams(dp(56), dp(56)))
        number.addView(input, LayoutParams(0, -2, 1f))
        val suffix = TextView(host)
        suffix.setText("×")
        suffix.setTextSize(20f)
        number.addView(suffix)
        number.addView(plus, LayoutParams(dp(56), dp(56)))
        slider = SeekBar(host)
        slider.setMax(AnimationScaleValue.MAX)
        slider.setKeyProgressIncrement(1)
        slider.setContentDescription(text.getString(R.string.animation_scale_value))
        panel.addView(slider, LayoutParams(-1, dp(56)))
        val builderType =
            Class.forName(
                "androidx.appcompat.app.AlertDialog\$Builder",
                false,
                host.getClassLoader(),
            )
        val builder = builderType.getConstructor(Context::class.java).newInstance(host)
        // Samsung removes unused Builder overloads. Use the reviewed host parameter fields.
        val parameters = builderType.getField("P").get(builder)
        parameters.javaClass.getField("mTitle").set(parameters, title)
        parameters.javaClass
            .getField("mPositiveButtonText")
            .set(parameters, text.getString(R.string.animation_scale_save))
        parameters.javaClass
            .getField("mNegativeButtonText")
            .set(parameters, host.getString(android.R.string.cancel))
        parameters.javaClass
            .getField("mNeutralButtonText")
            .set(parameters, text.getString(R.string.animation_scale_default))
        val content = ScrollView(host)
        content.setFillViewport(true)
        content.addView(panel)
        builderType.getMethod("setView", View::class.java).invoke(builder, content)
        dialog = (builderType.getMethod("create").invoke(builder) as Dialog?)!!
        dialog.setOnShowListener(
            OnShowListener { ignored: DialogInterface? ->
                try {
                    positive = getButton(DialogInterface.BUTTON_POSITIVE)
                    positive!!.setOnClickListener(OnClickListener { v: View? -> commit() })
                    getButton(DialogInterface.BUTTON_NEUTRAL)!!.setOnClickListener(
                        OnClickListener { v: View? -> setTicks(100) }
                    )
                    validate()
                } catch (error: Exception) {
                    HookEntry.log(
                        "ANIMATION_EDITOR_DIALOG_ERROR " + error.javaClass.getSimpleName()
                    )
                    dialog.dismiss()
                }
            }
        )
        input.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    validate()
                }

                override fun afterTextChanged(value: Editable?) {}
            }
        )
        slider.setOnSeekBarChangeListener(
            object : OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar?) {}

                override fun onStopTrackingTouch(bar: SeekBar?) {}

                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) setTicks(value)
                }
            }
        )
        minus.setOnClickListener(OnClickListener { v: View? -> setTicks(max(0, ticks - 1)) })
        plus.setOnClickListener(
            OnClickListener { v: View? -> setTicks(min(AnimationScaleValue.MAX, ticks + 1)) }
        )
        setTicks(ticks)
    }

    fun show() {
        dialog.show()
    }

    fun dismiss() {
        dialog.dismiss()
    }

    fun showing(): Boolean {
        return dialog.isShowing()
    }

    @Throws(Exception::class)
    private fun getButton(which: Int): Button? {
        return dialog.javaClass
            .getMethod("getButton", Int::class.javaPrimitiveType)
            .invoke(dialog, which) as Button?
    }

    private fun setTicks(value: Int) {
        input.setText(AnimationScaleValue.format(value, locale))
        input.setSelection(input.length())
    }

    private fun validate() {
        try {
            ticks = AnimationScaleValue.parse(input.getText().toString())
            valid = true
            input.setError(null)
            slider.setProgress(ticks)
        } catch (invalid: IllegalArgumentException) {
            valid = false
            input.setError(text.getString(R.string.animation_scale_invalid))
        } catch (invalid: ArithmeticException) {
            valid = false
            input.setError(text.getString(R.string.animation_scale_invalid))
        }
        if (positive != null) positive!!.setEnabled(valid)
        minus.setEnabled(valid && ticks > 0)
        plus.setEnabled(valid && ticks < AnimationScaleValue.MAX)
    }

    private fun commit() {
        validate()
        if (!valid) return
        try {
            save.apply(AnimationScaleValue.wire(ticks))
            dialog.dismiss()
        } catch (error: Exception) {
            input.setError(text.getString(R.string.animation_scale_failed))
            HookEntry.log("ANIMATION_EDITOR_SAVE_ERROR " + error.javaClass.getSimpleName())
        }
    }

    private fun button(label: String?, description: String?): Button {
        val button = Button(host)
        button.setText(label)
        button.setContentDescription(description)
        button.setMinWidth(0)
        button.setMinimumWidth(0)
        return button
    }

    private fun dp(value: Int): Int {
        return Math.round(value * host.getResources().getDisplayMetrics().density)
    }
}
