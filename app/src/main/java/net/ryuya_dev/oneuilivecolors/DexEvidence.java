package net.ryuya_dev.oneuilivecolors;

import java.io.File;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.*;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.MethodReference;

/** Finds the real callers by DEX type relationships; no obfuscated name assumptions. */
final class DexEvidence {
  static void deoptimizeVolumeCalls(String apk, ClassLoader loader) throws Exception {
    Set<Member> members = new HashSet<>();
    var dex = DexFileFactory.loadDexContainer(new File(apk), Opcodes.getDefault());
    for (String entry : dex.getDexEntryNames())
      for (ClassDef c : dex.getEntry(entry).getDexFile().getClasses()) {
        if (!c.getType().startsWith("Lcom/android/systemui/volume/view/")
            || c.getType().contains("/subscreen/")) continue;
        Set<String> methods = new HashSet<>();
        for (var m : c.getMethods())
          if (m.getImplementation() != null)
            for (var i : m.getImplementation().getInstructions())
              if (i instanceof ReferenceInstruction r
                  && r.getReference() instanceof MethodReference ref
                  && (ref.getDefiningClass().equals("Lcom/android/systemui/volume/util/BlurEffect;")
                      || ref.getDefiningClass()
                          .equals("Lcom/android/systemui/volume/util/ColorUtils;")))
                methods.add(m.getName());
        if (methods.isEmpty()) continue;
        Class<?> actual =
            Class.forName(
                c.getType().substring(1, c.getType().length() - 1).replace('/', '.'),
                false,
                loader);
        if (methods.contains("<init>"))
          members.addAll(Arrays.asList(actual.getDeclaredConstructors()));
        for (var m : actual.getDeclaredMethods())
          if (methods.contains(m.getName())
              && !Modifier.isAbstract(m.getModifiers())
              && !Modifier.isNative(m.getModifiers())) members.add(m);
      }
    if (members.isEmpty() || members.size() > 96)
      throw new IllegalStateException("Volume callsite deoptimization budget");
    for (Member m : members) ModernHook.deoptimize((java.lang.reflect.Executable) m);
    HookEntry.log("VOLUME_DEX_VERIFIED callers=" + members.size());
  }

  static boolean workspaceVerified;

  static void verifyScrollContract(String apk, String type) throws Exception {
    boolean verified = false;
    var dex = DexFileFactory.loadDexContainer(new File(apk), Opcodes.getDefault());
    String descriptor = "L" + type.replace('.', '/') + ";";
    for (String entry : dex.getDexEntryNames())
      for (ClassDef c : dex.getEntry(entry).getDexFile().getClasses())
        if (c.getType().equals(descriptor))
          for (var m : c.getMethods())
            if (m.getName().equals("notifyScrollX") && m.getImplementation() != null)
              for (var i : m.getImplementation().getInstructions())
                if (i instanceof ReferenceInstruction r
                    && r.getReference() instanceof MethodReference ref
                    && ref.getDefiningClass()
                        .equals("Lcom/honeyspace/sdk/GradientBackgroundManager;")
                    && ref.getName().equals("onScroll")) verified = true;
    if (!verified) throw new IllegalStateException("Missing runtime scroll DEX contract");
  }

  static void verifyAndDeoptimize(String apk, ClassLoader loader) throws Exception {
    Map<String, ClassDef> classes = new HashMap<>();
    var container = DexFileFactory.loadDexContainer(new File(apk), Opcodes.getDefault());
    for (String entry : container.getDexEntryNames()) {
      var dex = container.getEntry(entry);
      if (dex != null) for (ClassDef c : dex.getDexFile().getClasses()) classes.put(c.getType(), c);
    }
    ClassDef workspace =
        classes.get(
            "Lcom/honeyspace/ui/honeypots/workspace/presentation/WorkspaceFastRecyclerView;");
    if (workspace != null)
      for (org.jf.dexlib2.iface.Method m : workspace.getMethods()) {
        if (!"notifyScrollX".equals(m.getName()) || m.getImplementation() == null) continue;
        for (var instruction : m.getImplementation().getInstructions())
          if (instruction instanceof ReferenceInstruction r
              && r.getReference() instanceof MethodReference ref
              && "Lcom/honeyspace/sdk/GradientBackgroundManager;".equals(ref.getDefiningClass())
              && "onScroll".equals(ref.getName())) workspaceVerified = true;
      }
    String contract = "Lcom/honeyspace/sdk/source/GlobalSettingsDataSource;";
    Set<String> sources = new HashSet<>();
    for (ClassDef c : classes.values())
      if (c.getInterfaces().contains(contract)) sources.add(c.getType());
    if (sources.isEmpty() || sources.size() > 4)
      throw new IllegalStateException("Ambiguous settings data source");
    Set<ClassDef> related = new HashSet<>();
    for (ClassDef c : classes.values()) {
      if (sources.contains(c.getType())) related.add(c);
      for (Field f : c.getFields())
        if (sources.contains(f.getType())) {
          related.add(c);
          break;
        }
    }
    if (related.size() > 32) throw new IllegalStateException("Ambiguous settings dependencies");
    boolean reads = false, registers = false, observer = false;
    for (ClassDef c : related) {
      if ("Landroid/database/ContentObserver;".equals(c.getSuperclass())) observer = true;
      for (org.jf.dexlib2.iface.Method m : c.getMethods()) {
        if (m.getImplementation() == null) continue;
        for (var insn : m.getImplementation().getInstructions()) {
          if (!(insn instanceof ReferenceInstruction r)
              || !(r.getReference() instanceof MethodReference ref)) continue;
          if (sources.contains(c.getType())
              && "Landroid/provider/Settings$System;".equals(ref.getDefiningClass())
              && "getString".equals(ref.getName())) reads = true;
          if ("Landroid/content/ContentResolver;".equals(ref.getDefiningClass())
              && "registerContentObserver".equals(ref.getName())) registers = true;
        }
      }
    }
    if (!reads || !registers || !observer)
      throw new IllegalStateException("Missing settings read/observer DEX evidence");
    // API supplied by Vector. Fail closed if the running framework cannot undo inlining.
    List<java.lang.reflect.Method> methods = new ArrayList<>();
    for (ClassDef c : related) {
      String name = c.getType().substring(1, c.getType().length() - 1).replace('/', '.');
      Class<?> actual = Class.forName(name, false, loader);
      for (var m : actual.getDeclaredMethods()) {
        if (!Modifier.isAbstract(m.getModifiers()) && !Modifier.isNative(m.getModifiers()))
          methods.add(m);
      }
    }
    if (methods.size() > 96) throw new IllegalStateException("Deoptimization budget exceeded");
    for (var method : methods)
      if (!ModernHook.deoptimize(method))
        throw new IllegalStateException("Failed to deoptimize verified settings caller");
    HookEntry.log(
        "DEX_VERIFIED: settings read + observer; deoptimized "
            + methods.size()
            + " methods in "
            + related.size()
            + " related classes");
  }
}
