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
