package dev.ryuya.oneuihometweaks;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.*;
import android.view.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** Projects native DIY icon coordinates without changing widgets, DIY mode, or stored positions. */
final class DiyGridBridge {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final List<ModernHook.Handle> hooks = new ArrayList<>();
  private final Map<Object, State> states = new WeakHashMap<>();
  private final Map<ViewGroup, State> layouts = new WeakHashMap<>();
  private final Map<String, Method> methods = new HashMap<>();
  private final Map<Object, PointF> moveRemainders = new WeakHashMap<>();
  private boolean editing;
  private int editLogCount;
  private WeakReference<ViewGroup> workspace = new WeakReference<>(null);
  private Class<?> layoutType, modelType;
  private final Set<Class<?>> iconTypes = new HashSet<>();
  private boolean enabled, starting, ready, failed, gathering, rendering;
  private int projectionDepth;
  private String diagnostic = "off";
  private String observedGeometry = "";
  private Runnable changed = () -> {};
  private final Runnable render = this::renderNow;

  record PreparedSave(Context context, Object owner, GridSavePlan plan) {}

  PreparedSave prepareSave() throws ReflectiveOperationException {
    ViewGroup root = workspace.get();
    if (!onMain()
        || !ready
        || !enabled
        || failed
        || editing
        || root == null
        || !root.isAttachedToWindow()) throw new IllegalStateException("Grid not ready");
    collect(root);
    Set<State> owners = Collections.newSetFromMap(new IdentityHashMap<>());
    for (var entry : layouts.entrySet())
      if (entry.getKey().isAttachedToWindow() && entry.getValue().valid()) {
        if (number(entry.getKey(), "getSelectedItemsCount") != 0)
          throw new IllegalStateException("Editor selection active");
        owners.add(entry.getValue());
      }
    if (owners.size() != 1) throw new IllegalStateException("Ambiguous home model");
    State state = owners.iterator().next();
    state.capture();
    state.dirty = true;
    state.plan();
    Object vm = state.vm.get();
    if (vm == null) throw new IllegalStateException("Home model unavailable");
    Class<?> info =
        Class.forName(
            "com.honeyspace.ui.honeypots.freegrid.domain.model.FreeEditInfo",
            false,
            vm.getClass().getClassLoader());
    info.getDeclaredConstructor(
        int.class,
        Integer.class,
        Integer.class,
        Integer.class,
        Integer.class,
        Integer.class,
        Float.class,
        Float.class,
        Integer.class,
        boolean.class);
    vm.getClass()
        .getDeclaredMethod("updateFreeGridProperty", List.class, boolean.class, boolean.class);
    List<GridSavePlan.Entry> entries = new ArrayList<>();
    for (var reference : state.models.values()) {
      Object model = reference.get();
      if (model == null || !eligible(model) || Boolean.TRUE.equals(call(model, "getLand")))
        throw new IllegalStateException("Incomplete model snapshot");
      int id = number(model, "getId");
      GridSnapPlan.Slot slot = state.positions.get(id);
      if (slot == null) throw new IllegalStateException("Page cannot fit normal grid");
      Object original = call(model, "getToFreeEditInfo");
      if (Boolean.TRUE.equals(call(original, "getDelete")))
        throw new IllegalStateException("Hidden item cannot be saved");
      entries.add(
          new GridSavePlan.Entry(
              id,
              number(model, "getPageId"),
              number(model, "getPortX"),
              number(model, "getPortY"),
              slot.x(),
              slot.y(),
              state.grid
                  + ":"
                  + state.cell
                  + ":"
                  + state.source(model)
                  + ":"
                  + call(model, "getScale")
                  + ":"
                  + call(model, "getAngle")));
    }
    return new PreparedSave(root.getContext(), vm, new GridSavePlan(entries));
  }

