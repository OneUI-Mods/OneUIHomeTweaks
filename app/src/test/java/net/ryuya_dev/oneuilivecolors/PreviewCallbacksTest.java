package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;
import java.util.HashMap;
import org.junit.Test;

public class PreviewCallbacksTest {
  interface Function {
    Object invoke(Object argument);
  }

  @Test
  public void nativeScopeCanHashCallbackWithoutReceivingKotlinUnitInsteadOfInt() {
    Object unit = new Object();
    Function fn = callback(unit);
    var scopes = new HashMap<Function, String>();
    scopes.put(fn, "native scope");
    assertEquals("native scope", scopes.get(fn));
    assertEquals(fn.hashCode(), fn.hashCode());
    assertSame(unit, fn.invoke(false));
  }

  @Test
  public void differentPreviewScopesDoNotCompareEqualThroughTheirCallback() {
    Object unit = new Object();
    Function first = callback(unit), second = callback(unit);
    assertEquals(first, first);
    assertNotEquals(first, second);
    assertNotEquals(first, null);
    assertEquals("Read-only preview", first.toString());
  }

  private static Function callback(Object result) {
    return (Function)
        PreviewCallbacks.constant(
            PreviewCallbacksTest.class.getClassLoader(),
            Function.class,
            result,
            "Read-only preview");
  }
}
