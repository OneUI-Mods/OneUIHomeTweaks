package dev.ryuya.oneuihometweaks

import dev.ryuya.oneuihometweaks.compat.DexRuntimeCache
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleName
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData

/** Call-graph proofs are explored once per APK generation; runtime reflection stays uncached. */
object DexEvidence {
    private fun cached(
        apk: String?,
        rule: String,
        discover: (DexKitBridge) -> Map<String, String?>,
    ) = DexRuntimeCache.query(requireNotNull(apk), rule, discover)

    private fun records(methods: Collection<MethodData>): Map<String, String?> =
        linkedMapOf<String, String?>().apply {
            DexRuntimeCache.putList(
                this,
                "callers",
                methods.map { it.className + "#" + it.name }.distinct().sorted(),
            )
        }

    /**
     * Preserve native overload and constructor expansion, not only the directly calling overload.
     */
    internal fun members(
        values: Map<String, String?>,
        loader: ClassLoader?,
        constructors: Boolean = true,
        concreteOnly: Boolean = true,
    ): Set<Executable> = buildSet {
        for ((name, calls) in
            DexRuntimeCache.list(values, "callers").groupBy { it.substringBefore('#') }) {
            val type = Class.forName(name, false, loader)
            val names = calls.map { it.substringAfter('#') }.toSet()
            if (constructors && "<init>" in names) addAll(type.declaredConstructors)
            addAll(
                type.declaredMethods.filter {
                    it.name in names &&
                        (!concreteOnly ||
                            (!Modifier.isAbstract(it.modifiers) &&
                                !Modifier.isNative(it.modifiers)))
                }
            )
        }
    }

    private fun callers(
        bridge: DexKitBridge,
        owner: String,
        method: String? = null,
        prefix: Boolean = false,
    ): List<MethodData> = bridge.findMethod {
        matcher {
            invokeMethods {
                add {
                    declaredClass(
                        owner,
                        if (prefix) StringMatchType.StartsWith else StringMatchType.Equals,
                    )
                    if (method != null) name(method)
                }
            }
        }
    }

    private fun deoptimize(
        values: Map<String, String?>,
        loader: ClassLoader?,
        budget: Int,
        label: String,
    ): Int {
        val found = members(values, loader)
        check(found.isNotEmpty() && found.size <= budget) { "$label caller contract changed" }
        for (method in found) check(ModernHook.deoptimize(method)) {
            "$label deoptimization unavailable"
        }
        return found.size
    }

    @JvmStatic
    fun deoptimizeRecentsLiveCalls(
        apk: String?,
        loader: ClassLoader?,
        targets: MutableList<Method?>,
    ) {
        val identities =
            targets.map { requireNotNull(it).let { m -> m.declaringClass.name to m.name } } +
                ("com.honeyspace.gesture.recentsanimation.RecentsAnimationAction" to "start")
        val values =
            cached(apk, "recents-live-v2:" + identities.sortedBy { it.toString() }) { bridge ->
                records(identities.flatMap { (owner, method) -> callers(bridge, owner, method) })
            }
        val found =
            members(values, loader, constructors = false, concreteOnly = false) +
                targets.map { requireNotNull(it) }
        check(found.isNotEmpty() && found.size <= 96) { "Live Recents caller set changed" }
        found.forEach {
            check(ModernHook.deoptimize(it)) { "Cannot deoptimize live Recents caller" }
        }
        HookEntry.log("RECENTS_LIVE_DEX_VERIFIED callers=${found.size}")
    }

