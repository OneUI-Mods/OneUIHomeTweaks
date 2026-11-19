package dev.ryuya.oneuihometweaks

import android.content.Context
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import java.util.concurrent.Executor

/** Adds light to native dock and popup folder backgrounds, below folder content. */
@RequiresApi(33)
internal class GlassHomeSurfaces(
    context: Context,
    loader: ClassLoader,
    main: Handler,
    worker: Executor,
    private val level: () -> Int,
    dark: () -> Boolean,
    private val material: GlassMaterial,
    private val onDrawn: () -> Unit,
) {
    private val container = DexSymbols.findClass(
        "com.honeyspace.ui.honeypots.hotseat.presentation.HotseatContainer", false, loader,
    ).asSubclass(ViewGroup::class.java)
    private val dock = CompactGlassDock(context, loader, main, worker, level, dark, material, onDrawn)
    private val folderIcons = runCatching { GlassFolderIcons(loader, level, dark, material, onDrawn) }.onFailure {
        HookEntry.log("GLASS_FOLDER_ICONS_UNAVAILABLE ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()
    private val folderBackdrop = runCatching {
        GlassFolderBackdrop(loader, level, dark, material, onDrawn)
    }.onFailure {
        HookEntry.log("GLASS_FOLDER_LENS_UNAVAILABLE ${it.javaClass.simpleName}")
    }.getOrNull()

    init {
        val model = DexSymbols.findClass(
            "com.honeyspace.ui.honeypots.hotseat.viewmodel.HotseatViewModel", false, loader,
        )
        ModernHook.aroundMethod(container.getDeclaredMethod("setup", model)) { chain ->
            val result = chain.proceed()
            val view = chain.thisObject as ViewGroup
            safely { dock.watch(view) }
            result
        }
    }

    fun find(root: View) = safely { scan(root) }

    private fun scan(root: View) {
        if (root.javaClass == container) dock.watch(root as ViewGroup)
        folderBackdrop?.find(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) scan(root.getChildAt(index))
    }

    fun refresh() {
        dock.refresh()
        folderIcons?.refresh()
        folderBackdrop?.refresh()
    }

    fun wallpaperChanged() = dock.wallpaperChanged()

    fun clearDockBackdrops() = dock.clearBackdrops()

    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            HookEntry.log("GLASS_HOME " + error.javaClass.simpleName)
        }
    }

}
