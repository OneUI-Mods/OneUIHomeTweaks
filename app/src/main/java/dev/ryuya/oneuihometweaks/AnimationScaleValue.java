package dev.ryuya.oneuihometweaks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/** Exact hundredths for the editor; no repeated floating-point addition. */
final class AnimationScaleValue {
  static final int MAX = 1000;

  static int parse(String input) {
    StringBuilder normalized = new StringBuilder();
    for (char c : input.trim().toCharArray()) {
      int digit = Character.digit(c, 10);
      if (digit >= 0) normalized.append(digit);
      else if (c == '.' || c == ',' || c == '\u066b' || c == '\uff0e') normalized.append('.');
      else throw new IllegalArgumentException("Invalid decimal");
    }
    if (!normalized.toString().matches("[0-9]+(?:\\.[0-9]*)?"))
      throw new IllegalArgumentException("Invalid decimal");
    BigDecimal number = new BigDecimal(normalized.toString());
    int ticks = number.movePointRight(2).intValueExact();
    if (ticks < 0 || ticks > MAX) throw new IllegalArgumentException("Out of range");
    return ticks;
  }

  static int ticks(float value) {
    if (!Float.isFinite(value) || value < 0 || value > MAX / 100f)
      throw new IllegalArgumentException("Unsupported current scale");
    return new BigDecimal(Float.toString(value))
        .movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .intValueExact();
  }

  static String wire(int ticks) {
    if (ticks < 0 || ticks > MAX) throw new IllegalArgumentException("Out of range");
    return BigDecimal.valueOf(ticks, 2).toPlainString();
  }

  static String format(int ticks, Locale locale) {
    NumberFormat number = NumberFormat.getNumberInstance(locale);
    number.setGroupingUsed(false);
    number.setMinimumFractionDigits(2);
    number.setMaximumFractionDigits(2);
    return number.format(new BigDecimal(wire(ticks)));
  }
}
