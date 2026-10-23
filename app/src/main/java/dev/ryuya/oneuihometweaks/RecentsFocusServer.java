package dev.ryuya.oneuihometweaks;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;

/** Experimental per-process Samsung core-state delivery. No app-process hooks or global setters. */
@android.annotation.SuppressLint(
    "NewApi") // Discovery is gated to independently reviewed, exact firmware fingerprints.
final class RecentsFocusServer {
  private static final String KEY = "stay_focus_activity";
  private static RecentsFocusServer instance;
  private final Object atm, lock, lifecycle, core, root;
  private final Handler handler;
  private final Context context;
  private final ClassLoader loader;
  private final Class<?> coreItem, appThread, transactionType;
  private final Constructor<?> itemConstructor, transactionConstructor;
  private final Field stateField;
  private final Method getItems, getClient, addItem, schedule;
  private final RecentsFocusPolicy policy = new RecentsFocusPolicy();
  private final List<ModernHook.Handle> hooks = new ArrayList<>();
  private Active active;
  private int armedMode;
  private boolean installed, broken;
  private String state = "disarmed";

  private static final class Active {
    final IBinder owner, anchor, process;
    final Object thread, record, task;
    IBinder.DeathRecipient died;
    Runnable timeout;

    Active(
        IBinder owner, IBinder anchor, IBinder process, Object thread, Object record, Object task) {
      this.owner = owner;
      this.anchor = anchor;
      this.process = process;
      this.thread = thread;
      this.record = record;
      this.task = task;
    }
  }

  static void install(ClassLoader loader) throws Exception {
    if (!HookCompatibility.supportsRecentsFocus(Build.VERSION.SDK_INT, Build.FINGERPRINT)
        || android.os.Process.myUid() != 1000) return;
    Class<?> type =
        Class.forName("com.android.server.wm.ActivityTaskManagerService", false, loader);
    Method transact =
        type.getDeclaredMethod("onTransact", int.class, Parcel.class, Parcel.class, int.class);
    ModernHook.findAndHookMethod(
        type,
        transact.getName(),
        int.class,
        Parcel.class,
        Parcel.class,
        int.class,
        new ModernHook.Callback() {
          @Override
          protected void beforeHookedMethod(ModernHook.Param p) {
            if ((Integer) p.args[0] != RecentsFocusWire.DISCOVER) return;
            Parcel in = (Parcel) p.args[1], out = (Parcel) p.args[2];
            // Never turn a module protocol/compatibility failure into a system_server exception.
            try {
              Context context = (Context) get(p.thisObject, "mContext");
              requireUid(context, Binder.getCallingUid(), false);
              if (out == null || ((Integer) p.args[3] & IBinder.FLAG_ONEWAY) != 0)
                throw new SecurityException("Synchronous discovery only");
              in.enforceInterface(RecentsFocusWire.DESCRIPTOR);
              in.readBundle(RecentsFocusServer.class.getClassLoader());
              in.enforceNoDataAvail();
              synchronized (RecentsFocusServer.class) {
                if (instance == null) instance = new RecentsFocusServer(p.thisObject, loader);
              }
              Bundle reply = instance.reply(false);
              reply.putBinder("backend", instance.endpoint);
              out.writeNoException();
              out.writeBundle(reply);
            } catch (Throwable error) {
              if (out != null)
                out.writeException(new SecurityException("Focus discovery unavailable"));
              HookEntry.log(
                  "RECENTS_FOCUS_DISCOVERY_REJECTED "
                      + error.getClass().getSimpleName()
                      + ": "
                      + error.getMessage()
                      + " flags="
                      + p.args[3]);
            }
            p.setResult(true);
          }
        });
    HookEntry.log("RECENTS_FOCUS_SERVER_DISCOVERY_READY build=" + BuildConfig.VERSION_CODE);
  }

