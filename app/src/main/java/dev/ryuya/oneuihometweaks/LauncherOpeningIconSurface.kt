package dev.ryuya.oneuihometweaks

import android.graphics.Bitmap
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.hardware.DataSpace
import android.os.Handler
import android.os.Trace
import android.view.AttachedSurfaceControl
import android.view.Display
import android.view.Gravity
import android.view.SurfaceControl
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** Holds one opening icon generation from upload through its last composited frame. */
@RequiresApi(34)
internal class LauncherOpeningIconSurface(
    val owner: Any,
    val group: ViewGroup,
    private val main: Handler,
    private val prepareOnWorker: (() -> Unit) -> Boolean,
    prewarmedLayers: LayerReservation? = null,
    private val upload: (Bitmap, ColorFilter?, (LauncherIconBuffer?) -> Unit) -> Boolean,
) {
    private val front = Slot()
    private val back = Slot()
    private val writer = LauncherOpeningSurfaceWriter()
    private var root: AttachedSurfaceControl? = null
    private var allocated: LauncherOpeningSurfaceWriter.Layers? = null
    private var layerReservation = prewarmedLayers
    private var lastRejection: String? = null
    private val hiddenLayers = LauncherOpeningIconCommit("OneUI open hidden layers committed")
    val bindCommit = LauncherOpeningIconCommit("OneUI open icon handoff draw committed")
    val hiddenLayersReady get() = hiddenLayers.committed
    private var closed = false
    var layers: LauncherOpeningSurfaceWriter.Layers? = null
        private set
    var restoreStockGeometry: (() -> Unit)? = null
    val onDrawCommitted get() = layers != null && bindCommit.committed
    val foregroundSize get() = front.source?.size
    val backgroundSize get() = back.source?.size

    @Suppress("DEPRECATION")
    fun refresh(): Boolean {
        if (closed || group.childCount != 2) return reject("child hierarchy")
        val frontDrawable = group.getChildAt(1)?.background as? BitmapDrawable ?: return reject("foreground drawable")
        val foreground = bitmapSource(frontDrawable, front.source) ?: return reject("foreground bitmap")
        val background = when (val value = group.getChildAt(0)?.background) {
            null -> null
            is ColorDrawable -> {
                if (value.javaClass !== ColorDrawable::class.java || transferMode?.invoke(value) != null) {
                    return reject("solid background transfer mode")
                }
                when (value.opacity) {
                    PixelFormat.TRANSPARENT -> null
                    PixelFormat.OPAQUE -> {
                        if (transferMode == null) return reject("solid background transfer mode unavailable")
                        solidSource(value)
                    }
                    else -> return reject("filtered or translucent background")
                }
            }
            is BitmapDrawable -> {
                val filter = value.colorFilter
                if (filter != null && filter != LauncherIconCpuUpload.stockNightBackgroundFilter) return reject("background color filter")
                bitmapSource(value, back.source, filter) ?: return reject(bitmapRejection(value))
            }
            else -> return reject("background drawable")
        }
        if (layers != null && (foreground != front.source || background != back.source || !isAttached())) {
            return reject("asset changed")
        }
        lastRejection = null
        if (layers != null) return true
        prepareHiddenLayers()
        request(front, foreground)
        request(back, background)
        return true
    }

    private fun reject(reason: String): Boolean {
        if (lastRejection != reason && Trace.isEnabled()) mark("OneUI open asset rejected $reason")
        lastRejection = reason
        return false
    }

    private fun solidSource(drawable: ColorDrawable): Source {
        val color = drawable.color
        back.source?.takeIf { it.drawable === drawable && it.solidColor == color }?.let { return it }
        val bitmap = Bitmap.createBitmap(intArrayOf(color), 1, 1, Bitmap.Config.ARGB_8888)
        mark("OneUI open solid background captured")
        return Source(drawable, bitmap, bitmap.generationId, color)
    }

    fun isAttached(): Boolean = group.isAttachedToWindow && group.display?.displayId == Display.DEFAULT_DISPLAY &&
        (root == null || group.rootView.rootSurfaceControl === root)

    /** A bound layer already owns its native buffer reference. Move the unchanged upload to the next icon. */
    fun prepareReplacement(): LauncherOpeningIconSurface {
        check(!closed && layers != null)
        val next = LauncherOpeningIconSurface(owner, group, main, prepareOnWorker, upload = upload)
        val foreground = group.getChildAt(1)?.background as? BitmapDrawable
        if (front.buffer != null && foreground?.let { bitmapSource(it, front.source) } == front.source) {
            next.front.source = front.source
            next.front.buffer = front.buffer
            next.front.requested = true
            front.buffer = null
        }
        next.refresh()
        return next
    }

    fun canBindOnStockFrame(): Boolean =
        !closed && layers == null && allocated != null && group.transitionAlpha == 1f && isAttached() &&
            front.buffer?.ready == true && (back.source == null || back.buffer?.ready == true)

    fun bindOnStockFrame(stock: SurfaceControl.Transaction, pose: LauncherOpeningIconProjection.Pose): Boolean {
        if (!canBindOnStockFrame()) return false
        val attached = group.rootView.rootSurfaceControl ?: return false
        val renderLayers = checkNotNull(allocated)
        val foreground = checkNotNull(front.buffer)
        SurfaceControl.Transaction().use { staged ->
            // The early transaction may still be queued. Repeating the same hierarchy is safe.
            if (!hiddenLayersReady && !link(staged, attached, renderLayers)) return false
            bind(staged, renderLayers.foreground, foreground)
            back.buffer?.let { bind(staged, renderLayers.background, it) }
            writer.writeIcon(staged, renderLayers, pose)
            bindCommit.watch(staged)
            stock.merge(staged)
        }
        layers = renderLayers
        root = attached
        group.transitionAlpha = 0f
        group.invalidate()
        return true
    }

    fun restoreSuspendedGeometry() {
        val restore = restoreStockGeometry ?: return
        restoreStockGeometry = null
        if (group.transitionAlpha != 0f || !isAttached()) return
        try {
            restore()
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_OPEN_VIEW_RESTORE_FAILED ${failure.javaClass.simpleName}")
        }
    }

    fun close(onRestored: () -> Unit = {}) {
        if (closed) return
        closed = true
        layerReservation?.close()
        layerReservation = null
        hiddenLayers.retire()
        bindCommit.retire()
        val old = layers
        if (old == null) {
            try {
                allocated?.let(::releaseLayers)
            } finally {
                front.buffer?.close()
                back.buffer?.close()
                onRestored()
            }
            return
        }
        val attached = root
        val sameRoot = isAttached()
        restoreSuspendedGeometry()
        if (group.transitionAlpha == 0f) {
            group.transitionAlpha = 1f
        }
        val release = Runnable {
            try {
                releaseLayers(old)
            } finally {
                front.buffer?.close()
                back.buffer?.close()
                onRestored()
            }
            mark("OneUI open icon released")
        }
        if (attached == null || !sameRoot) {
            release.run()
            return
        }
        var queued = false
        try {
            SurfaceControl.Transaction().use { transaction ->
                transaction.setVisibility(old.group, false)
                transaction.addTransactionCommittedListener({ main.post(it) }) {
                    if (main.hasCallbacks(release)) {
                        main.removeCallbacks(release)
                        release.run()
                    }
                }
                queued = attached.applyTransactionOnDraw(transaction)
            }
            if (queued) {
                group.invalidate()
                main.postDelayed(release, 160L)
            }
        } finally {
            if (!queued) release.run()
        }
    }

    private fun prepareHiddenLayers() {
        val ready = allocated
        if (ready != null) {
            linkHiddenIfAttached(ready)
            return
        }
        val reservation = layerReservation
            ?: reserveLayers(prepareOnWorker, main, ::acceptLayers)?.also { layerReservation = it }
            ?: return
        // A stock frame can take a ready reservation before its posted main callback runs.
        acceptLayers(reservation)
    }

    fun acceptLayers(reservation: LayerReservation) {
        if (closed || layerReservation !== reservation) return
        val ready = reservation.take() ?: return
        layerReservation = null
        allocated = ready
        linkHiddenIfAttached(ready)
    }

    private fun linkHiddenIfAttached(renderLayers: LauncherOpeningSurfaceWriter.Layers) {
        if (closed || root != null || !isAttached()) return
        val attached = group.rootView.rootSurfaceControl ?: return
        try {
            SurfaceControl.Transaction().use { transaction ->
                if (!link(transaction, attached, renderLayers)) return
                hiddenLayers.watch(transaction)
                transaction.apply()
            }
            root = attached
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_OPEN_HIDDEN_LINK_FAILED ${failure.javaClass.simpleName}")
        }
    }

    private fun link(transaction: SurfaceControl.Transaction, attached: AttachedSurfaceControl,
        layers: LauncherOpeningSurfaceWriter.Layers): Boolean {
        val parent = attached.buildReparentTransaction(layers.group) ?: return false
        parent.use(transaction::merge)
        transaction.setLayer(layers.group, 1)
        transaction.reparent(layers.foregroundMask, layers.group).setLayer(layers.foregroundMask, 1)
        transaction.reparent(layers.backgroundMask, layers.group).setLayer(layers.backgroundMask, 0)
        transaction.reparent(layers.foreground, layers.foregroundMask)
        transaction.reparent(layers.background, layers.backgroundMask)
        return true
    }

    private fun request(slot: Slot, source: Source?) {
        if (slot.source != source) {
            slot.buffer?.close()
            slot.buffer = null
            slot.source = source
            slot.requested = false
        }
        if (source == null || slot.requested) return
        val request = checkNotNull(slot.source)
        slot.requested = upload(request.bitmap, request.filter) { result ->
            if (!closed && slot.source === request && request.generation == request.bitmap.generationId) {
                slot.buffer = result
                if (result != null && request.filter != null) mark("OneUI open night background uploaded")
            } else result?.close()
        }
    }

    private fun bind(transaction: SurfaceControl.Transaction, surface: SurfaceControl, asset: LauncherIconBuffer) {
        transaction.setBuffer(surface, asset.buffer, asset.fence)
            .setDataSpace(surface, DataSpace.DATASPACE_SRGB)
            .setVisibility(surface, true)
    }

    internal class LayerReservation : AutoCloseable {
        private var ready: LauncherOpeningSurfaceWriter.Layers? = null
        private var closed = false

        internal fun publish(layers: LauncherOpeningSurfaceWriter.Layers): Boolean {
            synchronized(this) {
                if (!closed) {
                    ready = layers
                    return true
                }
            }
            closeLayerHandles(layers)
            return false
        }

        internal fun take(): LauncherOpeningSurfaceWriter.Layers? = synchronized(this) {
            val layers = ready ?: return null
            ready = null
            closed = true
            layers
        }

        override fun close() {
            val layers = synchronized(this) {
                closed = true
                ready.also { ready = null }
            }
            layers?.let(::closeLayerHandles)
        }
    }

    private data class Source(val drawable: Drawable, val bitmap: Bitmap, val generation: Int,
        val solidColor: Int? = null, val filter: ColorFilter? = null) {
        val size = LauncherOpeningIconProjection.BitmapSize(bitmap.width, bitmap.height)
    }
    private class Slot {
        var source: Source? = null
        var requested = false
        var buffer: LauncherIconBuffer? = null
    }

    companion object {
        fun reserveLayers(
            schedule: (() -> Unit) -> Boolean,
            main: Handler,
            onReady: (LayerReservation) -> Unit,
        ): LayerReservation? {
            val reservation = LayerReservation()
            if (!schedule work@{
                Trace.beginSection("OneUI open hidden layers allocate")
                val layers = try {
                    createLayers()
                } catch (failure: Exception) {
                    HookEntry.log("LAUNCHER_OPEN_HIDDEN_LAYERS_FAILED ${failure.javaClass.simpleName}")
                    null
                } finally {
                    Trace.endSection()
                } ?: return@work
                if (reservation.publish(layers) && !main.post { onReady(reservation) }) reservation.close()
            }) return null
            return reservation
        }

        private fun createLayers(): LauncherOpeningSurfaceWriter.Layers {
            val created = ArrayList<SurfaceControl>(5)
            fun create(name: String, container: Boolean): SurfaceControl {
                val builder = SurfaceControl.Builder().setName(name).setHidden(true)
                if (container) containerLayer.invoke(builder)
                return builder.build().also { created += it }
            }
            try {
                return LauncherOpeningSurfaceWriter.Layers(
                    create("One UI opening icon", true),
                    create("One UI opening foreground mask", true),
                    create("One UI opening foreground", false),
                    create("One UI opening background mask", true),
                    create("One UI opening background", false),
                )
            } catch (failure: Exception) {
                created.asReversed().forEach(SurfaceControl::release)
                throw failure
            }
        }

        private fun releaseLayers(layers: LauncherOpeningSurfaceWriter.Layers) {
            try {
                SurfaceControl.Transaction().use { transaction ->
                    transaction.setVisibility(layers.group, false).reparent(layers.group, null)
                        .setBuffer(layers.foreground, null).setBuffer(layers.background, null).apply()
                }
            } finally {
                closeLayerHandles(layers)
            }
        }

        private fun closeLayerHandles(layers: LauncherOpeningSurfaceWriter.Layers) {
            layers.foreground.release()
            layers.background.release()
            layers.foregroundMask.release()
            layers.backgroundMask.release()
            layers.group.release()
        }

        // The One UI host exposes this framework method. Resolve it only when an icon is eligible.
        private val containerLayer by lazy {
            SurfaceControl.Builder::class.java.compatibleDeclaredMethod("setContainerLayer").apply { isAccessible = true }
        }
        private val transferMode by lazy {
            runCatching {
                ColorDrawable::class.java.compatibleDeclaredMethod("getXfermode").apply { isAccessible = true }
            }.getOrNull()
        }

        private fun bitmapSource(drawable: BitmapDrawable, existing: Source?, filter: ColorFilter? = null): Source? {
            val bitmap = drawable.bitmap ?: return null
            if (bitmap.isRecycled || bitmap.width !in 1..512 || bitmap.height !in 1..512 ||
                drawable.gravity != Gravity.FILL || drawable.alpha != 255 || drawable.colorFilter != filter ||
                drawable.isAutoMirrored || drawable.tileModeX != null || drawable.tileModeY != null ||
                (bitmap.colorSpace != null && bitmap.colorSpace?.isSrgb != true)) return null
            val generation = bitmap.generationId
            return existing?.takeIf {
                it.drawable === drawable && it.bitmap === bitmap && it.generation == generation && it.filter == filter
            } ?: Source(drawable, bitmap, generation, filter = filter)
        }

        private fun bitmapRejection(drawable: BitmapDrawable): String = when {
            drawable.bitmap == null || drawable.bitmap.isRecycled -> "background bitmap unavailable"
            drawable.bitmap.width !in 1..512 || drawable.bitmap.height !in 1..512 -> "background bitmap dimensions"
            drawable.gravity != Gravity.FILL || drawable.isAutoMirrored ||
                drawable.tileModeX != null || drawable.tileModeY != null -> "background bitmap placement"
            drawable.alpha != 255 -> "background bitmap alpha"
            else -> "background bitmap color space"
        }

        private fun mark(label: String) {
            if (!Trace.isEnabled()) return
            Trace.beginSection(label)
            Trace.endSection()
        }
    }
}
