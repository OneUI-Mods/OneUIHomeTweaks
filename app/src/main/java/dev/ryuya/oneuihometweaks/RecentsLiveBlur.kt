package dev.ryuya.oneuihometweaks

import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList
import kotlin.jvm.JvmRecord
import kotlin.math.log

/*
 * Places the owned backdrop blur below the live app, so video colors cannot bleed outside its card.
 */
class RecentsLiveBlur(loader: ClassLoader?) {
    private val blurType: Class<*>
    private val builderType: Class<*>
    private val setRadius: Method
    private val setBackgroundBlur: Method
    private val setParent: Method
    private val setName: Method
    private val setEffect: Method
    private val build: Method
    private val cropLayer: Method
    private val relativeLayer: Method
    private val showLayer: Method
    private val hideLayer: Method
    private val removeLayer: Method
    private val viewRoot: Method
    private val rootSurface: Method
    private val rootView: Method
    private val radius: Field
    private val viewPath: Field
    private val backdrops: MutableList<RecentsLiveBlur.Backdrop?> =
        ArrayList<RecentsLiveBlur.Backdrop?>()
    private var effectLayer: SurfaceControl? = null
    private var appLeash: SurfaceControl? = null
    private var viewport: Rect? = null
    private var active: Boolean = false
    private var blurRadius: Int = 0

    @JvmRecord
    private data class Backdrop(val view: View?, val drawable: Drawable?, val radius: Int) {
        override fun toString(): String = "Backdrop[view=$view, drawable=$drawable, radius=$radius]"
    }

    init {
        blurType =
            Class.forName(
                "com.android.internal.graphics.drawable.BackgroundBlurDrawable",
                false,
                loader,
            )
        setRadius = blurType.getMethod("setBlurRadius", Int::class.javaPrimitiveType)
        radius = blurType.getDeclaredField("mBlurRadius")
        radius.setAccessible(true)
        viewPath = View::class.java.getDeclaredField("mClipPath")
        viewPath.setAccessible(true)
        viewRoot = View::class.java.getDeclaredMethod("getViewRootImpl")
        viewRoot.setAccessible(true)
        rootSurface = viewRoot.getReturnType().getMethod("getSurfaceControl")
        rootView = viewRoot.getReturnType().getMethod("getView")
        builderType = Class.forName("android.view.SurfaceControl\$Builder", false, loader)
        builderType.getConstructor()
        setParent = builderType.getMethod("setParent", SurfaceControl::class.java)
        setName = builderType.getMethod("setName", String::class.java)
        setEffect = builderType.getMethod("setEffectLayer")
        build = builderType.getMethod("build")
        cropLayer =
            Transaction::class
                .java
                .getMethod(
                    "setWindowCrop",
                    SurfaceControl::class.java,
                    Rect::class.java,
                )
        relativeLayer =
            Transaction::class
                .java
                .getMethod(
                    "setRelativeLayer",
                    SurfaceControl::class.java,
                    SurfaceControl::class.java,
                    Int::class.javaPrimitiveType,
                )
        showLayer = Transaction::class.java.getMethod("show", SurfaceControl::class.java)
        hideLayer = Transaction::class.java.getMethod("hide", SurfaceControl::class.java)
        removeLayer = Transaction::class.java.getMethod("remove", SurfaceControl::class.java)
        setBackgroundBlur =
            Transaction::class
                .java
                .getMethod(
                    "setBackgroundBlurRadius",
                    SurfaceControl::class.java,
                    Int::class.javaPrimitiveType,
                )
    }

    @Throws(Exception::class)
    fun bind(root: View, app: SurfaceControl?): Boolean {
        clear()
        collect(root, root, 0)
        if (backdrops.isEmpty()) return false
        for (b in backdrops) {
            if (viewPath.get(b!!.view) as Path? != null || b!!.radius < 0 || b!!.radius > 1000)
                return false
            if (blurRadius != 0 && b!!.radius != blurRadius) return false
            blurRadius = b!!.radius
        }
        viewport = Rect(0, 0, root.getWidth(), root.getHeight())
        appLeash = app
        if (blurRadius > 0) {
            val builder = builderType.getConstructor().newInstance()
            setName.invoke(builder, "OneUiRecentsWallpaperBlur")
            setEffect.invoke(builder)
            val owner = viewRoot.invoke(root)
            if (owner == null) return false
            val parent: SurfaceControl? = rootSurface.invoke(owner) as SurfaceControl?
            if (rootView.invoke(owner) !== root || parent == null || !parent.isValid()) return false
            setParent.invoke(builder, parent)
            effectLayer = build.invoke(builder) as SurfaceControl?
            if (!effectLayer!!.isValid()) return false
        }
        return true
    }

    @Throws(Exception::class)
    private fun collect(root: View, view: View, depth: Int) {
        if (depth > 32) return
        val drawable = view.getBackground()
        if (
            blurType.isInstance(drawable) &&
                view.getWidth() >= root.getWidth() * .9f &&
                view.getHeight() >= root.getHeight() * .9f
        )
            backdrops.add(RecentsLiveBlur.Backdrop(view, drawable, radius.getInt(drawable)))
        if (view is ViewGroup)
            for (i in 0 until view.getChildCount()) collect(root, view.getChildAt(i), depth + 1)
    }

    fun valid(): Boolean {
        for (b in backdrops) if (b!!.view!!.getBackground() !== b!!.drawable) return false
        return !backdrops.isEmpty() && (effectLayer == null || effectLayer!!.isValid())
    }

    @Throws(Exception::class)
    fun apply(tx: Transaction) {
        check(valid()) { "Native backdrop changed" }
        val first: Boolean = !active
        active = true
        if (first) for (b in backdrops) setRadius.invoke(b!!.drawable, 0)
        if (effectLayer != null) {
            cropLayer.invoke(tx, effectLayer, viewport)
            setBackgroundBlur.invoke(tx, effectLayer, blurRadius)
            relativeLayer.invoke(tx, effectLayer, appLeash, -1)
            tx.setAlpha(effectLayer!!, 1f)
            showLayer.invoke(tx, effectLayer)
        }
        if (first) HookEntry.log("RECENTS_LIVE_BLUR_SEPARATED radius=" + blurRadius)
    }

    @Throws(Exception::class)
    fun restore(tx: Transaction?) {
        if (!active) return
        for (b in backdrops) if (b!!.view!!.getBackground() === b!!.drawable)
            setRadius.invoke(b!!.drawable, b!!.radius)
        if (effectLayer != null && effectLayer!!.isValid()) hideLayer.invoke(tx, effectLayer)
        active = false
    }

    fun clear() {
        try {
            Transaction().use { tx ->
                try {
                    restore(tx)
                } catch (error: Throwable) {
                    HookEntry.log("RECENTS_LIVE_BLUR_RESTORE_ERROR " + error)
                }
                if (effectLayer != null && effectLayer!!.isValid())
                    removeLayer.invoke(tx, effectLayer)
                tx.apply()
            }
        } catch (error: Throwable) {
            HookEntry.log("RECENTS_LIVE_BLUR_REMOVE_ERROR " + error)
        }
        if (effectLayer != null) effectLayer!!.release()
        appLeash = null
        effectLayer = appLeash
        viewport = null
        backdrops.clear()
        blurRadius = 0
        active = false
    }
}
