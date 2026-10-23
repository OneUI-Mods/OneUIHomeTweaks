package dev.ryuya.oneuihometweaks;

import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;

/** BZI9 native Compose controls, their real ViewModels and Samsung composition locals. */
final class QuickPanelPreviewAndroid17 implements QuickPanelNativePreview.Renderer {
  private static final String TILES = "com.android.systemui.samsung.quicksetting.ui.tiles.";
  private final ClassLoader loader;
  private final QuickPanelAndroid17 adapter;
  private final Runnable changed;
  private final Class<?> composer, local, provided, function2, scopeClass;
  private final Method consume, provides, provider, setContent;
  private final Constructor<?> composeView, scopeConstructor;
  private final Object unit, modifier;
  private final Map<String, Method> renderers = new HashMap<>();
  private final List<View> compositions = new ArrayList<>();
  private final Runnable failure;
  private final Set<Object> recomposers = Collections.newSetFromMap(new IdentityHashMap<>());
  private Object runner;
  private Object lifecycle;
  private final Set<Object> previewComposers = Collections.newSetFromMap(new IdentityHashMap<>());
  private final Set<Object> listening = Collections.newSetFromMap(new IdentityHashMap<>());
  private final Object listeningToken = new Object();
  private Method setListening;
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private Object scope, locals, sceneViewModel;

  private record Bound(Object source, Object tile, Object model) {}

  private final Map<String, Bound> bindings = new HashMap<>();
  private final Set<Object> hostLocals = Collections.newSetFromMap(new IdentityHashMap<>());
  private Object lastLocalMap;
  private int lastConfiguration;
  private Object draggingLocal, resizingLocal, dragging, resizing;
  private View sourceView;

