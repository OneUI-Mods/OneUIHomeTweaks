package dev.ryuya.oneuihometweaks;

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
    assertTrue(HookCompatibility.supports(37, "samsung", "SM-S948Q", "m3q", Android17Hooks.BUILD));
  }

  @Test
  public void android17AdmissionRejectsOtherHardwareAndBuilds() {
    assertFalse(HookCompatibility.supports(37, "other", "SM-S948Q", "m3q", Android17Hooks.BUILD));
    assertFalse(HookCompatibility.supports(37, "samsung", "other", "m3q", Android17Hooks.BUILD));
    assertFalse(
        HookCompatibility.supports(37, "samsung", "SM-S948Q", "other", Android17Hooks.BUILD));
    assertFalse(HookCompatibility.supports(37, "samsung", "SM-S948Q", "m3q", "other-firmware"));
    // Android 16 deliberately retains its original SDK-only admission rule.
    assertTrue(HookCompatibility.supports(36, "samsung", "SM-S948Q", "m3q", Android17Hooks.BUILD));
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
    assertTrue(HookCompatibility.supportsRecentsFocus(37, Android17Hooks.FINGERPRINT));
    assertFalse(HookCompatibility.supportsRecentsFocus(36, Android17Hooks.FINGERPRINT));
    assertFalse(HookCompatibility.supportsRecentsFocus(37, "other-firmware"));
  }

  @Test
  public void launcherArtifactsNeverCrossMatchVersions() {
    assertTrue(HookCompatibility.matchesLauncherVersion(36, 1750305027L));
    assertTrue(HookCompatibility.matchesLauncherVersion(37, 1800705053L));
    assertFalse(HookCompatibility.matchesLauncherVersion(36, 1800705053L));
    assertFalse(HookCompatibility.matchesLauncherVersion(37, 1750305027L));
    assertFalse(HookCompatibility.matchesLauncherVersion(38, 1800705053L));
  }

  @Test
  public void taskSceneRelocationIsVersionSpecific() {
    assertEquals(
        "com.honeyspace.ui.common.taskScene.TaskSceneView", HookCompatibility.taskSceneView(36));
    assertEquals(
        "com.honeyspace.common.taskscene.TaskSceneView", HookCompatibility.taskSceneView(37));
    assertEquals(
        "com.honeyspace.common.taskscene.TaskSceneData", HookCompatibility.taskSceneData(37));
  }

  @Test
  public void clockBuildsRemainSeparated() {
    assertTrue(HookCompatibility.matchesClockBuild(36, Android16Hooks.BUILD));
    assertTrue(HookCompatibility.matchesClockBuild(37, Android17Hooks.BUILD));
    assertFalse(HookCompatibility.matchesClockBuild(37, Android16Hooks.BUILD));
  }

  @Test
  public void recentsCallsRetainAndroid16ArityAndUseAndroid17DisplayAwareLookup() {
    var old = HookCompatibility.recentsCalls(36);
    assertEquals("getTopTask", old.topTaskMethod());
    assertFalse(old.topTaskTakesDisplay());
    assertFalse(old.finishTakesToHome());
    var current = HookCompatibility.recentsCalls(37);
    assertEquals("runningTaskForDisplayId", current.topTaskMethod());
    assertTrue(current.topTaskTakesDisplay());
    assertTrue(current.finishTakesToHome());
  }
}
