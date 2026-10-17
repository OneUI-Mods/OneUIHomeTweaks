package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RecentsHandoffGateTest {
  @Test
  public void handoffTransfersOwnershipOnlyOnce() {
    var gate = new RecentsHandoffGate();
    Object owner = new Object();
    var ticket = gate.start(owner);
    List<Boolean> result = new ArrayList<>();
    assertTrue(gate.await(owner, result::add));
    assertTrue(result.isEmpty());
    gate.complete(ticket, true);
    gate.complete(ticket, false);
    assertEquals(List.of(true), result);
    assertFalse(gate.await(owner, result::add));
  }

  @Test
  public void staleSessionCannotReleaseNewSession() {
    var gate = new RecentsHandoffGate();
    Object owner = new Object();
    var old = gate.start(owner);
    gate.discard(old);
    var fresh = gate.start(owner);
    List<Boolean> result = new ArrayList<>();
    gate.await(owner, result::add);
    gate.complete(old, true);
    assertTrue(result.isEmpty());
    gate.complete(fresh, false);
    assertEquals(List.of(false), result);
  }

  @Test
  public void identityDoesNotDependOnMutableNativeHashes() {
    class Owner {
      int hash;

      @Override
      public int hashCode() {
        return hash;
      }

      @Override
      public boolean equals(Object other) {
        return other instanceof Owner;
      }
    }
    var gate = new RecentsHandoffGate();
    Owner owner = new Owner(), another = new Owner();
    var ticket = gate.start(owner);
    owner.hash = 99;
    List<Boolean> result = new ArrayList<>();
    assertFalse(gate.await(another, result::add));
    assertTrue(gate.await(owner, result::add));
    gate.complete(ticket, true);
    assertEquals(List.of(true), result);
  }

  @Test
  public void callbackSeesReleasedOwnershipAndCanBeginAnotherGesture() {
    var gate = new RecentsHandoffGate();
    Object owner = new Object();
    var ticket = gate.start(owner);
    List<Boolean> result = new ArrayList<>();
    gate.await(
        owner,
        ok -> {
          assertFalse(gate.await(owner, result::add));
          var next = gate.start(owner);
          gate.discard(next);
          result.add(ok);
        });
    gate.complete(ticket, true);
    assertEquals(List.of(true), result);
  }

  @Test
  public void failingConsumerDoesNotStrandOtherConsumers() {
    var gate = new RecentsHandoffGate();
    Object owner = new Object();
    var ticket = gate.start(owner);
    List<Boolean> result = new ArrayList<>();
    gate.await(
        owner,
        ok -> {
          throw new IllegalStateException("test");
        });
    gate.await(owner, result::add);
    assertThrows(IllegalStateException.class, () -> gate.complete(ticket, false));
    assertEquals(List.of(false), result);
    assertFalse(gate.await(owner, result::add));
  }
}
