package net.ryuya_dev.oneuilivecolors;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;

/** Main-thread ownership barrier between the viewport animator and retained native live tile. */
final class RecentsHandoffGate {
  static final RecentsHandoffGate SHARED = new RecentsHandoffGate();
  private final IdentityHashMap<Object, Ticket> pending = new IdentityHashMap<>();

  static final class Ticket {
    private final Object owner;
    private final List<Consumer<Boolean>> waiting = new ArrayList<>();

    private Ticket(Object owner) {
      this.owner = owner;
    }
  }

  Ticket start(Object owner) {
    if (owner == null || pending.containsKey(owner) || pending.size() >= 4)
      throw new IllegalStateException("Invalid or duplicate handoff owner");
    Ticket ticket = new Ticket(owner);
    pending.put(owner, ticket);
    return ticket;
  }

  boolean await(Object owner, Consumer<Boolean> callback) {
    Ticket ticket = pending.get(owner);
    if (ticket == null) return false;
    if (callback == null || ticket.waiting.size() >= 4)
      throw new IllegalStateException("Invalid handoff continuation");
    ticket.waiting.add(callback);
    return true;
  }

  void complete(Ticket ticket, boolean success) {
    if (ticket == null || pending.get(ticket.owner) != ticket) return;
    pending.remove(ticket.owner);
    List<Consumer<Boolean>> waiting = new ArrayList<>(ticket.waiting);
    ticket.waiting.clear();
    Throwable first = null;
    for (var callback : waiting)
      try {
        callback.accept(success);
      } catch (Throwable error) {
        if (first == null) first = error;
      }
    if (first != null) throw new IllegalStateException("Handoff continuation failed", first);
  }

  void discard(Ticket ticket) {
    if (ticket != null && pending.get(ticket.owner) == ticket) {
      pending.remove(ticket.owner);
      ticket.waiting.clear();
    }
  }
}
