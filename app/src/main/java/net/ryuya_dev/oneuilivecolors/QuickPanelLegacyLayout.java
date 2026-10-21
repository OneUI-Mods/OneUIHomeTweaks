package net.ryuya_dev.oneuilivecolors;

import java.lang.reflect.*;
import java.util.*;

/** Reuses BZI9's retained BarWithTile renderer through an immutable presentation list. */
final class QuickPanelLegacyLayout {
  private static final String MODEL = "com.android.systemui.samsung.quicksetting.domain.model.";
  private static final String PANEL = "com.android.systemui.samsung.quicksetting.ui.panel.";
  private final QuickPanelHooks owner;
  private final Field item, width, height, offset, id, spec;
  private final Method type, copy, stateValue, flowValue;
  private final Class<?> stateInterface, quickTile;
  private final Constructor<?> brightGroup, volumeGroup;
  private final LinkedHashMap<Object, Object> states = new LinkedHashMap<>();
  private final LinkedHashMap<Key, Object> groups = new LinkedHashMap<>();
  private final Map<String, Object> aliases = new java.util.concurrent.ConcurrentHashMap<>();

  private record Key(Object bar, Object button, int width, int height) {}

  QuickPanelLegacyLayout(QuickPanelHooks owner, ClassLoader loader) throws Exception {
    this.owner = owner;
    Class<?> panelItem = Class.forName(MODEL + "QSPanelItem", false, loader);
    Class<?> gridItem = Class.forName(MODEL + "items.GridTileItem", false, loader);
    Class<?> assigned = Class.forName(MODEL + "items.IdAssignedGridTileItem", false, loader);
    quickTile = Class.forName(MODEL + "items.QuickTile", false, loader);
    item = panelItem.getDeclaredField("gridTileItem");
    width = panelItem.getDeclaredField("spanWidth");
    height = panelItem.getDeclaredField("spanHeight");
    offset = panelItem.getDeclaredField("spanOffset");
    id = assigned.getDeclaredField("id");
    spec = quickTile.getDeclaredField("spec");
    type = gridItem.getMethod("getType");
    copy = named(panelItem, "copy-1u2huiI$default", 12);
    brightGroup = constructor(Class.forName(MODEL + "items.BrightBarWithTile", false, loader), 6);
    volumeGroup = constructor(Class.forName(MODEL + "items.VolumeBarWithTile", false, loader), 7);
    stateInterface = Class.forName("androidx.compose.runtime.State", false, loader);
    stateValue = stateInterface.getMethod("getValue");
    flowValue =
        Class.forName("kotlinx.coroutines.flow.StateFlow", false, loader).getMethod("getValue");
    Class<?> scene = Class.forName(PANEL + "QuickSettingSceneViewModel", false, loader);
    installAlias(owner, scene, "getItemSpanOffset-Bjo55l4", false);
    installAlias(owner, scene, "getItemSpanSize-YEO4UFw", true);
    Class<?> store = Class.forName(PANEL + "PanelItemStoreImpl", false, loader);
    owner.add(
        ModernHook.hookMethod(
            store.getDeclaredMethod("getItem", String.class),
            new ModernHook.Callback() {
              @Override
              protected void beforeHookedMethod(ModernHook.Param p) {
                if (owner.legacy()) {
                  Object alias = aliases.get(p.args[0]);
                  if (alias != null) p.setResult(alias);
                }
              }
            }));
    Class<?> panel = Class.forName(PANEL + "QuickSettingTilePanelKt", false, loader);
    Method render = named(panel, "QuickSettingTilePanelScrollableGrid-OzltmbE", 14);
    if (render.getParameterTypes()[3] != stateInterface)
      throw new UnsupportedOperationException("Panel state contract");
    owner.add(
        ModernHook.hookMethod(
            render,
            new ModernHook.Callback() {
              @Override
              protected void beforeHookedMethod(ModernHook.Param p) {
                if (!owner.needsRefresh()) return;
                try {
                  Object original = unwrap(p.args[3]);
                  if (owner.legacy()) p.args[3] = wrap(original, loader);
                  else p.args[3] = original;
                  // The State argument changed; keep the original composition's native read
                  // tracking.
                  p.args[12] = ((int) p.args[12] & ~7168) | 2048 | 1;
                } catch (Throwable error) {
                  owner.stopLegacy(error);
                }
              }
            }));
    Method root = named(panel, "QuickSettingTilePanel", 7);
    for (String name : new String[] {"BrightBarWithTile", "VolumeBarWithTile"}) {
      Method draw =
          named(
              Class.forName(
                  "com.android.systemui.samsung.quicksetting.ui.tiles." + name + "Kt",
                  false,
                  loader),
              name,
              5);
      if (!ModernHook.deoptimize(draw))
        throw new UnsupportedOperationException("Legacy group call site");
    }
    if (!ModernHook.deoptimize(root))
      throw new UnsupportedOperationException("Panel render call site");
  }

  private void installAlias(QuickPanelHooks owner, Class<?> scene, String name, boolean size)
      throws Exception {
    owner.add(
        ModernHook.hookMethod(
            scene.getDeclaredMethod(name, String.class),
            new ModernHook.Callback() {
              @Override
              protected void beforeHookedMethod(ModernHook.Param p) {
                if (!owner.legacy()) return;
                Object entry = aliases.get(p.args[0]);
                if (entry == null) return;
                try {
                  p.setResult(
                      size
                          ? pack(width.getInt(entry), height.getInt(entry))
                          : offset.getLong(entry));
                } catch (Throwable error) {
                  owner.stopLegacy(error);
                }
              }
            }));
  }

