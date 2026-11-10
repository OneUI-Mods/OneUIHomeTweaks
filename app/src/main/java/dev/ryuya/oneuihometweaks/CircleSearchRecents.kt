package dev.ryuya.oneuihometweaks

import android.app.ActivityManager.RecentTaskInfo
import android.content.Intent
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

/** Filters only the contextual-search root task, never all tasks belonging to Google. */
internal object CircleSearchTaskPolicy {
    private const val GOOGLE = "com.google.android.googlequicksearchbox"
    private const val ENTRY = "com.google.android.apps.lens.ContextualSearchEntrypoint"
    private const val ACTION = "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH"

    fun matches(packageName: String?, className: String?, action: String?): Boolean =
        packageName == GOOGLE && (className == ENTRY || action == ACTION)

    // Keep mixed split-screen/desktop groups intact instead of hiding unrelated apps.
    fun hideGroup(members: List<Boolean>): Boolean = members.isNotEmpty() && members.all { it }
}

// Runs only in the scoped, privileged Xposed launcher host; never in the manager process.
@android.annotation.SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
internal class CircleSearchRecents {
    @Volatile private var enabled = false
    private var ready = false
    private val filtered = AtomicInteger()

    fun setEnabled(value: Boolean) { enabled = value }
    fun diagnostic(): String = "${if (ready) "ready" else "unavailable"}; hidden=${filtered.get()}"

    fun install(loader: ClassLoader?) {
        var installed = 0
        // Android 16's classic path; framework APIs require no APK/DexKit discovery.
        runCatching {
            val owner = Class.forName("android.app.ActivityTaskManager", false, loader)
            val method = owner.getDeclaredMethod("getRecentTasks", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            check(List::class.java.isAssignableFrom(method.returnType))
            ModernHook.hookMethod(method, callback { result ->
                (result as? List<*>)?.let { tasks ->
                    val kept = tasks.filterNot(::isSearchTask)
                    if (kept.size == tasks.size) null else kept
                }
            })
            installed++
        }.onFailure { ModernHook.log("CIRCLE_RECENTS_CLASSIC_UNAVAILABLE ${it.javaClass.simpleName}") }

        // Samsung's grouped Shell task source is resolved once by the module-owned DexKit cache.
        runCatching {
            val source = DexSymbols.findClass("circle.recents.TaskSource", false, loader)
            val method = source.declaredMethods.single { method ->
                method.name == "getRecentTasks" && !Modifier.isAbstract(method.modifiers) &&
                    method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)) &&
                    method.returnType.isArray
            }
            val group = method.returnType.componentType!!
            val list = group.methods.singleOrNull { it.name == "getTaskInfoList" && it.parameterCount == 0 }
            val individual = group.methods.filter {
                it.name in setOf("getTaskInfo1", "getTaskInfo2") && it.parameterCount == 0
            }
            check(list != null || individual.isNotEmpty())
            ModernHook.hookMethod(method, callback { result ->
                if (result == null) return@callback null
                val count = ReflectArray.getLength(result)
                val kept = (0 until count).map { ReflectArray.get(result, it) }.filterNot { item ->
                    val members = if (item == null) emptyList() else if (list != null)
                        (list.invoke(item) as? List<*>)?.filterNotNull().orEmpty()
                    else individual.mapNotNull { it.invoke(item) }
                    CircleSearchTaskPolicy.hideGroup(members.map(::isSearchTask))
                }
                if (kept.size == count) null else ReflectArray.newInstance(group, kept.size).also { array ->
                    kept.forEachIndexed { index, item -> ReflectArray.set(array, index, item) }
                }
            })
            installed++
        }.onFailure { ModernHook.log("CIRCLE_RECENTS_GROUPED_UNAVAILABLE ${it.javaClass.simpleName}") }
        ready = installed > 0
        ModernHook.log("CIRCLE_RECENTS_READY paths=$installed")
    }

    private fun callback(filter: (Any?) -> Any?) = object : ModernHook.Callback() {
        override fun afterHookedMethod(param: ModernHook.Param) {
            if (!enabled || param.hasThrowable()) return
            runCatching { filter(param.getResult()) }.onSuccess { replacement ->
                if (replacement != null) {
                    param.setResult(replacement)
                    val count = filtered.incrementAndGet()
                    if (count == 1) ModernHook.log("CIRCLE_RECENTS_FILTERED")
                }
            }.onFailure { ModernHook.log("CIRCLE_RECENTS_FILTER_ERROR ${it.javaClass.simpleName}") }
        }
    }

    private fun isSearchTask(task: Any?): Boolean {
        val intent = when (task) {
            null -> return false
            is RecentTaskInfo -> task.baseIntent
            else -> task.javaClass.getField("baseIntent").get(task) as? Intent
        } ?: return false
        val component = intent.component
        return CircleSearchTaskPolicy.matches(component?.packageName ?: intent.`package`,
            component?.className, intent.action)
    }
}
