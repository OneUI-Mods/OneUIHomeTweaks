package dev.ryuya.oneuihometweaks

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/** Java Proxy passes a null argument array to zero-argument State and Object methods. */
internal abstract class StatePresentationHandler : InvocationHandler {
    protected abstract fun readValue(): Any?

    final override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? =
        when (method.name) {
            "getValue" -> readValue()
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "One UI legacy panel presentation"
            else -> throw UnsupportedOperationException(method.name)
        }
}
