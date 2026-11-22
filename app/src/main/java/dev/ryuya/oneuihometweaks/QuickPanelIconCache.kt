package dev.ryuya.oneuihometweaks

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Handler
import android.util.SparseArray
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps the stock Quick Panel image resources off the first composition's critical path. */
internal class QuickPanelIconCache(
    private val app: Context,
    private val worker: Handler,
    loader: ClassLoader?,
) : ComponentCallbacks {
    private enum class SoundIcon(val id: Int, val entry: String, val frames: Int) {
        SOUND(0x7e081016, "quick_panel_icon_sound", 40),
        VIBRATE(0x7e08102d, "quick_panel_icon_sound_vibrate", 28),
        SILENT(0x7e081023, "quick_panel_icon_sound_mute", 32),
    }

    private data class Prepared(
        val icon: SoundIcon,
        val assets: AssetManager,
        val configuration: Configuration,
        val densityDpi: Int,
        val state: Drawable.ConstantState,
        val tiles: SparseArray<Drawable.ConstantState>,
    ) {
        fun matches(resources: Resources): Boolean =
            assets === resources.assets && densityDpi == resources.displayMetrics.densityDpi &&
                configuration == resources.configuration
    }

    @Volatile private var prepared: Prepared? = null
    @Volatile private var tileResources = WeakReference<Resources>(null)
    @Volatile private var retired = false
    private var enabled = false // Written and read on the Quick Panel worker.
    private val audio = checkNotNull(app.getSystemService(AudioManager::class.java))
    private val loggedHit = AtomicBoolean()
    private val loggedTileHit = AtomicBoolean()
    private val prepareIcons = Runnable { prepare() }
    private val ringerChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            schedulePreparation()
        }
    }

    init {
        val resources = app.resources
        for (soundIcon in SoundIcon.entries) {
            require(resources.getResourceEntryName(soundIcon.id) == soundIcon.entry)
        }
        val icon = DexSymbols.findClass(
            "com.android.systemui.qs.tileimpl.QSTileImpl\$AnimationIcon", false, loader,
        )
        val drawable = icon.compatibleDeclaredMethod("getDrawable", Context::class.java)
        val id = icon.compatibleDeclaredField("mAnimatedResId")
        require(drawable.returnType == Drawable::class.java && id.type == Int::class.javaPrimitiveType)

        val soundHandle = ModernHook.hookMethod(drawable, object : ModernHook.Callback() {
            override fun beforeHookedMethod(param: ModernHook.Param) {
                val cache = prepared ?: return
                if (id.getInt(param.thisObject) != cache.icon.id) return
                val resources = matchingResources(cache, param.args[0]) ?: return
                // Keep animation position, callbacks, bounds, and tint owned by each view.
                param.setResult(cache.state.newDrawable(resources).mutate())
                if (loggedHit.compareAndSet(false, true)) {
                    HookEntry.log("QUICK_PANEL_SOUND_ICONS_REUSED ${cache.icon.name}")
                }
            }
        })
        val tileHandles = mutableListOf<ModernHook.Handle>()
        ModernHook.onGenerationRetired {
            retired = true
            worker.removeCallbacks(prepareIcons)
            prepared = null
            tileResources.clear()
            runCatching { app.unregisterComponentCallbacks(this) }
            runCatching { app.unregisterReceiver(ringerChanges) }
        }
        ModernHook.afterGenerationActivation {
            try {
                app.registerReceiver(
                    ringerChanges, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
                    null, worker, Context.RECEIVER_NOT_EXPORTED,
                )
                app.registerComponentCallbacks(this)
            } catch (error: RuntimeException) {
                soundHandle.unhook()
                runCatching { app.unregisterReceiver(ringerChanges) }
                HookEntry.log("QUICK_PANEL_ICON_LISTENER_UNAVAILABLE ${error.javaClass.simpleName}")
            }
        }
        try {
            val resourceIcon = DexSymbols.findClass(
                "com.android.systemui.qs.tileimpl.QSTileImpl\$ResourceIcon", false, loader,
            )
            val resourceId = resourceIcon.compatibleDeclaredField("mResId")
            require(resourceId.type == Int::class.javaPrimitiveType)
            val callback = object : ModernHook.Callback() {
                override fun beforeHookedMethod(param: ModernHook.Param) {
                    val cache = prepared ?: return
                    val tile = cache.tiles[resourceId.getInt(param.thisObject)] ?: return
                    val resources = matchingResources(cache, param.args[0]) ?: return
                    param.setResult(tile.newDrawable(resources))
                    if (loggedTileHit.compareAndSet(false, true)) {
                        HookEntry.log("QUICK_PANEL_STATIC_ICONS_REUSED")
                    }
                }
            }
            for (method in listOf("getDrawable", "getInvisibleDrawable")) {
                val target = resourceIcon.compatibleDeclaredMethod(method, Context::class.java)
                require(target.returnType == Drawable::class.java)
                tileHandles += ModernHook.hookMethod(target, callback)
            }
        } catch (error: Exception) {
            tileHandles.forEach { it.unhook() }
            HookEntry.log("QUICK_PANEL_STATIC_ICON_HOOK_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun matchingResources(cache: Prepared, context: Any?): Resources? {
        val resources = (context as? Context)?.resources ?: return null
        if (cache.matches(resources)) return resources
        if (tileResources.get() !== resources) {
            tileResources = WeakReference(resources)
            schedulePreparation()
        }
        return null
    }

    // Called by the existing configuration reader on the worker thread.
    fun setEnabled(value: Boolean) {
        if (retired || enabled == value) return
        enabled = value
        worker.removeCallbacks(prepareIcons)
        if (value) {
            worker.post(prepareIcons)
        } else {
            tileResources.clear()
            prepared = null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = schedulePreparation()

    private fun schedulePreparation() {
        if (retired) return
        worker.removeCallbacks(prepareIcons)
        worker.post(prepareIcons)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        worker.removeCallbacks(prepareIcons)
        worker.post { prepared = null }
    }

    private fun prepare() {
        if (retired || !enabled) return
        val resources = tileResources.get() ?: app.resources
        try {
            val icon = when (audio.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> SoundIcon.SOUND
                AudioManager.RINGER_MODE_VIBRATE -> SoundIcon.VIBRATE
                AudioManager.RINGER_MODE_SILENT -> SoundIcon.SILENT
                else -> return
            }
            val previous = prepared?.takeIf { it.matches(resources) }
            if (previous?.icon == icon) return
            val configuration = Configuration(resources.configuration)
            val assets = resources.assets
            val densityDpi = resources.displayMetrics.densityDpi
            prepared = null
            // These stock animation lists contain only unthemed WebP resources.
            val drawable = resources.getDrawable(icon.id, null) as? AnimationDrawable ?: return
            val state = drawable.constantState ?: return
            check(drawable.numberOfFrames == icon.frames && drawable.isOneShot)
            val size = (0 until icon.frames).sumOf { index ->
                check(drawable.getDuration(index) == FRAME_DURATION_MS)
                (drawable.getFrame(index) as BitmapDrawable).bitmap.allocationByteCount.toLong()
            }
            val tiles = previous?.tiles ?: prepareStaticTiles(resources)
            val next = Prepared(icon, assets, configuration, densityDpi, state, tiles)
            if (size <= MAX_BITMAP_BYTES && next.matches(resources)) {
                prepared = next
                HookEntry.log("QUICK_PANEL_SOUND_ICONS_PREPARED ${icon.name} bytes=$size")
            } else if (size > MAX_BITMAP_BYTES) {
                HookEntry.log("QUICK_PANEL_SOUND_ICONS_SKIPPED bytes=$size")
            }
        } catch (error: RuntimeException) {
            HookEntry.log("QUICK_PANEL_SOUND_ICONS_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun prepareStaticTiles(resources: Resources): SparseArray<Drawable.ConstantState> {
        val states = SparseArray<Drawable.ConstantState>(STATIC_TILE_NAMES.size)
        var bytes = 0L
        for ((id, entry) in STATIC_TILE_NAMES) {
            val icon = try {
                if (resources.getResourceEntryName(id) != entry) continue
                resources.getDrawable(id, null) as? BitmapDrawable
            } catch (_: Resources.NotFoundException) {
                null
            } ?: continue
            val state = icon.constantState ?: continue
            val size = icon.bitmap.allocationByteCount.toLong()
            if (size > MAX_STATIC_BYTES - bytes) continue
            bytes += size
            states.put(id, state)
        }
        if (states.size() > 0) {
            HookEntry.log("QUICK_PANEL_STATIC_ICONS_PREPARED count=${states.size()} bytes=$bytes")
        }
        return states
    }

    private companion object {
        const val FRAME_DURATION_MS = 16
        const val MAX_BITMAP_BYTES = 16L * 1024 * 1024
        const val MAX_STATIC_BYTES = 2L * 1024 * 1024
        // One-frame WebP assets observed on the UI thread in the BZI9 resource trace.
        val STATIC_TILE_NAMES = mapOf(
            0x7e08053a to "airplane_off_00000",
            0x7e0805e1 to "camera_on_00035",
            0x7e080697 to "donotdisturb_on_00030",
            0x7e080d08 to "location_on_00040",
            0x7e080db1 to "microphone_on_00035",
            0x7e080dc6 to "mobilehotspot_off_00000",
            0x7e080f74 to "privacydisplay_on_00034",
            0x7e0816e2 to "wifi_off_00000",
        )
    }
}