  void applySave(PreparedSave prepared, boolean rollback) throws ReflectiveOperationException {
    if (!onMain()) throw new IllegalStateException("Main thread only");
    Object owner = prepared.owner();
    Class<?> info =
        Class.forName(
            "com.honeyspace.ui.honeypots.freegrid.domain.model.FreeEditInfo",
            false,
            owner.getClass().getClassLoader());
    Constructor<?> constructor =
        info.getDeclaredConstructor(
            int.class,
            Integer.class,
            Integer.class,
            Integer.class,
            Integer.class,
            Integer.class,
            Float.class,
            Float.class,
            Integer.class,
            boolean.class);
    List<Object> updates = new ArrayList<>();
    for (GridSavePlan.Entry entry : prepared.plan().entries())
      if (entry.changed())
        updates.add(
            constructor.newInstance(
                entry.id(),
                null,
                rollback ? entry.oldX() : entry.x(),
                rollback ? entry.oldY() : entry.y(),
                null,
                null,
                null,
                null,
                null,
                false));
    // Only x/y are supplied. Native history and repository persistence own every write.
    call(
        owner,
        "updateFreeGridProperty",
        new Class<?>[] {List.class, boolean.class, boolean.class},
        updates,
        false,
        true);
    for (State state : states.values()) if (state.vm.get() == owner) state.dirty = true;
    queueRender();
  }

  void setChanged(Runnable listener) {
    changed = listener;
  }

  String diagnostic() {
    return diagnostic;
  }

  void setEnabled(boolean on) {
    if (failed || enabled == on) return;
    enabled = on;
    moveRemainders.clear();
    if (on) {
      ensureReady();
      if (ready) queueRender();
    } else {
      report("off");
      queueRender();
    }
  }

  void observe(ViewGroup view) {
    boolean replaced = workspace.get() != view;
    workspace = new WeakReference<>(view);
    if (!ready) {
      if (enabled) ensureReady();
      return;
    }
    try {
      String key =
          view.getWidth()
              + ":"
              + view.getHeight()
              + ":"
              + view.getChildCount()
              + ":"
              + number(view, "getCurrentPage");
      if (!replaced && key.equals(observedGeometry)) return;
      observedGeometry = key;
      collect(view);
      queueRender();
    } catch (Throwable error) {
      disable(error);
    }
  }

