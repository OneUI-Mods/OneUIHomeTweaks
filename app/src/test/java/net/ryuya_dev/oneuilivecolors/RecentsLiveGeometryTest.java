package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecentsLiveGeometryTest {
  private static final float[] ID = {1, 0, 0, 0, 1, 0, 0, 0, 1};

  @Test
  public void reducedSnapshotIsScaledExactlyOnceBeforeItsShader() {
    var m =
        RecentsLiveGeometry.compose(
            1440,
            3120,
            720,
            1560,
            .5,
            new float[] {1.2f, 0, -10, 0, 1.2f, -20, 0, 0, 1},
            new float[] {1, 0, 265, 0, 1, 434, 0, 0, 1});
    var p = m.map(720, 1560);
    assertEquals(687, p.x(), .001);
    assertEquals(1350, p.y(), .001);
  }

  @Test
  public void nativeRoundingAndHomeUpShearAreRetained() {
    var m =
        RecentsLiveGeometry.compose(
            1440,
            3120,
            1440,
            3120,
            1,
            new float[] {.6637071f, 0, 0, 0, .66288596f, -90, 0, 0, 1},
            new float[] {1, .07f, 265, 0, 1, 434, 0, 0, 1});
    assertEquals(.6637071, m.a(), 1e-6);
    assertEquals(.66288596, m.d(), 1e-6);
    assertEquals(.07 * .66288596, m.c(), 1e-6);
    assertEquals(258.7, m.tx(), 1e-4);
  }

  @Test
  public void portraitCardCanShowLandscapeSnapshotWithoutStretchingIt() {
    var m =
        RecentsLiveGeometry.compose(
            3120,
            1440,
            3120,
            1440,
            1,
            new float[] {0, -.6955f, 970.9f, .6946f, 0, -97.2f, 0, 0, 1},
            ID);
    var a = m.map(0, 0);
    var b = m.map(3120, 0);
    assertEquals(a.x(), b.x(), 1e-6);
    assertEquals(3120 * .6946, b.y() - a.y(), .001);
  }

  @Test
  public void settledLandscapeHomeUpResidualStaysWithinHalfAPixelEverywhere() {
    float[] sh = {
      .59344506f, 0, -83.08231f, 1.8505962e-5f, .5937261f, -52.84421f, 4.7708575e-8f, 0, .9999933f
    };
    float[] gl = {.9999263f, 0, 768.0583f, 0, .9999263f, 291.02454f, 0, 0, 1};
    var m =
        RecentsLiveGeometry.compose(
            new RecentsCardSpace.Space(3120, 1440, 1), 3120, 1440, 1, 1, sh, gl);
    for (int x = 0; x <= 3120; x += 120)
      for (int y = 0; y <= 1440; y += 120) {
        double w = sh[6] * x + sh[7] * y + sh[8];
        double sx = (sh[0] * x + sh[1] * y + sh[2]) / w;
        double sy = (sh[3] * x + sh[4] * y + sh[5]) / w;
        var p = m.map(x, y);
        assertTrue(Math.hypot(p.x() - (gl[0] * sx + gl[2]), p.y() - (gl[4] * sy + gl[5])) < .5);
      }
  }

  @Test
  public void realPerspectiveAndDenominatorCrossingStillUseSnapshots() {
    for (float p : new float[] {.00001f, -.001f})
      assertThrows(
          RecentsLiveGeometry.PerspectiveCard.class,
          () ->
              RecentsLiveGeometry.compose(
                  3120, 1440, 3120, 1440, 1, new float[] {1, 0, 0, 0, 1, 0, p, 0, 1}, ID));
  }

  @Test
  public void croppedOrStaleSnapshotIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RecentsLiveGeometry.compose(1440, 3120, 1440, 2800, 1, ID, ID));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecentsLiveGeometry.compose(
                1440, 3120, 1440, 3120, 1, new float[] {1, 0, 0, 0, 1, 0, .01f, 0, 1}, ID));
  }
}
