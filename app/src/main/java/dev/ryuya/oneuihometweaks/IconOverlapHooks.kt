package dev.ryuya.oneuihometweaks

import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Field

/** Corrects Home Up's closing overlap test without changing icon alpha or native animations. */
class IconOverlapHooks {
    @Volatile private var enabled = false
    @Volatile private var attempted = false
    @Volatile private var ready = false
    @Volatile private var failed = false
    private var prevented = 0L
    private var changed: Runnable? = null
    private val scope = ThreadLocal<Any?>()
    private val handles = mutableListOf<ModernHook.Handle>()

    // Independently reviewed contracts. Unknown Android releases retain native behavior.
    private data class Contract(val operator: String, val bounds: String, val launched: String)

    private fun contract(): Contract =
        when (Build.VERSION.SDK_INT) {
            36 -> Contract(OPERATOR, BOUNDS, "launchedView")
            37 -> Contract(OPERATOR, BOUNDS, "launchedView")
            else -> throw UnsupportedOperationException("Unreviewed icon overlap platform")
        }

    fun setChanged(listener: Runnable) {
        changed = listener
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun diagnostic(): String =
        when {
            failed -> "unavailable; native behavior"
            !enabled -> "off"
            ready -> "active prevented=$prevented"
            else -> "waiting for Home Up"
        }

    /** Home Up has its own plugin loader, discovered from the real home workspace. */
    fun observe(workspace: ViewGroup) {
        if (attempted) return
        attempted = true
        val loader = workspace.javaClass.classLoader
        Thread(
                {
                    try {
                        install(loader)
                        ready = true
                        HookEntry.log("ICON_OVERLAP_READY sdk=${Build.VERSION.SDK_INT}")
                    } catch (error: Throwable) {
                        handles.forEach { it.unhook() }
                        handles.clear()
                        stop(error)
                    }
                    changed?.run()
                },
                "HomeIconOverlap",
            )
            .start()
    }

    private fun install(loader: ClassLoader?) {
        val contract = contract()
        val operator = Class.forName(contract.operator, false, loader)
        val bounds = Class.forName(contract.bounds, false, loader)
        val container =
            Class.forName("com.honeyspace.ui.honeypots.sticker.StickerContainer", false, loader)
        val icon = Class.forName("com.honeyspace.sdk.transition.AnimatableIconView", false, loader)
        val homeCell =
            Class.forName(
                "com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridCellLayout",
                false,
                loader,
            )
        val launched = operator.getDeclaredField(contract.launched)
        check(View::class.java.isAssignableFrom(launched.type)) { "Overlap source type changed" }
        launched.isAccessible = true
        val hide =
            operator.getDeclaredMethod(
                "hideOverlapStickers",
                Rect::class.java,
                container,
                List::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
        val intersects =
            bounds.getDeclaredMethod(
                "isIntersect",
                View::class.java,
                View::class.java,
                Int::class.javaPrimitiveType,
                Rect::class.java,
                Boolean::class.javaPrimitiveType,
            )
        check(
            hide.returnType == Void.TYPE &&
                intersects.returnType == Boolean::class.javaPrimitiveType
        ) {
            "Overlap method contract changed"
        }
        // The native caller may contain an AOT-inlined intersection test.
        check(ModernHook.deoptimize(hide)) { "Cannot deoptimize overlap caller" }
        handles +=
            ModernHook.aroundMethod(hide) { chain ->
                val previous = scope.get()
                if (
                    enabled &&
                        !failed &&
                        chain.args[4] == false &&
                        Looper.myLooper() == Looper.getMainLooper()
                ) {
                    scope.set(chain.thisObject)
                } else {
                    // A nested opening operation must not inherit a closing correction scope.
                    scope.remove()
                }
                try {
                    chain.proceed()
                } finally {
                    if (previous == null) scope.remove() else scope.set(previous)
                }
            }
        handles +=
            ModernHook.hookMethod(
                intersects,
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(param: ModernHook.Param) {
                        if (!enabled || failed) return
                        val owner = scope.get() ?: return
                        try {
                            if (shouldKeepVisible(param, owner, launched, icon, homeCell)) {
                                param.setResult(false)
                                prevented++
                                if (prevented <= 5)
                                    HookEntry.log("ICON_OVERLAP_REJECTED count=$prevented")
                                changed?.run()
                            }
                        } catch (error: Throwable) {
                            stop(error)
                        }
                    }
                },
            )
    }

    private fun shouldKeepVisible(
        param: ModernHook.Param,
        owner: Any,
        launched: Field,
        icon: Class<*>,
        homeCell: Class<*>,
    ): Boolean {
        val candidate = param.args[0] as? View ?: return false
        val parent = param.args[1] as? ViewGroup ?: return false
        if (!homeCell.isInstance(parent)) return false
        val source = launched.get(owner) as? View ?: return false
        if (source === candidate || !icon.isInstance(source) || !icon.isInstance(candidate))
            return false
        if (source.parent !== parent || candidate.parent !== parent) return false
        if (!source.isAttachedToWindow || !candidate.isAttachedToWindow) return false
        // Ignore the common parent's moving transform. Include each icon's own scale,
        // rotation and translation, and the entire label area, so real overlaps stay native.
        return IconOverlapPolicy.definitelySeparate(localBounds(source), localBounds(candidate))
    }

    private fun localBounds(view: View): IconOverlapPolicy.Bounds {
        val rect = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        view.matrix.mapRect(rect)
        rect.offset(view.left.toFloat(), view.top.toFloat())
        return IconOverlapPolicy.Bounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun stop(error: Throwable) {
        failed = true
        HookEntry.log("ICON_OVERLAP_DISABLED ${error.javaClass.simpleName}: ${error.message}")
        changed?.run()
    }

    companion object {
        private const val OPERATOR = "com.honeyspace.ui.honeypots.sticker.StickerOperator"
        private const val BOUNDS = "com.honeyspace.ui.honeypots.sticker.ViewBoundsUtil"
    }
}
