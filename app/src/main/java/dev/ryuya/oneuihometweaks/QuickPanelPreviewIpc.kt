package dev.ryuya.oneuihometweaks

import android.os.IBinder
import kotlin.jvm.JvmField

/* Private Binder protocol. Only the module app may request a surface from SystemUI. */
object QuickPanelPreviewIpc {
    const val DESCRIPTOR: String = "dev.ryuya.oneuihometweaks.quickpanel.preview"
    @JvmField val CREATE: Int = IBinder.FIRST_CALL_TRANSACTION
    @JvmField val RELEASE: Int = QuickPanelPreviewIpc.CREATE + 1
    const val REGISTER: String = "qs_preview_register"
    const val BACKEND: String = "qs_preview_backend"
    const val MAX_PIXELS: Int = 4096
}
