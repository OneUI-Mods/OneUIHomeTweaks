package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import org.junit.Test;

public class StatusFreshnessTest {
  @Test
  public void previousBootNeverLooksActiveOrCrashBlocked() {
    for (String state : new String[] {"ACTIVE", "CRASH_GUARD", "ERROR", "UNSUPPORTED"})
      assertFalse(StatusFreshness.usable(state, 202, 203, 100, 1000, 50));
  }

  @Test
  public void liveStatusRequiresResponseToCurrentRequest() {
    assertFalse(StatusFreshness.usable("ACTIVE", 203, 203, 100, 1000, 150));
    assertTrue(StatusFreshness.usable("ACTIVE", 203, 203, 160, 1000, 150));
    assertFalse(StatusFreshness.usable("OFF", 203, 203, 100, 1000, 150));
  }

  @Test
  public void sameBootGuardRemainsVisibleWithoutLiveObservers() {
    assertTrue(StatusFreshness.usable("CRASH_GUARD", 203, 203, 100, 1000, 150));
  }

  @Test
  public void legacyLiveHooksCanStillRespondAfterModuleUiUpdate() {
    assertTrue(StatusFreshness.usable("ACTIVE", -1, 203, 160, 1000, 150));
    assertFalse(StatusFreshness.usable("CRASH_GUARD", -1, 203, 160, 1000, 150));
  }

  @Test
  public void absentOrFutureTimestampsAreNotEvidence() {
    assertFalse(StatusFreshness.usable("ACTIVE", 203, 203, 0, 1000, 0));
    assertFalse(StatusFreshness.usable("ERROR", 203, 203, 2000, 1000, 0));
  }
}
