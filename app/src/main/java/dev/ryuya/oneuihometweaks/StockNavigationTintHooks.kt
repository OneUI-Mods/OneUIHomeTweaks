package dev.ryuya.oneuihometweaks

import android.animation.ArgbEvaluator
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.LruCache
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

internal object StockNavigationTintHooks {
    fun install(loader: ClassLoader) {
        val drawable = ModernHook.findClass(
            "com.android.systemui.navigationbar.views.buttons.KeyButtonDrawable", loader,
        )
        val state = ModernHook.findClass("${drawable.name}\$ShadowDrawableState", loader)
        val iconState = drawable.animationField("mState", state)
        val layers = drawable.animationField("mLayerDrawable", LayerDrawable::class.java)
        val paint = drawable.animationField("mIconPaint", Paint::class.java)
        val vector = drawable.animationField("mAnimatedDrawable", AnimatedVectorDrawable::class.java)
        val light = state.animationField("mLightColor", Integer.TYPE)
        val dark = state.animationField("mDarkColor", Integer.TYPE)
        val oval = state.animationField("mOvalBackgroundColor", Color::class.java)
        val intensity = state.animationField("mDarkIntensity", Float::class.javaPrimitiveType!!)
        val updateShadow = drawable.compatibleDeclaredMethod("updateShadowAlpha")
        val setIntensity = drawable.compatibleDeclaredMethod("setDarkIntensity", Float::class.javaPrimitiveType!!)
        val setFilter = drawable.compatibleDeclaredMethod("setColorFilter", ColorFilter::class.java)
        check(Drawable::class.java.isAssignableFrom(drawable))
        check(setIntensity.returnType == Void.TYPE && setFilter.returnType == Void.TYPE && updateShadow.returnType == Void.TYPE)

        val evaluator = ArgbEvaluator()
        val iconFilters = StockTintFilters(PorterDuff.Mode.SRC_ATOP)
        val vectorFilters = StockTintFilters(PorterDuff.Mode.SRC_IN)
        val colorHook = ModernHook.aroundMethod(setFilter) { chain ->
            val owner = chain.thisObject as Drawable
            if (owner.javaClass != drawable) return@aroundMethod chain.proceed()
            val image = vector.get(owner) as? AnimatedVectorDrawable ?: return@aroundMethod chain.proceed()
            val settings = iconState.get(owner)
            if (oval.get(settings) == null) return@aroundMethod chain.proceed()

            (paint.get(owner) as Paint).colorFilter = chain.args[0] as ColorFilter?
            image.colorFilter = vectorFilters.get(light.getInt(settings))
            owner.invalidateSelf()
            null
        }
        try {
            ModernHook.aroundMethod(setIntensity) { chain ->
                val owner = chain.thisObject as Drawable
                // Layer-backed buttons have their own Samsung tint path. Defer to it without
                // initializing BasicRune while SystemUIApplication is still attaching.
                if (owner.javaClass != drawable || layers.get(owner) != null) {
                    return@aroundMethod chain.proceed()
                }
                val settings = iconState.get(owner)
                val progress = chain.args[0] as Float
                intensity.setFloat(settings, progress)
                val color = evaluator.evaluate(progress, light.getInt(settings), dark.getInt(settings)) as Int
                updateShadow.invoke(owner)
                owner.colorFilter = iconFilters.get(color)
                null
            }
        } catch (error: Exception) {
            colorHook.unhook()
            throw error
        }
    }
}

/** Filters contain only an immutable ARGB value and a blending mode. */
internal class StockTintFilters(private val mode: PorterDuff.Mode) {
    private val cache = LruCache<Int, PorterDuffColorFilter>(32)

    fun get(color: Int): PorterDuffColorFilter {
        return cache[color] ?: PorterDuffColorFilter(color, mode).also { cache.put(color, it) }
    }
}
