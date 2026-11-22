package dev.ryuya.oneuihometweaks

import android.content.Context
import android.util.Log
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** App-side API 102 handshake. Hook configuration and scope stay on their existing paths. */
object FrameworkServiceConnection {
    private const val TAG = "OneUIModsBinder"
    private val registered = AtomicBoolean()
    private val current = AtomicReference<XposedService>()
    private val worker by lazy {
        BackgroundExecutors.serial("XposedServiceMetadata", daemon = true)
    }

    fun register(context: Context) {
        DexSymbols.configure(context)
        if (!registered.compareAndSet(false, true)) return
        val applicationId = context.packageName
        XposedServiceHelper.registerListener(
            object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    current.set(service)
                    // The listener can run inside Vector's provider.call. Return immediately;
                    // remote metadata reads must not delay the daemon's delivery reply.
                    worker.execute {
                        try {
                            val api = service.apiVersion
                            val framework = service.frameworkName
                            val version = service.frameworkVersionCode
                            Log.i(
                                TAG,
                                "CONNECTED $applicationId api=$api framework=$framework version=$version",
                            )
                        } catch (error: RuntimeException) {
                            Log.w(TAG, "METADATA_UNAVAILABLE $applicationId", error)
                        }
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    current.compareAndSet(service, null)
                    Log.w(TAG, "DISCONNECTED $applicationId")
                }
            },
        )
    }

    enum class ReloadRequest {
        ALREADY_CURRENT, INITIAL_LOAD, UNAVAILABLE, BUSY, ACCEPTED, REJECTED,
    }

    fun reloadSystemUi(callback: (ReloadRequest) -> Unit) {
        worker.execute {
            try {
                val service = current.get()
                if (service == null || service.apiVersion < XposedService.API_102) {
                    callback(ReloadRequest.UNAVAILABLE)
                    return@execute
                }
                val target = service.runningTargets.singleOrNull {
                    it.processName == "com.android.systemui" && it.uid / 100_000 == 0
                }
                when {
                    target == null -> callback(ReloadRequest.UNAVAILABLE)
                    target.loadedVersionCode < 133 -> callback(ReloadRequest.INITIAL_LOAD)
                    target.state == HookedTarget.State.RELOADING -> callback(ReloadRequest.BUSY)
                    target.state == HookedTarget.State.UP_TO_DATE ->
                        callback(ReloadRequest.ALREADY_CURRENT)
                    else -> service.hotReloadModule(target, null) { _, result ->
                        val status = result.status()
                        val outcome = when (status) {
                            HotReloadResult.Status.SUCCEEDED -> ReloadRequest.ACCEPTED
                            HotReloadResult.Status.IN_PROGRESS -> ReloadRequest.BUSY
                            HotReloadResult.Status.UNSUPPORTED -> ReloadRequest.UNAVAILABLE
                            else -> ReloadRequest.REJECTED
                        }
                        if (outcome == ReloadRequest.REJECTED) {
                            Log.w(TAG, "SystemUI hot reload ${status.name}: ${result.message()}")
                        }
                        callback(outcome)
                    }
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "SystemUI hot reload unavailable", error)
                callback(ReloadRequest.UNAVAILABLE)
            }
        }
    }
}
