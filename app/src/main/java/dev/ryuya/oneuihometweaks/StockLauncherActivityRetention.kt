package dev.ryuya.oneuihometweaks

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Trace
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import dev.ryuya.oneuihometweaks.compat.compatibleMethod

/** Optionally retains the stock Home and Recents activities during stopped-activity memory reclaim. */
internal object StockLauncherActivityRetention {
    const val HOME_KEY = "keep_home_activity"
    const val RECENTS_KEY = "keep_recents_activity"
    private const val HOME_ACTIVITY = "com.sec.android.app.launcher.activities.LauncherActivity"
    private const val RECENTS_ACTIVITY = "com.android.quickstep.RecentsActivity"

    data class Policy(val home: Boolean = false, val recents: Boolean = false)

    @Volatile var available = false
        private set
    @Volatile var policy = Policy()

    fun supportsDevice(): Boolean = Android17Hooks.supportsRecentsFocus(Build.VERSION.SDK_INT, Build.FINGERPRINT)

    fun install(loader: ClassLoader) {
        if (!supportsDevice()) return
        try {
            val process = DexSymbols.findClass("com.android.server.wm.WindowProcessController", false, loader)
            val record = DexSymbols.findClass("com.android.server.wm.ActivityRecord", false, loader)
            val release = process.compatibleDeclaredMethod("releaseSomeActivities")
            val destroy = record.compatibleDeclaredMethod("destroyImmediately", String::class.java)
            check(release.returnType == Void.TYPE && destroy.returnType == Boolean::class.javaPrimitiveType)
            val info = record.compatibleDeclaredField("info").apply { isAccessible = true }
            val user = record.compatibleDeclaredField("mUserId").apply { isAccessible = true }
            val owningProcess = record.compatibleDeclaredField("app").apply { isAccessible = true }
            val service = record.compatibleDeclaredField("mAtmService").apply { isAccessible = true }
            val homeProcess = service.type.compatibleDeclaredField("mHomeProcess").apply { isAccessible = true }
            val isHome = record.compatibleMethod("isActivityTypeHome").apply { isAccessible = true }
            val isRecents = record.compatibleMethod("isActivityTypeRecents").apply { isAccessible = true }
            check(info.type == ActivityInfo::class.java && homeProcess.type == process && owningProcess.type == process)
            check(ModernHook.deoptimize(release)) { "Activity removal may be inlined" }
            ModernHook.aroundMethod(destroy) { chain ->
                val current = policy
                val activity = chain.thisObject
                if ((!current.home && !current.recents) || chain.args[0] != "low-mem" ||
                    activity == null || user.getInt(activity) != 0
                ) {
                    return@aroundMethod chain.proceed()
                }
                val metadata = info.get(activity) as? ActivityInfo
                val owner = owningProcess.get(activity)
                val atm = service.get(activity)
                if (metadata?.packageName != HookEntry.LAUNCHER ||
                    owner == null || atm == null || owner !== homeProcess.get(atm)
                ) return@aroundMethod chain.proceed()

                val screen = when (metadata.targetActivity ?: metadata.name) {
                    HOME_ACTIVITY -> if (current.home && isHome.invoke(activity) == true) "home" else null
                    RECENTS_ACTIVITY -> if (current.recents && isRecents.invoke(activity) == true) "recents" else null
                    else -> null
                } ?: return@aroundMethod chain.proceed()
                if (Trace.isEnabled()) {
                    Trace.beginSection("OneUI $screen retained during activity reclaim")
                    Trace.endSection()
                }
                HookEntry.log("LAUNCHER_ACTIVITY_RETAINED_LOW_MEMORY $screen")
                false
            }
            available = true
            HookEntry.log("LAUNCHER_ACTIVITY_RETENTION_READY")
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_ACTIVITY_RETENTION_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
