package dev.ryuya.oneuihometweaks

import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.os.Looper
import android.os.Trace
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.util.WeakHashMap

/** Resolves Samsung's three named widget dimensions once per launcher asset table. */
internal object LauncherWidgetCornerResources {
    private const val CORNER = "com.honeyspace.ui.common.util.RoundedCornerEnforcement"
    private val entries = mapOf(
        "SMALL_RECTANGLE" to "enforced_rounded_corner_max_radius_in_minimum_height",
        "LARGE_RECTANGLE" to "enforced_rounded_corner_max_radius",
        "SQUIRCLE" to "enforced_rounded_corner_max_squircle_radius",
    )

    fun install(app: Application, loader: ClassLoader?) {
        val sdk = Build.VERSION.SDK_INT
        if (sdk != 37 || !HookCompatibility.matchesClockBuild(sdk, Build.DISPLAY)) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (!HookCompatibility.matchesLauncherVersion(sdk, version)) return

            val type = DexSymbols.findClass("$CORNER\$Type", loader)
            val companion = DexSymbols.findClass("$CORNER\$Companion", loader)
            val corner = companion.compatibleDeclaredMethod(
                "getRoundedCorner", Context::class.java, type, Int::class.javaPrimitiveType,
            )
            val radiusGetters = listOf(
                "com.honeyspace.ui.common.widget.HoneyAppWidgetHostView",
                "com.honeyspace.ui.common.blurbackground.BackgroundView",
            ).map { name ->
                DexSymbols.findClass(name, loader).compatibleDeclaredMethod("getEnforcedCornerRadius")
            }
            check(corner.returnType == Float::class.javaPrimitiveType)
            check(radiusGetters.all { it.returnType == corner.returnType })

            val packageName = app.packageName
            val main = Looper.getMainLooper()
            val dimensions = WeakHashMap<AssetManager, Map<String, Int>>()
            fun lookup(resources: Resources): Map<String, Int> {
                Trace.beginSection("OneUI home resolve widget corner IDs")
                try {
                    return entries.mapValues { (_, entry) -> resources.getIdentifier(entry, "dimen", packageName) }
                } finally {
                    Trace.endSection()
                }
            }
            dimensions[app.resources.assets] = lookup(app.resources)
            // ART can inline the stock switch into the widget and background radius getters.
            radiusGetters.forEach(ModernHook::deoptimize)
            ModernHook.aroundMethod(corner) { chain ->
                if (Looper.myLooper() !== main) return@aroundMethod chain.proceed()
                val shape = (chain.args[1] as? Enum<*>)?.name ?: return@aroundMethod chain.proceed()
                if (shape !in entries) return@aroundMethod chain.proceed()
                val context = chain.args[0] as? Context ?: return@aroundMethod chain.proceed()
                if (context.packageName != packageName) return@aroundMethod chain.proceed()
                val resources = context.resources
                val id = dimensions.getOrPut(resources.assets) { lookup(resources) }[shape]
                if (id == null || id == 0) return@aroundMethod chain.proceed()
                // Resource IDs stay tied to the asset table. Resolve the current dimension
                // on every call so density and configuration overrides still take effect.
                try {
                    resources.getDimension(id)
                } catch (_: Resources.NotFoundException) {
                    dimensions.remove(resources.assets)
                    chain.proceed()
                }
            }
            HookEntry.log("LAUNCHER_WIDGET_CORNER_RESOURCES_READY")
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_WIDGET_CORNER_RESOURCES_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }
}
