package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import dev.ryuya.oneuihometweaks.compat.compatibleMethod
import dev.ryuya.oneuihometweaks.compat.compatibleName
import java.lang.Boolean as JavaBoolean
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.WeakHashMap
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.abs
import kotlin.math.log

/* Reviewed Settings 17 contract; Android 16 hosts retain their existing independent behavior. */
class DeveloperAnimationHooks {
    private val bindings: MutableMap<Any?, DeveloperAnimationHooks.Binding?> =
        WeakHashMap<Any?, DeveloperAnimationHooks.Binding?>()
    private val hooks: MutableList<ModernHook.Handle?> = ArrayList<ModernHook.Handle?>()
    private val main: Handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var enabled: Boolean = false
    private var waitingForConfig = false
    private var failed: Boolean = false
    private var dialog: AnimationScaleDialog? = null
    private var configThread: HandlerThread? = null
    private var configObserver: ContentObserver? = null
    private var activityCallbacks: ActivityLifecycleCallbacks? = null

    fun readyForHotReload(): Boolean = !failed && dialog?.showing() != true

    fun liveControllers(): Array<WeakReference<Any?>> =
        bindings.values.mapNotNull { it?.controller }.filter { it.get() != null }.toTypedArray()

    fun reattachControllers(controllers: Array<*>) {
        for (reference in controllers) {
            val controller = (reference as? WeakReference<*>)?.get() ?: continue
            val index = CONTROLLERS!!.indexOf(controller.javaClass.compatibleName().substringAfterLast('.'))
            if (index >= 0) {
                val update = controller.javaClass.compatibleDeclaredMethod(UPDATE!![index])
                update.isAccessible = true
                update.invoke(controller)
            }
        }
    }

    fun close() {
        failed = true
        closeDialog()
        main.removeCallbacksAndMessages(null)
        configObserver?.let { observer ->
            runCatching { context?.contentResolver?.unregisterContentObserver(observer) }
                .onFailure { HookEntry.log("ANIMATION_EDITOR_RETIRE $it") }
        }
        activityCallbacks?.let { (context as Application).unregisterActivityLifecycleCallbacks(it) }
        configThread?.quitSafely()
        bindings.clear()
        hooks.clear()
    }

