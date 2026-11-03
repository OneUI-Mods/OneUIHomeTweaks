package dev.ryuya.oneuihometweaks.compat

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Uses the module's own JNI library even when the host has no module-native search path. */
object DexKitNative {
    @Volatile private var moduleInfo: ApplicationInfo? = null
    private var loaded = false

    fun configure(info: ApplicationInfo) {
        moduleInfo = info
    }

    @Synchronized
    fun load() {
        if (loaded) return
        try {
            val info = moduleInfo
            if (info == null) {
                // Normal module application/provider process, with an Android app class loader.
                System.loadLibrary("dexkit")
            } else {
                val extracted = File(info.nativeLibraryDir, "libdexkit.so")
                if (extracted.isFile) {
                    System.load(extracted.absolutePath)
                } else {
                    // Android loads uncompressed, aligned ELF entries directly from the APK.
                    val abis =
                        if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS
                        else Build.SUPPORTED_32_BIT_ABIS
                    val entry =
                        ZipFile(info.sourceDir).use { zip ->
                            abis
                                .map { "lib/$it/libdexkit.so" }
                                .firstOrNull { name ->
                                    zip.getEntry(name)?.let { it.method == ZipEntry.STORED } == true
                                }
                        } ?: throw UnsatisfiedLinkError("No compatible stored DexKit library")
                    System.load("${info.sourceDir}!/$entry")
                }
            }
            loaded = true
        } catch (error: LinkageError) {
            // Fixed library only. Preserve the linker reason instead of losing it in a cache state.
            Log.e("OneUIHomeTweaks", "DEXKIT_NATIVE_LOAD_FAILED", error)
            throw error
        }
    }
}
