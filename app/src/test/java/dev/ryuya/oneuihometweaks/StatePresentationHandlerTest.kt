package dev.ryuya.oneuihometweaks

import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class StatePresentationHandlerTest {
    interface State {
        fun getValue(): Any?
    }

    private fun state(value: () -> Any?): State =
        Proxy.newProxyInstance(
            State::class.java.classLoader,
            arrayOf(State::class.java),
            object : StatePresentationHandler() {
                override fun readValue(): Any? = value()
            },
        ) as State

    @Test
    fun zeroArgumentGetValueUsesTheRealJavaProxyNullArgumentArray() {
        val values = listOf("brightness", "sound")
        val proxy = state { values }
        assertSame(values, proxy.getValue())
    }

    @Test
    fun zeroArgumentObjectMethodsAndIdentityEqualityDoNotReadTheState() {
        val proxy = state { throw AssertionError("Object method must not read State") }
        assertEquals("One UI legacy panel presentation", proxy.toString())
        assertEquals(System.identityHashCode(proxy), proxy.hashCode())
        assertTrue(proxy == proxy)
        assertFalse(proxy.equals(Any()))
        assertFalse(proxy.equals(null))
    }

    @Test
    fun nullStateValuesRemainNull() {
        assertNull(state { null }.getValue())
    }
}
