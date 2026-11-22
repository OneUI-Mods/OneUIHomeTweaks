package dev.ryuya.oneuihometweaks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.appwidget.AppWidgetHostView
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import kotlin.math.sqrt

/** Adds local motion to visible launcher items for the duration of Samsung's reveal. */
internal class HomeUnlockReveal(
    private val root: View,
    private val animator: ValueAnimator,
    private val generation: UnlockLogicRuntime.Generation = UnlockLogicRuntime.capture(),
    private val finished: (HomeUnlockReveal) -> Unit,
) : AnimatorListenerAdapter(), ValueAnimator.AnimatorUpdateListener, View.OnAttachStateChangeListener {
    private val parts = collectParts()
    val description: String
        get() = "icons=${parts.count { it.kind == Kind.ICON }} " +
            "widgets=${parts.count { it.kind == Kind.WIDGET }} dock=${parts.count { it.kind == Kind.DOCK }}"
    private var done = false

    fun start() {
        if (parts.isEmpty()) {
            finish()
            return
        }
        root.addOnAttachStateChangeListener(this)
        animator.addUpdateListener(this)
        animator.addListener(this)
        for (part in parts) part.apply(0f)
    }

    override fun onAnimationUpdate(animation: ValueAnimator) {
        if (done) return
        val progress = (animation.currentPlayTime.toFloat() / animation.duration).coerceIn(0f, 1f)
        for (index in parts.indices) parts[index].apply(progress)
    }

    override fun onAnimationEnd(animation: Animator) = finish()

    override fun onAnimationCancel(animation: Animator) = finish()

    override fun onViewAttachedToWindow(view: View) = Unit

    override fun onViewDetachedFromWindow(view: View) = finish()

    fun finish() {
        if (done) return
        done = true
        // Animator may still be dispatching its listeners.
        main.post {
            animator.removeUpdateListener(this)
            animator.removeListener(this)
        }
        root.removeOnAttachStateChangeListener(this)
        for (part in parts) part.restore()
        finished(this)
    }

    private fun collectParts(): List<Part> {
        val rootBounds = Rect()
        if (!root.getGlobalVisibleRect(rootBounds) || rootBounds.isEmpty) return emptyList()
        val density = root.resources.displayMetrics.density
        val bounds = Rect()
        val centerX = rootBounds.exactCenterX()
        val centerY = rootBounds.top + rootBounds.height() * 0.54f
        val radius = sqrt(rootBounds.width().toFloat() * rootBounds.width() * 0.25f +
            rootBounds.height().toFloat() * rootBounds.height() * 0.29f)
        val found = ArrayList<Part>()
        var remainingViews = MAX_VIEWS

        fun visit(view: View, inheritedSection: Section?, depth: Int) {
            if (depth > MAX_DEPTH || remainingViews-- <= 0 || found.size >= MAX_PARTS) return
            if (view.visibility != View.VISIBLE || (view !== root && view.alpha <= 0f)) return

            var section = inheritedSection
            var hotseat = false
            var type: Class<*>? = view.javaClass
            var kind: Kind? = if (view is AppWidgetHostView) Kind.WIDGET else null
            while (type != null && type != View::class.java) {
                when (type.name) {
                    WORKSPACE, FREE_GRID -> section = Section.WORKSPACE
                    HOTSEAT -> {
                        section = Section.DOCK
                        hotseat = true
                    }
                    ICON, FOLDER -> kind = Kind.ICON
                    WIDGET -> kind = Kind.WIDGET
                }
                type = type.superclass
            }

            // Move the dock's container when it is a separate, shallow bar.
            if (hotseat && view.height in 1..(rootBounds.height() / 3) &&
                view.getGlobalVisibleRect(bounds) && bounds.intersect(rootBounds)
            ) {
                found.add(part(view, Kind.DOCK, 0f, 0f, radius, density))
                return
            }

            if (section != null && kind != null) {
                if (view.getGlobalVisibleRect(bounds) && bounds.intersect(rootBounds)) {
                    val dx = bounds.exactCenterX() - centerX
                    val dy = bounds.exactCenterY() - centerY
                    val itemKind = when {
                        section == Section.DOCK -> Kind.DOCK
                        kind == Kind.WIDGET -> Kind.WIDGET
                        else -> Kind.ICON
                    }
                    found.add(part(view, itemKind, dx, dy, radius, density))
                }
                // A composite folder preview or widget moves as a single item.
                return
            }

            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (remainingViews <= 0 || found.size >= MAX_PARTS) break
                    visit(view.getChildAt(index), section, depth + 1)
                }
            }
        }

        visit(root, null, 0)
        return found
    }

    private fun part(view: View, kind: Kind, dx: Float, dy: Float, radius: Float, density: Float): Part {
        val plan = generation.call {
            it.planHomeItem(kind.code, dx, dy, radius, density).also { result ->
                check(result.offsetX.isFinite() && result.offsetY.isFinite() &&
                    result.extraScale.isFinite() && result.delay in 0f..1f && result.travel in 0.01f..1f)
            }
        }
        return Part(view, kind, plan)
    }

    private enum class Section { WORKSPACE, DOCK }

    private enum class Kind(val code: Int) {
        ICON(UnlockLogicV1.ICON), WIDGET(UnlockLogicV1.WIDGET), DOCK(UnlockLogicV1.DOCK),
    }

    private inner class Part(
        private val view: View,
        val kind: Kind,
        private val plan: UnlockItemPlan,
    ) {
        private val frame = UnlockItemFrame()
        private val parent = view.parent
        private val baseX = view.translationX
        private val baseY = view.translationY
        private val baseAlpha = view.alpha
        private val baseScaleX = view.scaleX
        private val baseScaleY = view.scaleY
        private var appliedX = baseX
        private var appliedY = baseY
        private var appliedAlpha = baseAlpha
        private var appliedScaleX = baseScaleX
        private var appliedScaleY = baseScaleY
        private var released = false

        fun apply(progress: Float) {
            if (released) return
            if (!view.isAttachedToWindow || view.parent !== parent ||
                view.translationX != appliedX || view.translationY != appliedY || view.alpha != appliedAlpha ||
                view.scaleX != appliedScaleX || view.scaleY != appliedScaleY
            ) {
                restore()
                return
            }

            generation.call {
                it.homeItemFrame(plan, progress, frame)
                check(frame.x.isFinite() && frame.y.isFinite() && frame.scale in 0.25f..4f)
                frame.alpha.validFraction()
            }
            val x = baseX + frame.x
            val y = baseY + frame.y
            val alpha = baseAlpha * frame.alpha
            if (x != appliedX) view.translationX = x
            if (y != appliedY) view.translationY = y
            if (alpha != appliedAlpha) view.alpha = alpha
            appliedX = x
            appliedY = y
            appliedAlpha = alpha

            val scaleX = baseScaleX * frame.scale
            val scaleY = baseScaleY * frame.scale
            if (scaleX != appliedScaleX) view.scaleX = scaleX
            if (scaleY != appliedScaleY) view.scaleY = scaleY
            appliedScaleX = scaleX
            appliedScaleY = scaleY
        }

        fun restore() {
            if (released) return
            released = true
            if (view.translationX == appliedX) view.translationX = baseX
            if (view.translationY == appliedY) view.translationY = baseY
            if (view.alpha == appliedAlpha) view.alpha = baseAlpha
            if (view.scaleX == appliedScaleX) view.scaleX = baseScaleX
            if (view.scaleY == appliedScaleY) view.scaleY = baseScaleY
        }
    }

    private companion object {
        const val WORKSPACE = "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceFastRecyclerView"
        const val FREE_GRID = "com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridCellLayout"
        const val HOTSEAT = "com.honeyspace.ui.honeypots.hotseat.presentation.HotseatCellLayout"
        const val ICON = "com.honeyspace.ui.common.iconview.IconViewImpl"
        const val FOLDER = "com.honeyspace.ui.common.iconview.FolderIconViewImpl"
        const val WIDGET = "com.honeyspace.common.widget.SpannableWidgetView"
        const val MAX_VIEWS = 512
        const val MAX_DEPTH = 32
        const val MAX_PARTS = 64

        val main = Handler(Looper.getMainLooper())
    }
}
