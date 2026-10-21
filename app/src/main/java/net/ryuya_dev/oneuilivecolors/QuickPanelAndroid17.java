package net.ryuya_dev.oneuilivecolors;

import java.lang.reflect.*;
import java.util.*;

/**
 * BZI9 Compose contract. Changes immutable layout arguments, never snapshot state or final fields.
 */
final class QuickPanelAndroid17 implements QuickPanelHooks.Adapter {
  private static final String TILES = "com.android.systemui.samsung.quicksetting.ui.tiles.";
  private final QuickPanelHooks owner;
  private QuickPanelLegacyLayout legacyLayout;

  private record BarFrame(int kind, float nativeDp, boolean grouped) {}

  private final Map<Object, Boolean> groupedScopes = new WeakHashMap<>();
  private final Map<Object, Integer> previewScopes = new WeakHashMap<>();
  private final ThreadLocal<BarFrame> barFrame = new ThreadLocal<>();
  private final ThreadLocal<Integer> buttonKind = new ThreadLocal<>();
  private final Set<String> evidence = new HashSet<>();
  private final Map<Object, Object> nativeButtonModifiers = new WeakHashMap<>();
  private final Method requiredWidth, requiredHeight, then;
  private final Object emptyModifier;

  QuickPanelAndroid17(QuickPanelHooks owner, ClassLoader loader) throws Exception {
    this.owner = owner;
    Class<?> modifier = Class.forName("androidx.compose.ui.Modifier", false, loader);
    emptyModifier = modifier.getField("Companion").get(null);
    then = modifier.getMethod("then", modifier);
    Class<?> sizes = Class.forName("androidx.compose.foundation.layout.SizeKt", false, loader);
    requiredWidth = sizes.getDeclaredMethod("requiredWidth-3ABfNKs", modifier, float.class);
    requiredHeight = sizes.getDeclaredMethod("requiredHeight-3ABfNKs", modifier, float.class);
    installButtons(loader, modifier, sizes);
    installBars(loader, modifier);
    try {
      legacyLayout = new QuickPanelLegacyLayout(owner, loader);
    } catch (Throwable error) {
      owner.stopLegacy(error);
    }
  }

  void previewScope(Object scope, int kind, boolean grouped) {
    previewScopes.put(scope, kind);
    if (grouped) groupedScopes.put(scope, true);
  }

  java.util.List<?> previewEntries() throws Exception {
    return legacyLayout == null ? java.util.List.of() : legacyLayout.previewEntries();
  }

