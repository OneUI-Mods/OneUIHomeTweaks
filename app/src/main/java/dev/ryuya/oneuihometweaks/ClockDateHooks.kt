package dev.ryuya.oneuihometweaks

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.View
import android.widget.TextView
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.WeakHashMap
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Reorders only the date that the native status-bar clock already chose to display. */
class ClockDateHooks {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val clocks: MutableMap<TextView?, Boolean?> = WeakHashMap<TextView?, Boolean?>()
    private val hooks: MutableList<ModernHook.Handle?> = ArrayList<ModernHook.Handle?>()
    private var context: Context? = null
    private var worker: Handler? = null
    private var notifyTime: Method? = null
    private var dateText: Method? = null
    private var lastSound: Field? = null
    private var timeText: Field? = null
    private var spokenTime: Field? = null
    private var enabled: Boolean = false
    @Volatile private var failed: Boolean = false
    private var lastReport: String = ""

    fun install(app: Context, loader: ClassLoader?) {
        context = app
        try {
            // This feature uses the verified firmware contract, independently of launcher/volume
            // hooks.
            if (!HookCompatibility.matchesClockBuild(Build.VERSION.SDK_INT, Build.DISPLAY))
                throw UnsupportedOperationException("Unverified SystemUI build")
            val clock =
                Class.forName(
                    "com.android.systemui.statusbar.policy.QSClockIndicatorView",
                    false,
                    loader,
                )
            val sound =
                Class.forName(
                    "com.android.systemui.statusbar.policy.QSClockBellSound",
                    false,
                    loader,
                )
            if (!TextView::class.java.isAssignableFrom(clock))
                throw UnsupportedOperationException("Clock type changed")
            notifyTime = clock.getDeclaredMethod("notifyTimeChanged", sound)
            dateText = clock.getDeclaredMethod("getDateText", sound)
            lastSound = clock.getDeclaredField("lastQSClockBellSound")
            timeText = sound.getDeclaredField("TimeText")
            spokenTime = sound.getDeclaredField("TimeContentDescription")
            if (
                dateText!!.getReturnType() != String::class.java ||
                    lastSound!!.getType() != sound ||
                    timeText!!.getType() != String::class.java ||
                    spokenTime!!.getType() != String::class.java
            )
                throw UnsupportedOperationException("Clock date contract changed")
            hooks.add(
                ModernHook.hookMethod(
                    notifyTime!!,
                    object : ModernHook.Callback() {
                        override fun afterHookedMethod(p: ModernHook.Param) {
                            if (
                                failed ||
                                    p.hasThrowable() ||
                                    (p.thisObject !is TextView) ||
                                    Looper.myLooper() != Looper.getMainLooper()
                            )
                                return
                            try {
                                clocks.put(p.thisObject as TextView, true)
                                if (enabled) reorder(p.thisObject as TextView, p.args[0])
                            } catch (error: Throwable) {
                                disable(error)
                            }
                        }
                    },
                )
            )
            hooks.add(
                ModernHook.hookMethod(
                    clock.getDeclaredMethod("onAttachedToWindow"),
                    object : ModernHook.Callback() {
                        override fun afterHookedMethod(p: ModernHook.Param) {
                            if (failed || p.hasThrowable()) return
                            val view: TextView? = p.thisObject as TextView?
                            clocks.put(view, true)
                            main.post(Runnable { refreshClock(view!!) })
                        }
                    },
                )
            )
            val thread = HandlerThread("OneUiClockDateConfig")
            thread.start()
            worker = Handler(thread.getLooper())
            app.getContentResolver()
                .registerContentObserver(
                    ConfigProvider.URI!!,
                    false,
                    object : ContentObserver(worker) {
                        override fun onChange(self: Boolean, uri: Uri?) {
                            readConfig()
                        }
                    },
                )
            worker!!.post(Runnable { this.readConfig() })
            HookEntry.log("CLOCK_DATE_READY native notifyTimeChanged + getDateText")
        } catch (error: Throwable) {
            disable(error)
        }
    }

