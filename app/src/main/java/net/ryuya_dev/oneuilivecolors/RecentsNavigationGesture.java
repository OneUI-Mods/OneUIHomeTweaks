package net.ryuya_dev.oneuilivecolors;

/** Window-local navigation for a portrait Recents window over a retained landscape controller. */
final class RecentsNavigationGesture {
  static final int HOME = 3, BACK = 4;

  record Decision(boolean consume, int command) {}

  static final Decision NONE = new Decision(false, 0);
  private final int width, height, left, right, bottom, slop;
  private long down;
  private float startX, startY;
  private int key, direction;
  private boolean blocked;

  RecentsNavigationGesture(int width, int height, int left, int right, int bottom, int slop) {
    if (width <= 0
        || height <= width
        || left <= 0
        || right <= 0
        || bottom <= 0
        || left >= width * .2f
        || right >= width * .2f
        || bottom >= height * .1f
        || slop <= 0) throw new IllegalArgumentException("Unknown portrait navigation regions");
    this.width = width;
    this.height = height;
    this.left = left;
    this.right = right;
    this.bottom = bottom;
    this.slop = slop;
  }

  Decision event(long downTime, int action, int pointers, float x, float y) {
    if (action == 0) {
      down = 0;
      key = 0;
      blocked = false;
      if (pointers == 1
          && downTime > 0
          && Float.isFinite(x)
          && Float.isFinite(y)
          && x >= 0
          && x <= width
          && y >= 0
          && y <= height) {
        if (y >= height - bottom && x >= width * .04f && x <= width * .96f) key = HOME;
        else if (y < height - bottom && (x <= left || x >= width - right)) {
          key = BACK;
          blocked = true;
          direction = x < width * .5f ? 1 : -1;
        }
        if (key != 0) {
          down = downTime;
          startX = x;
          startY = y;
        }
      }
    }
    boolean consume = blocked;
    if (pointers != 1
        || action == 3
        || !Float.isFinite(x)
        || !Float.isFinite(y)
        || downTime != down) {
      down = 0;
      key = 0;
    }
    int command = 0;
    if (action == 1 && down != 0) {
      float up = startY - y, inward = (x - startX) * direction;
      if (key == HOME && y < height * .9f && up > slop && Math.abs(x - startX) < up) command = HOME;
      else if (key == BACK && inward > slop && Math.abs(y - startY) < inward) command = BACK;
    }
    if (action == 1 || action == 3) {
      down = 0;
      key = 0;
      blocked = false;
    }
    return new Decision(consume, command);
  }
}
