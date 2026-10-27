package dev.ryuya.oneuihometweaks;

/** A native grid may only be projected into a measured home viewport that can contain it. */
record DiyGridGeometry(
    int columns,
    int rows,
    int cellWidth,
    int cellHeight,
    int offsetX,
    int offsetY,
    boolean landscape) {
  boolean fits(int viewportWidth, int viewportHeight) {
    if (landscape
        || columns < 1
        || rows < 1
        || columns > 20
        || rows > 20
        || cellWidth < 1
        || cellHeight < 1
        || viewportWidth < 1
        || viewportHeight <= viewportWidth) return false;

    long insetX = Math.abs((long) offsetX), insetY = Math.abs((long) offsetY);
    if (insetX > viewportWidth || insetY > viewportHeight) return false;
    // Normal home and DIY home can have different margins. Allow their native inset
    // difference, plus at most one pixel of rounding per cell, without swapping axes.
    return (long) columns * cellWidth <= viewportWidth + 2 * insetX + columns
        && (long) rows * cellHeight <= viewportHeight + 2 * insetY + rows;
  }
}
