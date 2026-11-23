package dev.ryuya.oneuihometweaks

import android.graphics.Rect
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/** Matches FrameLayout's default START|TOP position for one fixed-size background child. */
internal class SearchBackgroundPlacement private constructor(
    private val host: FrameLayout,
    private val view: ImageView,
    private val width: Int,
    private val height: Int,
    private val left: Int,
    private val top: Int,
    private val previousParams: FrameLayout.LayoutParams,
) {
    var skippedRequest = false
        private set

    fun handlesRequest(requester: Any?): Boolean {
        if ((requester !== host && requester !== view) || !hasStockParams()) return false
        skippedRequest = true
        return true
    }

    fun apply(): Boolean {
        if (!hasStockParams() || view.visibility == View.GONE || view.parent !== host) return false
        view.forceLayout()
        view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        if (view.measuredWidth != width || view.measuredHeight != height) return false
        view.layout(left, top, left + width, top + height)
        return true
    }

    private fun hasStockParams(): Boolean {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return false
        return params !== previousParams && defaultPlacement(params) && params.width == width && params.height == height
    }

    companion object {
        fun create(host: FrameLayout, view: ImageView, width: Int, height: Int, content: Rect): SearchBackgroundPlacement? {
            val params = view.layoutParams as? FrameLayout.LayoutParams ?: return null
            if (!defaultPlacement(params) || (params.width == width && params.height == height) ||
                width <= 0 || height <= 0 || width > content.width() || height > content.height() ||
                view.parent !== host || !view.isAttachedToWindow || view.visibility == View.GONE ||
                view.adjustViewBounds || host.layoutMode != ViewGroup.LAYOUT_MODE_CLIP_BOUNDS
            ) return null
            val left = if (host.layoutDirection == View.LAYOUT_DIRECTION_RTL) content.right - width else content.left
            return SearchBackgroundPlacement(host, view, width, height, left, content.top, params)
        }

        private fun defaultPlacement(params: FrameLayout.LayoutParams): Boolean =
            params.javaClass === FrameLayout.LayoutParams::class.java && params.gravity == -1 &&
                !params.isMarginRelative && params.leftMargin == 0 && params.rightMargin == 0 &&
                params.topMargin == 0 && params.bottomMargin == 0
    }
}
