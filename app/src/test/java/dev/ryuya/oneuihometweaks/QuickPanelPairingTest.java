package dev.ryuya.oneuihometweaks;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class QuickPanelPairingTest {
  private QuickPanelPairing.Cell cell(
      int id, String type, String spec, int x, int y, int w, int h) {
    return new QuickPanelPairing.Cell(id, type, spec, x, y, w, h);
  }

  @Test
  public void portraitMergesOnlyCorrectCompanionBelow() {
    var pairs =
        QuickPanelPairing.pairs(
            List.of(
                cell(0, "BrightBar", "", 3, 4, 1, 2),
                cell(1, "QuickTile", "UiModeNight", 3, 6, 1, 1)));
    assertEquals(List.of(new QuickPanelPairing.Pair(0, 1, 3, 4, 1, 3)), pairs);
  }

  @Test
  public void landscapeMergesRightHandButton() {
    var pairs =
        QuickPanelPairing.pairs(
            List.of(
                cell(0, "VolumeBar", "", 1, 2, 3, 1),
                cell(1, "QuickTile", "SoundMode", 4, 2, 1, 1)));
    assertEquals(List.of(new QuickPanelPairing.Pair(0, 1, 1, 2, 4, 1)), pairs);
  }

  @Test
  public void detachedButtonsNeverMoveAcrossOtherItems() {
    assertTrue(
        QuickPanelPairing.pairs(
                List.of(
                    cell(0, "BrightBar", "", 3, 4, 1, 2),
                    cell(1, "QuickTile", "UiModeNight", 2, 6, 1, 1)))
            .isEmpty());
  }

  @Test
  public void wrongButtonNeverAttaches() {
    assertTrue(
        QuickPanelPairing.pairs(
                List.of(
                    cell(0, "BrightBar", "", 3, 4, 1, 2),
                    cell(1, "QuickTile", "SoundMode", 3, 6, 1, 1)))
            .isEmpty());
  }

  @Test
  public void overlappingThirdItemPreventsMerge() {
    assertTrue(
        QuickPanelPairing.pairs(
                List.of(
                    cell(0, "VolumeBar", "", 3, 4, 1, 2),
                    cell(1, "QuickTile", "SoundMode", 3, 6, 1, 1),
                    cell(2, "MediaPlayer", "", 3, 6, 2, 2)))
            .isEmpty());
  }

  @Test
  public void outsideNativeGroupBoundsStaysUnchanged() {
    assertTrue(
        QuickPanelPairing.pairs(
                List.of(
                    cell(0, "BrightBar", "", 0, 0, 1, 4),
                    cell(1, "QuickTile", "UiModeNight", 0, 4, 1, 1)))
            .isEmpty());
  }

  @Test
  public void unplacedItemStaysUnchanged() {
    assertTrue(
        QuickPanelPairing.pairs(
                List.of(
                    cell(0, "BrightBar", "", -1, -1, 1, 2),
                    cell(1, "QuickTile", "UiModeNight", 0, 1, 1, 1)))
            .isEmpty());
  }

  @Test
  public void sourceCellsAreNeverMutated() {
    var cells =
        List.of(
            cell(0, "VolumeBar", "", 0, 0, 1, 2), cell(1, "QuickTile", "SoundMode", 0, 2, 1, 1));
    QuickPanelPairing.pairs(cells);
    assertEquals(2, cells.get(0).height());
    assertEquals(2, cells.get(1).y());
  }
}
