package dev.ryuya.oneuihometweaks;

import static org.junit.Assert.*;

import java.util.Locale;
import org.junit.Test;

public class AnimationScaleValueTest {
  @Test
  public void hundredStepsDoNotDrift() {
    int ticks = AnimationScaleValue.parse("1.19");
    for (int i = 0; i < 100; i++)
      ticks = AnimationScaleValue.parse(AnimationScaleValue.wire(ticks + 1));
    assertEquals(219, ticks);
    assertEquals("2.19", AnimationScaleValue.wire(ticks));
  }

  @Test
  public void localizedNumbersAndExistingScaleRoundTrip() {
    for (Locale locale : new Locale[] {Locale.JAPAN, Locale.GERMANY, Locale.forLanguageTag("ar")})
      assertEquals(119, AnimationScaleValue.parse(AnimationScaleValue.format(119, locale)));
    assertEquals(119, AnimationScaleValue.ticks(1.19f));
    assertEquals(119, AnimationScaleValue.parse("１．１９"));
    assertEquals(119, AnimationScaleValue.parse("1.1900"));
  }

  @Test
  public void rejectInvalidValuesRatherThanClampOrRound() {
    for (String value :
        new String[] {
          "", "NaN", "Infinity", "-1", "10.01", "1.191", "1e2", "1.2.3", "1 19", "999999999999999"
        }) {
      assertThrows(RuntimeException.class, () -> AnimationScaleValue.parse(value));
    }
    assertThrows(IllegalArgumentException.class, () -> AnimationScaleValue.ticks(Float.NaN));
    assertEquals(0, AnimationScaleValue.parse("0"));
    assertEquals(1000, AnimationScaleValue.parse("10.00"));
  }
}
