package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings.Global
import android.provider.Settings.Secure
import android.view.Display
import android.view.WindowInsets.Type
import android.view.WindowInsetsController
import java.lang.Boolean as JavaBoolean
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.WeakHashMap
import java.util.function.Supplier
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Recents-window-only gesture bar policy. Never writes Samsung's global hint setting. */
class RecentsBar {
    private val states: MutableMap<WindowInsetsController?, RecentsBar.State?> =
        WeakHashMap<WindowInsetsController?, RecentsBar.State?>()
    private val hooks: MutableList<ModernHook.Handle?> = ArrayList<ModernHook.Handle?>()
    private val ownRequest: ThreadLocal<Boolean?>? =
        ThreadLocal.withInitial<Boolean?>(Supplier { false })
    private var recentsClass: Class<*>? = null
    private var requestedTypes: Method? = null
    @Volatile private var enabled: Boolean = false
    @Volatile private var failed: Boolean = false
    private var diagnostic: String = "unavailable"
    @Volatile private var gestureOwner: WeakReference<Activity?> = WeakReference<Activity?>(null)

    @Throws(Exception::class)
    fun install(app: Application, loader: ClassLoader?) {
        recentsClass = ModernHook.findClass("com.android.quickstep.RecentsActivity", loader)
        val controllerClass = ModernHook.findClass("android.view.InsetsController", null)
        requestedTypes =
            WindowInsetsController::class.java.getDeclaredMethod("getRequestedVisibleTypes")
        requestedTypes!!.setAccessible(true)
        check(
            !(!Activity::class.java.isAssignableFrom(recentsClass) ||
                !WindowInsetsController::class.java.isAssignableFrom(controllerClass) ||
                requestedTypes!!.getReturnType() != Int::class.javaPrimitiveType ||
                controllerClass!!
                    .getDeclaredMethod("show", Int::class.javaPrimitiveType)
                    .getReturnType() != Void.TYPE ||
                controllerClass!!
                    .getDeclaredMethod("hide", Int::class.javaPrimitiveType)
                    .getReturnType() != Void.TYPE)
        ) {
            "Recents insets contract changed"
        }
        try {
            val gesture =
                ModernHook.findClass("com.honeyspace.gesture.usecase.SystemGestureUseCase", loader)
            val animation =
                ModernHook.findClass(
                    "com.honeyspace.gesture.inputconsumer.RecentsAnimationState",
                    loader,
                )
            val allowHidden = gesture!!.getDeclaredMethod("isAllowGestureIgnoringBarVisibility")
            check(allowHidden.getReturnType() == Boolean::class.javaPrimitiveType) {
                "Gesture visibility contract changed"
            }
            for (caller in
                arrayOf<Method?>(
                    gesture!!.getDeclaredMethod(
                        "canStartSystemGesture",
                        Boolean::class.javaPrimitiveType,
                    ),
                    gesture!!.getDeclaredMethod("canStartWithNavHidden"),
                    animation!!.getDeclaredMethod("canStartSystemGesture"),
                    animation!!.getDeclaredMethod("canStartWithNavHidden"),
                )) check(ModernHook.deoptimize(caller!!)) {
                "Cannot deoptimize gesture visibility caller"
            }
            hooks.add(
                ModernHook.hookMethod(
                    allowHidden,
                    object : ModernHook.Callback() {
                        override fun afterHookedMethod(p: ModernHook.Param) {
                            if (p.hasThrowable() || JavaBoolean.TRUE == p.getResult() || failed)
                                return
                            val a = gestureOwner.get()
                            if (
                                a != null &&
                                    !a.isDestroyed() &&
                                    a.hasWindowFocus() &&
                                    shouldHide(a) &&
                                    !a.getSystemService<KeyguardManager?>(
                                            KeyguardManager::class.java
                                        )
                                        .isKeyguardLocked()
                            )
                                p.setResult(true)
                        }
                    },
                )
            )
            for (show in booleanArrayOf(true, false)) hooks.add(
                ModernHook.findAndHookMethod(
                    controllerClass,
                    if (show) "show" else "hide",
                    Int::class.javaPrimitiveType,
                    object : ModernHook.Callback() {
                        override fun beforeHookedMethod(p: ModernHook.Param) {
                            if (
                                failed ||
                                    ownRequest!!.get()!! ||
                                    Looper.myLooper() != Looper.getMainLooper()
                            )
                                return
                            val state = states.get(p.thisObject)
                            if (state == null || (p.args[0] as Int and Type.navigationBars()) == 0)
                                return
                            state.nativeVisible = show
                            val a = state.activity.get()
                            if (show && state.active && a != null && shouldHide(a))
                                p.args[0] = p.args[0] as Int and Type.navigationBars().inv()
                        }
                    },
                )
            )
            hooks.add(
                ModernHook.findAndHookMethod(
                    recentsClass!!,
                    "onResume",
                    object : ModernHook.Callback() {
                        override fun afterHookedMethod(p: ModernHook.Param) {
                            if (!p.hasThrowable()) apply(p.thisObject as Activity)
                        }
                    },
                )
            )
            hooks.add(
                ModernHook.findAndHookMethod(
                    Activity::class.java,
                    "onWindowFocusChanged",
                    Boolean::class.javaPrimitiveType,
                    object : ModernHook.Callback() {
                        override fun afterHookedMethod(p: ModernHook.Param) {
                            if (p.hasThrowable() || !recentsClass!!.isInstance(p.thisObject)) return
                            if (p.args[0] as Boolean) apply(p.thisObject as Activity)
                            else if (gestureOwner.get() === p.thisObject)
                                gestureOwner = WeakReference<Activity?>(null)
                        }
                    },
                )
            )
            hooks.add(
                ModernHook.findAndHookMethod(
                    recentsClass!!,
                    "onConfigurationChanged",
                    Configuration::class.java,
                    object : ModernHook.Callback() {
                        override fun afterHookedMethod(p: ModernHook.Param) {
                            if (!p.hasThrowable()) apply(p.thisObject as Activity)
                        }
                    },
                )
            )
            val observer: ContentObserver =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(self: Boolean, uri: Uri?) {
                        refresh()
                    }
                }
            app.getContentResolver()
                .registerContentObserver(Secure.getUriFor("navigation_mode"), false, observer)
            app.getContentResolver()
                .registerContentObserver(
                    Global.getUriFor("navigation_bar_gesture_hint"),
                    false,
                    observer,
                )
            diagnostic = "off"
            HookEntry.log("RECENTS_BAR_HOOK_READY window-local gesture bar policy")
        } catch (error: Throwable) {
            for (hook in hooks) hook!!.unhook()
            hooks.clear()
            if (error is Exception) throw error
            throw IllegalStateException(error)
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value && !failed && !hooks.isEmpty()
        if (!enabled) gestureOwner = WeakReference<Activity?>(null)
        if (!failed && !hooks.isEmpty()) diagnostic = if (enabled) "ready" else "off"
        refresh()
    }

