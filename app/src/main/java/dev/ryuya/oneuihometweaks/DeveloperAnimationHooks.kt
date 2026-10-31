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
    private var failed: Boolean = false
    private var dialog: AnimationScaleDialog? = null

    fun install(context: Context, loader: ClassLoader?) {
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
            val preference = Class.forName("androidx.preference.Preference", false, loader)
            val dialogPreference =
                Class.forName("androidx.preference.DialogPreference", false, loader)
            for (i in DeveloperAnimationHooks.CONTROLLERS!!.indices) {
                val index: Int = i
                val owner =
                    Class.forName(
                        "com.android.settings.development." +
                            DeveloperAnimationHooks.CONTROLLERS[i],
                        false,
                        loader,
                    )
                val update = owner.getDeclaredMethod(DeveloperAnimationHooks.UPDATE!![i])
                val changed =
                    owner.getDeclaredMethod("onPreferenceChange", preference, Any::class.java)
                val selected = DeveloperAnimationHooks.field(owner, "mPreference")
                val manager = DeveloperAnimationHooks.field(owner, "mWindowManager")
                val read =
                    manager!!.getType().getMethod("getAnimationScale", Int::class.javaPrimitiveType)
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
                                                preference.getMethod("getKey").invoke(item)
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
                                    preference.getMethod("getContext").invoke(call.thisObject)
                                        as Context?
                                val title: CharSequence? =
                                    preference.getMethod("getTitle").invoke(call.thisObject)
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
            (context as Application).registerActivityLifecycleCallbacks(
                object : ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, state: Bundle?) {}

                    override fun onActivityStarted(activity: Activity) {}

                    override fun onActivityResumed(activity: Activity) {}

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
            )
            val worker = HandlerThread("OneUiSettingsConfig")
            worker.start()
            context
                .getContentResolver()
                .registerContentObserver(
                    ConfigProvider.URI!!,
                    false,
                    object : ContentObserver(Handler(worker.getLooper())) {
                        override fun onChange(self: Boolean) {
                            main.post(Runnable { this@DeveloperAnimationHooks.readConfig() })
                        }
                    },
                )
            HookEntry.log("ANIMATION_EDITOR_READY sdk=37")
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun closeDialog() {
        if (dialog == null) return
        dialog!!.dismiss()
        dialog = null
    }

    private fun readConfig() {
        if (failed) return
        try {
            context!!
                .getContentResolver()
                .query(ConfigProvider.URI!!, null, null, null, null)
                .use { c ->
                    check(!(c == null || !c.moveToFirst())) { "Missing settings" }
                    enabled =
                        c.getInt(c.getColumnIndexOrThrow("enabled")) != 0 &&
                            c.getInt(c.getColumnIndexOrThrow("settings_enabled")) != 0
                    if (!enabled) closeDialog()
                    for (binding in
                        ArrayList<DeveloperAnimationHooks.Binding?>(bindings.values)) binding!!
                        .refresh()
                    HostStatus.report(
                        context,
                        if (enabled) "ACTIVE" else "OFF",
                        "Developer animation scale editor",
                    )
                }
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
        HostStatus.report(
            context,
            if (error is UnsupportedOperationException) "UNSUPPORTED" else "ERROR",
            "Animation editor: " + error.javaClass.getSimpleName(),
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
            val context: Context? = item.javaClass.getMethod("getContext").invoke(item) as Context?
            val summary: String =
                (AnimationScaleValue.format(
                    AnimationScaleValue.ticks(value()),
                    context!!.getResources().getConfiguration().getLocales().get(0),
                ) + "×")
            item.javaClass.getMethod("setSummary", CharSequence::class.java).invoke(item, summary)
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
            check(JavaBoolean.TRUE == item.javaClass.getMethod("isEnabled").invoke(item)) {
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
                    val value = type.getDeclaredField(name)
                    value.setAccessible(true)
                    return value
                } catch (ignored: NoSuchFieldException) {}
                type = type.getSuperclass()
            }
            throw NoSuchFieldException(name)
        }
    }
}
