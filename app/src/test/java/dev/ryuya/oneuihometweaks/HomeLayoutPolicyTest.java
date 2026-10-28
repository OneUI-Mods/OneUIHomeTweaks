package dev.ryuya.oneuihometweaks;

import static org.junit.Assert.*;

import org.junit.Test;

public final class HomeLayoutPolicyTest {
  @Test
  public void rejectsObservedPortraitWithLandscapeCameraHole() {
    assertFalse(HomeLayoutPolicy.consistent(1440, 3120, 139, 0, 0, 0));
    assertFalse(HomeLayoutPolicy.consistent(1440, 3120, 0, 0, 139, 0));
    assertTrue(HomeLayoutPolicy.consistent(1440, 3120, 0, 139, 0, 0));
  }

  @Test
  public void acceptsAllFourSettledRotations() {
    assertTrue(HomeLayoutPolicy.consistent(1440, 3120, 0, 139, 0, 0));
    assertTrue(HomeLayoutPolicy.consistent(1440, 3120, 0, 0, 0, 139));
    assertTrue(HomeLayoutPolicy.consistent(3120, 1440, 139, 0, 0, 0));
    assertTrue(HomeLayoutPolicy.consistent(3120, 1440, 0, 0, 139, 0));
    assertFalse(HomeLayoutPolicy.consistent(3120, 1440, 0, 139, 0, 0));
  }

  @Test
  public void rejectsMixedWindowMetricsEvenWithPlausibleCutout() {
    assertFalse(HomeLayoutPolicy.sameBounds(1440, 3120, 3120, 1440));
    assertFalse(HomeLayoutPolicy.sameBounds(1440, 3120, 1301, 3120));
    assertTrue(HomeLayoutPolicy.sameBounds(1440, 3120, 1440, 3120));
    assertFalse(HomeLayoutPolicy.consistent(0, 0, 0, 0, 0, 0));
  }
}