  private Object unwrap(Object value) {
    if (Proxy.isProxyClass(value.getClass())) {
      InvocationHandler handler = Proxy.getInvocationHandler(value);
      if (handler instanceof Presentation presentation) return presentation.source;
    }
    return value;
  }

  private Object wrap(Object source, ClassLoader loader) {
    Object existing = states.get(source);
    if (existing != null) return existing;
    Object proxy =
        Proxy.newProxyInstance(loader, new Class<?>[] {stateInterface}, new Presentation(source));
    states.put(source, proxy);
    if (states.size() > 4) states.remove(states.keySet().iterator().next());
    return proxy;
  }

  private final class Presentation implements InvocationHandler {
    final Object source;
    private List<?> lastSource, lastResult;

    Presentation(Object source) {
      this.source = source;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      return switch (method.getName()) {
        case "getValue" -> {
          Object value = stateValue.invoke(source);
          if (!owner.legacy() || !(value instanceof List<?> list)) yield value;
          try {
            if (editing(list)) yield list;
            if (list == lastSource) yield lastResult;
            List<?> converted = present(list);
            lastSource = list;
            lastResult = converted;
            yield converted;
          } catch (Throwable error) {
            owner.stopLegacy(error);
            yield value;
          }
        }
        case "equals" -> proxy == args[0];
        case "hashCode" -> System.identityHashCode(proxy);
        case "toString" -> "One UI legacy panel presentation";
        default -> throw new UnsupportedOperationException(method.getName());
      };
    }
  }

  private boolean editing(List<?> source) throws Exception {
    for (Object entry : source) {
      Object tile = item.get(entry);
      String name = (String) type.invoke(tile);
      if (!name.equals("BrightBar") && !name.equals("VolumeBar")) continue;
      Object vm = tile.getClass().getField("viewModel").get(tile);
      Object scene = vm.getClass().getField("sceneViewModel").get(vm);
      Object editor = scene.getClass().getField("$$delegate_0").get(scene);
      Object flow = editor.getClass().getField("editMode").get(editor);
      return Boolean.TRUE.equals(flowValue.invoke(flow));
    }
    return false;
  }

  private List<?> present(List<?> source) throws Exception {
    List<QuickPanelPairing.Cell> cells = new ArrayList<>();
    for (int i = 0; i < source.size(); i++) {
      Object entry = source.get(i), tile = item.get(entry);
      String name = (String) type.invoke(tile);
      long location = offset.getLong(entry);
      cells.add(
          new QuickPanelPairing.Cell(
              i,
              name,
              quickTile.isInstance(tile) ? (String) spec.get(tile) : "",
              (int) (location >> 32),
              (int) location,
              width.getInt(entry),
              height.getInt(entry)));
    }
    List<QuickPanelPairing.Pair> pairs = QuickPanelPairing.pairs(cells);
    if (pairs.isEmpty()) return source;
    Map<Integer, Object> replacements = new HashMap<>();
    Set<Integer> removed = new HashSet<>();
    for (var pair : pairs) {
      Object barEntry = source.get(pair.bar()), bar = item.get(barEntry);
      Object button = item.get(source.get(pair.button()));
      Key key = new Key(bar, button, pair.width(), pair.height());
      Object group = groups.get(key);
      if (group == null) {
        Object vm = bar.getClass().getField("viewModel").get(bar);
        Object category = bar.getClass().getSuperclass().getField("category").get(bar);
        boolean bright = type.invoke(bar).equals("BrightBar");
        group =
            bright
                ? brightGroup.newInstance(
                    vm,
                    pack(pair.width(), pair.height()),
                    "BrightBarWithTile",
                    button,
                    category,
                    null)
                : volumeGroup.newInstance(
                    vm,
                    pack(pair.width(), pair.height()),
                    "VolumeBarWithTile",
                    button,
                    category,
                    null,
                    null);
        groups.put(key, group);
        if (groups.size() > 8) {
          Object evicted = groups.remove(groups.keySet().iterator().next());
          aliases.remove((String) id.get(evicted));
        }
      }
      Object entry =
          copy.invoke(
              null,
              barEntry,
              group,
              pair.width(),
              pair.height(),
              pack(pair.x(), pair.y()),
              0,
              null,
              null,
              false,
              false,
              false,
              16368);
      replacements.put(pair.bar(), entry);
      removed.add(pair.button());
      aliases.put((String) id.get(group), entry);
    }
    List<Object> result = new ArrayList<>(source.size() - removed.size());
    for (int i = 0; i < source.size(); i++)
      if (!removed.contains(i)) result.add(replacements.getOrDefault(i, source.get(i)));
    return result;
  }

  private static long pack(int x, int y) {
    return ((long) x << 32) | (y & 0xffffffffL);
  }

  private static Constructor<?> constructor(Class<?> type, int count) throws NoSuchMethodException {
    for (Constructor<?> candidate : type.getDeclaredConstructors())
      if (candidate.getParameterCount() == count) {
        candidate.setAccessible(true);
        return candidate;
      }
    throw new NoSuchMethodException(type.getName());
  }

  private static Method named(Class<?> type, String name, int count) throws NoSuchMethodException {
    for (Method candidate : type.getDeclaredMethods())
      if (candidate.getName().equals(name) && candidate.getParameterCount() == count)
        return candidate;
    throw new NoSuchMethodException(type.getName() + "#" + name);
  }
}
