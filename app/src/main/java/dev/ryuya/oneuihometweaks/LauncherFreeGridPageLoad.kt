package dev.ryuya.oneuihometweaks

import android.os.Looper
import android.os.Trace
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import dev.ryuya.oneuihometweaks.compat.compatibleMethod
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/** Runs the stock initial-page data load on the dispatcher used for the other pages. */
internal class LauncherFreeGridPageLoad {
    private val initialPageRepository = ThreadLocal<Any>()
    private var installed = false

    @Synchronized
    fun install(workspace: Class<*>) {
        if (installed) return
        try {
            val loader = workspace.classLoader ?: return
            fun type(name: String) = DexSymbols.findClass(name, false, loader)

            val name = "com.honeyspace.ui.honeypots.freegrid.data.repository.FreeGridRepositoryImpl"
            val repository = type(name)
            val loadType = type("com.honeyspace.ui.honeypots.freegrid.domain.repository.FreeGridRepository\$LoadType")
            val page = type("com.honeyspace.sdk.database.entity.ItemGroupData")
            val state = type("com.honeyspace.sdk.HoneyState")
            val context = type("kotlin.coroutines.CoroutineContext")
            val continuation = type("kotlin.coroutines.Continuation")
            val block = type("kotlin.jvm.functions.Function2")
            val initialPage = loadType.enumConstants.first { (it as Enum<*>).name == "ONLY_DEFAULT" }
            val main = repository.compatibleDeclaredField("mainImmediateDispatcher").apply { isAccessible = true }
            val background = repository.compatibleMethod("getDefaultDispatcher").apply { isAccessible = true }
            val selectPage = repository.compatibleDeclaredMethod("loadItemByLoadType", loadType, page, state, continuation)
            val loadPage = repository.compatibleDeclaredMethod("loadByPage", page, context, state, Boolean::class.javaPrimitiveType, continuation)
            val makePageLoad = type("$name\$loadByPage\$2").getDeclaredConstructor(
                page, repository, context, Boolean::class.javaPrimitiveType, state, continuation,
            ).apply { isAccessible = true }
            val withContext = type("kotlinx.coroutines.BuildersKt")
                .compatibleMethod("withContext", context, block, continuation).apply { isAccessible = true }
            check(block.isAssignableFrom(makePageLoad.declaringClass) && context.isAssignableFrom(background.returnType))
            check(Modifier.isStatic(withContext.modifiers) && loadPage.returnType == withContext.returnType)
            val bridge = repository.compatibleDeclaredMethod("access\$loadItemByLoadType", repository, loadType, page, state, continuation)
            val collect = type("$name\$get\$1").compatibleDeclaredMethod("invokeSuspend", Any::class.java)
            check(ModernHook.deoptimize(selectPage) && ModernHook.deoptimize(bridge) && ModernHook.deoptimize(collect))

            val redirect = ModernHook.aroundMethod(loadPage, XposedInterface.PRIORITY_DEFAULT, XposedInterface.ExceptionMode.PASSTHROUGH) { chain ->
                val owner = chain.thisObject ?: return@aroundMethod chain.proceed()
                val args = chain.args
                if (initialPageRepository.get() !== owner || args[3] != false || args[1] !== main.get(owner)) {
                    return@aroundMethod chain.proceed()
                }
                val workerContext = background.invoke(owner) ?: return@aroundMethod chain.proceed()
                // The stock block uses its captured context to select icon and folder options.
                // Keep that input while choosing a different execution context for withContext.
                val stockLoad = makePageLoad.newInstance(args[0], owner, args[1], false, args[2], null)
                val tracing = Trace.isEnabled()
                if (tracing) Trace.beginSection("OneUI FreeGrid initial page schedule on worker")
                try {
                    withContext.invoke(null, workerContext, stockLoad, args[4])
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                } finally {
                    if (tracing) Trace.endSection()
                }
            }
            try {
                ModernHook.aroundMethod(selectPage, XposedInterface.PRIORITY_DEFAULT, XposedInterface.ExceptionMode.PASSTHROUGH) { chain ->
                    if (Looper.myLooper() !== Looper.getMainLooper() || chain.args[0] !== initialPage) {
                        return@aroundMethod chain.proceed()
                    }
                    val previous = initialPageRepository.get()
                    initialPageRepository.set(chain.thisObject)
                    try {
                        chain.proceed()
                    } finally {
                        if (previous != null) initialPageRepository.set(previous) else initialPageRepository.remove()
                    }
                }
            } catch (error: Exception) {
                redirect.unhook()
                throw error
            }
            installed = true
            HookEntry.log("FREEGRID_INITIAL_PAGE_WORKER_READY")
        } catch (error: Exception) {
            HookEntry.log("FREEGRID_INITIAL_PAGE_WORKER_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
