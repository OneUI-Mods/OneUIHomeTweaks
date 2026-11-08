package dev.ryuya.oneuihometweaks

import android.animation.ValueAnimator
import android.app.Instrumentation
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import java.lang.ref.WeakReference
import org.json.JSONObject

/** Exercises Android animators without a launcher, hooks, third-party data or test Activity. */
class MemoryInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private data class Fixture(
        val cache: MutableMap<Any?, Any?>,
        val owner: WeakReference<TextView>,
        val state: Any,
        val animator: ValueAnimator,
    )

    private fun field(owner: Any, name: String) =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun fixture(): Fixture {
        val bridge = HomeLabelBridge()
        field(bridge, "enabled").setBoolean(bridge, true)
        @Suppress("UNCHECKED_CAST")
        val cache = field(bridge, "saved").get(bridge) as MutableMap<Any?, Any?>
        val stateClass = Class.forName(HomeLabelBridge::class.java.name + "\$Saved")
        val state = stateClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val text = TextView(targetContext)
        cache[text] = state
        bridge.javaClass.getDeclaredMethod("reveal", TextView::class.java, stateClass, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(bridge, text, state, Color.WHITE)
        val animator = field(state, "animator").get(state) as ValueAnimator
        check(animator.duration == 120L)
        return Fixture(cache, WeakReference(text), state, animator)
    }

    override fun onStart() {
        val results = JSONObject()
        try {
            runOnMainSync {
                for (cancel in listOf(false, true)) {
                    val f = fixture()
                    val text = checkNotNull(f.owner.get())
                    f.animator.setCurrentFraction(0.3f)
                    if (cancel) f.animator.cancel() else f.animator.end()
                    check(field(f.state, "animator").get(f.state) == null)
                    check(f.animator.listeners.isNullOrEmpty())
                    check(text.currentTextColor == Color.WHITE)
                    // A held, finished animator must not keep changing a now separately styled view.
                    text.setTextColor(Color.RED)
                    f.animator.setCurrentFraction(0.2f)
                    check(text.currentTextColor == Color.RED)
                    results.put(if (cancel) "cancel" else "finish", true)
                }
                val f = fixture()
                val text = checkNotNull(f.owner.get())
                f.cache.remove(text)
                text.setTextColor(Color.RED)
                f.animator.cancel()
                check(text.currentTextColor == Color.RED)
                check(field(f.state, "animator").get(f.state) == null)
                results.put("removedOwnerPreserved", true)
            }
            lateinit var f: Fixture
            runOnMainSync { f = fixture().also { it.animator.end() } }
            val deadline = System.nanoTime() + 3_000_000_000L
            while (f.owner.get() != null && System.nanoTime() < deadline) {
                Runtime.getRuntime().gc() // Test-only; never asks Galaxy/SystemUI to collect.
                Thread.sleep(20)
            }
            check(f.owner.get() == null) { "Ended animator retained its view" }
            check(f.cache.isEmpty())
            results.put("weakOwnerCollected", true)
            finish(-1, Bundle().apply { putString("result", results.toString()) })
        } catch (failure: Throwable) {
            finish(0, Bundle().apply { putString("error", failure.stackTraceToString()) })
        }
    }
}
