package dev.ryuya.oneuihometweaks;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ClockDateOrderTest {
  private static String move(String text, String time, String spoken, String date) {
    var parts = ClockDateOrder.parts(text, time, spoken, date);
    return parts == null
        ? text
        : text.substring(parts.dateStart())
            + text.substring(parts.timeEnd(), parts.dateStart())
            + text.substring(0, parts.timeEnd());
  }

  @Test
  public void dateOffNeverInventsDate() {
    assertNull(ClockDateOrder.parts("17:41", "17:41", "午後5:41", "10/17"));
    assertEquals("17:41", move("17:41", "17:41", "午後5:41", "10/17"));
  }

  @Test
  public void preservesNativeDateAndSpacing() {
    assertEquals("10/17  17:41", move("17:41  10/17", "17:41", "午後5:41", "10/17"));
    assertEquals("10月17日(土)   17:41", move("17:41   10月17日(土)", "17:41", "午後5:41", "10月17日(土)"));
  }

  @Test
  public void amPmAndAccessibilityUseExactNativeTime() {
    assertEquals(
        "Sat, Oct 17  5:41 PM", move("5:41 PM  Sat, Oct 17", "5:41", "5:41 PM", "Sat, Oct 17"));
    assertEquals("토요일  17:41:32", move("17:41:32  토요일", "17:41:32", "오후5:41:32", "토요일"));
  }

  @Test
  public void unknownAlreadyReorderedAndIncompleteFormatsStayNative() {
    for (String text :
        new String[] {"10/17  17:41", "AiNudge 17:41  10/17", "17:41 — 10/17", "17:41  ", "10/17"})
      assertNull(ClockDateOrder.parts(text, "17:41", "午後5:41", "10/17"));
    assertNull(ClockDateOrder.parts("17:41  ", "17:41", "午後5:41", ""));
    assertNull(ClockDateOrder.parts(null, "17:41", "午後5:41", "10/17"));
    assertNull(ClockDateOrder.parts("  10/17", "", null, "10/17"));
  }
}
