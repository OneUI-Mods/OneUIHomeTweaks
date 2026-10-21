package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;
import org.junit.Test;

public class QuickPanelPolicyTest {
  private static long size(float w, float h) {
    return ((long) Float.floatToRawIntBits(w) << 32) | (Float.floatToRawIntBits(h) & 0xffffffffL);
  }

  @Test
  public void verticalOnlyChangesThicknessAndPreservesLength() {
    long actual = QuickPanelPolicy.thickness(size(280, 600), true, .6f);
    assertEquals(168, Float.intBitsToFloat((int) (actual >>> 32)), .001f);
    assertEquals(600, Float.intBitsToFloat((int) actual), 0);
  }

  @Test
  public void horizontalOnlyChangesThicknessAndPreservesLength() {
    long actual = QuickPanelPolicy.thickness(size(600, 120), false, 1.2f);
    assertEquals(600, Float.intBitsToFloat((int) (actual >>> 32)), 0);
    assertEquals(144, Float.intBitsToFloat((int) actual), .001f);
  }

  @Test
  public void standardPackedSizeIsRestoredExactly() {
    assertEquals(size(278.25f, 603.5f), QuickPanelPolicy.thickness(size(278.25f, 603.5f), true, 1));
    assertEquals(92, QuickPanelPolicy.dimension(92, 100), 0);
  }

  @Test
  public void touchableThicknessHasLowerBound() {
    assertEquals(48, QuickPanelPolicy.dimension(80, 50), 0);
    assertEquals(110.4f, QuickPanelPolicy.dimension(92, 120), .001f);
  }

  @Test
  public void invalidGeometryRemainsNative() {
    long original = size(Float.NaN, 600);
    assertEquals(original, QuickPanelPolicy.thickness(original, true, .5f));
    assertEquals(size(200, 600), QuickPanelPolicy.thickness(size(200, 600), true, Float.NaN));
    assertTrue(Float.isNaN(QuickPanelPolicy.dimension(Float.NaN, 50)));
  }

  @Test
  public void corruptPreferencesStayInsideReviewedBounds() {
    assertEquals(50, QuickPanelPolicy.percent(Integer.MIN_VALUE));
    assertEquals(120, QuickPanelPolicy.percent(Integer.MAX_VALUE));
    assertEquals(100, QuickPanelPolicy.percent(100));
  }
}
