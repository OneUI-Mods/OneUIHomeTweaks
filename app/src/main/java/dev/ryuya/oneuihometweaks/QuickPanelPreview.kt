package dev.ryuya.oneuihometweaks

import android.content.Context
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.util.AttributeSet
import android.view.Gravity
import android.view.SurfaceControlViewHost.SurfacePackage
import android.view.SurfaceHolder
import android.view.SurfaceHolder.Callback
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams
import android.widget.TextView

/* Embeds SystemUI's own native rendering surface; contains no imitation of its controls. */
class QuickPanelPreview(context: Context?, attrs: AttributeSet?) :
    FrameLayout(context!!, attrs), Callback {
    private val surface: SurfaceView
    private val message: TextView
    private val main: Handler = Handler(Looper.getMainLooper())
    private val thread: HandlerThread = HandlerThread("QuickPanelPreviewClient")
    private val worker: Handler
    private val client: IBinder = Binder()
    private var backend: IBinder? = null
    private var generation: Int = 0
    private var active: Boolean = false
    private var created: Boolean = false
    private val observer: ContentObserver =
        object : ContentObserver(main) {
            override fun onChange(self: Boolean) {
                connect()
            }
        }

    init {
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
        surface = SurfaceView(context)
        surface.setZOrderOnTop(true)
        surface.getHolder().setFormat(PixelFormat.TRANSLUCENT)
        surface.getHolder().addCallback(this)
        addView(surface, LayoutParams(-1, -1))
        message = TextView(context)
        message.setGravity(Gravity.CENTER)
        message.setText(R.string.qs_preview_loading)
        addView(message, LayoutParams(-1, -1))
        thread.start()
        worker = Handler(thread.getLooper())
    }

    fun start() {
        if (active) return
        active = true
        getContext()
            .getContentResolver()
            .registerContentObserver(ConfigProvider.STATUS_URI!!, false, observer)
        getContext().getContentResolver().notifyChange(ConfigProvider.URI!!, null)
        connect()
    }

    fun stop() {
        if (!active) return
        active = false
        generation++
        getContext().getContentResolver().unregisterContentObserver(observer)
        worker.post(Runnable { this.release() })
    }

    fun destroy() {
        stop()
        worker.post(Runnable { thread.quitSafely() })
    }

    private fun connect() {
        if (!active || !created || !hasWindowFocus() || getWidth() <= 0 || getHeight() <= 0) return
        val request: Int = ++generation
        val width = getWidth()
        val height = getHeight()
        val hostToken = surface.getHostToken()
        val display = getDisplay().getDisplayId()
        worker.post(
            Runnable {
                var pkg: SurfacePackage? = null
                var state: String = "UNAVAILABLE"
                try {
                    val b =
                        getContext()
                            .getContentResolver()
                            .call(ConfigProvider.URI!!, QuickPanelPreviewIpc.BACKEND, null, null)
                    val next: IBinder? = if (b == null) null else b.getBinder("backend")
                    if (next != null && next.isBinderAlive()) {
                        release()
                        backend = next
                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        try {
                            data.writeInterfaceToken(QuickPanelPreviewIpc.DESCRIPTOR)
                            data.writeStrongBinder(client)
                            data.writeStrongBinder(hostToken)
                            data.writeInt(display)
                            data.writeInt(width)
                            data.writeInt(height)
                            if (!backend!!.transact(QuickPanelPreviewIpc.CREATE, data, reply, 0))
                                throw RemoteException("Preview unavailable")
                            reply.readException()
                            state = (reply.readString())!!
                            pkg = reply.readTypedObject<SurfacePackage?>(SurfacePackage.CREATOR)
                        } finally {
                            data.recycle()
                            reply.recycle()
                        }
                    }
                } catch (e: Exception) {
                    state = "UNAVAILABLE"
                    release()
                }
                val result: SurfacePackage? = pkg
                val status: String? = state
                main.post(
                    Runnable {
                        try {
                            if (request != generation || !active || !created) return@Runnable
                            if (result != null) {
                                surface.setChildSurfacePackage(result)
                                message.setVisibility(View.GONE)
                            } else {
                                message.setVisibility(View.VISIBLE)
                                message.setText(
                                    if ("WAITING" == status) R.string.qs_preview_loading
                                    else R.string.qs_preview_unavailable
                                )
                            }
                        } finally {
                            if (result != null) result.release()
                        }
                    }
                )
            }
        )
    }

    private fun release() {
        if (backend == null) return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(QuickPanelPreviewIpc.DESCRIPTOR)
            data.writeStrongBinder(client)
            backend!!.transact(QuickPanelPreviewIpc.RELEASE, data, reply, 0)
        } catch (ignored: RemoteException) {} finally {
            data.recycle()
            reply.recycle()
            backend = null
        }
    }

    override fun onWindowFocusChanged(focused: Boolean) {
        super.onWindowFocusChanged(focused)
        if (focused) connect()
        else {
            generation++
            worker.post(Runnable { this.release() })
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        created = true
        connect()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        connect()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        created = false
        generation++
        worker.post(Runnable { this.release() })
    }
}