  private void ensureReady() {
    ViewGroup view = workspace.get();
    if (starting || ready || failed || view == null) return;
    starting = true;
    report("loading");
    ClassLoader loader = view.getClass().getClassLoader();
    // DEX inspection and deoptimization must not block Launcher startup or a touch callback.
    new Thread(
            () -> {
              try {
                Context host = view.getContext();
                String apk =
                    host.getPackageManager()
                        .getApplicationInfo("com.samsung.android.app.homestar", 0)
                        .sourceDir;
                Class<?> layout =
                    Class.forName(
                        "com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridCellLayout",
                        false,
                        loader);
                Class<?> model =
                    Class.forName(
                        "com.honeyspace.ui.honeypots.freegrid.domain.model.FreeGridItem",
                        false,
                        loader);
                Class<?> vm =
                    Class.forName(
                        "com.honeyspace.ui.honeypots.freegrid.viewmodel.FreeGridViewModel",
                        false,
                        loader);
                Class<?> params =
                    Class.forName(layout.getName() + "$FreeGridLayoutParams", false, loader);
                Class<?> searchable =
                    Class.forName("com.honeyspace.sdk.transition.SearchableView", false, loader);
                Method viewItemId = searchable.getDeclaredMethod("getItemId");
                if (viewItemId.getReturnType() != int.class)
                  throw new UnsupportedOperationException("DIY view identity changed");
                if (!ViewGroup.class.isAssignableFrom(layout)
                    || !ViewGroup.LayoutParams.class.isAssignableFrom(params))
                  throw new UnsupportedOperationException("DIY layout types changed");
                Method create = layout.getDeclaredMethod("createLayoutParams", model);
                Method items = vm.getDeclaredMethod("getItems");
                Method geometry = vm.getDeclaredMethod("getItemPositionData");
                geometry.setAccessible(true);
                params.getDeclaredMethod("setup", Point.class, float.class);
                model.getDeclaredMethod("getPortX");
                model.getDeclaredMethod("getPortY");
                for (String name : new String[] {"App", "Folder", "Shortcut", "DeepShortcut"})
                  iconTypes.add(Class.forName(model.getName() + "$" + name, false, loader));
                Class<?> sticker =
                    Class.forName("com.honeyspace.ui.honeypots.sticker.StickerView", false, loader);
                Class<?> frame =
                    Class.forName(
                        "com.honeyspace.ui.honeypots.sticker.FrameControlStickerView",
                        false,
                        loader);
                // Resolve the complete edit contract before installing any editing hook.
                Method prepare = layout.getDeclaredMethod("prepareSyncPosition", frame);
                Method sync = layout.getDeclaredMethod("syncPosition", frame);
                Method translate =
                    layout.getDeclaredMethod(
                        "translateTarget", sticker, float.class, float.class, boolean.class);
                Method drop =
                    vm.getDeclaredMethod(
                        "updateToDropPoint", model, Point.class, int.class, Point.class);
                layout.getDeclaredMethod("fitTargetView", sticker);
                frame.getDeclaredMethod("getTargetPosition");
                Field targetDiff = frame.getDeclaredField("targetPositionDiff");
                if (targetDiff.getType() != PointF.class)
                  throw new UnsupportedOperationException("DIY edit pointer contract changed");
                targetDiff.setAccessible(true);
                DexEvidence.deoptimizeDiyPositions(apk, loader);
                layoutType = layout;
                modelType = model;
                hooks.add(
                    ModernHook.hookMethod(
                        prepare,
                        new ModernHook.Callback() {
                          @Override
                          protected void beforeHookedMethod(ModernHook.Param p) {
                            if (onMain()) {
                              moveRemainders.remove(p.args[0]);
                              editLogCount = 0;
                            }
                          }
                        }));
                for (Method edit : new Method[] {sync, translate}) {
                  boolean isSync = edit == sync;
                  hooks.add(
                      ModernHook.hookMethod(
                          edit,
                          new ModernHook.Callback() {
                            @Override
                            protected void beforeHookedMethod(ModernHook.Param p) {
                              if (!ready || !enabled || failed || editing || !onMain()) return;
                              try {
                                ViewGroup owner = (ViewGroup) p.thisObject;
                                register(owner);
                                State state = layouts.get(owner);
                                if (state == null || !state.valid()) return;
                                Object control = p.args[0];
                                WeakReference<Object> ref =
                                    state.models.get(number(control, "getId"));
                                Object item = ref == null ? null : ref.get();
                                if (item == null || !eligible(item)) return;
                                View target = (View) call(control, "getTargetView");
                                if (target.getParent() != owner) return;
                                Object lp = target.getLayoutParams();
                                PointF rest = moveRemainders.getOrDefault(control, new PointF());
                                PointF requested;
                                if (isSync) {
                                  // Native getTargetPosition truncates to int. Read its verified
                                  // float
                                  // inputs so repeated moves of less than one pixel are not
                                  // discarded
                                  // when the control frame is snapped back onto a cell.
                                  PointF diff = (PointF) targetDiff.get(control);
                                  View handle = (View) call(control, "getControlView");
                                  requested =
                                      new PointF(
                                          handle.getTranslationX() - diff.x,
                                          handle.getTranslationY() - diff.y);
                                } else
                                  requested =
                                      new PointF(
                                          number(lp, "getCellX") + (Float) p.args[1],
                                          number(lp, "getCellY") + (Float) p.args[2]);
                                PointF intended =
                                    new PointF(requested.x + rest.x, requested.y + rest.y);
                                Point raw =
                                    new Point(Math.round(intended.x), Math.round(intended.y));
                                Point snapped = state.snap(item, number(item, "getPageId"), raw);
                                if (snapped == null) return;
                                if (editLogCount++ < 4)
                                  HookEntry.log(
                                      "DIY_GRID_EDIT "
                                          + (isSync ? "sync" : "translate")
                                          + " type="
                                          + item.getClass().getSimpleName()
                                          + " requested="
                                          + requested
                                          + " remainder="
                                          + rest
                                          + " snapped="
                                          + snapped);
                                editing = true;
                                try {
                                  call(
                                      lp,
                                      "setup",
                                      new Class<?>[] {Point.class, float.class},
                                      snapped,
                                      call(lp, "getScale"));
                                  target.requestLayout();
                                  // Keep the native edit frame on the icon. Carry sub-cell pointer
                                  // motion
                                  // forward so small MOVE events can still cross a cell boundary.
                                  if (isSync || Boolean.TRUE.equals(p.args[3]))
                                    call(owner, "fitTargetView", new Class<?>[] {sticker}, control);
                                  moveRemainders.put(
                                      control,
                                      new PointF(intended.x - snapped.x, intended.y - snapped.y));
                                  p.setResult(null);
                                } finally {
                                  editing = false;
                                }
                              } catch (Throwable error) {
                                disable(error);
                              }
                            }
                          }));
                }
                hooks.add(
                    ModernHook.hookMethod(
                        drop,
                        new ModernHook.Callback() {
                          @Override
                          protected void beforeHookedMethod(ModernHook.Param p) {
                            if (!ready || !enabled || failed || !onMain() || !eligible(p.args[0]))
                              return;
                            try {
                              Point desired = (Point) p.args[1];
                              if (desired.x == -1 || desired.y == -1) return; // Native sentinel.
                              State state = states.get(p.thisObject);
                              if (state == null || !state.valid()) return;
                              Point snapped = state.snap(p.args[0], (Integer) p.args[2], desired);
                              if (snapped != null) p.args[1] = snapped;
                            } catch (Throwable error) {
                              disable(error);
                            }
                          }

                          @Override
                          protected void afterHookedMethod(ModernHook.Param p) {
                            if (!ready || failed || !onMain()) return;
                            State state = states.get(p.thisObject);
                            if (state != null) state.dirty = true;
                            queueRender();
                          }
                        }));
                hooks.add(
                    ModernHook.hookMethod(
                        layout.getDeclaredMethod(
                            "createLayoutParams",
                            View.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            float.class,
                            boolean.class),
                        new ModernHook.Callback() {
                          @Override
                          protected void afterHookedMethod(ModernHook.Param p) {
                            if (!ready || !enabled || failed || !onMain() || p.hasThrowable())
                              return;
                            try {
                              register((ViewGroup) p.thisObject);
                              State state = layouts.get(p.thisObject);
                              View view = (View) p.args[0];
                              if (state == null || !searchable.isInstance(view) || !state.valid())
                                return;
                              WeakReference<Object> ref =
                                  state.models.get((Integer) viewItemId.invoke(view));
                              Object item = ref == null ? null : ref.get();
                              GridSnapPlan.Slot slot = item == null ? null : state.position(item);
                              if (slot != null)
                                call(
                                    p.getResult(),
                                    "setup",
                                    new Class<?>[] {Point.class, float.class},
                                    new Point(slot.x(), slot.y()),
                                    p.args[5]);
                            } catch (Throwable error) {
                              disable(error);
                            }
                          }
                        }));
                // Install observers before coordinate hooks; until ready=true every getter stays
                // native.
                hooks.add(
                    ModernHook.hookMethod(
                        create,
                        new ModernHook.Callback() {
                          @Override
                          protected void beforeHookedMethod(ModernHook.Param p) {
                            if (!ready || failed || !onMain()) return;
                            try {
                              register((ViewGroup) p.thisObject);
                              enterProjection(p);
                            } catch (Throwable error) {
                              disable(error);
                            }
                          }

                          @Override
                          protected void afterHookedMethod(ModernHook.Param p) {
                            leaveProjection(p);
                          }
                        }));
                // The coordinate projection is visible ONLY inside verified, read-only UI
                // consumers.
                // Repository writes, migrations, orientation sanitizers and serialization retain
                // raw coordinates.
                hooks.add(
                    ModernHook.hookMethod(
                        layout.getDeclaredMethod("updateViewWithoutReattach", View.class, model),
                        new ModernHook.Callback() {
                          @Override
                          protected void beforeHookedMethod(ModernHook.Param p) {
                            enterProjection(p);
                          }

                          @Override
                          protected void afterHookedMethod(ModernHook.Param p) {
                            leaveProjection(p);
                          }
                        }));
                hooks.add(
                    ModernHook.hookMethod(
                        vm.getDeclaredMethod("includePoint", model, Point.class),
                        new ModernHook.Callback() {
                          @Override
                          protected void beforeHookedMethod(ModernHook.Param p) {
                            enterProjection(p);
                          }

                          @Override
                          protected void afterHookedMethod(ModernHook.Param p) {
                            leaveProjection(p);
                          }
                        }));
                hooks.add(
                    ModernHook.hookMethod(
                        items,
                        new ModernHook.Callback() {
                          @Override
                          protected void afterHookedMethod(ModernHook.Param p) {
                            if (!ready || failed || gathering || p.hasThrowable() || !onMain())
                              return;
                            State state = states.get(p.thisObject);
                            if (state != null && p.getResult() instanceof Iterable<?> list)
                              try {
                                state.bind(list);
                                if (state.dirty) queueRender();
                              } catch (Throwable error) {
                                disable(error);
                              }
                          }
                        }));
                for (String coordinate : new String[] {"getX", "getY"}) {
                  Method getter = model.getDeclaredMethod(coordinate);
                  if (getter.getReturnType() != int.class)
                    throw new UnsupportedOperationException("DIY coordinates changed");
                  hooks.add(
                      ModernHook.hookMethod(
                          getter,
                          new ModernHook.Callback() {
                            @Override
                            protected void afterHookedMethod(ModernHook.Param p) {
                              if (!ready
                                  || !enabled
                                  || failed
                                  || p.hasThrowable()
                                  || !onMain()
                                  || projectionDepth == 0
                                  || !eligible(p.thisObject)) return;
                              try {
                                for (State state : new ArrayList<>(states.values())) {
                                  Object current =
                                      state.models.get(number(p.thisObject, "getId")) == null
                                          ? null
                                          : state.models.get(number(p.thisObject, "getId")).get();
                                  if (current != p.thisObject || !state.valid()) continue;
                                  GridSnapPlan.Slot slot = state.position(p.thisObject);
                                  if (slot != null)
                                    p.setResult(coordinate.equals("getX") ? slot.x() : slot.y());
                                  return;
                                }
                              } catch (Throwable error) {
                                disable(error);
                              }
                            }
                          }));
                }
                main.post(
                    () -> {
                      if (failed) return;
                      ready = true;
                      starting = false;
                      ViewGroup current = workspace.get();
                      if (current != null) collect(current);
                      queueRender();
                    });
              } catch (Throwable error) {
                main.post(() -> disable(error));
              }
            },
            "OneUiDiyGridEvidence")
        .start();
  }

