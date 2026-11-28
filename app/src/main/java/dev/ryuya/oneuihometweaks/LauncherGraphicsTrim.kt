package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.os.Handler
import android.os.Trace
import android.view.View
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/** Releases reusable drawing resources after the Launcher has stayed off-screen. */
internal class LauncherGraphicsTrim private constructor(
    private val main: Handler,
    private val transitionsIdle: () -> Boolean,
    private val trimResources: () -> Unit,
) {
    private val started = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private var home = WeakReference<Activity>(null)
    private var available = true
    private var enabled = false
    private val trim = Runnable {
        val decor = home.get()?.window?.peekDecorView() ?: return@Runnable
        if (!enabled || !available || started.isNotEmpty() || decor.windowVisibility == View.VISIBLE ||
            !transitionsIdle()
        ) return@Runnable

        val traced = Trace.isEnabled()
        if (traced) Trace.beginSection("OneUI Home hidden graphics trim")
        try {
            trimResources()
            HookEntry.log("LAUNCHER_HIDDEN_GRAPHICS_TRIM")
        } catch (error: ReflectiveOperationException) {
            available = false
            HookEntry.log("LAUNCHER_GRAPHICS_TRIM_FAILED " + error.javaClass.simpleName)
        } finally {
            if (traced) Trace.endSection()
        }
    }

    fun resumedHome(activity: Activity) {
        home = WeakReference(activity)
        started(activity)
    }

    fun started(activity: Activity) {
        if (started.add(activity)) main.removeCallbacks(trim)
    }

    fun stopped(activity: Activity) {
        if (started.remove(activity)) scheduleIfHidden()
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) scheduleIfHidden() else main.removeCallbacks(trim)
    }

    private fun scheduleIfHidden() {
        if (enabled && available && started.isEmpty() && home.get() != null) {
            main.postDelayed(trim, HIDDEN_DELAY_MS)
        }
    }

    fun destroyed(activity: Activity) {
        started.remove(activity)
        if (home.get() !== activity) return
        home.clear()
        main.removeCallbacks(trim)
    }

    fun close() {
        main.removeCallbacks(trim)
        started.clear()
        home.clear()
    }

    companion object {
        const val KEY = "home_graphics_trim"
        private const val HIDDEN_DELAY_MS = 12_000L

        fun create(main: Handler, loader: ClassLoader?, transitionsIdle: () -> Boolean): LauncherGraphicsTrim? =
            try {
                val renderer = DexSymbols.findClass("android.graphics.HardwareRenderer", loader)
                val trim = renderer.compatibleDeclaredMethod("trimCaches", Int::class.javaPrimitiveType)
                // Free unlocked GPU resources while keeping the rendering context.
                val allCaches = renderer.compatibleDeclaredField("CACHE_TRIM_ALL").getInt(null)
                LauncherGraphicsTrim(main, transitionsIdle) { trim.invoke(null, allCaches) }
            } catch (error: Exception) {
                HookEntry.log("LAUNCHER_GRAPHICS_TRIM_UNAVAILABLE " + error.javaClass.simpleName)
                null
            }
    }
}
