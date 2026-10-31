package dev.ryuya.oneuihometweaks

import java.util.HashMap
import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test

open class PreviewCallbacksTest {
    fun interface Function {
        fun invoke(argument: Any?): Any?
    }

    @Test
    open fun nativeScopeCanHashCallbackWithoutReceivingKotlinUnitInsteadOfInt() {
        val unit = Any()
        val fn = PreviewCallbacksTest.callback(unit)
        val scopes = HashMap<PreviewCallbacksTest.Function?, String?>()
        scopes.put(fn, "native scope")
        assertEquals("native scope", scopes.get(fn))
        assertEquals(fn.hashCode(), fn.hashCode())
        assertSame(unit, fn!!.invoke(false))
    }

    @Test
    open fun differentPreviewScopesDoNotCompareEqualThroughTheirCallback() {
        val unit = Any()
        val first = PreviewCallbacksTest.callback(unit)
        val second = PreviewCallbacksTest.callback(unit)
        assertEquals(first, first)
        assertNotEquals(first, second)
        assertNotEquals(first, null)
        assertEquals("Read-only preview", first.toString())
    }

    companion object {
        @JvmStatic
        private fun callback(result: Any?): PreviewCallbacksTest.Function? {
            return PreviewCallbacks.constant(
                PreviewCallbacksTest::class.java.getClassLoader(),
                PreviewCallbacksTest.Function::class.java,
                result,
                "Read-only preview",
            ) as PreviewCallbacksTest.Function?
        }
    }
}
