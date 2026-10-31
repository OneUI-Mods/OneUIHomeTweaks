package dev.ryuya.homefixture

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.honeyspace.ui.honeypots.workspace.presentation.FakeWorkspace
import dalvik.system.PathClassLoader
import java.lang.reflect.Modifier

/** Runs against the release APK in app_process; creates no window and changes no settings. */
object HomeLabelCollectionProbe {
    class Icon(context: Context) : TextView(context) {
        override fun isShown() = true

        override fun getGlobalVisibleRect(rect: Rect, offset: Point?): Boolean {
            rect.set(0, 0, 600, 300)
            offset?.set(0, 0)
            return true
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        // A standalone Android 17 app_process does not inherit this Zygote handle.
        val sharedType = Class.forName("com.android.internal.os.ApplicationSharedMemory")
        val shared = sharedType.getDeclaredMethod("create").invoke(null)
        sharedType.declaredFields
            .single { it.type == sharedType && Modifier.isStatic(it.modifiers) }
            .apply { isAccessible = true }
            .set(null, shared)
        val thread = Class.forName("android.app.ActivityThread")
        val instance = thread.getDeclaredMethod("systemMain").invoke(null)
        val context = thread.getDeclaredMethod("getSystemContext").invoke(instance) as Context
        Class.forName("android.graphics.Typeface")
            .getDeclaredMethod("loadPreinstalledSystemFontMap")
            .apply { isAccessible = true }
            .invoke(null)
        val loader =
            PathClassLoader(args.single(), HomeLabelCollectionProbe::class.java.classLoader)
        val type = Class.forName("dev.ryuya.oneuihometweaks.HomeLabelBridge", true, loader)
        val bridge = type.getConstructor().newInstance()
        type
            .getDeclaredField("iconClass")
            .apply { isAccessible = true }
            .set(bridge, Icon::class.java)
        val collect =
            type
                .getDeclaredMethod("collect", View::class.java, Rect::class.java, List::class.java)
                .apply { isAccessible = true }
        val remember =
            type.getDeclaredMethod("remember", TextView::class.java).apply { isAccessible = true }
        val icon =
            Icon(context).apply {
                text = "Home label"
                textSize = 20f
            }
        val workspace = FakeWorkspace(context)
        workspace.addView(icon)
        workspace.layout(0, 0, 600, 300)
        icon.measure(
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        icon.layout(0, 0, 500, 200)

        fun previous(): Int? {
            val targets = ArrayList<Any>()
            collect.invoke(bridge, icon, Rect(0, 0, 600, 300), targets)
            check(targets.size == 1) { "Expected one actual text ink target, got ${targets.size}" }
            val target = targets.single()
            return target.javaClass
                .getDeclaredField("previous")
                .apply { isAccessible = true }
                .get(target) as Int?
        }

        check(previous() == null)
        println("PASS unseen label has no previous color")
        val saved = remember.invoke(bridge, icon)
        val applied = saved.javaClass.getDeclaredField("applied").apply { isAccessible = true }
        check(previous() == null)
        println("PASS tracked label awaiting its first sample remains collectible")
        for (color in intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.WHITE)) {
            applied.set(saved, color)
            check(previous() == color)
        }
        println("PASS pending transparent and established black/white colors are preserved")
        applied.set(saved, null)
        check(previous() == null)
        check(previous() == null)
        println("PASS timeout-restored label can be sampled repeatedly")
    }
}
