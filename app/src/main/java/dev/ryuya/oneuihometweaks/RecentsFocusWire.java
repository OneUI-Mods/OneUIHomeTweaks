package dev.ryuya.oneuihometweaks;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

/** Private authenticated protocol, not a replacement for any Android Binder transaction. */
final class RecentsFocusWire {
  // Outside the verified ATMS transaction range, inside Binder's application transaction range.
  static final int DISCOVER = 0x00f04f55;
  static final int STATUS = 1, ARM = 2, BEGIN = 3, END = 4, STOP = 5;
  // Keep the existing wire name so a package migration does not invalidate
  // already loaded system_server / Launcher endpoints.
  static final String DESCRIPTOR = "net.ryuya_dev.oneuilivecolors.recents.focus.v1";

  static Bundle call(IBinder endpoint, int code, Bundle data) throws Exception {
    Parcel in = Parcel.obtain(), out = Parcel.obtain();
    try {
      in.writeInterfaceToken(DESCRIPTOR);
      in.writeBundle(data == null ? new Bundle() : data);
      if (!endpoint.transact(code, in, out, 0))
        throw new IllegalStateException("Focus backend not loaded");
      out.readException();
      Bundle reply = out.readBundle(RecentsFocusWire.class.getClassLoader());
      if (reply == null || reply.getInt("build", -1) != BuildConfig.VERSION_CODE)
        throw new IllegalStateException("Focus backend version mismatch");
      return reply;
    } finally {
      in.recycle();
      out.recycle();
    }
  }
}
