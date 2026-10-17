package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecentsNavigationGestureTest {
  private RecentsNavigationGesture navigation() {
    return new RecentsNavigationGesture(1440, 3120, 80, 80, 94, 24);
  }

  @Test
  public void homeOnlyFromBottomAndBeyondNativeCancelArea() {
    var n = navigation();
    assertFalse(n.event(10, 0, 1, 720, 3114).consume());
    assertEquals(0, n.event(10, 1, 1, 720, 3000).command());
    n.event(20, 0, 1, 720, 3114);
    assertEquals(3, n.event(20, 1, 1, 720, 1800).command());
    assertEquals(0, n.event(20, 1, 1, 720, 1800).command());
  }

  @Test
  public void bothBackEdgesBlockCardScrollingAndReturnOneCommand() {
    for (int x : new int[] {6, 1434}) {
      var n = navigation();
      assertTrue(n.event(10, 0, 1, x, 1550).consume());
      assertTrue(n.event(10, 2, 1, 720, 1550).consume());
      var up = n.event(10, 1, 1, 720, 1550);
      assertTrue(up.consume());
      assertEquals(4, up.command());
      assertEquals(0, n.event(10, 1, 1, 720, 1550).command());
    }
  }

  @Test
  public void cancelledAndReversedEdgesDoNotNavigate() {
    var n = navigation();
    n.event(10, 0, 1, 6, 1550);
    assertTrue(n.event(10, 3, 1, 500, 1550).consume());
    assertEquals(0, n.event(10, 1, 1, 720, 1550).command());
    n.event(20, 0, 1, 6, 1550);
    n.event(20, 2, 1, 600, 1550);
    assertEquals(0, n.event(20, 1, 1, 7, 1550).command());
  }

  @Test
  public void multitouchCannotBecomeBackAndRemainsBlockedUntilUp() {
    var n = navigation();
    n.event(10, 0, 1, 6, 1550);
    assertTrue(n.event(10, 5, 2, 500, 1550).consume());
    var up = n.event(10, 1, 1, 720, 1550);
    assertTrue(up.consume());
    assertEquals(0, up.command());
    assertFalse(n.event(20, 0, 1, 720, 1550).consume());
  }

  @Test
  public void cardSwipesAndStaleStreamsAreNotNavigation() {
    var n = navigation();
    n.event(10, 0, 1, 1200, 1550);
    assertEquals(RecentsNavigationGesture.NONE, n.event(10, 1, 1, 400, 1550));
    n.event(20, 0, 1, 720, 3114);
    assertEquals(0, n.event(19, 1, 1, 720, 1800).command());
    n.event(30, 0, 1, 6, 1550);
    assertEquals(0, n.event(30, 1, 1, 300, 2200).command());
  }

  @Test(expected = IllegalArgumentException.class)
  public void missingEdgeRegionsAreRejected() {
    new RecentsNavigationGesture(1440, 3120, 0, 0, 94, 24);
  }
}