  private RecentsFocusServer(Object atm, ClassLoader loader) throws Exception {
    this.atm = atm;
    this.loader = loader;
    context = (Context) get(atm, "mContext");
    lock = get(atm, "mGlobalLock");
    handler = (Handler) get(atm, "mH");
    lifecycle = get(atm, "mLifecycleManager");
    root = get(atm, "mRootWindowContainer");
    core = get(get(atm, "mExt"), "mCoreStateController");
    appThread = Class.forName("android.app.IApplicationThread", false, loader);
    coreItem = Class.forName("android.app.servertransaction.CoreStatesChangeItem", false, loader);
    transactionType =
        Class.forName("android.app.servertransaction.ClientTransaction", false, loader);
    Class<?> item =
        Class.forName("android.app.servertransaction.ClientTransactionItem", false, loader);
    itemConstructor = coreItem.getConstructor(Bundle.class);
    transactionConstructor = transactionType.getConstructor(appThread);
    stateField = field(coreItem, "mState");
    getItems = transactionType.getMethod("getTransactionItems");
    getClient = transactionType.getMethod("getClient");
    addItem = transactionType.getMethod("addTransactionItem", item);
    schedule = lifecycle.getClass().getMethod("scheduleTransaction", transactionType);
    // Resolve all contracts before accepting any policy change.
    core.getClass().getMethod("getCoreStates", int.class);
    root.getClass()
        .getMethod("anyTaskForId", int.class, int.class, ActivityOptions.class, boolean.class);
    Class<?> task = Class.forName("com.android.server.wm.Task", false, loader);
    task.getMethod("topRunningActivityLocked");
    task.getMethod("getDisplayId");
    task.getMethod("getWindowingMode");
    task.getMethod("getActivityType");
    Class<?> record = Class.forName("com.android.server.wm.ActivityRecord", false, loader);
    field(record, "app");
    field(record, "mActivityComponent");
    Class<?> process =
        Class.forName("com.android.server.wm.WindowProcessController", false, loader);
    for (String name : List.of("mUid", "mUserId", "mThread", "mActivities")) field(process, name);
    field(record, "mVisibleRequested");
  }

