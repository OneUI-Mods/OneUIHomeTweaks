package net.ryuya_dev.oneuilivecolors;

/** Window pixel spaces. Layout dimensions are observed, never resized to request an orientation. */
final class RecentsCardSpace {
  record Space(int width, int height, int rotation) {
    Space {
      if (width <= 0 || height <= 0 || rotation < 0 || rotation > 3)
        throw new IllegalArgumentException("Unmeasured card space");
    }

    private int naturalWidth() {
      return rotation % 2 == 0 ? width : height;
    }

    private int naturalHeight() {
      return rotation % 2 == 0 ? height : width;
    }
  }

  static RecentsAffine.Transform between(Space from, Space to) {
    if (from.naturalWidth() != to.naturalWidth() || from.naturalHeight() != to.naturalHeight())
      throw new IllegalArgumentException("Card windows do not share display geometry");
    int w = from.naturalWidth(), h = from.naturalHeight();
    return RecentsAffine.Transform.display(from.rotation(), w, h)
        .inverse()
        .then(RecentsAffine.Transform.display(to.rotation(), w, h));
  }
}
