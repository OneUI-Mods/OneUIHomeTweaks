package net.ryuya_dev.oneuilivecolors;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;

/** Native TaskViewInfo mutates its maps after construction, changing equals/hashCode. */
final class RecentsCardSourceCache {
  private record Entry(WeakReference<Object> info, RecentsCardSpace.Space space) {}

  private final ArrayDeque<Entry> entries = new ArrayDeque<>();

  void put(Object info, RecentsCardSpace.Space space) {
    entries.removeIf(entry -> entry.info().get() == null || entry.info().get() == info);
    if (entries.size() == 64) entries.removeFirst();
    entries.addLast(new Entry(new WeakReference<>(info), space));
  }

  RecentsCardSpace.Space get(Object info) {
    for (var entry : entries) if (entry.info().get() == info) return entry.space();
    return null;
  }

  void clear() {
    entries.clear();
  }
}
