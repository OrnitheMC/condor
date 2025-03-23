package net.ornithemc.condor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import net.ornithemc.condor.representation.Classpath;

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

	public static void generate(Classpath classpath, ClassNode classNode, MethodNode method) {
		List<Type> interfaces = null;
		if (classNode.interfaces != null) {
			interfaces = new ArrayList<Type>();
			for (String iface : classNode.interfaces) {
				interfaces.add(Type.getObjectType(iface));
			}
		}

		Type objectType = null;
		if (classNode.superName != null) {
			objectType = Type.getObjectType(classNode.superName);
		}

		// Use Analyzer to generate the bytecode frames
		Analyzer<BasicValue> analyzer = new Analyzer<BasicValue>(
				new Verifier(ASM.API_VERSION, Type.getObjectType(classNode.name), objectType, interfaces, false, classpath));
		try {
			analyzer.analyze(classNode.name, method);
		} catch (AnalyzerException ex) {
			ex.printStackTrace();
		}

		// Get frames from the Analyzer
		Frame<BasicValue>[] frames = analyzer.getFrames();

		// Record the original size of hte method
		int methodSize = method.instructions.size();

		method.localVariables = new ArrayList<LocalVariableNode>();

		LocalVariableNode[] localNodes = new LocalVariableNode[method.maxLocals]; // LocalVariableNodes for current frame
		BasicValue[] locals = new BasicValue[method.maxLocals]; // locals in previous frame, used to work out what changes between frames
		LabelNode[] labels = new LabelNode[methodSize]; // Labels to add to the method, for the markers
		String[] lastKnownType = new String[method.maxLocals];

		// Traverse the frames and work out when locals begin and end
		for (int i = 0; i < methodSize; i++) {
			Frame<BasicValue> f = frames[i];
			if (f == null) {
				continue;
			}
			LabelNode label = null;

			for (int j = 0; j < f.getLocals(); j++) {
				BasicValue local = f.getLocal(j);
				// the analyzer initializes all locals as the uninitiaized value
				if (local == BasicValue.UNINITIALIZED_VALUE) {
					local = null;
				}
				if (local == null && locals[j] == null) {
					continue;
				}
				if (local != null && local.equals(locals[j])) {
					continue;
				}

				if (label == null) {
					AbstractInsnNode existingLabel = method.instructions.get(i);
					if (existingLabel instanceof LabelNode) {
						label = (LabelNode) existingLabel;
					} else {
						labels[i] = label = new LabelNode();
					}
				}
				
				if (local == null && locals[j] != null) {
					method.localVariables.add(localNodes[j]);
					localNodes[j].end = label;
					localNodes[j] = null;
				} else if (local != null) {
					if (locals[j] != null) {
						method.localVariables.add(localNodes[j]);
						localNodes[j].end = label;
						localNodes[j] = null;
					}

					String desc = lastKnownType[j];
					Type localType = local.getType();
					if (localType != null) {
						desc = localType.getSort() >= Type.ARRAY && "null".equals(localType.getInternalName())
								? "Ljava/lang/Object;" : localType.getDescriptor();
					}
					
					localNodes[j] = new LocalVariableNode("var" + j, desc, null, label, null, j);
					if (desc != null) {
						lastKnownType[j] = desc;
					}
				}

				locals[j] = local;
			}
		}

		// Reached the end of the method so flush all current locals and mark the end
		LabelNode label = null;
		for (int k = 0; k < localNodes.length; k++) {
			if (localNodes[k] != null) {
				if (label == null) {
					label = new LabelNode();
					method.instructions.add(label);
				}

				localNodes[k].end = label;
				method.localVariables.add(localNodes[k]);
			}
		}

		// Sort local variables
		if (method.localVariables.size() > 1) {
			List<LocalVariableNode> sortedLocalVariables = new ArrayList<>();
			for (LocalVariableNode localVariable : method.localVariables) {
				int i = 0;
				while (i < sortedLocalVariables.size() && localVariable.index >= sortedLocalVariables.get(i).index) {
					i++;
				}
				sortedLocalVariables.add(i, localVariable);
			}
			method.localVariables = sortedLocalVariables;
		}

		// Insert generated labels into the method body
		for (int n = methodSize - 1; n >= 0; n--) {
			if (labels[n] != null) {
				method.instructions.insert(method.instructions.get(n), labels[n]);
			}
		}
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
