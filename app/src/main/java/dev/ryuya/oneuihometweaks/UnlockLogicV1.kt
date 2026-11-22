package dev.ryuya.oneuihometweaks

/**
 * In-process ABI for the signed unlock motion implementation.
 * Hook ownership, authentication, and SurfaceControl lifetimes stay in the host.
 */
interface UnlockLogicV1 {
    val apiVersion: Int get() = 1
    fun duration(stage: Int): Long
    fun homeOpacity(progress: Float): Float
    fun planHomeItem(kind: Int, dx: Float, dy: Float, radius: Float, density: Float): UnlockItemPlan
    fun homeItemFrame(plan: UnlockItemPlan, progress: Float, out: UnlockItemFrame)
    fun wallpaperZoom(): Float
    fun wallpaperProgress(progress: Float): Float
    fun lockscreenFrame(progress: Float, out: UnlockLockscreenFrame)
    fun appOpacity(progress: Float): Float
    fun appFrame(progress: Float, out: UnlockAppFrame)

    companion object {
        const val HOME = 0
        const val WALLPAPER = 1
        const val LOCKSCREEN = 2
        const val APP = 3
        const val ICON = 0
        const val WIDGET = 1
        const val DOCK = 2
    }
}

data class UnlockItemPlan(
    val offsetX: Float,
    val offsetY: Float,
    val extraScale: Float,
    val delay: Float,
    val travel: Float,
)

class UnlockItemFrame {
    var x = 0f
    var y = 0f
    var scale = 1f
    var alpha = 1f
}

class UnlockLockscreenFrame {
    var slide = 0f
    var alpha = 1f
}

class UnlockAppFrame {
    var lift = 0f
    var zoom = 0f
    var drop = 0f
    var wallpaperZoom = 0f
}
