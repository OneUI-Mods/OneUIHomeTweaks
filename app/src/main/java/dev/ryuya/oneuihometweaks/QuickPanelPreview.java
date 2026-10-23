package dev.ryuya.oneuihometweaks;

import android.content.*;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.os.*;
import android.util.AttributeSet;
import android.view.*;
import android.widget.*;

/** Embeds SystemUI's own native rendering surface; contains no imitation of its controls. */
public final class QuickPanelPreview extends FrameLayout implements SurfaceHolder.Callback {
  private final SurfaceView surface;
  private final TextView message;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final HandlerThread thread = new HandlerThread("QuickPanelPreviewClient");
  private final Handler worker;
  private final IBinder client = new Binder();
  private IBinder backend;
  private int generation;
  private boolean active, created;
  private final ContentObserver observer =
      new ContentObserver(main) {
        @Override
        public void onChange(boolean self) {
          connect();
        }
      };

  public QuickPanelPreview(Context context, AttributeSet attrs) {
    super(context, attrs);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    surface = new SurfaceView(context);
    surface.setZOrderOnTop(true);
    surface.getHolder().setFormat(PixelFormat.TRANSLUCENT);
    surface.getHolder().addCallback(this);
    addView(surface, new LayoutParams(-1, -1));
    message = new TextView(context);
    message.setGravity(Gravity.CENTER);
    message.setText(R.string.qs_preview_loading);
    addView(message, new LayoutParams(-1, -1));
    thread.start();
    worker = new Handler(thread.getLooper());
  }

  void start() {
    if (active) return;
    active = true;
    getContext()
        .getContentResolver()
        .registerContentObserver(ConfigProvider.STATUS_URI, false, observer);
    getContext().getContentResolver().notifyChange(ConfigProvider.URI, null);
    connect();
  }

  void stop() {
    if (!active) return;
    active = false;
    generation++;
    getContext().getContentResolver().unregisterContentObserver(observer);
    worker.post(this::release);
  }

  void destroy() {
    stop();
    worker.post(thread::quitSafely);
  }

  private void connect() {
    if (!active || !created || !hasWindowFocus() || getWidth() <= 0 || getHeight() <= 0) return;
    int request = ++generation, width = getWidth(), height = getHeight();
    IBinder hostToken = surface.getHostToken();
    int display = getDisplay().getDisplayId();
    worker.post(
        () -> {
          SurfaceControlViewHost.SurfacePackage pkg = null;
          String state = "UNAVAILABLE";
          try {
            Bundle b =
                getContext()
                    .getContentResolver()
                    .call(ConfigProvider.URI, QuickPanelPreviewIpc.BACKEND, null, null);
            IBinder next = b == null ? null : b.getBinder("backend");
            if (next != null && next.isBinderAlive()) {
              release();
              backend = next;
              Parcel data = Parcel.obtain(), reply = Parcel.obtain();
              try {
                data.writeInterfaceToken(QuickPanelPreviewIpc.DESCRIPTOR);
                data.writeStrongBinder(client);
                data.writeStrongBinder(hostToken);
                data.writeInt(display);
                data.writeInt(width);
                data.writeInt(height);
                if (!backend.transact(QuickPanelPreviewIpc.CREATE, data, reply, 0))
                  throw new RemoteException("Preview unavailable");
                reply.readException();
                state = reply.readString();
                pkg = reply.readTypedObject(SurfaceControlViewHost.SurfacePackage.CREATOR);
              } finally {
                data.recycle();
                reply.recycle();
              }
            }
          } catch (Exception e) {
            state = "UNAVAILABLE";
            release();
          }
          final var result = pkg;
          final String status = state;
          main.post(
              () -> {
                try {
                  if (request != generation || !active || !created) return;
                  if (result != null) {
                    surface.setChildSurfacePackage(result);
                    message.setVisibility(GONE);
                  } else {
                    message.setVisibility(VISIBLE);
                    message.setText(
                        "WAITING".equals(status)
                            ? R.string.qs_preview_loading
                            : R.string.qs_preview_unavailable);
                  }
                } finally {
                  if (result != null) result.release();
                }
              });
        });
  }

  private void release() {
    if (backend == null) return;
    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(QuickPanelPreviewIpc.DESCRIPTOR);
      data.writeStrongBinder(client);
      backend.transact(QuickPanelPreviewIpc.RELEASE, data, reply, 0);
    } catch (RemoteException ignored) {
    } finally {
      data.recycle();
      reply.recycle();
      backend = null;
    }
  }

  @Override
  public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if (focused) connect();
    else {
      generation++;
      worker.post(this::release);
    }
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    created = true;
    connect();
  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
    connect();
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    created = false;
    generation++;
    worker.post(this::release);
  }
}
