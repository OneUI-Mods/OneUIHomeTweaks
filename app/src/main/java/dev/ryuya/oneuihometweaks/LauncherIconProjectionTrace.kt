package dev.ryuya.oneuihometweaks

import android.graphics.Matrix
import android.graphics.RectF
import android.os.Trace
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import kotlin.math.abs
import kotlin.math.roundToLong

/** Measures how far a fixed icon projection can follow the stock Launcher hierarchy. */
@RequiresApi(33)
internal class LauncherIconProjectionTrace(player: Class<*>) {
    private val toHome = player.field("windowToHomeMatrix")
    private val drawable = player.field("useCurrentDrawable")
    private val orientation = player.field("isFixedOrientation")
    private val screen = player.compatibleDeclaredMethod("getScreenSize").apply { isAccessible = true }
    private val output = player.field("outputData")
    private val bounds = output.type.field("currentRectF")
    private val groupScale = output.type.field("containerScale")
    private val foregroundScale = output.type.field("fgScaleOffset")
    private val foregroundAlpha = output.type.field("fgAlpha")
    private val backgroundWidth = output.type.field("bgWidth")
    private val backgroundHeight = output.type.field("bgHeight")
    private val windowGeometry = LauncherWindowGeometry(player, player.field("inputData").type)
    private val writer = LauncherSurfaceFrameWriter()
    private val targets = player.compatibleDeclaredMethod("getFinalRemoteTargets").apply { isAccessible = true }
    private val apps = targets.returnType.compatibleDeclaredMethod("getUnfilteredApps").apply { isAccessible = true }
    private val nonApps = targets.returnType.compatibleDeclaredMethod("getNonApps").apply { isAccessible = true }
    private val mode = targets.returnType.compatibleDeclaredMethod("getTargetMode").apply { isAccessible = true }
    private val replaced = targets.returnType.compatibleDeclaredMethod("getReplacedTarget").apply { isAccessible = true }
    private val appeared = targets.returnType.compatibleDeclaredMethod("getAppearedApps").apply { isAccessible = true }
    private val targetType = requireNotNull(apps.returnType.componentType)
    private val windowType = targetType.field("windowType")
    private val targetMode = targetType.field("mode")
    private val leash = targetType.field("leash")
    private val configuration = targetType.field("windowConfiguration")
    private val activityType = configuration.type.compatibleDeclaredMethod("getActivityType").apply { isAccessible = true }

    fun capture(owner: Any, group: ViewGroup, front: View, width: Int, height: Int): LauncherIconPlaneProjection? {
        val dimensions = screen.invoke(owner) as? IntArray ?: return null
        if (dimensions.size != 2) return null
        val matrix = toHome.get(owner) as? Matrix ?: return null
        return LauncherIconPlaneProjection.capture(group, front, width, height, matrix, dimensions[0], dimensions[1],
            drawable.getBoolean(owner), orientation.getBoolean(owner))
    }

    fun captureOpening(owner: Any, group: ViewGroup, background: View, foreground: View, width: Int, height: Int):
        LauncherOpeningIconProjection? {
        val dimensions = screen.invoke(owner) as? IntArray ?: return null
        if (dimensions.size != 2) return null
        val projection = capture(owner, group, foreground, width, height) ?: return null
        return LauncherOpeningIconProjection.capture(projection, group, background, foreground,
            dimensions[0] < dimensions[1], orientation.getBoolean(owner))
    }

    fun compare(owner: Any, model: LauncherIconPlaneProjection,
        actual: LauncherIconPlaneProjection.Position): LauncherIconPlaneProjection.Position? {
        if (!Trace.isEnabled()) return null
        val data = output.get(owner) ?: return null
        val rect = bounds.get(data) as? RectF ?: return null
        val predicted = model.project(LauncherIconPlaneProjection.Frame(rect, groupScale.getFloat(data),
            foregroundScale.getFloat(data), backgroundWidth.getInt(data), backgroundHeight.getInt(data),
            foregroundAlpha.getFloat(data))) ?: return null
        val positionError = maxOf(abs(actual.x - predicted.x), abs(actual.y - predicted.y))
        val scaleError = maxOf(abs(actual.scaleX - predicted.scaleX), abs(actual.scaleY - predicted.scaleY))
        val alphaError = abs(actual.alpha - predicted.alpha)
        Trace.beginSection("OneUI closing projection compared")
        Trace.setCounter("OneUI closing projection position error x1000", (positionError * 1000f).roundToLong())
        Trace.setCounter("OneUI closing projection scale error x1e6", (scaleError * 1_000_000f).roundToLong())
        Trace.setCounter("OneUI closing projection alpha error x1e6", (alphaError * 1_000_000f).roundToLong())
        Trace.endSection()
        return predicted.takeIf { positionError <= 0.02f && scaleError <= 0.00001f && alphaError <= 0.000001f }
    }

    /** The captured projection reproduces the stock view's own transform for the stock output of this frame. */
    fun matches(owner: Any, model: LauncherIconPlaneProjection, actual: LauncherIconPlaneProjection.Position): Boolean {
        val data = output.get(owner) ?: return false
        val rect = bounds.get(data) as? RectF ?: return false
        val predicted = model.project(LauncherIconPlaneProjection.Frame(rect, groupScale.getFloat(data),
            foregroundScale.getFloat(data), backgroundWidth.getInt(data), backgroundHeight.getInt(data),
            foregroundAlpha.getFloat(data))) ?: return false
        return abs(actual.x - predicted.x) <= 0.02f && abs(actual.y - predicted.y) <= 0.02f &&
            abs(actual.scaleX - predicted.scaleX) <= 0.00001f && abs(actual.scaleY - predicted.scaleY) <= 0.00001f &&
            abs(actual.alpha - predicted.alpha) <= 0.000001f
    }

    fun checkPair(owner: Any, icon: SurfaceControl, position: LauncherIconPlaneProjection.Position): Boolean {
        val appSurface = singleAppSurface(owner) ?: return false
        val window = windowGeometry.readStock(owner) ?: return false
        return SurfaceControl.Transaction().use { transaction -> writer.writePair(transaction, appSurface, window, icon, position) }
    }

    fun singleAppSurface(owner: Any): SurfaceControl? {
        val transition = targets.invoke(owner) ?: return null
        val appTargets = apps.invoke(transition) as? Array<*> ?: return null
        var app: Any? = null
        for (target in appTargets) {
            if (target == null || activityType.invoke(configuration.get(target)) == 3) continue
            if (app != null) return null
            app = target
        }
        app ?: return null
        if (replaced.invoke(transition) != null || !(appeared.invoke(transition) as? Collection<*>).isNullOrEmpty() ||
            targetMode.getInt(app) != mode.invoke(transition) as Int) return null
        val otherTargets = nonApps.invoke(transition) as? Array<*>
        // This is the only non-app window type touched by the stock top-window updater.
        if (otherTargets?.any { it != null && windowType.getInt(it) == 2034 } == true) return null
        return (leash.get(app) as? SurfaceControl)?.takeIf { it.isValid }
    }

    private fun Class<*>.field(name: String) = compatibleDeclaredField(name).apply { isAccessible = true }
}
