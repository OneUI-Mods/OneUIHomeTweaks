package dev.ryuya.oneuihometweaks

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.os.RemoteException
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.SurfaceControlViewHost.SurfacePackage
import android.view.View
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import java.util.NoSuchElementException
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile
import kotlin.math.log

/* Owns one app-bound native surface. Never moves views out of the real Quick Panel. */
class QuickPanelNativePreview(
    context: Context,
    loader: ClassLoader?,
    adapter: QuickPanelHooks.Adapter?,
) : Binder() {
    interface Renderer {
        fun ready(): Boolean

        @Throws(Exception::class) fun populate(root: FrameLayout?)

        fun clear(root: FrameLayout?)
    }

    private val context: Context
    private val main: Handler = Handler(Looper.getMainLooper())
    private val renderer: QuickPanelNativePreview.Renderer
    private val appUid: Int
    private var host: SurfaceControlViewHost? = null
    private var root: FrameLayout? = null
    private var client: IBinder? = null
    private var death: DeathRecipient? = null
    private var announcedReady: Boolean = false
    @Volatile private var failed: Boolean = false

    init {
        this.context = context
        appUid = context.getPackageManager().getApplicationInfo("dev.ryuya.oneuihometweaks", 0).uid
        renderer =
            if (Build.VERSION.SDK_INT == 37)
                QuickPanelPreviewAndroid17(
                    loader!!,
                    adapter as QuickPanelAndroid17,
                    Runnable { this.sourceChanged() },
                    Runnable { this.fail() },
                )
            else QuickPanelPreviewAndroid16(loader, Runnable { this.sourceChanged() })
        register()
    }

    private fun register() {
        val extras = Bundle()
        extras.putBinder("backend", this)
        try {
            context
                .getContentResolver()
                .call(ConfigProvider.URI!!, QuickPanelPreviewIpc.REGISTER, null, extras)
        } catch (error: RuntimeException) {
            HookEntry.log("QUICK_PANEL_PREVIEW_REGISTRATION " + error.javaClass.getSimpleName())
        }
    }

    private fun sourceChanged() {
        if (!announcedReady && renderer != null && renderer.ready()) {
            announcedReady = true
            main.post(Runnable { this.register() })
        }
    }

    private fun fail() {
        failed = true
        close()
        register()
    }

    fun refresh() {
        register()
        if (root == null || failed) return
        try {
            renderer.clear(root)
            renderer.populate(root)
        } catch (error: Throwable) {
            HookEntry.log("QUICK_PANEL_PREVIEW_DISABLED " + error.javaClass.getSimpleName())
            fail()
        }
    }

    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != QuickPanelPreviewIpc.CREATE && code != QuickPanelPreviewIpc.RELEASE)
            return super.onTransact(code, data, reply, flags)
        if (Binder.getCallingUid() != appUid) throw SecurityException("Module app only")
        data.enforceInterface(QuickPanelPreviewIpc.DESCRIPTOR)
        val owner = data.readStrongBinder()
        requireNotNull(owner) { "Missing client" }
        if (code == QuickPanelPreviewIpc.RELEASE) {
            main.post(
                Runnable {
                    if (owner == client) close()
                }
            )
            reply!!.writeNoException()
            return true
        }
        val token = data.readStrongBinder()
        val displayId = data.readInt()
        val width = data.readInt()
        val height = data.readInt()
        require(
            !(token == null ||
                width <= 0 ||
                height <= 0 ||
                width > QuickPanelPreviewIpc.MAX_PIXELS ||
                height > QuickPanelPreviewIpc.MAX_PIXELS)
        ) {
            "Invalid surface geometry"
        }
        val expired = AtomicBoolean()
        val task =
            FutureTask<SurfacePackage?>(
                Callable {
                    close()
                    if (failed || !renderer.ready() || !owner.isBinderAlive()) return@Callable null
                    val display =
                        context
                            .getSystemService<DisplayManager?>(DisplayManager::class.java)
                            .getDisplay(displayId)
                    requireNotNull(display) { "Missing display" }
                    client = owner
                    death = DeathRecipient {
                        main.post(
                            Runnable {
                                if (owner == client) close()
                            }
                        )
                    }
                    owner.linkToDeath(death!!, 0)
                    root =
                        object : FrameLayout(context) {
                            override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                                return true
                            }
                        }
                    root!!.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    )
                    try {
                        renderer.populate(root)
                        host = SurfaceControlViewHost(context, display, token)
                        try {
                            val params =
                                LayoutParams(
                                    width,
                                    height,
                                    LayoutParams.TYPE_APPLICATION,
                                    LayoutParams.FLAG_NOT_FOCUSABLE or
                                        LayoutParams.FLAG_NOT_TOUCHABLE,
                                    PixelFormat.TRANSLUCENT,
                                )
                            SurfaceControlViewHost::class
                                .java
                                .getMethod("setView", View::class.java, LayoutParams::class.java)
                                .invoke(host, root, params)
                        } catch (missing: NoSuchMethodException) {
                            host!!.setView(root!!, width, height)
                        }
                        val result = host!!.getSurfacePackage()
                        if (expired.get()) {
                            if (result != null) result.release()
                            close()
                            return@Callable null
                        }
                        return@Callable result
                    } catch (error: Throwable) {
                        close()
                        throw error
                    }
                }
            )
        main.post(task)
        var result: SurfacePackage? = null
        var state: String = "WAITING"
        try {
            result = task.get(3, TimeUnit.SECONDS)
            state = if (failed) "UNAVAILABLE" else if (result == null) "WAITING" else "READY"
        } catch (error: Exception) {
            expired.set(true)
            task.cancel(false)
            main.post(
                Runnable {
                    if (owner == client) close()
                }
            )
            state = "UNAVAILABLE"
            HookEntry.log("QUICK_PANEL_PREVIEW_ERROR " + error)
        }
        reply!!.writeNoException()
        reply!!.writeString(state)
        reply!!.writeTypedObject<SurfacePackage?>(result, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
        if (result != null) result.release()
        return true
    }

    private fun close() {
        val oldRoot: FrameLayout? = root
        val oldHost: SurfaceControlViewHost? = host
        root = null
        host = null
        if (client != null && death != null) {
            try {
                client!!.unlinkToDeath(death!!, 0)
            } catch (ignored: NoSuchElementException) {}
        }
        client = null
        death = null
        try {
            if (oldRoot != null) renderer.clear(oldRoot)
        } catch (error: Throwable) {
            HookEntry.log("QUICK_PANEL_PREVIEW_CLEAR " + error.javaClass.getSimpleName())
        }
        try {
            if (oldHost != null) oldHost.release()
        } catch (error: RuntimeException) {
            HookEntry.log("QUICK_PANEL_PREVIEW_RELEASE " + error.javaClass.getSimpleName())
        }
    }
}
