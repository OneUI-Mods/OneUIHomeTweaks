package dev.ryuya.oneuihometweaks

import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.Arrays
import java.util.HashMap
import java.util.HashSet
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.log
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference

/* Finds the real callers by DEX type relationships; no obfuscated name assumptions. */
object DexEvidence {
    @JvmStatic
    @Throws(Exception::class)
    fun deoptimizeRecentsLiveCalls(
        apk: String?,
        loader: ClassLoader?,
        targets: MutableList<Method?>,
    ) {
        val identities = HashSet<String?>()
        for (method in targets) identities.add(
            "L" +
                method!!.getDeclaringClass().getName().replace('.', '/') +
                ";#" +
                method!!.getName()
        )
        identities.add("Lcom/honeyspace/gesture/recentsanimation/RecentsAnimationAction;#start")
        val callers = HashSet<Method?>(targets)
        val dex = DexFileFactory.loadDexContainer(File(apk), Opcodes.getDefault())
        for (entry in dex.getDexEntryNames()) for (c in
            dex.getEntry(entry)!!.getDexFile().getClasses()) {
            val names = HashSet<String?>()
            for (m in c.getMethods()) {
                if (m.getImplementation() == null) continue
                for (instruction in m.getImplementation()!!.getInstructions()) run {
                    val patternPrefix0 = instruction is ReferenceInstruction
                    val ref =
                        if (patternPrefix0) instruction.getReference() as? MethodReference else null
                    if (
                        patternPrefix0 &&
                            ref != null &&
                            identities.contains(ref.getDefiningClass() + "#" + ref.getName())
                    )
                        names.add(m.getName())
                }
            }
            if (names.isEmpty()) continue
            val actual =
                Class.forName(
                    c.getType().substring(1, c.getType().length - 1).replace('/', '.'),
                    false,
                    loader,
                )
            for (method in actual.getDeclaredMethods()) if (names.contains(method.getName()))
                callers.add(method)
        }
        check(!(callers.isEmpty() || callers.size > 96)) { "Live Recents caller set changed" }
        for (method in callers) check(ModernHook.deoptimize(method!!)) {
            "Cannot deoptimize live Recents caller"
        }
        HookEntry.log("RECENTS_LIVE_DEX_VERIFIED callers=" + callers.size)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun capturedEmitter(
        apk: String?,
        loader: ClassLoader?,
        ownerType: Class<*>,
        payload: Class<*>,
        marker: String?,
        calledMethod: String?,
    ): DexEvidence.CapturedEmitter? {
        val ownerDescriptor: String = "L" + ownerType.getName().replace('.', '/') + ";"
        val payloadDescriptor: String = "L" + payload.getName().replace('.', '/') + ";"
        val matches = ArrayList<DexEvidence.CapturedEmitter?>()
        val dex = DexFileFactory.loadDexContainer(File(apk), Opcodes.getDefault())
        for (entry in dex.getDexEntryNames()) for (c in
            dex.getEntry(entry)!!.getDexFile().getClasses()) {
            if (!c.getInterfaces().contains("Lkotlinx/coroutines/flow/FlowCollector;")) continue
            var captures: Int = 0
            for (f in c.getFields()) if (f.getType() == ownerDescriptor) captures++
            if (captures != 1) continue
            for (m in c.getMethods()) {
                if (
                    (m.getName() != "emit") ||
                        m.getImplementation() == null ||
                        (m.getParameterTypes().toString() !=
                            "[" + payloadDescriptor + ", Lkotlin/coroutines/Continuation;]")
                )
                    continue
                var text: Boolean = marker == null
                var call: Boolean = false
                for (i in m.getImplementation()!!.getInstructions()) if (
                    i is ReferenceInstruction
                ) {
                    run {
                        val s = i.getReference() as? StringReference
                        if (s != null && marker != null && s.getString().contains(marker))
                            text = true
                    }
                    run {
                        val ref = i.getReference() as? MethodReference
                        if (ref != null && ref.getName() == calledMethod) call = true
                    }
                }
                if (!text || !call) continue
                val actual =
                    Class.forName(
                        c.getType().substring(1, c.getType().length - 1).replace('/', '.'),
                        false,
                        loader,
                    )
                var owner: Field? = null
                for (f in actual.getDeclaredFields()) if (f.getType() == ownerType) owner = f
                if (owner == null) continue
                owner.setAccessible(true)
                val emit =
                    actual.getDeclaredMethod(
                        "emit",
                        payload,
                        Class.forName("kotlin.coroutines.Continuation", false, loader),
                    )
                emit.setAccessible(true)
                for (caller in actual.getDeclaredMethods()) check(ModernHook.deoptimize(caller)) {
                    "Cannot deoptimize captured emitter"
                }
                matches.add(DexEvidence.CapturedEmitter(emit, owner))
            }
        }
        check(matches.size == 1) { "Ambiguous captured emitter " + payload.getName() }
        return matches.get(0)
    }

    @JvmStatic /* Prevent compiled native callers from bypassing the layout guard and write journal. */
    @Throws(Exception::class)
    fun deoptimizeLayoutCalls(apk: String?, loader: ClassLoader?): Int {
        val targets =
            java.util.Set.of<String?>(
                "Lz5/b;#j",
                "Lcom/honeyspace/ui/honeypots/freegrid/viewmodel/FreeGridViewModel;#updateAllItemByLayoutChanged",
                "Lcom/honeyspace/ui/honeypots/freegrid/domain/model/FreeGridDataSanitizer;#sanitizeOnCellLayoutSizeChanged",
                "Lcom/honeyspace/ui/honeypots/freegrid/domain/repository/FreeGridRepository;#update",
            )
        val callers = HashSet<Executable?>()
        val dex = DexFileFactory.loadDexContainer(File(apk), Opcodes.getDefault())
        for (entry in dex.getDexEntryNames()) for (type in
            dex.getEntry(entry)!!.getDexFile().getClasses()) {
            val names = HashSet<String?>()
            for (method in type.getMethods()) {
                if (method.getImplementation() == null) continue
                for (instruction in method.getImplementation()!!.getInstructions()) run {
                    val patternPrefix1 = instruction is ReferenceInstruction
                    val ref =
                        if (patternPrefix1) instruction.getReference() as? MethodReference else null
                    if (
                        patternPrefix1 &&
                            ref != null &&
                            targets.contains(ref.getDefiningClass() + "#" + ref.getName())
                    )
                        names.add(method.getName())
                }
            }
            if (names.isEmpty()) continue
            val actual =
                Class.forName(
                    type.getType().substring(1, type.getType().length - 1).replace('/', '.'),
                    false,
                    loader,
                )
            if (names.contains("<init>"))
                callers.addAll(Arrays.asList<Constructor<*>?>(*actual.getDeclaredConstructors()))
            for (method in actual.getDeclaredMethods()) if (
                names.contains(method.getName()) &&
                    !Modifier.isAbstract(method.getModifiers()) &&
                    !Modifier.isNative(method.getModifiers())
            )
                callers.add(method)
        }
        if (callers.isEmpty() || callers.size > 160)
            throw UnsupportedOperationException("Layout caller contract changed")
        for (caller in callers) if (!ModernHook.deoptimize(caller!!))
            throw UnsupportedOperationException("Layout caller deoptimization unavailable")
        return callers.size
    }

    @JvmStatic
    @Throws(Exception::class)
    fun deoptimizeDiyPositions(apk: String?, loader: ClassLoader?) {
        val callers = HashSet<Executable?>()
        val dex = DexFileFactory.loadDexContainer(File(apk), Opcodes.getDefault())
        for (entry in dex.getDexEntryNames()) for (c in
            dex.getEntry(entry)!!.getDexFile().getClasses()) {
            val names = HashSet<String?>()
            for (method in c.getMethods()) {
                if (method.getImplementation() == null) continue
                for (instruction in method.getImplementation()!!.getInstructions()) {
                    val ref =
                        (instruction as? ReferenceInstruction)?.getReference() as? MethodReference
                    if (DexCallsitePolicy.isDiyPositionCall(ref)) names.add(method.getName())
                }
            }
            if (names.isEmpty()) continue
            val actual =
                Class.forName(
                    c.getType().substring(1, c.getType().length - 1).replace('/', '.'),
                    false,
                    loader,
                )
            if (names.contains("<init>"))
                callers.addAll(Arrays.asList<Constructor<*>?>(*actual.getDeclaredConstructors()))
            for (method in actual.getDeclaredMethods()) if (
                names.contains(method.getName()) &&
                    !Modifier.isAbstract(method.getModifiers()) &&
                    !Modifier.isNative(method.getModifiers())
            )
                callers.add(method)
        }
        if (callers.isEmpty() || callers.size > 256)
            throw UnsupportedOperationException("DIY coordinate callsite contract changed")
        for (caller in callers) if (!ModernHook.deoptimize(caller!!))
            throw UnsupportedOperationException("Cannot deoptimize DIY coordinate caller")
        HookEntry.log("DIY_GRID_DEX_VERIFIED coordinate callers=" + callers.size)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun deoptimizeVolumeCalls(apk: String?, loader: ClassLoader?) {
        val members = HashSet<Member?>()
        val dex = DexFileFactory.loadDexContainer(File(apk), Opcodes.getDefault())
        for (entry in dex.getDexEntryNames()) for (c in
            dex.getEntry(entry)!!.getDexFile().getClasses()) {
            if (
                !c.getType().startsWith("Lcom/android/systemui/volume/view/") ||
                    c.getType().contains("/subscreen/")
            )
                continue
            val methods = HashSet<String?>()
            for (m in c.getMethods()) if (m.getImplementation() != null)
                for (instruction in m.getImplementation()!!.getInstructions()) {
                    val ref =
                        (instruction as? ReferenceInstruction)?.getReference() as? MethodReference
                    if (DexCallsitePolicy.isVolumeCall(ref)) methods.add(m.getName())
                }
            if (methods.isEmpty()) continue
            val actual =
                Class.forName(
                    c.getType().substring(1, c.getType().length - 1).replace('/', '.'),
                    false,
                    loader,
                )
            if (methods.contains("<init>"))
                members.addAll(Arrays.asList<Constructor<*>?>(*actual.getDeclaredConstructors()))
            for (m in actual.getDeclaredMethods()) if (
                methods.contains(m.getName()) &&
                    !Modifier.isAbstract(m.getModifiers()) &&
                    !Modifier.isNative(m.getModifiers())
            )
                members.add(m)
        }
        check(!(members.isEmpty() || members.size > 96)) { "Volume callsite deoptimization budget" }
        for (m in members) ModernHook.deoptimize((m as Executable?)!!)
        HookEntry.log("VOLUME_DEX_VERIFIED callers=" + members.size)
    }

    @JvmField var workspaceVerified: Boolean = false

    @JvmStatic
    @Throws(Exception::class)
    fun verifyScrollContract(apk: String?, type: String) {
        var verified: Boolean = false
        val dex = DexFileFactory.loadDexContainer(File(apk), Opcodes.getDefault())
        val descriptor: String = "L" + type.replace('.', '/') + ";"
        for (entry in dex.getDexEntryNames()) for (c in
            dex.getEntry(entry)!!.getDexFile().getClasses()) if (c.getType() == descriptor)
            for (m in c.getMethods()) if (
                m.getName() == "notifyScrollX" && m.getImplementation() != null
            )
                for (i in m.getImplementation()!!.getInstructions()) run {
                    val patternPrefix4 = i is ReferenceInstruction
                    val ref = if (patternPrefix4) i.getReference() as? MethodReference else null
                    if (
                        patternPrefix4 &&
                            ref != null &&
                            ref.getDefiningClass() ==
                                "Lcom/honeyspace/sdk/GradientBackgroundManager;" &&
                            ref.getName() == "onScroll"
                    )
                        verified = true
                }
        check(verified) { "Missing runtime scroll DEX contract" }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun verifyAndDeoptimize(apk: String?, loader: ClassLoader?) {
        val classes = HashMap<String?, ClassDef?>()
        val container = DexFileFactory.loadDexContainer(File(apk), Opcodes.getDefault())
        for (entry in container.getDexEntryNames()) {
            val dex = container.getEntry(entry)
            if (dex != null) for (c in dex.getDexFile().getClasses()) classes.put(c.getType(), c)
        }
        val workspace =
            classes.get(
                "Lcom/honeyspace/ui/honeypots/workspace/presentation/WorkspaceFastRecyclerView;"
            )
        if (workspace != null)
            for (m in workspace.getMethods()) {
                if ("notifyScrollX" != m.getName() || m.getImplementation() == null) continue
                for (instruction in m.getImplementation()!!.getInstructions()) run {
                    val patternPrefix5 = instruction is ReferenceInstruction
                    val ref =
                        if (patternPrefix5) instruction.getReference() as? MethodReference else null
                    if (
                        patternPrefix5 &&
                            ref != null &&
                            "Lcom/honeyspace/sdk/GradientBackgroundManager;" ==
                                ref.getDefiningClass() &&
                            "onScroll" == ref.getName()
                    )
                        DexEvidence.workspaceVerified = true
                }
            }
        val contract: String = "Lcom/honeyspace/sdk/source/GlobalSettingsDataSource;"
        val sources = HashSet<String?>()
        for (c in classes.values) if (c!!.getInterfaces().contains(contract))
            sources.add(c!!.getType())
        check(!(sources.isEmpty() || sources.size > 4)) { "Ambiguous settings data source" }
        val related = HashSet<ClassDef?>()
        for (c in classes.values) {
            if (sources.contains(c!!.getType())) related.add(c)
            for (f in c!!.getFields()) if (sources.contains(f.getType())) {
                related.add(c)
                break
            }
        }
        check(related.size <= 32) { "Ambiguous settings dependencies" }
        var reads: Boolean = false
        var registers: Boolean = false
        var observer: Boolean = false
        for (c in related) {
            if ("Landroid/database/ContentObserver;" == c!!.getSuperclass()) observer = true
            for (m in c!!.getMethods()) {
                if (m.getImplementation() == null) continue
                for (insn in m.getImplementation()!!.getInstructions()) {
                    if (insn !is ReferenceInstruction || insn.getReference() !is MethodReference)
                        continue
                    val ref = insn.getReference() as MethodReference
                    if (
                        sources.contains(c!!.getType()) &&
                            "Landroid/provider/Settings\$System;" == ref.getDefiningClass() &&
                            "getString" == ref.getName()
                    )
                        reads = true
                    if (
                        "Landroid/content/ContentResolver;" == ref.getDefiningClass() &&
                            "registerContentObserver" == ref.getName()
                    )
                        registers = true
                }
            }
        }
        check(!(!reads || !registers || !observer)) {
            "Missing settings read/observer DEX evidence"
        }
        // API supplied by Vector. Fail closed if the running framework cannot undo inlining.
        val methods = ArrayList<Method?>()
        for (c in related) {
            val name = c!!.getType().substring(1, c!!.getType().length - 1).replace('/', '.')
            val actual = Class.forName(name, false, loader)
            for (m in actual.getDeclaredMethods()) {
                if (!Modifier.isAbstract(m.getModifiers()) && !Modifier.isNative(m.getModifiers()))
                    methods.add(m)
            }
        }
        check(methods.size <= 96) { "Deoptimization budget exceeded" }
        for (method in methods) check(ModernHook.deoptimize(method!!)) {
            "Failed to deoptimize verified settings caller"
        }
        HookEntry.log(
            ("DEX_VERIFIED: settings read + observer; deoptimized " +
                methods.size +
                " methods in " +
                related.size +
                " related classes")
        )
    }

    @JvmRecord
    data class CapturedEmitter(val emit: Method?, val owner: Field?) {
        override fun toString(): String = "CapturedEmitter[emit=$emit, owner=$owner]"
    }
}
