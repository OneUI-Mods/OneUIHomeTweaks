package dev.ryuya.oneuihometweaks;

import static org.junit.Assert.*;

import org.junit.Test;

public class NavigationPolicyTest {
  @Test
  public void buttonsAndUnknownModesNeverHidden() {
    for (int mode : new int[] {-1, 0, 1, 4, 99})
      assertFalse(NavigationPolicy.hide(true, mode, 1, true));
  }

  @Test
  public void bothSamsungGestureModesSupported() {
    assertTrue(NavigationPolicy.hide(true, 2, 1, true));
    assertTrue(NavigationPolicy.hide(true, 3, 1, true));
  }

  @Test
  public void globalHiddenOrUnknownHintIsNeverOverridden() {
    for (int mode : new int[] {2, 3}) {
      assertFalse(NavigationPolicy.hide(true, mode, 0, true));
      assertFalse(NavigationPolicy.hide(true, mode, -1, true));
      assertFalse(NavigationPolicy.restoreVisible(true, mode, 0));
    }
  }

  @Test
  public void disabledAndNonFullscreenNeverHide() {
    assertFalse(NavigationPolicy.hide(false, 2, 1, true));
    assertFalse(NavigationPolicy.hide(true, 2, 1, false));
  }

  @Test
  public void restorationKeepsNativeHiddenAndAllowsButtonControls() {
    for (int mode : new int[] {0, 1, 2, 3})
      assertFalse(NavigationPolicy.restoreVisible(false, mode, 1));
    assertTrue(NavigationPolicy.restoreVisible(true, 0, 0));
    assertTrue(NavigationPolicy.restoreVisible(true, 1, 0));
  }
}
