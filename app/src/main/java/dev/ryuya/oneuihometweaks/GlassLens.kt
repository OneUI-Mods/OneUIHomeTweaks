package dev.ryuya.oneuihometweaks

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import kotlin.math.min

internal const val GLASS_CIRCULAR_BEVEL = """
    float circularBevel(float depth, float height) {
        float t = clamp(depth / max(height, 0.5), 0.0, 1.0);
        return 1.0 - sqrt(t * (2.0 - t));
    }
"""
internal const val GLASS_DOCK_BEVEL_DP = "28.0"

/** Samples a background already supplied by its owner. No display capture is initiated here. */
@RequiresApi(33)
internal class GlassLens(private val density: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shader = RuntimeShader(
        """
        uniform shader backdrop;
        uniform shader bevelBackdrop;
        uniform float4 region;
        uniform float4 corners;
        uniform float strength;
        uniform float pixelsPerDp;
        uniform float dark;
        uniform float tintFactor;
        uniform float panel;
        uniform float dock;

        $GLASS_CIRCULAR_BEVEL

        half4 main(float2 p) {
            float2 center = (region.xy + region.zw) * 0.5;
            float2 halfSize = (region.zw - region.xy) * 0.5;
            float2 local = p - center;
            float corner = local.y < 0.0
                ? (local.x < 0.0 ? corners.x : corners.y)
                : (local.x < 0.0 ? corners.w : corners.z);
            float2 q = abs(local) - halfSize + corner;
            float2 outward = max(q, 0.0);
            float dist = length(outward) + min(max(q.x, q.y), 0.0) - corner;
            if (dot(outward, outward) < 0.0001) {
                outward = q.x > q.y ? float2(1, 0) : float2(0, 1);
            }
            float2 normal = normalize(outward) * sign(local);
            float depth = max(0.0, -dist);
            float limit = min(halfSize.x, halfSize.y) * 0.88;
            float innerHeight = min(mix(25.0, $GLASS_DOCK_BEVEL_DP, dock) * pixelsPerDp, limit);
            float outerHeight = min(12.0 * pixelsPerDp, limit);
            float innerOffset = circularBevel(depth, innerHeight) * mix(15.0, 19.0, dock) * pixelsPerDp * strength;
            float outerOffset = circularBevel(depth, outerHeight) * 34.0 * pixelsPerDp * strength;
            float edgeShare = clamp(1.0 - depth / max(outerHeight, 0.5), 0.0, 1.0);
            float2 facePoint = p + normal * innerOffset;
            float2 edgePoint = p + normal * outerOffset;

            half3 body = backdrop.eval(facePoint).rgb;
            if (panel < 0.5) {
                float spread = pixelsPerDp * mix(2.1 + 5.0 * strength, 0.45 + 1.5 * strength, dock);
                float centerWeight = mix(0.40, 0.62, dock);
                float neighborWeight = (1.0 - centerWeight) * 0.25;
                body *= centerWeight;
                body += backdrop.eval(facePoint + float2(spread, 0)).rgb * neighborWeight;
                body += backdrop.eval(facePoint - float2(spread, 0)).rgb * neighborWeight;
                body += backdrop.eval(facePoint + float2(0, spread)).rgb * neighborWeight;
                body += backdrop.eval(facePoint - float2(0, spread)).rgb * neighborWeight;
            }
            half3 light = body;
            if (edgeShare > 0.0) {
                float2 fringe = normal * (1.6 * pixelsPerDp * edgeShare * strength);
                half3 edge = half3(bevelBackdrop.eval(edgePoint + fringe).r,
                    bevelBackdrop.eval(edgePoint).g, bevelBackdrop.eval(edgePoint - fringe).b);
                light = mix(body, edge, edgeShare * strength);
            }
            if (dock > 0.5) {
                // The face is drawn by GlassMaterial over the original wallpaper.
                float edgeOpacity = 1.0 - smoothstep(outerHeight * 0.33, innerHeight, depth);
                return half4(light * edgeOpacity, edgeOpacity);
            }
            float luminance = dot(light, half3(0.2126, 0.7152, 0.0722));
            float tint = strength * tintFactor *
                mix(0.14 + (1.0 - luminance) * 0.12, 0.16 + luminance * 0.27, dark);
            half3 neutral = mix(half3(0.84, 0.90, 0.94), half3(0.045, 0.068, 0.095), dark);
            half3 glass = mix(light, neutral, clamp(tint, 0.0, 1.0));
            float glassLuma = dot(glass, half3(0.2126, 0.7152, 0.0722));
            half3 limited = mix(mix(glass, half3(0.92), max(0.0, 0.61 - glassLuma)),
                glass * min(1.0, 0.47 / max(glassLuma, 0.01)), dark);
            return half4(mix(glass, limited, panel), 1.0);
        }
        """.trimIndent(),
    )

    fun draw(
        canvas: Canvas,
        backdrop: Shader,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        strength: Int,
        dark: Boolean,
        opacity: Float = 1f,
        tintFactor: Float = 1f,
        panel: Boolean = false,
        outline: Path? = null,
        bevelBackdrop: Shader = backdrop,
        radii: FloatArray? = null,
        dock: Boolean = false,
    ) {
        if (!canvas.isHardwareAccelerated || strength <= 0 || right <= left || bottom <= top) return
        val corner = radius.coerceIn(0f, min(right - left, bottom - top) / 2)
        shader.setInputShader("backdrop", backdrop)
        shader.setInputShader("bevelBackdrop", bevelBackdrop)
        shader.setFloatUniform("region", left, top, right, bottom)
        if (radii != null && radii.size >= 8) {
            val limit = min(right - left, bottom - top) / 2f
            shader.setFloatUniform("corners", min(radii[0], radii[1]).coerceIn(0f, limit),
                min(radii[2], radii[3]).coerceIn(0f, limit), min(radii[4], radii[5]).coerceIn(0f, limit),
                min(radii[6], radii[7]).coerceIn(0f, limit))
        } else {
            shader.setFloatUniform("corners", corner, corner, corner, corner)
        }
        shader.setFloatUniform("strength", strength / 100f)
        shader.setFloatUniform("pixelsPerDp", density)
        shader.setFloatUniform("dark", if (dark) 1f else 0f)
        shader.setFloatUniform("tintFactor", tintFactor)
        shader.setFloatUniform("panel", if (panel) 1f else 0f)
        shader.setFloatUniform("dock", if (dock) 1f else 0f)
        paint.alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        paint.shader = shader
        try {
            if (outline == null) canvas.drawRoundRect(left, top, right, bottom, corner, corner, paint)
            else canvas.drawPath(outline, paint)
        } finally {
            paint.shader = null
        }
    }

    fun clearBackdrop() {
        shader.setInputShader("backdrop", EmptyBackdrop)
        shader.setInputShader("bevelBackdrop", EmptyBackdrop)
    }

    private companion object {
        val EmptyBackdrop = LinearGradient(0f, 0f, 1f, 0f, 0, 0, Shader.TileMode.CLAMP)
    }
}