  private static boolean onMain() {
    return Looper.myLooper() == Looper.getMainLooper();
  }

  private void enterProjection(ModernHook.Param p) {
    if (!enabled || !ready || failed || !onMain()) return;
    projectionDepth++;
    p.setObjectExtra("diy-grid-ui", true);
  }

  private void leaveProjection(ModernHook.Param p) {
    if (Boolean.TRUE.equals(p.getObjectExtra("diy-grid-ui"))) projectionDepth--;
  }

  private boolean eligible(Object model) {
    return iconTypes.contains(model.getClass());
  }

  private void collect(ViewGroup root) {
    try {
      if (layoutType.isInstance(root)) register(root);
      for (int i = 0; i < root.getChildCount(); i++)
        if (root.getChildAt(i) instanceof ViewGroup group) collect(group);
    } catch (Throwable error) {
      disable(error);
    }
  }

  private void register(ViewGroup layout) throws ReflectiveOperationException {
    if (layouts.containsKey(layout)) return;
    Object vm = call(layout, "getViewModel");
    State state = states.get(vm);
    if (state == null) {
      state = new State(vm, layout.getContext());
      states.put(vm, state);
    }
    layouts.put(layout, state);
    state.capture();
    queueRender();
  }

  private void queueRender() {
    if (rendering && !failed) return;
    main.removeCallbacks(render);
    main.post(render);
  }

