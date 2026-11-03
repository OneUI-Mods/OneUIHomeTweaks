package dev.ryuya.oneuihometweaks

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.View
import dev.ryuya.oneuihometweaks.compat.DexRuntimeCache
import java.lang.Boolean as JavaBoolean
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.HashSet
import java.util.IdentityHashMap
import java.util.Map
import java.util.function.Supplier
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic
import kotlin.math.log
import kotlin.math.max

/* Sound Assistant's dynamically loaded panel, inside SystemUI only. */
class VolumeStarHooks {
    private val seen: MutableSet<ClassLoader?>? =
        Collections.newSetFromMap<ClassLoader?>(IdentityHashMap<ClassLoader?, Boolean?>())
    private val blurDepth: ThreadLocal<Int?>? = ThreadLocal.withInitial<Int?>(Supplier { 0 })
    private val installed: MutableList<ModernHook.Handle?> = ArrayList<ModernHook.Handle?>()
    @Volatile private var mode: Int = 0
    private var lightPreset: Int = 0
    private var darkPreset: Int = 0
    private var failed: Boolean = false
    private var hostContext: Context? = null
    private var plugin: WeakReference<Any?> = WeakReference<Any?>(null)
    private var reconfigure: Method? = null
    private var lastColor: String = ""
    private var lastBlur: String = ""

