package net.ornithemc.condor.lvt;

import java.util.Arrays;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import net.ornithemc.condor.representation.ClassInstance;
import net.ornithemc.condor.representation.Classpath;
import net.ornithemc.condor.util.ASM;

public class InstructionMarker implements Opcodes {

	private Classpath classpath;
	private MethodNode method;

	private InsnList insns;

	/**
	 * marks whether an insn is an entry point
	 */
	boolean[] entry;
	/**
	 * marks whether an insn is an exit point
	 */
	boolean[] exit;

	/**
	 * for each insn, lists the target insns of a code jump
	 */
	int[][] jumpSources;
	/**
	 * for each insn, lists the source insns of a code jump
	 */
	int[][] jumpTargets;
	/**
	 * for each insn, gives the insn of the corresponding exception handler
	 */
	int[] exceptionHandlers;

	/**
	 * for each try-catch block, gives the corresponding start insn
	 */
	int[] tryCatchBlockStarts;
	/**
	 * for each try-catch block, gives the corresponding end insn
	 */
	int[] tryCatchBlockEnds;

	public void init(Classpath classpath, ClassNode cls, MethodNode method) {
		this.classpath = classpath;
		this.method = method;

		this.insns = this.method.instructions;

		this.entry = new boolean[this.insns.size()];
		this.exit = new boolean[this.insns.size()];

		// first insn is always an entrypoint
		this.entry[0] = true;

		this.jumpSources = new int[this.insns.size()][];
		this.jumpTargets = new int[this.insns.size()][];
		this.exceptionHandlers = new int[this.insns.size()];

		this.tryCatchBlockStarts = new int[this.method.tryCatchBlocks.size()];
		this.tryCatchBlockEnds = new int[this.method.tryCatchBlocks.size()];

		Arrays.fill(this.exceptionHandlers, -1);

		Arrays.fill(this.tryCatchBlockStarts, -1);
		Arrays.fill(this.tryCatchBlockEnds, -1);
	}

	/**
	 * Mark the start and end insns of each try-catch block.
	 */
	public void markTryCatchBlocks() {
		for (int i = 0; i < this.method.tryCatchBlocks.size(); i++) {
			TryCatchBlockNode tryCatchBlock = this.method.tryCatchBlocks.get(i);

			this.tryCatchBlockStarts[i] = this.insns.indexOf(tryCatchBlock.start);
			this.tryCatchBlockEnds[i] = this.insns.indexOf(tryCatchBlock.end);

			this.entry[this.insns.indexOf(tryCatchBlock.handler)] = true;
		}
	}

	/**
	 * Identify entry points, exit points, and code jumps.
	 */
	public void markEntriesAndExits() {
		for (int insnIndex = 0; insnIndex < this.insns.size(); insnIndex++) {
			AbstractInsnNode insn = this.insns.get(insnIndex);

			int insnType = insn.getType();
			int opcode = insn.getOpcode();

			if (insnType == AbstractInsnNode.JUMP_INSN) {
				int dstIndex = this.insns.indexOf(((JumpInsnNode) insn).label);

				if (dstIndex == insnIndex + 1 || opcode == GOTO || opcode == JSR) {
					this.jump(insnIndex, dstIndex);
				} else {
					// branch insns have two targets, one of which is the very next insn
					this.jump(insnIndex, insnIndex + 1, dstIndex);
				}
			} else if (insnType == AbstractInsnNode.TABLESWITCH_INSN) {
				TableSwitchInsnNode tsInsn = (TableSwitchInsnNode) insn;

				int[] dsts = new int[1 + tsInsn.labels.size()];

				dsts[0] = this.insns.indexOf(tsInsn.dflt);
				for (int i = 1; i < dsts.length; i++) {
					dsts[i] = this.insns.indexOf(tsInsn.labels.get(i - 1));
				}

				this.jump(insnIndex, dsts);
			} else if (insnType == AbstractInsnNode.LOOKUPSWITCH_INSN) {
				LookupSwitchInsnNode lsInsn = (LookupSwitchInsnNode) insn;

				int[] dsts = new int[1 + lsInsn.labels.size()];

				dsts[0] = this.insns.indexOf(lsInsn.dflt);
				for (int i = 1; i < dsts.length; i++) {
					dsts[i] = this.insns.indexOf(lsInsn.labels.get(i - 1));
				}

				this.jump(insnIndex, dsts);
			} else if (opcode >= IRETURN && opcode <= RETURN) {
				this.exit[insnIndex] = true;
			}
		}
	}