  private void renderNow() {
    if (!ready || rendering) return;
    if (failed) {
      restoreNativeViews();
      return;
    }
    rendering = true;
    try {
      int count = 0;
      Set<State> captured = Collections.newSetFromMap(new IdentityHashMap<>());
      for (var entry : new ArrayList<>(layouts.entrySet())) {
        ViewGroup layout = entry.getKey();
        State state = entry.getValue();
        if (!layout.isAttachedToWindow()) continue;
        if (captured.add(state)) state.capture();
        boolean use = enabled && !failed && state.valid();
        if (use) state.plan();
        for (var binding : new ArrayList<>(state.models.entrySet())) {
          Object model = binding.getValue().get();
          if (model == null || !eligible(model)) continue;
          Object child =
              call(layout, "getChildWithId", new Class<?>[] {int.class}, binding.getKey());
          if (!(child instanceof View view) || view.getParent() != layout) continue;
          GridSnapPlan.Slot slot = use ? state.position(model) : null;
          Point point =
              slot == null
                  ? new Point(number(model, "getX"), number(model, "getY"))
                  : new Point(slot.x(), slot.y());
          // Use the native params for both visual layout and editing coordinates; never translate
          // the view alone.
          call(
              view.getLayoutParams(),
              "setup",
              new Class<?>[] {Point.class, float.class},
              point,
              call(model, "getScale"));
          view.requestLayout();
          if (slot != null) count++;
        }
      }
      if (!failed) report(!enabled ? "off" : count == 0 ? "waiting" : "active icons=" + count);
    } catch (Throwable error) {
      disable(error);
    } finally {
      rendering = false;
    }
  }

