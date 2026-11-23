package dev.ryuya.oneuihometweaks

import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.Trace
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.reflect.Method

/** Avoids updating composited or transparent parts of the stock icon during a detached transition. */
internal class LauncherHiddenIconViews(player: Class<*>) {
    enum class Transition(val traceName: String) { OPENING("OneUI open"), CLOSING("OneUI closing") }
    private val container = player.compatibleDeclaredMethod("getContainerView").apply { isAccessible = true }
    private val center = container.returnType.compatibleDeclaredMethod("getCenterMostView").apply { isAccessible = true }
    private val isWidget = player.compatibleDeclaredField("isWidget").apply { isAccessible = true }
    private val widget = player.compatibleDeclaredField("containerWidgetView").apply { isAccessible = true }
    private val iconUpdates = listOf("applyContainer", "applyForeground", "applyBackground").map { name ->
        player.compatibleDeclaredMethod(name).apply { isAccessible = true }
    }
    private val main = Looper.getMainLooper()
    private var hiddenOwner: Any? = null
    private var transition: Transition? = null

    fun install(frame: Method, handles: MutableList<ModernHook.Handle>) {
        check(ModernHook.deoptimize(frame)) { "Stock floating updates may be inlined" }
        for (method in iconUpdates) {
            val openingMarker = "OneUI open bypass ${method.name}"
            val closingMarker = "OneUI closing bypass ${method.name}"
            val isBackground = method.name == "applyBackground"
            handles += ModernHook.aroundMethod(method) { chain ->
                val owner = hiddenOwner
                val active = transition
                if (owner == null || chain.thisObject !== owner || Looper.myLooper() !== main ||
                    active == null || (active == Transition.CLOSING && !isBackground)
                ) {
                    return@aroundMethod chain.proceed()
                }
                if (Trace.isEnabled()) {
                    Trace.beginSection(if (active == Transition.CLOSING) closingMarker else openingMarker)
                    Trace.endSection()
                }
                null
            }
        }
    }

    fun ownsIcon(owner: Any, group: ViewGroup): Boolean =
        container.invoke(owner) === group && group.childCount == 2 && center.invoke(group) == null &&
            !isWidget.getBoolean(owner) && widget.get(owner) == null

    fun hasTransparentBackground(group: ViewGroup): Boolean {
        val view = group.getChildAt(0) ?: return false
        if (view.javaClass != View::class.java && view.javaClass != ImageView::class.java) return false
        if (view.foreground != null || (view is ImageView && view.drawable != null)) return false
        val drawable = view.background
        return drawable == null || drawable is ColorDrawable && drawable.alpha == 0
    }

    fun runFrame(owner: Any?, mode: Transition, stock: () -> Any?): Any? {
        val previous = hiddenOwner
        val previousMode = transition
        if (owner != null || previousMode == mode) {
            hiddenOwner = owner
            transition = mode.takeIf { owner != null }
        }
        val tracing = owner != null && Trace.isEnabled()
        try {
            if (tracing) Trace.beginSection("${mode.traceName} hidden view updates bypassed")
            return stock()
        } finally {
            if (tracing) Trace.endSection()
            hiddenOwner = previous
            transition = previousMode
        }
    }

    fun restore(owner: Any, group: ViewGroup, mode: Transition) {
        if (!group.isAttachedToWindow || !ownsIcon(owner, group)) return
        val parent = group.parent as? ViewGroup
        val suppress = parent != null && !parent.isLayoutSuppressed
        if (suppress) parent.suppressLayout(true)
        val previousOwner = hiddenOwner
        hiddenOwner = null
        Trace.beginSection("${mode.traceName} restore stock view geometry")
        try {
            if (mode == Transition.CLOSING) iconUpdates.last().invoke(owner)
            else for (update in iconUpdates) update.invoke(owner)
        } finally {
            Trace.endSection()
            hiddenOwner = previousOwner
            if (suppress) parent.suppressLayout(false)
        }
    }
}
