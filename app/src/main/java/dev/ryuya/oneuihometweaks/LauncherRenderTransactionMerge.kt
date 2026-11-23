package dev.ryuya.oneuihometweaks

import android.app.Application
import android.graphics.HardwareRenderer
import android.os.Build
import android.os.Trace
import android.view.SurfaceControl
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/** Keeps BZI9's renderer and buffer-frame merge, removing the stack trace from each submission. */
internal object LauncherRenderTransactionMerge {
    fun install(app: Application, loader: ClassLoader) {
        if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.supportsRecentsFocus(37, Build.FINGERPRINT)) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (!HookCompatibility.matchesLauncherVersion(37, version)) return
            bind(loader)
            HookEntry.log("LAUNCHER_RENDER_TRANSACTION_MERGE_READY")
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_RENDER_TRANSACTION_MERGE_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        } catch (error: LinkageError) {
            HookEntry.log("LAUNCHER_RENDER_TRANSACTION_MERGE_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun bind(loader: ClassLoader) {
        val transactionType = SurfaceControl.Transaction::class.java
        val frameType = Long::class.javaPrimitiveType!!
        val root = DexSymbols.findClass("android.view.ViewRootImpl", false, loader)
        val merge = root.compatibleDeclaredMethod("mergeWithNextTransaction", transactionType, frameType)
        val compat = DexSymbols.findClass("com.android.systemui.shared.launcher.ViewRootImplCompat", false, loader)
        val forward = compat.compatibleDeclaredMethod("mergeWithNextTransaction", transactionType, frameType)
        val tag = root.compatibleDeclaredField("mTag").apply { isAccessible = true }
        val attachInfo = root.compatibleDeclaredField("mAttachInfo").apply { isAccessible = true }
        val renderer = attachInfo.type.compatibleDeclaredField("mThreadedRenderer").apply { isAccessible = true }
        val title = root.compatibleDeclaredMethod("getTitle").apply { isAccessible = true }
        val prepare = transactionType.compatibleDeclaredMethod("onMergeWithNextTransaction", CharSequence::class.java)
            .apply { isAccessible = true }
        val transactionId = transactionType.compatibleDeclaredMethod("getId").apply { isAccessible = true }
        val sync = DexSymbols.findClass("android.graphics.HardwareRenderer\$SyncInterface", false, loader)
            .compatibleDeclaredMethod("mergeWithNextTransaction", HardwareRenderer::class.java, transactionType, frameType)
            .apply { isAccessible = true }
        check(tag.type == String::class.java && HardwareRenderer::class.java.isAssignableFrom(renderer.type))
        check(title.returnType == CharSequence::class.java && Modifier.isStatic(sync.modifiers))
        check(merge.returnType == Void.TYPE && sync.returnType == Void.TYPE && prepare.returnType == Void.TYPE)
        check(transactionId.returnType == frameType)
        check(ModernHook.deoptimize(forward)) { "Stock ViewRoot forwarding may inline the merge" }

        ModernHook.aroundMethod(merge, XposedInterface.PRIORITY_DEFAULT, XposedInterface.ExceptionMode.PASSTHROUGH) { chain ->
            val owner = chain.thisObject ?: return@aroundMethod chain.proceed()
            val transaction = chain.args[0] as? SurfaceControl.Transaction ?: return@aroundMethod chain.proceed()
            val frame = chain.args[1] as Long
            if (frame <= 0 || Thread.currentThread().name != "RenderThread") return@aroundMethod chain.proceed()
            val windowTag = tag.get(owner) as? String ?: return@aroundMethod chain.proceed()
            val window = when {
                windowTag.startsWith("VRI[RecentsActivity]@") -> "RecentsActivity"
                windowTag.startsWith("VRI[RecentsTransitionOverlay]@") -> "RecentsTransitionOverlay"
                windowTag.startsWith("VRI[LauncherActivity]@") -> "LauncherActivity"
                else -> return@aroundMethod chain.proceed()
            }
            val targetRenderer = try {
                renderer.get(attachInfo.get(owner) ?: return@aroundMethod chain.proceed()) as? HardwareRenderer
            } catch (_: ReflectiveOperationException) {
                return@aroundMethod chain.proceed()
            } ?: return@aroundMethod chain.proceed()
            val surfaceTitle = try {
                title.invoke(owner) as? CharSequence
            } catch (_: ReflectiveOperationException) {
                return@aroundMethod chain.proceed()
            } ?: return@aroundMethod chain.proceed()

            val tracing = Trace.isEnabled()
            if (tracing) {
                val id = try {
                    transactionId.invoke(transaction) as Long
                } catch (_: ReflectiveOperationException) {
                    0L
                }
                Trace.beginSection("OneUI $window renderer merge frame=$frame id=$id")
            }
            try {
                prepare.invoke(transaction, surfaceTitle)
                sync.invoke(null, targetRenderer, transaction, frame)
            } catch (wrapped: InvocationTargetException) {
                // A submitted transaction must never be passed to the stock path a second time.
                throw wrapped.targetException
            } finally {
                if (tracing) Trace.endSection()
            }
            null
        }
    }
}
