package dev.ryuya.oneuihometweaks

import android.app.Application
import android.os.Build

/** Replaceable SystemUI code. Targets are resolved against the running SystemUI class loader. */
internal class SignedSystemUiHooks : SystemUiCodeV1 {
    override val apiVersion: Int get() = 1

    override fun install(app: Application, loader: ClassLoader) {
        if (BuildConfig.SHADE_FRAME_RATE_CACHE) ShadeFrameRateRequests.install(loader)
        if (BuildConfig.SHADE_TRIM_DEBOUNCE) ShadeGraphicsTrim.install(loader)
        if (BuildConfig.SHADE_PERF_TRACE && Build.VERSION.SDK_INT == 37) {
            ShadePerformanceTrace.install(loader)
        }
        BackGestureArrowHooks.install(app, loader)
        AppUnlockMotion.install(app, loader)
        if (Build.VERSION.SDK_INT == 37) StockSurfaceAnimationHooks.install(app, loader)
        InteractionRefresh.install(app, loader, home = false)
        QuickPanelHooks().install(app, loader)
        ClockDateHooks().install(app, loader)
        VolumeHooks().install(app, loader)
    }
}
