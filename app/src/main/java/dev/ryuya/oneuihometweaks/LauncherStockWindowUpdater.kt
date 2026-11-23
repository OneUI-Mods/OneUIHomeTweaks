package dev.ryuya.oneuihometweaks

import android.os.Trace
import android.view.SurfaceControl
import android.view.View
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal typealias TargetlessWindowUpdate =
    (owner: Any, screen: View, readWindow: () -> LauncherStockWindowUpdater.Window?, stock: () -> Any?) -> Any?

/** Skips duplicate stock transactions while the same opening or closing surfaces are driven independently. */
@RequiresApi(34)
internal object LauncherStockWindowUpdater {
    fun install(player: Class<*>, direct: LauncherDirectClosingFrames, surface: (Any) -> SurfaceControl?,
        targetless: TargetlessWindowUpdate?, omitStockUpdate: Boolean, omitOpeningStockUpdate: Boolean): ModernHook.Handle? {
        return try {
            register(player, direct, targetless, omitStockUpdate, omitOpeningStockUpdate, surface)
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_STOCK_WINDOW_OMISSION_UNAVAILABLE ${failure.javaClass.simpleName}")
            null
        }
    }

    internal fun register(player: Class<*>, direct: LauncherDirectClosingFrames,
        targetless: TargetlessWindowUpdate? = null, omitStockUpdate: Boolean = true,
        omitOpeningStockUpdate: Boolean = omitStockUpdate,
        surface: (Any) -> SurfaceControl?): ModernHook.Handle {
        val updater = findUpdater(player)
        val target = Target(player, updater, surface)
        val result = updater.returnType.getField("INSTANCE").get(null)

        return ModernHook.aroundMethod(updater) { chain ->
            val owner = chain.args[3] ?: return@aroundMethod chain.proceed()
            val view = chain.args[4] as? View ?: return@aroundMethod chain.proceed()
            val applier = chain.args[5] ?: return@aroundMethod chain.proceed()
            val opening = direct.openingWindowForOmission()
            val enabled = if (opening == null) omitStockUpdate else omitOpeningStockUpdate
            val omit = enabled && direct.canOmitStockUpdate(owner, applier, view)
            val capturedTargets = chain.args[2]
            fun readWindow() = target.window(owner, view, capturedTargets, chain.args[0], chain.args[1], chain.args[6])
            if (!omit) {
                return@aroundMethod if (targetless == null) chain.proceed() else {
                    targetless(owner, view, ::readWindow) { chain.proceed() }
                }
            }
            val window = if (opening == null) readWindow() else {
                target.openingWindow(owner, view, capturedTargets, chain.args[0], chain.args[1], chain.args[6]) {}
                    ?.takeIf(opening::sameOpeningTarget)
            }
            if (window == null || (!window.midpointWasReported &&
                    (opening == null || !target.beforeOpeningMidpoint(owner)))) return@aroundMethod chain.proceed()

            val saved = Array<Any?>(7) { chain.args[it] }
            val root = view.rootView
            direct.omitStockUpdate(applier) {
                if (view.isAttachedToWindow && view.rootView === root && target.sameTargets(owner, capturedTargets) &&
                    (if (opening == null) surface(owner) === window.app else {
                        target.openingWindow(owner, view, capturedTargets, saved[0], saved[1], saved[6]) {}
                            ?.let(window::sameOpeningTarget) == true
                    })
                ) {
                    updater.invoke(null, *saved)
                }
            }
            if (Trace.isEnabled()) {
                Trace.beginSection(if (opening == null) "OneUI detached stock transaction omitted"
                    else "OneUI opening duplicate stock transaction omitted")
                Trace.endSection()
            }
            result
        }
    }

    /** Reads the updater selected by PlayerImpl.apply, including its one-time stock commit state. */
    fun readDuringApply(player: Class<*>, loader: ClassLoader, surface: (Any) -> SurfaceControl?):
        ((Any, View) -> Window?)? {
        return try {
            val target = Target(player, findUpdater(player), surface)
            val fields = WindowFunction(player, loader)
            val read: (Any, View) -> Window? = read@{ playerInstance, view ->
                val updater = fields.selected(playerInstance) ?: return@read null
                if (fields.screen.get(updater) !== view) return@read null
                target.window(playerInstance, view, fields.targets.get(updater), fields.apps.get(updater), fields.nonApps.get(updater),
                    fields.midpoint.get(updater), ::traceClosingRejection)
            }
            read
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_TARGETLESS_APP_READER_UNAVAILABLE ${failure.javaClass.simpleName}")
            null
        }
    }

