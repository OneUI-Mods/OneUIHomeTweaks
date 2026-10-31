package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.ActivityManager.RunningTaskInfo
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Message
import android.os.Process
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets.Type
import java.lang.Boolean as JavaBoolean
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.List
import java.util.WeakHashMap
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Owns Samsung's native live-tile lifetime without suppressing app lifecycle methods. */
class RecentsLiveTileHooks {
    private val focus: RecentsFocusClient = RecentsFocusClient()
    private val focusOwners: MutableMap<Any?, RecentsFocusClient.Lease?> =
        WeakHashMap<Any?, RecentsFocusClient.Lease?>()
    private var focusMode: Int = 0

    fun connectFocus(context: Context?) {
        focus.connect(context!!)
    }

    fun focusDiagnostic(): String? {
        return focus.diagnostic()
    }

    fun setFocusMode(mode: Int) {
        val next = RecentsFocusPolicy.mode(mode)
        if (focusMode != next) {
            for (owner in ArrayList<RecentsFocusClient.Lease?>(focusOwners.values)) focus.end(owner)
            focusOwners.clear()
            focusMode = next
        }
    }

    private val sessions: MutableMap<Any?, RecentsLiveTileHooks.Session?> =
        WeakHashMap<Any?, RecentsLiveTileHooks.Session?>()
    private val gestures: MutableMap<Any?, RecentsLiveTileHooks.Session?> =
        WeakHashMap<Any?, RecentsLiveTileHooks.Session?>()
    private val hooks: MutableList<ModernHook.Handle?> = ArrayList<ModernHook.Handle?>()
    private var callbacks: Field? = null
    private var action: Field? = null
    private var managerDisplay: Field? = null
    private var gestureDisplay: Field? = null
    private var topSource: Field? = null
    private var nativeHold: Field? = null
    private var willGo: Field? = null
    private var desktop: Method? = null
    private var gestureDesktop: Method? = null
    private var topTask: Method? = null
    private var taskConfig: Method? = null
    private var windowMode: Method? = null
    private var activityType: Method? = null
    private var setHold: Method? = null
    private var finish: Method? = null
    private var systemController: Method? = null
    private var recentTarget: Method? = null
    private var taskId: Method? = null
    private var taskScene: Method? = null
    private var handlerDisplay: Method? = null
    private var handlerKeyInjector: Method? = null
    private var injectKey: Method? = null
    private var mainKeyInjector: Any? = null
    private var handlerType: Class<*>? = null
    private var inputProxy: Field? = null
    private var proxyRecents: Field? = null
    private var proxyHome: Field? = null
    private var proxySize: Field? = null
    private var appPlayer: Field? = null
    private var taskViewType: Class<*>? = null
    private var surface: RecentsLiveSurface? = null
    private var gestureType: Class<*>? = null
    private var context: Context? = null
    @Volatile private var enabled: Boolean = false
    @Volatile private var failed: Boolean = false
    private var releasing: Boolean = false
    private var application: Application? = null
    private var recentsType: Class<*>? = null
    private var foregroundRecents: Activity? = null
    private val lifecycle: ActivityLifecycleCallbacks =
        object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {}

            override fun onActivityStarted(a: Activity) {}

            override fun onActivityResumed(a: Activity) {
                if (recentsType != null && recentsType!!.isInstance(a)) foregroundRecents = a
                if (
                    recentsType != null &&
                        !recentsType!!.isInstance(a) &&
                        sessions.values.stream().anyMatch { s: RecentsLiveTileHooks.Session? ->
                            s!!.held
                        }
                )
                    release("other activity resumed")
            }

            override fun onActivityPaused(a: Activity) {
                releaseActivity(a, "recents paused")
                if (foregroundRecents === a) foregroundRecents = null
            }

