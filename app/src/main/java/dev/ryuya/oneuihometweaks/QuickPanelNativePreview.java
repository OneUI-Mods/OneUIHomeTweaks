package dev.ryuya.oneuihometweaks;

import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import java.util.concurrent.*;

/** Owns one app-bound native surface. Never moves views out of the real Quick Panel. */
final class QuickPanelNativePreview extends Binder {
  interface Renderer {
    boolean ready();

    void populate(FrameLayout root) throws Exception;

    void clear(FrameLayout root);
  }

  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Renderer renderer;
  private final int appUid;
  private SurfaceControlViewHost host;
  private FrameLayout root;
  private IBinder client;
  private IBinder.DeathRecipient death;
  private boolean announcedReady;
  private volatile boolean failed;

  QuickPanelNativePreview(Context context, ClassLoader loader, QuickPanelHooks.Adapter adapter)
      throws Exception {
    this.context = context;
    appUid = context.getPackageManager().getApplicationInfo("dev.ryuya.oneuihometweaks", 0).uid;
    renderer =
        Build.VERSION.SDK_INT == 37
            ? new QuickPanelPreviewAndroid17(
                loader, (QuickPanelAndroid17) adapter, this::sourceChanged, this::fail)
            : new QuickPanelPreviewAndroid16(loader, this::sourceChanged);
    register();
  }

  private void register() {
    Bundle extras = new Bundle();
    extras.putBinder("backend", this);
    try {
      context
          .getContentResolver()
          .call(ConfigProvider.URI, QuickPanelPreviewIpc.REGISTER, null, extras);
    } catch (RuntimeException error) {
      HookEntry.log("QUICK_PANEL_PREVIEW_REGISTRATION " + error.getClass().getSimpleName());
    }
  }

  private void sourceChanged() {
    if (!announcedReady && renderer != null && renderer.ready()) {
      announcedReady = true;
      main.post(this::register);
    }
  }

  private void fail() {
    failed = true;
    close();
    register();
  }

  void refresh() {
    register();
    if (root == null || failed) return;
    try {
      renderer.clear(root);
      renderer.populate(root);
    } catch (Throwable error) {
      HookEntry.log("QUICK_PANEL_PREVIEW_DISABLED " + error.getClass().getSimpleName());
      fail();
    }
  }

  @Override
  protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
      throws RemoteException {
    if (code != QuickPanelPreviewIpc.CREATE && code != QuickPanelPreviewIpc.RELEASE)
      return super.onTransact(code, data, reply, flags);
    if (Binder.getCallingUid() != appUid) throw new SecurityException("Module app only");
    data.enforceInterface(QuickPanelPreviewIpc.DESCRIPTOR);
    IBinder owner = data.readStrongBinder();
    if (owner == null) throw new IllegalArgumentException("Missing client");
    if (code == QuickPanelPreviewIpc.RELEASE) {
      main.post(
          () -> {
            if (owner.equals(client)) close();
          });
      reply.writeNoException();
      return true;
    }
    IBinder token = data.readStrongBinder();
    int displayId = data.readInt(), width = data.readInt(), height = data.readInt();
    if (token == null
        || width <= 0
        || height <= 0
        || width > QuickPanelPreviewIpc.MAX_PIXELS
        || height > QuickPanelPreviewIpc.MAX_PIXELS)
      throw new IllegalArgumentException("Invalid surface geometry");
    java.util.concurrent.atomic.AtomicBoolean expired =
        new java.util.concurrent.atomic.AtomicBoolean();
    FutureTask<SurfaceControlViewHost.SurfacePackage> task =
        new FutureTask<>(
            () -> {
              close();
              if (failed || !renderer.ready() || !owner.isBinderAlive()) return null;
              var display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
              if (display == null) throw new IllegalArgumentException("Missing display");
              client = owner;
              death =
                  () ->
                      main.post(
                          () -> {
                            if (owner.equals(client)) close();
                          });
              owner.linkToDeath(death, 0);
              root =
                  new FrameLayout(context) {
                    @Override
                    public boolean dispatchTouchEvent(MotionEvent event) {
                      return true;
                    }
                  };
              root.setImportantForAccessibility(
                  View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
              try {
                renderer.populate(root);
                host = new SurfaceControlViewHost(context, display, token);
                try {
                  var params =
                      new WindowManager.LayoutParams(
                          width,
                          height,
                          WindowManager.LayoutParams.TYPE_APPLICATION,
                          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                              | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                          android.graphics.PixelFormat.TRANSLUCENT);
                  SurfaceControlViewHost.class
                      .getMethod("setView", View.class, WindowManager.LayoutParams.class)
                      .invoke(host, root, params);
                } catch (NoSuchMethodException missing) {
                  host.setView(root, width, height);
                }
                var result = host.getSurfacePackage();
                if (expired.get()) {
                  if (result != null) result.release();
                  close();
                  return null;
                }
                return result;
              } catch (Throwable error) {
                close();
                throw error;
              }
            });
    main.post(task);
    SurfaceControlViewHost.SurfacePackage result = null;
    String state = "WAITING";
    try {
      result = task.get(3, TimeUnit.SECONDS);
      state = failed ? "UNAVAILABLE" : result == null ? "WAITING" : "READY";
    } catch (Exception error) {
      expired.set(true);
      task.cancel(false);
      main.post(
          () -> {
            if (owner.equals(client)) close();
          });
      state = "UNAVAILABLE";
      HookEntry.log("QUICK_PANEL_PREVIEW_ERROR " + error);
    }
    reply.writeNoException();
    reply.writeString(state);
    reply.writeTypedObject(result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
    if (result != null) result.release();
    return true;
  }

  private void close() {
    FrameLayout oldRoot = root;
    SurfaceControlViewHost oldHost = host;
    root = null;
    host = null;
    if (client != null && death != null) {
      try {
        client.unlinkToDeath(death, 0);
      } catch (java.util.NoSuchElementException ignored) {
      }
    }
    client = null;
    death = null;
    try {
      if (oldRoot != null) renderer.clear(oldRoot);
    } catch (Throwable error) {
      HookEntry.log("QUICK_PANEL_PREVIEW_CLEAR " + error.getClass().getSimpleName());
    }
    try {
      if (oldHost != null) oldHost.release();
    } catch (RuntimeException error) {
      HookEntry.log("QUICK_PANEL_PREVIEW_RELEASE " + error.getClass().getSimpleName());
    }
  }
}
