package dev.ryuya.oneuihometweaks

import android.graphics.Color
import android.view.View
import android.view.View.OnAttachStateChangeListener
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleMethod
import dev.ryuya.oneuihometweaks.compat.compatibleName
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.WeakHashMap
import kotlin.jvm.JvmField
import kotlin.math.log

/* Optional drawer-only custom label override using Samsung's own icon API. */
class DrawerBridge {
    private var mode: Int = ColorPolicy.OFF
    private var active: Boolean = false
    private var setColor: Method? = null
    private var getColor: Method? = null
    private val views: MutableMap<View?, DrawerBridge.Saved?> =
        WeakHashMap<View?, DrawerBridge.Saved?>()

    @Throws(Exception::class)
    fun install(loader: ClassLoader?) {
        val icon =
            DexSymbols.findClass("com.honeyspace.ui.common.iconview.IconViewImpl", false, loader)
        val style =
            DexSymbols.findClass("com.honeyspace.sdk.source.entity.ItemStyle", false, loader)
        setColor = icon.compatibleMethod("setCustomLabelColor", Int::class.javaObjectType)
        getColor = icon.compatibleMethod("getCustomLabelColor")
        ModernHook.hookAllConstructors(
            icon,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (p.hasThrowable()) return
                    watch(p.thisObject as View)
                }
            },
        )
        ModernHook.hookMethod(
            icon.compatibleMethod("setItemStyle", style),
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (!p.hasThrowable()) apply(p.thisObject as View)
                }
            },
        )
        HookEntry.log("DRAWER_HOOK_READY native custom-label API")
    }

    private val attachment = object : OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { apply(view) }
        override fun onViewDetachedFromWindow(view: View) = Unit
    }

    private fun watch(view: View) {
        if (views.containsKey(view)) return
        views[view] = Saved()
        view.addOnAttachStateChangeListener(attachment)
    }

    internal fun stateForReload(): Array<WeakReference<View>> =
        views.keys.filterNotNull().map { WeakReference(it) }.toTypedArray()

    internal fun reconnect(state: Any?) {
        for (value in (state as? Array<*>).orEmpty()) {
            ((value as? WeakReference<*>)?.get() as? View)?.let(::watch)
        }
    }

    internal fun closeForReload() {
        active = false
        for ((view, saved) in views.toList()) {
            if (view == null) continue
            view.removeOnAttachStateChangeListener(attachment)
            if (saved?.applied != null) runCatching {
                if (getColor?.invoke(view) == saved.applied) setColor?.invoke(view, saved.original)
            }
        }
        views.clear()
    }

    fun setMode(mode: Int, active: Boolean) {
        this.mode = mode
        this.active = active
        for (view in ArrayList<View?>(views.keys)) apply(view!!)
    }

    private fun drawer(view: View): Boolean {
        var parent = view.getParent()
        while (parent is View) {
            val name = parent.javaClass.compatibleName()
            if (
                name.startsWith("com.honeyspace.ui.honeypots.applist.presentation.") ||
                    name.startsWith("com.honeyspace.ui.honeypots.verticalapplist.presentation.")
            )
                return true
            parent = parent.getParent()
        }
        return false
    }

    private fun apply(view: View) {
        if (!view.isAttachedToWindow()) return
        try {
            var saved = views.get(view)
            if (saved == null) {
                watch(view)
                saved = requireNotNull(views[view])
            }
            var desired: Int? = null
            if (active && drawer(view)) {
                if (mode == ColorPolicy.BLACK) desired = Color.BLACK
                else if (mode == ColorPolicy.WHITE) desired = Color.WHITE
            }
            val current: Int? = getColor!!.invoke(view) as Int?
            if (desired != null) {
                if (saved.applied == null || current != saved.applied) saved.original = current
                setColor!!.invoke(view, desired)
                saved.applied = desired
            } else if (saved.applied != null) {
                if (current == saved.applied) setColor!!.invoke(view, saved.original)
                saved.applied = null
            }
        } catch (t: Throwable) {
            HookEntry.log("DRAWER_ERROR " + t.javaClass.getSimpleName())
        }
    }

    private class Saved {
        @JvmField var original: Int? = null
        @JvmField var applied: Int? = null
    }
}