  private final Binder endpoint =
      new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel in, Parcel out, int flags) {
          if (code < RecentsFocusWire.STATUS || code > RecentsFocusWire.STOP) return false;
          try {
            int uid = Binder.getCallingUid();
            boolean app =
                requireUid(
                    context, uid, code == RecentsFocusWire.ARM || code == RecentsFocusWire.STOP);
            if (out == null || (flags & IBinder.FLAG_ONEWAY) != 0)
              throw new SecurityException("Synchronous requests only");
            if (code == RecentsFocusWire.BEGIN || code == RecentsFocusWire.END) {
              if (app) throw new SecurityException("Launcher only");
            }
            in.enforceInterface(RecentsFocusWire.DESCRIPTOR);
            Bundle data = in.readBundle(RecentsFocusServer.class.getClassLoader());
            in.enforceNoDataAvail();
            if (data == null) data = new Bundle();
            long identity = Binder.clearCallingIdentity();
            Bundle reply;
            try {
              synchronized (lock) {
                boolean accepted = false;
                switch (code) {
                  case RecentsFocusWire.ARM -> {
                    restore("mode changed");
                    armedMode = broken ? 0 : RecentsFocusPolicy.mode(data.getInt("mode", 0));
                    if (!broken && armedMode != 0) {
                      installActiveHooks();
                      state = "ready";
                    } else if (!"restore-error".equals(state))
                      state = broken ? "error" : "disarmed";
                  }
                  case RecentsFocusWire.STOP -> {
                    armedMode = 0;
                    restore("disabled");
                    if (!broken) state = "disarmed";
                  }
                  case RecentsFocusWire.BEGIN -> accepted = begin(data);
                  case RecentsFocusWire.END -> {
                    if (active != null && active.owner.equals(data.getBinder("owner")))
                      restore("gesture ended");
                  }
                  default -> {}
                }
                reply = reply(accepted);
              }
            } finally {
              Binder.restoreCallingIdentity(identity);
            }
            out.writeNoException();
            out.writeBundle(reply);
          } catch (SecurityException error) {
            if (out != null) out.writeException(error);
          } catch (Throwable error) {
            synchronized (lock) {
              fail(error);
            }
            if (out != null) {
              out.writeNoException();
              out.writeBundle(reply(false));
            }
          }
          return true;
        }
      };

  /** true: module app; false: Launcher. No root/shell/arbitrary app caller is accepted. */
  private static boolean requireUid(Context context, int uid, boolean appOnly) throws Exception {
    long identity = Binder.clearCallingIdentity();
    try {
      int own = context.getPackageManager().getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid;
      int launcher = context.getPackageManager().getApplicationInfo(HookEntry.LAUNCHER, 0).uid;
      if (RecentsFocusPolicy.allowedUid(uid, own, launcher, appOnly)) return uid == own;
      throw new SecurityException("Module app or Launcher required");
    } finally {
      Binder.restoreCallingIdentity(identity);
    }
  }

  private Bundle reply(boolean accepted) {
    Bundle reply = new Bundle();
    reply.putInt("build", BuildConfig.VERSION_CODE);
    reply.putString("state", state);
    reply.putBoolean("accepted", accepted);
    reply.putInt("mode", armedMode);
    return reply;
  }

  private void installActiveHooks() throws Exception {
    if (installed) return;
    try {
      hooks.add(
          ModernHook.aroundMethod(
              schedule,
              chain -> {
                // Only core-state transactions need WM serialization. Other lifecycle traffic stays
                // native.
                boolean containsCore = false;
                try {
                  for (Object item : (List<?>) getItems.invoke(chain.getArgs().get(0)))
                    if (coreItem.isInstance(item)) {
                      containsCore = true;
                      break;
                    }
                } catch (Throwable error) {
                  synchronized (lock) {
                    fail(error);
                  }
                }
                if (!containsCore) return chain.proceed(chain.getArgs().toArray());
                synchronized (lock) {
                  List<Object[]> changes = new ArrayList<>();
                  if (active != null) {
                    try {
                      Object transaction = chain.getArgs().get(0);
                      IBinder client = ((IInterface) getClient.invoke(transaction)).asBinder();
                      if (client.equals(active.process)) {
                        RecentsFocusPolicy.Lease lease = policy.current();
                        if (lease != null)
                          for (Object item : (List<?>) getItems.invoke(transaction)) {
                            if (!coreItem.isInstance(item)) continue;
                            Bundle original = (Bundle) stateField.get(item);
                            Bundle patched = new Bundle(original);
                            patched.putInt(
                                KEY, lease.mode() == RecentsFocusPolicy.PRESERVE ? 1 : 0);
                            changes.add(new Object[] {item, original});
                            stateField.set(item, patched);
                          }
                      }
                    } catch (Throwable error) {
                      undo(changes);
                      changes.clear();
                      fail(error);
                    }
                  }
                  try {
                    return chain.proceed(chain.getArgs().toArray());
                  } finally {
                    undo(changes);
                  }
                }
              }));
      Object wms = get(atm, "mWindowManager");
      Method focus =
          wms.getClass().getMethod("updateFocusedWindowLocked", int.class, boolean.class);
      hooks.add(
          ModernHook.hookMethod(
              focus,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  synchronized (lock) {
                    if (active == null) return;
                    try {
                      if (!validForeground(active)) restore("left owned recents");
                    } catch (Throwable error) {
                      fail(error);
                    }
                  }
                }
              }));
      Class<?> recordType = Class.forName("com.android.server.wm.ActivityRecord", false, loader);
      Class<?> stateType =
          Class.forName("com.android.server.wm.ActivityRecord$State", false, loader);
      Method setState = recordType.getMethod("setState", stateType, String.class);
      hooks.add(
          ModernHook.hookMethod(
              setState,
              new ModernHook.Callback() {
                @Override
                protected void afterHookedMethod(ModernHook.Param p) {
                  synchronized (lock) {
                    if (active == null || p.thisObject != active.record) return;
                    if (!"RESUMED".equals(((Enum<?>) p.args[0]).name()))
                      restore("source lifecycle changed");
                  }
                }
              }));
      installed = true;
    } catch (Throwable error) {
      for (ModernHook.Handle hook : hooks)
        try {
          hook.unhook();
        } catch (Throwable ignored) {
        }
      hooks.clear();
      throw new IllegalStateException("Focus contract unavailable", error);
    }
  }

  private boolean begin(Bundle data) throws Exception {
    if (broken || !installed || armedMode == 0 || data.getInt("mode") != armedMode) return false;
    IBinder owner = data.getBinder("owner"), anchor = data.getBinder("anchor");
    if (owner == null || anchor == null || !owner.isBinderAlive() || !anchor.isBinderAlive())
      return false;
    Object task =
        root.getClass()
            .getMethod("anyTaskForId", int.class, int.class, ActivityOptions.class, boolean.class)
            .invoke(root, data.getInt("task", -1), 0, null, false);
    if (task == null) return false;
    Object record = task.getClass().getMethod("topRunningActivityLocked").invoke(task);
    if (record == null) return false;
    Object process = get(record, "app");
    if (process == null) return false;
    int uid = (Integer) get(process, "mUid");
    RecentsFocusPolicy.Target target =
        new RecentsFocusPolicy.Target(
            data.getInt("task"),
            uid,
            (Integer) get(process, "mUserId"),
            number(task, "getDisplayId"),
            number(task, "getWindowingMode"),
            number(task, "getActivityType"));
    if (!target.eligible()) return false;
    if (!RecentsFocusPolicy.canOverride(armedMode, states().getInt(KEY, 0) == 1)) {
      state = "preserve-unavailable";
      return false;
    }
    for (Object other : (List<?>) get(process, "mActivities"))
      if (other != record && (Boolean) get(other, "mVisibleRequested")) return false;
    Object thread = get(process, "mThread");
    if (!(thread instanceof IInterface iface)) return false;
    Active next = new Active(owner, anchor, iface.asBinder(), thread, record, task);
    if (!validForeground(next)) return false;
    restore("new gesture");
    // A failed restoration disarms the backend. Do not install a new owner that would
    // hide the failure or prevent the previous owner's bounded restoration retries.
    if (broken || armedMode == 0) return false;
    next.died =
        () ->
            handler.post(
                () -> {
                  synchronized (lock) {
                    if (active == next) restore("owner/provider/source died");
                  }
                });
    try {
      owner.linkToDeath(next.died, 0);
      anchor.linkToDeath(next.died, 0);
      next.process.linkToDeath(next.died, 0);
      policy.begin(owner, target, armedMode);
      active = next;
      // One-shot failsafe for a lost lifecycle signal, not a polling loop.
      next.timeout =
          () -> {
            synchronized (lock) {
              if (active == next) restore("ten minute lease limit");
            }
          };
      handler.postDelayed(next.timeout, 600000);
      send(thread, states());
      state = "state-dispatched";
      HookEntry.log("RECENTS_FOCUS_DISPATCH task=" + target.task() + " mode=" + armedMode);
      return true;
    } catch (Throwable error) {
      if (active == next) restore("begin failed");
      else unlink(next);
      throw new IllegalStateException("Focus state dispatch failed", error);
    }
  }

  private boolean validForeground(Active owned) throws Exception {
    if (number(owned.task, "getWindowingMode") != 1 || number(owned.task, "getDisplayId") != 0)
      return false;
    Object display = root.getClass().getMethod("getDisplayContent", int.class).invoke(root, 0);
    if (display == null) return false;
    Object window = get(display, "mCurrentFocus");
    if (window == null) return false;
    Object record = get(window, "mActivityRecord");
    if (record == owned.record) return true; // Arming during the outgoing gesture.
    if (record == null) return false;
    ComponentName component = (ComponentName) get(record, "mActivityComponent");
    return component != null
        && HookEntry.LAUNCHER.equals(component.getPackageName())
        && "com.android.quickstep.RecentsActivity".equals(component.getClassName());
  }

  private Bundle states() throws Exception {
    return new Bundle(
        (Bundle) core.getClass().getMethod("getCoreStates", int.class).invoke(core, 0));
  }

  private void send(Object thread, Bundle bundle) throws Exception {
    Object transaction = transactionConstructor.newInstance(thread);
    addItem.invoke(transaction, itemConstructor.newInstance(bundle));
    if (!Boolean.TRUE.equals(schedule.invoke(lifecycle, transaction)))
      throw new IllegalStateException("Native transaction rejected");
  }

  private void restore(String reason) {
    Active old = active;
    if (old == null) return;
    active = null;
    policy.end(old.owner);
    unlink(old);
    try {
      if (old.process.isBinderAlive()) send(old.thread, states());
      state = broken ? "error" : armedMode == 0 ? "disarmed" : "ready";
      HookEntry.log("RECENTS_FOCUS_RESTORE " + reason);
    } catch (Throwable error) {
      broken = true;
      armedMode = 0;
      state = "restore-error";
      HookEntry.log("RECENTS_FOCUS_RESTORE_ERROR " + error.getClass().getSimpleName());
      retryRestore(old, 3);
    }
  }

  private void retryRestore(Active old, int remaining) {
    if (remaining <= 0) return;
    handler.postDelayed(
        () -> {
          synchronized (lock) {
            if (!old.process.isBinderAlive() || active != null) return;
            try {
              send(old.thread, states());
              state = "error";
            } catch (Throwable error) {
              HookEntry.log("RECENTS_FOCUS_RESTORE_RETRY_FAILED " + remaining);
              retryRestore(old, remaining - 1);
            }
          }
        },
        1000);
  }

  private void unlink(Active old) {
    if (old.timeout != null) handler.removeCallbacks(old.timeout);
    if (old.died != null)
      for (IBinder binder : List.of(old.owner, old.anchor, old.process))
        try {
          binder.unlinkToDeath(old.died, 0);
        } catch (Throwable ignored) {
        }
  }

  private void fail(Throwable error) {
    broken = true;
    armedMode = 0;
    restore("backend error");
    if (!"restore-error".equals(state)) state = "error";
    HookEntry.log("RECENTS_FOCUS_DISABLED " + error.getClass().getSimpleName());
  }

  private void undo(List<Object[]> changes) {
    for (Object[] change : changes)
      try {
        stateField.set(change[0], change[1]);
      } catch (Throwable error) {
        fail(error);
      }
  }

  private static int number(Object owner, String method) throws Exception {
    return (Integer) owner.getClass().getMethod(method).invoke(owner);
  }

  private static Object get(Object owner, String name) throws Exception {
    return field(owner.getClass(), name).get(owner);
  }

  private static Field field(Class<?> type, String name) throws NoSuchFieldException {
    for (Class<?> current = type; current != null; current = current.getSuperclass())
      try {
        Field field = current.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException ignored) {
      }
    throw new NoSuchFieldException(type.getName() + "." + name);
  }
}
