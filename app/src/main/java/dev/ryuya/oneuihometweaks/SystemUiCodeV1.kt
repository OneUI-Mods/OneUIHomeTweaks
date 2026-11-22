package dev.ryuya.oneuihometweaks

import android.app.Application

/** ABI shared between the resident SystemUI bridge and signed app releases. */
internal interface SystemUiCodeV1 {
    val apiVersion: Int
    fun install(app: Application, loader: ClassLoader)
}
