package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecentsChaseTest {
  @Test
  public void changingDestinationDoesNotJumpPositionOrVelocity() {
    var spring = new RecentsChase(36, new double[] {720, 1560}, new double[] {50, -200});
    spring.target(new double[] {1200, 1200});
    spring.advance(.1);
    var p = spring.position();
    var v = spring.velocity();
    spring.target(new double[] {300, 900});
    assertArrayEquals(p, spring.position(), 0);
    assertArrayEquals(v, spring.velocity(), 0);
    spring.advance(.000001);
    assertEquals(p[0] + v[0] * .000001, spring.position()[0], .0001);
  }

  @Test
  public void frameSubdivisionDoesNotChangeTheTrajectory() {
    var a = new RecentsChase(36, new double[] {900, 1}, new double[] {-40, 0});
    var b = new RecentsChase(36, new double[] {900, 1}, new double[] {-40, 0});
    a.target(new double[] {0, .5});
    b.target(new double[] {0, .5});
    a.advance(.3);
    for (int i = 0; i < 36; i++) b.advance(1.0 / 120);
    assertArrayEquals(a.position(), b.position(), 1e-10);
    assertArrayEquals(a.velocity(), b.velocity(), 1e-10);
  }

  @Test
  public void nativeSizedTravelSettlesWithoutSingularity() {
    var s =
        new RecentsChase(36, new double[] {720, 1560, 0, Math.PI / 2}, new double[] {0, 0, 0, 0});
    s.target(new double[] {1680, 1500, Math.log(.694), Math.PI / 2});
    s.advance(.4);
    assertEquals(1680, s.position()[0], .01);
    assertTrue(Math.exp(s.position()[2]) > 0);
    assertEquals(Math.PI / 2, s.position()[3], 0);
  }

  @Test
  public void reversalRemainsContinuous() {
    var s = new RecentsChase(36, new double[] {0}, new double[] {0});
    s.target(new double[] {1});
    s.advance(.05);
    double before = s.position()[0];
    s.target(new double[] {0});
    assertEquals(before, s.position()[0], 0);
    s.advance(1);
    assertEquals(0, s.position()[0], 1e-12);
  }

  @Test
  public void invalidElapsedTimeAndTargetsAreRejected() {
    var s = new RecentsChase(36, new double[] {1}, new double[] {0});
    assertThrows(IllegalArgumentException.class, () -> s.advance(-1));
    assertThrows(IllegalArgumentException.class, () -> s.target(new double[] {Double.NaN}));
  }
}
