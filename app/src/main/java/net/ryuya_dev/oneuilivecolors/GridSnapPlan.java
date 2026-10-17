package net.ryuya_dev.oneuilivecolors;

import java.util.*;

/** Plans a page atomically: no duplicate cells, no moving widgets, no partial overflow plans. */
final class GridSnapPlan {
  record Item(int id, int x, int y, int spanX, int spanY, int offsetX, int offsetY) {}

  record Slot(int column, int row, int x, int y, Item source) {}

  /** A user move reserves every other icon's current slot, including large folders. */
  static Slot move(
      int columns, int rows, int width, int height, Item item, Collection<Slot> occupiedSlots) {
    if (columns < 1
        || rows < 1
        || columns > 20
        || rows > 20
        || width <= 0
        || height <= 0
        || item.spanX < 1
        || item.spanY < 1
        || item.spanX > columns
        || item.spanY > rows) return null;
    boolean[][] occupied = new boolean[rows][columns];
    for (Slot other : occupiedSlots) {
      if (other.source.id == item.id) continue;
      if (!free(occupied, other.column, other.row, other.source)) return null;
      occupy(occupied, other);
    }
    Slot best = null;
    double distance = Double.POSITIVE_INFINITY;
    for (int row = 0; row <= rows - item.spanY; row++)
      for (int column = 0; column <= columns - item.spanX; column++) {
        if (!free(occupied, column, row, item)) continue;
        Slot candidate = slot(item, column, row, width, height);
        double dx = (double) candidate.x - item.x, dy = (double) candidate.y - item.y;
        double next = dx * dx + dy * dy;
        if (next < distance) {
          distance = next;
          best = candidate;
        }
      }
    return best;
  }

  static Map<Integer, Slot> build(
      int columns,
      int rows,
      int cellWidth,
      int cellHeight,
      List<Item> items,
      Map<Integer, Slot> previous) {
    if (columns < 1 || rows < 1 || columns > 20 || rows > 20 || cellWidth <= 0 || cellHeight <= 0)
      return null;
    boolean[][] occupied = new boolean[rows][columns];
    Map<Integer, Slot> result = new LinkedHashMap<>();
    List<Item> pending = new ArrayList<>(items);
    Set<Integer> ids = new HashSet<>();
    for (Item item : pending)
      if (!ids.add(item.id)
          || item.spanX < 1
          || item.spanY < 1
          || item.spanX > columns
          || item.spanY > rows) return null;
    pending.sort(Comparator.comparingInt(Item::id));
    // Keep unchanged placements stable when another item moves, is added or is removed.
    for (Iterator<Item> iterator = pending.iterator(); iterator.hasNext(); ) {
      Item item = iterator.next();
      Slot old = previous.get(item.id);
      if (old != null && item.equals(old.source) && free(occupied, old.column, old.row, item)) {
        Slot slot = slot(item, old.column, old.row, cellWidth, cellHeight);
        result.put(item.id, slot);
        occupy(occupied, slot);
        iterator.remove();
      }
    }
    // Large folders first, then stable IDs. A full page fails closed instead of overlapping icons.
    pending.sort(
        Comparator.<Item>comparingInt(i -> -(i.spanX * i.spanY)).thenComparingInt(Item::id));
    for (Item item : pending) {
      Slot best = null;
      double distance = Double.POSITIVE_INFINITY;
      for (int row = 0; row <= rows - item.spanY; row++)
        for (int column = 0; column <= columns - item.spanX; column++) {
          if (!free(occupied, column, row, item)) continue;
          Slot candidate = slot(item, column, row, cellWidth, cellHeight);
          double dx = (double) candidate.x - item.x, dy = (double) candidate.y - item.y;
          double next = dx * dx + dy * dy;
          if (next < distance) {
            distance = next;
            best = candidate;
          }
        }
      if (best == null) return null;
      result.put(item.id, best);
      occupy(occupied, best);
    }
    return result;
  }

  private static Slot slot(Item item, int column, int row, int width, int height) {
    return new Slot(column, row, item.offsetX + column * width, item.offsetY + row * height, item);
  }

  private static boolean free(boolean[][] occupied, int column, int row, Item item) {
    if (column < 0
        || row < 0
        || row + item.spanY > occupied.length
        || column + item.spanX > occupied[0].length) return false;
    for (int y = row; y < row + item.spanY; y++)
      for (int x = column; x < column + item.spanX; x++) if (occupied[y][x]) return false;
    return true;
  }

  private static void occupy(boolean[][] occupied, Slot slot) {
    for (int y = slot.row; y < slot.row + slot.source.spanY; y++)
      for (int x = slot.column; x < slot.column + slot.source.spanX; x++) occupied[y][x] = true;
  }

  private GridSnapPlan() {}
}
