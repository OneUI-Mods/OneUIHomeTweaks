package dev.ryuya.oneuihometweaks;

import static org.junit.Assert.*;
import org.junit.Test;

public class QuickPanelPolicyTest {
  private static long size(float w, float h) {
    return ((long) Float.floatToRawIntBits(w) << 32) | (Float.floatToRawIntBits(h) & 0xffffffffL);
  }

  @Test
  public void legacyStandardRetainsNativeSmallButtonAndBarSize() {
    assertEquals(44, QuickPanelPolicy.legacyDimension(44, 100), 0);
    assertEquals(35.2f, QuickPanelPolicy.legacyDimension(44, 80), .001f);
    assertEquals(52.8f, QuickPanelPolicy.legacyDimension(44, 120), .001f);
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
  public void expandedRangeScalesBothDesignsWithoutThePreviousMinimum() {
    for (float nativeDp : new float[] {44, 80, 92}) {
      assertEquals(nativeDp / 4, QuickPanelPolicy.dimension(nativeDp, 25), .001f);
      assertEquals(nativeDp * 2, QuickPanelPolicy.dimension(nativeDp, 200), .001f);
      assertEquals(nativeDp / 4, QuickPanelPolicy.legacyDimension(nativeDp, 25), .001f);
      assertEquals(nativeDp * 2, QuickPanelPolicy.legacyDimension(nativeDp, 200), .001f);
    }
  }

  @Test
  public void invalidGeometryRemainsNative() {
    long original = size(Float.NaN, 600);
    assertEquals(original, QuickPanelPolicy.thickness(original, true, .5f));
    assertEquals(size(200, 600), QuickPanelPolicy.thickness(size(200, 600), true, Float.NaN));
    assertTrue(Float.isNaN(QuickPanelPolicy.dimension(Float.NaN, 50)));
    assertEquals(0, QuickPanelPolicy.dimension(0, 25), 0);
    assertEquals(
        Float.POSITIVE_INFINITY, QuickPanelPolicy.dimension(Float.POSITIVE_INFINITY, 200), 0);
  }

  @Test
  public void corruptPreferencesStayInsideReviewedBounds() {
    assertEquals(25, QuickPanelPolicy.percent(Integer.MIN_VALUE));
    assertEquals(200, QuickPanelPolicy.percent(Integer.MAX_VALUE));
    for (int value : new int[] {25, 50, 60, 80, 100, 105, 110, 120, 150, 200})
      assertEquals(value, QuickPanelPolicy.percent(value));
  }
}
