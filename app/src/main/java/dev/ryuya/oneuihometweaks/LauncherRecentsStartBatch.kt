package dev.ryuya.oneuihometweaks

import android.app.Application
import android.os.Build
import android.os.Looper
import android.os.Trace
import android.view.AttachedSurfaceControl
import android.view.SurfaceControl
import android.view.View
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** Schedules the initial Recents and overlay visibility changes together on their stock draw. */
@RequiresApi(37)
internal object LauncherRecentsStartBatch {
    fun install(app: Application, loader: ClassLoader) {
        if (!Android17Hooks.supportsRecentsFocus(Build.VERSION.SDK_INT, Build.FINGERPRINT)) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (version == Android17Hooks.LAUNCHER_VERSION) install(loader)
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_RECENTS_START_BATCH_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun install(loader: ClassLoader) {
        val manager = DexSymbols.findClass("com.honeyspace.gesture.recentsanimation.RecentsAnimationManager", loader)
        val updater = DexSymbols.findClass("com.honeyspace.gesture.recentsanimation.LeashUpdater", loader)
        val params = DexSymbols.findClass("com.honeyspace.transition.utils.SurfaceTransaction", loader)
        val start = manager.compatibleDeclaredMethod("startLeashControl", updater)
        val showRecent = updater.compatibleDeclaredMethod("showRecentTarget", View::class.java)
        val showOverlay = updater.compatibleDeclaredMethod("showAppearedLeashOverlayTarget", View::class.java)
        val apply = updater.compatibleDeclaredMethod("apply", View::class.java, params).apply { isAccessible = true }
        val wrapper = params.compatibleDeclaredMethod("getTransaction").apply { isAccessible = true }
        val native = wrapper.returnType.compatibleDeclaredField("mTransaction").apply { isAccessible = true }
        check(listOf(start, showRecent, showOverlay, apply).all { it.returnType == Void.TYPE })
        check(native.type == SurfaceControl.Transaction::class.java)
        check(listOf(start, showRecent, showOverlay).all(ModernHook::deoptimize))

        val main = Looper.getMainLooper()
        var active: Batch? = null
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            fun flush(batch: Batch) {
                val pending = batch.pending ?: return
                batch.pending = null
                val previous = active
                active = null
                try {
                    apply.invoke(batch.owner, pending.view, pending.params)
                } finally {
                    active = previous
                }
            }

            handles += ModernHook.aroundMethod(start) { chain ->
                if (Looper.myLooper() !== main || active != null) return@aroundMethod chain.proceed()
                val state = Batch(chain.args[0] ?: return@aroundMethod chain.proceed())
                active = state
                try {
                    chain.proceed()
                } finally {
                    active = null
                    flush(state)
                }
            }
            for ((method, role) in listOf(showRecent to Role.RECENTS, showOverlay to Role.OVERLAY)) {
                handles += ModernHook.aroundMethod(method) { chain ->
                    val batch = if (Looper.myLooper() === main) active else null
                    if (batch == null || batch.owner !== chain.thisObject || batch.role != null) {
                        return@aroundMethod chain.proceed()
                    }
                    batch.role = role
                    try {
                        chain.proceed()
                    } finally {
                        batch.role = null
                        if (role == Role.OVERLAY) flush(batch)
                    }
                }
            }
            handles += ModernHook.aroundMethod(apply) { chain ->
                val batch = if (Looper.myLooper() === main) active else null
                if (batch == null) return@aroundMethod chain.proceed()
                val role = batch.role
                val view = chain.args[0] as? View
                val args = chain.args[1]
                val root = view?.rootSurfaceControl
                if (batch.owner !== chain.thisObject || role == null || view == null || args == null || root == null ||
                    !view.isAttachedToWindow || !view.isHardwareAccelerated || view.display?.displayId != 0
                ) {
                    flush(batch)
                    return@aroundMethod chain.proceed()
                }
                val transaction = try {
                    native.get(wrapper.invoke(args)) as? SurfaceControl.Transaction
                } catch (_: ReflectiveOperationException) {
                    null
                }
                val pending = batch.pending
                if (transaction != null && role == Role.RECENTS && pending == null) {
                    batch.pending = Pending(view, root, args, transaction)
                    return@aroundMethod null
                }
                if (transaction != null && role == Role.OVERLAY &&
                    pending?.view === view && pending.root === root && pending.transaction !== transaction
                ) {
                    batch.pending = null
                    pending.transaction.merge(transaction)
                    if (Trace.isEnabled()) {
                        Trace.beginSection("OneUI recents start visibility batched")
                        Trace.endSection()
                    }
                    return@aroundMethod chain.proceed(arrayOf(view, pending.params))
                }
                flush(batch)
                chain.proceed()
            }
            HookEntry.log("LAUNCHER_RECENTS_START_BATCH_READY")
        } catch (error: Exception) {
            handles.asReversed().forEach { it.unhook() }
            throw error
        }
    }

    private enum class Role { RECENTS, OVERLAY }
    private class Batch(val owner: Any, var role: Role? = null, var pending: Pending? = null)
    private class Pending(
        val view: View,
        val root: AttachedSurfaceControl,
        val params: Any,
        val transaction: SurfaceControl.Transaction,
    )
}
