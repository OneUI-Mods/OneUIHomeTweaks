package dev.ryuya.oneuihometweaks

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PatternMatcher
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * The BZI9 tile compositor asks whether the Routines tile has a detail view while assigning
 * an accessibility action label. Its adapter lookup builds a remote view just to test for null.
 * Routines 5.1.02.9 always supplies either a populated view or its empty-state view.
 */
internal class QuickPanelRoutinesDetail(
    private val app: Context,
    private val worker: Handler,
    loader: ClassLoader?,
) {
    private data class Gate(val enabled: Boolean, val generation: Long)

    private data class DetailAvailability(
        val adapter: WeakReference<Any>,
        val binder: WeakReference<IBinder>,
        val generation: Long,
    )

    @Volatile private var gate = Gate(false, 0)
    private val composingTile = ThreadLocal<Any>()
    private val available = WeakHashMap<Any, DetailAvailability>()
    private val handles = mutableListOf<ModernHook.Handle>()
    @Volatile private var retired = false
    private var enabledByConfig = false // Quick Panel worker only.
    private var reportedHit = false // SystemUI main thread only.

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshGate()
        }
    }

    init {
        val customTile = DexSymbols.findClass("com.android.systemui.qs.external.CustomTile", false, loader)
        val component = customTile.compatibleDeclaredField("mComponent")
        val detailAdapter = customTile.compatibleDeclaredField("mDetailAdapter")
        val archivedDetailView = customTile.compatibleDeclaredField("mDetailView")
        val supportsDetail = customTile.compatibleDeclaredField("mIsSupportDetailView")
        val lifecycle = customTile.compatibleDeclaredField("mService")
        val asBinder = lifecycle.type.compatibleDeclaredMethod("asBinder")
        check(component.type == ComponentName::class.java && asBinder.returnType == IBinder::class.java)

        val tileModel = DexSymbols.findClass(
            "com.android.systemui.samsung.quicksetting.domain.model.items.QuickTile", false, loader,
        )
        val underlyingTile = tileModel.compatibleDeclaredField("tile")
        val tileComposable = DexSymbols.findClass(
            "com.android.systemui.samsung.quicksetting.ui.tiles.QuickTileKt", false, loader,
        ).declaredMethods.single { method ->
            method.name == "QuickTile" && method.parameterCount == 8 && method.parameterTypes[2] == tileModel
        }
        val lookup = customTile.compatibleDeclaredMethod("getDetailAdapter")
        val mainLooper = Looper.getMainLooper()
        try {
            handles += ModernHook.aroundMethod(tileComposable) { chain ->
                if (!gate.enabled || Looper.myLooper() !== mainLooper) return@aroundMethod chain.proceed()
                val tile = underlyingTile.get(chain.args[2])
                if (!customTile.isInstance(tile) || component.get(tile) != ROUTINES_TILE) {
                    return@aroundMethod chain.proceed()
                }
                val previous = composingTile.get()
                composingTile.set(tile)
                try {
                    chain.proceed()
                } finally {
                    if (previous == null) composingTile.remove() else composingTile.set(previous)
                }
            }
            handles += ModernHook.aroundMethod(lookup) { chain ->
                val tile = chain.thisObject ?: return@aroundMethod chain.proceed()
                val policy = gate
                if (!policy.enabled || composingTile.get() !== tile) return@aroundMethod chain.proceed()
                if (archivedDetailView.get(tile) != null) return@aroundMethod chain.proceed()
                val adapter = detailAdapter.get(tile)
                val manager = lifecycle.get(tile)
                val binder = manager?.let { asBinder.invoke(it) as? IBinder }
                if (adapter == null || !supportsDetail.getBoolean(tile) || binder?.isBinderAlive != true) {
                    available.remove(tile)
                    return@aroundMethod chain.proceed()
                }
                val cached = available[tile]
                if (cached?.generation == policy.generation &&
                    cached.adapter.get() === adapter && cached.binder.get() === binder
                ) {
                    if (!reportedHit) {
                        reportedHit = true
                        HookEntry.log("QUICK_PANEL_ROUTINES_DETAIL_REUSED")
                    }
                    return@aroundMethod adapter
                }

                // Keep the first native probe, including the service's one-time start event.
                // Detail creation and clicks run outside this composition scope.
                val result = chain.proceed()
                if (result === adapter && asBinder.invoke(manager) === binder &&
                    binder.isBinderAlive && gate == policy
                ) {
                    available[tile] = DetailAvailability(WeakReference(adapter), WeakReference(binder), policy.generation)
                } else {
                    available.remove(tile)
                }
                result
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
                addDataSchemeSpecificPart(ROUTINES_TILE.packageName, PatternMatcher.PATTERN_LITERAL)
            }
            ModernHook.afterGenerationActivation {
                if (!retired) app.registerReceiver(packageChanges, filter, null, worker, Context.RECEIVER_EXPORTED)
            }
        } catch (error: Exception) {
            handles.forEach { it.unhook() }
            throw error
        }
    }

    fun setEnabled(value: Boolean) {
        if (retired || enabledByConfig == value) return
        enabledByConfig = value
        refreshGate()
    }

    fun close() {
        if (retired) return
        retired = true
        handles.forEach { it.unhook() }
        runCatching { app.unregisterReceiver(packageChanges) }
    }

    @Suppress("DEPRECATION")
    private fun refreshGate() {
        val supported = !retired && enabledByConfig && runCatching {
            app.packageManager.getPackageInfo(ROUTINES_TILE.packageName, 0).longVersionCode == ROUTINES_VERSION
        }.getOrDefault(false)
        gate = Gate(supported, gate.generation + 1)
    }

    private companion object {
        val ROUTINES_TILE = ComponentName(
            "com.samsung.android.app.routines",
            "com.samsung.android.app.routines.LifestyleModeTile",
        )
        const val ROUTINES_VERSION = 510209000L
    }
}
