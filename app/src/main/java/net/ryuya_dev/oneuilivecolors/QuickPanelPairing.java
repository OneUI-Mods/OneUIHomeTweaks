package net.ryuya_dev.oneuilivecolors;

import java.util.*;

/** Merge only adjacent bar/companion cells whose union cannot cover another item. */
final class QuickPanelPairing {
  record Cell(int index, String type, String spec, int x, int y, int width, int height) {}

  record Pair(int bar, int button, int x, int y, int width, int height) {}

  static List<Pair> pairs(List<Cell> cells) {
    List<Pair> result = new ArrayList<>();
    Set<Integer> used = new HashSet<>();
    for (Cell bar : cells) {
      String spec =
          switch (bar.type) {
            case "BrightBar" -> "UiModeNight";
            case "VolumeBar" -> "SoundMode";
            default -> null;
          };
      if (spec == null || !valid(bar) || used.contains(bar.index)) continue;
      for (Cell button : cells) {
        if (!"QuickTile".equals(button.type)
            || !spec.equals(button.spec)
            || !valid(button)
            || used.contains(button.index)) continue;
        boolean below =
            button.x == bar.x && button.width == bar.width && button.y == bar.y + bar.height;
        boolean right =
            button.y == bar.y && button.height == bar.height && button.x == bar.x + bar.width;
        if (!below && !right) continue;
        int width = below ? bar.width : bar.width + button.width;
        int height = right ? bar.height : bar.height + button.height;
        if (width > 4 || height > 4) continue;
        boolean occupied = false;
        for (Cell other : cells) {
          if (other.index == bar.index || other.index == button.index || !valid(other)) continue;
          if (other.x < bar.x + width
              && other.x + other.width > bar.x
              && other.y < bar.y + height
              && other.y + other.height > bar.y) {
            occupied = true;
            break;
          }
        }
        if (occupied) continue;
        result.add(new Pair(bar.index, button.index, bar.x, bar.y, width, height));
        used.add(bar.index);
        used.add(button.index);
        break;
      }
    }
    return result;
  }

  private static boolean valid(Cell cell) {
    return cell.x >= 0
        && cell.y >= 0
        && cell.width > 0
        && cell.height > 0
        && cell.x < 100
        && cell.y < 100
        && cell.width <= 8
        && cell.height <= 32;
  }
}
