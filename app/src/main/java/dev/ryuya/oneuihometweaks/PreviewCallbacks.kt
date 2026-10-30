package dev.ryuya.oneuihometweaks

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.jvm.JvmStatic

/* Read-only Kotlin callback with normal identity semantics when native scopes use it as a key. */
object PreviewCallbacks {
    @JvmStatic
    fun constant(loader: ClassLoader?, function: Class<*>?, result: Any?, label: String?): Any? {
        return Proxy.newProxyInstance(
            loader,
            arrayOf<Class<*>?>(function),
            InvocationHandler { proxy: Any?, method: Method?, args: Array<Any?>? ->
                if (method!!.getDeclaringClass() == Any::class.java)
                    return@InvocationHandler when (method!!.getName()) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args!![0]
                        else -> label
                    }
                result
            },
        )
    }
}
