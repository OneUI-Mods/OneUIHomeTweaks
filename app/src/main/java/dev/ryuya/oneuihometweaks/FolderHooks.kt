package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Looper
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import dev.ryuya.oneuihometweaks.compat.compatibleField
import dev.ryuya.oneuihometweaks.compat.compatibleMethod
import dev.ryuya.oneuihometweaks.compat.compatibleName
import java.lang.Boolean as JavaBoolean
import java.lang.reflect.Method
import java.util.ArrayList
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Folder-return policy and native close animation repair, confined to One UI Home. */
class FolderHooks {
    private val hooks: MutableList<ModernHook.Handle?> = ArrayList<ModernHook.Handle?>()
    private val homeWhileFocused = ThreadLocal.withInitial { false }
    private var geometryRefreshes = 0L
    private var animatedHomeCloses = 0L
    @Volatile private var enabled: Boolean = false
    @Volatile private var returnToFolder: Boolean = true
    private var failed: Boolean = false
    private var ownRequest: Boolean = false
    private var currentState: Method? = null
    private var goTo: Method? = null
    private var animate: Method? = null
    private var isOpen: Method? = null
    private var homeNormal: Any? = null
    private var appsNormal: Any? = null
    private var diagnostic: String = "unavailable"

    @Throws(Exception::class)
    fun install(loader: ClassLoader?) {
        val contract = FolderContracts.forSdk(Build.VERSION.SDK_INT)
        val manager = DexSymbols.findClass(contract!!.manager, false, loader)
        val animator = DexSymbols.findClass(contract!!.animator, false, loader)
        val state = DexSymbols.findClass("com.honeyspace.sdk.HoneyState", false, loader)
        val key =
            DexSymbols.findClass("com.honeyspace.sdk.transition.CloseTarget\$Key", false, loader)
        val launcher = DexSymbols.findClass("com.sec.android.app.launcher.Launcher", false, loader)
        homeNormal = FolderHooks.singleton(loader, "HomeScreen\$Normal")
        appsNormal = FolderHooks.singleton(loader, "AppScreen\$Normal")
        currentState = manager.compatibleMethod("getCurrentHoneyScreenState")
        goTo = manager.compatibleDeclaredMethod("gotoScreen", state)
        isOpen = manager.compatibleMethod("isOpenFolderMode")
        animate =
            manager.compatibleDeclaredMethod(
                "gotoScreenWithAnimation",
                state,
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
        val target = manager.compatibleDeclaredMethod("findCloseAnimationTarget", key)
        val prepare =
            animator.compatibleDeclaredMethod(
                contract!!.prepare,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
        val reset = animator.compatibleDeclaredMethod(contract!!.resetGeometry)
        val viewModel = animator.compatibleDeclaredField(contract!!.viewModelField)
        val managerGetter = viewModel.getType().compatibleMethod(contract!!.managerGetter)
        val newIntent = launcher.compatibleDeclaredMethod("onNewIntent", Intent::class.java)
        check(
            !(!Activity::class.java.isAssignableFrom(launcher) ||
                isOpen!!.getReturnType() != Boolean::class.javaPrimitiveType ||
                prepare.getReturnType() != Void.TYPE ||
                reset.getReturnType() != Void.TYPE ||
                !DexSymbols.findClass("com.honeyspace.sdk.HoneyScreenManager", false, loader)
                    .isAssignableFrom(managerGetter.getReturnType()))
        ) {
            "Folder contract changed"
        }
        viewModel.setAccessible(true)
        managerGetter.setAccessible(true)
        reset.setAccessible(true)
        try {
            hooks.add(
                ModernHook.hookMethod(
                    target,
                    object : ModernHook.Callback() {
                        override fun beforeHookedMethod(p: ModernHook.Param) {
                            if (!active() || ownRequest) return
                            try {
                                if (JavaBoolean.TRUE != isOpen!!.invoke(p.thisObject)) return
                                if (!returnToFolder) {
                                    // Use the same native state change as Home, before Samsung
                                    // resolves its
                                    // closing target. The app then shrinks towards the closed
                                    // folder icon.
                                    val previous = currentState!!.invoke(p.thisObject)
                                    val normal: Any? =
                                        if (
                                            previous.javaClass
                                                .compatibleName()
                                                .contains(".AppScreen$")
                                        )
                                            appsNormal
                                        else homeNormal
                                    ownRequest = true
                                    try {
                                        goTo!!.invoke(p.thisObject, normal)
                                    } finally {
                                        ownRequest = false
                                    }
                                    diagnostic = "home after folder launch"
                                }
                            } catch (error: Throwable) {
                                disable(error)
                            }
                        }
                    },
                )
            )
            hooks.add(
                ModernHook.hookMethod(
                    prepare,
                    object : ModernHook.Callback() {
                        override fun beforeHookedMethod(p: ModernHook.Param) {
                            if (!active() || !returnToFolder || JavaBoolean.TRUE == p.args[1])
                                return
                            try {
                                val owner = managerGetter.invoke(viewModel.get(p.thisObject))
                                if (owner == null) return
                                // Closing a folder is sufficient evidence. Notification launches
                                // need not pass findCloseAnimationTarget, and a prior close must
                                // not consume eligibility for a later Home gesture.
                                // Native caching compares only icon x/y, omitting root scale.
                                reset.invoke(p.thisObject)
                                geometryRefreshes++
                                diagnostic = "native close geometry refreshed"
                                trace("geometry", "duration=${p.args[0]}")
                            } catch (error: Throwable) {
                                disable(error)
                            }
                        }
                    },
                )
            )
            hooks.add(
                ModernHook.hookMethod(
                    newIntent,
                    object : ModernHook.Callback() {
                        override fun beforeHookedMethod(p: ModernHook.Param) {
                            val intent: Intent? = p.args[0] as Intent?
                            p.setObjectExtra("folderHomeFocused", homeWhileFocused.get())
                            homeWhileFocused.set(
                                active() &&
                                    (p.thisObject as Activity).hasWindowFocus() &&
                                    intent != null &&
                                    Intent.ACTION_MAIN == intent.getAction() &&
                                    intent.hasCategory(Intent.CATEGORY_HOME)
                            )
                        }

                        override fun afterHookedMethod(p: ModernHook.Param) {
                            homeWhileFocused.set(
                                JavaBoolean.TRUE == p.getObjectExtra("folderHomeFocused")
                            )
                        }
                    },
                )
            )
            hooks.add(
                ModernHook.hookMethod(
                    goTo!!,
                    object : ModernHook.Callback() {
                        override fun beforeHookedMethod(p: ModernHook.Param) {
                            if (
                                !active() ||
                                    !returnToFolder ||
                                    ownRequest ||
                                    homeWhileFocused.get() != true ||
                                    p.args[0] !== homeNormal
                            )
                                return
                            try {
                                if (JavaBoolean.TRUE != isOpen!!.invoke(p.thisObject)) return
                                ownRequest = true
                                try {
                                    // A second Home gesture must close the folder with Samsung's
                                    // own
                                    // animation, rather than taking the immediate state-change
                                    // path.
                                    animate!!.invoke(
                                        p.thisObject,
                                        homeNormal,
                                        0f,
                                        true,
                                        true,
                                        false,
                                        false,
                                        true,
                                        350L,
                                        -1f,
                                    )
                                } finally {
                                    ownRequest = false
                                }
                                p.setResult(null)
                                animatedHomeCloses++
                                diagnostic = "native animated home gesture"
                                trace("focused_home", "nativeDuration=350")
                            } catch (error: Throwable) {
                                disable(error)
                            }
                        }
                    },
                )
            )
            diagnostic = "ready"
            HookEntry.log("FOLDER_HOOKS_READY sdk=" + Build.VERSION.SDK_INT)
        } catch (error: Throwable) {
            for (hook in hooks) hook!!.unhook()
            hooks.clear()
            throw if (error is Exception) error else IllegalStateException(error)
        }
    }

    fun configure(active: Boolean, keepFolder: Boolean) {
        enabled = active
        returnToFolder = keepFolder
    }

    fun diagnostic(): String? {
        return if (failed) "stopped after error"
        else if (enabled) "$diagnostic; geometry=$geometryRefreshes; home=$animatedHomeCloses"
        else "off"
    }

    private fun active(): Boolean {
        return enabled && !failed && Looper.myLooper() == Looper.getMainLooper()
    }

    private fun disable(error: Throwable?) {
        failed = true
        HookEntry.log("FOLDER_DISABLED " + error)
    }

    private fun trace(event: String, detail: String) {
        // One entry per close, never per frame; do not collect notification or app contents.
        HookEntry.log(
            "FOLDER_CLOSE event=$event $detail geometry=$geometryRefreshes home=$animatedHomeCloses"
        )
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        private fun singleton(loader: ClassLoader?, name: String?): Any? {
            return DexSymbols.findClass("com.honeyspace.sdk." + name, false, loader)
                .compatibleField("INSTANCE")
                .get(null)
        }
    }
}
