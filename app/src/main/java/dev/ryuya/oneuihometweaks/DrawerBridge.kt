package dev.ryuya.oneuihometweaks

import android.graphics.Color
import android.view.View
import android.view.View.OnAttachStateChangeListener
import java.lang.reflect.Method
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
        val icon = Class.forName("com.honeyspace.ui.common.iconview.IconViewImpl", false, loader)
        val style = Class.forName("com.honeyspace.sdk.source.entity.ItemStyle", false, loader)
        setColor = icon.getMethod("setCustomLabelColor", Int::class.javaObjectType)
        getColor = icon.getMethod("getCustomLabelColor")
        ModernHook.hookAllConstructors(
            icon,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (p.hasThrowable()) return
                    val view: View? = p.thisObject as View?
                    views.put(view, DrawerBridge.Saved())
                    view!!.addOnAttachStateChangeListener(
                        object : OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                apply(v)
                            }

                            override fun onViewDetachedFromWindow(v: View) {}
                        }
                    )
                }
            },
        )
        ModernHook.hookMethod(
            icon.getMethod("setItemStyle", style),
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (!p.hasThrowable()) apply(p.thisObject as View)
                }
            },
        )
        HookEntry.log("DRAWER_HOOK_READY native custom-label API")
    }

    fun setMode(mode: Int, active: Boolean) {
        this.mode = mode
        this.active = active
        for (view in ArrayList<View?>(views.keys)) apply(view!!)
    }

    private fun drawer(view: View): Boolean {
        var parent = view.getParent()
        while (parent is View) {
            val name = parent.javaClass.getName()
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
                saved = DrawerBridge.Saved()
                views.put(view, saved)
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