    /** Identifies the selected stock writer and its one animated app before the pulse. */
    fun readOpeningApplier(player: Class<*>, loader: ClassLoader): OpeningApplierReader? {
        return try {
            val updaterMethod = findUpdater(player)
            val target = Target(player, updaterMethod) { null }
            val fields = WindowFunction(player, loader)
            val applierClass = updaterMethod.parameterTypes[5]
            OpeningApplierReader read@{ playerInstance, root, note ->
                fun reject(reason: String): OpeningApplier? {
                    note(reason)
                    return null
                }
                val updater = fields.selected(playerInstance, note) ?: return@read null
                val view = fields.screen.get(updater) as? View ?: return@read reject("writer view absent")
                if (!view.isAttachedToWindow) return@read reject("writer view detached")
                if (root == null) return@read reject("icon root absent")
                if (view.rootView !== root) return@read reject("writer belongs to another root")
                val applier = fields.applier.get(updater)?.takeIf(applierClass::isInstance)
                    ?: return@read reject("writer applier absent")
                val targets = fields.targets.get(updater) ?: return@read reject("invalid callback")
                val window = target.openingWindow(playerInstance, view, targets, fields.apps.get(updater),
                    fields.nonApps.get(updater), fields.midpoint.get(updater), note) ?: return@read null
                OpeningApplier(applier, window)
            }
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_OPEN_APPLIER_READER_UNAVAILABLE ${failure.javaClass.simpleName}")
            null
        }
    }

    internal fun interface OpeningApplierReader {
        fun read(owner: Any, iconRoot: View?, note: (String) -> Unit): OpeningApplier?
    }

    internal class OpeningApplier(val applier: Any, val window: Window)

    private class WindowFunction(player: Class<*>, loader: ClassLoader) {
        private val selected = player.compatibleDeclaredField("topWindowUpdater").apply { isAccessible = true }
        // BZI9 constructs this synthetic Function0 in setupTopWindowUpdater. Branch 0 updates app surfaces.
        private val function = DexSymbols.findClass("com.honeyspace.transition.anim.floating.d", loader)
        private val branch = field("a")
        val apps = field("b")
        val nonApps = field("c")
        val targets = field("d")
        private val owner = field("e")
        val screen = field("f")
        val applier = field("g")
        val midpoint = field("h")

        init { check(branch.type == Int::class.javaPrimitiveType && screen.type === View::class.java) }

        fun selected(player: Any, note: ((String) -> Unit)? = null): Any? {
            val updater = selected.get(player)
            val rejection = when {
                updater == null -> "no updater"
                updater.javaClass !== function -> "different updater ${updater.javaClass.name.takeLast(60)}"
                branch.getInt(updater) != 0 -> "different updater branch"
                owner.get(updater) !== player -> "foreign updater owner"
                else -> return updater
            }
            note?.invoke(rejection)
            return null
        }

        private fun field(name: String) = function.compatibleDeclaredField(name).apply { isAccessible = true }
    }

    private fun findUpdater(player: Class<*>) = player.declaredMethods.single { method ->
        method.name == "setupTopWindowUpdater\$lambda\$2\$2" && method.parameterCount == 7 &&
            Modifier.isStatic(method.modifiers) && method.parameterTypes[3] === player &&
            method.parameterTypes[4] === View::class.java
    }.apply { isAccessible = true }

    internal class Window(val app: SurfaceControl, private val midpoint: Field, private val diagnostic: Any,
        val companion: SurfaceControl? = null, private val targetSet: Any? = null) {
        val hasCompanion get() = companion != null
        val midpointWasReported = midpoint.getBoolean(diagnostic)
        fun carriesMidpoint() = !midpointWasReported && midpoint.getBoolean(diagnostic)
        fun sameOpeningTarget(other: Window) = targetSet === other.targetSet && diagnostic === other.diagnostic &&
            app === other.app && companion === other.companion
    }

    private fun traceClosingRejection(reason: String) {
        if (!Trace.isEnabled()) return
        Trace.beginSection("OneUI closing no icon updater $reason")
        Trace.endSection()
    }