  private void installButtons(ClassLoader loader, Class<?> modifier, Class<?> sizes)
      throws Exception {
    Method size = sizes.getDeclaredMethod("size-3ABfNKs", modifier, float.class);
    Method requiredSize = sizes.getDeclaredMethod("requiredSize-3ABfNKs", modifier, float.class);
    Class<?> tile =
        Class.forName(
            "com.android.systemui.samsung.quicksetting.domain.model.items.QuickTile",
            false,
            loader);
    Field spec = tile.getDeclaredField("spec");
    Method companion =
        named(
            Class.forName(TILES + "CompanionQuickTileKt", false, loader), "CompanionQuickTile", 6);
    if (companion.getParameterTypes()[2] != tile || companion.getParameterTypes()[4] != int.class)
      throw new UnsupportedOperationException("Companion tile contract");
    owner.add(
        ModernHook.aroundMethod(
            companion,
            chain -> {
              Object[] args = chain.getArgs().toArray();
              Integer previous = buttonKind.get();
              try {
                String name = (String) spec.get(args[2]);
                if (evidence.add("button:" + name)) HookEntry.log("QUICK_PANEL_BUTTON " + name);
                int kind = "UiModeNight".equals(name) ? 2 : "SoundMode".equals(name) ? 3 : -1;
                if (kind >= 0 && owner.needsRefresh()) {
                  buttonKind.set(kind);
                  args[4] = (int) args[4] | 1;
                } else buttonKind.remove();
                return chain.proceed(args);
              } finally {
                restore(buttonKind, previous);
              }
            }));
    Method scale =
        Class.forName("androidx.compose.ui.draw.ScaleKt", false, loader)
            .getDeclaredMethod("scale", modifier, float.class, float.class);
    Method quickTile = named(Class.forName(TILES + "QuickTileKt", false, loader), "QuickTile", 8);
    owner.add(
        ModernHook.aroundMethod(
            quickTile,
            chain -> {
              Object[] args = chain.getArgs().toArray();
              String name = (String) spec.get(args[2]);
              int kind = "UiModeNight".equals(name) ? 2 : "SoundMode".equals(name) ? 3 : -1;
              if (kind >= 0 && owner.needsRefresh()) {
                args[6] = (int) args[6] | 1;
                Object supplied = args[1];
                Object nativeModifier =
                    ((int) args[7] & 1) != 0
                        ? emptyModifier
                        : nativeButtonModifiers.getOrDefault(supplied, supplied);
                if (nativeModifier != supplied) {
                  args[1] = nativeModifier;
                  args[7] = (int) args[7] & ~1;
                  args[6] = ((int) args[6] & ~112) | 32 | 1;
                }
                if (owner.size(kind) != 100) {
                  float ratio = owner.size(kind) / 100f;
                  Object scaled = scale.invoke(null, emptyModifier, ratio, ratio);
                  args[1] = then.invoke(scaled, nativeModifier);
                  nativeButtonModifiers.put(args[1], nativeModifier);
                  args[7] = (int) args[7] & ~1;
                  args[6] = ((int) args[6] & ~112) | 32 | 1;
                  evidence(kind, 100, owner.size(kind));
                }
              }
              return chain.proceed(args);
            }));
    if (!ModernHook.deoptimize(quickTile))
      throw new UnsupportedOperationException("Grid tile call site");
    Class<?> grid =
        Class.forName(
            "com.android.systemui.samsung.quicksetting.ui.base.spannablegrid.SpannableGridTileKt",
            false,
            loader);
    for (Method call : grid.getDeclaredMethods()) {
      if (call.getName().equals("SpannableGridTile") && !ModernHook.deoptimize(call))
        throw new UnsupportedOperationException("Grid caller");
    }
    owner.add(
        ModernHook.hookMethod(
            size,
            new ModernHook.Callback() {
              @Override
              protected void beforeHookedMethod(ModernHook.Param p) {
                Integer kind = buttonKind.get();
                if (kind == null || owner.size(kind) == 100) return;
                float nativeDp = (float) p.args[1];
                if (!Float.isFinite(nativeDp) || nativeDp <= 0) return;
                float target =
                    owner.legacy()
                        ? QuickPanelPolicy.legacyDimension(nativeDp, owner.size(kind))
                        : QuickPanelPolicy.dimension(nativeDp, owner.size(kind));
                try {
                  Object sized = requiredSize.invoke(null, emptyModifier, target);
                  p.setResult(then.invoke(sized, p.args[0]));
                  evidence(kind, nativeDp, target);
                } catch (Throwable error) {
                  owner.stopLegacy(error);
                }
              }
            }));
    for (String caller :
        new String[] {
          "BrightBarWithTileKt$$ExternalSyntheticLambda10",
          "VolumeBarWithTileKt$$ExternalSyntheticLambda2"
        }) {
      Method call =
          Class.forName(TILES + caller, false, loader)
              .getDeclaredMethod("invoke", Object.class, Object.class);
      if (!ModernHook.deoptimize(call)) throw new UnsupportedOperationException("Companion caller");
    }
    if (!ModernHook.deoptimize(companion))
      throw new UnsupportedOperationException("Tile call site");
  }

