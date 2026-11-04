package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.view.View
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

/* Folder-return policy and native close animation repair, confined to One UI Home. */
class FolderHooks {
    private val hooks: MutableList<ModernHook.Handle?> = ArrayList<ModernHook.Handle?>()
    private val homeCloseRequested = ThreadLocal.withInitial { false }
    private val preparedHomeClose = ThreadLocal<Any?>()
    private var geometryRefreshes = 0L
    private var animatedHomeCloses = 0L
    private var preservedHomeCloses = 0L
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
        // Samsung's fast Home flick dispatches an extras-only Intent inside the launcher.
        // It never enters Activity.onNewIntent, so scope the native key policy as well.
        val homeKeyPolicy =
            contract.homeKeyPolicyOwner?.let { name ->
                DexSymbols.findClass(name, false, loader)
                    .compatibleDeclaredMethod("handleKeyPolicy", Intent::class.java)
            }
        val homeRoot = homeKeyPolicy?.declaringClass?.compatibleMethod("getRootView")
        val nativeIntentDispatch =
            homeKeyPolicy
                ?.declaringClass
                ?.compatibleDeclaredMethod(
                    "onNewIntent",
                    Intent::class.java,
                )
        check(
            homeKeyPolicy == null ||
                (homeKeyPolicy.returnType == Boolean::class.javaPrimitiveType &&
                    View::class.java.isAssignableFrom(homeRoot!!.returnType)),
        ) {
            "Folder Home key policy contract changed"
        }
        check(
            !(!Activity::class.java.isAssignableFrom(launcher) ||
                isOpen!!.getReturnType() != Boolean::class.javaPrimitiveType ||
                prepare.getReturnType() != Void.TYPE ||
                reset.getReturnType() != Void.TYPE ||
                !DexSymbols.findClass("com.honeyspace.sdk.HoneyScreenManager", false, loader)
                    .isAssignableFrom(managerGetter.getReturnType())),
        ) {
            "Folder contract changed"
        }
        viewModel.setAccessible(true)
        managerGetter.setAccessible(true)
        reset.setAccessible(true)
        try {
            if (homeKeyPolicy != null) {
                // AOT may have inlined this private policy into its native dispatcher.
                ModernHook.deoptimize(nativeIntentDispatch!!)
                hooks.add(
                    ModernHook.hookMethod(
                        homeKeyPolicy,
                        object : ModernHook.Callback() {
                            override fun beforeHookedMethod(p: ModernHook.Param) {
                                p.setObjectExtra(
                                    "previousFolderHomeScope",
                                    homeCloseRequested.get(),
                                )
                                p.setObjectExtra(
                                    "previousPreparedHomeClose",
                                    preparedHomeClose.get(),
                                )
                                preparedHomeClose.remove()
                                if (!active()) return
                                try {
                                    val intent = p.args[0] as? Intent
                                    val root = homeRoot!!.invoke(p.thisObject) as? View
                                    homeCloseRequested.set(
                                        FolderHomePolicy.isVisibleHomeRequest(
                                            fromHomeKey =
                                                intent?.getBooleanExtra(
                                                    "android.intent.extra.FROM_HOME_KEY",
                                                    false,
                                                ) == true,
                                            rootVisible =
                                                root?.isShown == true &&
                                                    root.windowVisibility == View.VISIBLE,
                                            hasWindowFocus = root?.hasWindowFocus() == true,
                                            hasActionOrCategories =
                                                intent?.action != null ||
                                                    !intent?.categories.isNullOrEmpty(),
                                        ),
                                    )
                                } catch (error: Throwable) {
                                    disable(error)
                                }
                            }

                            override fun afterHookedMethod(p: ModernHook.Param) {
                                homeCloseRequested.set(
                                    p.getObjectExtra("previousFolderHomeScope") == true,
                                )
                                preparedHomeClose.set(p.getObjectExtra("previousPreparedHomeClose"))
                            }
                        },
                    ),
                )
            }
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
                ),
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
                                if (homeCloseRequested.get() == true && (p.args[0] as Long) > 0L) {
                                    preparedHomeClose.set(owner)
                                }
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
                ),
            )
            hooks.add(
                ModernHook.hookMethod(
                    newIntent,
                    object : ModernHook.Callback() {
                        override fun beforeHookedMethod(p: ModernHook.Param) {
                            val intent: Intent? = p.args[0] as Intent?
                            p.setObjectExtra("folderHomeFocused", homeCloseRequested.get())
                            homeCloseRequested.set(
                                active() &&
                                    (p.thisObject as Activity).hasWindowFocus() &&
                                    intent != null &&
                                    Intent.ACTION_MAIN == intent.getAction() &&
                                    intent.hasCategory(Intent.CATEGORY_HOME),
                            )
                        }

                        override fun afterHookedMethod(p: ModernHook.Param) {
                            homeCloseRequested.set(
                                JavaBoolean.TRUE == p.getObjectExtra("folderHomeFocused"),
                            )
                        }
                    },
                ),
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
                                    homeCloseRequested.get() != true ||
                                    p.args[0] !== homeNormal
                            )
                                return
                            try {
                                // onHomeKeyClick can already have started a folder close. The
                                // trailing gotoScreen would immediately finish that animation.
                                if (
                                    FolderHomePolicy.preservesPreparedClose(
                                        preparedHomeClose.get(),
                                        p.thisObject,
                                        currentState!!.invoke(p.thisObject) === homeNormal,
                                    )
                                ) {
                                    p.setResult(null)
                                    preservedHomeCloses++
                                    diagnostic = "native folder close preserved"
                                    trace("preserved_home", "nativeCloseAlreadyPrepared=true")
                                    return
                                }
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
                ),
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
        else if (enabled)
            "$diagnostic; geometry=$geometryRefreshes; home=$animatedHomeCloses; preserved=$preservedHomeCloses"
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
            "FOLDER_CLOSE event=$event $detail geometry=$geometryRefreshes home=$animatedHomeCloses",
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
