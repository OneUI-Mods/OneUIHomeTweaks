package dev.ryuya.oneuihometweaks;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecentsLeashSpaceTest {
  @Test
  public void normalizedPortraitLeashIsResolvedFromIdentityInsteadOfStaleAppRotation() {
    var p = new RecentsCardSpace.Space(1440, 3120, 0);
    assertEquals(
        p, RecentsLeashSpace.resolve(p, 1440, 3120, new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1}));
  }

  @Test
  public void nativeLandscapeQuarterTurnMatchesMeasuredSource() {
    var p = new RecentsCardSpace.Space(1440, 3120, 0);
    assertEquals(
        new RecentsCardSpace.Space(3120, 1440, 1),
        RecentsLeashSpace.resolve(p, 3120, 1440, new float[] {0, 1, 0, -1, 0, 1440, 0, 0, 1}));
  }

  @Test
  public void zoomedOrUnmeasuredSourceIsRejected() {
    var p = new RecentsCardSpace.Space(1440, 3120, 0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecentsLeashSpace.resolve(p, 1440, 3120, new float[] {.9f, 0, 0, 0, .9f, 0, 0, 0, 1}));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecentsLeashSpace.resolve(p, 720, 1560, new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1}));
  }

  @Test
  public void portraitNormalizedAppCanMatchALandscapeSnapshot() {
    var p = new RecentsCardSpace.Space(1440, 3120, 0);
    var t =
        RecentsLiveGeometry.compose(
            p,
            3120,
            1440,
            1,
            1,
            new float[] {0, -.694f, 1000, .694f, 0, -100, 0, 0, 1},
            new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1});
    assertEquals(.694, t.a(), 1e-6);
    assertEquals(.694, t.d(), 1e-6);
    assertEquals(0, t.b(), 0);
    assertEquals(0, t.c(), 0);
    assertEquals(.64, t.tx(), .001);
  }
}