            override fun onActivityStopped(a: Activity) {
                releaseActivity(a, "recents stopped")
            }

            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}

            override fun onActivityDestroyed(a: Activity) {
                releaseActivity(a, "recents destroyed")
                if (foregroundRecents === a) foregroundRecents = null
            }
        }
    private var diagnostic: String = "unavailable"
    private var error: String? = null
    private var stateChanged: Runnable = Runnable {}

    fun setStateListener(callback: Runnable) {
        stateChanged = callback
    }

    private fun setDiagnostic(value: String?) {
        val next: String? = if (error == null) value else "error:" + error
        if (next != diagnostic) {
            diagnostic = (next)!!
            stateChanged.run()
        }
    }

    private val screenOff: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent) {
                if (Intent.ACTION_SCREEN_OFF == i.getAction()) release("screen off")
            }
        }

    private class Session(m: Any, g: Any, original: Boolean) {
        @JvmField val manager: Any
        @JvmField val gesture: Any
        @JvmField val originalHold: Boolean
        @JvmField var held: Boolean = false
        @JvmField var heldAt: Long = 0
        @JvmField var navigation: RecentsNavigationGesture? = null
        @JvmField var navigationInjector: Any? = null
        @JvmField var taskId: Int = 0
        @JvmField var boundRoot: View? = null

        init {
            manager = m
            gesture = g
            originalHold = original
        }

        fun manager(): Any {
            return manager
        }

        fun gesture(): Any {
            return gesture
        }

        fun originalHold(): Boolean {
            return originalHold
        }
    }

    @Throws(Exception::class)
    fun install(app: Context?, apk: String?, loader: ClassLoader?) {
        check(HookCompatibility.isCurrentSupported()) { "Live tile requires renewed OS validation" }
        val manager =
            RecentsLiveTileHooks.type(
                loader,
                "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager",
            )
        gestureType =
            RecentsLiveTileHooks.type(loader, "com.honeyspace.gesture.session.AnimationSession")
        callbacks =
            RecentsLiveTileHooks.field(manager!!, "onStartCallbacks", MutableList::class.java)
        action =
            RecentsLiveTileHooks.field(
                gestureType!!,
                "recentsAnimationAction",
                RecentsLiveTileHooks.type(
                    loader,
                    "com.honeyspace.gesture.recentsanimation.RecentsAnimationAction",
                ),
            )
        managerDisplay =
            RecentsLiveTileHooks.field(manager!!, "displayId", Int::class.javaPrimitiveType)
        gestureDisplay =
            RecentsLiveTileHooks.field(gestureType!!, "displayId", Int::class.javaPrimitiveType)
        val top = RecentsLiveTileHooks.type(loader, "com.honeyspace.gesture.usecase.TopTaskUseCase")
        topSource = RecentsLiveTileHooks.field(manager!!, "topTaskUseCase", top)
        val recentsCalls = HookCompatibility.recentsCalls(Build.VERSION.SDK_INT)
        topTask =
            method(
                top!!,
                recentsCalls!!.topTaskMethod,
                RunningTaskInfo::class.java,
                *if (recentsCalls!!.topTaskTakesDisplay)
                    arrayOf<Class<*>?>(Int::class.javaPrimitiveType)
                else arrayOfNulls<Class<*>>(0),
            )
        val window = RecentsLiveTileHooks.type(loader, "android.app.WindowConfiguration")
        taskConfig =
            RecentsLiveTileHooks.method(
                RecentsLiveTileHooks.type(
                    loader,
                    "com.honeyspace.common.reflection.TaskInfoReflectionKt",
                )!!,
                "getWindowConfiguration",
                window,
                RecentsLiveTileHooks.type(loader, "android.app.TaskInfo"),
            )
        windowMode =
            RecentsLiveTileHooks.method(window!!, "getWindowingMode", Int::class.javaPrimitiveType)
        activityType =
            RecentsLiveTileHooks.method(window!!, "getActivityType", Int::class.javaPrimitiveType)
        desktop =
            RecentsLiveTileHooks.method(
                manager!!,
                "isInDexDisplay",
                Boolean::class.javaPrimitiveType,
            )
        gestureDesktop =
            RecentsLiveTileHooks.method(
                gestureType!!,
                "isInDexDisplay",
                Boolean::class.javaPrimitiveType,
            )
        nativeHold =
            RecentsLiveTileHooks.field(
                gestureType!!,
                "dontFinishByCommand",
                Boolean::class.javaPrimitiveType,
            )
        willGo =
            RecentsLiveTileHooks.field(
                gestureType!!,
                "willGoToRecents",
                Boolean::class.javaPrimitiveType,
            )
        setHold =
            RecentsLiveTileHooks.method(
                gestureType!!,
                "setDontFinishByCommand",
                Void.TYPE,
                Boolean::class.javaPrimitiveType,
            )
        finish = RecentsLiveTileHooks.method(manager!!, "finish", Void.TYPE)
        val controllerType =
            RecentsLiveTileHooks.type(loader, "com.honeyspace.sdk.HoneySystemController")
        systemController =
            RecentsLiveTileHooks.method(manager!!, "getSystemController", controllerType)
        recentTarget = controllerType!!.getMethod("getCurrentRecentTarget")
        check(recentTarget!!.getReturnType() == View::class.java) { "Recent target changed" }
        val proxy =
            RecentsLiveTileHooks.type(
                loader,
                "com.honeyspace.gesture.inputconsumer.InputConsumerProxy",
            )
        inputProxy = RecentsLiveTileHooks.field(manager!!, "inputConsumerProxy", proxy)
        proxyRecents =
            RecentsLiveTileHooks.field(proxy!!, "sendRecents", Boolean::class.javaPrimitiveType)
        proxyHome =
            RecentsLiveTileHooks.field(proxy!!, "sendLauncher", Boolean::class.javaPrimitiveType)
        proxySize = RecentsLiveTileHooks.field(proxy!!, "displaySize", Point::class.java)
        handlerType =
            RecentsLiveTileHooks.type(loader, "com.honeyspace.gesture.GestureInputHandler")
        handlerDisplay =
            RecentsLiveTileHooks.method(handlerType!!, "getDisplayId", Int::class.javaPrimitiveType)
        val nativeInput =
            RecentsLiveTileHooks.method(
                handlerType!!,
                "inputHandle",
                Void.TYPE,
                Int::class.javaPrimitiveType,
                MotionEvent::class.java,
            )
        val injectorType =
            RecentsLiveTileHooks.type(loader, "com.honeyspace.gesture.keyinject.KeyInjector")
        handlerKeyInjector =
            RecentsLiveTileHooks.method(handlerType!!, "getKeyInjector", injectorType)
        injectKey =
            RecentsLiveTileHooks.method(
                injectorType!!,
                "injectKey",
                Void.TYPE,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        val input =
            RecentsLiveTileHooks.method(
                proxy!!,
                "sendToInputProxy",
                Boolean::class.javaPrimitiveType,
                InputEvent::class.java,
            )
        appPlayer =
            RecentsLiveTileHooks.field(
                manager!!,
                "appPlayer",
                RecentsLiveTileHooks.type(loader, "com.honeyspace.transition.anim.floating.Player"),
            )
        taskViewType =
            RecentsLiveTileHooks.type(
                loader,
                "com.honeyspace.ui.honeypots.tasklist.presentation.TaskView",
            )
        taskId = taskViewType!!.getMethod("getFirstTaskId")
        taskScene = taskViewType!!.getMethod("getTaskSceneView")
        val dispatch =
            taskViewType!!
                .getMethod("getRecentsView")
                .getReturnType()
                .getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        dispatch.setAccessible(true)
        val begin =
            RecentsLiveTileHooks.method(
                manager!!,
                "start",
                Void.TYPE,
                Int::class.javaPrimitiveType,
                Point::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
        val decision =
            RecentsLiveTileHooks.method(
                manager!!,
                "needRecentsActivityCall",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
        val request =
            RecentsLiveTileHooks.method(
                gestureType!!,
                "requestFinishRecentAnimation",
                Void.TYPE,
                *if (recentsCalls!!.finishTakesToHome)
                    arrayOf<Class<*>?>(Boolean::class.javaPrimitiveType)
                else arrayOfNulls<Class<*>>(0),
            )
        val finishController =
            RecentsLiveTileHooks.method(
                manager!!,
                "finishController",
                Void.TYPE,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
        val gestureLaunch =
            RecentsLiveTileHooks.method(
                RecentsLiveTileHooks.type(
                    loader,
                    "com.honeyspace.gesture.utils.LaunchTaskHelper",
                )!!,
                "launchTask",
                MutableList::class.java,
                RecentsLiveTileHooks.type(
                    loader,
                    "com.honeyspace.sdk.transition.ShellTransition\$TaskInfo",
                ),
            )
        val invalidate = RecentsLiveTileHooks.method(manager!!, "invalidate", Void.TYPE)
        val shell =
            RecentsLiveTileHooks.type(loader, "com.honeyspace.transition.ShellTransitionManager")
        val launchInfo =
            RecentsLiveTileHooks.type(loader, "com.honeyspace.sdk.transition.ShellTransition\$Info")
        val launch =
            RecentsLiveTileHooks.method(
                shell!!,
                "getActivityLaunchOptions",
                RecentsLiveTileHooks.type(
                    loader,
                    "com.honeyspace.transition.utils.ActivityOptionsWrapper",
                ),
                launchInfo,
                RecentsLiveTileHooks.type(loader, "com.honeyspace.transition.ShellAnimationRunner"),
            )
        val launchView = launchInfo!!.getMethod("getTargetView")
        val launchRequest =
            RecentsLiveTileHooks.method(
                shell!!,
                "getLaunchOptions",
                RecentsLiveTileHooks.type(
                    loader,
                    "com.honeyspace.transition.utils.ActivityOptionsWrapper",
                ),
                launchInfo,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                RecentsLiveTileHooks.type(loader, "kotlin.jvm.functions.Function0"),
            )
        val recents = RecentsLiveTileHooks.type(loader, "com.android.quickstep.RecentsActivity")
        val back = recents!!.getMethod("onBackPressed")
        check(back.getReturnType() == Void.TYPE) { "Back contract changed" }
        recentsType = recents
        val taskMessages =
            RecentsLiveTileHooks.type(
                loader,
                "com.android.systemui.shared.system.TaskStackChangeListeners\$Impl",
            )
        val taskMessage =
            RecentsLiveTileHooks.method(
                taskMessages!!,
                "handleMessage",
                Boolean::class.javaPrimitiveType,
                Message::class.java,
            )
        val movedToFront =
            RecentsLiveTileHooks.field(
                    taskMessages!!,
                    "ON_TASK_MOVED_TO_FRONT",
                    Int::class.javaPrimitiveType,
                )!!
                .getInt(null)
        val movedDisplay = RunningTaskInfo::class.java.getField("displayId")
        DexEvidence.deoptimizeRecentsLiveCalls(
            apk,
            loader,
            List.of<Method?>(
                begin,
                decision,
                request,
                invalidate,
                setHold,
                finish,
                finishController,
                gestureLaunch,
                launch,
                launchRequest,
                input,
                taskMessage,
                nativeInput,
            ),
        )
        try {
            surface = RecentsLiveSurface(loader, Runnable { fallback("unsupported live surface") })
            hook(
                nativeInput,
                false,
                RecentsLiveTileHooks.Action { p: ModernHook.Param? ->
                    if (
                        p!!.args[0] as Int? == 0 &&
                            ((p!!.args[1] as MotionEvent).getActionMasked() ==
                                MotionEvent.ACTION_DOWN) &&
                            handlerDisplay!!.invoke(p!!.thisObject) as Int? == 0
                    )
                        mainKeyInjector = handlerKeyInjector!!.invoke(p!!.thisObject)
                },
            )
            hook(
                taskMessage,
                false,
                RecentsLiveTileHooks.Action { p: ModernHook.Param? ->
                    val message: Message? = p!!.args[0] as Message?
                    val obj = message?.obj as? RunningTaskInfo
                    if (
                        message!!.what != movedToFront ||
                            (obj == null) ||
                            movedDisplay.getInt(obj) != 0
                    )
                        return@Action
                    for (s in ArrayList<RecentsLiveTileHooks.Session?>(sessions.values)) {
                        val activity: Activity? = foregroundRecents
                        if (
                            s!!.held &&
                                message!!.getWhen() >= s!!.heldAt &&
                                activity != null &&
                                obj.taskId != activity.getTaskId()
                        ) {
                            release("foreground task changed")
                            break
                        }
                    }
                },
            )
            hook(
                input,
                false,
                RecentsLiveTileHooks.Action { p: ModernHook.Param? ->
                    val key = p!!.args[0] as? KeyEvent
                    if (key == null || key.getKeyCode() != KeyEvent.KEYCODE_BACK) return@Action
                    for (s in ArrayList<RecentsLiveTileHooks.Session?>(sessions.values)) {
                        val activity: Activity? = foregroundRecents
                        if (
                            !s!!.held ||
                                inputProxy!!.get(s!!.manager()) !== p!!.thisObject ||
                                activity == null ||
                                activity.getWindow().getDecorView() !== s!!.boundRoot
                        )
                            continue
                        p!!.setResult(true)
                        if (key.getAction() == KeyEvent.ACTION_UP && !key.isCanceled()) {
                            s!!
                                .boundRoot!!
                                .post(
                                    Runnable {
                                        if (
                                            sessions.get(s!!.manager()) != s ||
                                                foregroundRecents !== activity
                                        )
                                            return@Runnable
                                        release("back key")
                                        try {
                                            back.invoke(activity)
                                        } catch (error: Throwable) {
                                            HookEntry.log("RECENTS_LIVE_BACK_ERROR " + error)
                                        }
                                    }
                                )
                        }
                        break
                    }
                },
            )
            hook(
                begin,
                false,
                RecentsLiveTileHooks.Action { p: ModernHook.Param? -> this.begin(p!!) },
            )
            hooks.add(
                ModernHook.hookMethod(
                    begin!!,
                    object : ModernHook.Callback() {
                        override fun afterHookedMethod(p: ModernHook.Param) {
                            if (p.hasThrowable()) focus.end(focusOwners.remove(p.thisObject))
                        }
                    },
                )
            )
            hooks.add(
                ModernHook.hookMethod(
                    dispatch,
                    object : ModernHook.Callback() {
                        override fun beforeHookedMethod(p: ModernHook.Param) {
                            if (!enabled || failed || Looper.myLooper() != Looper.getMainLooper())
                                return
                            val v: View? = p.thisObject as View?
                            for (ss in
                                ArrayList<RecentsLiveTileHooks.Session?>(sessions.values)) if (
                                ss!!.held && v!!.getRootView() === ss!!.boundRoot
                            ) {
                                try {
                                    val original: MotionEvent? = p.args[0] as MotionEvent?
                                    var copy = RecentsLocalMotion.rebase(v!!, original!!)
                                    val navigation =
                                        navigation(
                                            ss!!,
                                            v!!,
                                            if (copy == null) original else copy!!,
                                        )
                                    if (navigation!!.consume) p.setResult(true)
                                    if (navigation!!.command != 0) {
                                        if (!navigation!!.consume) {
                                            if (copy == null) copy = MotionEvent.obtain(original)
                                            copy.setAction(MotionEvent.ACTION_CANCEL)
                                        }
                                        p.setObjectExtra("liveNavigationHome", ss)
                                        p.setObjectExtra("liveNavigationKey", navigation!!.command)
                                    }
                                    if (copy != null) {
                                        p.setObjectExtra("liveRawEvent", copy)
                                        p.args[0] = copy
                                        if (copy.getActionMasked() == MotionEvent.ACTION_DOWN)
                                            HookEntry.log(
                                                ("RECENTS_LIVE_INPUT_REBASED local=" +
                                                    copy.getX() +
                                                    "," +
                                                    copy.getY() +
                                                    " raw=" +
                                                    copy.getRawX() +
                                                    "," +
                                                    copy.getRawY())
                                            )
                                    }
                                } catch (error: Throwable) {
                                    HookEntry.log("RECENTS_LIVE_INPUT_ERROR " + error)
                                    release("input axes changed")
                                }
                                break
                            }
                        }

                        override fun afterHookedMethod(p: ModernHook.Param) {
                            run {
                                val s =
                                    p.getObjectExtra("liveNavigationHome")
                                        as? RecentsLiveTileHooks.Session
                                if (s != null && sessions.get(s.manager()) == s && s.held) {
                                    s.boundRoot!!.post(
                                        Runnable {
                                            if (sessions.get(s.manager()) != s || !s.held)
                                                return@Runnable
                                            val injector = s.navigationInjector
                                            val key: Int =
                                                p.getObjectExtra("liveNavigationKey") as Int
                                            val activity: Activity? = foregroundRecents
                                            release(
                                                if (key == KeyEvent.KEYCODE_HOME)
                                                    "native navigation home"
                                                else "native navigation back"
                                            )
                                            try {
                                                if (
                                                    key == KeyEvent.KEYCODE_BACK && activity != null
                                                )
                                                    back.invoke(activity)
                                                else
                                                    injectKey!!.invoke(
                                                        injector,
                                                        key,
                                                        Process.myUid() / 100000,
                                                        0,
                                                    )
                                            } catch (error: Throwable) {
                                                HookEntry.log(
                                                    "RECENTS_LIVE_NAVIGATION_ERROR " + error
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                            val copy = p.getObjectExtra("liveRawEvent")
                            if (copy is MotionEvent) {
                                if (
                                    BuildConfig.RECENTS_LIVE_TRACE &&
                                        copy.getActionMasked() == MotionEvent.ACTION_UP
                                ) {
                                    val view: View? = p.thisObject as View?
                                    for (ss in
                                        ArrayList<RecentsLiveTileHooks.Session?>(
                                            sessions.values
                                        )) if (ss!!.held && view!!.getRootView() === ss!!.boundRoot)
                                        view!!.postDelayed(
                                            Runnable {
                                                if (sessions.get(ss!!.manager()) == ss)
                                                    try {
                                                        inspect(ss!!)
                                                    } catch (error: Throwable) {
                                                        HookEntry.log(
                                                            "RECENTS_LIVE_INSPECT_ERROR " + error
                                                        )
                                                    }
                                            },
                                            750,
                                        )
                                }
                                copy.recycle()
                            }
                        }
                    },
                )
            )
            hook(
                decision,
                true,
                RecentsLiveTileHooks.Action { p: ModernHook.Param? ->
                    if (sessions.containsKey(p!!.thisObject) && JavaBoolean.TRUE != p!!.args[0])
                        p!!.setResult(true)
                },
            )
            // Return/cancel decisions must restore focus policy before native finish returns focus.
            hook(
                request,
                false,
                RecentsLiveTileHooks.Action { p: ModernHook.Param? ->
                    val s = gestures.get(p!!.thisObject)
                    if (s != null && !willGo!!.getBoolean(s.gesture()))
                        focus.end(focusOwners.remove(s.manager()))
                },
            )
            hook(
                finish,
                false,
                RecentsLiveTileHooks.Action { p: ModernHook.Param? ->
                    focus.end(focusOwners.remove(p!!.thisObject))
                },
            )
            // Native onGestureEnd/onSettledOnTarget may bypass both public finish entry points.
            // This shared gate runs before finishGestureTransition can return focus to the app.
            hook(
                finishController,
                false,
                RecentsLiveTileHooks.Action { p: ModernHook.Param? ->
                    focus.end(focusOwners.remove(p!!.thisObject))
                },
            )
            // Home Up's gesture page can relaunch the task before any animation-finish callback.
            // Restore only our focus leases before its coroutine dispatches the native task launch.
            hook(
                gestureLaunch,
                false,
                RecentsLiveTileHooks.Action { p: ModernHook.Param? ->
                    for (owner in ArrayList<RecentsFocusClient.Lease?>(focusOwners.values)) focus
                        .end(owner)
                    focusOwners.clear()
                },
            )
            hook(
                request,
                true,
                RecentsLiveTileHooks.Action { p: ModernHook.Param? ->
                    val s = gestures.get(p!!.thisObject)
                    if (
                        s != null &&
                            willGo!!.getBoolean(s.gesture()) &&
                            nativeHold!!.getBoolean(s.gesture())
                    ) {
                        if (s.held) return@Action
                        setDiagnostic("held")
                        s.held = true
                        s.heldAt = SystemClock.uptimeMillis()
                        s.boundRoot =
                            recentTarget!!.invoke(systemController!!.invoke(s.manager())) as View?
                        HookEntry.log("RECENTS_LIVE_HELD native controller retained")
                        activateSurface(s)
                    }
                },
            )
            // Suggested-app icons enter getLaunchOptions before getActivityLaunchOptions. Its
            // native
            // transitionRunning gate otherwise waits for the very controller we retained, so the
            // old
            // later hook never executes and multiple taps queue launches until another exit
            // releases it.
            hook(
                launchRequest,
                false,
                RecentsLiveTileHooks.Action { p: ModernHook.Param? ->
                    releaseForLaunch(launchView, p!!.args[0], "recents app launch")
                },
            )
            // Task cards also have direct getActivityLaunchOptions callers; retain their completion
            // path.
            hook(
                launch,
                false,
                RecentsLiveTileHooks.Action { p: ModernHook.Param? ->
                    releaseForLaunch(launchView, p!!.args[0], "recents task launch")
                },
            )
            // Restore native focus retention before invalidate can return focus to the source
            // Activity.
            // Restoring afterwards misses DecorView.mStayFocus initialization on a cancelled
            // gesture.
            hook(
                invalidate,
                false,
                RecentsLiveTileHooks.Action { p: ModernHook.Param? ->
                    focus.end(focusOwners.remove(p!!.thisObject))
                },
            )
            hook(
                invalidate,
                true,
                RecentsLiveTileHooks.Action { p: ModernHook.Param? ->
                    end(sessions.remove(p!!.thisObject))
                },
            )
            // initialize() can run before ActivityThread publishes mInitialApplication. The
            // supplied
            // Application context is already attached; getApplicationContext() can still return
            // null.
            context = app
            check(app is Application) { "Attached Application required" }
            application = app
            application!!.registerActivityLifecycleCallbacks(lifecycle)
            context!!.registerReceiver(
                screenOff,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_NOT_EXPORTED,
            )
            setDiagnostic("off")
            HookEntry.log("RECENTS_LIVE_READY native orientation lifecycle 0.11")
        } catch (t: Throwable) {
            uninstall()
            if (t is Exception) throw t
            throw IllegalStateException(t)
        }
    }

    @Throws(Exception::class)
    private fun releaseForLaunch(targetView: Method, info: Any?, reason: String?) {
        val target: View? = targetView.invoke(info) as View?
        if (target == null) return
        val root = target.getRootView()
        for (s in ArrayList<RecentsLiveTileHooks.Session?>(sessions.values)) if (
            s!!.held && s!!.boundRoot != null && root === s!!.boundRoot
        ) {
            release(reason)
            return
        }
    }

    @Throws(Exception::class)
    private fun activateSurface(s: RecentsLiveTileHooks.Session) {
        if (BuildConfig.RECENTS_LIVE_TRACE) inspect(s)
        val root: View? = recentTarget!!.invoke(systemController!!.invoke(s.manager())) as View?
        if (root != null) {
            val bottom = RecentsLiveTileHooks.navigationBottom(root)
            if (bottom <= 0 || bottom >= root.getHeight() * .1f) {
                fallback("native navigation region unavailable")
                return
            }
            val windowInsets = root.getRootWindowInsets()
            val edges = windowInsets.getInsetsIgnoringVisibility(Type.systemGestures())
            HookEntry.log("RECENTS_LIVE_NAVIGATION_REGIONS " + edges + " bottom=" + bottom)
            try {
                s.navigation =
                    RecentsNavigationGesture(
                        root.getWidth(),
                        root.getHeight(),
                        edges.left,
                        edges.right,
                        bottom,
                        ViewConfiguration.get(root.getContext()).getScaledTouchSlop(),
                    )
            } catch (unsupported: IllegalArgumentException) {
                fallback("native back region unavailable")
                return
            }
            s.navigationInjector = mainKeyInjector
            if (s.navigationInjector == null) {
                fallback("native home navigation unavailable")
                return
            }
        }
        if (!surface!!.bind(s.manager(), root, s.taskId)) fallback("unsupported live binding")
        else s.boundRoot = root
    }

    @Throws(Exception::class)
    private fun navigation(
        s: RecentsLiveTileHooks.Session,
        view: View,
        event: MotionEvent,
    ): RecentsNavigationGesture.Decision? {
        if (s.navigation == null) return RecentsNavigationGesture.NONE
        val xy = floatArrayOf(event.getX(), event.getY())
        val matrix = Matrix()
        view.transformMatrixToGlobal(matrix)
        matrix.mapPoints(xy)
        return s!!
            .navigation!!
            .event(
                event.getDownTime(),
                event.getActionMasked(),
                event.getPointerCount(),
                xy[0],
                xy[1],
            )
    }

    private fun releaseActivity(a: Activity, reason: String?) {
        if (a.getWindow() == null) return
        val root = a.getWindow().getDecorView()
        for (s in ArrayList<RecentsLiveTileHooks.Session?>(sessions.values)) if (
            s!!.held && s!!.boundRoot === root
        ) {
            release(reason)
            return
        }
    }

    @Throws(Exception::class)
    private fun begin(p: ModernHook.Param) {
        if (
            !NavigationPolicy.gestures(context!!) ||
                JavaBoolean.TRUE == p.args[2] ||
                JavaBoolean.TRUE == p.args[3] ||
                JavaBoolean.TRUE == p.args[4] ||
                managerDisplay!!.getInt(p.thisObject) != 0 ||
                JavaBoolean.TRUE == desktop!!.invoke(p.thisObject) ||
                sessions.containsKey(p.thisObject)
        )
            return
        val source = topSource!!.get(p.thisObject)
        val task: Any? =
            if (topTask!!.getParameterCount() == 0) topTask!!.invoke(source)
            else topTask!!.invoke(source, managerDisplay!!.getInt(p.thisObject))
        if (
            task == null ||
                task.javaClass.getField("taskId").getInt(task) != p.args[0] as Int? ||
                task.javaClass.getField("displayId").getInt(task) != 0 ||
                task.javaClass.getField("userId").getInt(task) != Process.myUid() / 100000
        )
            return
        val config = taskConfig!!.invoke(null, task)
        if (
            config == null ||
                windowMode!!.invoke(config) as Int? != 1 ||
                activityType!!.invoke(config) as Int? != 1
        )
            return
        var owner: Any? = null
        for (cb in callbacks!!.get(p.thisObject) as MutableList<*>) for (f in
            cb!!.javaClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue
            f.setAccessible(true)
            val value = f.get(cb)
            if (gestureType!!.isInstance(value) && action!!.get(value) === p.thisObject) {
                check(!(owner != null && owner !== value)) { "Ambiguous live gesture owner" }
                owner = value
            }
        }
        if (
            owner == null ||
                gestureDisplay!!.getInt(owner) != 0 ||
                JavaBoolean.TRUE == gestureDesktop!!.invoke(owner)
        )
            return
        val s = RecentsLiveTileHooks.Session(p.thisObject!!, owner, nativeHold!!.getBoolean(owner))
        s.taskId = (p.args[0] as Int?)!!
        if (focusMode != 0) {
            val ownerToken = focus.begin(s.taskId, focusMode)
            if (ownerToken != null) focusOwners.put(p.thisObject, ownerToken)
        }
        sessions.put(p.thisObject, s)
        gestures.put(owner, s)
        setHold!!.invoke(owner, true)
        setDiagnostic("gesture")
        HookEntry.log("RECENTS_LIVE_BEGIN native transient recents")
    }

    @Throws(Exception::class)
    private fun inspect(s: RecentsLiveTileHooks.Session) {
        val proxy = inputProxy!!.get(s.manager())
        val root: View? = recentTarget!!.invoke(systemController!!.invoke(s.manager())) as View?
        HookEntry.log(
            ("RECENTS_LIVE_STATE recentsInput=" +
                proxyRecents!!.getBoolean(proxy) +
                " homeInput=" +
                proxyHome!!.getBoolean(proxy) +
                " inputSize=" +
                proxySize!!.get(proxy) +
                " player=" +
                (appPlayer!!.get(s.manager()) != null) +
                " root=" +
                (if (root == null) "null" else root.getWidth().toString() + "x" + root.getHeight()))
        )
        if (root != null) inspectCards(root, 0)
    }

    @Throws(Exception::class)
    private fun inspectCards(v: View, depth: Int) {
        if (depth > 32) return
        if (taskViewType!!.isInstance(v)) {
            val scene: View? = taskScene!!.invoke(v) as View?
            val xy = IntArray(2)
            if (scene != null) scene.getLocationOnScreen(xy)
            var metadata: String = ""
            if (scene != null) {
                val data: MutableList<*>? =
                    scene.javaClass.getMethod("getTaskSceneData").invoke(scene) as MutableList<*>?
                for (datum in data!!) {
                    val bitmap = datum!!.javaClass.getMethod("getThumbnail").invoke(datum)
                    metadata +=
                        (" snapshot=" +
                            (if (bitmap is Bitmap)
                                bitmap.getWidth().toString() + "x" + bitmap.getHeight()
                            else "none") +
                            " rotation=" +
                            datum!!.javaClass.getMethod("getRotation").invoke(datum) +
                            " real=" +
                            datum!!.javaClass.getMethod("isRealSnapshot").invoke(datum) +
                            " locked=" +
                            datum!!.javaClass.getMethod("getAppLocked").invoke(datum))
                }
                metadata +=
                    (" contextRotation=" +
                        scene.getContext().getDisplay().getRotation() +
                        " rootRotation=" +
                        scene.getRootView().getDisplay().getRotation())
            }
            HookEntry.log(
                ("RECENTS_LIVE_CARD id=" +
                    taskId!!.invoke(v) +
                    " visible=" +
                    v.getVisibility() +
                    " alpha=" +
                    v.getAlpha() +
                    " scene=" +
                    (if (scene == null) "null"
                    else
                        xy[0].toString() +
                            "," +
                            xy[1] +
                            " " +
                            scene.getWidth() +
                            "x" +
                            scene.getHeight()) +
                    metadata)
            )
            return
        }
        if (v is ViewGroup)
            for (i in 0 until v.getChildCount()) inspectCards(v.getChildAt(i), depth + 1)
    }

    @Throws(Exception::class)
    private fun end(s: RecentsLiveTileHooks.Session?) {
        if (s == null) return
        focus.end(focusOwners.remove(s.manager()))
        if (surface != null) surface!!.clear()
        gestures.remove(s.gesture())
        setHold!!.invoke(s.gesture(), s.originalHold())
        setDiagnostic(if (enabled) "ready" else "off")
        HookEntry.log("RECENTS_LIVE_END native invalidation")
    }

    private fun release(reason: String?) {
        if (releasing) return
        if (sessions.isEmpty()) {
            setDiagnostic(if (enabled) "ready" else "off")
            return
        }
        for (owner in ArrayList<RecentsFocusClient.Lease?>(focusOwners.values)) focus.end(owner)
        focusOwners.clear()
        releasing = true
        if (surface != null) surface!!.clear()
        try {
            for (s in ArrayList<RecentsLiveTileHooks.Session?>(sessions.values)) try {
                setHold!!.invoke(s!!.gesture(), s!!.originalHold())
                finish!!.invoke(s!!.manager())
            } catch (t: Throwable) {
                HookEntry.log("RECENTS_LIVE_RELEASE_ERROR " + t)
            }
            sessions.clear()
            gestures.clear()
            setDiagnostic(if (enabled) "ready" else "off")
            HookEntry.log("RECENTS_LIVE_RELEASE " + reason)
        } finally {
            releasing = false
        }
    }

    private fun fallback(reason: String?) {
        release(reason)
        setDiagnostic("unavailable:" + reason)
    }

    fun setEnabled(value: Boolean) {
        if (hooks.isEmpty()) {
            enabled = false
            setDiagnostic("unavailable")
            return
        }
        val next: Boolean = value && !failed
        if (next == enabled) return
        enabled = next
        if (!enabled) release("disabled") else if (sessions.isEmpty()) setDiagnostic("ready")
    }

    fun diagnostic(): String {
        return diagnostic
    }

    fun uninstall() {
        enabled = false
        release("uninstall")
        for (h in hooks) try {
            h!!.unhook()
        } catch (ignored: Throwable) {}
        hooks.clear()
        if (surface != null) {
            surface!!.uninstall()
            surface = null
        }
        if (context != null)
            try {
                context!!.unregisterReceiver(screenOff)
            } catch (ignored: Throwable) {}
        context = null
        if (application != null) {
            application!!.unregisterActivityLifecycleCallbacks(lifecycle)
            application = null
        }
        foregroundRecents = null
        mainKeyInjector = null
    }

    private fun interface Action {
        @Throws(Exception::class) fun run(p: ModernHook.Param?)
    }

    private fun hook(method: Method?, after: Boolean, action: RecentsLiveTileHooks.Action) {
        hooks.add(
            ModernHook.hookMethod(
                method!!,
                object : ModernHook.Callback() {
                    fun run(p: ModernHook.Param) {
                        if (
                            !enabled ||
                                failed ||
                                p.hasThrowable() ||
                                Looper.myLooper() != Looper.getMainLooper()
                        )
                            return
                        try {
                            action.run(p)
                        } catch (t: Throwable) {
                            failed = true
                            error = t.javaClass.getSimpleName()
                            uninstall()
                            setDiagnostic("error:" + t.javaClass.getSimpleName())
                            HookEntry.log("RECENTS_LIVE_DISABLED " + t)
                        }
                    }

                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        if (!after) run(p)
                    }

                    override fun afterHookedMethod(p: ModernHook.Param) {
                        if (after) run(p)
                    }
                },
            )
        )
    }

    companion object {
        @JvmStatic
        private fun navigationBottom(root: View): Int {
            val insets = root.getRootWindowInsets()
            return if (insets == null) 0
            else
                insets
                    .getInsetsIgnoringVisibility(
                        Type.navigationBars() or Type.mandatorySystemGestures()
                    )
                    .bottom
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun type(l: ClassLoader?, n: String?): Class<*>? {
            return Class.forName(n, false, l)
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun field(c: Class<*>, n: String?, t: Class<*>?): Field? {
            val f = c.getDeclaredField(n)
            check(f.getType() == t) { "Live field changed: " + n }
            f.setAccessible(true)
            return f
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun method(c: Class<*>, n: String?, t: Class<*>?, vararg p: Class<*>?): Method? {
            val m = c.getDeclaredMethod(n, *p)
            check(m.getReturnType() == t) { "Live method changed: " + n }
            m.setAccessible(true)
            return m
        }
    }
}
