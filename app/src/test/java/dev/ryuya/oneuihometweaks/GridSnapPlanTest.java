package dev.ryuya.oneuihometweaks;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public final class GridSnapPlanTest {
  @Test
  public void layoutsAreBoundedUniqueAndIndependentOfInputOrdering() {
    Random random = new Random(917);
    for (int run = 0; run < 100; run++) {
      List<GridSnapPlan.Item> items = new ArrayList<>();
      for (int id = 0; id < 30; id++)
        items.add(item(id, random.nextInt(2000) - 200, random.nextInt(2800) - 200));
      var first = GridSnapPlan.build(7, 7, 190, 339, items, Map.of());
      assertNotNull(first);
      Set<String> cells = new HashSet<>();
      for (var slot : first.values()) {
        assertTrue(slot.column() >= 0 && slot.column() < 7);
        assertTrue(slot.row() >= 0 && slot.row() < 7);
        assertTrue(cells.add(slot.column() + ":" + slot.row()));
        assertEquals(9 + 190 * slot.column(), slot.x());
        assertEquals(61 + 339 * slot.row(), slot.y());
      }
      Collections.shuffle(items, random);
      assertEquals(first, GridSnapPlan.build(7, 7, 190, 339, items, Map.of()));
    }
  }

  private static GridSnapPlan.Item item(int id, int x, int y) {
    return new GridSnapPlan.Item(id, x, y, 1, 1, 9, 61);
  }

  @Test
  public void usesNativeGridGeometryRatherThanScreenFractions() {
    var plan = GridSnapPlan.build(7, 7, 190, 339, List.of(item(1, 210, 420)), Map.of());
    assertEquals(199, plan.get(1).x());
    assertEquals(400, plan.get(1).y());
  }

  @Test
  public void duplicateNearbyIconsGetSeparateSlots() {
    var plan =
        GridSnapPlan.build(2, 2, 100, 100, List.of(item(1, 9, 61), item(2, 10, 62)), Map.of());
    assertNotEquals(
        plan.get(1).column() + 2 * plan.get(1).row(), plan.get(2).column() + 2 * plan.get(2).row());
  }

  @Test
  public void unchangedIconsStayPutWhenAnotherMoves() {
    var first =
        GridSnapPlan.build(3, 2, 100, 100, List.of(item(1, 9, 61), item(2, 109, 61)), Map.of());
    var second =
        GridSnapPlan.build(3, 2, 100, 100, List.of(item(1, 109, 61), item(2, 109, 61)), first);
    assertEquals(first.get(2), second.get(2));
    assertNotEquals(second.get(1).column(), second.get(2).column());
  }

  @Test
  public void foldersReserveAllTheirCellsAndOverflowIsAtomic() {
    var large = new GridSnapPlan.Item(1, 9, 61, 2, 2, 9, 61);
    assertNull(GridSnapPlan.build(2, 2, 100, 100, List.of(large, item(2, 9, 61)), Map.of()));
    assertNull(GridSnapPlan.build(0, 7, 100, 100, List.of(), Map.of()));
    assertNull(
        GridSnapPlan.build(2, 2, 100, 100, List.of(item(1, 9, 61), item(1, 10, 62)), Map.of()));
  }

  @Test
  public void userMoveUsesNearestFreeCellAndKeepsOtherIcons() {
    var fixed =
        GridSnapPlan.build(3, 2, 100, 100, List.of(item(1, 9, 61), item(2, 109, 61)), Map.of());
    var moved = GridSnapPlan.move(3, 2, 100, 100, item(1, 190, 70), fixed.values());
    assertEquals(2, moved.column());
    assertEquals(0, moved.row());
    assertEquals(109, fixed.get(2).x());
    assertEquals(
        0, GridSnapPlan.move(3, 2, 100, 100, item(1, -10000, -10000), fixed.values()).column());
  }

  @Test
  public void movingFolderCannotOverlapOccupiedCellsOrLeaveGrid() {
    var fixed = GridSnapPlan.build(3, 2, 100, 100, List.of(item(2, 209, 61)), Map.of());
    var folder = new GridSnapPlan.Item(1, 999, 999, 2, 2, 9, 61);
    var moved = GridSnapPlan.move(3, 2, 100, 100, folder, fixed.values());
    assertEquals(0, moved.column());
    assertEquals(0, moved.row());
    assertNull(GridSnapPlan.move(1, 1, 100, 100, folder, List.of()));
  }
}
