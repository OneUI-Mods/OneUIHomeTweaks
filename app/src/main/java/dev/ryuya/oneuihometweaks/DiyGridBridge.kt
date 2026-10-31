package dev.ryuya.oneuihometweaks

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import java.lang.Boolean as JavaBoolean
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.Map
import java.util.TreeMap
import java.util.WeakHashMap
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.log
import kotlin.math.round

/* Projects native DIY icon coordinates without changing widgets, DIY mode, or stored positions. */
class DiyGridBridge {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val hooks: MutableList<ModernHook.Handle?> = ArrayList<ModernHook.Handle?>()
    private val states: MutableMap<Any?, DiyGridBridge.State?> =
        WeakHashMap<Any?, DiyGridBridge.State?>()
    private val layouts: MutableMap<ViewGroup?, DiyGridBridge.State?> =
        WeakHashMap<ViewGroup?, DiyGridBridge.State?>()
    private val methods: MutableMap<String?, Method?> = HashMap<String?, Method?>()
    private val moveRemainders: MutableMap<Any?, PointF?> = WeakHashMap<Any?, PointF?>()
    private var editing: Boolean = false
    private var editLogCount: Int = 0
    private var workspace: WeakReference<ViewGroup?> = WeakReference<ViewGroup?>(null)
    private var layoutType: Class<*>? = null
    private var modelType: Class<*>? = null
    private val iconTypes: MutableSet<Class<*>?> = HashSet<Class<*>?>()
    private var enabled: Boolean = false
    private var starting: Boolean = false
    private var ready: Boolean = false
    private var failed: Boolean = false
    private var gathering: Boolean = false
    private var rendering: Boolean = false
    private var projectionDepth: Int = 0
    private var diagnostic: String = "off"
    private var observedGeometry: String = ""
    private var changed: Runnable = Runnable {}
    private val render: Runnable = Runnable { this.renderNow() }

    @JvmRecord
    data class PreparedSave(val context: Context?, val owner: Any?, val plan: GridSavePlan?) {
        override fun toString(): String = "PreparedSave[context=$context, owner=$owner, plan=$plan]"
    }

    @Throws(ReflectiveOperationException::class)
    fun prepareSave(): DiyGridBridge.PreparedSave? {
        val root = workspace.get()
        check(
            !(!DiyGridBridge.onMain() ||
                !ready ||
                !enabled ||
                failed ||
                editing ||
                root == null ||
                !root.isAttachedToWindow())
        ) {
            "Grid not ready"
        }
        collect(root)
        val owners =
            Collections.newSetFromMap<DiyGridBridge.State?>(
                IdentityHashMap<DiyGridBridge.State?, Boolean?>()
            )
        for (entry in layouts.entries) if (
            entry.key!!.isAttachedToWindow() && entry.value!!.valid()
        ) {
            check(number(entry!!.key!!, "getSelectedItemsCount") == 0) { "Editor selection active" }
            owners.add(entry.value)
        }
        check(owners.size == 1) { "Ambiguous home model" }
        val state = owners.iterator().next()
        state.capture()
        check(state.valid()) { "Home grid geometry is not ready" }
        state.dirty = true
        state.plan()
        val vm = state.vm.get()
        checkNotNull(vm) { "Home model unavailable" }
        val info =
            Class.forName(
                "com.honeyspace.ui.honeypots.freegrid.domain.model.FreeEditInfo",
                false,
                vm.javaClass.getClassLoader(),
            )
        info.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaObjectType,
            Int::class.javaObjectType,
            Int::class.javaObjectType,
            Int::class.javaObjectType,
            Int::class.javaObjectType,
            Float::class.javaObjectType,
            Float::class.javaObjectType,
            Int::class.javaObjectType,
            Boolean::class.javaPrimitiveType,
        )
        vm.javaClass.getDeclaredMethod(
            "updateFreeGridProperty",
            MutableList::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        val entries = ArrayList<GridSavePlan.Entry?>()
        for (reference in state.models.values) {
            val model = reference!!.get()
            check(
                !(model == null || !eligible(model) || JavaBoolean.TRUE == call(model, "getLand"))
            ) {
                "Incomplete model snapshot"
            }
            val id = number(model, "getId")
            val slot = state.positions.get(id)
            checkNotNull(slot) { "Page cannot fit normal grid" }
            val original = call(model, "getToFreeEditInfo")
            check(JavaBoolean.TRUE != call(original!!, "getDelete")) {
                "Hidden item cannot be saved"
            }
            entries.add(
                GridSavePlan.Entry(
                    id,
                    number(model, "getPageId"),
                    number(model, "getPortX"),
                    number(model, "getPortY"),
                    slot.x,
                    slot.y,
                    (state.grid.toString() +
                        ":" +
                        state.cell +
                        ":" +
                        state.source(model) +
                        ":" +
                        call(model, "getScale") +
                        ":" +
                        call(model, "getAngle")),
                )
            )
        }
        return DiyGridBridge.PreparedSave(root.getContext(), vm, GridSavePlan(entries))
    }

