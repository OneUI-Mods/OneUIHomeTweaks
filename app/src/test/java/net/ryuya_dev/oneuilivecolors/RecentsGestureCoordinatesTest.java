package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecentsGestureCoordinatesTest {
  private static final double EPS = 1e-7;

  @Test
  public void eventMatrixMatchesPointerAndHistoricalSampleProjection() {
    for (var region : RecentsGestureCoordinates.Region.values())
      for (int initial : new int[] {1, 3}) {
        var c = new RecentsGestureCoordinates(initial, 3120, 1440, 1560, 1434, region);
        for (int rotation = 0; rotation < 4; rotation++)
          for (double[] point : new double[][] {{100, 200}, {720, 1000}, {900, 1200}}) {
            var projected = c.pointerTransform(rotation).map(point[0], point[1]);
            var expected = c.pointer(rotation, point[0], point[1]).virtualPointer();
            assertEquals(expected.x(), projected.x(), EPS);
            assertEquals(expected.y(), projected.y(), EPS);
          }
      }
  }

  @Test
  public void leftAndRightRegionsPreserveNativeHorizontalPointerDirection() {
    var left =
        new RecentsGestureCoordinates(
            1, 3120, 1440, 10, 720, RecentsGestureCoordinates.Region.LEFT);
    var right =
        new RecentsGestureCoordinates(
            1, 3120, 1440, 10, 720, RecentsGestureCoordinates.Region.RIGHT);
    assertTrue(
        left.pointer(1, 400, 740).virtualPointer().x()
            > left.pointer(1, 400, 720).virtualPointer().x());
    assertTrue(
        right.pointer(1, 400, 740).virtualPointer().x()
            < right.pointer(1, 400, 720).virtualPointer().x());
  }

  @Test
  public void measuredDragSurvivesDisplayRotationWithoutChangingFraction() {
    var coordinates =
        new RecentsGestureCoordinates(
            1, 3120, 1440, 1560, 1434, RecentsGestureCoordinates.Region.BOTTOM);
    var before = coordinates.pointer(1, 1560, 828.2666);
    // Same point on the glass, now expressed by a portrait InputMonitor.
    var after = coordinates.pointer(0, 611.7334, 1560);
    assertEquals(before, after);
    assertEquals(0, after.displacement().x(), EPS);
    assertEquals(-605.7334 * 3120 / 1440, after.displacement().y(), EPS);
    assertEquals(720, after.virtualPointer().x(), EPS);
    assertEquals(3120 + after.displacement().y(), after.virtualPointer().y(), EPS);
  }

  @Test
  public void reverseLandscapeAlsoPreservesThePhysicalPoint() {
    var c =
        new RecentsGestureCoordinates(
            3, 3120, 1440, 1560, 1434, RecentsGestureCoordinates.Region.BOTTOM);
    assertEquals(c.pointer(3, 1560, 900), c.pointer(0, 900, 1560));
  }

  @Test
  public void gestureReversalRetracesAcrossMultipleConfigurationChanges() {
    var c =
        new RecentsGestureCoordinates(
            1, 3120, 1440, 1560, 1434, RecentsGestureCoordinates.Region.BOTTOM);
    for (int i = 0; i <= 200; i++) {
      double y = 1434 - Math.min(i, 200 - i) * 4.0;
      var landscape = c.pointer(1, 1560, y);
      var portrait = c.pointer(0, 1440 - y, 1560);
      assertEquals(landscape, portrait);
    }
    assertEquals(0, c.pointer(0, 6, 1560).displacement().y(), EPS);
  }

  @Test
  public void horizontalDriftUsesCapturedAxesAfterRotation() {
    var c =
        new RecentsGestureCoordinates(
            1, 3120, 1440, 1560, 1434, RecentsGestureCoordinates.Region.BOTTOM);
    var before = c.pointer(1, 1700, 900);
    var after = c.pointer(0, 540, 1700);
    assertEquals(before, after);
    assertEquals(140 * 1440.0 / 3120, after.displacement().x(), EPS);
  }

  @Test
  public void nativeSideRegionsUseTheirVerifiedAxisConvention() {
    for (var r :
        new RecentsGestureCoordinates.Region[] {
          RecentsGestureCoordinates.Region.LEFT, RecentsGestureCoordinates.Region.RIGHT
        }) {
      var c = new RecentsGestureCoordinates(1, 3120, 1440, 10, 720, r);
      var p = c.pointer(1, 400, 740);
      assertEquals(-20 * 1440.0 / 3120, p.displacement().x(), EPS);
      assertEquals(390 * 3120.0 / 1440, p.displacement().y(), EPS);
    }
  }

  @Test
  public void invalidStartOrRotationCannotProduceInputCoordinates() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecentsGestureCoordinates(
                0, 3120, 1440, 0, 0, RecentsGestureCoordinates.Region.BOTTOM));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecentsGestureCoordinates(
                1, 1440, 3120, 0, 0, RecentsGestureCoordinates.Region.BOTTOM));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecentsGestureCoordinates(
                1, 3120, 1440, Double.NaN, 0, RecentsGestureCoordinates.Region.BOTTOM));
    var c =
        new RecentsGestureCoordinates(1, 3120, 1440, 0, 0, RecentsGestureCoordinates.Region.BOTTOM);
    assertThrows(IllegalArgumentException.class, () -> c.pointer(4, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> c.pointer(0, Double.NaN, 0));
  }
}
