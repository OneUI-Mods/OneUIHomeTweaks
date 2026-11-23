package dev.ryuya.oneuihometweaks

import android.app.Application
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.HardwareBufferRenderer
import android.graphics.RenderNode
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.hardware.SyncFence
import android.os.Build
import android.os.Looper
import android.os.Trace
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.time.Duration
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Draws disposable small copies of the stock icon layers for measuring readiness. */
@RequiresApi(34)
internal object LauncherIconBufferTrace {
    fun install(app: Application, loader: ClassLoader) {
        val sdk = Build.VERSION.SDK_INT
        if (sdk != 37 || !HookCompatibility.matchesClockBuild(sdk, Build.DISPLAY)) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (HookCompatibility.matchesLauncherVersion(sdk, version)) install(loader)
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_ICON_BUFFER_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun install(loader: ClassLoader) {
        val player = DexSymbols.findClass("com.honeyspace.transition.anim.floating.PlayerImpl", loader)
        val setupData = DexSymbols.findClass("com.honeyspace.transition.anim.floating.entity.SetupData", loader)
        val figures = player.compatibleDeclaredMethod("getContainerView").apply { isAccessible = true }
        val direction = player.compatibleDeclaredMethod("isForward").apply { isAccessible = true }
        val opening = setupData.compatibleDeclaredMethod("isForward").apply { isAccessible = true }
        val setup = player.compatibleDeclaredMethod("setupViews", setupData, View::class.java)
        val apply = player.compatibleDeclaredMethod("apply", Float::class.javaPrimitiveType)
        val foreground = player.compatibleDeclaredMethod("setupForegroundView", View::class.java, Drawable::class.java)
        val background = player.declaredMethods.single { method ->
            method.name == "setupBackgroundView\$lambda\$0\$1" && method.parameterCount == 4 &&
                method.parameterTypes[1] == View::class.java && method.parameterTypes[3] == player
        }
        val main = Looper.getMainLooper()
        val observations = WeakHashMap<Any, Observation>()
        val jobs = Executors.newSingleThreadExecutor { task -> Thread(task, "HomeIconBufferTrace") }
        val slots = Semaphore(2)
        val geometry = if (BuildConfig.LAUNCHER_ICON_GEOMETRY_TRACE) LauncherIconGeometry() else null
        var sequence = 0
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            handles += ModernHook.aroundMethod(setup) { chain ->
                if (Looper.myLooper() !== main || !Trace.isEnabled()) return@aroundMethod chain.proceed()
                val label = "OneUI icon ${if (opening.invoke(chain.args[0]) == true) "open" else "close"} ${++sequence}"
                chain.thisObject?.let { observations[it] = Observation(label, sequence) }
                Trace.beginSection("$label setup")
                try {
                    chain.proceed()
                } finally {
                    Trace.endSection()
                }
            }
            if (BuildConfig.LAUNCHER_ICON_BUFFER_TRACE) {
                handles += ModernHook.aroundMethod(foreground) { chain ->
                    val result = chain.proceed()
                    if (Looper.myLooper() === main && Trace.isEnabled()) {
                        val observation = observations[chain.thisObject]
                        val view = chain.args[0] as View
                        if (observation != null) observeLayer(observation, view, "foreground", slots, jobs)
                    }
                    result
                }
                handles += ModernHook.aroundMethod(background) { chain ->
                    val result = chain.proceed()
                    if (Looper.myLooper() === main && Trace.isEnabled()) {
                        val owner = chain.args[3]
                        val view = chain.args[1] as View
                        val observation = observations[owner]
                        val container = figures.invoke(owner) as? ViewGroup
                        if (observation != null && container?.getChildAt(0) === view) {
                            observeLayer(observation, view, "background", slots, jobs)
                        }
                    }
                    result
                }
            }
            handles += ModernHook.aroundMethod(apply) { chain ->
                val owner = chain.thisObject
                if (owner == null || Looper.myLooper() !== main || !Trace.isEnabled()) {
                    return@aroundMethod chain.proceed()
                }
                val observation = observations[owner] ?: if (geometry != null) {
                    val label = "OneUI icon ${if (direction.invoke(owner) == true) "open" else "close"} ${++sequence}"
                    Observation(label, sequence).also { observations[owner] = it }
                } else null
                if (observation != null && !observation.firstFrame) {
                    observation.firstFrame = true
                    val container = figures.invoke(owner) as? ViewGroup
                    val backgroundType = container?.getChildAt(0)?.background?.javaClass?.simpleName ?: "none"
                    val foregroundType = container?.getChildAt(1)?.background?.javaClass?.simpleName ?: "none"
                    Trace.beginSection("${observation.label} first frame $backgroundType/$foregroundType")
                    Trace.endSection()
                }
                val result = chain.proceed()
                if (geometry != null && observation != null && observation.geometryFrames < 90) {
                    val group = figures.invoke(owner) as? ViewGroup
                    if (group != null) geometry.trace(observation.id, ++observation.geometryFrames, group)
                }
                result
            }
            val deoptimized = if (BuildConfig.LAUNCHER_ICON_BUFFER_TRACE) {
                listOf(setup, foreground, background).count { ModernHook.deoptimize(it) }
            } else 0
            HookEntry.log("LAUNCHER_ICON_BUFFER_TRACE_READY deoptimized=$deoptimized")
            ModernHook.onGenerationRetired { jobs.shutdown() }
        } catch (error: Exception) {
            handles.asReversed().forEach { it.unhook() }
            jobs.shutdown()
            throw error
        }
    }

