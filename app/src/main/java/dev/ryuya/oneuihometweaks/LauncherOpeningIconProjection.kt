package dev.ryuya.oneuihometweaks

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi

/** Projects the two icon images and the stock outline clips in logical View coordinates. */
@RequiresApi(29)
internal class LauncherOpeningIconProjection private constructor(
    private val projection: LauncherIconPlaneProjection,
    private val fixedOrientation: Boolean,
    private val portrait: Boolean,
    private val staticBackgroundWidth: Int,
    private val staticBackgroundHeight: Int,
    private val groupOutline: Boolean,
    private val clipChildren: Boolean,
    private val backgroundOutline: Boolean,
) {
    fun project(state: State, background: BitmapSize?): Pose? {
        val frame = state.icon
        if (state.rotation != 0f || state.backgroundAlpha !in 0f..1f || !state.windowScale.isFinite() ||
            state.windowScale <= 0f || !state.windowRadius.isFinite() || state.windowRadius < 0f) return null
        val group = projection.projectGroup(frame, state.groupAlpha) ?: return null
        val front = projection.projectForegroundWithinGroup(frame) ?: return null
        val width: Int
        val height: Int
        if (portrait != fixedOrientation) {
            width = staticBackgroundWidth
            height = if (portrait) frame.backgroundHeight else frame.backgroundWidth
        } else {
            width = if (portrait) frame.backgroundHeight else frame.backgroundWidth
            height = staticBackgroundHeight
        }
        if (width <= 0 || height <= 0) return null
        val bounds = Rect(0, 0, width, height)
        val outlineWidth = if (fixedOrientation) frame.backgroundHeight else frame.backgroundWidth
        val outlineHeight = if (fixedOrientation) frame.backgroundWidth else frame.backgroundHeight
        val radius = (state.windowScale / frame.groupScale) * state.windowRadius
        if (!radius.isFinite()) return null
        val outline = Clip(Rect(0, 0, outlineWidth.takeIf { it > 0 } ?: width, outlineHeight.takeIf { it > 0 } ?: height), radius)
        if (groupOutline && clipChildren && outline.bounds != bounds) return null
        val inheritedClip = when {
            groupOutline -> outline
            clipChildren -> Clip(bounds, 0f)
            else -> null
        }
        val visibleBackground = background?.takeIf { state.backgroundAlpha > 0f && state.groupAlpha > 0f }
        // Separate native children multiply the parent's opacity. The stock group may blend them first.
        if (front.alpha > 0f && visibleBackground != null && state.groupAlpha < 1f) return null

        val back = visibleBackground?.let { bitmap ->
            if (bitmap.width <= 0 || bitmap.height <= 0) return null
            val own = if (backgroundOutline) {
                val inset = state.backgroundOutlineOffset
                if (inset < 0 || inset > minOf(outline.bounds.width(), outline.bounds.height()) / 2) return null
                Clip(Rect(outline.bounds).apply { inset(inset, inset) }, radius)
            } else Clip(bounds, 0f)
            if (own.bounds.isEmpty) return null
            // SurfaceFlinger keeps one rounded clip per content layer. Select an equivalent intersection.
            val combined = when {
                inheritedClip == null || inheritedClip == own -> own
                inheritedClip.radius == own.radius && inheritedClip.bounds.contains(own.bounds) &&
                    own.radius * 2f <= minOf(own.bounds.width(), own.bounds.height()) -> own
                own.radius == 0f && own.bounds.contains(inheritedClip.bounds) -> inheritedClip
                inheritedClip.radius == 0f && inheritedClip.bounds.contains(own.bounds) -> own
                else -> return null
            }
            if (!bounds.contains(combined.bounds)) return null
            Image(LauncherIconPlaneProjection.Position(0f, 0f, width.toFloat() / bitmap.width,
                height.toFloat() / bitmap.height, state.backgroundAlpha), combined)
        }
        return Pose(group, Image(front, inheritedClip), back)
    }

    internal data class State(
        val icon: LauncherIconPlaneProjection.Frame,
        val groupAlpha: Float,
        val backgroundAlpha: Float,
        val backgroundOutlineOffset: Int,
        val windowScale: Float,
        val windowRadius: Float,
        val rotation: Float,
    )

    internal data class BitmapSize(val width: Int, val height: Int)
    internal data class Clip(val bounds: Rect, val radius: Float)
    internal data class Image(val position: LauncherIconPlaneProjection.Position, val clip: Clip?)
    internal data class Pose(val group: LauncherIconPlaneProjection.Position, val foreground: Image, val background: Image?)

    companion object {
        fun capture(projection: LauncherIconPlaneProjection, group: ViewGroup, background: View, foreground: View,
            portrait: Boolean, fixedOrientation: Boolean): LauncherOpeningIconProjection? {
            if (group.childCount != 2 || group.getChildAt(0) !== background || group.getChildAt(1) !== foreground ||
                group.clipToPadding && (group.paddingLeft != 0 || group.paddingTop != 0 ||
                    group.paddingRight != 0 || group.paddingBottom != 0) ||
                group.scrollX != 0 || group.scrollY != 0 || background.left != 0 || background.top != 0 ||
                !background.matrix.isIdentity || foreground.clipToOutline || background.width <= 0 || background.height <= 0
            ) return null
            return LauncherOpeningIconProjection(projection, fixedOrientation, portrait, background.width, background.height,
                group.clipToOutline, group.clipChildren, background.clipToOutline)
        }
    }
}
