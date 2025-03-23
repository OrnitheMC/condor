package net.ornithemc.condor.lvt;

import java.util.Iterator;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

public class LocalVariableTables {

	public static boolean isComplete(MethodNode method) {
		// abstract methods have no method body, so no lvt
		if ((method.access & Opcodes.ACC_ABSTRACT) != 0) {
			return true;
		}
		// static methods without parameters and no var instructions
		if (method.maxLocals == 0) {
			return true;
		}

		// if lvt is missing or empty, it's incomplete (given maxLocals > 0)
		if (method.localVariables == null || method.localVariables.isEmpty()) {
			return false;
		}
		// non-static methods should have a 'this' variable at index 0
		if ((method.access & Opcodes.ACC_STATIC) == 0 && !hasThisVariable(method)) {
			return false;
		}
		// check that var indices up to maxLocals are used
		if (!hasMaxLocals(method)) {
			return false;
		}

		return true;
	}

	private static boolean hasThisVariable(MethodNode method) {
		for (LocalVariableNode localVariable : method.localVariables) {
			if (localVariable.index == 0) {
				return true;
			}
		}

		return false;
	}

	private static boolean hasMaxLocals(MethodNode method) {
		int maxLocals = 0;

		for (LocalVariableNode localVariable : method.localVariables) {
			int varIndex = localVariable.index;
			Type type = Type.getType(localVariable.desc);

			if (maxLocals < varIndex + type.getSize()) {
				maxLocals = varIndex + type.getSize();
			}
		}

		return maxLocals == method.maxLocals;
	}

	public static void removeInvalidEntries(ClassNode cls, MethodNode method) {
		if (method.localVariables == null || method.localVariables.isEmpty()) {
			return;
		}

		// Proguard can do very ugly things.
		// One optimization in particular drops local variables to lower
		// indices in the LVT based on when the variable is 'live'.
		// This is not much of a problem except that Proguard likes to
		// reuse indices reserved for method parameters (and some horrific
		// cases even the 'this' variable)

		Type desc = Type.getType(method.desc);
		Type[] args = desc.getArgumentTypes();

		boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
		// index 0 is for the 'this' var (not strictly a param but shh)
		boolean[] paramsFound = new boolean[args.length + 1];

		for (Iterator<LocalVariableNode> it = method.localVariables.iterator(); it.hasNext(); ) {
			LocalVariableNode localVariable = it.next();

			if (!isStatic && localVariable.index == 0) {
				// strip entries that reuse the 'this' var index
				if (paramsFound[0]) {
					it.remove();
				}

				paramsFound[0] = true;
			} else {
				int varsSize = localVariable.index;

				// offset the var index to account for the 'this' var in non-static methods
				if (!isStatic) {
					varsSize--;
				}

				for (int i = 0; i < args.length; i++) {
					if (varsSize == 0) {
						// strip entries that reuse param indices
						if (paramsFound[i + 1]) {
							it.remove();
						} else {
							paramsFound[i + 1] = true;
						}

						break;
					} else {
						varsSize -= args[i].getSize();
					}
				}

				// if parameters contain double or long types (which have size 2)
				// there are indices that are not used by any LVT entry, but that
				// can be reused by later entries - remove those as well
				if (varsSize < 0) {
					it.remove();
				}
			}
		}
	}
}
