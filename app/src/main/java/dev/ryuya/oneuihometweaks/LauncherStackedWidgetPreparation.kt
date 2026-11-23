package dev.ryuya.oneuihometweaks

import android.app.Application
import android.os.Build
import android.os.Looper
import android.os.Trace
import android.view.View
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** Uses BZI9's stock background preparation for a new, detached stack holder. */
internal object LauncherStackedWidgetPreparation {
    fun install(app: Application, loader: ClassLoader) {
        val sdk = Build.VERSION.SDK_INT
        if (sdk != 37 || !HookCompatibility.matchesClockBuild(sdk, Build.DISPLAY)) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (!HookCompatibility.matchesLauncherVersion(sdk, version)) return

            val adapter = DexSymbols.findClass("be.i", loader)
            val holder = DexSymbols.findClass("be.a", loader)
            val entry = DexSymbols.findClass("yd.e", loader)
            val provider = DexSymbols.findClass("com.honeyspace.ui.common.widget.HoneyAppWidgetProviderInfo", loader)
            val baseHolder = DexSymbols.findClass("androidx.recyclerview.widget.RecyclerView\$ViewHolder", loader)
            val bind = adapter.compatibleDeclaredMethod("bindViewHolder", baseHolder, Int::class.javaPrimitiveType)
            val prepare = adapter.compatibleDeclaredMethod("f", entry, holder, provider, Boolean::class.javaPrimitiveType)
            val existingView = entry.compatibleDeclaredField("b").apply { isAccessible = true }
            val itemType = entry.compatibleDeclaredField("c").apply { isAccessible = true }
            val root = baseHolder.compatibleDeclaredField("itemView").apply { isAccessible = true }
            val preview = adapter.compatibleDeclaredField("f").apply { isAccessible = true }
            check(existingView.type === View::class.java && itemType.type.isEnum && root.type === View::class.java)
            check(preview.type === Boolean::class.javaPrimitiveType)
            check(ModernHook.deoptimize(bind)) { "Stack binding may inline widget preparation" }

            val main = Looper.getMainLooper()
            ModernHook.aroundMethod(prepare) { chain ->
                val args = chain.args
                if (Looper.myLooper() !== main || args[3] != false || args[2] != null) {
                    return@aroundMethod chain.proceed()
                }
                val owner = chain.thisObject ?: return@aroundMethod chain.proceed()
                if (preview.getBoolean(owner)) return@aroundMethod chain.proceed()
                val child = args[0] ?: return@aroundMethod chain.proceed()
                val view = root.get(args[1]) as? View ?: return@aroundMethod chain.proceed()
                if (view.isAttachedToWindow || view.isLaidOut || existingView.get(child) != null ||
                    (itemType.get(child) as? Enum<*>)?.name != "WIDGET"
                ) return@aroundMethod chain.proceed()

                // Stock selects Dispatchers.Default and still attaches the container on Main.
                val tracing = Trace.isEnabled()
                if (tracing) Trace.beginSection("OneUI stack widget prepare on stock worker")
                try {
                    chain.proceed(arrayOf(args[0], args[1], args[2], true))
                } finally {
                    if (tracing) Trace.endSection()
                }
            }
            HookEntry.log("LAUNCHER_STACK_WIDGET_PREPARATION_READY")
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_STACK_WIDGET_PREPARATION_UNAVAILABLE ${failure.javaClass.simpleName}: ${failure.message}")
        }
    }
}