    @Throws(ReflectiveOperationException::class)
    fun applySave(prepared: DiyGridBridge.PreparedSave, rollback: Boolean) {
        check(DiyGridBridge.onMain()) { "Main thread only" }
        val owner = prepared.owner
        val info =
            Class.forName(
                "com.honeyspace.ui.honeypots.freegrid.domain.model.FreeEditInfo",
                false,
                owner!!.javaClass.getClassLoader(),
            )
        val constructor =
            info.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaObjectType,
                Int::class.javaObjectType,
                Int::class.javaObjectType,
                Int::class.javaObjectType,
                Int::class.javaObjectType,
                Float::class.javaObjectType,
                Float::class.javaObjectType,
                Int::class.javaObjectType,
                Boolean::class.javaPrimitiveType,
            )
        val updates = ArrayList<Any?>()
        for (entry in prepared.plan!!.entries!!) if (entry!!.changed())
            updates.add(
                constructor.newInstance(
                    entry.id,
                    null,
                    if (rollback) entry.oldX else entry.x,
                    if (rollback) entry.oldY else entry.y,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                )
            )
        // Only x/y are supplied. Native history and repository persistence own every write.
        call(
            owner!!,
            "updateFreeGridProperty",
            arrayOf<Class<*>?>(
                MutableList::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ),
            updates,
            false,
            true,
        )
        for (state in states.values) if (state!!.vm.get() === owner) state!!.dirty = true
        queueRender()
    }

    fun setChanged(listener: Runnable) {
        changed = listener
    }

    fun diagnostic(): String {
        return diagnostic
    }

    fun setEnabled(on: Boolean) {
        if (failed || enabled == on) return
        enabled = on
        moveRemainders.clear()
        if (on) {
            ensureReady()
            if (ready) queueRender()
        } else {
            report("off")
            queueRender()
        }
    }

    fun observe(view: ViewGroup) {
        val replaced: Boolean = workspace.get() !== view
        workspace = WeakReference<ViewGroup?>(view)
        if (!ready) {
            if (enabled) ensureReady()
            return
        }
        try {
            val key: String =
                (view.getWidth().toString() +
                    ":" +
                    view.getHeight() +
                    ":" +
                    view.getChildCount() +
                    ":" +
                    number(view, "getCurrentPage") +
                    ":" +
                    view.getResources().getConfiguration().orientation +
                    ":" +
                    view.getResources().getConfiguration().screenWidthDp +
                    ":" +
                    view.getResources().getConfiguration().screenHeightDp +
                    ":" +
                    view.getResources().getConfiguration().densityDpi)
            if (!replaced && key == observedGeometry) return
            observedGeometry = key
            collect(view)
            queueRender()
        } catch (error: Throwable) {
            disable(error)
        }
    }

    private fun ensureReady() {
        val view = workspace.get()
        if (starting || ready || failed || view == null) return
        starting = true
        report("loading")
        val loader = view.javaClass.getClassLoader()
        // DEX inspection and deoptimization must not block Launcher startup or a touch callback.
        Thread(
                Runnable {
                    try {
                        val host = view.getContext()
                        val apk =
                            host
                                .getPackageManager()
                                .getApplicationInfo("com.samsung.android.app.homestar", 0)
                                .sourceDir
                        val layout =
                            Class.forName(
                                "com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridCellLayout",
                                false,
                                loader,
                            )
                        val model =
                            Class.forName(
                                "com.honeyspace.ui.honeypots.freegrid.domain.model.FreeGridItem",
                                false,
                                loader,
                            )
                        val vm =
                            Class.forName(
                                "com.honeyspace.ui.honeypots.freegrid.viewmodel.FreeGridViewModel",
                                false,
                                loader,
                            )
                        val params =
                            Class.forName(
                                layout.getName() + "\$FreeGridLayoutParams",
                                false,
                                loader,
                            )
                        val searchable =
                            Class.forName(
                                "com.honeyspace.sdk.transition.SearchableView",
                                false,
                                loader,
                            )
                        val viewItemId = searchable.getDeclaredMethod("getItemId")
                        if (viewItemId.getReturnType() != Int::class.javaPrimitiveType)
                            throw UnsupportedOperationException("DIY view identity changed")
                        if (
                            !ViewGroup::class.java.isAssignableFrom(layout) ||
                                !LayoutParams::class.java.isAssignableFrom(params)
                        )
                            throw UnsupportedOperationException("DIY layout types changed")
                        val create = layout.getDeclaredMethod("createLayoutParams", model)
                        val items = vm.getDeclaredMethod("getItems")
                        val geometry = vm.getDeclaredMethod("getItemPositionData")
                        geometry.setAccessible(true)
                        if (
                            geometry.getReturnType().getDeclaredMethod("isLand").getReturnType() !=
                                Boolean::class.javaPrimitiveType
                        )
                            throw UnsupportedOperationException("DIY geometry orientation changed")
                        params.getDeclaredMethod(
                            "setup",
                            Point::class.java,
                            Float::class.javaPrimitiveType,
                        )
                        model.getDeclaredMethod("getPortX")
                        model.getDeclaredMethod("getPortY")
                        for (name in
                            arrayOf<String>("App", "Folder", "Shortcut", "DeepShortcut")) iconTypes
                            .add(Class.forName(model.getName() + "$" + name, false, loader))
                        val sticker =
                            Class.forName(
                                "com.honeyspace.ui.honeypots.sticker.StickerView",
                                false,
                                loader,
                            )
                        val frame =
                            Class.forName(
                                "com.honeyspace.ui.honeypots.sticker.FrameControlStickerView",
                                false,
                                loader,
                            )
                        // Resolve the complete edit contract before installing any editing hook.
                        val prepare = layout.getDeclaredMethod("prepareSyncPosition", frame)
                        val sync = layout.getDeclaredMethod("syncPosition", frame)
                        val translate =
                            layout.getDeclaredMethod(
                                "translateTarget",
                                sticker,
                                Float::class.javaPrimitiveType,
                                Float::class.javaPrimitiveType,
                                Boolean::class.javaPrimitiveType,
                            )
                        val drop =
                            vm.getDeclaredMethod(
                                "updateToDropPoint",
                                model,
                                Point::class.java,
                                Int::class.javaPrimitiveType,
                                Point::class.java,
                            )
                        layout.getDeclaredMethod("fitTargetView", sticker)
                        frame.getDeclaredMethod("getTargetPosition")
                        val targetDiff = frame.getDeclaredField("targetPositionDiff")
                        if (targetDiff.getType() != PointF::class.java)
                            throw UnsupportedOperationException("DIY edit pointer contract changed")
                        targetDiff.setAccessible(true)
                        DexEvidence.deoptimizeDiyPositions(apk, loader)
                        layoutType = layout
                        modelType = model
                        hooks.add(
                            ModernHook.hookMethod(
                                prepare,
                                object : ModernHook.Callback() {
                                    override fun beforeHookedMethod(p: ModernHook.Param) {
                                        if (DiyGridBridge.onMain()) {
                                            moveRemainders.remove(p.args[0])
                                            editLogCount = 0
                                        }
                                    }
                                },
                            )
                        )
                        for (edit in arrayOf<Method?>(sync, translate)) {
                            val isSync: Boolean = edit === sync
                            hooks.add(
                                ModernHook.hookMethod(
                                    edit!!,
                                    object : ModernHook.Callback() {
                                        override fun beforeHookedMethod(p: ModernHook.Param) {
                                            if (
                                                !ready ||
                                                    !enabled ||
                                                    failed ||
                                                    editing ||
                                                    !DiyGridBridge.onMain()
                                            )
                                                return
                                            try {
                                                val owner: ViewGroup? = p.thisObject as ViewGroup?
                                                register(owner!!)
                                                val state = layouts.get(owner)
                                                if (state == null || !state.valid()) return
                                                val control: Any? = p.args[0]
                                                val ref =
                                                    state.models.get(number(control!!, "getId"))
                                                val item: Any? =
                                                    if (ref == null) null else ref.get()
                                                if (item == null || !eligible(item)) return
                                                val target: View? =
                                                    call(control!!, "getTargetView") as View?
                                                if (target!!.getParent() !== owner) return
                                                val lp = target!!.getLayoutParams()
                                                val rest =
                                                    moveRemainders.getOrDefault(control, PointF())
                                                val requested: PointF?
                                                if (isSync) {
                                                    // Native getTargetPosition truncates to int.
                                                    // Read its verified
                                                    // float
                                                    // inputs so repeated moves of less than one
                                                    // pixel are not
                                                    // discarded
                                                    // when the control frame is snapped back onto a
                                                    // cell.
                                                    val diff: PointF? =
                                                        targetDiff.get(control) as PointF?
                                                    val handle: View? =
                                                        call(control!!, "getControlView") as View?
                                                    requested =
                                                        PointF(
                                                            handle!!.getTranslationX() - diff!!.x,
                                                            handle!!.getTranslationY() - diff!!.y,
                                                        )
                                                } else
                                                    requested =
                                                        PointF(
                                                            number(lp, "getCellX") +
                                                                p.args[1] as Float,
                                                            number(lp, "getCellY") +
                                                                p.args[2] as Float,
                                                        )
                                                val intended =
                                                    PointF(
                                                        requested.x + rest!!.x,
                                                        requested.y + rest!!.y,
                                                    )
                                                val raw =
                                                    Point(
                                                        Math.round(intended.x),
                                                        Math.round(intended.y),
                                                    )
                                                val snapped =
                                                    state.snap(item, number(item, "getPageId"), raw)
                                                if (snapped == null) return
                                                if (editLogCount++ < 4)
                                                    HookEntry.log(
                                                        ("DIY_GRID_EDIT " +
                                                            (if (isSync) "sync" else "translate") +
                                                            " type=" +
                                                            item.javaClass.getSimpleName() +
                                                            " requested=" +
                                                            requested +
                                                            " remainder=" +
                                                            rest +
                                                            " snapped=" +
                                                            snapped)
                                                    )
                                                editing = true
                                                try {
                                                    call(
                                                        lp,
                                                        "setup",
                                                        arrayOf<Class<*>?>(
                                                            Point::class.java,
                                                            Float::class.javaPrimitiveType,
                                                        ),
                                                        snapped,
                                                        call(lp, "getScale"),
                                                    )
                                                    target!!.requestLayout()
                                                    // Keep the native edit frame on the icon. Carry
                                                    // sub-cell pointer
                                                    // motion
                                                    // forward so small MOVE events can still cross
                                                    // a cell boundary.
                                                    if (isSync || JavaBoolean.TRUE == p.args[3])
                                                        call(
                                                            owner,
                                                            "fitTargetView",
                                                            arrayOf<Class<*>?>(sticker),
                                                            control,
                                                        )
                                                    moveRemainders.put(
                                                        control,
                                                        PointF(
                                                            intended.x - snapped.x,
                                                            intended.y - snapped.y,
                                                        ),
                                                    )
                                                    p.setResult(null)
                                                } finally {
                                                    editing = false
                                                }
                                            } catch (error: Throwable) {
                                                disable(error)
                                            }
                                        }
                                    },
                                )
                            )
                        }
                        hooks.add(
                            ModernHook.hookMethod(
                                drop,
                                object : ModernHook.Callback() {
                                    override fun beforeHookedMethod(p: ModernHook.Param) {
                                        if (
                                            !ready ||
                                                !enabled ||
                                                failed ||
                                                !DiyGridBridge.onMain() ||
                                                !eligible(p.args[0]!!)
                                        )
                                            return
                                        try {
                                            val desired: Point? = p.args[1] as Point?
                                            if (desired!!.x == -1 || desired!!.y == -1)
                                                return // Native sentinel.

                                            val state = states.get(p.thisObject)
                                            if (state == null || !state.valid()) return
                                            val snapped =
                                                state.snap(p.args[0]!!, p.args[2] as Int, desired!!)
                                            if (snapped != null) p.args[1] = snapped
                                        } catch (error: Throwable) {
                                            disable(error)
                                        }
                                    }

                                    override fun afterHookedMethod(p: ModernHook.Param) {
                                        if (!ready || failed || !DiyGridBridge.onMain()) return
                                        val state = states.get(p.thisObject)
                                        if (state != null) state.dirty = true
                                        queueRender()
                                    }
                                },
                            )
                        )
                        hooks.add(
                            ModernHook.hookMethod(
                                layout.getDeclaredMethod(
                                    "createLayoutParams",
                                    View::class.java,
                                    Int::class.javaPrimitiveType,
                                    Int::class.javaPrimitiveType,
                                    Int::class.javaPrimitiveType,
                                    Int::class.javaPrimitiveType,
                                    Float::class.javaPrimitiveType,
                                    Boolean::class.javaPrimitiveType,
                                ),
                                object : ModernHook.Callback() {
                                    override fun afterHookedMethod(p: ModernHook.Param) {
                                        if (
                                            !ready ||
                                                !enabled ||
                                                failed ||
                                                !DiyGridBridge.onMain() ||
                                                p.hasThrowable()
                                        )
                                            return
                                        try {
                                            register(p.thisObject as ViewGroup)
                                            val state = layouts.get(p.thisObject)
                                            val view: View? = p.args[0] as View?
                                            if (
                                                state == null ||
                                                    !searchable.isInstance(view) ||
                                                    !state.valid()
                                            )
                                                return
                                            val ref =
                                                state.models.get(viewItemId.invoke(view) as Int?)
                                            val item: Any? = if (ref == null) null else ref.get()
                                            val slot: GridSnapPlan.Slot? =
                                                if (item == null) null else state.position(item)
                                            if (slot != null)
                                                call(
                                                    p.getResult()!!,
                                                    "setup",
                                                    arrayOf<Class<*>?>(
                                                        Point::class.java,
                                                        Float::class.javaPrimitiveType,
                                                    ),
                                                    Point(slot.x, slot.y),
                                                    p.args[5],
                                                )
                                        } catch (error: Throwable) {
                                            disable(error)
                                        }
                                    }
                                },
                            )
                        )
                        // Install observers before coordinate hooks; until ready=true every getter
                        // stays
                        // native.
                        hooks.add(
                            ModernHook.hookMethod(
                                create,
                                object : ModernHook.Callback() {
                                    override fun beforeHookedMethod(p: ModernHook.Param) {
                                        if (!ready || failed || !DiyGridBridge.onMain()) return
                                        try {
                                            register(p.thisObject as ViewGroup)
                                            enterProjection(p)
                                        } catch (error: Throwable) {
                                            disable(error)
                                        }
                                    }

                                    override fun afterHookedMethod(p: ModernHook.Param) {
                                        leaveProjection(p)
                                    }
                                },
                            )
                        )
                        // The coordinate projection is visible ONLY inside verified, read-only UI
                        // consumers.
                        // Repository writes, migrations, orientation sanitizers and serialization
                        // retain
                        // raw coordinates.
                        hooks.add(
                            ModernHook.hookMethod(
                                layout.getDeclaredMethod(
                                    "updateViewWithoutReattach",
                                    View::class.java,
                                    model,
                                ),
                                object : ModernHook.Callback() {
                                    override fun beforeHookedMethod(p: ModernHook.Param) {
                                        enterProjection(p)
                                    }

                                    override fun afterHookedMethod(p: ModernHook.Param) {
                                        leaveProjection(p)
                                    }
                                },
                            )
                        )
                        hooks.add(
                            ModernHook.hookMethod(
                                vm.getDeclaredMethod("includePoint", model, Point::class.java),
                                object : ModernHook.Callback() {
                                    override fun beforeHookedMethod(p: ModernHook.Param) {
                                        enterProjection(p)
                                    }

                                    override fun afterHookedMethod(p: ModernHook.Param) {
                                        leaveProjection(p)
                                    }
                                },
                            )
                        )
                        hooks.add(
                            ModernHook.hookMethod(
                                items,
                                object : ModernHook.Callback() {
                                    override fun afterHookedMethod(p: ModernHook.Param) {
                                        if (
                                            !ready ||
                                                failed ||
                                                gathering ||
                                                p.hasThrowable() ||
                                                !DiyGridBridge.onMain()
                                        )
                                            return
                                        val state = states.get(p.thisObject)
                                        run {
                                            val patternPrefix0 = state != null
                                            val list =
                                                if (patternPrefix0) p.getResult() as? Iterable<*>
                                                else null
                                            if (patternPrefix0 && list != null)
                                                try {
                                                    state.bind(list)
                                                    if (state.dirty) queueRender()
                                                } catch (error: Throwable) {
                                                    disable(error)
                                                }
                                        }
                                    }
                                },
                            )
                        )
                        for (coordinate in arrayOf<String>("getX", "getY")) {
                            val getter = model.getDeclaredMethod(coordinate)
                            if (getter.getReturnType() != Int::class.javaPrimitiveType)
                                throw UnsupportedOperationException("DIY coordinates changed")
                            hooks.add(
                                ModernHook.hookMethod(
                                    getter,
                                    object : ModernHook.Callback() {
                                        override fun afterHookedMethod(p: ModernHook.Param) {
                                            if (
                                                !ready ||
                                                    !enabled ||
                                                    failed ||
                                                    p.hasThrowable() ||
                                                    !DiyGridBridge.onMain() ||
                                                    projectionDepth == 0 ||
                                                    !eligible(p.thisObject!!)
                                            )
                                                return
                                            try {
                                                for (state in
                                                    ArrayList<DiyGridBridge.State?>(
                                                        states.values
                                                    )) {
                                                    val current: Any? =
                                                        if (
                                                            state!!
                                                                .models
                                                                .get(
                                                                    number(p.thisObject!!, "getId")
                                                                ) == null
                                                        )
                                                            null
                                                        else
                                                            state!!
                                                                .models
                                                                .get(
                                                                    number(p.thisObject!!, "getId")
                                                                )!!
                                                                .get()
                                                    if (
                                                        current !== p.thisObject || !state!!.valid()
                                                    )
                                                        continue
                                                    val slot = state!!.position(p.thisObject!!)
                                                    if (slot != null)
                                                        p.setResult(
                                                            if (coordinate == "getX") slot.x
                                                            else slot.y
                                                        )
                                                    return
                                                }
                                            } catch (error: Throwable) {
                                                disable(error)
                                            }
                                        }
                                    },
                                )
                            )
                        }
                        main.post(
                            Runnable {
                                if (failed) return@Runnable
                                ready = true
                                starting = false
                                val current = workspace.get()
                                if (current != null) collect(current)
                                queueRender()
                            }
                        )
                    } catch (error: Throwable) {
                        main.post(Runnable { disable(error) })
                    }
                },
                "OneUiDiyGridEvidence",
            )
            .start()
    }

    private fun enterProjection(p: ModernHook.Param) {
        if (!enabled || !ready || failed || !DiyGridBridge.onMain()) return
        projectionDepth++
        p.setObjectExtra("diy-grid-ui", true)
    }

    private fun leaveProjection(p: ModernHook.Param) {
        if (JavaBoolean.TRUE == p.getObjectExtra("diy-grid-ui")) projectionDepth--
    }

    private fun eligible(model: Any): Boolean {
        return iconTypes.contains(model.javaClass)
    }

    private fun collect(root: ViewGroup) {
        try {
            if (layoutType!!.isInstance(root)) register(root)
            for (i in 0 until root.getChildCount()) run {
                val group = root.getChildAt(i) as? ViewGroup
                if (group != null) collect(group)
            }
        } catch (error: Throwable) {
            disable(error)
        }
    }

    @Throws(ReflectiveOperationException::class)
    private fun register(layout: ViewGroup) {
        val existing = layouts.get(layout)
        if (existing != null) {
            existing.noteViewport(layout)
            return
        }
        val vm = call(layout, "getViewModel")
        var state = states.get(vm)
        if (state == null) {
            state = State(vm, layout)
            states.put(vm, state)
        }
        layouts.put(layout, state)
        state!!.noteViewport(layout)
        state!!.capture()
        queueRender()
    }

    private fun queueRender() {
        if (rendering && !failed) return
        main.removeCallbacks(render)
        main.post(render)
    }

    private fun renderNow() {
        if (!ready || rendering) return
        if (failed) {
            restoreNativeViews()
            return
        }
        rendering = true
        try {
            var count: Int = 0
            var invalidGeometry: Int = 0
            val captured =
                Collections.newSetFromMap<DiyGridBridge.State?>(
                    IdentityHashMap<DiyGridBridge.State?, Boolean?>()
                )
            for (entry in
                ArrayList<MutableMap.MutableEntry<ViewGroup?, DiyGridBridge.State?>?>(
                    layouts.entries
                )) {
                val layout = entry!!.key
                val state = entry!!.value
                if (!layout!!.isAttachedToWindow()) continue
                if (captured.add(state)) state!!.capture()
                if (enabled && !state!!.geometryFitsViewport()) {
                    invalidGeometry++
                    state!!.reportGeometryMismatch()
                } else state!!.lastRejectedGeometry = ""
                val use: Boolean = enabled && !failed && state!!.valid()
                if (use) state!!.plan()
                for (binding in
                    ArrayList<MutableMap.MutableEntry<Int?, WeakReference<Any?>?>?>(
                        state!!.models.entries
                    )) {
                    val model = binding!!.value!!.get()
                    if (model == null || !eligible(model)) continue
                    val child =
                        call(
                            layout!!,
                            "getChildWithId",
                            arrayOf<Class<*>?>(Int::class.javaPrimitiveType),
                            binding!!.key,
                        )
                    if (child !is View || child.getParent() !== layout) continue
                    val slot: GridSnapPlan.Slot? = if (use) state!!.position(model) else null
                    val point: Point? =
                        if (slot == null) Point(number(model, "getX"), number(model, "getY"))
                        else Point(slot.x, slot.y)
                    // Use the native params for both visual layout and editing coordinates; never
                    // translate
                    // the view alone.
                    call(
                        child.getLayoutParams(),
                        "setup",
                        arrayOf<Class<*>?>(Point::class.java, Float::class.javaPrimitiveType),
                        point,
                        call(model, "getScale"),
                    )
                    child.requestLayout()
                    if (slot != null) count++
                }
            }
            if (!failed) {
                val next: String?
                if (!enabled) next = "off"
                else if (count > 0) next = "active icons=" + count
                else if (invalidGeometry > 0) next = "waiting: native grid geometry mismatch"
                else next = "waiting"
                report(next)
            }
        } catch (error: Throwable) {
            disable(error)
        } finally {
            rendering = false
        }
    }

    private inner class State(owner: Any?, layout: ViewGroup) {
        @JvmField val vm: WeakReference<Any?>
        @JvmField val context: WeakReference<Context?>
        @JvmField var viewport: WeakReference<ViewGroup?> = WeakReference<ViewGroup?>(null)
        @JvmField
        val models: MutableMap<Int?, WeakReference<Any?>?> =
            LinkedHashMap<Int?, WeakReference<Any?>?>()
        @JvmField
        val pages: MutableMap<Int?, MutableMap<Int?, GridSnapPlan.Slot?>?> =
            HashMap<Int?, MutableMap<Int?, GridSnapPlan.Slot?>?>()
        @JvmField
        var positions: MutableMap<Int?, GridSnapPlan.Slot?> = HashMap<Int?, GridSnapPlan.Slot?>()
        @JvmField
        var sources: MutableMap<Int?, GridSnapPlan.Item?> = HashMap<Int?, GridSnapPlan.Item?>()
        @JvmField var sourcePages: MutableMap<Int?, Int?> = HashMap<Int?, Int?>()
        @JvmField var geometry: Any? = null
        @JvmField var dimensions: DiyGridGeometry? = null
        @JvmField var lastRejectedGeometry: String = ""
        @JvmField var grid: Point? = null
        @JvmField var cell: Point? = null
        @JvmField var offset: Point? = null
        @JvmField var dirty: Boolean = true

        init {
            vm = WeakReference<Any?>(owner)
            context = WeakReference<Context?>(layout.getContext())
            noteViewport(layout)
        }

        fun noteViewport(layout: ViewGroup?) {
            val current = viewport.get()
            if (
                current == null ||
                    !current.isAttachedToWindow() ||
                    current.getWidth() <= 0 ||
                    current.getHeight() <= 0
            )
                viewport = WeakReference<ViewGroup?>(layout)
        }

        fun geometryFitsViewport(): Boolean {
            if (dimensions == null) return false
            var current = viewport.get()
            if (
                current == null ||
                    !current.isAttachedToWindow() ||
                    current.getWidth() <= 0 ||
                    current.getHeight() <= 0
            ) {
                // RecyclerView may detach the page that supplied the previous bounds. Reuse an
                // attached page of this same model, never a preview or a different ViewModel.
                current = null
                for (entry in layouts.entries) if (
                    entry.value == this &&
                        entry.key!!.isAttachedToWindow() &&
                        entry.key!!.getWidth() > 0 &&
                        entry.key!!.getHeight() > 0
                ) {
                    current = entry.key
                    viewport = WeakReference<ViewGroup?>(current)
                    break
                }
            }
            return current != null && dimensions!!.fits(current.getWidth(), current.getHeight())
        }

        fun reportGeometryMismatch() {
            val current = viewport.get()
            if (dimensions == null || current == null) return
            val signature: String =
                dimensions.toString() +
                    " viewport=" +
                    current.getWidth() +
                    "x" +
                    current.getHeight()
            if (signature == lastRejectedGeometry) return
            lastRejectedGeometry = signature
            HookEntry.log("DIY_GRID_GEOMETRY_REJECTED " + signature)
        }

        @Throws(ReflectiveOperationException::class)
        fun valid(): Boolean {
            val ctx = context.get()
            val owner = vm.get()
            if (
                ctx == null ||
                    owner == null ||
                    ctx.getDisplay() == null ||
                    ctx.getDisplay().getDisplayId() != 0 ||
                    ctx.getResources().getConfiguration().orientation !=
                        Configuration.ORIENTATION_PORTRAIT ||
                    (ctx.getResources().getConfiguration().getLayoutDirection() !=
                        View.LAYOUT_DIRECTION_LTR)
            )
                return false
            val preview = owner.javaClass.getDeclaredField("isPreview")
            preview.setAccessible(true)
            return !preview.getBoolean(owner) && geometryFitsViewport()
        }

        @Throws(ReflectiveOperationException::class)
        fun capture() {
            val owner = vm.get()
            if (owner == null) return
            gathering = true
            try {
                val raw = call(owner, "getItems")
                if (raw is Iterable<*>) bind(raw)
                val nextGrid: Point? = call(owner, "getWorkspaceUIGrid") as Point?
                val nextGeometry = call(owner, "getItemPositionData")
                val nextCell: Point? =
                    call(nextGeometry!!, "getItemSizeBasedOnDefaultGrid") as Point?
                val nextOffset: Point? = call(nextGeometry!!, "getPositionDiff") as Point?
                val nextDimensions =
                    DiyGridGeometry(
                        nextGrid!!.x,
                        nextGrid!!.y,
                        nextCell!!.x,
                        nextCell!!.y,
                        nextOffset!!.x,
                        nextOffset!!.y,
                        call(nextGeometry!!, "isLand") as Boolean,
                    )
                if (nextDimensions != dimensions) dirty = true
                grid = Point(nextGrid!!)
                cell = Point(nextCell!!)
                offset = Point(nextOffset!!)
                geometry = nextGeometry
                dimensions = nextDimensions
            } finally {
                gathering = false
            }
        }

        @Throws(ReflectiveOperationException::class)
        fun bind(list: Iterable<*>) {
            val live = HashSet<Int?>()
            for (model in list) if (modelType!!.isInstance(model) && eligible(model!!)) {
                val id = number(model!!, "getId")
                live.add(id)
                val old = models.put(id, WeakReference<Any?>(model))
                if (old == null || old.get() !== model) dirty = true
            }
            if (models.keys.removeIf { id: Int? -> !live.contains(id) }) dirty = true
        }

        @Throws(ReflectiveOperationException::class)
        fun source(model: Any): GridSnapPlan.Item? {
            val spanX = number(model, "getSpanX")
            val spanY = number(model, "getSpanY")
            val padding: Point? =
                call(
                    geometry!!,
                    "getInnerPadding",
                    arrayOf<Class<*>?>(Point::class.java),
                    Point(spanX, spanY),
                )
                    as Point?
            return GridSnapPlan.Item(
                number(model, "getId"),
                number(model, "getPortX"),
                number(model, "getPortY"),
                spanX,
                spanY,
                offset!!.x + padding!!.x,
                offset!!.y + padding!!.y,
            )
        }

        @Throws(ReflectiveOperationException::class)
        fun position(model: Any): GridSnapPlan.Slot? {
            if (geometry == null || JavaBoolean.TRUE == call(model, "getLand")) return null
            val current = source(model)
            if (
                current != sources.get(current!!.id) ||
                    number(model, "getPageId") != sourcePages.get(current!!.id)
            )
                dirty = true
            if (dirty) {
                plan()
                queueRender()
            }
            return positions.get(current!!.id)
        }

        @Throws(ReflectiveOperationException::class)
        fun plan() {
            if (!dirty || geometry == null) return
            val groups = TreeMap<Int?, MutableList<GridSnapPlan.Item?>?>()
            val nextSources = HashMap<Int?, GridSnapPlan.Item?>()
            val nextPages = HashMap<Int?, Int?>()
            for (reference in models.values) {
                val model = reference!!.get()
                if (model == null) continue
                val source = source(model)
                groups
                    .computeIfAbsent(number(model, "getPageId")) { id: Int? ->
                        ArrayList<GridSnapPlan.Item?>()
                    }!!
                    .add(source)
                nextSources.put(source!!.id, source)
                nextPages.put(source!!.id, number(model, "getPageId"))
            }
            val next = HashMap<Int?, GridSnapPlan.Slot?>()
            for (group in groups.entries) {
                val page =
                    GridSnapPlan.build(
                        grid!!.x,
                        grid!!.y,
                        cell!!.x,
                        cell!!.y,
                        group.value,
                        pages.getOrDefault(group.key, Map.of<Int?, GridSnapPlan.Slot?>())!!,
                    )
                if (page == null) {
                    pages.remove(group.key)
                    HookEntry.log("DIY_GRID_PAGE_UNAVAILABLE capacity/geometry")
                    continue
                }
                pages.put(group.key, page)
                next.putAll(page)
            }
            positions = next
            sources = nextSources
            sourcePages = nextPages
            dirty = false
        }

        @Throws(ReflectiveOperationException::class)
        fun snap(item: Any, page: Int, requested: Point): Point? {
            if (geometry == null || JavaBoolean.TRUE == call(item, "getLand")) return null
            plan()
            val original = source(item)
            val desired =
                GridSnapPlan.Item(
                    original!!.id,
                    requested.x,
                    requested.y,
                    original!!.spanX,
                    original!!.spanY,
                    original!!.offsetX,
                    original!!.offsetY,
                )
            val slot =
                GridSnapPlan.move(
                    grid!!.x,
                    grid!!.y,
                    cell!!.x,
                    cell!!.y,
                    desired,
                    pages.getOrDefault(page, Map.of<Int?, GridSnapPlan.Slot?>())!!.values,
                )
            return if (slot == null) null else Point(slot.x, slot.y)
        }
    }

    private fun disable(error: Throwable) {
        if (failed) return
        failed = true
        enabled = false
        for (hook in hooks) try {
            hook!!.unhook()
        } catch (ignored: Throwable) {}
        report("error:" + error.javaClass.getSimpleName())
        queueRender()
        HookEntry.log("DIY_GRID_DISABLED " + error)
    }

    private fun restoreNativeViews() {
        for (entry in
            ArrayList<MutableMap.MutableEntry<ViewGroup?, DiyGridBridge.State?>?>(
                layouts.entries
            )) {
            val layout = entry!!.key
            for (binding in
                ArrayList<MutableMap.MutableEntry<Int?, WeakReference<Any?>?>?>(
                    entry!!.value!!.models.entries
                )) {
                try {
                    val model = binding!!.value!!.get()
                    if (model == null) continue
                    val child =
                        call(
                            layout!!,
                            "getChildWithId",
                            arrayOf<Class<*>?>(Int::class.javaPrimitiveType),
                            binding!!.key,
                        )
                    if (child !is View || child.getParent() !== layout) continue
                    val point = Point(number(model, "getX"), number(model, "getY"))
                    call(
                        child.getLayoutParams(),
                        "setup",
                        arrayOf<Class<*>?>(Point::class.java, Float::class.javaPrimitiveType),
                        point,
                        call(model, "getScale"),
                    )
                    child.requestLayout()
                } catch (error: Throwable) {
                    HookEntry.log("DIY_GRID_RESTORE_UNAVAILABLE " + error)
                }
            }
        }
    }

    private fun report(next: String) {
        if (next == diagnostic) return
        diagnostic = next
        changed.run()
        HookEntry.log("DIY_GRID " + next)
    }

    @Throws(ReflectiveOperationException::class)
    private fun number(owner: Any, name: String?): Int {
        return (call(owner, name) as Number).toInt()
    }

    @Throws(ReflectiveOperationException::class)
    private fun call(owner: Any, name: String?): Any? {
        return call(owner, name, arrayOfNulls<Class<*>>(0))
    }

    @Throws(ReflectiveOperationException::class)
    private fun call(
        owner: Any,
        name: String?,
        signature: Array<Class<*>?>,
        vararg args: Any?,
    ): Any? {
        val key: String = owner.javaClass.getName() + "#" + name + signature.contentToString()
        val cached = methods.get(key)
        if (cached != null) return cached.invoke(owner, *args)
        var type = owner.javaClass
        while (type != null) {
            try {
                val method = type.getDeclaredMethod(name, *signature)
                method.setAccessible(true)
                methods.put(key, method)
                return method.invoke(owner, *args)
            } catch (ignored: NoSuchMethodException) {}
            type = type.getSuperclass()
        }
        throw NoSuchMethodException(key)
    }

    companion object {
        @JvmStatic
        private fun onMain(): Boolean {
            return Looper.myLooper() == Looper.getMainLooper()
        }
    }
}
