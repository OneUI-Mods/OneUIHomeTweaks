package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecentsCardSpaceTest {
  @Test
  public void portraitDestinationIsRotatedIntoLandscapeWithoutStretching() {
    var p = new RecentsCardSpace.Space(1440, 3120, 0);
    var l = new RecentsCardSpace.Space(3120, 1440, 1);
    var t = RecentsCardSpace.between(p, l);
    // Measured portrait card size: its long side becomes landscape width, not scaleY.
    var a = t.map(323, 354);
    var b = t.map(323 + 793, 354 + 1969);
    assertEquals(1969, Math.abs(b.x() - a.x()), 1e-8);
    assertEquals(793, Math.abs(b.y() - a.y()), 1e-8);
    assertEquals(1, Math.hypot(t.a(), t.b()), 1e-8);
    assertEquals(1, Math.hypot(t.c(), t.d()), 1e-8);
  }

  @Test
  public void allWindowRotationsRoundTripAndPreserveDistances() {
    for (int r = 0; r < 4; r++)
      for (int s = 0; s < 4; s++) {
        var from =
            new RecentsCardSpace.Space(r % 2 == 0 ? 1440 : 3120, r % 2 == 0 ? 3120 : 1440, r);
        var to = new RecentsCardSpace.Space(s % 2 == 0 ? 1440 : 3120, s % 2 == 0 ? 3120 : 1440, s);
        var f = RecentsCardSpace.between(from, to);
        var back = RecentsCardSpace.between(to, from);
        var a = f.map(71, 302);
        var b = f.map(171, 302);
        assertEquals(100, Math.hypot(a.x() - b.x(), a.y() - b.y()), 1e-8);
        var q = back.map(a.x(), a.y());
        assertEquals(71, q.x(), 1e-8);
        assertEquals(302, q.y(), 1e-8);
      }
  }

  @Test
  public void sameSpaceIsIdentityAndCannotApplyASecondQuarterTurn() {
    var s = new RecentsCardSpace.Space(3120, 1440, 1);
    var t = RecentsCardSpace.between(s, s);
    assertEquals(new RecentsAffine.Point(971, 249), t.map(971, 249));
  }

  @Test
  public void staleDimensionsAndOtherDisplaysCannotBeScaledToFit() {
    var p = new RecentsCardSpace.Space(1440, 3120, 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> RecentsCardSpace.between(p, new RecentsCardSpace.Space(3120, 1440, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecentsCardSpace.between(p, new RecentsCardSpace.Space(1, 1, 1)));
    assertThrows(IllegalArgumentException.class, () -> new RecentsCardSpace.Space(0, 3120, 0));
  }
}
