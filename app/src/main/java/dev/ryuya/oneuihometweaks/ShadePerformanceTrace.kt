package dev.ryuya.oneuihometweaks

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RenderNode
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.function.Supplier

/** Local profiling for the SystemUI build used in the performance investigation. */
internal object ShadePerformanceTrace {
    fun install(loader: ClassLoader) {
        if (!HookCompatibility.matchesClockBuild(Build.VERSION.SDK_INT, Build.DISPLAY)) return
        if (BuildConfig.SHADE_WORK_TILE_TRACE) {
            try {
                trackWorkTile(loader)
            } catch (error: Exception) {
                HookEntry.log("SHADE_PERF_WORK_TILE_UNAVAILABLE ${error.javaClass.simpleName}")
            }
            return
        }
        if (BuildConfig.SHADE_RECOMPOSE_TRACE) {
            try {
                trackQuickPanelComposition(loader)
            } catch (error: Exception) {
                HookEntry.log("SHADE_PERF_COMPOSE_PATHS_UNAVAILABLE ${error.javaClass.simpleName}")
            }
            try {
                trackGraphicsTrim(loader)
            } catch (error: Exception) {
                HookEntry.log("SHADE_PERF_GRAPHICS_TRIM_UNAVAILABLE ${error.javaClass.simpleName}")
            }
            try {
                trackShadeSurface(loader)
            } catch (error: Exception) {
                HookEntry.log("SHADE_PERF_SURFACE_UNAVAILABLE ${error.javaClass.simpleName}")
            }
            try {
                trackAnimationEndListeners(loader)
            } catch (error: Exception) {
                HookEntry.log("SHADE_PERF_ANIMATOR_END_UNAVAILABLE ${error.javaClass.simpleName}")
            }
            if (!BuildConfig.SHADE_LAYER_ID_TRACE) return
        }
        try {
            trackLayers(loader, labelLayers = BuildConfig.SHADE_LAYER_ID_TRACE)
        } catch (error: Exception) {
            HookEntry.log("SHADE_PERF_LAYERS_UNAVAILABLE ${error.javaClass.simpleName}")
        }
        try {
            trackPanelCleanup(loader)
        } catch (error: Exception) {
            HookEntry.log("SHADE_PERF_CLEANUP_UNAVAILABLE ${error.javaClass.simpleName}")
        }
        if (BuildConfig.SHADE_LAYER_ID_TRACE) return
        try {
            enableCompositionSlices(loader)
        } catch (error: Exception) {
            HookEntry.log("SHADE_PERF_COMPOSE_UNAVAILABLE ${error.javaClass.simpleName}")
        }
        try {
            trackSlowPowerQueries(loader)
        } catch (error: Exception) {
            HookEntry.log("SHADE_PERF_POWER_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun trackAnimationEndListeners(loader: ClassLoader) {
        val caller = DexSymbols.findClass("android.animation.Animator\$AnimatorCaller", loader)
        val endCaller = checkNotNull(caller.compatibleDeclaredField("ON_END").get(null))
        val dispatch = endCaller.javaClass.compatibleDeclaredMethod(
            "call", Any::class.java, Any::class.java, Boolean::class.javaPrimitiveType,
        )
        val main = Looper.getMainLooper()
        ModernHook.aroundMethod(dispatch) { chain ->
            if (Looper.myLooper() !== main || !Trace.isEnabled() || chain.args[1] !is ValueAnimator) {
                return@aroundMethod chain.proceed()
            }
            val listener = chain.args[0] ?: return@aroundMethod chain.proceed()
            val name = listener.javaClass.name.removePrefix("com.android.systemui.").take(96)
            Trace.beginSection("OneUI shade animation end $name")
            try {
                chain.proceed()
            } finally {
                Trace.endSection()
            }
        }
        HookEntry.log("SHADE_PERF_ANIMATOR_END_READY")
    }

    private fun trackShadeSurface(loader: ClassLoader) {
        val root = DexSymbols.findClass("android.view.ViewRootImpl", loader)
        val shadeWindow = DexSymbols.findClass("com.android.systemui.shade.NotificationShadeWindowView", loader)
        val rootView = root.compatibleDeclaredField("mView")
        val rootSurface = root.compatibleDeclaredField("mSurface")
        val windowAttributes = root.compatibleDeclaredField("mWindowAttributes")
        fun isNotificationShade(viewRoot: Any): Boolean {
            if (shadeWindow.isInstance(rootView.get(viewRoot))) return true
            val attributes = windowAttributes.get(viewRoot) as? WindowManager.LayoutParams
            return attributes?.title?.toString() == "NotificationShade"
        }
        val relayout = root.compatibleDeclaredMethod(
            "relayoutWindow", WindowManager.LayoutParams::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        ModernHook.aroundMethod(relayout) { chain ->
            if (!Trace.isEnabled()) return@aroundMethod chain.proceed()
            val viewRoot = chain.thisObject ?: return@aroundMethod chain.proceed()
            if (!isNotificationShade(viewRoot)) return@aroundMethod chain.proceed()

            val validBefore = (rootSurface.get(viewRoot) as? Surface)?.isValid == true
            Trace.beginSection("OneUI shade relayout visibility=${chain.args[1]} validBefore=$validBefore")
            try {
                val result = chain.proceed()
                val validAfter = (rootSurface.get(viewRoot) as? Surface)?.isValid == true
                Trace.beginSection("OneUI shade relayout returned $result validAfter=$validAfter")
                Trace.endSection()
                result
            } finally {
                Trace.endSection()
            }
        }
        trace(root.compatibleDeclaredMethod("destroySurface"), "ShadeWindow.destroySurface") {
            it.thisObject?.let(::isNotificationShade) == true
        }
        trace(root.compatibleDeclaredMethod("destroyHardwareRenderer"), "ShadeWindow.destroyHardwareRenderer") {
            it.thisObject?.let(::isNotificationShade) == true
        }
        HookEntry.log("SHADE_PERF_SURFACE_READY")
    }

    private fun trackGraphicsTrim(loader: ClassLoader) {
        val windows = DexSymbols.findClass("android.view.WindowManagerGlobal", loader)
        val trim = windows.compatibleDeclaredMethod("trimMemory", Int::class.javaPrimitiveType)
        ModernHook.aroundMethod(trim) { chain ->
            if (!Trace.isEnabled()) return@aroundMethod chain.proceed()
            val caller = Thread.currentThread().stackTrace.firstOrNull { frame ->
                (frame.className == "android.app.ActivityThread" && frame.methodName == "handleTrimMemory") ||
                    frame.className == "com.android.systemui.shade.ShadeControllerImpl" ||
                    frame.className.startsWith("com.android.systemui.keyguard.ResourceTrimmer")
            }
            val source = caller?.className?.substringAfterLast('.') ?: "other"
            Trace.beginSection("OneUI graphics trim ${chain.args[0]} $source")
            try {
                chain.proceed()
            } finally {
                Trace.endSection()
            }
        }
        val shade = DexSymbols.findClass("com.android.systemui.shade.ShadeControllerImpl", loader)
        trace(shade.compatibleDeclaredMethod("makeExpandedInvisible"), "ShadeController.makeExpandedInvisible")
        HookEntry.log("SHADE_PERF_GRAPHICS_TRIM_READY")
    }

    private fun trackQuickPanelComposition(loader: ClassLoader) {
        val ui = "com.android.systemui.samsung.quicksetting.ui"
        val composer = DexSymbols.findClass("androidx.compose.runtime.Composer", loader)
        val roots = listOf(
            "$ui.panel.SecQuickPanelSceneKt" to "QuickPanelSceneMain",
            "$ui.SecQuickSettingSceneKt" to "SecQuickSettingSceneMain",
            "$ui.panel.QuickSettingTilePanelKt" to "QuickSettingTilePanel",
            "$ui.panel.SecQuickSettingHeaderKt" to "HeaderContainer",
        ).map { (className, name) ->
            DexSymbols.findClass(className, loader).declaredMethods.single {
                it.name == name && composer in it.parameterTypes && it.returnType == Void.TYPE
            }
        }
        val tileModel = DexSymbols.findClass(
            "com.android.systemui.samsung.quicksetting.domain.model.items.QuickTile", loader,
        )
        val tileSpec = tileModel.compatibleDeclaredField("spec")
        val quickTile = DexSymbols.findClass("$ui.tiles.QuickTileKt", loader).declaredMethods.single {
            it.name == "QuickTile" && it.parameterCount == 8 && it.parameterTypes[2] == tileModel &&
                it.parameterTypes[5] == composer && it.returnType == Void.TYPE
        }
        val main = Looper.getMainLooper()
        for (method in roots) {
            trace(method, "Compose ${method.name}") { Looper.myLooper() === main }
        }
        ModernHook.aroundMethod(quickTile) { chain ->
            if (Looper.myLooper() !== main || !Trace.isEnabled()) return@aroundMethod chain.proceed()
            val spec = tileSpec.get(chain.args[2]) as? String
            val label = spec?.takeIf { value ->
                value.length in 1..48 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
            } ?: "custom"
            Trace.beginSection("OneUI shade Compose QuickTile $label")
            try {
                chain.proceed()
            } finally {
                Trace.endSection()
            }
        }
        HookEntry.log("SHADE_PERF_COMPOSE_PATHS_READY")
    }

    private fun trackSlowPowerQueries(loader: ClassLoader) {
        val proxy = DexSymbols.findClass("android.os.IPowerManager\$Stub\$Proxy", loader)
        val query = proxy.compatibleDeclaredMethod("isInteractiveForDisplay", Int::class.javaPrimitiveType)
        val main = Looper.getMainLooper()
        var remainingReports = 10
        ModernHook.aroundMethod(query) { chain ->
            if (Looper.myLooper() !== main || !Trace.isEnabled() || remainingReports == 0) {
                return@aroundMethod chain.proceed()
            }
            val start = SystemClock.elapsedRealtimeNanos()
            try {
                chain.proceed()
            } finally {
                val duration = SystemClock.elapsedRealtimeNanos() - start
                if (duration >= 2_500_000L) {
                    remainingReports--
                    val callers = Thread.currentThread().stackTrace.asSequence()
                        .filter { frame ->
                            frame.className.startsWith("com.android.systemui.") ||
                                frame.className.startsWith("android.view.") ||
                                frame.className.startsWith("dev.ryuya.galaxybar.") ||
                                frame.className.startsWith("dev.ryuya.galaxyglass.")
                        }
                        .take(9)
                        .joinToString(" <- ") { "${it.className}.${it.methodName}" }
                    HookEntry.log("SHADE_PERF_POWER_CALL duration_us=${duration / 1000} callers=$callers")
                }
            }
        }
    }

    private fun enableCompositionSlices(loader: ClassLoader) {
        // BZI9 already includes the SystemUI startup tracer. Its compiled ComposerKt checks
        // Trace.isEnabled() before emitting per-composable slices.
        val runtime = DexSymbols.findClass("androidx.compose.runtime.ComposerKt", loader)
        val tracer = runtime.compatibleDeclaredField("compositionTracer")
        check(tracer.type.name == "com.android.systemui.compose.CompositionSlicesTracing\$enable\$1")
        if (tracer.get(null) == null) {
            tracer.set(null, tracer.type.getDeclaredConstructor().newInstance())
        }
        HookEntry.log("SHADE_PERF_COMPOSE_TRACE_READY")
    }

    private data class LayerState(val dimensions: Long, val compositing: Boolean)

    private fun trackLayers(loader: ClassLoader, labelLayers: Boolean) {
        val layerClass = DexSymbols.findClass("androidx.compose.ui.platform.GraphicsLayerOwnerLayer", loader)
        val layer = layerClass.compatibleDeclaredField("graphicsLayer")
        val view = layerClass.compatibleDeclaredField("ownerView")
        val size = layerClass.compatibleDeclaredField("size")
        val drawBlock = layerClass.compatibleDeclaredField("drawBlock")
        val impl = layer.type.compatibleDeclaredField("impl")
        val v29 = DexSymbols.findClass("androidx.compose.ui.graphics.layer.GraphicsLayerV29", loader)
        val renderNode = v29.compatibleDeclaredField("renderNode")
        val coordinatorClass = DexSymbols.findClass("androidx.compose.ui.node.NodeCoordinator", loader)
        val captured = DexSymbols.findClass("androidx.compose.ui.node.NodeCoordinator\$drawBlock\$1", loader)
            .declaredFields.single { it.type == coordinatorClass }.apply { isAccessible = true }
        val layerBlock = coordinatorClass.compatibleDeclaredField("layerBlock")
        val layoutNode = coordinatorClass.compatibleDeclaredField("layoutNode")
        val measurePolicy = layoutNode.type.compatibleDeclaredField("measurePolicy")
        val updateDisplayList = layerClass.compatibleDeclaredMethod("updateDisplayList")
        val labels = if (labelLayers) labelComposeRenderNodes(v29, loader) else emptyMap()
        val reportLimit = if (labelLayers) 180 else 36

        var reports = 0
        val seen = WeakHashMap<RenderNode, LayerState>()
        ModernHook.hookMethod(updateDisplayList, object : ModernHook.Callback() {
            override fun afterHookedMethod(param: ModernHook.Param) {
                if (reports >= reportLimit || param.hasThrowable() || Looper.myLooper() != Looper.getMainLooper()) return
                if (labelLayers && !Trace.isEnabled()) return
                val owner = param.thisObject ?: return
                val dimensions = size.getLong(owner)
                val width = (dimensions shr 32).toInt()
                val height = dimensions.toInt()
                val metrics = (view.get(owner) as View).resources.displayMetrics
                if (width != metrics.widthPixels || height !in (metrics.heightPixels * 4 / 5)..metrics.heightPixels) return
                val implementation = impl.get(layer.get(owner))
                if (!v29.isInstance(implementation)) return
                val node = renderNode.get(implementation) as RenderNode
                val compositing = node.useCompositingLayer
                val state = LayerState(dimensions, compositing)
                if (seen.put(node, state) == state) return
                val label = labels[node] ?: "graphicsLayer"
                val draw = drawBlock.get(owner)
                val ownerNode = if (captured.declaringClass.isInstance(draw)) captured.get(draw) else null
                val modifier = ownerNode?.let { layerBlock.get(it)?.javaClass?.name }
                val layout = ownerNode?.let(layoutNode::get)
                val policy = layout?.let { measurePolicy.get(it)?.javaClass?.name }
                val layoutId = layout?.let { System.identityHashCode(it).toString(16) }
                if (Trace.isEnabled()) {
                    Trace.beginSection("Shade layer ${node.uniqueId} $label ${width}x$height $compositing")
                    Trace.endSection()
                }
                HookEntry.log("SHADE_PERF_LAYER native=${node.uniqueId} label=$label ${width}x$height " +
                    "compositing=$compositing layout=$layoutId modifier=$modifier policy=$policy")
                reports++
            }
        })
        if (labelLayers) {
            val destroy = layerClass.compatibleDeclaredMethod("destroy")
            ModernHook.aroundMethod(destroy) { chain ->
                if (!Trace.isEnabled() || Looper.myLooper() != Looper.getMainLooper()) {
                    return@aroundMethod chain.proceed()
                }
                val implementation = impl.get(layer.get(chain.thisObject))
                if (!v29.isInstance(implementation)) return@aroundMethod chain.proceed()
                val node = renderNode.get(implementation) as RenderNode
                if (seen.remove(node) == null) return@aroundMethod chain.proceed()
                val label = labels[node] ?: "graphicsLayer"
                Trace.beginSection("Shade layer release ${node.uniqueId} $label")
                try {
                    chain.proceed()
                } finally {
                    Trace.endSection()
                }
            }
        }
        HookEntry.log("SHADE_PERF_LAYER_TRACE_READY")
    }

    /** A diagnostic-only name that appears in HWUI's native drawLayer trace. */
    private fun labelComposeRenderNodes(v29: Class<*>, loader: ClassLoader): Map<RenderNode, String> {
        val canvasHolder = DexSymbols.findClass("androidx.compose.ui.graphics.CanvasHolder", loader)
        val canvasDrawScope = DexSymbols.findClass("androidx.compose.ui.graphics.drawscope.CanvasDrawScope", loader)
        val composeConstructor = v29.getDeclaredConstructor(
            Long::class.javaPrimitiveType, canvasHolder, canvasDrawScope,
        )
        val renderConstructor = RenderNode::class.java.getDeclaredConstructor(String::class.java)
        val main = Looper.getMainLooper()
        val labels = WeakHashMap<RenderNode, String>()
        var inComposeConstructor = false
        var sequence = 0
        val renderHook = ModernHook.aroundMethod(renderConstructor) { chain ->
            if (Looper.myLooper() !== main || !inComposeConstructor || chain.args[0] != "graphicsLayer") {
                return@aroundMethod chain.proceed()
            }
            val label = "graphicsLayer-OneUI-${++sequence}"
            val result = chain.proceed(arrayOf(label))
            (chain.thisObject as? RenderNode)?.let { labels[it] = label }
            result
        }
        try {
            ModernHook.deoptimize(composeConstructor)
            ModernHook.aroundMethod(composeConstructor) { chain ->
                if (Looper.myLooper() !== main || inComposeConstructor) return@aroundMethod chain.proceed()
                inComposeConstructor = true
                try {
                    chain.proceed()
                } finally {
                    inComposeConstructor = false
                }
            }
        } catch (error: Exception) {
            renderHook.unhook()
            throw error
        }
        return labels
    }

    private fun trackPanelCleanup(loader: ClassLoader) {
        val controller = DexSymbols.findClass("com.android.systemui.shade.NotificationPanelViewController", loader)
        trace(controller, "onFlingEnd", Boolean::class.javaPrimitiveType)
        trace(controller, "notifyExpandingFinished")
        val stack = DexSymbols.findClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", loader)
        trace(stack, "clearTemporaryViewsInGroup", ViewGroup::class.java, String::class.java)
        trace(stack, "resetAllSwipeState")
        trace(stack, "resetScrollPosition")
        val row = DexSymbols.findClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", loader)
        trace(row, "setUserSwipingToExpandRow", Boolean::class.javaPrimitiveType)
    }

    private fun trackWorkTile(loader: ClassLoader) {
        val workMode = DexSymbols.findClass("com.android.systemui.qs.tiles.SWorkModeTile", loader)
        trace(workMode.compatibleDeclaredMethod("getTileLabel"), "WorkMode.getTileLabel")
        trace(workMode.compatibleDeclaredMethod("isAvailable"), "WorkMode.isAvailable")

        val tile = DexSymbols.findClass(
            "com.android.systemui.samsung.quicksetting.domain.model.items.QuickTile", loader,
        )
        val tileImplField = tile.compatibleDeclaredField("tile")
        val specField = tile.compatibleDeclaredField("spec")
        fun isWorkTile(model: Any?): Boolean {
            if (model == null) return false
            val spec = specField.get(model)
            return spec == "WorkMode" || spec == "work" || workMode.isInstance(tileImplField.get(model))
        }
        trace(tile.compatibleDeclaredMethod("getTileStateFlow", String::class.java), "WorkMode.getTileStateFlow") {
            isWorkTile(it.thisObject)
        }

        val tileComposable = DexSymbols.findClass(
            "com.android.systemui.samsung.quicksetting.ui.tiles.QuickTileKt", loader,
        ).declaredMethods.single { candidate ->
            candidate.name == "QuickTile" && candidate.parameterCount == 8 && candidate.parameterTypes[2] == tile
        }
        var loggedWorkTile = false
        trace(tileComposable, "WorkMode.compose") {
            val model = it.args[2]
            if (!isWorkTile(model)) return@trace false
            if (!loggedWorkTile) {
                loggedWorkTile = true
                HookEntry.log("SHADE_PERF_WORK_TILE_IMPL ${tileImplField.get(model)?.javaClass?.name}")
            }
            true
        }

        val tileImpl = DexSymbols.findClass("com.android.systemui.qs.tileimpl.SQSTileImpl", loader)
        trace(tileImpl.compatibleDeclaredMethod("isNonToggleTile"), "WorkMode.isNonToggleTile") {
            workMode.isInstance(it.thisObject)
        }

        val policyResources = DexSymbols.findClass("android.app.admin.DevicePolicyResourcesManager", loader)
        trace(
            policyResources.compatibleDeclaredMethod("getString", String::class.java, Supplier::class.java),
            "WorkMode.policyLabel",
        ) { it.args[0] == "SystemUi.QS_WORK_PROFILE_LABEL" }

        val modelMapper = DexSymbols.findClass(
            "com.android.systemui.qs.tiles.impl.work.ui.mapper.WorkModeTileMapper", loader,
        )
        trace(modelMapper.declaredMethods.single { it.name == "map" && it.parameterCount == 2 }, "WorkMode.map")

        val icon = DexSymbols.findClass("com.android.systemui.qs.tileimpl.QSTileImpl\$ResourceIcon", loader)
        val resourceId = icon.compatibleDeclaredField("mResId")
        for (method in listOf("getDrawable", "getInvisibleDrawable")) {
            trace(icon.compatibleDeclaredMethod(method, Context::class.java), "WorkMode.$method") {
                // The verified BZI9 resource sec_stat_sys_managed_profile_status.
                resourceId.getInt(it.thisObject) == 0x7e08116c
            }
        }

        val drawableIcon = DexSymbols.findClass("com.android.systemui.qs.tileimpl.QSTileImpl\$DrawableIcon", loader)
        val drawableIconWithRes = DexSymbols.findClass(
            "com.android.systemui.qs.tileimpl.QSTileImpl\$DrawableIconWithRes", loader,
        )
        val drawableId = drawableIconWithRes.compatibleDeclaredField("mId")
        for (method in listOf("getDrawable", "getInvisibleDrawable")) {
            trace(drawableIcon.compatibleDeclaredMethod(method, Context::class.java), "WorkMode.loaded.$method") {
                // The adapter uses qs_work_mode_icon_off and qs_work_mode_icon_on.
                drawableIconWithRes.isInstance(it.thisObject) && drawableId.getInt(it.thisObject) in 0x7e080fec..0x7e080fed
            }
        }
        HookEntry.log("SHADE_PERF_WORK_TILE_TRACE_READY")
    }

    private fun trace(owner: Class<*>, method: String, vararg args: Class<*>?) {
        trace(owner.compatibleDeclaredMethod(method, *args), method)
    }

    private fun trace(
        method: Method,
        label: String,
        include: (XposedInterface.Chain) -> Boolean = { true },
    ) {
        ModernHook.aroundMethod(method) { chain ->
            if (!Trace.isEnabled() || !include(chain)) return@aroundMethod chain.proceed()
            Trace.beginSection("OneUI shade $label")
            try {
                chain.proceed()
            } finally {
                Trace.endSection()
            }
        }
    }
}
