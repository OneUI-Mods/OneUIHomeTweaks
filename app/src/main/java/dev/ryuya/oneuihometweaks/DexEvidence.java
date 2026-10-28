package dev.ryuya.oneuihometweaks;

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
  record CapturedEmitter(java.lang.reflect.Method emit, java.lang.reflect.Field owner) {}

  static void deoptimizeRecentsLiveCalls(
      String apk, ClassLoader loader, List<java.lang.reflect.Method> targets) throws Exception {
    Set<String> identities = new HashSet<>();
    for (var method : targets)
      identities.add(
          "L" + method.getDeclaringClass().getName().replace('.', '/') + ";#" + method.getName());
    identities.add("Lcom/honeyspace/gesture/recentsanimation/RecentsAnimationAction;#start");
    Set<java.lang.reflect.Method> callers = new HashSet<>(targets);
    var dex = DexFileFactory.loadDexContainer(new File(apk), Opcodes.getDefault());
    for (String entry : dex.getDexEntryNames())
      for (ClassDef c : dex.getEntry(entry).getDexFile().getClasses()) {
        Set<String> names = new HashSet<>();
        for (var m : c.getMethods()) {
          if (m.getImplementation() == null) continue;
          for (var instruction : m.getImplementation().getInstructions())
            if (instruction instanceof ReferenceInstruction r
                && r.getReference() instanceof MethodReference ref
                && identities.contains(ref.getDefiningClass() + "#" + ref.getName()))
              names.add(m.getName());
        }
        if (names.isEmpty()) continue;
        Class<?> actual =
            Class.forName(
                c.getType().substring(1, c.getType().length() - 1).replace('/', '.'),
                false,
                loader);
        for (var method : actual.getDeclaredMethods())
          if (names.contains(method.getName())) callers.add(method);
      }
    if (callers.isEmpty() || callers.size() > 96)
      throw new IllegalStateException("Live Recents caller set changed");
    for (var method : callers)
      if (!ModernHook.deoptimize(method))
        throw new IllegalStateException("Cannot deoptimize live Recents caller");
    HookEntry.log("RECENTS_LIVE_DEX_VERIFIED callers=" + callers.size());
  }

  static CapturedEmitter capturedEmitter(
      String apk,
      ClassLoader loader,
      Class<?> ownerType,
      Class<?> payload,
      String marker,
      String calledMethod)
      throws Exception {
    String ownerDescriptor = "L" + ownerType.getName().replace('.', '/') + ";";
    String payloadDescriptor = "L" + payload.getName().replace('.', '/') + ";";
    List<CapturedEmitter> matches = new ArrayList<>();
    var dex = DexFileFactory.loadDexContainer(new File(apk), Opcodes.getDefault());
    for (String entry : dex.getDexEntryNames())
      for (ClassDef c : dex.getEntry(entry).getDexFile().getClasses()) {
        if (!c.getInterfaces().contains("Lkotlinx/coroutines/flow/FlowCollector;")) continue;
        int captures = 0;
        for (var f : c.getFields()) if (f.getType().equals(ownerDescriptor)) captures++;
        if (captures != 1) continue;
        for (var m : c.getMethods()) {
          if (!m.getName().equals("emit")
              || m.getImplementation() == null
              || !m.getParameterTypes()
                  .toString()
                  .equals("[" + payloadDescriptor + ", Lkotlin/coroutines/Continuation;]"))
            continue;
          boolean text = marker == null, call = false;
          for (var i : m.getImplementation().getInstructions())
            if (i instanceof ReferenceInstruction r) {
              if (r.getReference() instanceof org.jf.dexlib2.iface.reference.StringReference s
                  && marker != null
                  && s.getString().contains(marker)) text = true;
              if (r.getReference() instanceof MethodReference ref
                  && ref.getName().equals(calledMethod)) call = true;
            }
          if (!text || !call) continue;
          Class<?> actual =
              Class.forName(
                  c.getType().substring(1, c.getType().length() - 1).replace('/', '.'),
                  false,
                  loader);
          java.lang.reflect.Field owner = null;
          for (var f : actual.getDeclaredFields()) if (f.getType() == ownerType) owner = f;
          if (owner == null) continue;
          owner.setAccessible(true);
          var emit =
              actual.getDeclaredMethod(
                  "emit", payload, Class.forName("kotlin.coroutines.Continuation", false, loader));
          emit.setAccessible(true);
          for (var caller : actual.getDeclaredMethods())
            if (!ModernHook.deoptimize(caller))
              throw new IllegalStateException("Cannot deoptimize captured emitter");
          matches.add(new CapturedEmitter(emit, owner));
        }
      }
    if (matches.size() != 1)
      throw new IllegalStateException("Ambiguous captured emitter " + payload.getName());
    return matches.get(0);
  }

  /** Prevent compiled native callers from bypassing the layout guard and write journal. */
  static int deoptimizeLayoutCalls(String apk, ClassLoader loader) throws Exception {
    Set<String> targets =
        Set.of(
            "Lz5/b;#j",
            "Lcom/honeyspace/ui/honeypots/freegrid/viewmodel/FreeGridViewModel;#updateAllItemByLayoutChanged",
            "Lcom/honeyspace/ui/honeypots/freegrid/domain/model/FreeGridDataSanitizer;#sanitizeOnCellLayoutSizeChanged",
            "Lcom/honeyspace/ui/honeypots/freegrid/domain/repository/FreeGridRepository;#update");
    Set<java.lang.reflect.Executable> callers = new HashSet<>();
    var dex = DexFileFactory.loadDexContainer(new File(apk), Opcodes.getDefault());
    for (String entry : dex.getDexEntryNames())
      for (ClassDef type : dex.getEntry(entry).getDexFile().getClasses()) {
        Set<String> names = new HashSet<>();
        for (var method : type.getMethods()) {
          if (method.getImplementation() == null) continue;
          for (var instruction : method.getImplementation().getInstructions())
            if (instruction instanceof ReferenceInstruction refInstruction
                && refInstruction.getReference() instanceof MethodReference ref
                && targets.contains(ref.getDefiningClass() + "#" + ref.getName()))
              names.add(method.getName());
        }
        if (names.isEmpty()) continue;
        Class<?> actual =
            Class.forName(
                type.getType().substring(1, type.getType().length() - 1).replace('/', '.'),
                false,
                loader);
        if (names.contains("<init>"))
          callers.addAll(Arrays.asList(actual.getDeclaredConstructors()));
        for (var method : actual.getDeclaredMethods())
          if (names.contains(method.getName())
              && !Modifier.isAbstract(method.getModifiers())
              && !Modifier.isNative(method.getModifiers())) callers.add(method);
      }
    if (callers.isEmpty() || callers.size() > 160)
      throw new UnsupportedOperationException("Layout caller contract changed");
    for (var caller : callers)
      if (!ModernHook.deoptimize(caller))
        throw new UnsupportedOperationException("Layout caller deoptimization unavailable");
    return callers.size();
  }

  static void deoptimizeDiyPositions(String apk, ClassLoader loader) throws Exception {
    Set<java.lang.reflect.Executable> callers = new HashSet<>();
    var dex = DexFileFactory.loadDexContainer(new File(apk), Opcodes.getDefault());
    for (String entry : dex.getDexEntryNames())
      for (ClassDef c : dex.getEntry(entry).getDexFile().getClasses()) {
        Set<String> names = new HashSet<>();
        for (var method : c.getMethods()) {
          if (method.getImplementation() == null) continue;
          for (var instruction : method.getImplementation().getInstructions())
            if (instruction instanceof ReferenceInstruction refInstruction
                && refInstruction.getReference() instanceof MethodReference ref
                && ((ref.getDefiningClass()
                            .startsWith(
                                "Lcom/honeyspace/ui/honeypots/freegrid/domain/model/FreeGridItem")
                        && (ref.getName().equals("getX") || ref.getName().equals("getY"))
                        && ref.getParameterTypes().isEmpty()
                        && ref.getReturnType().equals("I"))
                    || (ref.getDefiningClass()
                            .equals(
                                "Lcom/honeyspace/ui/honeypots/freegrid/presentation/FreeGridCellLayout;")
                        && Set.of(
                                "prepareSyncPosition",
                                "syncPosition",
                                "translateTarget",
                                "createLayoutParams")
                            .contains(ref.getName()))
                    || (ref.getDefiningClass()
                            .equals(
                                "Lcom/honeyspace/ui/honeypots/freegrid/viewmodel/FreeGridViewModel;")
                        && ref.getName().equals("updateToDropPoint")))) names.add(method.getName());
        }
        if (names.isEmpty()) continue;
        Class<?> actual =
            Class.forName(
                c.getType().substring(1, c.getType().length() - 1).replace('/', '.'),
                false,
                loader);
        if (names.contains("<init>"))
          callers.addAll(Arrays.asList(actual.getDeclaredConstructors()));
        for (var method : actual.getDeclaredMethods())
          if (names.contains(method.getName())
              && !Modifier.isAbstract(method.getModifiers())
              && !Modifier.isNative(method.getModifiers())) callers.add(method);
      }
    if (callers.isEmpty() || callers.size() > 256)
      throw new UnsupportedOperationException("DIY coordinate callsite contract changed");
    for (var caller : callers)
      if (!ModernHook.deoptimize(caller))
        throw new UnsupportedOperationException("Cannot deoptimize DIY coordinate caller");
    HookEntry.log("DIY_GRID_DEX_VERIFIED coordinate callers=" + callers.size());
  }

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
