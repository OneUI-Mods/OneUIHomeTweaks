package dev.ryuya.oneuihometweaks

import java.lang.Boolean as JavaBoolean
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Reuses BZI9's retained BarWithTile renderer through an immutable presentation list. */
class QuickPanelLegacyLayout(owner: QuickPanelHooks, loader: ClassLoader?) {
    private val owner: QuickPanelHooks
    private var previewState: Any? = null
    private val item: Field
    private val width: Field
    private val height: Field
    private val offset: Field
    private val id: Field
    private val spec: Field
    private val type: Method
    private val copy: Method
    private val stateValue: Method
    private val flowValue: Method
    private val stateInterface: Class<*>
    private val quickTile: Class<*>
    private val brightGroup: Constructor<*>
    private val volumeGroup: Constructor<*>
    private val states: LinkedHashMap<Any?, Any?> = LinkedHashMap<Any?, Any?>()
    private val groups: LinkedHashMap<QuickPanelLegacyLayout.Key?, Any?> =
        LinkedHashMap<QuickPanelLegacyLayout.Key?, Any?>()
    private val aliases: MutableMap<String?, Any?> = ConcurrentHashMap<String?, Any?>()

    @JvmRecord
    private data class Key(val bar: Any?, val button: Any?, val width: Int, val height: Int) {
        override fun toString(): String =
            "Key[bar=$bar, button=$button, width=$width, height=$height]"
    }

