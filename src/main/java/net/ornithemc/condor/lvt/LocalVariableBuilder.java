package net.ornithemc.condor.lvt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import net.ornithemc.condor.representation.Classpath;
import net.ornithemc.condor.util.ASM;

public class LocalVariableBuilder {

	private final InstructionMarker marker;
	private final FrameBuilder frames;

	private MethodNode method;

	private InsnList insns;

	public LocalVariableBuilder(InstructionMarker marker, FrameBuilder frames) {
		this.marker = marker;
		this.frames = frames;
	}

	public void init(Classpath classpath, ClassNode cls, MethodNode method) {
		this.method = method;

		this.insns = this.method.instructions;
	}

	/**
	 * Build the local variable table.
	 */
	public void build() {
		// TODO: use FrameBuilder's liveness instead of computing 'exists' here
		//       or maybe not? since they are not quite the same thing

		Type[] lvtTypes = new Type[this.method.maxLocals];

		int[] lvtIndexToVarIndex = new int[this.method.maxLocals];
		int[] lvtIndexToStartInsnIndex = new int[this.method.maxLocals];
		int[] lvtIndexToEndInsnIndex = new int[this.method.maxLocals];

		// for each lvt index, whether that entry exists at each insn
		BitSet[] existence = new BitSet[this.method.maxLocals];
		for (int i = 0; i < existence.length; i++) {
			existence[i] = new BitSet(this.insns.size());
		}

		int varCount = 0;
		int firstNonZeroVarCount = 0;

		// first find all local variables for each linear control flow block
		for (int entry = 0; entry < this.insns.size(); entry++) {
			if (!this.marker.entry[entry]) {
				continue;
			}

			int[] varIndexToLvtIndex = new int[this.method.maxLocals];
			Arrays.fill(varIndexToLvtIndex, -1);

			int insnIndex = entry;
			boolean exit = false;

			for (; !exit; insnIndex++) {
				StackFrame frame = this.frames.frames[insnIndex];

				for (int varIndex = 0; varIndex < this.method.maxLocals; varIndex++) {
					Type type = frame.getLocal(varIndex);

					if (type == null || type == Type.VOID_TYPE) {
						// var no longer exists
						varIndexToLvtIndex[varIndex] = -1;

						continue;
					}

					int lvtIndex = varIndexToLvtIndex[varIndex];

					if (lvtIndex < 0 || !type.equals(lvtTypes[lvtIndex])) {
						// no var exists yet or existing var is different type: create new lvt entry

						lvtIndex = varCount++;

						if (varCount > lvtIndexToVarIndex.length) {
							lvtTypes = Arrays.copyOf(lvtTypes, lvtTypes.length * 2);

							lvtIndexToVarIndex = Arrays.copyOf(lvtIndexToVarIndex, lvtIndexToVarIndex.length * 2);
							lvtIndexToStartInsnIndex = Arrays.copyOf(lvtIndexToStartInsnIndex, lvtIndexToStartInsnIndex.length * 2);
							lvtIndexToEndInsnIndex = Arrays.copyOf(lvtIndexToEndInsnIndex, lvtIndexToEndInsnIndex.length * 2);

							existence = Arrays.copyOf(existence, existence.length * 2);
						}

						lvtTypes[lvtIndex] = type;

						varIndexToLvtIndex[varIndex] = lvtIndex;
						lvtIndexToVarIndex[lvtIndex] = varIndex;
						lvtIndexToStartInsnIndex[lvtIndex] = insnIndex;

						existence[lvtIndex] = new BitSet(this.insns.size());
					}

					// update end index and existence
					lvtIndexToEndInsnIndex[lvtIndex] = insnIndex;
					existence[lvtIndex].set(insnIndex);
				}

				exit = this.marker.exit[insnIndex];

				if (varCount != 0 && firstNonZeroVarCount < 0) {
					firstNonZeroVarCount = varCount;
				}
			}
		}

		// merge any local variables from adjacent linear control flow blocks
		if (varCount > firstNonZeroVarCount) {
			// for each lvt entry, check if there is another entry that
			// can be merged into it
			for (int lvtIndex = varCount - 1; lvtIndex >= 0; ) {
				// if this lvt entry merged into another
				// this lvt index must be checked again
				boolean checkAgain = false;

				boolean existed = false;
				boolean exists = false;

				Type type = lvtTypes[lvtIndex];
				int varIndex = lvtIndexToVarIndex[lvtIndex];
				int fromInsnIndex = lvtIndexToStartInsnIndex[lvtIndex];
				int toInsnIndex = lvtIndexToEndInsnIndex[lvtIndex];

				// bitset to be re-used
				BitSet insnsToCheck = new BitSet(this.insns.size());

				findEntriesToMerge:
				for (int insnIndex = fromInsnIndex; insnIndex <= toInsnIndex; insnIndex++) {
					existed = exists;
					exists = existence[lvtIndex].get(insnIndex);

					// if there are any jumps to or from this insn, check if any lvt entries at
					// the source or target insns can be merged
					// if this insn is an entrypoint, also check lvt entries at the previous insn
					// one block can flow directly into the next with or without a jump!

					// TODO: if an LVT entry is not preceded by a store insn and none of the insns
					//       during its lifetime are store insns, that entry MUST be merged with
					//       another entry!

					insnsToCheck.clear();

					if (exists) {
						int[] srcs = this.marker.jumpSources[insnIndex];

						if (srcs != null) {
							for (int src : srcs) {
								insnsToCheck.set(src);
							}
						}

						// TODO: check if this is needed
						if (!existed && insnIndex > 0 && this.marker.entry[insnIndex]) {
							insnsToCheck.set(insnIndex - 1);
						}

						int[] dsts = this.marker.jumpTargets[insnIndex];

						if (dsts != null) {
							for (int dst : dsts) {
								insnsToCheck.set(dst);
							}
						}
					}

					int insnToCheck = -1;

					while ((insnToCheck = insnsToCheck.nextSetBit(insnToCheck + 1)) != -1) {
						int matchingLvtIndex = -1;

						// find an adjacent lvt entry at insnToCheck that matches the varIndex and type
						for (int adjLvtIndex = 0; matchingLvtIndex < 0 && adjLvtIndex < varCount; adjLvtIndex++) {
							if (adjLvtIndex == lvtIndex || !existence[adjLvtIndex].get(insnToCheck)) {
								continue;
							}

							// check if adjacent entry has matching varIndex and type
							Type adjType = lvtTypes[adjLvtIndex];
							int adjVarIndex = lvtIndexToVarIndex[adjLvtIndex];

							if (adjVarIndex == varIndex && canMergeLocals(adjType, type)) {
								matchingLvtIndex = adjLvtIndex;
							}
						}

						// no matching adjacent lvt entry found
						if (matchingLvtIndex < 0) {
							continue;
						}

						int lvtIndexToMergeInto;
						int lvtIndexToMerge;

						// always merge into the entry with the lower index
						if (lvtIndex < matchingLvtIndex) {
							lvtIndexToMergeInto = lvtIndex;
							lvtIndexToMerge = matchingLvtIndex;
						} else {
							lvtIndexToMergeInto = matchingLvtIndex;
							lvtIndexToMerge = lvtIndex;
						}

						// merge local types
						lvtTypes[lvtIndexToMergeInto] = mergeLocals(lvtTypes[lvtIndexToMergeInto], lvtTypes[lvtIndexToMerge]);

						// merge start and end indices
						lvtIndexToStartInsnIndex[lvtIndexToMergeInto] = Math.min(lvtIndexToStartInsnIndex[lvtIndexToMergeInto], lvtIndexToStartInsnIndex[lvtIndexToMerge]);
						lvtIndexToEndInsnIndex[lvtIndexToMergeInto] = Math.max(lvtIndexToEndInsnIndex[lvtIndexToMergeInto], lvtIndexToEndInsnIndex[lvtIndexToMerge]);

						// merge existence
						existence[lvtIndexToMergeInto].or(existence[lvtIndexToMerge]);

						//  and remove the merged lvt entry from the arrays
						System.arraycopy(lvtTypes, lvtIndexToMerge + 1, lvtTypes, lvtIndexToMerge, lvtTypes.length - (lvtIndexToMerge + 1));

						System.arraycopy(lvtIndexToVarIndex, lvtIndexToMerge + 1, lvtIndexToVarIndex, lvtIndexToMerge, lvtIndexToVarIndex.length - (lvtIndexToMerge + 1));
						System.arraycopy(lvtIndexToStartInsnIndex, lvtIndexToMerge + 1, lvtIndexToStartInsnIndex, lvtIndexToMerge, lvtIndexToStartInsnIndex.length - (lvtIndexToMerge + 1));
						System.arraycopy(lvtIndexToEndInsnIndex, lvtIndexToMerge + 1, lvtIndexToEndInsnIndex, lvtIndexToMerge, lvtIndexToEndInsnIndex.length - (lvtIndexToMerge + 1));

						System.arraycopy(existence, lvtIndexToMerge + 1, existence, lvtIndexToMerge, existence.length - (lvtIndexToMerge + 1));

						// two lvt entries were merged, the variable count is now 1 less!
						varCount--;

						if (lvtIndex == lvtIndexToMergeInto) {
							// if another entry merged into the entry at lvtIndex,
							// it was modified and must be checked again
							checkAgain = true;
						} else {
							// if the entry at lvtIndex merged into another one,
							// it no longer exists, and we must stop looking for
							// entries to merge into lvtIndex since it no longer
							// points to the same entry
							checkAgain = false;

							break findEntriesToMerge;
						}
					}
				}

				if (!checkAgain) {
					lvtIndex--;
				}
			}
		}

		// sort and populate lvt
		this.method.localVariables = new ArrayList<>();

		while (this.method.localVariables.size() < varCount) {
			int nextLvtIndex = Integer.MAX_VALUE;
			int nextVarIndex = Integer.MAX_VALUE;
			int nextStartInsnIndex = Integer.MAX_VALUE;

			for (int lvtIndex = 0; lvtIndex < varCount; lvtIndex++) {
				int varIndex = lvtIndexToVarIndex[lvtIndex];
				int startInsnIndex = lvtIndexToStartInsnIndex[lvtIndex];

				if (varIndex == nextVarIndex ? startInsnIndex < nextStartInsnIndex : varIndex < nextVarIndex) {
					nextLvtIndex = lvtIndex;
					nextVarIndex = varIndex;
					nextStartInsnIndex = startInsnIndex;
				}
			}

			int lvtIndex = nextLvtIndex;

			Type type = lvtTypes[lvtIndex];
			int startInsnIndex = lvtIndexToStartInsnIndex[lvtIndex];
			int endInsnIndex = lvtIndexToEndInsnIndex[lvtIndex];

			if (type == ASM.NULL_TYPE) {
				type = ASM.OBJECT_TYPE;
			}

			int varIndex = lvtIndexToVarIndex[lvtIndex];
			LabelNode startLabel = this.getStartLabel(startInsnIndex);
			LabelNode endLabel = this.getEndLabel(endInsnIndex);
			String desc = type.getDescriptor();
			String name = "var" + this.method.localVariables.size();

			this.method.localVariables.add(new LocalVariableNode(name, desc, null, startLabel, endLabel, varIndex));

			// make sure this lvt entry will not be added multiple times
			lvtIndexToVarIndex[lvtIndex] = Integer.MAX_VALUE;
		}
	}

