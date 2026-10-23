package dev.ryuya.oneuihometweaks;

import static org.junit.Assert.*;

import org.junit.Test;

public class StartupPolicyTest {
  @Test
  public void threeConfirmedEarlyCrashesBlockNextStart() {
    int n = 0;
    n = StartupPolicy.nextCount(n, false, 500, 1000, StartupPolicy.Exit.CRASH);
    assertEquals(1, n);
    n = StartupPolicy.nextCount(n, false, 1000, 2000, StartupPolicy.Exit.CRASH);
    assertEquals(2, n);
    assertEquals(3, StartupPolicy.nextCount(n, false, 2000, 3000, StartupPolicy.Exit.CRASH));
  }

  @Test
  public void intentionalRestartsDoNotAccumulate() {
    assertEquals(0, StartupPolicy.nextCount(2, false, 1000, 2000, StartupPolicy.Exit.INTENTIONAL));
  }

  @Test
  public void stableAndWidelySpacedRunsReset() {
    assertEquals(0, StartupPolicy.nextCount(2, true, 1000, 2000, StartupPolicy.Exit.UNKNOWN));
    assertEquals(0, StartupPolicy.nextCount(2, false, 1000, 602000, StartupPolicy.Exit.CRASH));
  }

  @Test
  public void NewBootResetsMonotonicHistory() {
    assertEquals(0, StartupPolicy.nextCount(2, false, 1000000, 2000, StartupPolicy.Exit.UNKNOWN));
  }

  @Test
  public void unknownExitDoesNotCreateFalseCrashLoop() {
    assertEquals(0, StartupPolicy.nextCount(2, false, 1000, 2000, StartupPolicy.Exit.UNKNOWN));
    assertEquals(
        3, StartupPolicy.nextCount(Integer.MAX_VALUE, false, 1000, 2000, StartupPolicy.Exit.CRASH));
  }
}
