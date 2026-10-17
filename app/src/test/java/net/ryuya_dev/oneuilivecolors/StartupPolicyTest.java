package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import org.junit.Test;

public class StartupPolicyTest {
  @Test
  public void repeatedEarlyCrashesStopThirdStart() {
    int n = 1;
    n = StartupPolicy.nextCount(n, false, 1000, 2000, StartupPolicy.Exit.CRASH);
    assertEquals(2, n);
    assertEquals(3, StartupPolicy.nextCount(n, false, 2000, 3000, StartupPolicy.Exit.CRASH));
  }

  @Test
  public void intentionalRestartsDoNotAccumulate() {
    assertEquals(1, StartupPolicy.nextCount(2, false, 1000, 2000, StartupPolicy.Exit.INTENTIONAL));
  }

  @Test
  public void stableAndWidelySpacedRunsReset() {
    assertEquals(1, StartupPolicy.nextCount(2, true, 1000, 2000, StartupPolicy.Exit.UNKNOWN));
    assertEquals(1, StartupPolicy.nextCount(2, false, 1000, 602000, StartupPolicy.Exit.CRASH));
  }

  @Test
  public void NewBootResetsMonotonicHistory() {
    assertEquals(1, StartupPolicy.nextCount(2, false, 1000000, 2000, StartupPolicy.Exit.UNKNOWN));
  }

  @Test
  public void unknownExitDoesNotCreateFalseCrashLoop() {
    assertEquals(1, StartupPolicy.nextCount(2, false, 1000, 2000, StartupPolicy.Exit.UNKNOWN));
    assertEquals(
        3, StartupPolicy.nextCount(Integer.MAX_VALUE, false, 1000, 2000, StartupPolicy.Exit.CRASH));
  }
}
