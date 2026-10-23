package dev.ryuya.oneuihometweaks;

final class PagePolicy {
  static float offset(int scroll, int[] positions) {
    if (positions.length <= 1) return 0.5f;
    boolean ascending = positions[positions.length - 1] > positions[0];
    for (int i = 1; i < positions.length; i++)
      if (ascending ? positions[i] <= positions[i - 1] : positions[i] >= positions[i - 1])
        throw new IllegalArgumentException("Nonmonotonic page positions");
    float first = positions[0], last = positions[positions.length - 1];
    if (ascending ? scroll <= first : scroll >= first) return 0f;
    if (ascending ? scroll >= last : scroll <= last) return 1f;
    for (int i = 0; i < positions.length - 1; i++) {
      if (ascending ? scroll <= positions[i + 1] : scroll >= positions[i + 1])
        return (i + (scroll - (float) positions[i]) / (positions[i + 1] - (float) positions[i]))
            / (positions.length - 1);
    }
    return 0.5f;
  }
}