	private boolean canMergeLocals(Type type1, Type type2) {
		if (type1 == ASM.NULL_TYPE) {
			return type2.getSort() == Type.OBJECT || type2.getSort() == Type.ARRAY;
		}
		if (type2 == ASM.NULL_TYPE) {
			return type1.getSort() == Type.OBJECT || type1.getSort() == Type.ARRAY;
		}

		return type1.equals(type2);
	}

	private Type mergeLocals(Type type1, Type type2) {
		if (type1 == ASM.NULL_TYPE) {
			return type2;
		}
		if (type2 == ASM.NULL_TYPE) {
			return type1;
		}

		return type1;
	}

	private LabelNode getStartLabel(int insnIndex) {
		AbstractInsnNode insn = this.insns.get(insnIndex);

		while (insn.getType() != AbstractInsnNode.LABEL) {
			insn = insn.getPrevious();

			if (insn == null) {
				throw new IllegalStateException("no start label found!");
			}
		}

		return (LabelNode) insn;
	}

	private LabelNode getEndLabel(int insnIndex) {
		AbstractInsnNode insn = this.insns.get(insnIndex);

		while (insn.getType() != AbstractInsnNode.LABEL) {
			insn = insn.getNext();

			// no label found after given insn
			// insert one at the end of the list
			if (insn == null) {
				this.insns.add(insn = new LabelNode());
			}
		}

		return (LabelNode) insn;
	}
}