	/**
	 * Identify code jumps to exception handlers from the given throw instruction.
	 */
	public void processThrowInsn(AbstractInsnNode insn, StackFrame frame) {
		int insnIndex = this.insns.indexOf(insn);
		int opcode = insn.getOpcode();

		if (opcode == ATHROW) {
			boolean foundHandler = false;

			if (!this.method.tryCatchBlocks.isEmpty()) {
				Type exceptionType = frame.peek();
				ClassInstance exceptionCls = this.classpath.getClass(exceptionType);

				// find exception handler for this exception, record jump
				for (TryCatchBlockNode tryCatchBlock : this.method.tryCatchBlocks) {
					int startInsnIndex = this.insns.indexOf(tryCatchBlock.start);
					int endInsnIndex = this.insns.indexOf(tryCatchBlock.end);

					if (startInsnIndex <= insnIndex && endInsnIndex > insnIndex) {
						Type handlerType = (tryCatchBlock.type == null)
							? ASM.THROWABLE_TYPE
							: Type.getObjectType(tryCatchBlock.type);
						ClassInstance handlerCls = this.classpath.getClass(handlerType);

						if (exceptionCls.hasSuperClass(this.classpath, handlerCls)) {
							int handlerIndex = this.insns.indexOf(tryCatchBlock.handler);

							this.jump(insnIndex, handlerIndex);
							this.exceptionHandlers[insnIndex] = handlerIndex;

							foundHandler = true;
						}
					}
				}
			}

			if (!foundHandler) {
				// no matching exception handler found, mark as exit
				this.exit[insnIndex] = true;
			}
		}
	}

	/**
	 * Identify code jumps to exception handlers inside try-catch blocks.
	 */
	public void processTryCatchBlocks() {
		for (TryCatchBlockNode tryCatchBlock : this.method.tryCatchBlocks) {
			int handlerInsnIndex = this.insns.indexOf(tryCatchBlock.handler);

			// figuring out which insns could lead to an exception that's
			// caught by the handler is hard, so instead treat every insn
			// as suspect but only create jumps at store insns since those
			// are the only ones that change local variables
			// exits/jumps from throw and return insns were already handled
			// by the processInsns step

			int startInsnIndex = this.insns.indexOf(tryCatchBlock.start);
			int endInsnIndex = this.insns.indexOf(tryCatchBlock.end);

			boolean firstInsn = true;
			boolean prevInsnIsStore = false;

			for (int insnIndex = startInsnIndex; insnIndex <=  endInsnIndex; insnIndex++) {
				AbstractInsnNode insn = this.insns.get(insnIndex);

				if (ASM.isPseudoInsn(insn)) {
					continue; // won't cause exceptions
				}

				int[] dsts = this.jumpTargets[insnIndex];

				if (dsts != null) {
					// add handler to jump targets
					boolean found = false;

					for (int dst : dsts) {
						if (dst == handlerInsnIndex) {
							found = true;
						}
					}

					if (!found) {
						dsts = Arrays.copyOf(dsts, dsts.length + 1);
						dsts[dsts.length - 1] = handlerInsnIndex;

						this.jumpTargets[insnIndex] = dsts;
					}
				} else if (this.exit[insnIndex]) {
					// no jumps yet but this insn is an exit
					// add handler as jump target
					this.jump(insnIndex, handlerInsnIndex);
				} else if (firstInsn || prevInsnIsStore) {
					// no jumps or exit yet, but the prev insn
					// was a store
					// there could be an exception here or not,
					// so jump to both handler and next insn
					// if the next insn is not after this block
					if (insnIndex < endInsnIndex) {
						this.jump(insnIndex, insnIndex + 1, handlerInsnIndex);
					} else {
						this.jump(insnIndex, handlerInsnIndex);
					}
				}

				int opcode = insn.getOpcode();

				if (opcode >= ISTORE && opcode <= ASTORE) {
					prevInsnIsStore = true;
				} else {
					prevInsnIsStore = false;
				}

				firstInsn = false;
			}
		}
	}

	/**
	 * Identify exit points that are followed by entry points.
	 */
	public void processEntryPoints() {
		// mark insns before entry points as exits
		for (int insnIndex = 0; insnIndex < this.insns.size() - 1; insnIndex++) {
			if (this.exit[insnIndex] || !this.entry[insnIndex + 1]) {
				continue;
			}

			int[] dsts = this.jumpTargets[insnIndex];

			if (dsts == null) {
				this.jump(insnIndex, insnIndex + 1);
			}
		}
	}

	/**
	 *  Record a jump from the given src insn to the given dst insns.
	 */
	private void jump(int src, int... dsts) {
		this.exit[src] = true;
		dstLoop: for (int dst : dsts) {
			this.entry[dst] = true;

			int[] osrcs = this.jumpSources[dst];
			if (osrcs == null) {
				osrcs = new int[0];
			}

			// make sure each src is only added once
			for (int osrc : osrcs) {
				if (src == osrc) {
					continue dstLoop;
				}
			}

			int[] srcs = Arrays.copyOf(osrcs, osrcs.length + 1);
			this.jumpSources[dst] = srcs;

			srcs[srcs.length - 1] = src; 
		}
		this.jumpTargets[src] = dsts;
	}
}
