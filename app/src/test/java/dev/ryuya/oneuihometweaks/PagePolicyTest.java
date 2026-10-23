package dev.ryuya.oneuihometweaks;

import static org.junit.Assert.*;

import org.junit.Test;

public class PagePolicyTest {
  @Test
  public void pagePositionsAndUnequalGaps() {
    assertEquals(0.5f, PagePolicy.offset(1100, new int[] {100, 1100, 2300}), 0.0001f);
    assertEquals(0.75f, PagePolicy.offset(1700, new int[] {100, 1100, 2300}), 0.0001f);
  }

  @Test
  public void descendingRtlPositions() {
    assertEquals(0f, PagePolicy.offset(2200, new int[] {2200, 1100, 0}), 0.0001f);
    assertEquals(0.75f, PagePolicy.offset(550, new int[] {2200, 1100, 0}), 0.0001f);
  }

  @Test
  public void clampsAndSinglePage() {
    assertEquals(0f, PagePolicy.offset(-10, new int[] {0, 100}), 0f);
    assertEquals(1f, PagePolicy.offset(110, new int[] {0, 100}), 0f);
    assertEquals(0.5f, PagePolicy.offset(9, new int[] {0}), 0f);
  }

  @Test(expected = IllegalArgumentException.class)
  public void ambiguousLayoutRejected() {
    PagePolicy.offset(90, new int[] {0, 100, 80});
  }
}
