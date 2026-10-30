package dev.ryuya.oneuihometweaks

import android.content.Context
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Method
import java.util.HashSet
import java.util.WeakHashMap
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.log

/*
 * BZI9 Compose contract. Changes immutable layout arguments, never snapshot state or final fields.
 */
class QuickPanelAndroid17(owner: QuickPanelHooks, loader: ClassLoader?) : QuickPanelHooks.Adapter {
    private val owner: QuickPanelHooks
    private var legacyLayout: QuickPanelLegacyLayout? = null

    @JvmRecord
    private data class BarFrame(val kind: Int, val nativeDp: Float, val grouped: Boolean) {
        override fun toString(): String =
            "BarFrame[kind=$kind, nativeDp=$nativeDp, grouped=$grouped]"
    }

    private val groupedScopes: MutableMap<Any?, Boolean?> = WeakHashMap<Any?, Boolean?>()
    private val previewScopes: MutableMap<Any?, Int?> = WeakHashMap<Any?, Int?>()
    private val barFrame: ThreadLocal<QuickPanelAndroid17.BarFrame?> =
        ThreadLocal<QuickPanelAndroid17.BarFrame?>()
    private val buttonKind: ThreadLocal<Int?> = ThreadLocal<Int?>()
    private val evidence: MutableSet<String?> = HashSet<String?>()
    private val nativeButtonModifiers: MutableMap<Any?, Any?> = WeakHashMap<Any?, Any?>()
    private val requiredWidth: Method
    private val requiredHeight: Method
    private val then: Method
    private val emptyModifier: Any

    init {
        this.owner = owner
        val modifier = Class.forName("androidx.compose.ui.Modifier", false, loader)
        emptyModifier = modifier.getField("Companion").get(null)
        then = modifier.getMethod("then", modifier)
        val sizes = Class.forName("androidx.compose.foundation.layout.SizeKt", false, loader)
        requiredWidth =
            sizes.getDeclaredMethod(
                "requiredWidth-3ABfNKs",
                modifier,
                Float::class.javaPrimitiveType,
            )
        this.requiredHeight =
            sizes.getDeclaredMethod(
                "requiredHeight-3ABfNKs",
                modifier,
                Float::class.javaPrimitiveType,
            )
        installButtons(loader, modifier, sizes)
        installBars(loader, modifier)
        try {
            legacyLayout = QuickPanelLegacyLayout(owner, loader)
        } catch (error: Throwable) {
            owner.stopLegacy(error)
        }
    }

    fun previewScope(scope: Any?, kind: Int, grouped: Boolean) {
        previewScopes.put(scope, kind)
        if (grouped) groupedScopes.put(scope, true)
    }

    @Throws(Exception::class)
    fun previewEntries(): MutableList<*>? {
        return if (legacyLayout == null) java.util.List.of<Any?>()
        else legacyLayout!!.previewEntries()
    }