    @JvmStatic
    fun capturedEmitter(
        apk: String?,
        loader: ClassLoader?,
        ownerType: Class<*>,
        payload: Class<*>,
        marker: String?,
        calledMethod: String?,
    ): CapturedEmitter? {
        val rule = "captured-emitter-v2:${ownerType.name}:${payload.name}:$marker:$calledMethod"
        val values =
            cached(apk, rule) { bridge ->
                val candidates =
                    bridge
                        .findClass {
                            matcher { interfaces { add("kotlinx.coroutines.flow.FlowCollector") } }
                        }
                        .flatMap { type ->
                            if (type.fields.count { it.typeName == ownerType.name } != 1)
                                emptyList()
                            else
                                type.methods
                                    .filter { method ->
                                        method.name == "emit" &&
                                            method.paramTypeNames ==
                                                listOf(
                                                    payload.name,
                                                    "kotlin.coroutines.Continuation",
                                                ) &&
                                            (marker == null ||
                                                method.usingStrings.any { marker in it }) &&
                                            method.invokes.any { it.name == calledMethod }
                                    }
                                    .map { it.className }
                        }
                check(candidates.size == 1) { "Ambiguous captured emitter ${payload.name}" }
                mapOf("type" to candidates.single())
            }
        val type = Class.forName(requireNotNull(values["type"]), false, loader)
        val field =
            type.declaredFields.single { it.type == ownerType }.apply { isAccessible = true }
        val emit =
            type
                .getDeclaredMethod(
                    "emit",
                    payload,
                    Class.forName("kotlin.coroutines.Continuation", false, loader),
                )
                .apply { isAccessible = true }
        type.declaredMethods.forEach {
            check(ModernHook.deoptimize(it)) { "Cannot deoptimize captured emitter" }
        }
        return CapturedEmitter(emit, field)
    }

    @JvmStatic
    fun deoptimizeLayoutCalls(apk: String?, loader: ClassLoader?): Int {
        val logical =
            listOf(
                "z5.b" to "j",
                "com.honeyspace.ui.honeypots.freegrid.viewmodel.FreeGridViewModel" to
                    "updateAllItemByLayoutChanged",
                "com.honeyspace.ui.honeypots.freegrid.domain.model.FreeGridDataSanitizer" to
                    "sanitizeOnCellLayoutSizeChanged",
                "com.honeyspace.ui.honeypots.freegrid.domain.repository.FreeGridRepository" to
                    "update",
            )
        val targets = logical.map { (name, method) ->
            val type = DexSymbols.findClass(name, false, loader)
            val actual =
                type.declaredMethods
                    .filter { it.compatibleName() == method }
                    .map { it.name }
                    .distinct()
                    .single()
            type.name to actual
        }
        val values =
            cached(apk, "layout-v2:$targets") { bridge ->
                records(targets.flatMap { (owner, method) -> callers(bridge, owner, method) })
            }
        return deoptimize(values, loader, 160, "Layout")
    }

    @JvmStatic
    fun deoptimizeDiyPositions(apk: String?, loader: ClassLoader?) {
        val values =
            cached(apk, "diy-position-v2") { bridge ->
                val getters =
                    callers(
                            bridge,
                            "com.honeyspace.ui.honeypots.freegrid.domain.model.FreeGridItem",
                            prefix = true,
                        )
                        .filter { method ->
                            method.invokes.any {
                                it.className.startsWith(
                                    "com.honeyspace.ui.honeypots.freegrid.domain.model.FreeGridItem"
                                ) &&
                                    it.name in setOf("getX", "getY") &&
                                    it.paramTypeNames.isEmpty() &&
                                    it.returnTypeName == "int"
                            }
                        }
                val layout =
                    listOf(
                            "prepareSyncPosition",
                            "syncPosition",
                            "translateTarget",
                            "createLayoutParams",
                        )
                        .flatMap {
                            callers(
                                bridge,
                                "com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridCellLayout",
                                it,
                            )
                        }
                records(
                    getters +
                        layout +
                        callers(
                            bridge,
                            "com.honeyspace.ui.honeypots.freegrid.viewmodel.FreeGridViewModel",
                            "updateToDropPoint",
                        )
                )
            }
        val count = deoptimize(values, loader, 256, "DIY coordinate")
        HookEntry.log("DIY_GRID_DEX_VERIFIED coordinate callers=$count")
    }