    private class Observation(val label: String, val id: Int) {
        var firstFrame = false
        var geometryFrames = 0
    }

    private fun observeLayer(observation: Observation, view: View, name: String, slots: Semaphore, jobs: Executor) {
        val stock = view.background ?: return
        val label = "${observation.label} $name ${stock.javaClass.simpleName}"
        Trace.beginSection("$label source")
        try {
            if (stock is Animatable || !slots.tryAcquire()) return
            val copy = try {
                stock.constantState?.newDrawable(view.resources, view.context.theme)?.mutate()?.also { drawable ->
                    drawable.state = stock.state.copyOf()
                    drawable.level = stock.level
                    drawable.layoutDirection = stock.layoutDirection
                    drawable.alpha = stock.alpha
                    drawable.setBounds(0, 0, BUFFER_SIZE, BUFFER_SIZE)
                }
            } catch (_: RuntimeException) {
                null
            }
            if (copy == null || copy === stock) {
                slots.release()
                return
            }
            Trace.beginAsyncSection("$label prepare", observation.id)
            try {
                jobs.execute {
                    try {
                        if (drawAndAwait(copy)) {
                            Trace.beginSection("$label ready")
                            Trace.endSection()
                        }
                    } catch (error: Exception) {
                        HookEntry.log("LAUNCHER_ICON_BUFFER_DRAW ${error.javaClass.simpleName}")
                    } finally {
                        Trace.endAsyncSection("$label prepare", observation.id)
                        slots.release()
                    }
                }
            } catch (_: RejectedExecutionException) {
                Trace.endAsyncSection("$label prepare", observation.id)
                slots.release()
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun drawAndAwait(drawable: Drawable): Boolean {
        val node = RenderNode("Home icon diagnostic layer").apply { setPosition(0, 0, BUFFER_SIZE, BUFFER_SIZE) }
        val canvas = node.beginRecording()
        try {
            canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
            drawable.draw(canvas)
        } finally {
            node.endRecording()
        }
        try {
            HardwareBuffer.create(
                BUFFER_SIZE, BUFFER_SIZE, HardwareBuffer.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    HardwareBuffer.USAGE_COMPOSER_OVERLAY,
            ).use { buffer ->
                HardwareBufferRenderer(buffer).use { renderer ->
                    renderer.setContentRoot(node)
                    val ready = CompletableFuture<SyncFence>()
                    renderer.obtainRenderRequest().draw(Executor(Runnable::run)) { result ->
                        val fence = result.fence
                        if (result.status != HardwareBufferRenderer.RenderResult.SUCCESS) {
                            fence.close()
                            ready.completeExceptionally(IllegalStateException("GPU result ${result.status}"))
                        } else if (!ready.complete(fence)) fence.close()
                    }
                    val fence = try {
                        ready.get(250, TimeUnit.MILLISECONDS)
                    } catch (error: Exception) {
                        if (!ready.cancel(false) && !ready.isCompletedExceptionally) ready.getNow(null)?.close()
                        throw error
                    }
                    fence.use { return !it.isValid || it.await(Duration.ofMillis(250)) }
                }
            }
        } finally {
            node.discardDisplayList()
        }
    }

    private const val BUFFER_SIZE = 160
}
