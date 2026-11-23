package dev.ryuya.oneuihometweaks

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.IInterface
import android.os.Parcel
import android.os.Process
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import dev.ryuya.oneuihometweaks.compat.compatibleMethod
import dev.ryuya.oneuihometweaks.compat.compatibleName
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.Boolean as JavaBoolean
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.List
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Experimental per-process Samsung core-state delivery. No app-process hooks or global setters. */
@SuppressLint(
    "NewApi",
) // Discovery is gated to independently reviewed, exact firmware fingerprints.
class RecentsFocusServer private constructor(atm: Any, loader: ClassLoader) {
    private val atm: Any
    private val lock: Any
    private val lifecycle: Any
    private val core: Any
    private val root: Any
    private val handler: Handler
    private val context: Context
    private val loader: ClassLoader
    private val coreItem: Class<*>
    private val appThread: Class<*>
    private val transactionType: Class<*>
    private val itemConstructor: Constructor<*>
    private val transactionConstructor: Constructor<*>
    private val stateField: Field
    private val getItems: Method
    private val getClient: Method
    private val addItem: Method
    private val schedule: Method
    private val policy: RecentsFocusPolicy = RecentsFocusPolicy()
    private val hooks: MutableList<ModernHook.Handle?> = ArrayList<ModernHook.Handle?>()
    @Volatile private var active: RecentsFocusServer.Active? = null
    private var armedMode: Int = 0
    private var installed: Boolean = false
    private var broken: Boolean = false
    private var state: String = "disarmed"
    private var legacyEndpointExported = false
    private var restorePending = false

    private class Active(
        owner: IBinder,
        anchor: IBinder,
        process: IBinder,
        thread: Any,
        record: Any,
        task: Any,
    ) {
        @JvmField val owner: IBinder
        @JvmField val anchor: IBinder
        @JvmField val process: IBinder
        @JvmField val thread: Any
        @JvmField val record: Any
        @JvmField val task: Any
        @JvmField var died: DeathRecipient? = null
        @JvmField var timeout: Runnable? = null

        init {
            this.owner = owner
            this.anchor = anchor
            this.process = process
            this.thread = thread
            this.record = record
            this.task = task
        }
    }

