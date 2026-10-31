package dev.ryuya.oneuihometweaks

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.HashMap
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import kotlin.jvm.JvmRecord
import kotlin.math.log
import kotlin.math.min
import kotlin.math.round

/* BZI9 native Compose controls, their real ViewModels and Samsung composition locals. */
class QuickPanelPreviewAndroid17(
    loader: ClassLoader,
    adapter: QuickPanelAndroid17,
    changed: Runnable,
    failure: Runnable,
) : QuickPanelNativePreview.Renderer {
    private val loader: ClassLoader
    private val adapter: QuickPanelAndroid17
    private val changed: Runnable
    private val composer: Class<*>
    private val local: Class<*>
    private val provided: Class<*>
    private val function2: Class<*>
    private val scopeClass: Class<*>
    private val consume: Method
    private val provides: Method
    private val provider: Method
    private val setContent: Method
    private val composeView: Constructor<*>
    private val scopeConstructor: Constructor<*>
    private val unit: Any
    private val modifier: Any
    private val renderers: MutableMap<String?, Method?> = HashMap<String?, Method?>()
    private val compositions: MutableList<View?> = ArrayList<View?>()
    private val failure: Runnable
    private val recomposers: MutableSet<Any?>? =
        Collections.newSetFromMap<Any?>(IdentityHashMap<Any?, Boolean?>())
    private var runner: Any? = null
    private var lifecycle: Any? = null
    private val previewComposers: MutableSet<Any?>? =
        Collections.newSetFromMap<Any?>(IdentityHashMap<Any?, Boolean?>())
    private val listening: MutableSet<Any?>? =
        Collections.newSetFromMap<Any?>(IdentityHashMap<Any?, Boolean?>())
    private val listeningToken: Any = Any()
    private var setListening: Method?
    private val main: Handler = Handler(Looper.getMainLooper())
    private var scope: Any? = null
    private var locals: Any? = null
    private var sceneViewModel: Any? = null

    @JvmRecord
    private data class Bound(val source: Any?, val tile: Any?, val model: Any?) {
        override fun toString(): String = "Bound[source=$source, tile=$tile, model=$model]"
    }

    private val bindings: MutableMap<String?, QuickPanelPreviewAndroid17.Bound?> =
        HashMap<String?, QuickPanelPreviewAndroid17.Bound?>()
    private val hostLocals: MutableSet<Any?>? =
        Collections.newSetFromMap<Any?>(IdentityHashMap<Any?, Boolean?>())
    private var lastLocalMap: Any? = null
    private var lastConfiguration: Int = 0
    private var draggingLocal: Any?
    private var resizingLocal: Any?
    private var dragging: Any?
    private var resizing: Any?
    private var sourceView: View? = null

    init {
        this.loader = loader
        this.adapter = adapter
        this.changed = changed
        this.failure = failure
        composer = (load("androidx.compose.runtime.Composer"))!!
        local = (load("androidx.compose.runtime.CompositionLocal"))!!
        provided = (load("androidx.compose.runtime.ProvidedValue"))!!
        function2 = (load("kotlin.jvm.functions.Function2"))!!
        consume = composer.getMethod("consume", local)
        provides =
            load("androidx.compose.runtime.ProvidableCompositionLocal")!!.getMethod(
                "provides",
                Any::class.java,
            )
        provider =
            load("androidx.compose.runtime.CompositionLocalKt")!!.getMethod(
                "CompositionLocalProvider",
                ReflectArray.newInstance(provided, 0).javaClass,
                function2,
                composer,
                Int::class.javaPrimitiveType,
            )
        val view = load("androidx.compose.ui.platform.ComposeView")
        composeView = view!!.getConstructor(Context::class.java)
        setContent = view!!.getMethod("setContent", function2)
        unit = load("kotlin.Unit")!!.getField("INSTANCE").get(null)
        modifier = load("androidx.compose.ui.Modifier")!!.getField("Companion").get(null)
        scopeClass =
            (load(
                "com.android.systemui.samsung.quicksetting.ui.base.spannablegrid.SpannableGridTileScopeImpl"
            ))!!
        scopeConstructor =
            Arrays.stream<Constructor<*>?>(scopeClass.getConstructors())
                .filter { c: Constructor<*>? -> c!!.getParameterCount() == 13 }
                .findFirst()
                .orElseThrow()
        // The embedded window supplies its own view, context and lifecycle. Inherit the native
        // Samsung theme and scene locals, including locals required only by the larger tiles.
        for (name in
            arrayOf<String>(
                "androidx.compose.ui.platform.AndroidCompositionLocals_androidKt",
                "androidx.compose.ui.platform.CompositionLocalsKt",
                "androidx.lifecycle.compose.LocalLifecycleOwnerKt",
                "androidx.savedstate.compose.LocalSavedStateRegistryOwnerKt",
            )) {
            for (field in load(name)!!.getDeclaredFields()) {
                if (
                    Modifier.isStatic(field.getModifiers()) &&
                        local.isAssignableFrom(field.getType())
                ) {
                    field.setAccessible(true)
                    hostLocals!!.add(field.get(null))
                }
            }
        }
        val editable =
            load("com.android.systemui.samsung.quicksetting.ui.base.editable.EditableItemKt")
        draggingLocal = editable!!.getField("LocalDragging").get(null)
        resizingLocal = editable!!.getField("LocalResizing").get(null)
        hostLocals!!.add(draggingLocal)
        hostLocals!!.add(resizingLocal)
        dragging =
            load("com.android.systemui.samsung.quicksetting.ui.base.editable.DraggingInfo")!!
                .getConstructor()
                .newInstance()
        resizing =
            load("com.android.systemui.samsung.quicksetting.ui.base.editable.ResizingInfo")!!
                .getConstructor()
                .newInstance()
        val collectLive =
            load("androidx.lifecycle.compose.FlowExtKt")!!.getMethod(
                "collectAsStateWithLifecycle",
                load("kotlinx.coroutines.flow.StateFlow"),
                composer,
                Int::class.javaPrimitiveType,
            )
        val controlled =
            load("com.android.systemui.samsung.common.CollectStateKt")!!.getMethod(
                "collectAsStateWithControl",
                load("kotlinx.coroutines.flow.StateFlow"),
                load("kotlinx.coroutines.flow.StateFlow"),
                composer,
            )
        ModernHook.hookMethod(
            controlled,
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(p: ModernHook.Param) {
                    if (previewComposers!!.contains(p.args[2])) {
                        try {
                            p.setResult(collectLive.invoke(null, p.args[0], p.args[2], 0))
                        } catch (error: ReflectiveOperationException) {
                            previewFailed(error)
                        }
                    }
                }
            },
        )
        setListening =
            load("com.android.systemui.plugins.qs.QSTile")!!.getMethod(
                "setListening",
                Any::class.java,
                Boolean::class.javaPrimitiveType,
            )
        for (callSite in
            arrayOf<String>(
                "IconProgressBarKt",
                "IconProgressBarWithTileKt$\$ExternalSyntheticLambda0",
            )) {
            for (method in
                load(QuickPanelPreviewAndroid17.TILES + callSite)!!.getDeclaredMethods()) if (
                method.getName() == "invoke" || method.getName().startsWith("IconProgressBar-")
            )
                ModernHook.deoptimize(method)
        }
        val recomposer = load("androidx.compose.runtime.Recomposer")
        val initial =
            Arrays.stream<Method?>(recomposer!!.getDeclaredMethods())
                .filter { m: Method? -> m!!.getName() == "composeInitial\$runtime" }
                .findFirst()
                .orElseThrow()
        ModernHook.aroundMethod(
            initial,
            ModernHook.Around { chain: Chain? ->
                if (!recomposers!!.contains(chain!!.getThisObject()))
                    return@Around chain!!.proceed()
                try {
                    return@Around chain!!.proceed()
                } catch (error: Throwable) {
                    previewFailed(error)
                    return@Around null
                }
            },
        )

        for (name in
            arrayOf<String>(
                "BrightBar",
                "VolumeBar",
                "BrightBarWithTile",
                "VolumeBarWithTile",
                "QuickTile",
            )) {
            val method =
                Arrays.stream<Method?>(
                        load(QuickPanelPreviewAndroid17.TILES + name + "Kt")!!.getDeclaredMethods()
                    )
                    .filter { m: Method? ->
                        m!!.getName() == name &&
                            m!!.getParameterCount() == (if (name == "QuickTile") 8 else 5)
                    }
                    .findFirst()
                    .orElseThrow()
            renderers.put(name, method)
            ModernHook.hookMethod(
                method,
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        try {
                            if (scopeClass.getField("parentGridScope").get(p.args[0]) == null)
                                return
                            scope = p.args[0]
                            val c: Any? = p.args[if (name == "QuickTile") 5 else 3]
                            val viewKey =
                                load(
                                        "androidx.compose.ui.platform.AndroidCompositionLocals_androidKt"
                                    )!!
                                    .getField("LocalView")
                                    .get(null)
                            sourceView = consume.invoke(c, viewKey) as View?
                            val map: MutableMap<*, *>? =
                                composer.getMethod("getCurrentCompositionLocalMap").invoke(c)
                                    as MutableMap<*, *>?
                            val configuration =
                                sourceView!!.getResources().getConfiguration().hashCode()
                            if (map !== lastLocalMap || configuration != lastConfiguration) {
                                val scene =
                                    load(
                                        "com.android.systemui.samsung.quicksetting.ui.SecQuickSettingSceneKt"
                                    )
                                sceneViewModel =
                                    consume.invoke(c, scene!!.getField("LocalViewModel").get(null))
                                val inherited = ArrayList<Any?>()
                                for (key in map!!.keys) {
                                    if (!hostLocals!!.contains(key))
                                        inherited.add(provides.invoke(key, consume.invoke(c, key)))
                                }
                                // Normal tiles require edit context even outside the editor. Keep
                                // it private and
                                // idle.
                                inherited.add(provides.invoke(draggingLocal, dragging))
                                inherited.add(provides.invoke(resizingLocal, resizing))
                                val values = ReflectArray.newInstance(provided, inherited.size)
                                for (i in inherited.indices) ReflectArray.set(
                                    values,
                                    i,
                                    inherited.get(i),
                                )
                                locals = values
                                lastLocalMap = map
                                lastConfiguration = configuration
                            }
                            changed.run()
                        } catch (error: Throwable) {
                            HookEntry.log(
                                "QUICK_PANEL_PREVIEW_SOURCE " + error.javaClass.getSimpleName()
                            )
                        }
                    }
                },
            )
        }
    }

    @Throws(ClassNotFoundException::class)
    private fun load(name: String?): Class<*>? {
        return Class.forName(name, false, loader)
    }

    override fun ready(): Boolean {
        try {
            return scope != null &&
                locals != null &&
                sourceView != null &&
                !adapter.previewEntries()!!.isEmpty()
        } catch (e: Exception) {
            return false
        }
    }

    @Throws(Exception::class)
    override fun populate(root: FrameLayout?) {
        val parent = newRecomposer()
        createOwner(root)
        val row = LinearLayout(root!!.getContext())
        row.setOrientation(LinearLayout.HORIZONTAL)
        val cell = scopeClass.getField("cellSize").getLong(scope)
        val cellWidth = java.lang.Float.intBitsToFloat((cell ushr 32).toInt())
        val cellHeight = java.lang.Float.intBitsToFloat(cell.toInt())
        check(
            !(!java.lang.Float.isFinite(cellWidth) ||
                cellWidth <= 0 ||
                !java.lang.Float.isFinite(cellHeight) ||
                cellHeight <= 0)
        ) {
            "Invalid native cell size"
        }
        val nativeWidth = Math.round(cellWidth * 4)
        val nativeHeight = Math.round(cellHeight * 4)
        val region =
            android.widget.FrameLayout.LayoutParams(nativeWidth, nativeHeight, Gravity.CENTER)
        root!!.addView(row, region)
        row.addOnLayoutChangeListener(
            OnLayoutChangeListener {
                v: View?,
                l: Int,
                t: Int,
                r: Int,
                b: Int,
                ol: Int,
                ot: Int,
                or: Int,
                ob: Int ->
                val fit =
                    min(
                        1f,
                        min(
                            root!!.getWidth() / nativeWidth.toFloat(),
                            root!!.getHeight() / nativeHeight.toFloat(),
                        ),
                    )
                row.setScaleX(fit)
                row.setScaleY(fit)
            }
        )
        val columns = HashMap<Int?, Array<Any?>?>()
        for (entry in adapter.previewEntries()!!) {
            val model = entry!!.javaClass.getField("gridTileItem").get(entry)
            val type: String? = model.javaClass.getMethod("getType").invoke(model) as String?
            val spec: String? =
                if (type == "QuickTile") model.javaClass.getField("spec").get(model) as String?
                else ""
            val kind: Int =
                if (type!!.startsWith("BrightBar") || spec == "UiModeNight") 0
                else if (type!!.startsWith("VolumeBar") || spec == "SoundMode") 1 else -1
            if (kind < 0) continue
            val column = columns.computeIfAbsent(kind) { k: Int? -> arrayOfNulls<Any>(2) }
            column!![if (type == "QuickTile") 1 else 0] = entry
        }
        for (i in 0..1) {
            val entries = columns.get(i)
            if (entries == null || entries[0] == null) continue
            val column = LinearLayout(root!!.getContext())
            column.setOrientation(LinearLayout.VERTICAL)
            row.addView(column, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
            for (j in 0..1) {
                val entry: Any? = entries[j]
                if (entry == null) continue
                val model = bind(entry.javaClass.getField("gridTileItem").get(entry))
                val type: String? = model!!.javaClass.getMethod("getType").invoke(model) as String?
                val w = entry.javaClass.getField("spanWidth").getInt(entry)
                val h = entry.javaClass.getField("spanHeight").getInt(entry)
                val sampleScope = cloneScope(w, h)
                adapter.previewScope(sampleScope, i, type!!.endsWith("WithTile"))
                val tileModel: Any? =
                    if (type!!.endsWith("WithTile")) model!!.javaClass.getField("tile").get(model)
                    else if (type == "QuickTile") model else null
                if (tileModel != null) {
                    val tile = tileModel.javaClass.getField("tile").get(tileModel)
                    if (listening!!.add(tile)) {
                        setListening!!.invoke(tile, listeningToken, true)
                    }
                }
                val draw = renderers.get(type)
                if (draw == null) continue
                val view: View? = composeView.newInstance(sourceView!!.getContext()) as View?
                view!!.setId(View.generateViewId())
                view!!
                    .javaClass
                    .getMethod(
                        "setParentCompositionContext",
                        load("androidx.compose.runtime.CompositionContext"),
                    )
                    .invoke(view, parent)
                compositions.add(view)
                val sampleLocals: Any? = locals
                val content =
                    lambda(
                        QuickPanelPreviewAndroid17.Composition { c: Any?, flags: Any? ->
                            previewComposers!!.add(c)
                            val body =
                                lambda(
                                    QuickPanelPreviewAndroid17.Composition {
                                        inner: Any?,
                                        ignored: Any? ->
                                        previewComposers!!.add(inner)
                                        try {
                                            if (type == "QuickTile")
                                                draw.invoke(
                                                    null,
                                                    sampleScope,
                                                    modifier,
                                                    model,
                                                    false,
                                                    false,
                                                    inner,
                                                    1,
                                                    0,
                                                )
                                            else
                                                draw.invoke(
                                                    null,
                                                    sampleScope,
                                                    modifier,
                                                    model,
                                                    inner,
                                                    1,
                                                )
                                        } catch (error: InvocationTargetException) {
                                            throw IllegalStateException(
                                                "Native preview composition failed",
                                                error.cause,
                                            )
                                        }
                                    }
                                )
                            provider.invoke(null, sampleLocals, body, c, 0)
                        }
                    )
                setContent.invoke(view, content)
                column.addView(
                    view,
                    android.widget.LinearLayout.LayoutParams(
                        -1,
                        0,
                        (if (type!!.endsWith("WithTile")) 4 else if (j == 0) 3 else 1).toFloat(),
                    ),
                )
            }
        }
    }

    @Throws(Exception::class)
    private fun bind(source: Any): Any? {
        val type: String? = source.javaClass.getMethod("getType").invoke(source) as String?
        val group = type!!.endsWith("WithTile")
        val tileModel: Any? =
            if (group) source.javaClass.getField("tile").get(source)
            else if (type == "QuickTile") source else null
        if (tileModel == null) return source
        val spec: String = tileModel.javaClass.getField("spec").get(tileModel) as String
        val host = sceneViewModel!!.javaClass.getField("qsHost").get(sceneViewModel)
        val tiles: MutableCollection<*>? =
            load("com.android.systemui.qs.QSHost")!!.getMethod("getTiles").invoke(host)
                as MutableCollection<*>?
        val qsTile = load("com.android.systemui.plugins.qs.QSTile")
        val getSpec = qsTile!!.getMethod("getTileSpec")
        var actual: Any? = null
        for (tile in tiles!!) if (spec == getSpec.invoke(tile)) {
            actual = tile
            break
        }
        checkNotNull(actual) { "Native tile not ready: " + spec }
        var key: String = "tile:" + spec
        var cached = bindings.get(key)
        val boundTile: Any?
        if (cached != null && cached.source === tileModel && cached.tile === actual)
            boundTile = cached.model
        else {
            val copy =
                Arrays.stream<Method?>(tileModel.javaClass.getDeclaredMethods())
                    .filter { m: Method? ->
                        m!!.getName().startsWith("copy-") && m!!.getParameterCount() == 5
                    }
                    .findFirst()
                    .orElseThrow()
            boundTile = copy.invoke(null, tileModel, actual, null, null, 160)
            bindings.put(key, QuickPanelPreviewAndroid17.Bound(tileModel, actual, boundTile))
        }
        if (!group) return boundTile
        key = "group:" + type
        cached = bindings.get(key)
        if (cached != null && cached.source === source && cached.tile === boundTile)
            return cached.model
        val copy =
            Arrays.stream<Method?>(source.javaClass.getDeclaredMethods())
                .filter { m: Method? ->
                    m!!.getName().startsWith("copy-") && m!!.getParameterCount() == 2
                }
                .findFirst()
                .orElseThrow()
        val model = copy.invoke(null, source, boundTile)
        bindings.put(key, QuickPanelPreviewAndroid17.Bound(source, boundTile, model))
        return model
    }

    private fun previewFailed(error: Throwable?) {
        HookEntry.log("QUICK_PANEL_PREVIEW_COMPOSITION " + Log.getStackTraceString(error))
        main.post(failure)
    }

    @Throws(Exception::class)
    private fun newRecomposer(): Any? {
        val ctx = load("kotlin.coroutines.CoroutineContext")
        val handler = load("kotlinx.coroutines.CoroutineExceptionHandler")
        val key = handler!!.getField("Key").get(null)
        val empty = load("kotlin.coroutines.EmptyCoroutineContext")!!.getField("INSTANCE").get(null)
        val plus = ctx!!.getMethod("plus", ctx)
        val errors =
            Proxy.newProxyInstance(
                loader,
                arrayOf<Class<*>?>(handler),
                InvocationHandler { p: Any?, m: Method?, a: Array<Any?>? ->
                    when (m!!.getName()) {
                        "getKey" -> key
                        "get" -> if (a!![0] === key) p else null
                        "minusKey" -> if (a!![0] === key) empty else p
                        "fold" -> {
                            function2
                                .getMethod("invoke", Any::class.java, Any::class.java)
                                .invoke(a!![1], a!![0], p)
                        }
                        "plus" ->
                            load("kotlin.coroutines.CoroutineContext\$DefaultImpls")!!
                                .getMethod("plus", ctx, ctx)
                                .invoke(null, p, a!![0])
                        "handleException" -> {
                            for (arg in a!!) if (arg is Throwable) previewFailed(arg)
                            null
                        }
                        "hashCode" -> System.identityHashCode(p)
                        "equals" -> p === a!![0]
                        else -> "Quick Panel preview exception handler"
                    }
                },
            )
        val lazy =
            load("androidx.compose.ui.platform.AndroidUiDispatcher")!!
                .getField("Main\$delegate")
                .get(null)
        var context = load("kotlin.Lazy")!!.getMethod("getValue").invoke(lazy)
        context = plus.invoke(context, errors)
        val recomposerClass = load("androidx.compose.runtime.Recomposer")
        val recomposer = recomposerClass!!.getConstructor(ctx).newInstance(context)
        recomposers!!.add(recomposer)
        runner =
            load("kotlinx.coroutines.CoroutineScopeKt")!!
                .getMethod("CoroutineScope", ctx)
                .invoke(null, context)
        val run =
            recomposerClass!!.getMethod(
                "runRecomposeAndApplyChanges",
                load("kotlin.coroutines.Continuation"),
            )
        val block =
            Proxy.newProxyInstance(
                loader,
                arrayOf<Class<*>?>(function2),
                InvocationHandler { p: Any?, m: Method?, a: Array<Any?>? ->
                    if (m!!.getDeclaringClass() == Any::class.java)
                        return@InvocationHandler when (m!!.getName()) {
                            "hashCode" -> System.identityHashCode(p)
                            "equals" -> p === a!![0]
                            else -> "Quick Panel preview recomposer"
                        }
                    try {
                        return@InvocationHandler run.invoke(recomposer, a!![1])
                    } catch (error: InvocationTargetException) {
                        throw error.cause!!
                    }
                },
            )
        val coroutineScope = load("kotlinx.coroutines.CoroutineScope")
        val start = load("kotlinx.coroutines.CoroutineStart")
        val defaultStart = start!!.enumConstants!!.first { (it as Enum<*>).name == "DEFAULT" }
        load("kotlinx.coroutines.BuildersKt")!!
            .getMethod("launch", coroutineScope, ctx, start, function2)
            .invoke(null, runner, empty, defaultStart, block)
        return recomposer
    }

    @Throws(Exception::class)
    private fun cloneScope(w: Int, h: Int): Any? {
        val expand =
            PreviewCallbacks.constant(
                loader,
                load("kotlin.jvm.functions.Function1"),
                unit,
                "Read-only preview expansion",
            )
        return scopeConstructor.newInstance(
            scopeClass.getField("cellSize").getLong(scope),
            false,
            false,
            false,
            false,
            true,
            false,
            1f,
            null,
            (w.toLong() shl 32) or (h.toLong() and 0xffffffffL),
            false,
            expand,
            null,
        )
    }

    @Throws(Exception::class)
    private fun createOwner(root: View?) {
        val lifecycleOwner = load("androidx.lifecycle.LifecycleOwner")
        val savedOwner = load("androidx.savedstate.SavedStateRegistryOwner")
        val state = arrayOfNulls<Any>(2)
        val owner =
            Proxy.newProxyInstance(
                loader,
                arrayOf<Class<*>?>(savedOwner),
                InvocationHandler { p: Any?, m: Method?, a: Array<Any?>? ->
                    when (m!!.getName()) {
                        "getLifecycle" -> state[0]
                        "getSavedStateRegistry" -> state[1]
                        "hashCode" -> System.identityHashCode(p)
                        "equals" -> p === a!![0]
                        else -> "Quick Panel preview lifecycle"
                    }
                },
            )
        lifecycle =
            load("androidx.lifecycle.LifecycleRegistry")!!
                .getConstructor(lifecycleOwner)
                .newInstance(owner)
        state[0] = lifecycle
        val controllerType = load("androidx.savedstate.SavedStateRegistryController")
        val controller =
            load("androidx.savedstate.SavedStateRegistryController\$Companion")!!
                .getMethod("create", savedOwner)
                .invoke(null, owner)
        state[1] = controllerType!!.getField("savedStateRegistry").get(controller)
        controllerType!!.getMethod("performAttach").invoke(controller)
        controllerType!!
            .getMethod("performRestore", Bundle::class.java)
            .invoke(controller, *arrayOf<Any?>(null))
        load("androidx.lifecycle.ViewTreeLifecycleOwner")!!
            .getMethod("set", lifecycleOwner, View::class.java)
            .invoke(null, owner, root)
        load("androidx.savedstate.ViewTreeSavedStateRegistryOwner")!!
            .getMethod("set", View::class.java, savedOwner)
            .invoke(null, root, owner)
        lifecycleState("RESUMED")
    }

    @Throws(Exception::class)
    private fun lifecycleState(name: String?) {
        if (lifecycle == null) return
        val state = load("androidx.lifecycle.Lifecycle\$State")
        lifecycle!!
            .javaClass
            .getMethod("setCurrentState", state)
            .invoke(lifecycle, state!!.enumConstants!!.first { (it as Enum<*>).name == name })
    }

    private fun interface Composition {
        @Throws(Throwable::class) fun draw(composer: Any?, flags: Any?)
    }

    private fun lambda(draw: QuickPanelPreviewAndroid17.Composition): Any? {
        return Proxy.newProxyInstance(
            loader,
            arrayOf<Class<*>?>(function2),
            InvocationHandler { p: Any?, m: Method?, a: Array<Any?>? ->
                if (m!!.getDeclaringClass() == Any::class.java)
                    return@InvocationHandler when (m!!.getName()) {
                        "hashCode" -> System.identityHashCode(p)
                        "equals" -> p === a!![0]
                        else -> "Native Quick Panel preview"
                    }
                draw.draw(a!![0], a!![1])
                unit
            },
        )
    }

    override fun clear(root: FrameLayout?) {
        for (view in compositions) try {
            view!!.javaClass.getMethod("disposeComposition").invoke(view)
        } catch (ignored: ReflectiveOperationException) {}
        compositions.clear()
        previewComposers!!.clear()
        try {
            lifecycleState("DESTROYED")
        } catch (ignored: Exception) {}
        lifecycle = null
        for (tile in listening!!) try {
            setListening!!.invoke(tile, listeningToken, false)
        } catch (ignored: ReflectiveOperationException) {}
        listening!!.clear()
        for (recomposer in recomposers!!) try {
            recomposer!!.javaClass.getMethod("cancel").invoke(recomposer)
        } catch (ignored: ReflectiveOperationException) {}
        recomposers!!.clear()
        if (runner != null)
            try {
                load("kotlinx.coroutines.CoroutineScopeKt")!!
                    .getMethod(
                        "cancel",
                        load("kotlinx.coroutines.CoroutineScope"),
                        CancellationException::class.java,
                    )
                    .invoke(null, runner, null)
            } catch (ignored: ReflectiveOperationException) {}
        runner = null
        root!!.removeAllViews()
    }

    companion object {
        private const val TILES: String = "com.android.systemui.samsung.quicksetting.ui.tiles."
    }
}
