package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public final class LocalContrastTest {
  private int[] solid(int color) {
    int[] pixels = new int[100];
    Arrays.fill(pixels, color);
    return pixels;
  }

  @Test
  public void transitionFramesNeedMatchingSettledLocalPixels() {
    assertFalse(LocalContrast.settled(null, solid(0xff888888)));
    assertFalse(LocalContrast.settled(new int[7], new int[7]));
    assertFalse(LocalContrast.settled(solid(0xff888888), new int[8]));
    assertFalse(LocalContrast.settled(solid(0xff202020), solid(0xffeeeeee)));
    assertTrue(LocalContrast.settled(solid(0xff888888), solid(0xff8a8a8a)));
    assertTrue(LocalContrast.settled(solid(0xff888888), solid(0xff888888)));
  }

  @Test
  public void eachRegionChoosesItsOwnContrast() {
    assertEquals(
        Integer.valueOf(LocalContrast.BLACK), LocalContrast.choose(solid(0xffeeeeee), null));
    assertEquals(
        Integer.valueOf(LocalContrast.WHITE), LocalContrast.choose(solid(0xff202020), null));
  }

  @Test
  public void srgbLuminanceUsesColorNotJustRgbAverage() {
    assertEquals(
        Integer.valueOf(LocalContrast.BLACK), LocalContrast.choose(solid(0xff00ff00), null));
    assertEquals(
        Integer.valueOf(LocalContrast.WHITE), LocalContrast.choose(solid(0xff0000ff), null));
  }

  @Test
  public void smallOutlierDoesNotReverseTheWholeLabel() {
    int[] bright = solid(0xffeeeeee);
    Arrays.fill(bright, 0, 10, 0xff000000);
    assertEquals(Integer.valueOf(LocalContrast.BLACK), LocalContrast.choose(bright, null));
    int[] dark = solid(0xff202020);
    Arrays.fill(dark, 0, 10, 0xffffffff);
    assertEquals(Integer.valueOf(LocalContrast.WHITE), LocalContrast.choose(dark, null));
  }

  @Test
  public void transparentOrInsufficientDataFallsBack() {
    assertNull(LocalContrast.choose(new int[0], null));
    assertNull(LocalContrast.choose(new int[7], LocalContrast.BLACK));
    assertNull(LocalContrast.choose(solid(0x00ffffff), null));
    int[] partial = solid(0xffffffff);
    Arrays.fill(partial, 0, 26, 0x00ffffff);
    assertNull(LocalContrast.choose(partial, LocalContrast.BLACK));
  }

  @Test
  public void hysteresisAvoidsFlipsButDoesNotLockThePreviousColor() {
    assertEquals(
        Integer.valueOf(LocalContrast.WHITE), LocalContrast.choose(solid(0xff757575), null));
    assertEquals(
        Integer.valueOf(LocalContrast.BLACK),
        LocalContrast.choose(solid(0xff757575), LocalContrast.BLACK));
    assertEquals(
        Integer.valueOf(LocalContrast.WHITE),
        LocalContrast.choose(solid(0xff202020), LocalContrast.BLACK));
    assertEquals(
        Integer.valueOf(LocalContrast.BLACK),
        LocalContrast.choose(solid(0xffeeeeee), LocalContrast.WHITE));
  }
}
