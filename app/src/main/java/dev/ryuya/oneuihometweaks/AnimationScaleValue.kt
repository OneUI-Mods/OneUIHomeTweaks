package dev.ryuya.oneuihometweaks

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.jvm.JvmStatic

/* Exact hundredths for the editor; no repeated floating-point addition. */
object AnimationScaleValue {
    const val MAX: Int = 1000

    @JvmStatic
    fun parse(input: String): Int {
        val normalized = StringBuilder()
        for (c in input.trim { it <= ' ' }.toCharArray()) {
            val digit: Int = c.digitToIntOrNull() ?: -1
            if (digit >= 0) normalized.append(digit)
            else if (c == '.' || c == ',' || c == '\u066b' || c == '\uff0e') normalized.append('.')
            else throw IllegalArgumentException("Invalid decimal")
        }
        require(normalized.toString().matches("[0-9]+(?:\\.[0-9]*)?".toRegex())) {
            "Invalid decimal"
        }
        val number = BigDecimal(normalized.toString())
        val ticks = number.movePointRight(2).intValueExact()
        require(!(ticks < 0 || ticks > AnimationScaleValue.MAX)) { "Out of range" }
        return ticks
    }

    @JvmStatic
    fun ticks(value: Float): Int {
        require(
            !(!java.lang.Float.isFinite(value) ||
                value < 0 ||
                value > AnimationScaleValue.MAX / 100f)
        ) {
            "Unsupported current scale"
        }
        return BigDecimal(value.toString())
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .intValueExact()
    }

    @JvmStatic
    fun wire(ticks: Int): String? {
        require(!(ticks < 0 || ticks > AnimationScaleValue.MAX)) { "Out of range" }
        return BigDecimal.valueOf(ticks.toLong(), 2).toPlainString()
    }

    @JvmStatic
    fun format(ticks: Int, locale: Locale?): String? {
        val number = NumberFormat.getNumberInstance(locale)
        number.setGroupingUsed(false)
        number.setMinimumFractionDigits(2)
        number.setMaximumFractionDigits(2)
        return number.format(BigDecimal(AnimationScaleValue.wire(ticks)))
    }
}