    private class Target(player: Class<*>, updater: Method, private val surface: (Any) -> SurfaceControl?) {
        private val readTargets = player.compatibleDeclaredMethod("getFinalRemoteTargets").apply { isAccessible = true }
        private val readMode = readTargets.returnType.compatibleDeclaredMethod("getTargetMode").apply { isAccessible = true }
        private val replaced = readTargets.returnType.compatibleDeclaredMethod("getReplacedTarget").apply { isAccessible = true }
        private val appeared = readTargets.returnType.compatibleDeclaredMethod("getAppearedApps").apply { isAccessible = true }
        private val readProgress = runCatching {
            val output = player.compatibleDeclaredField("outputData").apply { isAccessible = true }
            val progress = output.type.compatibleDeclaredMethod("getProgress").apply { isAccessible = true }
            check(progress.returnType == Float::class.javaPrimitiveType)
            val read: (Any) -> Float? = { owner -> output.get(owner)?.let { progress.invoke(it) as Float } }
            read
        }.getOrNull()
        private val canScheduleApply = player.compatibleDeclaredMethod("isValidSurfaceControl", View::class.java)
            .apply { isAccessible = true }
        private val multiTargets = player.compatibleDeclaredField("isMultiTargets").apply { isAccessible = true }
        private val midpoint = updater.parameterTypes[6].compatibleDeclaredField("element").apply { isAccessible = true }
        private val appType = checkNotNull(updater.parameterTypes[1].componentType)
        private val leash = appType.compatibleDeclaredField("leash").apply { isAccessible = true }
        private val targetMode = appType.compatibleDeclaredField("mode").apply { isAccessible = true }
        private val windowType = appType.compatibleDeclaredField("windowType").apply { isAccessible = true }

        fun sameTargets(owner: Any, targets: Any?) = readTargets.invoke(owner) === targets

        fun beforeOpeningMidpoint(owner: Any): Boolean = try {
            // Stock queues its one-time commit listener when the live output first passes 0.5.
            readProgress?.invoke(owner)?.let { it.isFinite() && it <= 0.5f } == true
        } catch (_: ReflectiveOperationException) {
            false
        }

        fun openingWindow(owner: Any, view: View, targets: Any?, apps: Any?, nonApps: Any?, diagnostic: Any?,
            note: (String) -> Unit): Window? {
            fun reject(reason: String): Window? {
                note(reason)
                return null
            }
            if (diagnostic == null || !midpoint.declaringClass.isInstance(diagnostic)) return reject("invalid midpoint")
            if (multiTargets.getBoolean(owner)) return reject("multi window")
            if (targets == null || !sameTargets(owner, targets)) return reject("stale targets")
            val additions = appeared.invoke(targets)
            if (replaced.invoke(targets) != null || (additions != null &&
                    (additions !is Collection<*> || additions.isNotEmpty()))) return reject("changed target set")
            if (hasDivider(nonApps)) return reject("split divider")
            if (canScheduleApply.invoke(owner, view) != true) return reject("invalid writer surface")
            val list = apps as? List<*> ?: return reject("invalid app list")
            if (list.size !in 1..2) return reject("app count ${list.size}")
            val mode = readMode.invoke(targets) as Int
            var animated: SurfaceControl? = null
            var companion: SurfaceControl? = null
            for (entry in list) {
                if (!appType.isInstance(entry)) return reject("invalid target")
                val surface = (leash.get(entry) as? SurfaceControl)?.takeIf { it.isValid }
                    ?: return reject("invalid app leash")
                if (targetMode.getInt(entry) == mode) {
                    if (animated != null) return reject("multiple animated targets")
                    animated = surface
                } else {
                    companion = surface
                }
            }
            val app = animated ?: return reject("animated target absent")
            if (companion === app) return reject("shared target leash")
            return Window(app, midpoint, diagnostic, companion, targets)
        }

        fun window(owner: Any, view: View, targets: Any?, apps: Any?, nonApps: Any?, diagnostic: Any?,
            note: ((String) -> Unit)? = null): Window? {
            fun reject(reason: String): Window? {
                note?.invoke(reason)
                return null
            }
            if (targets == null || diagnostic == null || !midpoint.declaringClass.isInstance(diagnostic)) return reject("invalid callback")
            if (multiTargets.getBoolean(owner)) return reject("multi window")
            val list = apps as? List<*> ?: return reject("invalid app list")
            val target = list.singleOrNull() ?: return reject("app count ${list.size}")
            if (!sameTargets(owner, targets)) return reject("stale targets")
            if (targetMode.getInt(target) != readMode.invoke(targets)) return reject("target mode changed")
            if (hasDivider(nonApps)) return reject("split divider")
            val app = surface(owner) ?: return reject("unsupported app leash")
            if (leash.get(target) !== app) return reject("invalid app leash")
            if (canScheduleApply.invoke(owner, view) != true) return reject("invalid writer surface")
            return Window(app, midpoint, diagnostic)
        }

        private fun hasDivider(nonApps: Any?): Boolean =
            (nonApps as? Array<*>)?.any { it != null && windowType.getInt(it) == 2034 } == true
    }
}
