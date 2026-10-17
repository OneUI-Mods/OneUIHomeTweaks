package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class RecentsViewportContractTest {
  @Test
  public void matchingPortraitViewAndNativeStyleCanShareCoordinates() {
    RecentsViewportContract.verify(1440, 3120, 1440, 3120, 0, 0, 1440, 3120);
    RecentsViewportContract.verify(1440, 3120, 1440, 3120, 0, 0, 1439.5, 3120.5);
  }

  @Test
  public void measuredLandscapeOverlayCannotUseVirtualPortraitSize() {
    // Actual failed experiment: the calculator was 1440x3120 while the root was 3120x1440.
    assertThrows(
        IllegalStateException.class,
        () -> RecentsViewportContract.verify(1440, 3120, 3120, 1440, 0, 0, 3120, 1440));
  }

  @Test
  public void portraitRootWithStaleLandscapeStyleIsAlsoRejected() {
    assertThrows(
        IllegalStateException.class,
        () -> RecentsViewportContract.verify(1440, 3120, 1440, 3120, 0, 0, 3120, 1440));
  }

  @Test
  public void incompleteOrOffsetViewportCannotBecomeAnEndpoint() {
    for (double[] style :
        new double[][] {
          {0, 0, Double.NaN, 3120}, {0, 0, 1440, Double.POSITIVE_INFINITY},
          {20, 0, 1440, 3120}, {0, 100, 1440, 3120}
        }) {
      assertThrows(
          IllegalStateException.class,
          () ->
              RecentsViewportContract.verify(
                  1440, 3120, 1440, 3120, style[0], style[1], style[2], style[3]));
    }
    assertThrows(
        IllegalStateException.class,
        () -> RecentsViewportContract.verify(1440, 3120, 1, 1, 0, 0, 1440, 3120));
  }
}
