package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Looper
import java.lang.Boolean as JavaBoolean
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.function.Supplier
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Folder-return policy and native close animation repair, confined to One UI Home. */
class FolderHooks {
    private val hooks: MutableList<ModernHook.Handle?> = ArrayList<ModernHook.Handle?>()
    private val homeWhileFocused: ThreadLocal<Boolean?>? =
        ThreadLocal.withInitial<Boolean?>(Supplier { false })
    private var returnedManager: WeakReference<Any?> = WeakReference<Any?>(null)
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
        val manager = Class.forName(contract!!.manager, false, loader)
        val animator = Class.forName(contract!!.animator, false, loader)
        val state = Class.forName("com.honeyspace.sdk.HoneyState", false, loader)
        val key = Class.forName("com.honeyspace.sdk.transition.CloseTarget\$Key", false, loader)
        val launcher = Class.forName("com.sec.android.app.launcher.Launcher", false, loader)
        homeNormal = FolderHooks.singleton(loader, "HomeScreen\$Normal")
        appsNormal = FolderHooks.singleton(loader, "AppScreen\$Normal")
        currentState = manager.getMethod("getCurrentHoneyScreenState")
        goTo = manager.getDeclaredMethod("gotoScreen", state)
        isOpen = manager.getMethod("isOpenFolderMode")
        animate =
            manager.getDeclaredMethod(
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
        val target = manager.getDeclaredMethod("findCloseAnimationTarget", key)
        val prepare =
            animator.getDeclaredMethod(
                contract!!.prepare,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
        val reset = animator.getDeclaredMethod(contract!!.resetGeometry)
        val viewModel = animator.getDeclaredField(contract!!.viewModelField)
        val managerGetter = viewModel.getType().getMethod(contract!!.managerGetter)
        val newIntent = launcher.getDeclaredMethod("onNewIntent", Intent::class.java)
        check(
            !(!Activity::class.java.isAssignableFrom(launcher) ||
                isOpen!!.getReturnType() != Boolean::class.javaPrimitiveType ||
                prepare.getReturnType() != Void.TYPE ||
                reset.getReturnType() != Void.TYPE ||
                !Class.forName("com.honeyspace.sdk.HoneyScreenManager", false, loader)
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
                                if (returnToFolder) {
                                    returnedManager = WeakReference<Any?>(p.thisObject)
                                } else {
                                    // Use the same native state change as Home, before Samsung
                                    // resolves its
                                    // closing target. The app then shrinks towards the closed
                                    // folder icon.
                                    val previous = currentState!!.invoke(p.thisObject)
                                    val normal: Any? =
                                        if (previous.javaClass.getName().contains(".AppScreen$"))
                                            appsNormal
                                        else homeNormal
                                    ownRequest = true
                                    try {
                                        goTo!!.invoke(p.thisObject, normal)
                                    } finally {
                                        ownRequest = false
                                    }
                                    returnedManager.clear()
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
                                if (owner !== returnedManager.get()) return
                                // Native caching compares only the icon's x/y. App gestures can
                                // change
                                // the root scale without moving the icon; rebuild native geometry.
                                reset.invoke(p.thisObject)
                                returnedManager.clear()
                                diagnostic = "native close geometry refreshed"
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
                            p.setObjectExtra("folderHomeFocused", homeWhileFocused!!.get())
                            homeWhileFocused!!.set(
                                active() &&
                                    (p.thisObject as Activity).hasWindowFocus() &&
                                    intent != null &&
                                    Intent.ACTION_MAIN == intent.getAction() &&
                                    intent.hasCategory(Intent.CATEGORY_HOME)
                            )
                        }

                        override fun afterHookedMethod(p: ModernHook.Param) {
                            homeWhileFocused!!.set(
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
                                    !homeWhileFocused!!.get()!! ||
                                    p.thisObject !== returnedManager.get() ||
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
                                diagnostic = "native animated home gesture"
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
        if (!active || !keepFolder) returnedManager.clear()
    }

    fun diagnostic(): String? {
        return if (failed) "stopped after error" else if (enabled) diagnostic else "off"
    }

    private fun active(): Boolean {
        return enabled && !failed && Looper.myLooper() == Looper.getMainLooper()
    }

    private fun disable(error: Throwable?) {
        failed = true
        returnedManager.clear()
        HookEntry.log("FOLDER_DISABLED " + error)
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        private fun singleton(loader: ClassLoader?, name: String?): Any? {
            return Class.forName("com.honeyspace.sdk." + name, false, loader)
                .getField("INSTANCE")
                .get(null)
        }
    }
}