    fun install(context: Application, loader: ClassLoader?): Boolean {
        this.context = context
        try {
            if (
                Build.VERSION.SDK_INT != 37 ||
                    (Android17Hooks.BUILD != Build.DISPLAY) ||
                    ("17" !=
                        context
                            .getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0)
                            .versionName)
            )
                throw UnsupportedOperationException("Unreviewed Settings contract")
            val preference = DexSymbols.findClass("androidx.preference.Preference", false, loader)
            val dialogPreference =
                DexSymbols.findClass("androidx.preference.DialogPreference", false, loader)
            for (i in DeveloperAnimationHooks.CONTROLLERS!!.indices) {
                val index: Int = i
                val owner =
                    DexSymbols.findClass(
                        "com.android.settings.development." +
                            DeveloperAnimationHooks.CONTROLLERS[i],
                        false,
                        loader,
                    )
                val update = owner.compatibleDeclaredMethod(DeveloperAnimationHooks.UPDATE!![i])
                val changed =
                    owner.compatibleDeclaredMethod(
                        "onPreferenceChange",
                        preference,
                        Any::class.java,
                    )
                val selected = DeveloperAnimationHooks.field(owner, "mPreference")
                val manager = DeveloperAnimationHooks.field(owner, "mWindowManager")
                val read =
                    manager!!
                        .getType()
                        .compatibleMethod("getAnimationScale", Int::class.javaPrimitiveType)
                hooks.add(
                    ModernHook.hookMethod(
                        update,
                        object : ModernHook.Callback() {
                            override fun afterHookedMethod(call: ModernHook.Param) {
                                if (failed || call.hasThrowable()) return
                                try {
                                    val item = selected!!.get(call.thisObject)
                                    if (
                                        item == null ||
                                            DeveloperAnimationHooks.KEYS!![index] !=
                                                preference.compatibleMethod("getKey").invoke(item)
                                    )
                                        return
                                    val binding =
                                        DeveloperAnimationHooks.Binding(
                                            item,
                                            call.thisObject,
                                            index,
                                            manager!!,
                                            read,
                                            changed,
                                            update,
                                        )
                                    bindings.put(item, binding)
                                    if (enabled) binding.summary()
                                } catch (error: Exception) {
                                    fail(error)
                                }
                            }
                        },
                    )
                )
                ModernHook.deoptimize(update)
            }
            hooks.add(
                ModernHook.findAndHookMethod(
                    dialogPreference,
                    "onClick",
                    object : ModernHook.Callback() {
                        override fun beforeHookedMethod(call: ModernHook.Param) {
                            val binding = bindings.get(call.thisObject)
                            if (!enabled || failed || binding == null) return
                            try {
                                if (dialog != null && dialog!!.showing()) {
                                    call.setResult(null)
                                    return
                                }
                                val host: Context? =
                                    preference
                                        .compatibleMethod("getContext")
                                        .invoke(call.thisObject) as Context?
                                val title: CharSequence? =
                                    preference.compatibleMethod("getTitle").invoke(call.thisObject)
                                        as CharSequence?
                                dialog =
                                    AnimationScaleDialog(
                                        host!!,
                                        title,
                                        binding.value(),
                                        AnimationScaleDialog.Save { value: String? ->
                                            binding.save(value)
                                        },
                                    )
                                dialog!!.show()
                                call.setResult(null)
                            } catch (error: Exception) {
                                fail(error)
                            }
                        }
                    },
                )
            )
            readConfig()
            val callbacks = object : ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, state: Bundle?) {}

                    override fun onActivityStarted(activity: Activity) {}

                    override fun onActivityResumed(activity: Activity) {
                        if (waitingForConfig) readConfig()
                    }

                    override fun onActivityPaused(activity: Activity) {}

                    override fun onActivityStopped(activity: Activity) {
                        closeDialog()
                    }

                    override fun onActivitySaveInstanceState(
                        activity: Activity,
                        state: Bundle,
                    ) {}

