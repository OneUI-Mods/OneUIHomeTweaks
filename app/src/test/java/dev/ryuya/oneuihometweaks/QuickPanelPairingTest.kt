package dev.ryuya.oneuihometweaks

import java.util.List
import org.junit.Assert.*
import org.junit.Test

open class QuickPanelPairingTest {
    private fun cell(
        id: Int,
        type: String?,
        spec: String?,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): QuickPanelPairing.Cell? {
        return QuickPanelPairing.Cell(id, type, spec, x, y, w, h)
    }

    @Test
    open fun portraitMergesOnlyCorrectCompanionBelow() {
        val pairs =
            QuickPanelPairing.pairs(
                List.of<QuickPanelPairing.Cell?>(
                    cell(0, "BrightBar", "", 3, 4, 1, 2),
                    cell(1, "QuickTile", "UiModeNight", 3, 6, 1, 1),
                )
            )
        assertEquals(List.of(QuickPanelPairing.Pair(0, 1, 3, 4, 1, 3)), pairs)
    }

    @Test
    open fun landscapeMergesRightHandButton() {
        val pairs =
            QuickPanelPairing.pairs(
                List.of<QuickPanelPairing.Cell?>(
                    cell(0, "VolumeBar", "", 1, 2, 3, 1),
                    cell(1, "QuickTile", "SoundMode", 4, 2, 1, 1),
                )
            )
        assertEquals(List.of(QuickPanelPairing.Pair(0, 1, 1, 2, 4, 1)), pairs)
    }

    @Test
    open fun detachedButtonsNeverMoveAcrossOtherItems() {
        assertTrue(
            QuickPanelPairing.pairs(
                    List.of<QuickPanelPairing.Cell?>(
                        cell(0, "BrightBar", "", 3, 4, 1, 2),
                        cell(1, "QuickTile", "UiModeNight", 2, 6, 1, 1),
                    )
                )
                .isEmpty()
        )
    }

    @Test
    open fun wrongButtonNeverAttaches() {
        assertTrue(
            QuickPanelPairing.pairs(
                    List.of<QuickPanelPairing.Cell?>(
                        cell(0, "BrightBar", "", 3, 4, 1, 2),
                        cell(1, "QuickTile", "SoundMode", 3, 6, 1, 1),
                    )
                )
                .isEmpty()
        )
    }

    @Test
    open fun overlappingThirdItemPreventsMerge() {
        assertTrue(
            QuickPanelPairing.pairs(
                    List.of<QuickPanelPairing.Cell?>(
                        cell(0, "VolumeBar", "", 3, 4, 1, 2),
                        cell(1, "QuickTile", "SoundMode", 3, 6, 1, 1),
                        cell(2, "MediaPlayer", "", 3, 6, 2, 2),
                    )
                )
                .isEmpty()
        )
    }

    @Test
    open fun outsideNativeGroupBoundsStaysUnchanged() {
        assertTrue(
            QuickPanelPairing.pairs(
                    List.of<QuickPanelPairing.Cell?>(
                        cell(0, "BrightBar", "", 0, 0, 1, 4),
                        cell(1, "QuickTile", "UiModeNight", 0, 4, 1, 1),
                    )
                )
                .isEmpty()
        )
    }

    @Test
    open fun unplacedItemStaysUnchanged() {
        assertTrue(
            QuickPanelPairing.pairs(
                    List.of<QuickPanelPairing.Cell?>(
                        cell(0, "BrightBar", "", -1, -1, 1, 2),
                        cell(1, "QuickTile", "UiModeNight", 0, 1, 1, 1),
                    )
                )
                .isEmpty()
        )
    }

    @Test
    open fun sourceCellsAreNeverMutated() {
        val cells =
            List.of<QuickPanelPairing.Cell?>(
                cell(0, "VolumeBar", "", 0, 0, 1, 2),
                cell(1, "QuickTile", "SoundMode", 0, 2, 1, 1),
            )
        QuickPanelPairing.pairs(cells)
        assertEquals(2, cells.get(0).height)
        assertEquals(2, cells.get(1).y)
    }
}
