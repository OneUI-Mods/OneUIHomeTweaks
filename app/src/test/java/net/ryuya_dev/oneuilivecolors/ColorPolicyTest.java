package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ColorPolicyTest {
  @Test
  public void hintsAreIndependentOfSeedColorAndTheme() {
    assertEquals(Integer.valueOf(1), ColorPolicy.darkValue(0, true, 1));
    assertEquals(Integer.valueOf(0), ColorPolicy.darkValue(0, true, 2));
    assertEquals(Integer.valueOf(1), ColorPolicy.darkValue(0, true, 3));
    assertEquals(Integer.valueOf(0), ColorPolicy.darkValue(0, true, 0));
  }

  @Test
  public void noColorsDoesNotInventAutomaticResult() {
    assertNull(ColorPolicy.darkValue(0, true, null));
    assertEquals(Integer.valueOf(1), ColorPolicy.darkValue(1, true, null));
    assertEquals(Integer.valueOf(0), ColorPolicy.darkValue(2, true, null));
  }

  @Test
  public void staticWallpaperNeverOverridden() {
    for (int mode = 0; mode < 4; mode++) assertNull(ColorPolicy.darkValue(mode, false, 1));
    assertNull(ColorPolicy.darkValue(3, true, 1));
    assertNull(ColorPolicy.darkValue(98, true, 1));
  }

  @Test
  public void scopeRejectsLockDexCoverVirtualEvents() {
    assertTrue(ColorPolicy.isMainHomeEvent(1));
    assertTrue(ColorPolicy.isMainHomeEvent(5));
    assertTrue(ColorPolicy.isMainHomeEvent(7));
    for (int which : new int[] {0, 2, 6, 9, 11, 17, 33})
      assertFalse(ColorPolicy.isMainHomeEvent(which));
  }
}
