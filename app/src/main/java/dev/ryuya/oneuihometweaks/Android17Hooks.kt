package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmStatic

/* Android 17 is reviewed independently; never fall back to Android 16 on a mismatch. */
object Android17Hooks {
    const val TOP_TASK_METHOD: String = "runningTaskForDisplayId"
    const val TOP_TASK_TAKES_DISPLAY: Boolean = true
    const val FINISH_TAKES_TO_HOME: Boolean = true
    const val LAUNCHER_VERSION: Long = 1800705053L
    const val TASK_SCENE_VIEW: String = "com.honeyspace.common.taskscene.TaskSceneView"
    const val TASK_SCENE_DATA: String = "com.honeyspace.common.taskscene.TaskSceneData"
    const val BUILD: String = "CP2A.260605.016.S948QOPU1BZI9"
    const val FINGERPRINT: String =
        "samsung/m3qjpnw/m3q:17/CP2A.260605.016/S948QOPU1BZI9_SJP1BZI9:user/release-keys"
    const val CANDIDATE_FIRMWARE: String = "S948QOPU1BZI9"

    @JvmStatic
    fun supportsRecentsFocus(sdk: Int, fingerprint: String?): Boolean {
        return sdk == 37 && Android17Hooks.FINGERPRINT == fingerprint
    }

    @JvmStatic
    fun supports(
        sdk: Int,
        manufacturer: String?,
        model: String?,
        device: String?,
        display: String?,
    ): Boolean {
        // BZI9 contracts were reviewed against ROM and matching installed APKs.
        // Each feature still resolves its full runtime contract before changing behavior.
        return sdk == 37 &&
            "samsung".equals(manufacturer, ignoreCase = true) &&
            "SM-S948Q" == model &&
            "m3q" == device &&
            Android17Hooks.BUILD == display
    }
}
