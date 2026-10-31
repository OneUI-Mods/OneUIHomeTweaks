package dev.ryuya.oneuihometweaks

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.WeakHashMap
import kotlin.jvm.JvmRecord
import kotlin.math.round

/* AZHL View contract. Does not share the Android 17 Compose assumptions. */
class QuickPanelAndroid16(owner: QuickPanelHooks, loader: ClassLoader?) : QuickPanelHooks.Adapter {
    private val owner: QuickPanelHooks
    private val bars: MutableMap<Any?, QuickPanelAndroid16.Contract?> =
        WeakHashMap<Any?, QuickPanelAndroid16.Contract?>()
    private val originalScales: MutableMap<View?, FloatArray?> = WeakHashMap<View?, FloatArray?>()
    private var refreshing: Boolean = false

    @JvmRecord
    private data class Contract(
        val kind: Int,
        val update: Method?,
        val container: Field?,
        val tiles: Field?,
        val brightnessController: Field?,
        val sliderHeight: Method?,
        val slider: Field?,
    ) {
        override fun toString(): String =
            "Contract[kind=$kind, update=$update, container=$container, tiles=$tiles, brightnessController=$brightnessController, sliderHeight=$sliderHeight, slider=$slider]"
    }

    init {
        this.owner = owner
        for (kind in 0..1) {
            val type =
                Class.forName(
                    "com.android.systemui.qs.bar." +
                        (if (kind == 0) "BrightnessBar" else "VolumeBar"),
                    false,
                    loader,
                )
            val controller: Field? =
                if (kind == 0) type.getDeclaredField("mSecBrightnessSliderController") else null
            val height: Method? =
                if (controller != null)
                    controller
                        .getType()
                        .getDeclaredMethod("updateSliderHeight", Int::class.javaPrimitiveType)
                else null
            val c =
                QuickPanelAndroid16.Contract(
                    kind,
                    type.getDeclaredMethod("updateHeightMargins"),
                    type.getDeclaredField("mSliderContainer"),
                    type.getDeclaredField("mTileLayout"),
                    controller,
                    height,
                    if (kind == 1) type.getDeclaredField("mSlider") else null,
                )
            // Both native classes recreate their track geometry in updateLayout; clone paths use it
            // too.
            var layout: Method? = null
            for (candidate in type.getDeclaredMethods()) {
                if (candidate.getName() == "updateLayout" && candidate.getParameterCount() == 4)
                    layout = candidate
            }
            if (layout == null) throw NoSuchMethodException("View bar layout")
            owner.add(
                ModernHook.hookMethod(
                    layout,
                    object : ModernHook.Callback() {
                        override fun afterHookedMethod(p: ModernHook.Param) {
                            if (p.hasThrowable()) return
                            bars.put(p.thisObject, c)
                            try {
                                apply(
                                    p.thisObject,
                                    c,
                                    p.args[if (c.kind == 0) 3 else 2] as ViewGroup?,
                                    p.args[if (c.kind == 0) 2 else 1] as LinearLayout?,
                                )
                            } catch (error: Throwable) {
                                owner.fail(error)
                            }
                        }
                    },
                )
            )
        }
    }

    @Throws(Exception::class)
    private fun apply(
        bar: Any?,
        c: QuickPanelAndroid16.Contract,
        track: ViewGroup?,
        tiles: LinearLayout?,
    ) {
        if (track == null) return
        val lp = track.getLayoutParams()
        if (lp.height <= 0) return
        val nativeHeight = lp.height
        val percent = owner.size(c.kind)
        if (percent != 100) {
            val density = track.getResources().getDisplayMetrics().density
            val target =
                Math.round(QuickPanelPolicy.dimension(nativeHeight / density, percent) * density)
            lp.height = target
            track.setLayoutParams(lp)
            if (c.brightnessController != null) {
                val controller = c.brightnessController.get(bar)
                if (controller != null) c.sliderHeight!!.invoke(controller, target)
            } else {
                val slider = c.slider!!.get(bar)
                if (slider is SeekBar) slider.setMaxHeight(target)
            }
        }
        if (tiles == null) return
        val button = owner.size(c.kind + 2)
        for (i in 0 until tiles.getChildCount()) {
            val child = tiles.getChildAt(i)
            if (button == 100) {
                val nativeScale = originalScales.remove(child)
                if (nativeScale != null) {
                    child.setScaleX(nativeScale[0])
                    child.setScaleY(nativeScale[1])
                }
            } else {
                val nativeScale =
                    originalScales.computeIfAbsent(child) { v: View? ->
                        floatArrayOf(v!!.getScaleX(), v!!.getScaleY())
                    }
                val factor: Float = button / 100f
                child.setScaleX(nativeScale!![0] * factor)
                child.setScaleY(nativeScale!![1] * factor)
            }
        }
    }

    override fun refresh() {
        if (refreshing) return
        refreshing = true
        try {
            for (entry in
                ArrayList<MutableMap.MutableEntry<Any?, QuickPanelAndroid16.Contract?>?>(
                    bars.entries
                )) entry!!.value!!.update!!.invoke(entry!!.key)
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException(error)
        } finally {
            refreshing = false
        }
    }

    override fun close() {
        for (entry in
            ArrayList<MutableMap.MutableEntry<View?, FloatArray?>?>(originalScales.entries)) {
            entry!!.key!!.setScaleX(entry!!.value!![0])
            entry!!.key!!.setScaleY(entry!!.value!![1])
        }
        originalScales.clear()
        refresh()
    }
}
