package dev.ryuya.oneuihometweaks

import android.graphics.Rect
import android.view.SurfaceControl
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** Encodes the app leash and the prepared icon in one SurfaceControl transaction. */
@RequiresApi(33)
internal class LauncherSurfaceFrameWriter {
    private val transactionType = SurfaceControl.Transaction::class.java
    private val crop = transactionType.compatibleDeclaredMethod("setWindowCrop", SurfaceControl::class.java, Rect::class.java)
        .apply { isAccessible = true }
    private val corner = transactionType.compatibleDeclaredMethod(
        "setCornerRadius", SurfaceControl::class.java, Float::class.javaPrimitiveType,
    ).apply { isAccessible = true }

    fun writePair(
        transaction: SurfaceControl.Transaction,
        app: SurfaceControl,
        window: LauncherWindowGeometry.Frame,
        icon: SurfaceControl,
        position: LauncherIconPlaneProjection.Position,
    ): Boolean {
        if (!icon.isValid || app === icon ||
            !position.x.isFinite() || !position.y.isFinite() || !position.scaleX.isFinite() || !position.scaleY.isFinite() ||
            position.scaleX <= 0f || position.scaleY <= 0f || position.alpha !in 0f..1f
        ) return false

        if (!writeWindow(transaction, app, window)) return false
        writeIcon(transaction, icon, position)
        return true
    }

    fun writeWindow(transaction: SurfaceControl.Transaction, app: SurfaceControl, window: LauncherWindowGeometry.Frame): Boolean {
        if (!app.isValid || window.crop.isEmpty || !window.x.isFinite() || !window.y.isFinite() ||
            !window.scale.isFinite() || window.scale <= 0f || window.alpha !in 0f..1f ||
            !window.radius.isFinite() || window.radius < 0f
        ) return false
        transaction.setPosition(app, window.x, window.y).setScale(app, window.scale, window.scale)
            .setAlpha(app, window.alpha).setVisibility(app, true)
        crop.invoke(transaction, app, window.crop)
        // BZI9's RemoteAnimationTarget overload sets the corner and leaves the leash's shadow alone.
        corner.invoke(transaction, app, window.radius)
        return true
    }

    fun writeClip(transaction: SurfaceControl.Transaction, surface: SurfaceControl, bounds: Rect?, radius: Float) {
        transaction.setCrop(surface, bounds)
        corner.invoke(transaction, surface, radius)
    }

    companion object {
        fun writeIcon(transaction: SurfaceControl.Transaction, icon: SurfaceControl, position: LauncherIconPlaneProjection.Position) {
            transaction.setPosition(icon, position.x, position.y).setScale(icon, position.scaleX, position.scaleY)
                .setAlpha(icon, position.alpha)
        }
    }
}