    fun diagnostic(): String {
        return diagnostic
    }

    private fun refresh() {
        for (s in ArrayList<RecentsBar.State?>(states.values)) {
            val a = s!!.activity.get()
            if (a != null && !a.isDestroyed()) apply(a)
        }
    }

    private fun shouldHide(a: Activity): Boolean {
        val mode = RecentsBar.mode(a)
        val d = a.getDisplay()
        return NavigationPolicy.hide(
            enabled,
            mode,
            Global.getInt(a.getContentResolver(), "navigation_bar_gesture_hint", -1),
            !a.isInMultiWindowMode() &&
                !a.isInPictureInPictureMode() &&
                d != null &&
                d.getDisplayId() == Display.DEFAULT_DISPLAY,
        )
    }

    private fun apply(a: Activity) {
        if (failed || a.isDestroyed() || !recentsClass!!.isInstance(a)) return
        try {
            val controller = a.getWindow().getInsetsController()
            if (controller == null) return
            var state = states.get(controller)
            if (state == null) {
                // PhoneWindow exposes a PendingInsetsController before attachment. Its requested
                // mask is
                // replayed into a new real controller. Keep the pre-override state across that
                // handoff;
                // otherwise our own hidden mask would be mistaken for the original native
                // preference.
                val entries = states.entries.iterator()
                while (entries.hasNext()) {
                    val entry = entries.next()
                    if (entry.value!!.activity.get() === a) {
                        state = entry.value
                        entries.remove()
                        break
                    }
                }
                if (state != null) states.put(controller, state)
            }
            val hide = shouldHide(a)
            if (!hide) {
                if (state != null && state.active) restore(controller, state)
                if (enabled)
                    diagnostic =
                        if (RecentsBar.mode(a) == 0 || RecentsBar.mode(a) == 1) "buttons-skipped"
                        else "system"
                return
            }
            if (state == null) {
                val nativeVisible: Boolean =
                    (requestedTypes!!.invoke(controller) as Int and Type.navigationBars()) != 0
                state = RecentsBar.State(a, nativeVisible, controller.getSystemBarsBehavior())
                states.put(controller, state)
            }
            if (!state.active) {
                state.nativeBehavior = controller.getSystemBarsBehavior()
                state.active = true
                HookEntry.log("RECENTS_BAR hide; nativeVisible=" + state.nativeVisible)
            }
            ownRequest!!.set(true)
            try {
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                )
                controller.hide(Type.navigationBars())
            } finally {
                ownRequest!!.remove()
            }
            diagnostic = "hidden"
            if (a.hasWindowFocus()) gestureOwner = WeakReference<Activity?>(a)
        } catch (error: Throwable) {
            failed = true
            enabled = false
            gestureOwner = WeakReference<Activity?>(null)
            diagnostic = "error:" + error.javaClass.getSimpleName()
            for (hook in hooks) {
                try {
                    hook!!.unhook()
                } catch (ignored: Throwable) {}
            }
            hooks.clear()
            for (entry in
                ArrayList<MutableMap.MutableEntry<WindowInsetsController?, RecentsBar.State?>?>(
                    states.entries
                )) {
                try {
                    restore(entry!!.key!!, entry!!.value!!)
                } catch (ignored: Throwable) {}
            }
            HookEntry.log("RECENTS_BAR_DISABLED " + error)
        }
    }

    private fun restore(controller: WindowInsetsController, state: RecentsBar.State) {
        if (!state.active) return
        state.active = false
        val a = state.activity.get()
        if (gestureOwner.get() === a) gestureOwner = WeakReference<Activity?>(null)
        ownRequest!!.set(true)
        try {
            controller.setSystemBarsBehavior(state.nativeBehavior)
            // A global hint-off setting may have been selected while the override was active.
            val visible: Boolean =
                a != null &&
                    NavigationPolicy.restoreVisible(
                        state.nativeVisible,
                        RecentsBar.mode(a),
                        Global.getInt(
                            a.getContentResolver(),
                            "navigation_bar_gesture_hint",
                            -1,
                        ),
                    )
            if (visible) controller.show(Type.navigationBars())
            else controller.hide(Type.navigationBars())
        } finally {
            ownRequest!!.remove()
        }
        HookEntry.log("RECENTS_BAR restored native request")
    }

    private class State(a: Activity?, visible: Boolean, behavior: Int) {
        @JvmField val activity: WeakReference<Activity?>
        @JvmField var nativeVisible: Boolean
        @JvmField var nativeBehavior: Int
        @JvmField var active: Boolean = false

        init {
            activity = WeakReference<Activity?>(a)
            nativeVisible = visible
            nativeBehavior = behavior
        }
    }

    companion object {
        @JvmStatic
        private fun mode(a: Activity): Int {
            val r = a.getResources()
            val id = r.getIdentifier("config_navBarInteractionMode", "integer", "android")
            return if (id == 0) -1 else r.getInteger(id)
        }
    }
}