    init {
        this.owner = owner
        val panelItem = Class.forName(QuickPanelLegacyLayout.MODEL + "QSPanelItem", false, loader)
        val gridItem =
            Class.forName(QuickPanelLegacyLayout.MODEL + "items.GridTileItem", false, loader)
        val assigned =
            Class.forName(
                QuickPanelLegacyLayout.MODEL + "items.IdAssignedGridTileItem",
                false,
                loader,
            )
        quickTile = Class.forName(QuickPanelLegacyLayout.MODEL + "items.QuickTile", false, loader)
        item = panelItem.getDeclaredField("gridTileItem")
        width = panelItem.getDeclaredField("spanWidth")
        height = panelItem.getDeclaredField("spanHeight")
        offset = panelItem.getDeclaredField("spanOffset")
        id = assigned.getDeclaredField("id")
        spec = quickTile.getDeclaredField("spec")
        type = gridItem.getMethod("getType")
        copy = (QuickPanelLegacyLayout.named(panelItem, "copy-1u2huiI\$default", 12))!!
        brightGroup =
            (QuickPanelLegacyLayout.constructor(
                Class.forName(
                    QuickPanelLegacyLayout.MODEL + "items.BrightBarWithTile",
                    false,
                    loader,
                ),
                6,
            ))!!
        volumeGroup =
            (QuickPanelLegacyLayout.constructor(
                Class.forName(
                    QuickPanelLegacyLayout.MODEL + "items.VolumeBarWithTile",
                    false,
                    loader,
                ),
                7,
            ))!!
        stateInterface = Class.forName("androidx.compose.runtime.State", false, loader)
        stateValue = stateInterface.getMethod("getValue")
        flowValue =
            Class.forName("kotlinx.coroutines.flow.StateFlow", false, loader).getMethod("getValue")
        val scene =
            Class.forName(
                QuickPanelLegacyLayout.PANEL + "QuickSettingSceneViewModel",
                false,
                loader,
            )
        installAlias(owner, scene, "getItemSpanOffset-Bjo55l4", false)
        installAlias(owner, scene, "getItemSpanSize-YEO4UFw", true)
        val store =
            Class.forName(QuickPanelLegacyLayout.PANEL + "PanelItemStoreImpl", false, loader)
        owner.add(
            ModernHook.hookMethod(
                store.getDeclaredMethod("getItem", String::class.java),
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        if (owner.legacy()) {
                            val alias = aliases.get(p.args[0])
                            if (alias != null) p.setResult(alias)
                        }
                    }
                },
            )
        )
        val panel =
            Class.forName(QuickPanelLegacyLayout.PANEL + "QuickSettingTilePanelKt", false, loader)
        val render =
            QuickPanelLegacyLayout.named(panel, "QuickSettingTilePanelScrollableGrid-OzltmbE", 14)
        if (render!!.getParameterTypes()[3] != stateInterface)
            throw UnsupportedOperationException("Panel state contract")
        owner.add(
            ModernHook.hookMethod(
                render,
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        try {
                            val original = unwrap(p.args[3]!!)
                            previewState = original
                            if (!owner.needsRefresh()) return
                            if (owner.legacy()) p.args[3] = wrap(original, loader)
                            else p.args[3] = original
                            // The State argument changed; keep the original composition's native
                            // read
                            // tracking.
                            p.args[12] = (p.args[12] as Int and 7168.inv()) or 2048 or 1
                        } catch (error: Throwable) {
                            owner.stopLegacy(error)
                        }
                    }
                },
            )
        )
        val root = QuickPanelLegacyLayout.named(panel, "QuickSettingTilePanel", 7)
        for (name in arrayOf<String>("BrightBarWithTile", "VolumeBarWithTile")) {
            val draw =
                QuickPanelLegacyLayout.named(
                    Class.forName(
                        "com.android.systemui.samsung.quicksetting.ui.tiles." + name + "Kt",
                        false,
                        loader,
                    ),
                    name,
                    5,
                )
            if (!ModernHook.deoptimize(draw!!))
                throw UnsupportedOperationException("Legacy group call site")
        }
        if (!ModernHook.deoptimize(root!!))
            throw UnsupportedOperationException("Panel render call site")
    }

    @Throws(Exception::class)
    private fun installAlias(
        owner: QuickPanelHooks,
        scene: Class<*>,
        name: String?,
        size: Boolean,
    ) {
        owner.add(
            ModernHook.hookMethod(
                scene.getDeclaredMethod(name, String::class.java),
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        if (!owner.legacy()) return
                        val entry = aliases.get(p.args[0])
                        if (entry == null) return
                        try {
                            p.setResult(
                                if (size)
                                    QuickPanelLegacyLayout.pack(
                                        width.getInt(entry),
                                        height.getInt(entry),
                                    )
                                else offset.getLong(entry)
                            )
                        } catch (error: Throwable) {
                            owner.stopLegacy(error)
                        }
                    }
                },
            )
        )
    }

    @Throws(Exception::class)
    fun previewEntries(): MutableList<*>? {
        val value: Any? = if (previewState == null) null else stateValue.invoke(previewState)
        if (value !is MutableList<*>) return java.util.List.of<Any?>()
        return if (owner.legacy()) present(value) else value
    }

    private fun unwrap(value: Any): Any {
        if (Proxy.isProxyClass(value.javaClass)) {
            val handler = Proxy.getInvocationHandler(value)
            if (handler is QuickPanelLegacyLayout.Presentation) return handler.source
        }
        return value
    }

    private fun wrap(source: Any, loader: ClassLoader?): Any? {
        val existing = states.get(source)
        if (existing != null) return existing
        val proxy =
            Proxy.newProxyInstance(
                loader,
                arrayOf<Class<*>?>(stateInterface),
                Presentation(source),
            )
        states.put(source, proxy)
        if (states.size > 4) states.remove(states.keys.iterator().next())
        return proxy
    }

    private inner class Presentation(source: Any) : StatePresentationHandler() {
        @JvmField val source: Any
        private var lastSource: MutableList<*>? = null
        private var lastResult: MutableList<*>? = null

        init {
            this.source = source
        }

        override fun readValue(): Any? {
            val value = stateValue.invoke(source)
            if (!owner.legacy() || value !is MutableList<*>) return value
            return try {
                if (editing(value)) return value
                if (value === lastSource) return lastResult
                val converted = present(value)
                lastSource = value
                lastResult = converted
                converted
            } catch (error: Throwable) {
                owner.stopLegacy(error)
                value
            }
        }
    }

    @Throws(Exception::class)
    private fun editing(source: MutableList<*>): Boolean {
        for (entry in source) {
            val tile = item.get(entry)
            val name: String? = type.invoke(tile) as String?
            if (name != "BrightBar" && name != "VolumeBar") continue
            val vm = tile.javaClass.getField("viewModel").get(tile)
            val scene = vm.javaClass.getField("sceneViewModel").get(vm)
            val editor = scene.javaClass.getField("$\$delegate_0").get(scene)
            val flow = editor.javaClass.getField("editMode").get(editor)
            return JavaBoolean.TRUE == flowValue.invoke(flow)
        }
        return false
    }

    @Throws(Exception::class)
    private fun present(source: MutableList<*>): MutableList<*> {
        val cells = ArrayList<QuickPanelPairing.Cell?>()
        for (i in source.indices) {
            val entry = source.get(i)
            val tile = item.get(entry)
            val name: String? = type.invoke(tile) as String?
            val location = offset.getLong(entry)
            cells.add(
                QuickPanelPairing.Cell(
                    i,
                    name,
                    if (quickTile.isInstance(tile)) spec.get(tile) as String? else "",
                    (location shr 32).toInt(),
                    location.toInt(),
                    width.getInt(entry),
                    height.getInt(entry),
                )
            )
        }
        val pairs = QuickPanelPairing.pairs(cells)
        if (pairs.isEmpty()) return source
        val replacements = HashMap<Int?, Any?>()
        val removed = HashSet<Int?>()
        for (pair in pairs) {
            val barEntry = source.get(pair!!.bar)
            val bar = item.get(barEntry)
            val button = item.get(source.get(pair!!.button))
            val key = QuickPanelLegacyLayout.Key(bar, button, pair!!.width, pair!!.height)
            var group = groups.get(key)
            if (group == null) {
                val vm = bar.javaClass.getField("viewModel").get(bar)
                val category = bar.javaClass.getSuperclass().getField("category").get(bar)
                val bright: Boolean = type.invoke(bar) == "BrightBar"
                group =
                    if (bright)
                        brightGroup.newInstance(
                            vm,
                            QuickPanelLegacyLayout.pack(pair!!.width, pair!!.height),
                            "BrightBarWithTile",
                            button,
                            category,
                            null,
                        )
                    else
                        volumeGroup.newInstance(
                            vm,
                            QuickPanelLegacyLayout.pack(pair!!.width, pair!!.height),
                            "VolumeBarWithTile",
                            button,
                            category,
                            null,
                            null,
                        )
                groups.put(key, group)
                if (groups.size > 8) {
                    val evicted = groups.remove(groups.keys.iterator().next())
                    aliases.remove(id.get(evicted) as String?)
                }
            }
            val entry =
                copy.invoke(
                    null,
                    barEntry,
                    group,
                    pair!!.width,
                    pair!!.height,
                    QuickPanelLegacyLayout.pack(pair!!.x, pair!!.y),
                    0,
                    null,
                    null,
                    false,
                    false,
                    false,
                    16368,
                )
            replacements.put(pair!!.bar, entry)
            removed.add(pair!!.button)
            aliases.put(id.get(group) as String?, entry)
        }
        val result = ArrayList<Any?>(source.size - removed.size)
        for (i in source.indices) if (!removed.contains(i))
            result.add(replacements.getOrDefault(i, source.get(i)))
        return result
    }

    companion object {
        private const val MODEL: String = "com.android.systemui.samsung.quicksetting.domain.model."
        private const val PANEL: String = "com.android.systemui.samsung.quicksetting.ui.panel."

        @JvmStatic
        private fun pack(x: Int, y: Int): Long {
            return (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
        }

        @JvmStatic
        @Throws(NoSuchMethodException::class)
        private fun constructor(type: Class<*>, count: Int): Constructor<*>? {
            for (candidate in type.getDeclaredConstructors()) if (
                candidate.getParameterCount() == count
            ) {
                candidate.setAccessible(true)
                return candidate
            }
            throw NoSuchMethodException(type.getName())
        }

        @JvmStatic
        @Throws(NoSuchMethodException::class)
        private fun named(type: Class<*>, name: String?, count: Int): Method? {
            for (candidate in type.getDeclaredMethods()) if (
                candidate.getName() == name && candidate.getParameterCount() == count
            )
                return candidate
            throw NoSuchMethodException(type.getName() + "#" + name)
        }
    }
}
