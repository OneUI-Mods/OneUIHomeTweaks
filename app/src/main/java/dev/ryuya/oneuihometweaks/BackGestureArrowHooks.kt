package dev.ryuya.oneuihometweaks

import android.app.Application
import android.graphics.Canvas
import android.graphics.Path
import android.os.Build
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

/** Reuses the stock back arrow's unchanged geometry on the verified Samsung ROM. */
internal object BackGestureArrowHooks {
    private const val BACK_PANEL = "com.android.systemui.navigationbar.gestural.BackPanel"
    private const val SAMSUNG_BACK_PANEL =
        "com.android.systemui.navigationbar.gestural.SamsungBackPanel"
    private const val ARROW_PATH_METHOD =
        "calculateArrowPath\$frameworks__base__packages__SystemUI__android_common__SystemUI_core"

    fun install(app: Application, loader: ClassLoader?) {
        if (Build.VERSION.SDK_INT < 37 || !HookCompatibility.matchesClockBuild(37, Build.DISPLAY)) return
        if (Build.VERSION.SDK_INT != 37) return
        if (app.packageName != "com.android.systemui") return

        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (version != 37L) return

            installHook(loader)
        } catch (error: Exception) {
            HookEntry.log("STOCK_BACK_ARROW_PATH_CACHE_UNAVAILABLE ${error.javaClass.simpleName}")
        } catch (error: LinkageError) {
            HookEntry.log("STOCK_BACK_ARROW_PATH_CACHE_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    @RequiresApi(37)
    private fun installHook(loader: ClassLoader?) {
        val backPanel = ModernHook.findClass(BACK_PANEL, loader)
        val samsungBackPanel = ModernHook.findClass(SAMSUNG_BACK_PANEL, loader)
        check(samsungBackPanel.superclass == backPanel)
        val field = backPanel.getDeclaredField("arrowPath").apply { isAccessible = true }
        check(field.type == Path::class.java && Modifier.isFinal(field.modifiers))
        check(!Modifier.isStatic(field.modifiers))
        val buildPath = backPanel.compatibleDeclaredMethod(
            ARROW_PATH_METHOD,
            Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
        )
        check(buildPath.returnType == Path::class.java && Modifier.isFinal(buildPath.modifiers))
        check(!Modifier.isStatic(buildPath.modifiers))

        // ART can inline the small final helper in either stock drawing method.
        for (panelType in listOf(backPanel, samsungBackPanel)) {
            val draw = panelType.compatibleDeclaredMethod("onDraw", Canvas::class.java)
            check(draw.returnType == Void.TYPE)
            check(ModernHook.deoptimize(draw))
        }

        val states = ThreadLocal<ArrowPathState>()
        ModernHook.aroundMethod(buildPath) { chain ->
            val panel = chain.thisObject ?: return@aroundMethod chain.proceed()
            if (panel.javaClass != backPanel && panel.javaClass != samsungBackPanel) {
                return@aroundMethod chain.proceed()
            }
            var state = states.get()
            if (state == null || state.owner.get() !== panel) {
                val path = field.get(panel) as? Path ?: return@aroundMethod chain.proceed()
                state = ArrowPathState(panel, path)
                states.set(state)
            }
            val length = chain.args[0] as Float
            val height = chain.args[1] as Float
            state.unchangedPath(length, height)?.let { return@aroundMethod it }

            val built = chain.proceed()
            state.record(length, height, built)
            built
        }
        HookEntry.log("STOCK_BACK_ARROW_PATH_CACHE_READY")
    }
}

/** One entry for the last panel drawn on a given rendering thread. */
@RequiresApi(34)
internal class ArrowPathState(panel: Any, private val path: Path) {
    val owner = WeakReference(panel)
    private var recorded = false
    private var lengthBits = 0
    private var heightBits = 0
    private var generation = 0

    fun unchangedPath(length: Float, height: Float): Path? {
        if (recorded && lengthBits == length.toRawBits() && heightBits == height.toRawBits() &&
            generation == path.generationId
        ) {
            return path
        }
        return null
    }

    fun record(length: Float, height: Float, built: Any?) {
        recorded = built === path && length.isFinite() && height.isFinite()
        if (recorded) {
            lengthBits = length.toRawBits()
            heightBits = height.toRawBits()
            generation = path.generationId
        }
    }
}