  private final class State {
    final WeakReference<Object> vm;
    final WeakReference<Context> context;
    final Map<Integer, WeakReference<Object>> models = new LinkedHashMap<>();
    final Map<Integer, Map<Integer, GridSnapPlan.Slot>> pages = new HashMap<>();
    Map<Integer, GridSnapPlan.Slot> positions = new HashMap<>();
    Map<Integer, GridSnapPlan.Item> sources = new HashMap<>();
    Map<Integer, Integer> sourcePages = new HashMap<>();
    Object geometry;
    Point grid, cell, offset;
    boolean dirty = true;

    State(Object owner, Context ctx) {
      vm = new WeakReference<>(owner);
      context = new WeakReference<>(ctx);
    }

    boolean valid() throws ReflectiveOperationException {
      Context ctx = context.get();
      Object owner = vm.get();
      if (ctx == null
          || owner == null
          || ctx.getDisplay() == null
          || ctx.getDisplay().getDisplayId() != 0
          || ctx.getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT
          || ctx.getResources().getConfiguration().getLayoutDirection()
              != View.LAYOUT_DIRECTION_LTR) return false;
      Field preview = owner.getClass().getDeclaredField("isPreview");
      preview.setAccessible(true);
      return !preview.getBoolean(owner);
    }

    void capture() throws ReflectiveOperationException {
      Object owner = vm.get();
      if (owner == null) return;
      gathering = true;
      try {
        Object raw = call(owner, "getItems");
        if (raw instanceof Iterable<?> list) bind(list);
        Point nextGrid = (Point) call(owner, "getWorkspaceUIGrid");
        Object nextGeometry = call(owner, "getItemPositionData");
        Point nextCell = (Point) call(nextGeometry, "getItemSizeBasedOnDefaultGrid");
        Point nextOffset = (Point) call(nextGeometry, "getPositionDiff");
        if (!nextGrid.equals(grid) || !nextCell.equals(cell) || !nextOffset.equals(offset))
          dirty = true;
        grid = new Point(nextGrid);
        cell = new Point(nextCell);
        offset = new Point(nextOffset);
        geometry = nextGeometry;
      } finally {
        gathering = false;
      }
    }

    void bind(Iterable<?> list) throws ReflectiveOperationException {
      Set<Integer> live = new HashSet<>();
      for (Object model : list)
        if (modelType.isInstance(model) && eligible(model)) {
          int id = number(model, "getId");
          live.add(id);
          WeakReference<Object> old = models.put(id, new WeakReference<>(model));
          if (old == null || old.get() != model) dirty = true;
        }
      if (models.keySet().removeIf(id -> !live.contains(id))) dirty = true;
    }

    GridSnapPlan.Item source(Object model) throws ReflectiveOperationException {
      int spanX = number(model, "getSpanX"), spanY = number(model, "getSpanY");
      Point padding =
          (Point)
              call(
                  geometry,
                  "getInnerPadding",
                  new Class<?>[] {Point.class},
                  new Point(spanX, spanY));
      return new GridSnapPlan.Item(
          number(model, "getId"),
          number(model, "getPortX"),
          number(model, "getPortY"),
          spanX,
          spanY,
          offset.x + padding.x,
          offset.y + padding.y);
    }

    GridSnapPlan.Slot position(Object model) throws ReflectiveOperationException {
      if (geometry == null || Boolean.TRUE.equals(call(model, "getLand"))) return null;
      GridSnapPlan.Item current = source(model);
      if (!current.equals(sources.get(current.id()))
          || !Integer.valueOf(number(model, "getPageId")).equals(sourcePages.get(current.id())))
        dirty = true;
      if (dirty) {
        plan();
        queueRender();
      }
      return positions.get(current.id());
    }

