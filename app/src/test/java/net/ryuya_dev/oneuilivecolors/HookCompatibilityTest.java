package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import org.junit.Test;

public class HookCompatibilityTest {
  @Test
  public void knownAndroid16StillUsesItsOriginalProfile() {
    assertEquals(HookCompatibility.Platform.ANDROID_16, HookCompatibility.platform(36));
    assertTrue(HookCompatibility.supports(36, "samsung", "SM-S948Q", "m3q", Android16Hooks.BUILD));
  }

  @Test
  public void android17DoesNotReuseTheAndroid16Allowlist() {
    assertEquals(HookCompatibility.Platform.ANDROID_17, HookCompatibility.platform(37));
    assertFalse(HookCompatibility.supports(37, "samsung", "SM-S948Q", "m3q", Android16Hooks.BUILD));
    assertFalse(
        HookCompatibility.supports(
            37, "samsung", "SM-S948Q", "m3q", Android17Hooks.CANDIDATE_FIRMWARE));
  }

  @Test
  public void futureAndOlderVersionsAreNotImplicitlyEnabled() {
    for (int sdk : new int[] {35, 38, 100}) {
      assertEquals(HookCompatibility.Platform.UNSUPPORTED, HookCompatibility.platform(sdk));
      assertFalse(
          HookCompatibility.supports(sdk, "samsung", "SM-S948Q", "m3q", Android16Hooks.BUILD));
    }
  }

  @Test
  public void recentsFocusRetainsItsExactAndroid16Fingerprint() {
    assertTrue(HookCompatibility.supportsRecentsFocus(36, Android16Hooks.FINGERPRINT));
    assertFalse(HookCompatibility.supportsRecentsFocus(36, "other-firmware"));
    assertFalse(HookCompatibility.supportsRecentsFocus(37, Android16Hooks.FINGERPRINT));
  }
}