    private fun readConfig() {
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
                            c.getInt(c.getColumnIndexOrThrow("clock_date_left")) != 0
                    main.post(
                        Runnable {
                            if (failed) return@Runnable
                            enabled = on
                            lastReport = "" // Configuration notifications also request a fresh hook
                            // response.
                            report(
                                if (on) "ACTIVE" else "OFF",
                                if (on) "Clock date ready" else "Native clock order",
                            )
                            for (clock in ArrayList<TextView?>(clocks.keys)) refreshClock(clock!!)
                        }
                    )
                }
        } catch (error: Throwable) {
            main.post(
                Runnable {
                    if (failed) return@Runnable
                    enabled = false
                    for (clock in ArrayList<TextView?>(clocks.keys)) refreshClock(clock!!)
                    report("ERROR", "Clock configuration unavailable; native order restored")
                }
            )
        }
    }

    private fun refreshClock(view: TextView) {
        if (failed || !view.isAttachedToWindow()) return
        try {
            val sound = lastSound!!.get(view)
            if (sound != null) notifyTime!!.invoke(view, sound)
        } catch (error: Throwable) {
            disable(error)
        }
    }

    @Throws(ReflectiveOperationException::class)
    private fun reorder(view: TextView, sound: Any?) {
        if (
            !view.isAttachedToWindow() ||
                view.getDisplay() == null ||
                view.getDisplay().getDisplayId() != 0
        )
            return
        if (view.getLayoutDirection() != View.LAYOUT_DIRECTION_LTR) {
            report("UNSUPPORTED", "RTL clock keeps native order")
            return
        }
        val date: String? = dateText!!.invoke(view, sound) as String?
        val time: String? = timeText!!.get(sound) as String?
        val spoken: String? = spokenTime!!.get(sound) as String?
        val original = view.getText()
        val parts = ClockDateOrder.parts(original.toString(), time, spoken, date)
        if (parts == null) {
            // Date OFF produces time only: do not manufacture a date or override QuickStar
            // settings.
            val hidden: Boolean = original.toString() == time || original.toString() == spoken
            report(
                if (hidden) "ACTIVE" else "UNSUPPORTED",
                if (hidden) "Date hidden by system settings"
                else "Unrecognized native clock format; unchanged",
            )
            return
        }
        view.setText(ClockDateHooks.move(original, parts))
        val description = view.getContentDescription()
        if (description != null) {
            val spokenParts = ClockDateOrder.parts(description.toString(), spoken, spoken, date)
            if (spokenParts != null)
                view.setContentDescription(ClockDateHooks.move(description, spokenParts))
        }
        report("ACTIVE", "Date before time; native date visibility and format retained")
    }

    private fun disable(error: Throwable) {
        if (failed) return
        failed = true
        enabled = false
        for (hook in hooks) try {
            hook!!.unhook()
        } catch (ignored: Throwable) {}
        // Regenerate the native string after unhooking. Never touch the time source or global
        // settings.
        main.post(
            Runnable {
                for (view in ArrayList<TextView?>(clocks.keys)) try {
                    val sound = lastSound!!.get(view)
                    if (view!!.isAttachedToWindow() && sound != null)
                        notifyTime!!.invoke(view, sound)
                } catch (ignored: Throwable) {}
                report(
                    if (error is UnsupportedOperationException) "UNSUPPORTED" else "ERROR",
                    "Clock date disabled: " + error.javaClass.getSimpleName(),
                )
            }
        )
        HookEntry.log("CLOCK_DATE_DISABLED " + error)
    }

    private fun report(state: String?, detail: String?) {
        val next: String = state + ":" + detail
        if (next == lastReport) return
        lastReport = next
        HostStatus.reportFeature(context, "clock_date", state, detail)
        HookEntry.log("CLOCK_DATE " + next)
    }

    companion object {
        @JvmStatic
        private fun move(original: CharSequence, parts: ClockDateOrder.Parts): CharSequence? {
            return SpannableStringBuilder()
                .append(original, parts.dateStart, original.length)
                .append(original, parts.timeEnd, parts.dateStart)
                .append(original, 0, parts.timeEnd)
        }
    }
}