                    override fun onActivityDestroyed(activity: Activity) {}
            }
            context.registerActivityLifecycleCallbacks(callbacks)
            activityCallbacks = callbacks
            val worker = HandlerThread("OneUiSettingsConfig")
            worker.start()
            configThread = worker
            val observer = object : ContentObserver(Handler(worker.looper)) {
                override fun onChange(self: Boolean) {
                    main.post { readConfig() }
                }
            }
            context.contentResolver.registerContentObserver(ConfigProvider.URI!!, false, observer)
            configObserver = observer
            HookEntry.log("ANIMATION_EDITOR_READY sdk=37")
            return !failed
        } catch (error: Exception) {
            fail(error)
            return false
        }
    }

    private fun closeDialog() {
        if (dialog == null) return
        dialog!!.dismiss()
        dialog = null
    }

    private fun readConfig() {
        if (failed) return
        val host = context ?: return
        val selected = try {
            host.contentResolver.query(ConfigProvider.URI!!, null, null, null, null).use { cursor ->
                check(cursor != null && cursor.moveToFirst()) { "Configuration is not available yet" }
                cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) != 0 &&
                    cursor.getInt(cursor.getColumnIndexOrThrow("settings_enabled")) != 0
            }
        } catch (error: Exception) {
            // The provider can disappear during an APK update. Keep the installed hooks and
            // retry on the next configuration notification or return to Settings.
            enabled = false
            waitingForConfig = true
            closeDialog()
            HostStatus.report(host, "WAITING", "Animation editor configuration: ${error.message ?: error.javaClass.simpleName}")
            HookEntry.log("ANIMATION_EDITOR_CONFIG_UNAVAILABLE $error")
            return
        }
        waitingForConfig = false
        try {
            enabled = selected
            if (!enabled) closeDialog()
            bindings.values.toList().forEach { it?.refresh() }
            HostStatus.report(host, if (enabled) "ACTIVE" else "OFF", "Developer animation scale editor")
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun fail(error: Exception) {
        failed = true
        enabled = false
        closeDialog()
        for (hook in hooks) hook!!.unhook()
        hooks.clear()
        val cause = error.cause ?: error
        HostStatus.report(
            context,
            if (error is UnsupportedOperationException) "UNSUPPORTED" else "ERROR",
            "Animation editor: ${cause.javaClass.simpleName}" +
                (cause.message?.let { ": $it" } ?: ""),
        )
        HookEntry.log("ANIMATION_EDITOR_DISABLED " + error)
    }

    private class Binding(
        preference: Any?,
        controller: Any?,
        index: Int,
        manager: Field,
        read: Method,
        changed: Method,
        update: Method,
    ) {
        @JvmField val preference: WeakReference<Any?>
        @JvmField val controller: WeakReference<Any?>
        @JvmField val index: Int
        @JvmField val manager: Field
        @JvmField val read: Method
        @JvmField val changed: Method
        @JvmField val update: Method

        init {
            this.preference = WeakReference<Any?>(preference)
            this.controller = WeakReference<Any?>(controller)
            this.index = index
            this.manager = manager
            this.read = read
            this.changed = changed
            this.update = update
        }

        @Throws(Exception::class)
        fun value(): Float {
            val owner = controller.get()
            checkNotNull(owner) { "Settings closed" }
            return read.invoke(manager.get(owner), index) as Float
        }

        @Throws(Exception::class)
        fun summary() {
            val item = preference.get()
            if (item == null) return
            val context: Context? =
                item.javaClass.compatibleMethod("getContext").invoke(item) as Context?
            val summary: String =
                (AnimationScaleValue.format(
                    AnimationScaleValue.ticks(value()),
                    context!!.getResources().getConfiguration().getLocales().get(0),
                ) + "×")
            item.javaClass
                .compatibleMethod("setSummary", CharSequence::class.java)
                .invoke(item, summary)
        }

        @Throws(Exception::class)
        fun refresh() {
            val owner = controller.get()
            if (owner != null) update.invoke(owner)
        }

        @Throws(Exception::class)
        fun save(value: String?) {
            val owner = controller.get()
            val item = preference.get()
            check(!(owner == null || item == null)) { "Settings closed" }
            check(JavaBoolean.TRUE == item.javaClass.compatibleMethod("isEnabled").invoke(item)) {
                "Preference disabled"
            }
            check(JavaBoolean.TRUE == changed.invoke(owner, item, value)) {
                "Native change rejected"
            }
            check(!(abs(value() - value!!.toFloat()) > 0.0001f)) { "Native value not applied" }
        }
    }

    companion object {
        private val CONTROLLERS: Array<String?>? =
            arrayOf<String?>(
                "WindowAnimationScalePreferenceController",
                "TransitionAnimationScalePreferenceController",
                "AnimatorDurationScalePreferenceController",
            )
        private val KEYS: Array<String?>? =
            arrayOf<String?>(
                "window_animation_scale",
                "transition_animation_scale",
                "animator_duration_scale_pref_key",
            )
        private val UPDATE: Array<String?>? =
            arrayOf<String?>(
                "updateAnimationScaleValue$2",
                "updateAnimationScaleValue$1",
                "updateAnimationScaleValue",
            )

        @JvmStatic
        @Throws(NoSuchFieldException::class)
        private fun field(owner: Class<*>?, name: String?): Field? {
            var type: Class<*>? = owner
            while (type != null) {
                try {
                    val value = type.compatibleDeclaredField(name)
                    value.setAccessible(true)
                    return value
                } catch (ignored: NoSuchFieldException) {}
                type = type.getSuperclass()
            }
            throw NoSuchFieldException(name)
        }
    }
}