    void plan() throws ReflectiveOperationException {
      if (!dirty || geometry == null) return;
      Map<Integer, List<GridSnapPlan.Item>> groups = new TreeMap<>();
      Map<Integer, GridSnapPlan.Item> nextSources = new HashMap<>();
      Map<Integer, Integer> nextPages = new HashMap<>();
      for (WeakReference<Object> reference : models.values()) {
        Object model = reference.get();
        if (model == null) continue;
        GridSnapPlan.Item source = source(model);
        groups.computeIfAbsent(number(model, "getPageId"), id -> new ArrayList<>()).add(source);
        nextSources.put(source.id(), source);
        nextPages.put(source.id(), number(model, "getPageId"));
      }
      Map<Integer, GridSnapPlan.Slot> next = new HashMap<>();
      for (var group : groups.entrySet()) {
        Map<Integer, GridSnapPlan.Slot> page =
            GridSnapPlan.build(
                grid.x,
                grid.y,
                cell.x,
                cell.y,
                group.getValue(),
                pages.getOrDefault(group.getKey(), Map.of()));
        if (page == null) {
          pages.remove(group.getKey());
          HookEntry.log("DIY_GRID_PAGE_UNAVAILABLE capacity/geometry");
          continue;
        }
        pages.put(group.getKey(), page);
        next.putAll(page);
      }
      positions = next;
      sources = nextSources;
      sourcePages = nextPages;
      dirty = false;
    }

    Point snap(Object item, int page, Point requested) throws ReflectiveOperationException {
      if (geometry == null || Boolean.TRUE.equals(call(item, "getLand"))) return null;
      plan();
      GridSnapPlan.Item original = source(item);
      GridSnapPlan.Item desired =
          new GridSnapPlan.Item(
              original.id(),
              requested.x,
              requested.y,
              original.spanX(),
              original.spanY(),
              original.offsetX(),
              original.offsetY());
      GridSnapPlan.Slot slot =
          GridSnapPlan.move(
              grid.x, grid.y, cell.x, cell.y, desired, pages.getOrDefault(page, Map.of()).values());
      return slot == null ? null : new Point(slot.x(), slot.y());
    }
  }

  private void disable(Throwable error) {
    if (failed) return;
    failed = true;
    enabled = false;
    for (var hook : hooks)
      try {
        hook.unhook();
      } catch (Throwable ignored) {
      }
    report("error:" + error.getClass().getSimpleName());
    queueRender();
    HookEntry.log("DIY_GRID_DISABLED " + error);
  }

  private void restoreNativeViews() {
    for (var entry : new ArrayList<>(layouts.entrySet())) {
      ViewGroup layout = entry.getKey();
      for (var binding : new ArrayList<>(entry.getValue().models.entrySet())) {
        try {
          Object model = binding.getValue().get();
          if (model == null) continue;
          Object child =
              call(layout, "getChildWithId", new Class<?>[] {int.class}, binding.getKey());
          if (!(child instanceof View view) || view.getParent() != layout) continue;
          Point point = new Point(number(model, "getX"), number(model, "getY"));
          call(
              view.getLayoutParams(),
              "setup",
              new Class<?>[] {Point.class, float.class},
              point,
              call(model, "getScale"));
          view.requestLayout();
        } catch (Throwable error) {
          HookEntry.log("DIY_GRID_RESTORE_UNAVAILABLE " + error);
        }
      }
    }
  }

  private void report(String next) {
    if (next.equals(diagnostic)) return;
    diagnostic = next;
    changed.run();
    HookEntry.log("DIY_GRID " + next);
  }

  private int number(Object owner, String name) throws ReflectiveOperationException {
    return ((Number) call(owner, name)).intValue();
  }

  private Object call(Object owner, String name) throws ReflectiveOperationException {
    return call(owner, name, new Class<?>[0]);
  }

  private Object call(Object owner, String name, Class<?>[] signature, Object... args)
      throws ReflectiveOperationException {
    String key = owner.getClass().getName() + "#" + name + Arrays.toString(signature);
    Method cached = methods.get(key);
    if (cached != null) return cached.invoke(owner, args);
    for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass())
      try {
        Method method = type.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        methods.put(key, method);
        return method.invoke(owner, args);
      } catch (NoSuchMethodException ignored) {
      }
    throw new NoSuchMethodException(key);
  }
}
