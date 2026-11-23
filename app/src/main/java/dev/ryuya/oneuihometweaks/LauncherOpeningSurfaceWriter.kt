package dev.ryuya.oneuihometweaks

import android.view.SurfaceControl
import androidx.annotation.RequiresApi

/** Emits the app and both icon planes together, using one effective clip per image. */
@RequiresApi(33)
internal class LauncherOpeningSurfaceWriter {
    private val frames = LauncherSurfaceFrameWriter()

    fun writeWindow(transaction: SurfaceControl.Transaction, app: SurfaceControl, window: LauncherWindowGeometry.Frame): Boolean =
        frames.writeWindow(transaction, app, window)

    fun write(transaction: SurfaceControl.Transaction, app: SurfaceControl, window: LauncherWindowGeometry.Frame,
        layers: Layers, pose: LauncherOpeningIconProjection.Pose): Boolean {
        if (!layers.group.isValid || !layers.foregroundMask.isValid || !layers.foreground.isValid ||
            !layers.backgroundMask.isValid || !layers.background.isValid) return false
        if (!frames.writeWindow(transaction, app, window)) return false
        writeIcon(transaction, layers, pose)
        return true
    }

    fun writeIcon(transaction: SurfaceControl.Transaction, layers: Layers, pose: LauncherOpeningIconProjection.Pose) {
        LauncherSurfaceFrameWriter.writeIcon(transaction, layers.group, pose.group)
        transaction.setVisibility(layers.group, pose.group.alpha > 0f)
        val foregroundClip = pose.foreground.clip
        frames.writeClip(transaction, layers.foregroundMask, foregroundClip?.bounds, foregroundClip?.radius ?: 0f)
        LauncherSurfaceFrameWriter.writeIcon(transaction, layers.foreground, pose.foreground.position)
        transaction.setVisibility(layers.foregroundMask, pose.foreground.position.alpha > 0f)
        val background = pose.background
        if (background != null) {
            frames.writeClip(transaction, layers.backgroundMask, background.clip?.bounds, background.clip?.radius ?: 0f)
            LauncherSurfaceFrameWriter.writeIcon(transaction, layers.background, background.position)
        }
        transaction.setVisibility(layers.backgroundMask, background != null)
    }

    internal class Layers(
        val group: SurfaceControl,
        val foregroundMask: SurfaceControl,
        val foreground: SurfaceControl,
        val backgroundMask: SurfaceControl,
        val background: SurfaceControl,
    )
}