    @Throws(Exception::class)
    fun install(host: Context?, hostLoader: ClassLoader?, light: Int, dark: Int) {
        hostContext = host
        lightPreset = light
        darkPreset = dark
        val wrapper =
            Class.forName(
                "com.samsung.systemui.splugins.SPluginInstanceManager\$PluginContextWrapper",
                false,
                hostLoader,
            )
        val constructor =
            wrapper.getDeclaredConstructor(Context::class.java, ClassLoader::class.java)
        val handler =
            Class.forName(
                "com.samsung.systemui.splugins.SPluginInstanceManager\$PluginHandler",
                false,
                hostLoader,
            )
        val load = handler.getDeclaredMethod("handleLoadPlugin", ComponentName::class.java)
        check(ModernHook.deoptimize(load)) { "Failed to deoptimize plugin loader" }
        ModernHook.hookMethod(
            constructor,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    val patternPrefix0 = p.hasThrowable()
                    val ctx = if (!patternPrefix0) p.args[0] as? Context else null
                    val patternPrefix1 =
                        patternPrefix0 ||
                            ctx == null ||
                            VolumeStarHooks.PACKAGE != ctx.getPackageName()
                    val loader = if (!patternPrefix1) p.args[1] as? ClassLoader else null
                    if (patternPrefix1 || loader == null || !seen!!.add(loader)) return
                    try {
                        attach(ctx, loader)
                    } catch (t: Throwable) {
                        fail(t)
                    }
                }
            },
        )
    }

    @Throws(Exception::class)
    private fun attach(ctx: Context, loader: ClassLoader?) {
        val impl = Class.forName("com.samsung.systemui.volumestar.VolumeStarImpl", false, loader)
        val dependency =
            Class.forName(
                "com.samsung.systemui.splugins.volume.VolumeStarDependency",
                false,
                loader,
            )
        val init =
            impl.getDeclaredMethod("init", Context::class.java, Context::class.java, dependency)
        reconfigure = impl.getDeclaredMethod("onConfigurationChanged")
        reconfigure!!.setAccessible(true)
        val proof =
            DexRuntimeCache.query(ctx.applicationInfo.sourceDir, "volumestar-v2") { bridge ->
                val palettes =
                    bridge
                        .findClass { matcher { usingStrings("ColorThemeWrapper") } }
                        .filter { c -> c.methods.any { "ColorThemeWrapper" in it.usingStrings } }
                val bars =
                    bridge
                        .findClass { matcher { usingStrings("BarColorModel(progressColor=") } }
                        .filter { c ->
                            c.methods.any { "BarColorModel(progressColor=" in it.usingStrings }
                        }
                val blurMethods =
                    bridge
                        .findMethod {
                            matcher {
                                invokeMethods {
                                    add {
                                        declaredClass("android.view.SemBlurInfo\$Builder")
                                        name("setColorCurvePreset")
                                    }
                                }
                            }
                        }
                        .filter { method ->
                            method.invokes.any { ref ->
                                VolumeStarDexContract.isBlurCall(
                                    "L" + ref.className.replace('.', '/') + ";",
                                    ref.name,
                                ) {
                                    method.paramTypeNames.map { "L" + it.replace('.', '/') + ";" }
                                }
                            }
                        }
                val blurTypes = blurMethods.map { it.className }.toSet()
                check(palettes.size == 1 && blurTypes.isNotEmpty() && blurTypes.size <= 3) {
                    "VolumeStar palette/blur DEX contract changed"
                }
                check(bars.size <= 1) { "Ambiguous VolumeStar bar model" }
                val targets = blurTypes + palettes.map { it.name } + bars.map { it.name }
                val callers =
                    targets
                        .flatMap { target ->
                            bridge.findMethod {
                                matcher { invokeMethods { add { declaredClass(target) } } }
                            }
                        }
                        .filter { ".subscreen." !in it.className }
                linkedMapOf<String, String?>().apply {
                    put("palette", palettes.single().name)
                    put("bar", bars.singleOrNull()?.name)
                    DexRuntimeCache.putList(this, "blur", blurTypes.sorted())
                    DexRuntimeCache.putList(
                        this,
                        "callers",
                        callers.map { it.className + "#" + it.name }.distinct().sorted(),
                    )
                }
            }
        val palette = Class.forName(requireNotNull(proof["palette"]), false, loader)
        val blurTypes =
            DexRuntimeCache.list(proof, "blur").map { "L" + it.replace('.', '/') + ";" }.toSet()
        val ownerField =
            Arrays.stream<Field?>(impl.getDeclaredFields()).anyMatch { f: Field? ->
                f!!.getType() == palette
            }
        val colorMethods = ArrayList<Method?>()
        for (m in palette!!.getDeclaredMethods()) if (
            m.getReturnType() == Int::class.javaPrimitiveType &&
                m.getParameterCount() == 1 &&
                m.getParameterTypes()[0].isEnum()
        )
            colorMethods.add(m)
        check(!(!ownerField || colorMethods.size != 1)) { "VolumeStar color signature changed" }
        val color = colorMethods.get(0)
        val keys = HashSet<String?>()
        for (e in color!!.getParameterTypes()[0].getEnumConstants()) keys.add((e as Enum<*>).name)
        check(
            keys.containsAll(
                java.util.Set.of<String?>(
                    "PRIMARY",
                    "SECONDARY",
                    "BACKGROUND",
                    "ON_PRIMARY",
                    "ON_BACKGROUND",
                    "WARNING",
                )
            )
        ) {
            "VolumeStar color keys changed"
        }
        for (name in VolumeStarHooks.COLORS!!.values) check(
            ctx.getResources().getIdentifier(name, "color", VolumeStarHooks.PACKAGE) != 0
        ) {
            "VolumeStar resource missing: " + name
        }
        for (name in
            java.util.List.of<String?>(
                "volume_star_on_primary_color_black",
                "volume_star_on_primary_color_white",
            )) check(
            ctx.getResources().getIdentifier(name, "color", VolumeStarHooks.PACKAGE) != 0
        ) {
            "VolumeStar foreground missing"
        }

        // Undo actual compiled callsites, including obfuscated ones; exclude cover display paths.
        var barConstructor: Constructor<*>? = null
        val barName = proof["bar"]
        if (barName != null) {
            val bar = Class.forName(barName, false, loader)
            check(Parcelable::class.java.isAssignableFrom(bar)) {
                "VolumeStar bar model signature changed"
            }
            barConstructor =
                bar.getDeclaredConstructor(
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
        }
        val callers = DexEvidence.members(proof, loader)
        check(callers.isNotEmpty() && callers.size <= 192) { "VolumeStar deoptimization budget" }
        for (method in callers) check(ModernHook.deoptimize(method)) {
            "Failed to deoptimize VolumeStar caller"
        }

        if (barConstructor != null)
            installed.add(
                ModernHook.hookMethod(
                    barConstructor,
                    object : ModernHook.Callback() {
                        override fun beforeHookedMethod(p: ModernHook.Param) {
                            if (!failed && mode != 0) p.args[1] = mode == 2
                        }
                    },
                )
            )
        installed.add(
            ModernHook.hookMethod(
                color,
                object : ModernHook.Callback() {
                    override fun afterHookedMethod(p: ModernHook.Param) {
                        if (failed || mode == 0 || p.hasThrowable()) return
                        try {
                            val key = (p.args[0] as Enum<*>).name
                            val name: String? =
                                if (key == "ON_PRIMARY")
                                    (if (mode == 1) "volume_star_on_primary_color_black"
                                    else "volume_star_on_primary_color_white")
                                else VolumeStarHooks.COLORS!!.get(key)
                            if (name == null)
                                return // Warning and unrelated colors retain native behavior.

                            val cfg = Configuration(ctx.getResources().getConfiguration())
                            cfg.uiMode =
                                ((cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                    (if (mode == 1) Configuration.UI_MODE_NIGHT_NO
                                    else Configuration.UI_MODE_NIGHT_YES))
                            val themed = ctx.createConfigurationContext(cfg)
                            val id =
                                themed
                                    .getResources()
                                    .getIdentifier(name, "color", VolumeStarHooks.PACKAGE)
                            p.setResult(themed.getColor(id))
                            val report: String = "VOLUMESTAR_COLOR mode=" + mode
                            if (report != lastColor) {
                                lastColor = report
                                HookEntry.log(report)
                            }
                        } catch (t: Throwable) {
                            fail(t)
                        }
                    }
                },
            )
        )
        for (descriptor in blurTypes) {
            val type = VolumeStarHooks.actual(descriptor!!, loader)
            for (m in type!!.getDeclaredMethods()) {
                val parameters = m.getParameterTypes()
                if (parameters.size == 0 || !View::class.java.isAssignableFrom(parameters[0]))
                    continue
                installed.add(
                    ModernHook.hookMethod(
                        m,
                        object : ModernHook.Callback() {
                            override fun beforeHookedMethod(p: ModernHook.Param) {
                                val view: View? = p.args[0] as View?
                                if (
                                    failed ||
                                        mode == 0 ||
                                        view == null ||
                                        view.getDisplay() == null ||
                                        view.getDisplay().getDisplayId() != 0
                                )
                                    return
                                blurDepth!!.set(blurDepth!!.get()!! + 1)
                                p.setObjectExtra("oneUiVolumeBlur", true)
                            }

                            override fun afterHookedMethod(p: ModernHook.Param) {
                                if (JavaBoolean.TRUE == p.getObjectExtra("oneUiVolumeBlur"))
                                    blurDepth!!.set(max(0, blurDepth!!.get()!! - 1))
                            }
                        },
                    )
                )
            }
        }
        val builder = Class.forName("android.view.SemBlurInfo\$Builder")
        installed.add(
            ModernHook.hookMethod(
                builder.getDeclaredMethod("setColorCurvePreset", Int::class.javaPrimitiveType),
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        if (failed || mode == 0 || blurDepth!!.get() == 0) return
                        p.args[0] = if (mode == 1) lightPreset else darkPreset
                        val report: String = "VOLUMESTAR_BLUR mode=" + mode + " preset=" + p.args[0]
                        if (report != lastBlur) {
                            lastBlur = report
                            HookEntry.log(report)
                        }
                    }
                },
            )
        )
        installed.add(
            ModernHook.hookMethod(
                init,
                object : ModernHook.Callback() {
                    override fun afterHookedMethod(p: ModernHook.Param) {
                        if (!p.hasThrowable()) plugin = WeakReference<Any?>(p.thisObject)
                    }
                },
            )
        )
        HookEntry.log("VOLUMESTAR_HOOK_READY callers=" + callers.size)
    }

    fun hasError(): Boolean {
        return failed
    }

    fun setMode(next: Int) {
        if (mode == next) return
        mode = if (failed) 0 else next
        val current = plugin.get()
        if (current == null || failed) return
        try {
            reconfigure!!.invoke(current)
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun fail(t: Throwable) {
        if (failed) return
        failed = true
        mode = 0
        for (hook in installed) hook!!.unhook()
        installed.clear()
        HookEntry.log("VOLUMESTAR_DISABLED " + t)
        HostStatus.report(hostContext, "ERROR", "VolumeStar: " + t.javaClass.getSimpleName())
        Handler(Looper.getMainLooper())
            .post(
                Runnable {
                    try {
                        val current = plugin.get()
                        if (current != null && reconfigure != null) reconfigure!!.invoke(current)
                    } catch (restore: Throwable) {
                        HookEntry.log(
                            "VOLUMESTAR_NATIVE_RESTORE_ERROR " + restore.javaClass.getSimpleName()
                        )
                    }
                }
            )
    }

    companion object {
        private const val PACKAGE: String = "com.samsung.android.soundassistant"
        private val COLORS: MutableMap<String?, String?>? =
            Map.of<String?, String?>(
                "PRIMARY",
                "volume_star_primary_color",
                "SECONDARY",
                "volume_star_secondary_color",
                "BACKGROUND",
                "volume_star_background_color",
                "ON_BACKGROUND",
                "volume_star_on_background_color",
            )

        @JvmStatic
        @Throws(ClassNotFoundException::class)
        private fun actual(descriptor: String, loader: ClassLoader?): Class<*>? {
            return Class.forName(
                descriptor.substring(1, descriptor.length - 1).replace('/', '.'),
                false,
                loader,
            )
        }
    }
}
