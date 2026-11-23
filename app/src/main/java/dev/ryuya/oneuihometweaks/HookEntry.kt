package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.app.WallpaperManager.OnColorsChangedListener
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.Trace
import android.provider.Settings.System
import android.util.Log
import android.view.ViewGroup
import android.os.SystemClock
import dev.ryuya.oneuihometweaks.compat.DexRuntimeCache
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleMethod
import dev.ryuya.oneuihometweaks.compat.compatibleName
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Arrays
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Scoped hosts plus opt-in, disarmed-by-default system_server focus support. No Zygote hooks. */
class HookEntry : XposedModule() {
    private var dataSource: Class<*>? = null
    private var launcherLoader: ClassLoader? = null
    private val readerClasses: MutableMap<String?, Boolean?> =
        ConcurrentHashMap<String?, Boolean?>()
    @Volatile private var snapshot: HomeColorSnapshot = HomeColorSnapshot(null, null)
    private val observers: MutableList<HookEntry.Observed?> = ArrayList<HookEntry.Observed?>()
    private val hooks: MutableList<ModernHook.Handle?> = ArrayList<ModernHook.Handle?>()
    private val lastRead: MutableMap<String?, String?> = HashMap<String?, String?>()
    private var main: Handler? = null
    private var worker: Handler? = null
    private var context: Context? = null
    private var wm: WallpaperManager? = null
    private val pages: PageBridge = PageBridge()
    private val homeLayout: HomeLayoutGuard = HomeLayoutGuard()
    private val freeGridShutdownReceivers = LauncherFreeGridShutdownReceivers()
    private val freeGridPageLoad = LauncherFreeGridPageLoad()
    private val diyWidgets = DiyWidgetPlacementGuard()
    private val diyGrid: DiyGridBridge = DiyGridBridge().also { it.widgetPlacementGuard = diyWidgets }
    private val gridSave: GridSaveCoordinator = GridSaveCoordinator(diyGrid)
    private val drawer: DrawerBridge = DrawerBridge()
    private val homeLabels: HomeLabelBridge = HomeLabelBridge()
    private val recentsBar: RecentsBar = RecentsBar()
    private val circleRecents = CircleSearchRecents()
    private val folders: FolderHooks = FolderHooks()
    private val unlockMotion = HomeUnlockMotion()
    private val iconOverlap = IconOverlapHooks()
    private var initialized: Boolean = false
    private var pendingHomeActivity = WeakReference<Activity>(null)
    private var hostAttached: Boolean = false
    private var earlyPages: Boolean = false
    private var preparedGuard: CrashGuard? = null
    @Volatile private var failed: Boolean = false
    private var failures: Int = 0
    private var listener: OnColorsChangedListener? = null
    private val liveRecents: RecentsLiveTileHooks = RecentsLiveTileHooks()
    private var lastReport: String = ""
    private var processName: String? = null
    private var systemServer = false
    private val launcherMain = Handler(Looper.getMainLooper())
    private var launcherScope: SystemUiHookScope? = null
    private var launcherTransitionsIdle: () -> Boolean = { true }
    private var launcherWorker: LauncherWorker? = null
    private var launcherPackage: TargetPackage? = null
    private var launcherActivity = WeakReference<Activity>(null)
    private var healthyDeadline = 0L
    private var launcherRecovery: Array<*>? = null
    @Volatile private var launcherRetired = false
    private val refresh: Runnable = Runnable { this.refresh() }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        ModernHook.attach(this)
        pages.setFreeGridObserver(
            Consumer { view: ViewGroup? ->
                homeLayout.observe(view!!)
                diyGrid.observe(view!!)
                diyWidgets.observe(view)
                iconOverlap.observe(view)
            }
        )
        diyGrid.setChanged(Runnable { this.requestRefresh() })
        diyWidgets.onChanged = diyGrid::widgetPlacementChanged
        iconOverlap.setChanged(Runnable { this.requestRefresh() })
        processName = param.getProcessName()
        systemServer = param.isSystemServer()
        HookEntry.log(
            ("MODULE_LOADED build=" +
                BuildConfig.VERSION_CODE +
                " api=" +
                ModernHook.apiVersion() +
                " framework=" +
                ModernHook.frameworkDescription() +
                " process=" +
                processName)
        )
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        if (systemServer) return RecentsFocusServer.retire(param::setSavedInstanceState)
        return when (processName) {
            LAUNCHER -> retireLauncher(param::setSavedInstanceState)
            "com.android.systemui" -> SystemUiCodeRuntime.handOff(param::setSavedInstanceState)
            AuxiliaryHostRuntime.SETTINGS, AuxiliaryHostRuntime.PREVIEW ->
                AuxiliaryHostRuntime.handOff(param::setSavedInstanceState)
            else -> false
        }
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val state = requireNotNull(param.savedInstanceState as? Array<*>)
        onModuleLoaded(param)
        val discardPrevious = { param.oldHookHandles.forEach { it.unhook() } }
        if (param.isSystemServer()) {
            RecentsFocusServer.resume(state)
            discardPrevious()
            log("ONEUI_MODS_HOT_RELOAD package=${BuildConfig.APPLICATION_ID} " +
                "version=${BuildConfig.VERSION_CODE} pid=${Process.myPid()}")
            return
        }
        when (param.processName) {
            LAUNCHER -> resumeLauncher(state, discardPrevious)
            "com.android.systemui" -> SystemUiCodeRuntime.resume(state, discardPrevious)
            AuxiliaryHostRuntime.SETTINGS, AuxiliaryHostRuntime.PREVIEW ->
                AuxiliaryHostRuntime.resume(state, discardPrevious)
            else -> error("Unsupported hot-update target ${param.processName}")
        }
    }

    private fun prepareLauncherScope(): SystemUiHookScope {
        launcherScope?.let { return it }
        val scope = SystemUiHookScope { log("LAUNCHER_HOT_RELOAD_SCOPE $it") }
        ModernHook.trackLauncherHooks(scope)
        check(SystemUiHookScopes.trySwitch(scope) { it?.close() })
        launcherScope = scope
        return scope
    }

    private fun retireLauncher(save: (Array<*>) -> Unit): Boolean =
        MainThreadReload.run(launcherMain) retire@{
            val app = context as? Application
            val target = launcherPackage
            val configWorker = launcherWorker
            if (app == null || target == null || configWorker == null) {
                val state = launcherRecovery ?: return@retire false
                if (launcherScope == null) save(state)
                else if (!SystemUiHookScopes.tryRetire { save(state) } && !launcherRetired) return@retire false
                launcherRetired = true
                launcherRecovery = null
                return@retire true
            }
            if (!homeLabels.idleForHotReload() || !liveRecents.idleForHotReload() ||
                !unlockMotion.idleForHotReload() || !diyWidgets.idleForHotReload() ||
                !diyGrid.idleForHotReload() || !gridSave.idleForHotReload() || !launcherTransitionsIdle() ||
                !freeGridShutdownReceivers.idleForHotReload()
            ) return@retire false

            SystemUiHookScopes.tryRetireIf {
                if (!configWorker.tryPause()) return@tryRetireIf false
                if (!homeLayout.pauseForHotReload()) {
                    configWorker.resume()
                    return@tryRetireIf false
                }
                try {
                    val subscriptions = observers.mapNotNull { item ->
                        item?.takeIf { it.ref.get() != null }?.let { arrayOf(it.key, it.ref) }
                    }.toTypedArray()
                    save(arrayOf(app, target.classLoader, launcherActivity, pages.stateForReload(),
                        drawer.stateForReload(), subscriptions,
                        DexRuntimeCache.exportProofs(), healthyDeadline))
                    launcherRetired = true
                    true
                } catch (error: Throwable) {
                    configWorker.resume()
                    homeLayout.resumeAfterRejectedReload()
                    throw error
                }
            }
        }

    private fun resumeLauncher(state: Array<*>, discardPrevious: () -> Unit) {
        val app = requireNotNull(state.getOrNull(0) as? Application)
        val loader = requireNotNull(state.getOrNull(1) as? ClassLoader)
        check(app.packageName == LAUNCHER && HostStartupGate.unlocked(app))
        check(MainThreadReload.run(launcherMain) {
            launcherRecovery = state
            DexRuntimeCache.importProofs(state.getOrNull(6))
            check(DexSymbols.canInstallNow(app))
            val home = (state.getOrNull(2) as? WeakReference<*>)?.get() as? Activity
            pendingHomeActivity = WeakReference(home)
            for (entry in (state.getOrNull(5) as? Array<*>).orEmpty()) {
                val values = entry as? Array<*> ?: continue
                val key = values.getOrNull(0) as? String ?: continue
                val watcher = (values.getOrNull(1) as? WeakReference<*>)?.get() as? ContentObserver
                if (watcher != null) observers.add(Observed(key, watcher))
            }
            val scope = prepareLauncherScope()
            val target = TargetPackage(LAUNCHER, LAUNCHER, app.applicationInfo, loader)
            val deadline = state.getOrNull(7) as? Long ?: 0L
            val guard = if (deadline > 0) CrashGuard.resume(app) else null
            check(guard?.blocked() != true)
            try {
                SystemUiHookScopes.bind(scope) {
                    attachLauncher(target, app)
                    check(initialized && !failed) { "Launcher hooks did not attach" }
                    pages.restoreObservations(state.getOrNull(3))
                    drawer.reconnect(state.getOrNull(4))
                    if (guard != null) scheduleHealthy(guard, deadline)
                }
            } catch (error: Throwable) {
                SystemUiHookScopes.tryRetire {}
                launcherPackage = null
                launcherWorker = null
                launcherScope = null
                throw error
            }
            discardPrevious()
            launcherRecovery = null
            log("ONEUI_MODS_HOT_RELOAD package=${BuildConfig.APPLICATION_ID} " +
                "version=${BuildConfig.VERSION_CODE} pid=${Process.myPid()}")
            true
        }) { "Launcher main thread was unavailable" }
    }

    private fun scheduleHealthy(guard: CrashGuard, deadline: Long) {
        healthyDeadline = deadline
        launcherMain.postAtTime({
            healthyDeadline = 0L
            guard.healthy()
        }, deadline)
    }

    private fun attachLauncher(target: TargetPackage, app: Application) {
        val scope = prepareLauncherScope()
        SystemUiHookScopes.bind(scope) {
            scope.onRetire(::closeLauncher)
            if (BuildConfig.LAUNCHER_PERF_TRACE) LauncherPerformanceTrace.install(target.classLoader)
            InteractionRefresh.install(app, target.classLoader, home = true)
            homeLayout.start(app)
            installLauncher(target, app)
            if (initialized) launcherPackage = target
        }
    }

    private fun closeLauncher() {
        launcherRetired = true
        launcherMain.removeCallbacksAndMessages(null)
        main?.removeCallbacksAndMessages(null)
        launcherWorker?.close()
        val retire = listOf<() -> Unit>(
            { listener?.let { wm?.removeOnColorsChangedListener(it) } },
            { recentsBar.setEnabled(false) },
            liveRecents::uninstall,
            { folders.configure(false, false) },
            { unlockMotion.configure(false) },
            homeLabels::closeForReload,
            pages::closeForReload,
            drawer::closeForReload,
            gridSave::closeForReload,
            diyWidgets::closeForReload,
            diyGrid::closeForReload,
            { iconOverlap.setEnabled(false) },
        )
        for (cleanup in retire) runCatching(cleanup).onFailure {
            log("LAUNCHER_RETIRE ${it.javaClass.simpleName}: ${it.message}")
        }
        DexSymbols.detachObserver()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        try {
            RecentsFocusServer.install(param.getClassLoader())
        } catch (error: Throwable) {
            HookEntry.log("RECENTS_FOCUS_SERVER_UNSUPPORTED " + error.javaClass.getSimpleName())
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage()) return
        handleLoadPackage(
            HookEntry.TargetPackage(
                param.getPackageName(),
                processName!!,
                param.getApplicationInfo(),
                param.getClassLoader(),
            )
        )
    }

    private fun handleLoadPackage(p: HookEntry.TargetPackage) {
        if (
            p.packageName != p.processName ||
                !Arrays.asList<String?>(*ConfigProvider.HOSTS!!).contains(p.packageName)
        )
            return
        if (p.packageName == LAUNCHER) prepareLauncherScope()
        ModernHook.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(param: ModernHook.Param) {
                    if (
                        hostAttached ||
                            (HookEntry.LAUNCHER != p.packageName) ||
                            !HookCompatibility.isCurrentSupported() ||
                            ("SM-S948Q" != Build.MODEL) ||
                            Process.myUid() / 100000 != 0
                    )
                        return
                    val base: Context? = param.args[0] as Context?
                    if (
                        HookEntry.LAUNCHER != base!!.getPackageName() ||
                            !HostStartupGate.unlocked(base)
                    ) return
                    try {
                        if (!DexSymbols.canInstallNow(base!!)) return
                        preparedGuard = CrashGuard.enter(base!!)
                        if (preparedGuard!!.blocked()) return
                        DexEvidence.verifyScrollContract(
                            p.appInfo.sourceDir,
                            "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceFastRecyclerView",
                        )
                        DexEvidence.workspaceVerified = true
                        pages.install(p.classLoader)
                        earlyPages = true
                    } catch (t: Throwable) {
                        HookEntry.log("EARLY_PAGE_UNSUPPORTED " + t)
                    }
                }

                override fun afterHookedMethod(param: ModernHook.Param) {
                    if (param.hasThrowable()) return
                    val app: Application? = param.thisObject as Application?
                    if (hostAttached || p.packageName != app!!.getPackageName()) return
                    hostAttached = true
                    context = app
                    val prepare: () -> Unit = {
                        DexSymbols.configure(app)
                        DexSymbols.whenPrepared(app) {
                            try {
                                if (
                                    !HookCompatibility.isCurrentSupported() ||
                                        ("SM-S948Q" != Build.MODEL) ||
                                        Process.myUid() / 100000 != 0
                                ) {
                                    HostStatus.report(
                                        app,
                                        "UNSUPPORTED",
                                        "SDK/model/user compatibility gate",
                                    )
                                    return@whenPrepared
                                }
                                val guard: CrashGuard? =
                                    if (preparedGuard != null) preparedGuard else CrashGuard.enter(app)
                                if (guard!!.blocked()) {
                                    HostStatus.report(
                                        app,
                                        "CRASH_GUARD",
                                        "Three confirmed crash starts; clear protection before retrying",
                                    )
                                    if ("com.android.systemui" == p.packageName)
                                        HostStatus.reportFeature(
                                            app,
                                            "quick_panel",
                                            "CRASH_GUARD",
                                            "Host startup protection is active",
                                        )
                                    HookEntry.log("CRASH_GUARD blocked " + p.packageName)
                                    return@whenPrepared
                                }
                                if (LAUNCHER == p.packageName) {
                                    attachLauncher(p, app)
                                    if (initialized) scheduleHealthy(guard, SystemClock.uptimeMillis() + 90_000L)
                                } else if ("com.android.systemui" == p.packageName) {
                                    SystemUiCodeRuntime.start(app, p.classLoader, guard)
                                } else {
                                    AuxiliaryHostRuntime.start(app, p.classLoader, guard)
                                }
                            } catch (t: Throwable) {
                                HostStatus.report(
                                    app,
                                    "ERROR",
                                    t.javaClass.getSimpleName() + ": " + t.message,
                                )
                                HookEntry.log("HOST_DISABLED " + t)
                            }
                        }
                    }
                    if (p.packageName == HookEntry.LAUNCHER) {
                        HostStartupGate.whenUnlocked(app) { activity ->
                            pendingHomeActivity = WeakReference(activity)
                            prepare()
                        }
                    } else {
                        // These hosts also read the module's CE-only settings and DexKit cache.
                        // Before first unlock a failed install would otherwise remain inactive
                        // until SystemUI is restarted, even after the provider becomes available.
                        HostStartupGate.whenUnlocked(app) { prepare() }
                    }

                }
            },
        )
    }

    private fun installLauncher(p: HookEntry.TargetPackage, app: Application) {
        if (HookEntry.LAUNCHER != p.packageName || HookEntry.LAUNCHER != p.processName) return
        try {
            if (
                !HookCompatibility.isCurrentSupported() ||
                    !"samsung".equals(Build.MANUFACTURER, ignoreCase = true) ||
                    ("SM-S948Q" != Build.MODEL) ||
                    Process.myUid() / 100000 != 0 ||
                    p.appInfo == null
            ) {
                HookEntry.log(
                    "UNSUPPORTED: manufacturer, model, SDK or owner user. No hooks installed."
                )
                return
            }
            launcherLoader = p.classLoader
            dataSource =
                ModernHook.findClass(
                    "com.honeyspace.sdk.source.GlobalSettingsDataSource",
                    launcherLoader,
                )
            val key =
                ModernHook.findClass("com.honeyspace.sdk.source.entity.SettingsKey", launcherLoader)
            val updater =
                ModernHook.findClass(
                    "com.honeyspace.common.interfaces.WhiteBgColorUpdater",
                    launcherLoader,
                )
            if (
                !dataSource!!.isInterface() ||
                    !updater!!.isInterface() ||
                    ("kotlinx.coroutines.flow.StateFlow" !=
                        dataSource!!
                            .compatibleMethod("get", key)
                            .getReturnType()
                            .compatibleName()) ||
                    ("kotlinx.coroutines.flow.StateFlow" !=
                        updater!!
                            .compatibleMethod("getDarkFont")
                            .getReturnType()
                            .compatibleName()) ||
                    ("kotlinx.coroutines.flow.StateFlow" !=
                        updater!!
                            .compatibleMethod("getDarkStatusBar")
                            .getReturnType()
                            .compatibleName())
            ) {
                HostStatus.report(app, "UNSUPPORTED", "Samsung interface mismatch")
                HookEntry.log("UNSUPPORTED: stable Samsung interface mismatch")
                return
            }
            DexEvidence.verifyAndDeoptimize(p.appInfo.sourceDir, launcherLoader)
            LauncherEarlyWakeupHooks.install(app, p.classLoader)
            LauncherFocusedDisplayHooks.install(app, p.classLoader)
            LauncherWidgetCornerResources.install(app, p.classLoader)
            LauncherLiveIconHooks.install(app, p.classLoader)
            if (BuildConfig.LAUNCHER_PREUPLOAD_WIDGET_BITMAPS) {
                LauncherWidgetBitmapPreupload.install(app)
            }
            if (BuildConfig.LAUNCHER_SKIP_ICON_BLUR) {
                LauncherTransitionIconBlur.install(app, p.classLoader)
            }
            unlockMotion.install(app, p.classLoader)
            if (Build.VERSION.SDK_INT == 37) {
                StockSurfaceAnimationHooks.install(app, p.classLoader)
            }
            if (BuildConfig.LAUNCHER_BLUR_CACHE) {
                LauncherBlurCacheHooks.install(app, p.classLoader)
            }
            if (BuildConfig.LAUNCHER_TRANSITION_DISPLAY_LIST_REUSE) {
                LauncherTransitionDisplayListHooks.install(app, p.classLoader)
            }
            if (BuildConfig.LAUNCHER_RENDER_TRANSACTION_MERGE) {
                LauncherRenderTransactionMerge.install(app, p.classLoader)
            }
            if (BuildConfig.LAUNCHER_STOCK_TRANSACTION_TRACE) {
                LauncherStockTransactionTrace.install(app, p.classLoader)
            }
            if (BuildConfig.LAUNCHER_BATCH_RECENTS_START && Build.VERSION.SDK_INT >= 37) {
                LauncherRecentsStartBatch.install(app, p.classLoader)
            }
            if (BuildConfig.LAUNCHER_PREWARM_HOME_UP_PROVIDER) {
                LauncherHomeUpProviderWarmup.install(app, p.classLoader)
            }
            prepareFreeGridPerformance(app)
            if (BuildConfig.LAUNCHER_REUSE_SEARCH_BACKGROUND_LAYOUT) {
                LauncherSearchBackgroundLayoutHooks.install(app, p.classLoader)
            }
            if (BuildConfig.LAUNCHER_OPTIMIZE_STACK_WIDGET_OPTIONS) {
                LauncherStackedWidgetOptions.install(app, p.classLoader)
            }
            if (BuildConfig.LAUNCHER_PREPARE_STACK_WIDGET_OFF_MAIN) {
                LauncherStackedWidgetPreparation.install(app, p.classLoader)
            }
            if ((BuildConfig.LAUNCHER_ICON_BUFFER_TRACE || BuildConfig.LAUNCHER_ICON_GEOMETRY_TRACE) &&
                Build.VERSION.SDK_INT >= 34
            ) {
                LauncherIconBufferTrace.install(app, p.classLoader)
            }
            if (BuildConfig.LAUNCHER_CLOSING_ICON_SURFACE && Build.VERSION.SDK_INT >= 34) {
                LauncherClosingIconSurface.install(app, p.classLoader)?.let { launcherTransitionsIdle = it }
            }
            if (BuildConfig.LAUNCHER_SPRING_ALPHA_CACHE) {
                LauncherSpringAlphaCache.install(app, p.classLoader)
            }
            if (BuildConfig.LAUNCHER_SPRING_REPLAY_PROBE && !BuildConfig.LAUNCHER_DETACHED_CLOSING_SPRING &&
                Build.VERSION.SDK_INT >= 35
            ) {
                LauncherSpringReplayProbe.install(app, p.classLoader)
            }
            if (BuildConfig.LAUNCHER_OPEN_REPLAY_PROBE && !BuildConfig.LAUNCHER_DETACHED_OPENING &&
                Build.VERSION.SDK_INT >= 35
            ) {
                LauncherOpeningReplayProbe.install(app, p.classLoader)
            }
            val reads: ModernHook.Callback =
                object : ModernHook.Callback() {
                    override fun afterHookedMethod(param: ModernHook.Param) {
                        try {
                            if (failed || param.hasThrowable()) return
                            val key: String? = param.args[1] as String?
                            if (HookEntry.FONT != key) return
                            if (param.args.size == 3 && param.args[2] as Int != 0) return
                            // Revalidate the actual consumer, even after obfuscation changes.
                            if (!isSamsungDataSourceRead()) return
                            val s: HomeColorSnapshot? = snapshot
                            val value: Int? =
                                if (HookEntry.FONT == key) s!!.font
                                else if (HookEntry.STATUS == key) s!!.status else null
                            if (value == null) return
                            val replacement = value.toString()
                            if (replacement == param.getResult()) return
                            val evidence: String =
                                param.getResult().toString() + " -> " + replacement
                            synchronized(lastRead) {
                                if (evidence != lastRead.put(key, evidence))
                                    HookEntry.log("READ " + key + " " + evidence)
                            }
                            param.setResult(replacement)
                        } catch (t: Throwable) {
                            fail("read callback", t)
                        }
                    }
                }
            hooks.add(
                ModernHook.findAndHookMethod(
                    System::class.java,
                    "getString",
                    ContentResolver::class.java,
                    String::class.java,
                    reads,
                )
            )
            hooks.add(
                ModernHook.findAndHookMethod(
                    System::class.java,
                    "getStringForUser",
                    ContentResolver::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    reads,
                )
            )
            val registrations: ModernHook.Callback =
                object : ModernHook.Callback() {
                    override fun afterHookedMethod(param: ModernHook.Param) {
                        try {
                            if (failed || param.hasThrowable()) return
                            val uri: Uri? = param.args[0] as Uri?
                            val key: String? =
                                if (System.getUriFor(HookEntry.FONT) == uri) HookEntry.FONT
                                else null
                            if (key == null) return
                            if (
                                param.args.size == 4 &&
                                    param.args[3] as Int != 0 &&
                                    param.args[3] as Int != -2
                            )
                                return
                            val observer: ContentObserver? = param.args[2] as ContentObserver?
                            synchronized(observers) {
                                observers.removeIf { o: HookEntry.Observed? ->
                                    o!!.ref.get() == null
                                }
                                for (o in observers) if (
                                    o!!.ref.get() === observer && o!!.key == key
                                )
                                    return
                                observers.add(HookEntry.Observed(key, observer))
                            }
                            HookEntry.log("OBSERVE " + key)
                        } catch (t: Throwable) {
                            fail("observer callback", t)
                        }
                    }
                }
            hooks.add(
                ModernHook.findAndHookMethod(
                    ContentResolver::class.java,
                    "registerContentObserver",
                    Uri::class.java,
                    Boolean::class.javaPrimitiveType,
                    ContentObserver::class.java,
                    registrations,
                )
            )
            hooks.add(
                ModernHook.findAndHookMethod(
                    ContentResolver::class.java,
                    "registerContentObserver",
                    Uri::class.java,
                    Boolean::class.javaPrimitiveType,
                    ContentObserver::class.java,
                    Int::class.javaPrimitiveType,
                    registrations,
                )
            )
            hooks.add(
                ModernHook.findAndHookMethod(
                    ContentResolver::class.java,
                    "unregisterContentObserver",
                    ContentObserver::class.java,
                    object : ModernHook.Callback() {
                        override fun afterHookedMethod(param: ModernHook.Param) {
                            if (param.hasThrowable()) return
                            synchronized(observers) {
                                observers.removeIf { o: HookEntry.Observed? ->
                                    o!!.ref.get() == null || o!!.ref.get() === param.args[0]
                                }
                            }
                        }
                    },
                )
            )
            if (!earlyPages)
                try {
                    pages.install(p.classLoader)
                } catch (t: Throwable) {
                    HookEntry.log("PAGE_UNSUPPORTED " + t)
                }
            try {
                drawer.install(p.classLoader)
            } catch (t: Throwable) {
                HookEntry.log("DRAWER_UNSUPPORTED " + t)
            }
            initialize(app)
            val exact: Boolean =
                HookEntry.FINGERPRINT == Build.FINGERPRINT &&
                    HookEntry.LAUNCHER_SHA == HookEntry.sha256(p.appInfo.sourceDir)
            HookEntry.log(
                ("HOOKS_READY: " +
                    (if (exact) "verified APK"
                    else
                        "updated APK; structural checks passed; consumer read verification required") +
                    "; modern Xposed API=" +
                    ModernHook.apiVersion())
            )
        } catch (t: Throwable) {
            for (hook in hooks) {
                try {
                    hook!!.unhook()
                } catch (ignored: Throwable) {}
            }
            fail("install; rolled back", t)
        }
    }

    private fun prepareFreeGridPerformance(app: Application) {
        // Home Up's plugin loader carries its own live icon cache; hook it once the plugin classes appear.
        pages.setFreeGridClassObserver { workspace -> LauncherLiveIconHooks.installHomeUp(app, workspace) }
        if (!BuildConfig.LAUNCHER_FREE_GRID_SHUTDOWN_RECEIVER_WORKER &&
            !BuildConfig.LAUNCHER_FREE_GRID_INITIAL_PAGE_WORKER
        ) return
        if (Build.VERSION.SDK_INT != 37 || !HookCompatibility.matchesClockBuild(37, Build.DISPLAY)) return
        try {
            @Suppress("DEPRECATION")
            val launcherVersion = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (!HookCompatibility.matchesLauncherVersion(37, launcherVersion)) return
            @Suppress("DEPRECATION")
            val homeUpVersion = app.packageManager.getPackageInfo("com.samsung.android.app.homestar", 0).longVersionCode
            if (homeUpVersion != 1800005036L) return
            pages.setFreeGridClassObserver { workspace ->
                LauncherLiveIconHooks.installHomeUp(app, workspace)
                if (BuildConfig.LAUNCHER_FREE_GRID_SHUTDOWN_RECEIVER_WORKER) freeGridShutdownReceivers.install(workspace)
                if (BuildConfig.LAUNCHER_FREE_GRID_INITIAL_PAGE_WORKER) freeGridPageLoad.install(workspace)
            }
            if (BuildConfig.LAUNCHER_FREE_GRID_SHUTDOWN_RECEIVER_WORKER) {
                ModernHook.onGenerationRetired(freeGridShutdownReceivers::close)
            }
        } catch (error: Exception) {
            HookEntry.log("FREEGRID_PERFORMANCE_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun isSamsungDataSourceRead(): Boolean {
        for (frame in Thread.currentThread().getStackTrace()) {
            val name = frame.getClassName()
            if (
                name.startsWith("android.") ||
                    name.startsWith("java.") ||
                    name.startsWith("net.ryuya_dev.")
            )
                continue
            var matched = readerClasses.get(name)
            if (matched == null) {
                try {
                    matched =
                        dataSource!!.isAssignableFrom(Class.forName(name, false, launcherLoader))
                } catch (ignored: Throwable) {
                    matched = false
                }
                readerClasses.put(name, matched)
                if (matched) HookEntry.log("VERIFIED_CONSUMER " + name)
            }
            if (matched) return true
        }
        return false
    }

    @Throws(Exception::class)
    private fun initialize(app: Application) {
        if (initialized) return
        context = app
        try {
            recentsBar.install(app, launcherLoader)
        } catch (t: Throwable) {
            HookEntry.log("RECENTS_BAR_UNSUPPORTED " + t)
        }
        DexSymbols.whenDiscovered(app) {
            if (launcherRetired) return@whenDiscovered
            circleRecents.install(launcherLoader)
            requestRefresh()
        }
        liveRecents.setStateListener(Runnable { this.requestRefresh() })
        try {
            liveRecents.install(app, app.getApplicationInfo().sourceDir, launcherLoader)
        } catch (t: Throwable) {
            HookEntry.log("RECENTS_LIVE_UNSUPPORTED " + t)
        }
        try {
            folders.install(launcherLoader)
        } catch (t: Throwable) {
            HookEntry.log("FOLDER_UNSUPPORTED " + t)
        }
        // Samsung's actual WhichChecker and public constants were verified on this device.
        val wc = DexSymbols.findClass("com.samsung.android.wallpaper.utils.WhichChecker")
        check(
            !(1 != wc.compatibleMethod("getType", Int::class.javaPrimitiveType).invoke(null, 5) ||
                4 != wc.compatibleMethod("getMode", Int::class.javaPrimitiveType).invoke(null, 5))
        ) {
            "which=5 incompatible"
        }
        main = Handler(Looper.getMainLooper())
        val configWorker = LauncherWorker("OneUIHomeTweaks")
        launcherWorker = configWorker
        worker = configWorker.handler
        wm = WallpaperManager.getInstance(app)
        try {
            homeLabels.install(app, launcherLoader, worker, Runnable { this.requestRefresh() })
        } catch (t: Throwable) {
            HookEntry.log("LOCAL_LABEL_UNSUPPORTED " + t)
        }
        listener = OnColorsChangedListener { colors: WallpaperColors?, which: Int ->
            if (ColorPolicy.isMainHomeEvent(which)) {
                HookEntry.log(
                    ("COLOR_EVENT which=" +
                        which +
                        " hints=" +
                        (if (colors == null) "null" else colors.getColorHints()))
                )
                main!!.post(Runnable { homeLabels.invalidate() })
                requestRefresh()
            }
        }
        wm!!.addOnColorsChangedListener(listener!!, main!!)
        val settings = object : ContentObserver(worker) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = requestRefresh()
        }
        app.contentResolver.registerContentObserver(ConfigProvider.URI!!, false, settings)
        ModernHook.onGenerationRetired { app.contentResolver.unregisterContentObserver(settings) }
        val wallpaper = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = requestRefresh()
        }
        app.registerReceiver(wallpaper, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
            Context.RECEIVER_NOT_EXPORTED)
        ModernHook.onGenerationRetired { app.unregisterReceiver(wallpaper) }

        val lifecycle = object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                if (a.javaClass.compatibleName() == "$LAUNCHER.Launcher") launcherActivity = WeakReference(a)
                pages.setHomeWindow(a)
                homeLabels.setHome(a)
                requestRefresh()
            }

            override fun onActivityPaused(a: Activity) = homeLabels.paused(a)
            override fun onActivityDestroyed(a: Activity) {
                if (launcherActivity.get() === a) launcherActivity.clear()
                pages.clearHomeWindow(a)
                homeLabels.destroyed(a)
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        }
        app.registerActivityLifecycleCallbacks(lifecycle)
        ModernHook.onGenerationRetired { app.unregisterActivityLifecycleCallbacks(lifecycle) }
        initialized = true
        pendingHomeActivity.get()?.takeUnless { it.isDestroyed }?.let { activity ->
            launcherActivity = WeakReference(activity)
            pages.setHomeWindow(activity)
            homeLabels.setHome(activity)
        }
        pendingHomeActivity.clear()
        requestRefresh()
        HookEntry.log("INITIALIZED: owner user, main home; event-driven, no polling")
    }

    private fun requestRefresh() {
        if (launcherRetired) return
        if (failed || worker == null) return
        worker!!.removeCallbacks(refresh)
        worker!!.postDelayed(refresh, 120)
    }

    private fun refresh() {
        if (failed) return
        try {
            var label = ColorPolicy.OFF
            var status = ColorPolicy.OFF
            var drawerMode = ColorPolicy.AUTO
            var focusMode: Int = 0
            var folderReturn: Boolean = true
            var animateUnlock = false
            var iconOverlapFix = true
            var master: Boolean = false
            var pageMode: Boolean = false
            var hideRecentsBar: Boolean = false
            var recentsLive: Boolean = false
            var hideCircleSearch = false
            var diyGridMode: Boolean = false
            var avoidWidgets = false
            context!!
                .getContentResolver()
                .query(ConfigProvider.URI!!, null, null, null, null)
                .use { cursor ->
                    check(!(cursor == null || !cursor.moveToFirst())) { "No configuration" }
                    master =
                        cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) != 0 &&
                            cursor.getInt(cursor.getColumnIndexOrThrow("home_enabled")) != 0
                    val circleColumn = cursor.getColumnIndex("hide_circle_search")
                    hideCircleSearch = master && circleColumn >= 0 && cursor.getInt(circleColumn) != 0
                    val folderColumn = cursor.getColumnIndex("folder_return")
                    folderReturn = folderColumn < 0 || cursor.getInt(folderColumn) != 0
                    val unlockColumn = cursor.getColumnIndex(HomeUnlockMotion.KEY)
                    animateUnlock = master && unlockColumn >= 0 && cursor.getInt(unlockColumn) != 0
                    val overlapColumn = cursor.getColumnIndex("icon_overlap_fix")
                    iconOverlapFix = overlapColumn < 0 || cursor.getInt(overlapColumn) != 0
                    pageMode = cursor.getInt(cursor.getColumnIndexOrThrow("pages")) != 0
                    hideRecentsBar =
                        master &&
                            cursor.getInt(cursor.getColumnIndexOrThrow("recents_hide_bar")) != 0
                    val liveColumn = cursor.getColumnIndex("recents_live_tile")
                    recentsLive = master && liveColumn >= 0 && cursor.getInt(liveColumn) != 0
                    val focusColumn = cursor.getColumnIndex("recents_focus_mode")
                    focusMode =
                        if (recentsLive && focusColumn >= 0) cursor.getInt(focusColumn) else 0
                    diyGridMode =
                        master && cursor.getInt(cursor.getColumnIndexOrThrow("diy_icon_grid")) != 0
                    val widgetPlacementColumn = cursor.getColumnIndex("diy_avoid_widgets")
                    avoidWidgets = master && widgetPlacementColumn >= 0 && cursor.getInt(widgetPlacementColumn) != 0
                    drawerMode = cursor.getInt(cursor.getColumnIndexOrThrow("drawer"))
                    label = cursor.getInt(cursor.getColumnIndexOrThrow("label"))
                    // Status bar belongs to the user's separate module; never override it.
                    status = ColorPolicy.OFF
                }
            val info = wm!!.getWallpaperInfo()
            val target: Boolean = info != null
            val colors: WallpaperColors? =
                if (target) wm!!.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) else null
            val hints: Int? = if (colors == null) null else colors.getColorHints()
            if (!master) {
                label = ColorPolicy.OFF
                status = ColorPolicy.OFF
            }
            val apply: Boolean = master && target
            val currentDrawerMode: Int = drawerMode
            val currentPages: Boolean = pageMode
            val currentHideRecentsBar: Boolean = hideRecentsBar
            val currentLiveRecents: Boolean = recentsLive
            val currentFocusMode: Int = focusMode
            liveRecents.connectFocus(context)
            val currentHideCircleSearch = hideCircleSearch
            val currentMaster: Boolean = master
            val currentFolderReturn: Boolean = folderReturn
            val currentUnlockMotion = animateUnlock
            val currentOverlapFix = master && iconOverlapFix
            val currentDiyGrid: Boolean = diyGridMode
            val currentAvoidWidgets = avoidWidgets
            val gridRequest =
                context!!
                    .getContentResolver()
                    .call(ConfigProvider.URI!!, "take_grid_request", null, null)
            val localLabels: Boolean = apply && label == ColorPolicy.LOCAL
            main!!.post(
                Runnable {
                    // A later refresh may disable the bridge before this task runs.
                    if (failed) return@Runnable
                    pages.setEnabled(apply && currentPages)
                    drawer.setMode(currentDrawerMode, apply)
                    homeLabels.setEnabled(localLabels)
                    liveRecents.setFocusMode(currentFocusMode)
                    liveRecents.setEnabled(currentLiveRecents)
                    recentsBar.setEnabled(currentHideRecentsBar)
                    circleRecents.setEnabled(currentHideCircleSearch)
                    folders.configure(currentMaster, currentFolderReturn)
                    unlockMotion.configure(currentUnlockMotion)
                    iconOverlap.setEnabled(currentOverlapFix)
                    diyWidgets.setEnabled(currentAvoidWidgets)
                    diyGrid.setEnabled(currentDiyGrid)
                    gridSave.accept(context!!, gridRequest)
                    val detail = ("Launcher bridge; wallpaper=" +
                            (if (target) "live" else "static") +
                            "; hints=" +
                            hints +
                            "; localLabels=" +
                            homeLabels.diagnostic() +
                            "; recentsBar=" +
                            recentsBar.diagnostic() +
                            "; circleRecents=" + circleRecents.diagnostic() +
                            "; liveRecents=" +
                            liveRecents.diagnostic() +
                            "; focusBridge=" +
                            liveRecents.focusDiagnostic() +
                            "; diyGrid=" +
                            diyGrid.diagnostic() +
                            "; folders=" +
                            folders.diagnostic() +
                            "; iconOverlap=" +
                            iconOverlap.diagnostic())
                    val state = if (currentMaster) "ACTIVE" else "OFF"
                    val host = context
                    // Snapshot view-owned diagnostics here. Provider storage and notification can
                    // wait on system_server, so keep that IPC off the launcher frame thread.
                    worker?.post {
                        if (!failed && !launcherRetired) {
                            val tracing = Trace.isEnabled()
                            if (tracing) Trace.beginSection("OneUI launcher status report")
                            try {
                                HostStatus.report(host, state, detail)
                            } finally {
                                if (tracing) Trace.endSection()
                            }
                        }
                    }
                }
            )
            val next = HomeColorSnapshot.resolve(label, status, target, hints)
            val report: String =
                ("STATE wallpaper=" +
                    (if (info == null) "static/none" else info.getComponent()) +
                    " hints=" +
                    hints +
                    " labelMode=" +
                    label +
                    " statusMode=" +
                    status +
                    " darkFont=" +
                    next.font +
                    " darkStatus=" +
                    next.status)
            if (report != lastReport) {
                HookEntry.log(report)
                lastReport = report
            }
            publish(next)
            failures = 0
        } catch (t: Throwable) {
            publish(HomeColorSnapshot(null, null))
            main!!.post(
                Runnable {
                    homeLabels.setEnabled(false)
                    diyGrid.setEnabled(false)
                    diyWidgets.setEnabled(false)
                    iconOverlap.setEnabled(false)
                    unlockMotion.configure(false)
                }
            )
            HookEntry.log(
                ("REFRESH_ERROR, native behavior restored: " +
                    t.javaClass.getSimpleName() +
                    ": " +
                    t.message)
            )
            if (++failures >= 3) fail("three consecutive refresh errors", t)
        }
    }

    private fun publish(next: HomeColorSnapshot) {
        val before: HomeColorSnapshot? = snapshot
        snapshot = next
        val fontChanged: Boolean = before!!.font != next.font
        val statusChanged: Boolean = before!!.status != next.status
        if (main == null || (!fontChanged && !statusChanged)) return
        main!!.post(
            Runnable {
                try {
                    val copy: MutableList<HookEntry.Observed?>?
                    synchronized(observers) {
                        observers.removeIf { o: HookEntry.Observed? -> o!!.ref.get() == null }
                        copy = ArrayList<HookEntry.Observed?>(observers)
                    }
                    var count: Int = 0
                    for (o in copy!!) {
                        if (
                            (HookEntry.FONT == o!!.key && fontChanged) ||
                                (HookEntry.STATUS == o!!.key && statusChanged)
                        ) {
                            val observer = o!!.ref.get()
                            if (observer != null) {
                                observer.dispatchChange(false, System.getUriFor(o!!.key))
                                count++
                            }
                        }
                    }
                    HookEntry.log("DISPATCH local observers=" + count)
                } catch (t: Throwable) {
                    fail("local dispatch", t)
                }
            }
        )
    }

    private fun fail(stage: String?, t: Throwable) {
        // Restore the original reads and let native observers reload their saved values.
        failed = true
        HostStatus.report(context, "ERROR", stage + ": " + t.javaClass.getSimpleName())
        if (main != null)
            main!!.post(
                Runnable {
                    pages.setEnabled(false)
                    drawer.setMode(ColorPolicy.OFF, false)
                    homeLabels.setEnabled(false)
                    recentsBar.setEnabled(false)
                    diyGrid.setEnabled(false)
                    diyWidgets.setEnabled(false)
                    iconOverlap.setEnabled(false)
                }
            )
        publish(HomeColorSnapshot(null, null))
        HookEntry.log("DISABLED " + stage + ": " + t)
    }

    private class Observed(key: String, observer: ContentObserver?) {
        @JvmField val key: String
        @JvmField val ref: WeakReference<ContentObserver?>

        init {
            this.key = key
            ref = WeakReference<ContentObserver?>(observer)
        }
    }

    private class TargetPackage(
        packageName: String,
        processName: String,
        appInfo: ApplicationInfo,
        classLoader: ClassLoader,
    ) {
        @JvmField val packageName: String
        @JvmField val processName: String
        @JvmField val appInfo: ApplicationInfo
        @JvmField val classLoader: ClassLoader

        init {
            this.packageName = packageName
            this.processName = processName
            this.appInfo = appInfo
            this.classLoader = classLoader
        }
    }

    companion object {
        const val LAUNCHER: String = "com.sec.android.app.launcher"
        private const val FINGERPRINT: String =
            "samsung/m3qjpnw/m3q:16/BP4A.251205.006/S948QOPS1AZHL_SJP1AZHL:user/release-keys"
        private const val LAUNCHER_SHA: String =
            "e8f0ac6818d066549720ebbeea6ac6c13e04e4ee93a26f5ea35589b6dc0bb291"
        private const val FONT: String = "need_dark_font"
        private const val STATUS: String = "need_dark_statusbar"

        @JvmStatic
        fun log(message: String?) {
            Log.i("OneUIHomeTweaks", message!!)
            ModernHook.log(message)
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun sha256(path: String?): String? {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            FileInputStream(path).use { stream ->
                var n: Int
                while ((stream.read(buffer).also { n = it }) != -1) digest.update(buffer, 0, n)
            }
            val result = StringBuilder()
            for (b in digest.digest()) result.append(
                String.format(Locale.ROOT, "%02x", b.toInt() and 255)
            )
            return result.toString()
        }
    }
}
