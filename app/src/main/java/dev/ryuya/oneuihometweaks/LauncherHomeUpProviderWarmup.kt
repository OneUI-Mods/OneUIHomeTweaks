package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.Application
import android.content.ContentProviderClient
import android.os.Build
import android.os.Bundle
import android.os.Trace
import android.view.Display
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Starts Home Up's provider while the stock Recents activity is still setting up. */
internal class LauncherHomeUpProviderWarmup(private val app: Application) {
    private val active = AtomicBoolean()
    private val worker = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "HomeUpProviderWarmup") }.apply {
        setKeepAliveTime(10, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }

    private fun request() {
        if (!active.compareAndSet(false, true)) return
        try {
            worker.execute(::connect)
        } catch (_: RejectedExecutionException) {
            active.set(false)
        }
    }

    private fun connect() {
        var client: ContentProviderClient? = null
        var releaseScheduled = false
        val tracing = Trace.isEnabled()
        if (tracing) Trace.beginSection("OneUI Home Up provider warmup")
        try {
            client = app.contentResolver.acquireUnstableContentProviderClient(AUTHORITY)
            if (client != null) {
                val connected = client
                worker.schedule({ release(connected) }, 1_500L, TimeUnit.MILLISECONDS)
                releaseScheduled = true
            }
        } catch (_: RejectedExecutionException) {
            // A retired generation may finish connecting after shut down.
        } catch (error: RuntimeException) {
            HookEntry.log("LAUNCHER_HOME_UP_PROVIDER_WARMUP_FAILED ${error.javaClass.simpleName}")
        } finally {
            if (tracing) Trace.endSection()
            if (!releaseScheduled) {
                if (client != null) release(client) else active.set(false)
            }
        }
    }

    private fun release(client: ContentProviderClient) {
        try {
            client.close()
        } finally {
            active.set(false)
        }
    }

    companion object {
        private const val AUTHORITY = "com.samsung.android.app.homestar.provider"
        private const val HOME_UP = "com.samsung.android.app.homestar"

        fun install(app: Application, loader: ClassLoader) {
            if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.matchesClockBuild(37, Build.DISPLAY)) return
            try {
                @Suppress("DEPRECATION")
                val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
                if (!HookCompatibility.matchesLauncherVersion(37, version)) return
                @Suppress("DEPRECATION")
                val provider = app.packageManager.resolveContentProvider(AUTHORITY, 0) ?: return
                if (provider.packageName != HOME_UP || !provider.enabled) return
                val activity = DexSymbols.findClass("com.android.quickstep.RecentsActivity", false, loader)
                val onCreate = activity.compatibleDeclaredMethod("onCreate", Bundle::class.java)
                val warmup = LauncherHomeUpProviderWarmup(app)
                ModernHook.onGenerationRetired(warmup.worker::shutdown)
                ModernHook.aroundMethod(onCreate) { chain ->
                    val recents = chain.thisObject as? Activity
                    if (recents?.display?.displayId == Display.DEFAULT_DISPLAY) warmup.request()
                    chain.proceed()
                }
                HookEntry.log("LAUNCHER_HOME_UP_PROVIDER_WARMUP_READY")
            } catch (error: Exception) {
                HookEntry.log("LAUNCHER_HOME_UP_PROVIDER_WARMUP_UNAVAILABLE ${error.javaClass.simpleName}")
            }
        }
    }
}