    private val endpoint: Binder by lazy {
        object : Binder() {
            override fun onTransact(code: Int, input: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code !in RecentsFocusWire.STATUS..RecentsFocusWire.KEEP_LAUNCHER_SCREENS) return false
                try {
                    requireRequest(context, reply, flags)
                    requireUid(context, Binder.getCallingUid(), false)
                    transact(code, readRequest(input), requireNotNull(reply))
                } catch (error: Exception) {
                    reply?.writeException(error)
                }
                return true
            }
        }
    }

    private fun transact(code: Int, data: Bundle, output: Parcel) {
        require(code in RecentsFocusWire.STATUS..RecentsFocusWire.KEEP_LAUNCHER_SCREENS)
        val app = requireUid(context, Binder.getCallingUid(),
            code == RecentsFocusWire.ARM || code == RecentsFocusWire.STOP || code == RecentsFocusWire.KEEP_LAUNCHER_SCREENS)
        if (app && (code == RecentsFocusWire.BEGIN || code == RecentsFocusWire.END)) {
            throw SecurityException("Launcher only")
        }
        val identity = Binder.clearCallingIdentity()
        val response = try {
            synchronized(lock) {
                val accepted = try {
                    when (code) {
                        RecentsFocusWire.ARM -> {
                            restore("mode changed")
                            armedMode = if (broken) 0
                                else RecentsFocusPolicy.mode(data.getInt("mode", 0))
                            if (!broken && armedMode != 0) {
                                installActiveHooks()
                                state = "ready"
                            } else if (state != "restore-error") {
                                state = if (broken) "error" else "disarmed"
                            }
                            false
                        }
                        RecentsFocusWire.STOP -> {
                            armedMode = 0
                            restore("disabled")
                            if (!broken) state = "disarmed"
                            false
                        }
                        RecentsFocusWire.KEEP_LAUNCHER_SCREENS -> {
                            if (StockLauncherActivityRetention.available) {
                                StockLauncherActivityRetention.policy = StockLauncherActivityRetention.Policy(
                                    data.getBoolean(StockLauncherActivityRetention.HOME_KEY, false),
                                    data.getBoolean(StockLauncherActivityRetention.RECENTS_KEY, false),
                                )
                            }
                            false
                        }
                        RecentsFocusWire.BEGIN -> begin(data)
                        RecentsFocusWire.END -> {
                            if (active?.owner == data.getBinder("owner")) restore("gesture ended")
                            false
                        }
                        else -> false
                    }
                } catch (error: Throwable) {
                    fail(error)
                    false
                }
                reply(accepted)
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        output.writeNoException()
        output.writeBundle(response)
    }

    private fun discover(useTaskManager: Boolean): Bundle = synchronized(lock) {
        reply(false).apply {
            putBoolean(RecentsFocusWire.TASK_MANAGER, useTaskManager)
            if (!useTaskManager) {
                legacyEndpointExported = true
                putBinder("backend", endpoint)
            }
        }
    }

    private fun handOff(save: (Array<*>) -> Unit): Boolean = synchronized(lock) {
        if (active != null || restorePending || state == "restore-error" || legacyEndpointExported) return false
        SystemUiHookScopes.tryRetireIf {
            if (BuildConfig.STOCK_WIDGET_BROADCAST_WORKER && StockWidgetBroadcastHooks.hasPending()) {
                return@tryRetireIf false
            }
            val retention = StockLauncherActivityRetention.policy
            save(arrayOf(loader, atm, armedMode, broken, state, retention.home, retention.recents))
            true
        }
    }

    init {
        this.atm = atm
        this.loader = loader
        context = (RecentsFocusServer.get(atm, "mContext") as Context?)!!
        lock = (RecentsFocusServer.get(atm, "mGlobalLock"))!!
        handler = (RecentsFocusServer.get(atm, "mH") as Handler?)!!
        lifecycle = (RecentsFocusServer.get(atm, "mLifecycleManager"))!!
        root = (RecentsFocusServer.get(atm, "mRootWindowContainer"))!!
        core =
            (RecentsFocusServer.get(
                RecentsFocusServer.get(atm, "mExt")!!,
                "mCoreStateController",
            ))!!
        appThread = DexSymbols.findClass("android.app.IApplicationThread", false, loader)
        coreItem =
            DexSymbols.findClass(
                "android.app.servertransaction.CoreStatesChangeItem",
                false,
                loader,
            )
        transactionType =
            DexSymbols.findClass("android.app.servertransaction.ClientTransaction", false, loader)
        val item =
            DexSymbols.findClass(
                "android.app.servertransaction.ClientTransactionItem",
                false,
                loader,
            )
        itemConstructor = coreItem.getConstructor(Bundle::class.java)
        transactionConstructor = transactionType.getConstructor(appThread)
        stateField = (RecentsFocusServer.field(coreItem, "mState"))!!
        getItems = transactionType.compatibleMethod("getTransactionItems")
        getClient = transactionType.compatibleMethod("getClient")
        addItem = transactionType.compatibleMethod("addTransactionItem", item)
        schedule = lifecycle.javaClass.compatibleMethod("scheduleTransaction", transactionType)
        // Resolve all contracts before accepting any policy change.
        core.javaClass.compatibleMethod("getCoreStates", Int::class.javaPrimitiveType)
        root.javaClass.compatibleMethod(
            "anyTaskForId",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            ActivityOptions::class.java,
            Boolean::class.javaPrimitiveType,
        )
        val task = DexSymbols.findClass("com.android.server.wm.Task", false, loader)
        task.compatibleMethod("topRunningActivityLocked")
        task.compatibleMethod("getDisplayId")
        task.compatibleMethod("getWindowingMode")
        task.compatibleMethod("getActivityType")
        val record = DexSymbols.findClass("com.android.server.wm.ActivityRecord", false, loader)
        RecentsFocusServer.field(record, "app")
        RecentsFocusServer.field(record, "mActivityComponent")
        val process =
            DexSymbols.findClass("com.android.server.wm.WindowProcessController", false, loader)
        for (name in
            java.util.List.of<String?>(
                "mUid",
                "mUserId",
                "mThread",
                "mActivities",
            )) RecentsFocusServer.field(process, name)
        RecentsFocusServer.field(record, "mVisibleRequested")
    }

    private fun reply(accepted: Boolean): Bundle {
        val reply = Bundle()
        reply.putInt("build", BuildConfig.VERSION_CODE)
        reply.putInt("protocol", RecentsFocusCompatibility.PROTOCOL_VERSION)
        reply.putString("state", state)
        reply.putBoolean("accepted", accepted)
        reply.putInt("mode", armedMode)
        if (StockLauncherActivityRetention.available) {
            val retention = StockLauncherActivityRetention.policy
            reply.putBoolean(StockLauncherActivityRetention.HOME_KEY, retention.home)
            reply.putBoolean(StockLauncherActivityRetention.RECENTS_KEY, retention.recents)
        }
        return reply
    }

    @Throws(Exception::class)
    private fun installActiveHooks() {
        if (installed) return
        try {
            hooks.add(
                ModernHook.aroundMethod(
                    schedule,
                    ModernHook.Around { chain: Chain? ->
                        // Only core-state transactions need WM serialization. Other lifecycle
                        // traffic stays
                        // native.
                        var containsCore: Boolean = false
                        try {
                            for (item in
                                getItems.invoke(chain!!.getArgs().get(0)) as MutableList<*>) if (
                                coreItem.isInstance(item)
                            ) {
                                containsCore = true
                                break
                            }
                        } catch (error: Throwable) {
                            synchronized(lock) {
                                fail(error)
                            }
                        }
                        if (!containsCore)
                            return@Around chain!!.proceed(chain!!.getArgs().toTypedArray())
                        synchronized(lock) {
                            val changes = ArrayList<Array<Any?>?>()
                            if (active != null) {
                                try {
                                    val transaction = chain!!.getArgs().get(0)
                                    val client =
                                        (getClient.invoke(transaction) as IInterface).asBinder()
                                    if (client == active!!.process) {
                                        val lease = policy.current()
                                        if (lease != null)
                                            for (item in
                                                getItems.invoke(transaction) as MutableList<*>) {
                                                if (!coreItem.isInstance(item)) continue
                                                val original: Bundle? =
                                                    stateField.get(item) as Bundle?
                                                val patched = Bundle(original)
                                                patched.putInt(
                                                    RecentsFocusServer.KEY,
                                                    if (lease.mode == RecentsFocusPolicy.PRESERVE) 1
                                                    else 0,
                                                )
                                                changes.add(arrayOf<Any?>(item, original))
                                                stateField.set(item, patched)
                                            }
                                    }
                                } catch (error: Throwable) {
                                    undo(changes)
                                    changes.clear()
                                    fail(error)
                                }
                            }
                            try {
                                return@synchronized chain!!.proceed(
                                    chain!!.getArgs().toTypedArray(),
                                )
                            } finally {
                                undo(changes)
                            }
                        }
                    },
                ),
            )
            val wms = RecentsFocusServer.get(atm, "mWindowManager")
            val focus =
                wms!!
                    .javaClass
                    .compatibleMethod(
                        "updateFocusedWindowLocked",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    )
            hooks.add(
                ModernHook.hookMethod(
                    focus,
                    object : ModernHook.Callback() {
                        override fun afterHookedMethod(p: ModernHook.Param) {
                            if (active == null) return
                            synchronized(lock) {
                                val owned = active ?: return
                                try {
                                    if (!validForeground(owned)) restore("left owned recents")
                                } catch (error: Throwable) {
                                    fail(error)
                                }
                            }
                        }
                    },
                ),
            )
            val recordType =
                DexSymbols.findClass("com.android.server.wm.ActivityRecord", false, loader)
            val stateType =
                DexSymbols.findClass("com.android.server.wm.ActivityRecord\$State", false, loader)
            val setState = recordType.compatibleMethod("setState", stateType, String::class.java)
            hooks.add(
                ModernHook.hookMethod(
                    setState,
                    object : ModernHook.Callback() {
                        override fun afterHookedMethod(p: ModernHook.Param) {
                            val observed = active ?: return
                            if (p.thisObject !== observed.record || (p.args[0] as Enum<*>).name == "RESUMED") return
                            synchronized(lock) {
                                if (active === observed) restore("source lifecycle changed")
                            }
                        }
                    },
                ),
            )
            installed = true
        } catch (error: Throwable) {
            for (hook in hooks) try {
                hook!!.unhook()
            } catch (ignored: Throwable) {}
            hooks.clear()
            throw IllegalStateException("Focus contract unavailable", error)
        }
    }

    @Throws(Exception::class)
    private fun begin(data: Bundle): Boolean {
        if (broken || !installed || armedMode == 0 || data.getInt("mode") != armedMode) return false
        val owner = data.getBinder("owner")
        val anchor = data.getBinder("anchor")
        if (owner == null || anchor == null || !owner.isBinderAlive() || !anchor.isBinderAlive())
            return false
        val task =
            root.javaClass
                .compatibleMethod(
                    "anyTaskForId",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    ActivityOptions::class.java,
                    Boolean::class.javaPrimitiveType,
                )
                .invoke(root, data.getInt("task", -1), 0, null, false)
        if (task == null) return false
        val record = task.javaClass.compatibleMethod("topRunningActivityLocked").invoke(task)
        if (record == null) return false
        val process = RecentsFocusServer.get(record, "app")
        if (process == null) return false
        val uid: Int = RecentsFocusServer.get(process, "mUid") as Int
        val target =
            RecentsFocusPolicy.Target(
                data.getInt("task"),
                uid,
                RecentsFocusServer.get(process, "mUserId") as Int,
                RecentsFocusServer.number(task, "getDisplayId"),
                RecentsFocusServer.number(task, "getWindowingMode"),
                RecentsFocusServer.number(task, "getActivityType"),
            )
        if (!target.eligible()) return false
        if (
            !RecentsFocusPolicy.canOverride(
                armedMode,
                states()!!.getInt(RecentsFocusServer.KEY, 0) == 1,
            )
        ) {
            state = "preserve-unavailable"
            return false
        }
        for (other in RecentsFocusServer.get(process, "mActivities") as MutableList<*>) if (
            other !== record && RecentsFocusServer.get(other!!, "mVisibleRequested") as Boolean
        )
            return false
        val thread = RecentsFocusServer.get(process, "mThread")
        if (thread !is IInterface) return false
        val next = RecentsFocusServer.Active(owner, anchor, thread.asBinder(), thread, record, task)
        if (!validForeground(next)) return false
        restore("new gesture")
        // A failed restoration disarms the backend. Do not install a new owner that would
        // hide the failure or prevent the previous owner's bounded restoration retries.
        if (broken || armedMode == 0) return false
        next.died = DeathRecipient {
            handler.post(
                Runnable {
                    synchronized(lock) {
                        if (active == next) restore("owner/provider/source died")
                    }
                },
            )
        }
        try {
            owner.linkToDeath(next.died!!, 0)
            anchor.linkToDeath(next.died!!, 0)
            next.process.linkToDeath(next.died!!, 0)
            policy.begin(owner, target, armedMode)
            active = next
            // One-shot failsafe for a lost lifecycle signal, not a polling loop.
            next.timeout = Runnable {
                synchronized(lock) {
                    if (active == next) restore("ten minute lease limit")
                }
            }
            handler.postDelayed(next.timeout!!, 600000)
            send(thread, states())
            state = "state-dispatched"
            HookEntry.log("RECENTS_FOCUS_DISPATCH task=" + target.task + " mode=" + armedMode)
            return true
        } catch (error: Throwable) {
            if (active == next) restore("begin failed") else unlink(next)
            throw IllegalStateException("Focus state dispatch failed", error)
        }
    }

    @Throws(Exception::class)
    private fun validForeground(owned: RecentsFocusServer.Active): Boolean {
        if (
            RecentsFocusServer.number(owned.task, "getWindowingMode") != 1 ||
                RecentsFocusServer.number(owned.task, "getDisplayId") != 0
        )
            return false
        val display =
            root.javaClass
                .compatibleMethod("getDisplayContent", Int::class.javaPrimitiveType)
                .invoke(root, 0)
        if (display == null) return false
        val window = RecentsFocusServer.get(display, "mCurrentFocus")
        if (window == null) return false
        val record = RecentsFocusServer.get(window, "mActivityRecord")
        if (record === owned.record) return true // Arming during the outgoing gesture.

        if (record == null) return false
        val component: ComponentName? =
            RecentsFocusServer.get(record, "mActivityComponent") as ComponentName?
        return component != null &&
            HookEntry.LAUNCHER == component.getPackageName() &&
            "com.android.quickstep.RecentsActivity" == component.getClassName()
    }

    @Throws(Exception::class)
    private fun states(): Bundle? {
        return Bundle(
            core.javaClass
                .compatibleMethod("getCoreStates", Int::class.javaPrimitiveType)
                .invoke(core, 0) as Bundle?,
        )
    }

    @Throws(Exception::class)
    private fun send(thread: Any?, bundle: Bundle?) {
        val transaction = transactionConstructor.newInstance(thread)
        addItem.invoke(transaction, itemConstructor.newInstance(bundle))
        check(JavaBoolean.TRUE == schedule.invoke(lifecycle, transaction)) {
            "Native transaction rejected"
        }
    }

    private fun restore(reason: String?) {
        val old: RecentsFocusServer.Active? = active
        if (old == null) return
        active = null
        policy.end(old.owner)
        unlink(old)
        try {
            if (old.process.isBinderAlive()) send(old.thread, states())
            state = if (broken) "error" else if (armedMode == 0) "disarmed" else "ready"
            HookEntry.log("RECENTS_FOCUS_RESTORE " + reason)
        } catch (error: Throwable) {
            broken = true
            armedMode = 0
            state = "restore-error"
            HookEntry.log("RECENTS_FOCUS_RESTORE_ERROR " + error.javaClass.getSimpleName())
            retryRestore(old, 3)
        }
    }

    private fun retryRestore(old: RecentsFocusServer.Active, remaining: Int) {
        if (remaining <= 0) {
            restorePending = false
            return
        }
        restorePending = true
        val posted = handler.postDelayed({
            synchronized(lock) {
                restorePending = false
                if (!old.process.isBinderAlive()) {
                    state = "error"
                    return@synchronized
                }
                if (active != null) return@synchronized
                try {
                    send(old.thread, states())
                    state = "error"
                } catch (error: Throwable) {
                    HookEntry.log("RECENTS_FOCUS_RESTORE_RETRY_FAILED " + remaining)
                    retryRestore(old, remaining - 1)
                }
            }
        }, 1000)
        if (!posted) restorePending = false
    }

    private fun unlink(old: RecentsFocusServer.Active) {
        if (old.timeout != null) handler.removeCallbacks(old!!.timeout!!)
        if (old.died != null)
            for (binder in listOfNotNull(old.owner, old.anchor, old.process)) try {
                binder.unlinkToDeath(old!!.died!!, 0)
            } catch (ignored: Throwable) {}
    }

    private fun fail(error: Throwable) {
        broken = true
        armedMode = 0
        restore("backend error")
        if ("restore-error" != state) state = "error"
        HookEntry.log("RECENTS_FOCUS_DISABLED " + error.javaClass.getSimpleName())
    }

    private fun undo(changes: MutableList<Array<Any?>?>) {
        for (change in changes) try {
            stateField.set(change!![0], change!![1])
        } catch (error: Throwable) {
            fail(error)
        }
    }

    companion object {
        private const val KEY: String = "stay_focus_activity"
        @Volatile private var instance: RecentsFocusServer? = null
        @Volatile private var installedLoader: ClassLoader? = null
        @Volatile private var recovery: Array<*>? = null

        fun retire(save: (Array<*>) -> Unit): Boolean {
            val current = instance
            if (current != null) return current.handOff(save)
            val loader = installedLoader
            if (loader != null) return SystemUiHookScopes.tryRetireIf {
                // A first discovery may have initialized a session before the lock was acquired.
                if (instance != null ||
                    (BuildConfig.STOCK_WIDGET_BROADCAST_WORKER && StockWidgetBroadcastHooks.hasPending())
                ) false
                else {
                    save(arrayOf(loader))
                    true
                }
            }
            val retry = recovery ?: return false
            save(retry)
            recovery = null
            return true
        }

        fun resume(saved: Array<*>) {
            val loader = requireNotNull(saved.getOrNull(0) as? ClassLoader)
            recovery = saved
            check(install(loader, saved)) { "Focus backend is unavailable on this build" }
            recovery = null
        }

        fun install(loader: ClassLoader, previous: Array<*> = emptyArray<Any>()): Boolean {
            if (!HookCompatibility.supportsRecentsFocus(Build.VERSION.SDK_INT, Build.FINGERPRINT) ||
                Process.myUid() != 1000) return false
            val scope = SystemUiHookScope { HookEntry.log("RECENTS_FOCUS_SCOPE $it") }
            try {
                SystemUiHookScopes.bind(scope) {
                    StockLauncherActivityRetention.install(loader)
                    if (StockLauncherActivityRetention.available) {
                        StockLauncherActivityRetention.policy = StockLauncherActivityRetention.Policy(
                            previous.getOrNull(5) == true,
                            previous.getOrNull(6) == true,
                        )
                    }
                    val atm = previous.getOrNull(1)
                    if (atm != null) {
                        val backend = RecentsFocusServer(atm, loader)
                        backend.armedMode = previous[2] as Int
                        backend.broken = previous[3] as Boolean
                        backend.state = previous[4] as String
                        if (backend.armedMode != 0 && !backend.broken) backend.installActiveHooks()
                        instance = backend
                    }
                    installDispatch(loader)
                    if (BuildConfig.STOCK_BATTERY_PASS_THROUGH_LOCK) StockBatteryLockHooks.install(loader)
                    if (BuildConfig.STOCK_WIDGET_SIZE_SAVE_BATCH) StockWidgetSaveHooks.install(loader)
                    if (BuildConfig.STOCK_WIDGET_BROADCAST_WORKER) StockWidgetBroadcastHooks.install(loader)
                }
                check(SystemUiHookScopes.trySwitch(scope) { it?.close() })
            } catch (error: Throwable) {
                instance = null
                scope.close()
                throw error
            }
            installedLoader = loader
            HookEntry.log("RECENTS_FOCUS_SERVER_DISCOVERY_READY build=" + BuildConfig.VERSION_CODE)
            return true
        }

        private fun installDispatch(loader: ClassLoader) {
            val type = DexSymbols.findClass("com.android.server.wm.ActivityTaskManagerService", false, loader)
            val transact = type.compatibleDeclaredMethod("onTransact", Int::class.javaPrimitiveType,
                Parcel::class.java, Parcel::class.java, Int::class.javaPrimitiveType)
            ModernHook.hookMethod(transact, object : ModernHook.Callback() {
                override fun beforeHookedMethod(p: ModernHook.Param) {
                    if (p.args[0] != RecentsFocusWire.DISCOVER) return
                    val output = p.args[2] as? Parcel
                    try {
                        val atm = requireNotNull(p.thisObject)
                        val context = get(atm, "mContext") as Context
                        requireRequest(context, output, p.args[3] as Int)
                        requireUid(context, Binder.getCallingUid(), false)
                        val data = readRequest(p.args[1] as Parcel)
                        val operation = data.getInt(RecentsFocusWire.OPERATION, 0)
                        val taskManager = data.getBoolean(RecentsFocusWire.TASK_MANAGER)
                        if (operation != 0 && !taskManager)
                            throw SecurityException("Unsupported focus transport")

                        val backend = synchronized(RecentsFocusServer::class.java) {
                            instance ?: RecentsFocusServer(atm, loader).also { instance = it }
                        }
                        if (taskManager && operation != 0) {
                            backend.transact(operation, data, requireNotNull(output))
                        } else {
                            val response = backend.discover(taskManager)
                            output!!.writeNoException()
                            output.writeBundle(response)
                        }
                    } catch (error: Throwable) {
                        output?.writeException(SecurityException("Focus service unavailable"))
                        HookEntry.log("RECENTS_FOCUS_REQUEST_REJECTED " + error.javaClass.simpleName)
                    }
                    p.setResult(true)
                }
            })
        }

        private fun requireRequest(context: Context, reply: Parcel?, flags: Int) {
            if (!HostStartupGate.unlocked(context)) throw SecurityException("First owner unlock required")
            if (reply == null || flags and IBinder.FLAG_ONEWAY != 0)
                throw SecurityException("Synchronous requests only")
        }

        private fun readRequest(input: Parcel): Bundle {
            input.enforceInterface(RecentsFocusWire.DESCRIPTOR)
            return (input.readBundle(RecentsFocusServer::class.java.classLoader) ?: Bundle()).also {
                input.enforceNoDataAvail()
            }
        }

        @JvmStatic /* true: module app; false: Launcher. No root/shell/arbitrary app caller is accepted. */
        @Throws(Exception::class)
        private fun requireUid(context: Context, uid: Int, appOnly: Boolean): Boolean {
            val identity = Binder.clearCallingIdentity()
            try {
                val own =
                    context
                        .getPackageManager()
                        .getApplicationInfo(BuildConfig.APPLICATION_ID, 0)
                        .uid
                val launcher =
                    context.getPackageManager().getApplicationInfo(HookEntry.LAUNCHER, 0).uid
                if (RecentsFocusPolicy.allowedUid(uid, own, launcher, appOnly)) return uid == own
                throw SecurityException("Module app or Launcher required")
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun number(owner: Any, method: String?): Int {
            return owner.javaClass.compatibleMethod(method).invoke(owner) as Int
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun get(owner: Any, name: String?): Any? {
            return RecentsFocusServer.field(owner.javaClass, name)!!.get(owner)
        }

        @JvmStatic
        @Throws(NoSuchFieldException::class)
        private fun field(type: Class<*>, name: String?): Field? {
            var current: Class<*>? = type
            while (current != null) {
                try {
                    val field = current.compatibleDeclaredField(name)
                    field.setAccessible(true)
                    return field
                } catch (ignored: NoSuchFieldException) {}
                current = current.getSuperclass()
            }
            throw NoSuchFieldException(type.compatibleName() + "." + name)
        }
    }
}
