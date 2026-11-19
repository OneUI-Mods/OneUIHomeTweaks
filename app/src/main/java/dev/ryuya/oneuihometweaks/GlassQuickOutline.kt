package dev.ryuya.oneuihometweaks

import dev.ryuya.oneuihometweaks.compat.DexSymbols

/** Resolves the same corner geometry as Samsung's clip, including RTL and animated sizes. */
internal class GlassQuickOutline(private val loader: ClassLoader) {
    private fun resolve(name: String) = DexSymbols.findClass(name, false, loader)

    private val shape = resolve("androidx.compose.ui.graphics.Shape")
    private val direction = resolve("androidx.compose.ui.unit.LayoutDirection")
    private val density = resolve("androidx.compose.ui.unit.Density")
    private val createOutline = shape.getMethod("createOutline-Pq9zytI", Long::class.javaPrimitiveType, direction, density)
    private val rounded = resolve("androidx.compose.ui.graphics.Outline\$Rounded")
    private val roundRect = rounded.getField("roundRect")
    private val radiusFields = listOf("topLeft", "topRight", "bottomRight", "bottomLeft").map {
        roundRect.type.getField(it + "CornerRadius")
    }
    private val bounds = listOf("left", "top", "right", "bottom").map(roundRect.type::getField)

    data class Geometry(val left: Float, val top: Float, val right: Float, val bottom: Float, val radii: FloatArray)

    fun read(nativeShape: Any, size: Long, layoutDirection: Any, drawScope: Any): Geometry? {
        val outline = createOutline.invoke(nativeShape, size, layoutDirection, drawScope)
        if (!rounded.isInstance(outline)) return null
        val rect = roundRect.get(outline)
        val radii = FloatArray(8)
        for ((index, field) in radiusFields.withIndex()) {
            val pair = field.getLong(rect)
            radii[2 * index] = Float.fromBits((pair ushr 32).toInt())
            radii[2 * index + 1] = Float.fromBits(pair.toInt())
        }
        return Geometry(bounds[0].getFloat(rect), bounds[1].getFloat(rect), bounds[2].getFloat(rect), bounds[3].getFloat(rect), radii)
    }
}
