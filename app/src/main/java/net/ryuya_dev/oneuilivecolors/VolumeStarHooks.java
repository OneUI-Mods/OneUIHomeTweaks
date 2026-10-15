package net.ryuya_dev.oneuilivecolors;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import de.robv.android.xposed.*;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.*;

/** Sound Assistant's dynamically loaded panel, inside SystemUI only. */
final class VolumeStarHooks {
  private static final String PACKAGE = "com.samsung.android.soundassistant";
  private static final Map<String, String> COLORS =
      Map.of(
          "PRIMARY", "volume_star_primary_color",
          "SECONDARY", "volume_star_secondary_color",
          "BACKGROUND", "volume_star_background_color",
          "ON_BACKGROUND", "volume_star_on_background_color");
  private final Set<ClassLoader> seen = Collections.newSetFromMap(new IdentityHashMap<>());
  private final ThreadLocal<Integer> blurDepth = ThreadLocal.withInitial(() -> 0);
  private final List<XC_MethodHook.Unhook> installed = new ArrayList<>();
  private volatile int mode;
  private int lightPreset, darkPreset;
  private boolean failed;
  private Context hostContext;
  private WeakReference<Object> plugin = new WeakReference<>(null);
  private Method reconfigure;
  private String lastColor = "", lastBlur = "";

