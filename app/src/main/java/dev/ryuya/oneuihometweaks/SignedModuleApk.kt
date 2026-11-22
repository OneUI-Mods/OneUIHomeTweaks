package dev.ryuya.oneuihometweaks

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PatternMatcher
import androidx.core.content.ContextCompat
import java.io.File
import java.security.MessageDigest

internal object SignedModuleApk {
    private const val PACKAGE = "dev.ryuya.oneuihometweaks"
    private const val SIGNER = "7938e1850451b95c45c8f138a768c9da7c5d9db7fb61a883ac8dc724077ddb03"

    data class Installed(val version: Long, val source: String)

    @Suppress("DEPRECATION")
    fun read(app: Application): Installed {
        val info = app.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val certificate = requireNotNull(info.signingInfo?.apkContentsSigners?.singleOrNull())
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        check(digest == SIGNER) { "Unexpected APK signing certificate" }
        val source = requireNotNull(info.applicationInfo?.sourceDir)
        val file = File(source)
        check(file.isFile && file.name == "base.apk" && file.canonicalPath == source &&
            source.startsWith("/data/app/")) { "Unexpected installed APK path" }
        return Installed(info.longVersionCode, source)
    }

    fun watch(app: Application, onReplaced: () -> Unit) {
        val filter = IntentFilter(Intent.ACTION_PACKAGE_REPLACED).apply {
            addDataScheme("package")
            addDataSchemeSpecificPart(PACKAGE, PatternMatcher.PATTERN_LITERAL)
        }
        // Android reserves PACKAGE_REPLACED to the system.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_PACKAGE_REPLACED &&
                    intent.data?.schemeSpecificPart == PACKAGE
                ) onReplaced()
            }
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }
}