  QuickPanelPreviewAndroid17(
      ClassLoader loader, QuickPanelAndroid17 adapter, Runnable changed, Runnable failure)
      throws Exception {
    this.loader = loader;
    this.adapter = adapter;
    this.changed = changed;
    this.failure = failure;
    composer = load("androidx.compose.runtime.Composer");
    local = load("androidx.compose.runtime.CompositionLocal");
    provided = load("androidx.compose.runtime.ProvidedValue");
    function2 = load("kotlin.jvm.functions.Function2");
    consume = composer.getMethod("consume", local);
    provides =
        load("androidx.compose.runtime.ProvidableCompositionLocal")
            .getMethod("provides", Object.class);
    provider =
        load("androidx.compose.runtime.CompositionLocalKt")
            .getMethod(
                "CompositionLocalProvider",
                Array.newInstance(provided, 0).getClass(),
                function2,
                composer,
                int.class);
    Class<?> view = load("androidx.compose.ui.platform.ComposeView");
    composeView = view.getConstructor(android.content.Context.class);
    setContent = view.getMethod("setContent", function2);
    unit = load("kotlin.Unit").getField("INSTANCE").get(null);
    modifier = load("androidx.compose.ui.Modifier").getField("Companion").get(null);
    scopeClass =
        load(
            "com.android.systemui.samsung.quicksetting.ui.base.spannablegrid.SpannableGridTileScopeImpl");
    scopeConstructor =
        Arrays.stream(scopeClass.getConstructors())
            .filter(c -> c.getParameterCount() == 13)
            .findFirst()
            .orElseThrow();
    // The embedded window supplies its own view, context and lifecycle. Inherit the native
    // Samsung theme and scene locals, including locals required only by the larger tiles.
    for (String name :
        new String[] {
          "androidx.compose.ui.platform.AndroidCompositionLocals_androidKt",
          "androidx.compose.ui.platform.CompositionLocalsKt",
          "androidx.lifecycle.compose.LocalLifecycleOwnerKt",
          "androidx.savedstate.compose.LocalSavedStateRegistryOwnerKt"
        }) {
      for (Field field : load(name).getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
            && local.isAssignableFrom(field.getType())) {
          field.setAccessible(true);
          hostLocals.add(field.get(null));
        }
      }
    }
    Class<?> editable =
        load("com.android.systemui.samsung.quicksetting.ui.base.editable.EditableItemKt");
    draggingLocal = editable.getField("LocalDragging").get(null);
    resizingLocal = editable.getField("LocalResizing").get(null);
    hostLocals.add(draggingLocal);
    hostLocals.add(resizingLocal);
    dragging =
        load("com.android.systemui.samsung.quicksetting.ui.base.editable.DraggingInfo")
            .getConstructor()
            .newInstance();
    resizing =
        load("com.android.systemui.samsung.quicksetting.ui.base.editable.ResizingInfo")
            .getConstructor()
            .newInstance();
    Method collectLive =
        load("androidx.lifecycle.compose.FlowExtKt")
            .getMethod(
                "collectAsStateWithLifecycle",
                load("kotlinx.coroutines.flow.StateFlow"),
                composer,
                int.class);
    Method controlled =
        load("com.android.systemui.samsung.common.CollectStateKt")
            .getMethod(
                "collectAsStateWithControl",
                load("kotlinx.coroutines.flow.StateFlow"),
                load("kotlinx.coroutines.flow.StateFlow"),
                composer);
    ModernHook.hookMethod(
        controlled,
        new ModernHook.Callback() {
          @Override
          protected void beforeHookedMethod(ModernHook.Param p) {
            if (previewComposers.contains(p.args[2])) {
              try {
                p.setResult(collectLive.invoke(null, p.args[0], p.args[2], 0));
              } catch (ReflectiveOperationException error) {
                previewFailed(error);
              }
            }
          }
        });
    setListening =
        load("com.android.systemui.plugins.qs.QSTile")
            .getMethod("setListening", Object.class, boolean.class);
    for (String callSite :
        new String[] {"IconProgressBarKt", "IconProgressBarWithTileKt$$ExternalSyntheticLambda0"}) {
      for (Method method : load(TILES + callSite).getDeclaredMethods())
        if (method.getName().equals("invoke") || method.getName().startsWith("IconProgressBar-"))
          ModernHook.deoptimize(method);
    }
    Class<?> recomposer = load("androidx.compose.runtime.Recomposer");
    Method initial =
        Arrays.stream(recomposer.getDeclaredMethods())
            .filter(m -> m.getName().equals("composeInitial$runtime"))
            .findFirst()
            .orElseThrow();
    ModernHook.aroundMethod(
        initial,
        chain -> {
          if (!recomposers.contains(chain.getThisObject())) return chain.proceed();
          try {
            return chain.proceed();
          } catch (Throwable error) {
            previewFailed(error);
            return null;
          }
        });