    @Throws(Exception::class)
    private fun installButtons(loader: ClassLoader?, modifier: Class<*>?, sizes: Class<*>) {
        val size = sizes.getDeclaredMethod("size-3ABfNKs", modifier, Float::class.javaPrimitiveType)
        val requiredSize =
            sizes.getDeclaredMethod(
                "requiredSize-3ABfNKs",
                modifier,
                Float::class.javaPrimitiveType,
            )
        val tile =
            Class.forName(
                "com.android.systemui.samsung.quicksetting.domain.model.items.QuickTile",
                false,
                loader,
            )
        val spec = tile.getDeclaredField("spec")
        val companion =
            QuickPanelAndroid17.named(
                Class.forName(QuickPanelAndroid17.TILES + "CompanionQuickTileKt", false, loader),
                "CompanionQuickTile",
                6,
            )
        if (
            companion!!.getParameterTypes()[2] != tile ||
                companion!!.getParameterTypes()[4] != Int::class.javaPrimitiveType
        )
            throw UnsupportedOperationException("Companion tile contract")
        owner.add(
            ModernHook.aroundMethod(
                companion,
                ModernHook.Around { chain: Chain? ->
                    val args = chain!!.getArgs().toTypedArray()
                    val previous = buttonKind.get()
                    try {
                        val name: String? = spec.get(args[2]) as String?
                        if (evidence.add("button:" + name))
                            HookEntry.log("QUICK_PANEL_BUTTON " + name)
                        val kind: Int =
                            if ("UiModeNight" == name) 2 else if ("SoundMode" == name) 3 else -1
                        if (kind >= 0 && owner.needsRefresh()) {
                            buttonKind.set(kind)
                            args[4] = args[4] as Int or 1
                        } else buttonKind.remove()
                        return@Around chain!!.proceed(args)
                    } finally {
                        QuickPanelAndroid17.restore<Int?>(buttonKind, previous)
                    }
                },
            )
        )
        val scale =
            Class.forName("androidx.compose.ui.draw.ScaleKt", false, loader)
                .getDeclaredMethod(
                    "scale",
                    modifier,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                )
        val quickTile =
            QuickPanelAndroid17.named(
                Class.forName(QuickPanelAndroid17.TILES + "QuickTileKt", false, loader),
                "QuickTile",
                8,
            )
        owner.add(
            ModernHook.aroundMethod(
                quickTile!!,
                ModernHook.Around { chain: Chain? ->
                    val args = chain!!.getArgs().toTypedArray()
                    val name: String? = spec.get(args[2]) as String?
                    val kind: Int =
                        if ("UiModeNight" == name) 2 else if ("SoundMode" == name) 3 else -1
                    if (kind >= 0 && owner.needsRefresh()) {
                        args[6] = args[6] as Int or 1
                        val supplied: Any? = args[1]
                        val nativeModifier: Any? =
                            if ((args[7] as Int and 1) != 0) emptyModifier
                            else nativeButtonModifiers.getOrDefault(supplied, supplied)
                        if (nativeModifier !== supplied) {
                            args[1] = nativeModifier
                            args[7] = args[7] as Int and 1.inv()
                            args[6] = (args[6] as Int and 112.inv()) or 32 or 1
                        }
                        if (owner.size(kind) != 100) {
                            val ratio: Float = owner.size(kind) / 100f
                            val scaled = scale.invoke(null, emptyModifier, ratio, ratio)
                            args[1] = then.invoke(scaled, nativeModifier)
                            nativeButtonModifiers.put(args[1], nativeModifier)
                            args[7] = args[7] as Int and 1.inv()
                            args[6] = (args[6] as Int and 112.inv()) or 32 or 1
                            evidence(kind, 100f, owner.size(kind).toFloat())
                        }
                    }
                    chain!!.proceed(args)
                },
            )
        )
        if (!ModernHook.deoptimize(quickTile!!))
            throw UnsupportedOperationException("Grid tile call site")
        val grid =
            Class.forName(
                "com.android.systemui.samsung.quicksetting.ui.base.spannablegrid.SpannableGridTileKt",
                false,
                loader,
            )
        for (call in grid.getDeclaredMethods()) {
            if (call.getName() == "SpannableGridTile" && !ModernHook.deoptimize(call))
                throw UnsupportedOperationException("Grid caller")
        }
        owner.add(
            ModernHook.hookMethod(
                size,
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        val kind = buttonKind.get()
                        if (kind == null || owner.size(kind) == 100) return
                        val nativeDp: Float = p.args[1] as Float
                        if (!java.lang.Float.isFinite(nativeDp) || nativeDp <= 0) return
                        val target: Float =
                            if (owner.legacy())
                                QuickPanelPolicy.legacyDimension(nativeDp, owner.size(kind))
                            else QuickPanelPolicy.dimension(nativeDp, owner.size(kind))
                        try {
                            val sized = requiredSize.invoke(null, emptyModifier, target)
                            p.setResult(then.invoke(sized, p.args[0]))
                            evidence(kind, nativeDp, target)
                        } catch (error: Throwable) {
                            owner.stopLegacy(error)
                        }
                    }
                },
            )
        )
        for (caller in
            arrayOf<String>(
                "BrightBarWithTileKt$\$ExternalSyntheticLambda10",
                "VolumeBarWithTileKt$\$ExternalSyntheticLambda2",
            )) {
            val call =
                Class.forName(QuickPanelAndroid17.TILES + caller, false, loader)
                    .getDeclaredMethod("invoke", Any::class.java, Any::class.java)
            if (!ModernHook.deoptimize(call))
                throw UnsupportedOperationException("Companion caller")
        }
        if (!ModernHook.deoptimize(companion)) throw UnsupportedOperationException("Tile call site")
    }

    @Throws(Exception::class)
    private fun installBars(loader: ClassLoader?, modifier: Class<*>?) {
        // This exact closure is the BoxWithConstraints content of IconProgressBar on BZI9.
        // The 100 / 150 discriminator comes from the reviewed BrightBar / VolumeBar call sites.
        val content =
            Class.forName(
                QuickPanelAndroid17.TILES + "IconProgressBarKt$\$ExternalSyntheticLambda8",
                false,
                loader,
            )
        val kindField = content.getDeclaredField("f$31")
        if (
            kindField.getType() != Int::class.javaPrimitiveType ||
                content.getDeclaredField("f$1").getType() != Float::class.javaPrimitiveType ||
                content.getDeclaredField("f$2").getType() != modifier
        )
            throw UnsupportedOperationException("Slider closure contract")
        val verticalField = content.getDeclaredField("f$0")
        val scopeField = content.getDeclaredField("f$8")
        val contextField = content.getDeclaredField("f$20")
        val constraints =
            Class.forName(
                "androidx.compose.foundation.layout.BoxWithConstraintsScopeImpl",
                false,
                loader,
            )
        val maxWidth = constraints.getDeclaredMethod("getMaxWidth-D9Ej5fM")
        val maxHeight = constraints.getDeclaredMethod("getMaxHeight-D9Ej5fM")
        val invoke =
            content.getDeclaredMethod("invoke", Any::class.java, Any::class.java, Any::class.java)
        owner.add(
            ModernHook.aroundMethod(
                invoke,
                ModernHook.Around { chain: Chain? ->
                    val previous = barFrame.get()
                    val args = chain!!.getArgs().toTypedArray()
                    try {
                        val discriminator = kindField.getInt(chain!!.getThisObject())
                        if (evidence.add("bar:" + discriminator))
                            HookEntry.log("QUICK_PANEL_BAR " + discriminator)
                        var kind: Int =
                            if (discriminator == 100) 0 else if (discriminator == 150) 1 else -1
                        val previewKind = previewScopes.get(scopeField.get(chain!!.getThisObject()))
                        if (previewKind != null) kind = previewKind
                        if (kind >= 0 && owner.needsRefresh()) {
                            var dp: Float =
                                (if (verticalField.get(chain!!.getThisObject()) as Boolean) maxWidth
                                    else maxHeight)
                                    .invoke(args[0]) as Float
                            val grouped: Boolean =
                                owner.legacy() &&
                                    groupedScopes.containsKey(
                                        scopeField.get(chain!!.getThisObject())
                                    )
                            if (grouped) {
                                val context: Context? =
                                    contextField.get(chain!!.getThisObject()) as Context?
                                val resources = context!!.getResources()
                                val dimension =
                                    resources.getIdentifier(
                                        "sec_style_qs_slider_tile_size",
                                        "dimen",
                                        "com.android.systemui",
                                    )
                                if (dimension == 0)
                                    throw UnsupportedOperationException("Legacy bar dimension")
                                dp =
                                    resources.getDimension(dimension) /
                                        resources.getDisplayMetrics().density
                            }
                            barFrame.set(QuickPanelAndroid17.BarFrame(kind, dp, grouped))
                            args[2] = args[2] as Int or 1
                        } else barFrame.remove()
                        return@Around chain!!.proceed(args)
                    } finally {
                        QuickPanelAndroid17.restore<QuickPanelAndroid17.BarFrame?>(
                            barFrame,
                            previous,
                        )
                    }
                },
            )
        )
        val bar =
            QuickPanelAndroid17.named(
                Class.forName(QuickPanelAndroid17.TILES + "BidirectionalBarKt", false, loader),
                "BidirectionalBar-Ld4roP0",
                19,
            )
        val signature = bar!!.getParameterTypes()
        if (
            signature[0] != modifier ||
                signature[2] != Long::class.javaPrimitiveType ||
                signature[3] != Float::class.javaPrimitiveType ||
                signature[4] != Boolean::class.javaPrimitiveType
        )
            throw UnsupportedOperationException("Bar contract")
        owner.add(
            ModernHook.hookMethod(
                bar,
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        val frame = barFrame.get()
                        if (frame == null || (owner.size(frame.kind) == 100 && !frame.grouped))
                            return
                        val kind = frame.kind
                        try {
                            val nativeDp = frame.nativeDp
                            if (!java.lang.Float.isFinite(nativeDp) || nativeDp <= 0) return
                            if (evidence.add("geometry:" + kind))
                                HookEntry.log(
                                    ("QUICK_PANEL_GEOMETRY kind=" +
                                        kind +
                                        " size=" +
                                        p.args[2] +
                                        " dp=" +
                                        nativeDp +
                                        " vertical=" +
                                        p.args[4])
                                )
                            val targetDp: Float =
                                if (frame.grouped)
                                    QuickPanelPolicy.legacyDimension(nativeDp, owner.size(kind))
                                else QuickPanelPolicy.dimension(nativeDp, owner.size(kind))
                            val vertical: Boolean = p.args[4] as Boolean
                            var next =
                                (if (vertical) requiredWidth else requiredHeight).invoke(
                                    null,
                                    emptyModifier,
                                    targetDp,
                                )
                            next = then.invoke(next, p.args[0])
                            val packed =
                                QuickPanelPolicy.thickness(
                                    p.args[2] as Long,
                                    vertical,
                                    targetDp / nativeDp,
                                )
                            p.args[0] = next
                            p.args[2] = packed
                            p.args[3] = targetDp
                            evidence(kind, nativeDp, targetDp)
                        } catch (error: Throwable) {
                            owner.fail(error)
                        }
                    }
                },
            )
        )
        if (!ModernHook.deoptimize(invoke)) throw UnsupportedOperationException("Bar call site")
        // Reopening the panel recomposes each native item; changed sizes must reach the inner
        // content.
        for (name in
            arrayOf<String>("BrightBar", "VolumeBar", "BrightBarWithTile", "VolumeBarWithTile")) {
            val root =
                QuickPanelAndroid17.named(
                    Class.forName(QuickPanelAndroid17.TILES + name + "Kt", false, loader),
                    name,
                    5,
                )
            owner.add(
                ModernHook.hookMethod(
                    root!!,
                    object : ModernHook.Callback() {
                        override fun beforeHookedMethod(p: ModernHook.Param) {
                            if (name.endsWith("WithTile") && owner.legacy())
                                groupedScopes.put(p.args[0], true)
                            if (owner.needsRefresh()) p.args[4] = p.args[4] as Int or 1
                        }
                    },
                )
            )
        }
    }

    private fun evidence(kind: Int, from: Float, to: Float) {
        val value: String = kind.toString() + ":" + from + ":" + to
        if (evidence.size < 64 && evidence.add(value)) HookEntry.log("QUICK_PANEL_SIZE " + value)
    }

    override fun refresh() {
        /* Native presenting state remeasures on the next panel opening. */
    }

    override fun close() {
        barFrame.remove()
        buttonKind.remove()
    }

    companion object {
        private const val TILES: String = "com.android.systemui.samsung.quicksetting.ui.tiles."

        @JvmStatic
        private fun <T> restore(local: ThreadLocal<T?>, value: T?) {
            if (value == null) local.remove() else local.set(value)
        }

        @JvmStatic
        @Throws(NoSuchMethodException::class)
        private fun named(type: Class<*>, name: String?, count: Int): Method? {
            var method: Method? = null
            for (candidate in type.getDeclaredMethods()) {
                if (candidate.getName() != name || candidate.getParameterCount() != count) continue
                if (method != null) throw NoSuchMethodException("Ambiguous " + name)
                method = candidate
            }
            if (method == null) throw NoSuchMethodException(type.getName() + "#" + name)
            return method
        }
    }
}
