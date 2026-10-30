package dev.ryuya.oneuihometweaks

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings.Global
import android.util.Log
import kotlin.jvm.JvmStatic

object HostStatus {
    @JvmStatic
    fun report(context: Context?, state: String?, detail: String?) {
        HostStatus.reportFeature(context, null, state, detail)
    }

    @JvmStatic
    fun reportFeature(context: Context?, feature: String?, state: String?, detail: String?) {
        if (context == null) return
        try {
            val b = Bundle()
            b.putString("state", state)
            b.putString("detail", detail)
            b.putInt("pid", Process.myPid())
            b.putLong("elapsed", SystemClock.elapsedRealtime())
            b.putInt(
                "boot",
                Global.getInt(
                    context.getContentResolver(),
                    Global.BOOT_COUNT,
                    -1,
                ),
            )
            b.putString("host", context.getPackageName())
            if (feature != null) b.putString("feature", feature)
            context.getContentResolver().call(ConfigProvider.URI!!, "report_status", null, b)
        } catch (e: Throwable) {
            Log.w(
                "OneUIHomeTweaks",
                "Status report unavailable: " + e.javaClass.getSimpleName(),
            )
        }
    }
}
