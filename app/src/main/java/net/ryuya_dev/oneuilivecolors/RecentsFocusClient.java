package net.ryuya_dev.oneuilivecolors;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

/** Launcher-only discovery. No injection or hooks in the source application. */
final class RecentsFocusClient {
  private record Connection(IBinder backend, IBinder anchor) {}

  record Lease(IBinder backend, IBinder owner) {}

  private volatile Connection connection;
  private volatile String state = "backend-missing";

  String diagnostic() {
    return state;
  }

  void connect(Context context) {
    try {
      Connection current = connection;
      IBinder backend = current == null ? null : current.backend();
      if (backend == null || !backend.isBinderAlive()) {
        Class<?> services = Class.forName("android.os.ServiceManager");
        IBinder atm =
            (IBinder) services.getMethod("getService", String.class).invoke(null, "activity_task");
        Bundle found = RecentsFocusWire.call(atm, RecentsFocusWire.DISCOVER, null);
        backend = found.getBinder("backend");
        if (backend == null) throw new IllegalStateException("No backend");
      }
      Bundle data = new Bundle();
      data.putBinder("backend", backend);
      Bundle reply =
          context.getContentResolver().call(ConfigProvider.URI, "focus_register", null, data);
      IBinder anchor = reply == null ? null : reply.getBinder("anchor");
      if (anchor == null) throw new IllegalStateException("No provider anchor");
      connection = new Connection(backend, anchor);
      state =
          RecentsFocusWire.call(backend, RecentsFocusWire.STATUS, null).getString("state", "error");
    } catch (Throwable error) {
      state = "backend-missing";
    }
  }

  Lease begin(int task, int mode) {
    Connection current = connection;
    if (current == null || RecentsFocusPolicy.mode(mode) == 0) return null;
    IBinder backend = current.backend(), anchor = current.anchor();
    IBinder owner = new Binder();
    Bundle data = new Bundle();
    data.putBinder("owner", owner);
    data.putBinder("anchor", anchor);
    data.putInt("task", task);
    data.putInt("mode", mode);
    try {
      Bundle reply = RecentsFocusWire.call(backend, RecentsFocusWire.BEGIN, data);
      state = reply.getString("state", "error");
      return reply.getBoolean("accepted") ? new Lease(backend, owner) : null;
    } catch (Throwable error) {
      state = "error";
      return null;
    }
  }

  void end(Lease lease) {
    if (lease == null) return;
    Bundle data = new Bundle();
    data.putBinder("owner", lease.owner());
    try {
      state =
          RecentsFocusWire.call(lease.backend(), RecentsFocusWire.END, data)
              .getString("state", "error");
    } catch (Throwable error) {
      state = "error";
    }
  }
}
