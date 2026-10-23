package dev.ryuya.oneuihometweaks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable preview identity: a confirmation cannot silently apply to a different layout. */
record GridSavePlan(List<Entry> entries) {
  record Entry(int id, int page, int oldX, int oldY, int x, int y, String shape) {
    boolean changed() {
      return oldX != x || oldY != y;
    }
  }

  GridSavePlan {
    entries =
        List.copyOf(
            entries.stream()
                .sorted(Comparator.comparingInt(Entry::id))
                .collect(java.util.stream.Collectors.toList()));
    if (entries.isEmpty()
        || entries.size() > 500
        || entries.stream().map(Entry::id).distinct().count() != entries.size())
      throw new IllegalArgumentException("Ambiguous icon list");
  }

  int changed() {
    return (int) entries.stream().filter(Entry::changed).count();
  }

  int pages() {
    return (int) entries.stream().filter(Entry::changed).map(Entry::page).distinct().count();
  }

  String fingerprint() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Entry e : entries) digest.update((e.toString() + "\n").getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
      return out.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