  private void installBars(ClassLoader loader, Class<?> modifier) throws Exception {
    // This exact closure is the BoxWithConstraints content of IconProgressBar on BZI9.
    // The 100 / 150 discriminator comes from the reviewed BrightBar / VolumeBar call sites.
    Class<?> content =
        Class.forName(TILES + "IconProgressBarKt$$ExternalSyntheticLambda8", false, loader);
    Field kindField = content.getDeclaredField("f$31");
    if (kindField.getType() != int.class
        || content.getDeclaredField("f$1").getType() != float.class
        || content.getDeclaredField("f$2").getType() != modifier)
      throw new UnsupportedOperationException("Slider closure contract");
    Field verticalField = content.getDeclaredField("f$0");
    Field scopeField = content.getDeclaredField("f$8");
    Field contextField = content.getDeclaredField("f$20");
    Class<?> constraints =
        Class.forName(
            "androidx.compose.foundation.layout.BoxWithConstraintsScopeImpl", false, loader);
    Method maxWidth = constraints.getDeclaredMethod("getMaxWidth-D9Ej5fM");
    Method maxHeight = constraints.getDeclaredMethod("getMaxHeight-D9Ej5fM");
    Method invoke = content.getDeclaredMethod("invoke", Object.class, Object.class, Object.class);
    owner.add(
        ModernHook.aroundMethod(
            invoke,
            chain -> {
              BarFrame previous = barFrame.get();
              Object[] args = chain.getArgs().toArray();
              try {
                int discriminator = kindField.getInt(chain.getThisObject());
                if (evidence.add("bar:" + discriminator))
                  HookEntry.log("QUICK_PANEL_BAR " + discriminator);
                int kind = discriminator == 100 ? 0 : discriminator == 150 ? 1 : -1;
                Integer previewKind = previewScopes.get(scopeField.get(chain.getThisObject()));
                if (previewKind != null) kind = previewKind;
                if (kind >= 0 && owner.needsRefresh()) {
                  float dp =
                      (float)
                          ((boolean) verticalField.get(chain.getThisObject())
                                  ? maxWidth
                                  : maxHeight)
                              .invoke(args[0]);
                  boolean grouped =
                      owner.legacy()
                          && groupedScopes.containsKey(scopeField.get(chain.getThisObject()));
                  if (grouped) {
                    android.content.Context context =
                        (android.content.Context) contextField.get(chain.getThisObject());
                    android.content.res.Resources resources = context.getResources();
                    int dimension =
                        resources.getIdentifier(
                            "sec_style_qs_slider_tile_size", "dimen", "com.android.systemui");
                    if (dimension == 0)
                      throw new UnsupportedOperationException("Legacy bar dimension");
                    dp = resources.getDimension(dimension) / resources.getDisplayMetrics().density;
                  }
                  barFrame.set(new BarFrame(kind, dp, grouped));
                  args[2] = (int) args[2] | 1;
                } else barFrame.remove();
                return chain.proceed(args);
              } finally {
                restore(barFrame, previous);
              }
            }));
    Method bar =
        named(
            Class.forName(TILES + "BidirectionalBarKt", false, loader),
            "BidirectionalBar-Ld4roP0",
            19);
    Class<?>[] signature = bar.getParameterTypes();
    if (signature[0] != modifier
        || signature[2] != long.class
        || signature[3] != float.class
        || signature[4] != boolean.class) throw new UnsupportedOperationException("Bar contract");
    owner.add(
        ModernHook.hookMethod(
            bar,
            new ModernHook.Callback() {
              @Override
              protected void beforeHookedMethod(ModernHook.Param p) {
                BarFrame frame = barFrame.get();
                if (frame == null || (owner.size(frame.kind) == 100 && !frame.grouped)) return;
                int kind = frame.kind;
                try {
                  float nativeDp = frame.nativeDp;
                  if (!Float.isFinite(nativeDp) || nativeDp <= 0) return;
                  if (evidence.add("geometry:" + kind))
                    HookEntry.log(
                        "QUICK_PANEL_GEOMETRY kind="
                            + kind
                            + " size="
                            + p.args[2]
                            + " dp="
                            + nativeDp
                            + " vertical="
                            + p.args[4]);
                  float targetDp =
                      frame.grouped
                          ? QuickPanelPolicy.legacyDimension(nativeDp, owner.size(kind))
                          : QuickPanelPolicy.dimension(nativeDp, owner.size(kind));
                  boolean vertical = (boolean) p.args[4];
                  Object next =
                      (vertical ? requiredWidth : requiredHeight)
                          .invoke(null, emptyModifier, targetDp);
                  next = then.invoke(next, p.args[0]);
                  long packed =
                      QuickPanelPolicy.thickness((long) p.args[2], vertical, targetDp / nativeDp);
                  p.args[0] = next;
                  p.args[2] = packed;
                  p.args[3] = targetDp;
                  evidence(kind, nativeDp, targetDp);
                } catch (Throwable error) {
                  owner.fail(error);
                }
              }
            }));
    if (!ModernHook.deoptimize(invoke)) throw new UnsupportedOperationException("Bar call site");
    // Reopening the panel recomposes each native item; changed sizes must reach the inner content.
    for (String name :
        new String[] {"BrightBar", "VolumeBar", "BrightBarWithTile", "VolumeBarWithTile"}) {
      Method root = named(Class.forName(TILES + name + "Kt", false, loader), name, 5);
      owner.add(
          ModernHook.hookMethod(
              root,
              new ModernHook.Callback() {
                @Override
                protected void beforeHookedMethod(ModernHook.Param p) {
                  if (name.endsWith("WithTile") && owner.legacy())
                    groupedScopes.put(p.args[0], true);
                  if (owner.needsRefresh()) p.args[4] = (int) p.args[4] | 1;
                }
              }));
    }
  }

  private void evidence(int kind, float from, float to) {
    String value = kind + ":" + from + ":" + to;
    if (evidence.size() < 64 && evidence.add(value)) HookEntry.log("QUICK_PANEL_SIZE " + value);
  }

  private static <T> void restore(ThreadLocal<T> local, T value) {
    if (value == null) local.remove();
    else local.set(value);
  }

  private static Method named(Class<?> type, String name, int count) throws NoSuchMethodException {
    Method method = null;
    for (Method candidate : type.getDeclaredMethods()) {
      if (!candidate.getName().equals(name) || candidate.getParameterCount() != count) continue;
      if (method != null) throw new NoSuchMethodException("Ambiguous " + name);
      method = candidate;
    }
    if (method == null) throw new NoSuchMethodException(type.getName() + "#" + name);
    return method;
  }

  @Override
  public void refresh() {
    /* Native presenting state remeasures on the next panel opening. */
  }

  @Override
  public void close() {
    barFrame.remove();
    buttonKind.remove();
  }
}
