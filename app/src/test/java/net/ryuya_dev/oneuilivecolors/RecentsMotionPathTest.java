package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecentsMotionPathTest {
  private static final double EPS = 1e-7;
  private final RecentsMotionPath.Pose start =
      new RecentsMotionPath.Pose(720, 1560, 1, -Math.PI / 2);
  private final RecentsMotionPath.Pose end = new RecentsMotionPath.Pose(720, 1300, .42, 0);
  private final RecentsMotionPath path = new RecentsMotionPath(start, end);

  @Test
  public void endpointsMatchRealPoses() {
    assertPose(start, path.at(0));
    assertPose(end, path.at(1));
    assertPose(start, path.at(-.1));
    assertPose(end, path.at(1.1));
  }

  @Test
  public void cancelRetracesRatherThanStartingAnotherAnimator() {
    for (double p : new double[] {0, .1, .4, .8, .4, .1, 0}) {
      assertPose(path.at(p), new RecentsMotionPath(start, end).at(p));
    }
    assertPose(start, path.at(0));
  }

  @Test
  public void noSingularMatrixDuringQuarterTurn() {
    for (int i = 0; i <= 1000; i++) {
      var pose = path.at(i / 1000.0);
      var matrix = pose.matrix(1560, 720);
      assertEquals(
          pose.scale() * pose.scale(), matrix.a() * matrix.d() - matrix.b() * matrix.c(), EPS);
      assertEquals(pose.x(), matrix.map(1560, 720).x(), EPS);
      assertEquals(pose.y(), matrix.map(1560, 720).y(), EPS);
      assertPose(pose, RecentsMotionPath.Pose.from(matrix, 1560, 720));
    }
  }

  @Test
  public void reprojectionAcrossAllDisplayRotationsKeepsSamePhysicalPoint() {
    for (int rotation = 0; rotation < 4; rotation++) {
      var logical = RecentsMotionPath.Transform.display(rotation, 1440, 3120);
      // logical -> portrait is the current leash basis in this synthetic test.
      var surface = path.surfaceAt(.4, 1560, 720, logical.inverse());
      var portrait = surface.then(logical.inverse());
      var expected = path.at(.4).matrix(1560, 720);
      for (double[] point : new double[][] {{0, 0}, {3120, 0}, {0, 1440}, {3120, 1440}}) {
        assertEquals(
            expected.map(point[0], point[1]).x(), portrait.map(point[0], point[1]).x(), EPS);
        assertEquals(
            expected.map(point[0], point[1]).y(), portrait.map(point[0], point[1]).y(), EPS);
      }
    }
  }

  @Test
  public void angleUsesShortRouteAcrossWrap() {
    var wrap =
        new RecentsMotionPath(
            new RecentsMotionPath.Pose(0, 0, 1, Math.toRadians(179)),
            new RecentsMotionPath.Pose(0, 0, 1, Math.toRadians(-179)));
    assertEquals(Math.PI, wrap.at(.5).angle(), EPS);
  }

  @Test
  public void transformInverseRoundTrip() {
    var matrix = path.at(.73).matrix(31, 47);
    var point = matrix.then(matrix.inverse()).map(123, 456);
    assertEquals(123, point.x(), EPS);
    assertEquals(456, point.y(), EPS);
  }

  @Test
  public void quarterTurnMapsActualDisplayCorners() {
    var landscape = RecentsMotionPath.Transform.display(1, 1440, 3120);
    assertEquals(new RecentsMotionPath.Point(0, 1440), landscape.map(0, 0));
    assertEquals(new RecentsMotionPath.Point(3120, 0), landscape.map(1440, 3120));
    var reverse = RecentsMotionPath.Transform.display(3, 1440, 3120);
    assertEquals(new RecentsMotionPath.Point(3120, 0), reverse.map(0, 0));
  }

  @Test
  public void adjacentFractionsAreContinuous() {
    for (int i = 0; i < 100; i++) {
      var a = path.at(i / 100.0);
      var b = path.at(i / 100.0 + 1e-8);
      assertEquals(a.x(), b.x(), 1e-4);
      assertEquals(a.y(), b.y(), 1e-4);
      assertEquals(a.scale(), b.scale(), 1e-4);
      assertEquals(a.angle(), b.angle(), 1e-4);
    }
  }

  @Test
  public void rejectsUnverifiedOrInvalidGeometry() {
    assertThrows(IllegalArgumentException.class, () -> path.at(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> new RecentsMotionPath.Pose(0, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new RecentsMotionPath.Transform(0, 0, 0, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecentsMotionPath.Pose.from(new RecentsMotionPath.Transform(2, 0, 0, 1, 0, 0), 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecentsMotionPath.Pose.from(new RecentsMotionPath.Transform(-1, 0, 0, 1, 0, 0), 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> RecentsMotionPath.Transform.display(4, 1, 1));
  }

  private static void assertPose(RecentsMotionPath.Pose expected, RecentsMotionPath.Pose actual) {
    assertEquals(expected.x(), actual.x(), EPS);
    assertEquals(expected.y(), actual.y(), EPS);
    assertEquals(expected.scale(), actual.scale(), EPS);
    assertEquals(expected.angle(), actual.angle(), EPS);
  }
}
