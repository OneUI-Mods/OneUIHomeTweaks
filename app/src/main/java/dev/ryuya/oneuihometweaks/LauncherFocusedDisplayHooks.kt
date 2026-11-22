package dev.ryuya.oneuihometweaks

import android.app.Application
import android.os.Build
import android.os.Looper
import android.view.Display
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** Avoids the WindowManager focus query when the launcher's only display is the default one. */
internal object LauncherFocusedDisplayHooks {
    fun install(app: Application, loader: ClassLoader) {
        val sdk = Build.VERSION.SDK_INT
        if (sdk != 37 || !HookCompatibility.matchesClockBuild(sdk, Build.DISPLAY)) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (!HookCompatibility.matchesLauncherVersion(sdk, version)) return

            val contract = DexSymbols.findClass("com.honeyspace.common.interfaces.DisplayHelper", loader)
            val helper = DexSymbols.findClass("a6.d0", loader)
            val focusedDisplay = helper.compatibleDeclaredMethod("getFocusedDisplay")
            val builtInDisplay = helper.compatibleDeclaredMethod("getBuiltInDisplay")
            check(contract.isAssignableFrom(helper))
            check(focusedDisplay.returnType == Display::class.java && builtInDisplay.returnType == Display::class.java)

            val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val manager = global.getMethod("getInstance").invoke(null)
            val serviceField = global.getDeclaredField("mDm").apply { isAccessible = true }
            val service = checkNotNull(serviceField.get(manager))
            val connectedDisplayIds = serviceField.type.getMethod("getDisplayIds", Boolean::class.javaPrimitiveType)
            check(connectedDisplayIds.returnType == IntArray::class.java)
            val main = Looper.getMainLooper()

            ModernHook.aroundMethod(focusedDisplay) { chain ->
                val helperInstance = chain.thisObject
                if (Looper.myLooper() !== main || helperInstance == null) return@aroundMethod chain.proceed()

                val display = try {
                    // Query DisplayManagerService's current topology, including disabled
                    // displays. Its ROM implementation uses a copy-on-write topology snapshot.
                    val ids = connectedDisplayIds.invoke(service, true) as? IntArray
                    if (ids?.size == 1 && ids[0] == Display.DEFAULT_DISPLAY) {
                        (builtInDisplay.invoke(helperInstance) as? Display)
                            ?.takeIf { it.displayId == Display.DEFAULT_DISPLAY }
                    } else null
                } catch (_: ReflectiveOperationException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
                display ?: chain.proceed()
            }
            HookEntry.log("LAUNCHER_SINGLE_DISPLAY_FOCUS_READY")
        } catch (error: Exception) {
            HookEntry.log("LAUNCHER_SINGLE_DISPLAY_FOCUS_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }
}
