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
    "NewApi"
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
    private var active: RecentsFocusServer.Active? = null
    private var armedMode: Int = 0
    private var installed: Boolean = false
    private var broken: Boolean = false
    private var state: String = "disarmed"

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

    private val endpoint: Binder =
        object : Binder() {
            override fun onTransact(code: Int, `in`: Parcel, out: Parcel?, flags: Int): Boolean {
                if (code < RecentsFocusWire.STATUS || code > RecentsFocusWire.STOP) return false
                try {
                    val uid = Binder.getCallingUid()
                    val app =
                        RecentsFocusServer.requireUid(
                            context,
                            uid,
                            code == RecentsFocusWire.ARM || code == RecentsFocusWire.STOP,
                        )
                    if (out == null || (flags and IBinder.FLAG_ONEWAY) != 0)
                        throw SecurityException("Synchronous requests only")
                    if (code == RecentsFocusWire.BEGIN || code == RecentsFocusWire.END) {
                        if (app) throw SecurityException("Launcher only")
                    }
                    `in`.enforceInterface(RecentsFocusWire.DESCRIPTOR)
                    var data = `in`.readBundle(RecentsFocusServer::class.java.getClassLoader())
                    `in`.enforceNoDataAvail()
                    if (data == null) data = Bundle()
                    val identity = Binder.clearCallingIdentity()
                    var reply: Bundle? = null
                    try {
                        synchronized(lock) {
                            var accepted: Boolean = false
                            when (code) {
                                RecentsFocusWire.ARM -> {
                                    restore("mode changed")
                                    armedMode =
                                        if (broken) 0
                                        else RecentsFocusPolicy.mode(data.getInt("mode", 0))
                                    if (!broken && armedMode != 0) {
                                        installActiveHooks()
                                        state = "ready"
                                    } else if ("restore-error" != state)
                                        state = if (broken) "error" else "disarmed"
                                }
                                RecentsFocusWire.STOP -> {
                                    armedMode = 0
                                    restore("disabled")
                                    if (!broken) state = "disarmed"
                                }
                                RecentsFocusWire.BEGIN -> accepted = begin(data)
                                RecentsFocusWire.END -> {
                                    if (active != null && active!!.owner == data.getBinder("owner"))
                                        restore("gesture ended")
                                }
                                else -> {}
                            }
                            reply = reply(accepted)
                        }
                    } finally {
                        Binder.restoreCallingIdentity(identity)
                    }
                    out.writeNoException()
                    out.writeBundle(reply)
                } catch (error: SecurityException) {
                    if (out != null) out.writeException(error)
                } catch (error: Throwable) {
                    synchronized(lock) {
                        fail(error)
                    }
                    if (out != null) {
                        out.writeNoException()
                        out.writeBundle(reply(false))
                    }
                }
                return true
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
        appThread = Class.forName("android.app.IApplicationThread", false, loader)
        coreItem =
            Class.forName("android.app.servertransaction.CoreStatesChangeItem", false, loader)
        transactionType =
            Class.forName("android.app.servertransaction.ClientTransaction", false, loader)
        val item =
            Class.forName("android.app.servertransaction.ClientTransactionItem", false, loader)
        itemConstructor = coreItem.getConstructor(Bundle::class.java)
        transactionConstructor = transactionType.getConstructor(appThread)
        stateField = (RecentsFocusServer.field(coreItem, "mState"))!!
        getItems = transactionType.getMethod("getTransactionItems")
        getClient = transactionType.getMethod("getClient")
        addItem = transactionType.getMethod("addTransactionItem", item)
        schedule = lifecycle.javaClass.getMethod("scheduleTransaction", transactionType)
        // Resolve all contracts before accepting any policy change.
        core.javaClass.getMethod("getCoreStates", Int::class.javaPrimitiveType)
        root.javaClass.getMethod(
            "anyTaskForId",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            ActivityOptions::class.java,
            Boolean::class.javaPrimitiveType,
        )
        val task = Class.forName("com.android.server.wm.Task", false, loader)
        task.getMethod("topRunningActivityLocked")
        task.getMethod("getDisplayId")
        task.getMethod("getWindowingMode")
        task.getMethod("getActivityType")
        val record = Class.forName("com.android.server.wm.ActivityRecord", false, loader)
        RecentsFocusServer.field(record, "app")
        RecentsFocusServer.field(record, "mActivityComponent")
        val process = Class.forName("com.android.server.wm.WindowProcessController", false, loader)
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
        reply.putString("state", state)
        reply.putBoolean("accepted", accepted)
        reply.putInt("mode", armedMode)
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
                                    chain!!.getArgs().toTypedArray()
                                )
                            } finally {
                                undo(changes)
                            }
                        }
                    },
                )
            )
            val wms = RecentsFocusServer.get(atm, "mWindowManager")
            val focus =
                wms!!
                    .javaClass
                    .getMethod(
                        "updateFocusedWindowLocked",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    )
            hooks.add(
                ModernHook.hookMethod(
                    focus,
                    object : ModernHook.Callback() {
                        override fun afterHookedMethod(p: ModernHook.Param) {
                            synchronized(lock) {
                                if (active == null) return
                                try {
                                    if (!validForeground(active!!)) restore("left owned recents")
                                } catch (error: Throwable) {
                                    fail(error)
                                }
                            }
                        }
                    },
                )
            )
            val recordType = Class.forName("com.android.server.wm.ActivityRecord", false, loader)
            val stateType =
                Class.forName("com.android.server.wm.ActivityRecord\$State", false, loader)
            val setState = recordType.getMethod("setState", stateType, String::class.java)
            hooks.add(
                ModernHook.hookMethod(
                    setState,
                    object : ModernHook.Callback() {
                        override fun afterHookedMethod(p: ModernHook.Param) {
                            synchronized(lock) {
                                if (active == null || p.thisObject !== active!!.record) return
                                if ("RESUMED" != (p.args[0] as Enum<*>).name)
                                    restore("source lifecycle changed")
                            }
                        }
                    },
                )
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
                .getMethod(
                    "anyTaskForId",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    ActivityOptions::class.java,
                    Boolean::class.javaPrimitiveType,
                )
                .invoke(root, data.getInt("task", -1), 0, null, false)
        if (task == null) return false
        val record = task.javaClass.getMethod("topRunningActivityLocked").invoke(task)
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
                }
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
                .getMethod("getDisplayContent", Int::class.javaPrimitiveType)
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
            core.javaClass.getMethod("getCoreStates", Int::class.javaPrimitiveType).invoke(core, 0)
                as Bundle?
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
        if (remaining <= 0) return
        handler.postDelayed(
            Runnable {
                synchronized(lock) {
                    if (!old.process.isBinderAlive() || active != null) return@synchronized
                    try {
                        send(old.thread, states())
                        state = "error"
                    } catch (error: Throwable) {
                        HookEntry.log("RECENTS_FOCUS_RESTORE_RETRY_FAILED " + remaining)
                        retryRestore(old, remaining - 1)
                    }
                }
            },
            1000,
        )
    }

    private fun unlink(old: RecentsFocusServer.Active) {
        if (old.timeout != null) handler.removeCallbacks(old!!.timeout!!)
        if (old.died != null)
            for (binder in List.of<IBinder?>(old.owner, old.anchor, old.process)) try {
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
        private var instance: RecentsFocusServer? = null

        @JvmStatic
        @Throws(Exception::class)
        fun install(loader: ClassLoader) {
            if (
                !HookCompatibility.supportsRecentsFocus(Build.VERSION.SDK_INT, Build.FINGERPRINT) ||
                    Process.myUid() != 1000
            )
                return
            val type =
                Class.forName("com.android.server.wm.ActivityTaskManagerService", false, loader)
            val transact =
                type.getDeclaredMethod(
                    "onTransact",
                    Int::class.javaPrimitiveType,
                    Parcel::class.java,
                    Parcel::class.java,
                    Int::class.javaPrimitiveType,
                )
            ModernHook.findAndHookMethod(
                type,
                transact.getName(),
                Int::class.javaPrimitiveType,
                Parcel::class.java,
                Parcel::class.java,
                Int::class.javaPrimitiveType,
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        if (p.args[0] as Int? != RecentsFocusWire.DISCOVER) return
                        val `in`: Parcel? = p.args[1] as Parcel?
                        val out: Parcel? = p.args[2] as Parcel?
                        // Never turn a module protocol/compatibility failure into a system_server
                        // exception.
                        try {
                            val context: Context? =
                                RecentsFocusServer.get(p.thisObject!!, "mContext") as Context?
                            RecentsFocusServer.requireUid(context!!, Binder.getCallingUid(), false)
                            if (out == null || (p.args[3] as Int and IBinder.FLAG_ONEWAY) != 0)
                                throw SecurityException("Synchronous discovery only")
                            `in`!!.enforceInterface(RecentsFocusWire.DESCRIPTOR)
                            `in`!!.readBundle(RecentsFocusServer::class.java.getClassLoader())
                            `in`!!.enforceNoDataAvail()
                            synchronized(RecentsFocusServer::class.java) {
                                if (RecentsFocusServer.instance == null)
                                    RecentsFocusServer.instance =
                                        RecentsFocusServer(p.thisObject!!, loader)
                            }
                            val reply = RecentsFocusServer.instance!!.reply(false)
                            reply.putBinder("backend", RecentsFocusServer.instance!!.endpoint)
                            out.writeNoException()
                            out.writeBundle(reply)
                        } catch (error: Throwable) {
                            if (out != null)
                                out.writeException(SecurityException("Focus discovery unavailable"))
                            HookEntry.log(
                                ("RECENTS_FOCUS_DISCOVERY_REJECTED " +
                                    error.javaClass.getSimpleName() +
                                    ": " +
                                    error.message +
                                    " flags=" +
                                    p.args[3])
                            )
                        }
                        p.setResult(true)
                    }
                },
            )
            HookEntry.log("RECENTS_FOCUS_SERVER_DISCOVERY_READY build=" + BuildConfig.VERSION_CODE)
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
            return owner.javaClass.getMethod(method).invoke(owner) as Int
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
                    val field = current.getDeclaredField(name)
                    field.setAccessible(true)
                    return field
                } catch (ignored: NoSuchFieldException) {}
                current = current.getSuperclass()
            }
            throw NoSuchFieldException(type.getName() + "." + name)
        }
    }
}