  void install(Context host, ClassLoader hostLoader, int light, int dark) throws Exception {
    hostContext = host;
    lightPreset = light;
    darkPreset = dark;
    Class<?> wrapper =
        Class.forName(
            "com.samsung.systemui.splugins.SPluginInstanceManager$PluginContextWrapper",
            false,
            hostLoader);
    Constructor<?> constructor = wrapper.getDeclaredConstructor(Context.class, ClassLoader.class);
    Class<?> handler =
        Class.forName(
            "com.samsung.systemui.splugins.SPluginInstanceManager$PluginHandler",
            false,
            hostLoader);
    Method load = handler.getDeclaredMethod("handleLoadPlugin", ComponentName.class);
    XposedBridge.class.getMethod("deoptimizeMethod", Member.class).invoke(null, load);
    XposedBridge.hookMethod(
        constructor,
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (p.hasThrowable()
                || !(p.args[0] instanceof Context ctx)
                || !PACKAGE.equals(ctx.getPackageName())
                || !(p.args[1] instanceof ClassLoader loader)
                || !seen.add(loader)) return;
            try {
              attach(ctx, loader);
            } catch (Throwable t) {
              fail(t);
            }
          }
        });
  }

  private void attach(Context ctx, ClassLoader loader) throws Exception {
    Class<?> impl = Class.forName("com.samsung.systemui.volumestar.VolumeStarImpl", false, loader);
    Class<?> dependency =
        Class.forName("com.samsung.systemui.splugins.volume.VolumeStarDependency", false, loader);
    Method init = impl.getDeclaredMethod("init", Context.class, Context.class, dependency);
    reconfigure = impl.getDeclaredMethod("onConfigurationChanged");
    reconfigure.setAccessible(true);
    var dex =
        DexFileFactory.loadDexContainer(
            new File(ctx.getApplicationInfo().sourceDir), Opcodes.getDefault());
    List<ClassDef> definitions = new ArrayList<>();
    for (String entry : dex.getDexEntryNames())
      definitions.addAll(dex.getEntry(entry).getDexFile().getClasses());
    List<ClassDef> palettes = new ArrayList<>();
    List<ClassDef> barModels = new ArrayList<>();
    Set<String> blurTypes = new HashSet<>();
    for (ClassDef c : definitions) {
      boolean colorTag = false, barTag = false;
      for (var m : c.getMethods())
        if (m.getImplementation() != null)
          for (var i : m.getImplementation().getInstructions())
            if (i instanceof ReferenceInstruction r) {
              if (r.getReference() instanceof StringReference s) {
                if ("ColorThemeWrapper".equals(s.getString())) colorTag = true;
                if ("BarColorModel(progressColor=".equals(s.getString())) barTag = true;
              }
              if (r.getReference() instanceof MethodReference ref
                  && ref.getDefiningClass().equals("Landroid/view/SemBlurInfo$Builder;")
                  && ref.getName().equals("setColorCurvePreset")
                  && !m.getParameterTypes().isEmpty()
                  && (m.getParameterTypes().get(0).equals("Landroid/view/View;")
                      || m.getParameterTypes().get(0).equals("Landroid/widget/ImageView;")))
                blurTypes.add(c.getType());
            }
      if (colorTag) palettes.add(c);
      if (barTag) barModels.add(c);
    }
    if (palettes.size() != 1 || blurTypes.isEmpty() || blurTypes.size() > 3)
      throw new IllegalStateException("VolumeStar palette/blur DEX contract changed");
    Class<?> palette = actual(palettes.get(0).getType(), loader);
    boolean ownerField =
        Arrays.stream(impl.getDeclaredFields()).anyMatch(f -> f.getType() == palette);
    List<Method> colorMethods = new ArrayList<>();
    for (Method m : palette.getDeclaredMethods())
      if (m.getReturnType() == int.class
          && m.getParameterCount() == 1
          && m.getParameterTypes()[0].isEnum()) colorMethods.add(m);
    if (!ownerField || colorMethods.size() != 1)
      throw new IllegalStateException("VolumeStar color signature changed");
    Method color = colorMethods.get(0);
    Set<String> keys = new HashSet<>();
    for (Object e : color.getParameterTypes()[0].getEnumConstants()) keys.add(((Enum<?>) e).name());
    if (!keys.containsAll(
        Set.of("PRIMARY", "SECONDARY", "BACKGROUND", "ON_PRIMARY", "ON_BACKGROUND", "WARNING")))
      throw new IllegalStateException("VolumeStar color keys changed");
    for (String name : COLORS.values())
      if (ctx.getResources().getIdentifier(name, "color", PACKAGE) == 0)
        throw new IllegalStateException("VolumeStar resource missing: " + name);
    for (String name :
        List.of("volume_star_on_primary_color_black", "volume_star_on_primary_color_white"))
      if (ctx.getResources().getIdentifier(name, "color", PACKAGE) == 0)
        throw new IllegalStateException("VolumeStar foreground missing");

    // Undo actual compiled callsites, including obfuscated ones; exclude cover display paths.
    Set<String> targets = new HashSet<>(blurTypes);
    targets.add(palettes.get(0).getType());
    Constructor<?> barConstructor = null;
    if (barModels.size() > 1) throw new IllegalStateException("Ambiguous VolumeStar bar model");
    if (barModels.size() == 1) {
      Class<?> bar = actual(barModels.get(0).getType(), loader);
      if (!android.os.Parcelable.class.isAssignableFrom(bar))
        throw new IllegalStateException("VolumeStar bar model signature changed");
      barConstructor = bar.getDeclaredConstructor(int.class, boolean.class);
      targets.add(barModels.get(0).getType());
    }
    Set<Member> callers = new HashSet<>();
    for (ClassDef c : definitions) {
      if (c.getType().contains("/subscreen/")) continue;
      Set<String> names = new HashSet<>();
      for (var m : c.getMethods())
        if (m.getImplementation() != null)
          for (var i : m.getImplementation().getInstructions())
            if (i instanceof ReferenceInstruction r
                && r.getReference() instanceof MethodReference ref
                && targets.contains(ref.getDefiningClass())) names.add(m.getName());
      if (names.isEmpty()) continue;
      Class<?> type = actual(c.getType(), loader);
      if (names.contains("<init>")) callers.addAll(Arrays.asList(type.getDeclaredConstructors()));
      for (Method m : type.getDeclaredMethods())
        if (names.contains(m.getName())
            && !Modifier.isAbstract(m.getModifiers())
            && !Modifier.isNative(m.getModifiers())) callers.add(m);
    }
    if (callers.isEmpty() || callers.size() > 192)
      throw new IllegalStateException("VolumeStar deoptimization budget");
    Method deopt = XposedBridge.class.getMethod("deoptimizeMethod", Member.class);
    for (Member m : callers) deopt.invoke(null, m);

    if (barConstructor != null)
      installed.add(
          XposedBridge.hookMethod(
              barConstructor,
              new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                  if (!failed && mode != 0) p.args[1] = mode == 2;
                }
              }));
    installed.add(
        XposedBridge.hookMethod(
            color,
            new XC_MethodHook() {
              @Override
              protected void afterHookedMethod(MethodHookParam p) {
                if (failed || mode == 0 || p.hasThrowable()) return;
                try {
                  String key = ((Enum<?>) p.args[0]).name();
                  String name =
                      key.equals("ON_PRIMARY")
                          ? (mode == 1
                              ? "volume_star_on_primary_color_black"
                              : "volume_star_on_primary_color_white")
                          : COLORS.get(key);
                  if (name == null) return; // Warning and unrelated colors retain native behavior.
                  Configuration cfg = new Configuration(ctx.getResources().getConfiguration());
                  cfg.uiMode =
                      (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                          | (mode == 1
                              ? Configuration.UI_MODE_NIGHT_NO
                              : Configuration.UI_MODE_NIGHT_YES);
                  Context themed = ctx.createConfigurationContext(cfg);
                  int id = themed.getResources().getIdentifier(name, "color", PACKAGE);
                  p.setResult(themed.getColor(id));
                  String report = "VOLUMESTAR_COLOR mode=" + mode;
                  if (!report.equals(lastColor)) {
                    lastColor = report;
                    HookEntry.log(report);
                  }
                } catch (Throwable t) {
                  fail(t);
                }
              }
            }));
    for (String descriptor : blurTypes) {
      Class<?> type = actual(descriptor, loader);
      for (Method m : type.getDeclaredMethods()) {
        Class<?>[] parameters = m.getParameterTypes();
        if (parameters.length == 0 || !View.class.isAssignableFrom(parameters[0])) continue;
        installed.add(
            XposedBridge.hookMethod(
                m,
                new XC_MethodHook() {
                  @Override
                  protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.args[0];
                    if (failed
                        || mode == 0
                        || view == null
                        || view.getDisplay() == null
                        || view.getDisplay().getDisplayId() != 0) return;
                    blurDepth.set(blurDepth.get() + 1);
                    p.setObjectExtra("oneUiVolumeBlur", true);
                  }

                  @Override
                  protected void afterHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(p.getObjectExtra("oneUiVolumeBlur")))
                      blurDepth.set(Math.max(0, blurDepth.get() - 1));
                  }
                }));
      }
    }
    Class<?> builder = Class.forName("android.view.SemBlurInfo$Builder");
    installed.add(
        XposedBridge.hookMethod(
            builder.getDeclaredMethod("setColorCurvePreset", int.class),
            new XC_MethodHook() {
              @Override
              protected void beforeHookedMethod(MethodHookParam p) {
                if (failed || mode == 0 || blurDepth.get() == 0) return;
                p.args[0] = mode == 1 ? lightPreset : darkPreset;
                String report = "VOLUMESTAR_BLUR mode=" + mode + " preset=" + p.args[0];
                if (!report.equals(lastBlur)) {
                  lastBlur = report;
                  HookEntry.log(report);
                }
              }
            }));
    installed.add(
        XposedBridge.hookMethod(
            init,
            new XC_MethodHook() {
              @Override
              protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable()) plugin = new WeakReference<>(p.thisObject);
              }
            }));
    HookEntry.log("VOLUMESTAR_HOOK_READY callers=" + callers.size());
  }

  boolean hasError() {
    return failed;
  }

  void setMode(int next) {
    if (mode == next) return;
    mode = failed ? 0 : next;
    Object current = plugin.get();
    if (current == null || failed) return;
    try {
      reconfigure.invoke(current);
    } catch (Throwable t) {
      fail(t);
    }
  }

  private void fail(Throwable t) {
    if (failed) return;
    failed = true;
    mode = 0;
    for (var hook : installed) hook.unhook();
    installed.clear();
    HookEntry.log("VOLUMESTAR_DISABLED " + t);
    HostStatus.report(hostContext, "ERROR", "VolumeStar: " + t.getClass().getSimpleName());
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              try {
                Object current = plugin.get();
                if (current != null && reconfigure != null) reconfigure.invoke(current);
              } catch (Throwable restore) {
                HookEntry.log(
                    "VOLUMESTAR_NATIVE_RESTORE_ERROR " + restore.getClass().getSimpleName());
              }
            });
  }

  private static Class<?> actual(String descriptor, ClassLoader loader)
      throws ClassNotFoundException {
    return Class.forName(
        descriptor.substring(1, descriptor.length() - 1).replace('/', '.'), false, loader);
  }
}