    @JvmStatic
    fun deoptimizeVolumeCalls(apk: String?, loader: ClassLoader?) {
        val values =
            cached(apk, "volume-v2") { bridge ->
                records(
                    listOf("BlurEffect", "ColorUtils")
                        .flatMap {
                            callers(bridge, "com.android.systemui.volume.util.$it")
                        }
                        .filter {
                            it.className.startsWith("com.android.systemui.volume.view.") &&
                                ".subscreen." !in it.className
                        }
                )
            }
        val methods = members(values, loader)
        check(methods.isNotEmpty() && methods.size <= 96) {
            "Volume callsite deoptimization budget"
        }
        methods.forEach { ModernHook.deoptimize(it) }
        HookEntry.log("VOLUME_DEX_VERIFIED callers=${methods.size}")
    }

    @JvmField var workspaceVerified = false

    private fun scrollVerified(bridge: DexKitBridge, type: String): Boolean =
        bridge.getClassData(type)?.methods.orEmpty().any { method ->
            method.name == "notifyScrollX" &&
                method.invokes.any {
                    it.className == "com.honeyspace.sdk.GradientBackgroundManager" &&
                        it.name == "onScroll"
                }
        }

    @JvmStatic
    fun verifyScrollContract(apk: String?, type: String) {
        val result =
            cached(apk, "scroll-v2:$type") { bridge ->
                check(scrollVerified(bridge, type)) { "Missing runtime scroll DEX contract" }
                mapOf("verified" to "true")
            }
        check(result["verified"] == "true") { "Missing runtime scroll DEX contract" }
    }

    @JvmStatic
    fun verifyAndDeoptimize(apk: String?, loader: ClassLoader?) {
        val values =
            cached(apk, "settings-observer-v2") { bridge ->
                val contract =
                    DexSymbols.findClass(
                            "com.honeyspace.sdk.source.GlobalSettingsDataSource",
                            false,
                            loader,
                        )
                        .name
                val sources = bridge.findClass { matcher { interfaces { add(contract) } } }
                check(sources.isNotEmpty() && sources.size <= 4) {
                    "Ambiguous settings data source"
                }
                val names = sources.map { it.name }.toSet()
                val related =
                    bridge
                        .findClass {}
                        .filter { it.name in names || it.fields.any { f -> f.typeName in names } }
                check(related.size <= 32) { "Ambiguous settings dependencies" }
                val reads = sources.any {
                    it.methods.any { m ->
                        m.invokes.any { call ->
                            call.className == "android.provider.Settings\$System" &&
                                call.name == "getString"
                        }
                    }
                }
                val registers = related.any {
                    it.methods.any { m ->
                        m.invokes.any { call ->
                            call.className == "android.content.ContentResolver" &&
                                call.name == "registerContentObserver"
                        }
                    }
                }
                val observer = related.any {
                    it.superClass?.name == "android.database.ContentObserver"
                }
                check(reads && registers && observer) {
                    "Missing settings read/observer DEX evidence"
                }
                linkedMapOf<String, String?>().apply {
                    DexRuntimeCache.putList(this, "classes", related.map { it.name }.sorted())
                    put(
                        "workspace",
                        scrollVerified(
                                bridge,
                                "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceFastRecyclerView",
                            )
                            .toString(),
                    )
                }
            }
        workspaceVerified = values["workspace"] == "true"
        val methods =
            DexRuntimeCache.list(values, "classes").flatMap {
                Class.forName(it, false, loader).declaredMethods.filter { method ->
                    !Modifier.isAbstract(method.modifiers) && !Modifier.isNative(method.modifiers)
                }
            }
        check(methods.size <= 96) { "Deoptimization budget exceeded" }
        methods.forEach {
            check(ModernHook.deoptimize(it)) { "Failed to deoptimize verified settings caller" }
        }
        HookEntry.log("DEX_VERIFIED: settings read + observer; deoptimized ${methods.size} methods")
    }

    @JvmRecord data class CapturedEmitter(val emit: Method?, val owner: Field?)
}
