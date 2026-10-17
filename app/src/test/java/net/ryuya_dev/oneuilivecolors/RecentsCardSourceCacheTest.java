package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import java.util.HashMap;
import org.junit.Test;

public class RecentsCardSourceCacheTest {
  private final RecentsCardSpace.Space portrait = new RecentsCardSpace.Space(1440, 3120, 0);

  @Test
  public void producerMapMutationDoesNotLoseItsCoordinateStamp() {
    var cache = new RecentsCardSourceCache();
    var info = new HashMap<Integer, Integer>();
    cache.put(info, portrait);
    info.put(24373, 12345);
    assertSame(portrait, cache.get(info));
  }

  @Test
  public void equalObjectsDoNotShareAStampAndReplacementUsesIdentity() {
    var cache = new RecentsCardSourceCache();
    Object first = new HashMap<>(), second = new HashMap<>();
    cache.put(first, portrait);
    assertNull(cache.get(second));
    var landscape = new RecentsCardSpace.Space(3120, 1440, 1);
    cache.put(first, landscape);
    assertSame(landscape, cache.get(first));
  }

  @Test
  public void historyIsBoundedAndCanBeCleared() {
    var cache = new RecentsCardSourceCache();
    Object[] keys = new Object[65];
    for (int i = 0; i < 65; i++) {
      keys[i] = new Object();
      cache.put(keys[i], portrait);
    }
    assertNull(cache.get(keys[0]));
    assertSame(portrait, cache.get(keys[1]));
    cache.clear();
    assertNull(cache.get(keys[64]));
  }
}
