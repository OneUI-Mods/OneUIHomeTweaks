package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmStatic

/* Frozen Android 16 admission rules; hook sources are recorded in docs/compatibility. */
object Android16Hooks {
    const val TOP_TASK_METHOD: String = "getTopTask"
    const val TOP_TASK_TAKES_DISPLAY: Boolean = false
    const val FINISH_TAKES_TO_HOME: Boolean = false
    const val FINGERPRINT: String =
        "samsung/m3qjpnw/m3q:16/BP4A.251205.006/S948QOPS1AZHL_SJP1AZHL:user/release-keys"
    const val LAUNCHER_VERSION: Long = 1750305027L
    const val TASK_SCENE_VIEW: String = "com.honeyspace.ui.common.taskScene.TaskSceneView"
    const val TASK_SCENE_DATA: String = "com.honeyspace.ui.common.taskScene.TaskSceneData"
    const val BUILD: String = "BP4A.251205.006.S948QOPS1AZHL"

    @JvmStatic
    fun supportsRecentsFocus(sdk: Int, fingerprint: String?): Boolean {
        return sdk == 36 && Android16Hooks.FINGERPRINT == fingerprint
    }

    @JvmStatic
    fun supports(
        sdk: Int,
        manufacturer: String?,
        model: String?,
        device: String?,
        display: String?,
    ): Boolean {
        return sdk == 36
    }
}
