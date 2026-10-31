package dev.ryuya.oneuihometweaks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.ArrayList
import kotlin.concurrent.Volatile
import kotlin.math.log

/* Configuration and failure boundary shared by independent Android 16 and 17 adapters. */
class QuickPanelHooks {
    interface Adapter {
        fun refresh()

        fun close()
    }

    private val handles: MutableList<ModernHook.Handle?> = ArrayList<ModernHook.Handle?>()
    private val main: Handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var adapter: QuickPanelHooks.Adapter? = null
    private var preview: QuickPanelNativePreview? = null
    @Volatile private var failed: Boolean = false
    @Volatile private var sizes: IntArray? = intArrayOf(100, 100, 100, 100)
    @Volatile private var enabled: Boolean = false
    @Volatile private var legacy: Boolean = false
    @Volatile private var legacyFailed: Boolean = false
    private var touched: Boolean = false
    private var lastReport: String = ""

    fun size(index: Int): Int {
        return if (enabled && !failed) sizes!![index] else 100
    }

    fun legacy(): Boolean {
        return enabled && legacy && !failed && !legacyFailed
    }

    fun needsRefresh(): Boolean {
        return touched
    }

    fun add(handle: ModernHook.Handle?) {
        handles.add(handle)
    }

    fun install(app: Context?, loader: ClassLoader?) {
        context = app
        try {
            if (!HookCompatibility.matchesClockBuild(Build.VERSION.SDK_INT, Build.DISPLAY))
                throw UnsupportedOperationException("Unreviewed Quick Panel firmware")
            adapter =
                when (Build.VERSION.SDK_INT) {
                    36 -> QuickPanelAndroid16(this, loader)
                    37 -> QuickPanelAndroid17(this, loader)
                    else -> throw UnsupportedOperationException("Unreviewed Quick Panel platform")
                }
            try {
                preview = QuickPanelNativePreview(context!!, loader, adapter)
            } catch (error: Throwable) {
                HookEntry.log("QUICK_PANEL_PREVIEW_UNAVAILABLE " + error.javaClass.getSimpleName())
            }
            val thread = HandlerThread("OneUiQuickPanelConfig")
            thread.start()
            val worker = Handler(thread.getLooper())
            context!!
                .getContentResolver()
                .registerContentObserver(
                    ConfigProvider.URI!!,
                    false,
                    object : ContentObserver(worker) {
                        override fun onChange(self: Boolean, uri: Uri?) {
                            read()
                        }
                    },
                )
            context!!.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        worker.post(Runnable { this@QuickPanelHooks.read() })
                    }
                },
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
                Context.RECEIVER_NOT_EXPORTED,
            )
            worker.post(Runnable { this.read() })
            HookEntry.log("QUICK_PANEL_READY platform=" + Build.VERSION.SDK_INT)
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun read() {
        if (failed) return
        try {
            context!!
                .getContentResolver()
                .query(ConfigProvider.URI!!, null, null, null, null)
                .use { c ->
                    check(!(c == null || !c.moveToFirst())) { "No configuration" }
                    val on: Boolean =
                        c.getInt(c.getColumnIndexOrThrow("enabled")) != 0 &&
                            c.getInt(c.getColumnIndexOrThrow("systemui_enabled")) != 0 &&
                            c.getInt(c.getColumnIndexOrThrow("qs_enabled")) != 0
                    val oldDesign: Boolean =
                        c.getInt(c.getColumnIndexOrThrow("qs_legacy_design")) != 0
                    val next = IntArray(4)
                    for (i in 0..3) next[i] =
                        QuickPanelPolicy.percent(
                            c.getInt(c.getColumnIndexOrThrow(QuickPanelPolicy.KEYS!![i]))
                        )
                    main.post(
                        Runnable {
                            if (failed) return@Runnable
                            touched = touched or on
                            sizes = next
                            enabled = on
                            legacy = oldDesign
                            try {
                                adapter!!.refresh()
                                if (preview != null) preview!!.refresh()
                                report(
                                    if (on && oldDesign && legacyFailed) "PARTIAL"
                                    else if (on) "ACTIVE" else "OFF",
                                    if (on) next.contentToString() else "Native Quick Panel sizes",
                                )
                            } catch (error: Throwable) {
                                fail(error)
                            }
                        }
                    )
                }
        } catch (error: Throwable) {
            main.post(
                Runnable {
                    enabled = false
                    try {
                        if (adapter != null) adapter!!.refresh()
                    } catch (restore: Throwable) {
                        fail(restore)
                        return@Runnable
                    }
                    report("ERROR", "Configuration unavailable; native sizes restored")
                }
            )
        }
    }

    fun report(state: String?, detail: String?) {
        val value: String = state + ":" + detail
        if (value == lastReport) return
        lastReport = value
        HostStatus.reportFeature(context, "quick_panel", state, detail)
    }

    fun stopLegacy(error: Throwable?) {
        legacyFailed = true
        report("PARTIAL", "Legacy design unavailable; size controls remain active")
        HookEntry.log("QUICK_PANEL_LEGACY_DISABLED " + error)
    }

    fun fail(error: Throwable) {
        if (failed) return
        failed = true
        enabled = false
        for (h in handles) try {
            h!!.unhook()
        } catch (ignored: Throwable) {}
        main.post(
            Runnable {
                try {
                    if (adapter != null) adapter!!.close()
                } catch (ignored: Throwable) {}
                report(
                    if (
                        error is ReflectiveOperationException ||
                            error is UnsupportedOperationException
                    )
                        "UNSUPPORTED"
                    else "ERROR",
                    "Quick Panel stopped: " + error.javaClass.getSimpleName(),
                )
            }
        )
        HookEntry.log("QUICK_PANEL_DISABLED " + error)
    }
}
