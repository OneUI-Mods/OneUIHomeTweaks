import java.io.File;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;

public class DexReferences {
  public static void main(String[] args) throws Exception {
    var container = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
    for (String entry : container.getDexEntryNames())
      for (ClassDef c : container.getEntry(entry).getDexFile().getClasses())
        for (Method m : c.getMethods()) {
          if (m.getImplementation() == null) continue;
          for (Instruction insn : m.getImplementation().getInstructions())
            if (insn instanceof ReferenceInstruction r
                && r.getReference().toString().contains(args[1])) {
              System.out.println(
                  c.getType()
                      + "->"
                      + m.getName()
                      + m.getParameterTypes()
                      + " : "
                      + r.getReference());
              break;
            }
        }
  }
}