    for (String name :
        new String[] {
          "BrightBar", "VolumeBar", "BrightBarWithTile", "VolumeBarWithTile", "QuickTile"
        }) {
      Method method =
          Arrays.stream(load(TILES + name + "Kt").getDeclaredMethods())
              .filter(
                  m ->
                      m.getName().equals(name)
                          && m.getParameterCount() == (name.equals("QuickTile") ? 8 : 5))
              .findFirst()
              .orElseThrow();
      renderers.put(name, method);
      ModernHook.hookMethod(
          method,
          new ModernHook.Callback() {
            @Override
            protected void beforeHookedMethod(ModernHook.Param p) {
              try {
                if (scopeClass.getField("parentGridScope").get(p.args[0]) == null) return;
                scope = p.args[0];
                Object c = p.args[name.equals("QuickTile") ? 5 : 3];
                Object viewKey =
                    load("androidx.compose.ui.platform.AndroidCompositionLocals_androidKt")
                        .getField("LocalView")
                        .get(null);
                sourceView = (View) consume.invoke(c, viewKey);
                Map<?, ?> map =
                    (Map<?, ?>) composer.getMethod("getCurrentCompositionLocalMap").invoke(c);
                int configuration = sourceView.getResources().getConfiguration().hashCode();
                if (map != lastLocalMap || configuration != lastConfiguration) {
                  Class<?> scene =
                      load("com.android.systemui.samsung.quicksetting.ui.SecQuickSettingSceneKt");
                  sceneViewModel = consume.invoke(c, scene.getField("LocalViewModel").get(null));
                  List<Object> inherited = new ArrayList<>();
                  for (Object key : map.keySet()) {
                    if (!hostLocals.contains(key))
                      inherited.add(provides.invoke(key, consume.invoke(c, key)));
                  }
                  // Normal tiles require edit context even outside the editor. Keep it private and
                  // idle.
                  inherited.add(provides.invoke(draggingLocal, dragging));
                  inherited.add(provides.invoke(resizingLocal, resizing));
                  Object values = Array.newInstance(provided, inherited.size());
                  for (int i = 0; i < inherited.size(); i++) Array.set(values, i, inherited.get(i));
                  locals = values;
                  lastLocalMap = map;
                  lastConfiguration = configuration;
                }
                changed.run();
              } catch (Throwable error) {
                HookEntry.log("QUICK_PANEL_PREVIEW_SOURCE " + error.getClass().getSimpleName());
              }
            }
          });
    }
  }

  private Class<?> load(String name) throws ClassNotFoundException {
    return Class.forName(name, false, loader);
  }

  @Override
  public boolean ready() {
    try {
      return scope != null
          && locals != null
          && sourceView != null
          && !adapter.previewEntries().isEmpty();
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public void populate(FrameLayout root) throws Exception {
    Object parent = newRecomposer();
    createOwner(root);
    LinearLayout row = new LinearLayout(root.getContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    long cell = scopeClass.getField("cellSize").getLong(scope);
    float cellWidth = Float.intBitsToFloat((int) (cell >>> 32));
    float cellHeight = Float.intBitsToFloat((int) cell);
    if (!Float.isFinite(cellWidth)
        || cellWidth <= 0
        || !Float.isFinite(cellHeight)
        || cellHeight <= 0) throw new IllegalStateException("Invalid native cell size");
    int nativeWidth = Math.round(cellWidth * 4), nativeHeight = Math.round(cellHeight * 4);
    FrameLayout.LayoutParams region =
        new FrameLayout.LayoutParams(nativeWidth, nativeHeight, Gravity.CENTER);
    root.addView(row, region);
    row.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          float fit =
              Math.min(
                  1f,
                  Math.min(
                      root.getWidth() / (float) nativeWidth,
                      root.getHeight() / (float) nativeHeight));
          row.setScaleX(fit);
          row.setScaleY(fit);
        });
    Map<Integer, Object[]> columns = new HashMap<>();
    for (Object entry : adapter.previewEntries()) {
      Object model = entry.getClass().getField("gridTileItem").get(entry);
      String type = (String) model.getClass().getMethod("getType").invoke(model);
      String spec =
          type.equals("QuickTile") ? (String) model.getClass().getField("spec").get(model) : "";
      int kind =
          type.startsWith("BrightBar") || spec.equals("UiModeNight")
              ? 0
              : type.startsWith("VolumeBar") || spec.equals("SoundMode") ? 1 : -1;
      if (kind < 0) continue;
      Object[] column = columns.computeIfAbsent(kind, k -> new Object[2]);
      column[type.equals("QuickTile") ? 1 : 0] = entry;
    }
    for (int i = 0; i < 2; i++) {
      Object[] entries = columns.get(i);
      if (entries == null || entries[0] == null) continue;
      LinearLayout column = new LinearLayout(root.getContext());
      column.setOrientation(LinearLayout.VERTICAL);
      row.addView(column, new LinearLayout.LayoutParams(0, -1, 1));
      for (int j = 0; j < 2; j++) {
        Object entry = entries[j];
        if (entry == null) continue;
        Object model = bind(entry.getClass().getField("gridTileItem").get(entry));
        String type = (String) model.getClass().getMethod("getType").invoke(model);
        int w = entry.getClass().getField("spanWidth").getInt(entry),
            h = entry.getClass().getField("spanHeight").getInt(entry);
        Object sampleScope = cloneScope(w, h);
        adapter.previewScope(sampleScope, i, type.endsWith("WithTile"));
        Object tileModel =
            type.endsWith("WithTile")
                ? model.getClass().getField("tile").get(model)
                : type.equals("QuickTile") ? model : null;
        if (tileModel != null) {
          Object tile = tileModel.getClass().getField("tile").get(tileModel);
          if (listening.add(tile)) {
            setListening.invoke(tile, listeningToken, true);
          }
        }
        Method draw = renderers.get(type);
        if (draw == null) continue;
        View view = (View) composeView.newInstance(sourceView.getContext());
        view.setId(View.generateViewId());
        view.getClass()
            .getMethod(
                "setParentCompositionContext", load("androidx.compose.runtime.CompositionContext"))
            .invoke(view, parent);
        compositions.add(view);
        Object sampleLocals = locals;
        Object content =
            lambda(
                (c, flags) -> {
                  previewComposers.add(c);
                  Object body =
                      lambda(
                          (inner, ignored) -> {
                            previewComposers.add(inner);
                            try {
                              if (type.equals("QuickTile"))
                                draw.invoke(
                                    null, sampleScope, modifier, model, false, false, inner, 1, 0);
                              else draw.invoke(null, sampleScope, modifier, model, inner, 1);
                            } catch (InvocationTargetException error) {
                              throw new IllegalStateException(
                                  "Native preview composition failed", error.getCause());
                            }
                          });
                  provider.invoke(null, sampleLocals, body, c, 0);
                });
        setContent.invoke(view, content);
        column.addView(
            view,
            new LinearLayout.LayoutParams(-1, 0, type.endsWith("WithTile") ? 4 : j == 0 ? 3 : 1));
      }
    }
  }

  private Object bind(Object source) throws Exception {
    String type = (String) source.getClass().getMethod("getType").invoke(source);
    boolean group = type.endsWith("WithTile");
    Object tileModel =
        group
            ? source.getClass().getField("tile").get(source)
            : type.equals("QuickTile") ? source : null;
    if (tileModel == null) return source;
    String spec = (String) tileModel.getClass().getField("spec").get(tileModel);
    Object host = sceneViewModel.getClass().getField("qsHost").get(sceneViewModel);
    Collection<?> tiles =
        (Collection<?>) load("com.android.systemui.qs.QSHost").getMethod("getTiles").invoke(host);
    Class<?> qsTile = load("com.android.systemui.plugins.qs.QSTile");
    Method getSpec = qsTile.getMethod("getTileSpec");
    Object actual = null;
    for (Object tile : tiles)
      if (spec.equals(getSpec.invoke(tile))) {
        actual = tile;
        break;
      }
    if (actual == null) throw new IllegalStateException("Native tile not ready: " + spec);
    String key = "tile:" + spec;
    Bound cached = bindings.get(key);
    Object boundTile;
    if (cached != null && cached.source == tileModel && cached.tile == actual)
      boundTile = cached.model;
    else {
      Method copy =
          Arrays.stream(tileModel.getClass().getDeclaredMethods())
              .filter(m -> m.getName().startsWith("copy-") && m.getParameterCount() == 5)
              .findFirst()
              .orElseThrow();
      boundTile = copy.invoke(null, tileModel, actual, null, null, 160);
      bindings.put(key, new Bound(tileModel, actual, boundTile));
    }
    if (!group) return boundTile;
    key = "group:" + type;
    cached = bindings.get(key);
    if (cached != null && cached.source == source && cached.tile == boundTile) return cached.model;
    Method copy =
        Arrays.stream(source.getClass().getDeclaredMethods())
            .filter(m -> m.getName().startsWith("copy-") && m.getParameterCount() == 2)
            .findFirst()
            .orElseThrow();
    Object model = copy.invoke(null, source, boundTile);
    bindings.put(key, new Bound(source, boundTile, model));
    return model;
  }

  private void previewFailed(Throwable error) {
    HookEntry.log("QUICK_PANEL_PREVIEW_COMPOSITION " + android.util.Log.getStackTraceString(error));
    main.post(failure);
  }

  private Object newRecomposer() throws Exception {
    Class<?> ctx = load("kotlin.coroutines.CoroutineContext");
    Class<?> handler = load("kotlinx.coroutines.CoroutineExceptionHandler");
    Object key = handler.getField("Key").get(null);
    Object empty = load("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
    Method plus = ctx.getMethod("plus", ctx);
    Object errors =
        Proxy.newProxyInstance(
            loader,
            new Class<?>[] {handler},
            (p, m, a) ->
                switch (m.getName()) {
                  case "getKey" -> key;
                  case "get" -> a[0] == key ? p : null;
                  case "minusKey" -> a[0] == key ? empty : p;
                  case "fold" -> {
                    yield function2
                        .getMethod("invoke", Object.class, Object.class)
                        .invoke(a[1], a[0], p);
                  }
                  case "plus" ->
                      load("kotlin.coroutines.CoroutineContext$DefaultImpls")
                          .getMethod("plus", ctx, ctx)
                          .invoke(null, p, a[0]);
                  case "handleException" -> {
                    for (Object arg : a) if (arg instanceof Throwable t) previewFailed(t);
                    yield null;
                  }
                  case "hashCode" -> System.identityHashCode(p);
                  case "equals" -> p == a[0];
                  default -> "Quick Panel preview exception handler";
                });
    Object lazy =
        load("androidx.compose.ui.platform.AndroidUiDispatcher")
            .getField("Main$delegate")
            .get(null);
    Object context = load("kotlin.Lazy").getMethod("getValue").invoke(lazy);
    context = plus.invoke(context, errors);
    Class<?> recomposerClass = load("androidx.compose.runtime.Recomposer");
    Object recomposer = recomposerClass.getConstructor(ctx).newInstance(context);
    recomposers.add(recomposer);
    runner =
        load("kotlinx.coroutines.CoroutineScopeKt")
            .getMethod("CoroutineScope", ctx)
            .invoke(null, context);
    Method run =
        recomposerClass.getMethod(
            "runRecomposeAndApplyChanges", load("kotlin.coroutines.Continuation"));
    Object block =
        Proxy.newProxyInstance(
            loader,
            new Class<?>[] {function2},
            (p, m, a) -> {
              if (m.getDeclaringClass() == Object.class)
                return switch (m.getName()) {
                  case "hashCode" -> System.identityHashCode(p);
                  case "equals" -> p == a[0];
                  default -> "Quick Panel preview recomposer";
                };
              try {
                return run.invoke(recomposer, a[1]);
              } catch (InvocationTargetException error) {
                throw error.getCause();
              }
            });
    Class<?> coroutineScope = load("kotlinx.coroutines.CoroutineScope"),
        start = load("kotlinx.coroutines.CoroutineStart");
    Object defaultStart = Enum.valueOf((Class) start, "DEFAULT");
    load("kotlinx.coroutines.BuildersKt")
        .getMethod("launch", coroutineScope, ctx, start, function2)
        .invoke(null, runner, empty, defaultStart, block);
    return recomposer;
  }

  private Object cloneScope(int w, int h) throws Exception {
    Object expand =
        PreviewCallbacks.constant(
            loader, load("kotlin.jvm.functions.Function1"), unit, "Read-only preview expansion");
    return scopeConstructor.newInstance(
        scopeClass.getField("cellSize").getLong(scope),
        false,
        false,
        false,
        false,
        true,
        false,
        1f,
        null,
        ((long) w << 32) | (h & 0xffffffffL),
        false,
        expand,
        null);
  }

  private void createOwner(View root) throws Exception {
    Class<?> lifecycleOwner = load("androidx.lifecycle.LifecycleOwner"),
        savedOwner = load("androidx.savedstate.SavedStateRegistryOwner");
    Object[] state = new Object[2];
    Object owner =
        Proxy.newProxyInstance(
            loader,
            new Class<?>[] {savedOwner},
            (p, m, a) ->
                switch (m.getName()) {
                  case "getLifecycle" -> state[0];
                  case "getSavedStateRegistry" -> state[1];
                  case "hashCode" -> System.identityHashCode(p);
                  case "equals" -> p == a[0];
                  default -> "Quick Panel preview lifecycle";
                });
    lifecycle =
        load("androidx.lifecycle.LifecycleRegistry")
            .getConstructor(lifecycleOwner)
            .newInstance(owner);
    state[0] = lifecycle;
    Class<?> controllerType = load("androidx.savedstate.SavedStateRegistryController");
    Object controller =
        load("androidx.savedstate.SavedStateRegistryController$Companion")
            .getMethod("create", savedOwner)
            .invoke(null, owner);
    state[1] = controllerType.getField("savedStateRegistry").get(controller);
    controllerType.getMethod("performAttach").invoke(controller);
    controllerType
        .getMethod("performRestore", android.os.Bundle.class)
        .invoke(controller, new Object[] {null});
    load("androidx.lifecycle.ViewTreeLifecycleOwner")
        .getMethod("set", lifecycleOwner, View.class)
        .invoke(null, owner, root);
    load("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
        .getMethod("set", View.class, savedOwner)
        .invoke(null, root, owner);
    lifecycleState("RESUMED");
  }

  private void lifecycleState(String name) throws Exception {
    if (lifecycle == null) return;
    Class<?> state = load("androidx.lifecycle.Lifecycle$State");
    lifecycle
        .getClass()
        .getMethod("setCurrentState", state)
        .invoke(lifecycle, Enum.valueOf((Class) state, name));
  }

  private interface Composition {
    void draw(Object composer, Object flags) throws Throwable;
  }

  private Object lambda(Composition draw) {
    return Proxy.newProxyInstance(
        loader,
        new Class<?>[] {function2},
        (p, m, a) -> {
          if (m.getDeclaringClass() == Object.class)
            return switch (m.getName()) {
              case "hashCode" -> System.identityHashCode(p);
              case "equals" -> p == a[0];
              default -> "Native Quick Panel preview";
            };
          draw.draw(a[0], a[1]);
          return unit;
        });
  }

  @Override
  public void clear(FrameLayout root) {
    for (View view : compositions)
      try {
        view.getClass().getMethod("disposeComposition").invoke(view);
      } catch (ReflectiveOperationException ignored) {
      }
    compositions.clear();
    previewComposers.clear();
    try {
      lifecycleState("DESTROYED");
    } catch (Exception ignored) {
    }
    lifecycle = null;
    for (Object tile : listening)
      try {
        setListening.invoke(tile, listeningToken, false);
      } catch (ReflectiveOperationException ignored) {
      }
    listening.clear();
    for (Object recomposer : recomposers)
      try {
        recomposer.getClass().getMethod("cancel").invoke(recomposer);
      } catch (ReflectiveOperationException ignored) {
      }
    recomposers.clear();
    if (runner != null)
      try {
        load("kotlinx.coroutines.CoroutineScopeKt")
            .getMethod(
                "cancel",
                load("kotlinx.coroutines.CoroutineScope"),
                java.util.concurrent.CancellationException.class)
            .invoke(null, runner, null);
      } catch (ReflectiveOperationException ignored) {
      }
    runner = null;
    root.removeAllViews();
  }
}
