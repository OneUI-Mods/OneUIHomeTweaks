package dev.ryuya.oneuihometweaks.unlocklogic

import android.view.animation.PathInterpolator
import dev.ryuya.oneuihometweaks.UnlockAppFrame
import dev.ryuya.oneuihometweaks.UnlockItemFrame
import dev.ryuya.oneuihometweaks.UnlockItemPlan
import dev.ryuya.oneuihometweaks.UnlockLockscreenFrame
import dev.ryuya.oneuihometweaks.UnlockLogicV1
import kotlin.math.sqrt

/** This package is loaded from the current signed APK when it is replaced. */
class SignedUnlockLogic : UnlockLogicV1 {
    private val homeFade = PathInterpolator(0.14f, 0.86f, 0.25f, 1f)
    private val settle = PathInterpolator(0.22f, 0.52f, 0.34f, 1f)
    private val opacity = PathInterpolator(0.15f, 0.80f, 0.31f, 1f)
    private val wallpaper = PathInterpolator(0.20f, 0.35f, 0.34f, 1f)
    private val slide = PathInterpolator(0.32f, 0.02f, 0.55f, 1f)
    private val appFade = PathInterpolator(0.25f, 0.38f, 0.38f, 1f)
    private val appSettle = PathInterpolator(0.22f, 0.35f, 0.30f, 1f)
    private val appExit = PathInterpolator(0.35f, 0.15f, 0.50f, 1f)

    override fun duration(stage: Int): Long = when (stage) {
        UnlockLogicV1.HOME -> 410L
        UnlockLogicV1.WALLPAPER -> 300L
        UnlockLogicV1.LOCKSCREEN -> 165L
        UnlockLogicV1.APP -> 320L
        else -> error("Unknown unlock stage")
    }

    override fun homeOpacity(progress: Float): Float = homeFade.getInterpolation(progress)

    override fun planHomeItem(kind: Int, dx: Float, dy: Float, radius: Float, density: Float): UnlockItemPlan {
        val distance = (sqrt(dx * dx + dy * dy) / radius).coerceIn(0f, 1f)
        val depth = distance * distance * distance
        return when (kind) {
            UnlockLogicV1.DOCK -> UnlockItemPlan(0f, 62f * density, 0f, 0.32f, 0.62f)
            UnlockLogicV1.WIDGET ->
                UnlockItemPlan(dx * 0.43f, dy * 0.53f, 0.24f, 0.20f + depth * 0.12f, 0.64f)
            else -> {
                val spread = 0.80f + depth * 0.78f
                UnlockItemPlan(dx * spread, dy * spread, 0.76f + depth * 0.57f,
                    depth * 0.18f, 0.70f + depth * 0.10f)
            }
        }
    }

    override fun homeItemFrame(plan: UnlockItemPlan, progress: Float, out: UnlockItemFrame) {
        val phase = ((progress - plan.delay) / plan.travel).coerceIn(0f, 1f)
        val remaining = 1f - settle.getInterpolation(phase)
        val visibility = opacity.getInterpolation((phase * 2.6f).coerceAtMost(1f))
        out.x = plan.offsetX * remaining
        out.y = plan.offsetY * remaining
        out.scale = 1f + plan.extraScale * remaining
        out.alpha = visibility * visibility
    }

    override fun wallpaperZoom() = 0.05f

    override fun wallpaperProgress(progress: Float): Float = wallpaper.getInterpolation(progress)

    override fun lockscreenFrame(progress: Float, out: UnlockLockscreenFrame) {
        out.slide = 0.52f * slide.getInterpolation(progress)
        val fade = ((progress - 0.38f) / 0.62f).coerceIn(0f, 1f)
        out.alpha = 1f - (1f - 0.001f) * fade
    }

    override fun appOpacity(progress: Float): Float = appFade.getInterpolation(progress)

    override fun appFrame(progress: Float, out: UnlockAppFrame) {
        val remaining = 1f - appSettle.getInterpolation(progress)
        out.lift = if (progress == 1f) 0f else 0.16f * appExit.getInterpolation(progress)
        out.zoom = 0.06f * remaining
        out.drop = 0.016f * remaining
        out.wallpaperZoom = 0.12f * remaining
    }
}
