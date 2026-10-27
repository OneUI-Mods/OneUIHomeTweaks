package dev.ryuya.oneuihometweaks;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class DiyGridGeometryTest {
  @Test
  public void portraitHomeAcceptsItsMeasuredNativeGrid() {
    assertTrue(new DiyGridGeometry(7, 7, 190, 339, 0, 0, false).fits(1330, 2375));
  }

  @Test
  public void observedWideCellsCannotRearrangePortraitIcons() {
    var source =
        List.of(
            new GridSnapPlan.Item(1, 1149, 2077, 1, 1, 221, -71),
            new GridSnapPlan.Item(2, 1149, 2077, 1, 1, 221, -71),
            new GridSnapPlan.Item(3, 1149, 2077, 1, 1, 221, -71));
    var unchecked = GridSnapPlan.build(7, 7, 394, 117, source, Map.of());
    assertTrue(unchecked.values().stream().anyMatch(slot -> slot.x() > 1330));
    assertFalse(new DiyGridGeometry(7, 7, 394, 117, 0, 0, false).fits(1330, 2375));
    assertFalse(new DiyGridGeometry(7, 7, 394, 117, 110, -21, false).fits(1330, 2375));
  }

  @Test
  public void geometryFromLandscapeIsNeverUsedForPortraitProjection() {
    assertFalse(new DiyGridGeometry(7, 7, 190, 339, 0, 0, true).fits(1330, 2375));
    assertFalse(new DiyGridGeometry(7, 7, 394, 117, 0, 0, false).fits(2758, 819));
  }

  @Test
  public void nativeMarginDifferencesAndRoundingAreAllowed() {
    assertTrue(new DiyGridGeometry(7, 7, 200, 339, -35, 0, false).fits(1330, 2375));
    assertTrue(new DiyGridGeometry(7, 7, 191, 340, 0, 0, false).fits(1330, 2375));
    assertFalse(new DiyGridGeometry(7, 7, 300, 339, -35, 0, false).fits(1330, 2375));
  }

  @Test
  public void viewportChangesAreCheckedAgainWithoutCachingTheOldAcceptance() {
    var grid = new DiyGridGeometry(7, 7, 190, 339, 0, 0, false);
    assertTrue(grid.fits(1330, 2375));
    assertFalse(grid.fits(1000, 2375));
    assertFalse(grid.fits(1330, 900));
    assertTrue(grid.fits(1330, 2375));
  }

  @Test
  public void customGridCountsUseTheirOwnDimensions() {
    assertTrue(new DiyGridGeometry(5, 8, 288, 280, 0, 0, false).fits(1440, 2240));
  }

  @Test
  public void unmeasuredOrInvalidGeometryIsNotProjected() {
    assertFalse(new DiyGridGeometry(7, 7, 190, 339, 0, 0, false).fits(0, 2375));
    assertFalse(new DiyGridGeometry(7, 7, 190, 339, 0, 0, false).fits(1330, 0));
    assertFalse(new DiyGridGeometry(0, 7, 190, 339, 0, 0, false).fits(1330, 2375));
    assertFalse(new DiyGridGeometry(7, 7, -1, 339, 0, 0, false).fits(1330, 2375));
  }

  @Test
  public void extremeDimensionsAndOffsetsCannotOverflowIntoAcceptance() {
    assertFalse(
        new DiyGridGeometry(20, 20, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0, false)
            .fits(1330, 2375));
    assertFalse(
        new DiyGridGeometry(7, 7, 190, 339, Integer.MIN_VALUE, Integer.MIN_VALUE, false)
            .fits(1330, 2375));
  }
}
