package dev.ryuya.oneuihometweaks

import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import dev.ryuya.oneuihometweaks.DiyWidgetReflowPlan.Bounds
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import dev.ryuya.oneuihometweaks.compat.compatibleMethod
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Repairs saved overlaps on the open DIY page once its native item layout is stable. */
internal class DiyWidgetReflow(
    private val loader: ClassLoader?,
    private val pageType: Class<*>,
    ownerType: Class<*>,
    paramsType: Class<*>,
    private val isWidget: (View) -> Boolean,
    private val isAppIcon: (View) -> Boolean,
    private val onApplied: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val int = Int::class.javaPrimitiveType
    private val modelPackage = "com.honeyspace.ui.honeypots.freegrid.domain.model."
    private fun type(name: String) = DexSymbols.findClass(name, false, loader)

    private val workspaceClass = type("com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridFastRecyclerView")
    private val normalState = type("com.honeyspace.sdk.HomeScreen\$Normal")
    private val itemClass = type(modelPackage + "FreeGridItem")
    private val editClass = type(modelPackage + "FreeEditInfo")
    private val coordinate = Int::class.javaObjectType
    private val edit = editClass.getDeclaredConstructor(
        int, coordinate, coordinate, coordinate, coordinate, coordinate,
        Float::class.javaObjectType, Float::class.javaObjectType, coordinate,
        Boolean::class.javaPrimitiveType,
    )

    private val currentState = workspaceClass.compatibleDeclaredMethod("getCurrentState").apply { isAccessible = true }
    private val getOwner = workspaceClass.compatibleMethod("getViewModel")
    private val getItems = ownerType.compatibleMethod("getItems")
    private val currentRank = ownerType.compatibleMethod("getCurrentPageRank")
    private val rankOf = ownerType.compatibleMethod("getPageRankById", int)
    private val preview = ownerType.compatibleMethod("isPreview")
    private val dragInfo = ownerType.compatibleMethod("getDragInfo")
    private val getPageId = pageType.compatibleDeclaredMethod("getPageId").apply { isAccessible = true }
    private val getChild = pageType.compatibleMethod("getChildWithId", int)
    private val getSelectedCount = pageType.compatibleMethod("getSelectedItemsCount")
    private val getId = itemClass.compatibleMethod("getId")
    private val getItemPage = itemClass.compatibleMethod("getPageId")
    private val getLand = itemClass.compatibleMethod("getLand")
    private val getX = itemClass.compatibleMethod("getPortX")
    private val getY = itemClass.compatibleMethod("getPortY")
    private val cellX = paramsType.compatibleMethod("getCellX")
    private val cellY = paramsType.compatibleMethod("getCellY")
    private val layoutX = paramsType.compatibleMethod("getX")
    private val layoutY = paramsType.compatibleMethod("getY")
    private val update = ownerType.compatibleMethod(
        "updateFreeGridProperty", List::class.java, Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
    )

    private data class Position(val page: Int, val x: Int, val y: Int)
    private data class Snapshot(
        val source: DiyWidgetReflowPlan.Page,
        val targets: List<DiyWidgetReflowPlan.Page>,
        val positions: Map<Int, Position>,
    )
    private data class Placement(val id: Int, val before: Position, val after: Position)

    private var workspace = WeakReference<ViewGroup>(null)
    private var observer: ViewTreeObserver? = null
    private var lastAttempt: Snapshot? = null
    private var failed = false
    private var queued = false
    private val fixed = HashMap<Int, Position>()
    private val work = Runnable {
        queued = false
        val view = workspace.get() ?: return@Runnable
        runCatching { repair(view) }.onFailure {
            HookEntry.log("DIY_WIDGET_REFLOW_UNAVAILABLE ${it.javaClass.simpleName}: ${it.cause?.message ?: it.message}")
            failed = true
            observe(null)
        }
    }
    private val onLayout = ViewTreeObserver.OnGlobalLayoutListener { schedule() }
    private val onFocus = ViewTreeObserver.OnWindowFocusChangeListener { if (it) schedule() }

    fun observe(view: ViewGroup?) {
        val current = view.takeUnless { failed }
        val tree = current?.viewTreeObserver
        if (current !== workspace.get() || tree !== observer) {
            observer?.takeIf { it.isAlive }?.run {
                removeOnGlobalLayoutListener(onLayout)
                removeOnWindowFocusChangeListener(onFocus)
            }
            main.removeCallbacks(work)
            queued = false
            workspace = WeakReference(current)
            observer = tree?.takeIf { it.isAlive }
            observer?.addOnGlobalLayoutListener(onLayout)
            observer?.addOnWindowFocusChangeListener(onFocus)
            lastAttempt = null
        }
        schedule()
    }

    fun preservesPosition(page: ViewGroup, id: Int, x: Int, y: Int): Boolean {
        val position = fixed[id] ?: return false
        return position.x == x && position.y == y && position.page == getPageId.invoke(page)
    }

    private fun schedule() {
        if (queued || workspace.get()?.hasWindowFocus() != true) return
        queued = true
        main.postDelayed(work, 360)
    }

    private fun repair(view: ViewGroup) {
        if (!view.isShown || !view.hasWindowFocus() || view.display?.displayId != 0 ||
            view.layoutDirection != View.LAYOUT_DIRECTION_LTR ||
            view.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT ||
            !normalState.isInstance(currentState.invoke(view))
        ) return

        val owner = getOwner.invoke(view) ?: return
        if (preview.invoke(owner) == true || dragInfo.invoke(owner) != null) return
        val rank = currentRank.invoke(owner) as Int
        if (rank < 0) return
        val models = (getItems.invoke(owner) as List<*>).filterNotNull().groupBy { getItemPage.invoke(it) as Int }
        val positions = HashMap<Int, Position>()
        val visible = Rect()
        val pages = ArrayList<DiyWidgetReflowPlan.Page>()
        // The recycler contains page holders. Only fully bound native pages with matching geometry are usable.
        for (index in 0 until view.childCount) {
            val holder = view.getChildAt(index) as? ViewGroup ?: continue
            val page = (0 until holder.childCount).asSequence()
                .map { holder.getChildAt(it) }.firstOrNull(pageType::isInstance) as? ViewGroup ?: continue
            if (!page.isAttachedToWindow || page.visibility != View.VISIBLE || page.isLayoutRequested ||
                page.width <= 0 || page.height <= 0 || getSelectedCount.invoke(page) != 0
            ) continue
            val id = getPageId.invoke(page) as Int
            val pageRank = rankOf.invoke(owner, id) as Int
            if (pageRank < 0 || (pageRank == rank &&
                    (!page.getLocalVisibleRect(visible) || visible.width() != page.width))
            ) continue
            snapshot(page, id, pageRank, models[id].orEmpty(), positions)?.let(pages::add)
        }
        val source = pages.singleOrNull { it.rank == rank } ?: return
        if (source.widgets.isEmpty()) return
        val targets = pages.filter { it.id != source.id && it.width == source.width && it.height == source.height }
        val snapshot = Snapshot(source, targets, positions)
        if (snapshot == lastAttempt) return
        lastAttempt = snapshot
        val padding = (2 * view.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val moves = DiyWidgetReflowPlan.build(source, targets, padding)
        if (moves == null) {
            HookEntry.log("DIY_WIDGET_REFLOW_NO_ROOM rank=$rank pages=${targets.size + 1}")
            return
        }
        if (moves.isEmpty()) return
        val placements = moves.map { move ->
            val old = positions.getValue(move.item.id)
            Placement(move.item.id, old, Position(
                move.pageId,
                old.x + move.bounds.left - move.item.saved.left,
                old.y + move.bounds.top - move.item.saved.top,
            ))
        }
        if (placements.any { it.after.x == -1 || it.after.y == -1 }) return

        val items = models[source.id].orEmpty().associateBy { getId.invoke(it) as Int }
        val undo = edits(placements, rollback = true)
        val apply = edits(placements, rollback = false)
        try {
            // Prevent the optional grid projection from replacing the freshly planned placements.
            for (placement in placements) fixed[placement.id] = placement.after
            update.invoke(owner, apply, false, true)
            check(placements.all { placement ->
                val item = items.getValue(placement.id)
                getItemPage.invoke(item) == placement.after.page &&
                    getX.invoke(item) == placement.after.x && getY.invoke(item) == placement.after.y
            }) { "Native editor did not accept all placements" }
        } catch (failure: Throwable) {
            for (placement in placements) fixed.remove(placement.id)
            runCatching { update.invoke(owner, undo, false, false) }
                .onFailure(failure::addSuppressed)
            throw failure
        }
        onApplied()
        val overflow = placements.count { it.before.page != it.after.page }
        HookEntry.log("DIY_WIDGET_REFLOW_APPLIED rank=$rank moved=${placements.size} overflow=$overflow")
    }

    private fun edits(placements: List<Placement>, rollback: Boolean): List<Any> = placements.map { change ->
        val position = if (rollback) change.before else change.after
        val page = position.page.takeIf { change.before.page != change.after.page }
        edit.newInstance(change.id, page, position.x, position.y, null, null, null, null, null, false)
    }

    private fun snapshot(
        page: ViewGroup,
        id: Int,
        rank: Int,
        models: List<Any>,
        positions: MutableMap<Int, Position>,
    ): DiyWidgetReflowPlan.Page? {
        val widgets = ArrayList<Bounds>()
        val items = ArrayList<DiyWidgetReflowPlan.Item>()
        val boundViews = HashSet<View>()
        for (model in models) {
            if (getLand.invoke(model) == true) return null
            val itemId = getId.invoke(model) as Int
            val x = getX.invoke(model) as Int
            val y = getY.invoke(model) as Int
            // The native repository also reports unplaced applications with (-1, -1).
            if (x == -1 && y == -1) continue
            val child = getChild.invoke(page, itemId) as? View ?: return null
            if (child.parent !== page || child.width <= 0 || child.height <= 0 ||
                child.visibility != View.VISIBLE || child.isLayoutRequested
            ) return null
            boundViews += child
            val shape = RectF(0f, 0f, child.width.toFloat(), child.height.toFloat())
            child.matrix.mapRect(shape)
            val shown = shape.toBounds(child.left, child.top)
            if (isWidget(child)) {
                widgets += shown
            } else {
                val params = child.layoutParams
                val saved = shape.toBounds(
                    (layoutX.invoke(params) as Int) + x - (cellX.invoke(params) as Int),
                    (layoutY.invoke(params) as Int) + y - (cellY.invoke(params) as Int),
                )
                positions[itemId] = Position(id, x, y)
                items += DiyWidgetReflowPlan.Item(itemId, saved, shown, isAppIcon(child))
            }
        }
        // During binding an untracked child may already be on screen. Wait for a complete snapshot.
        for (index in 0 until page.childCount) {
            val child = page.getChildAt(index)
            if (child.visibility == View.VISIBLE && child.width > 0 && child.height > 0 &&
                child !in boundViews
            ) return null
        }
        return DiyWidgetReflowPlan.Page(id, rank, page.width, page.height, widgets, items)
    }

    private fun RectF.toBounds(x: Int, y: Int): Bounds =
        Bounds(floor(left + x).toInt(), floor(top + y).toInt(), ceil(right + x).toInt(), ceil(bottom + y).toInt())
}
