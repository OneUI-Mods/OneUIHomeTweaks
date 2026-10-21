package net.ryuya_dev.oneuilivecolors;

import java.lang.reflect.Proxy;

/** Read-only Kotlin callback with normal identity semantics when native scopes use it as a key. */
final class PreviewCallbacks {
  static Object constant(ClassLoader loader, Class<?> function, Object result, String label) {
    return Proxy.newProxyInstance(
        loader,
        new Class<?>[] {function},
        (proxy, method, args) -> {
          if (method.getDeclaringClass() == Object.class)
            return switch (method.getName()) {
              case "hashCode" -> System.identityHashCode(proxy);
              case "equals" -> proxy == args[0];
              default -> label;
            };
          return result;
        });
  }
}
