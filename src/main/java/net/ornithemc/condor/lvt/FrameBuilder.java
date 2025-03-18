package net.ornithemc.condor.lvt;

import java.util.BitSet;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.ornithemc.condor.representation.Classpath;
import net.ornithemc.condor.util.ASM;

public class FrameBuilder implements Opcodes {

	private final InstructionMarker marker;

	private Classpath classpath;
	private ClassNode cls;
	private MethodNode method;

	private Type[] params;
	private InsnList insns;

	/**
	 * for each insn, the corresponding stack frame
	 */
	StackFrame[] frames;

	/**
	 * for each insn, the corresponding liveness of all locals
	 */
	BitSet[] livenesses;

	private int[] insnsToProcess;
	private int insnsToProcessCount;
	private BitSet inInsnsToProcess;

	public FrameBuilder(InstructionMarker marker) {
		this.marker = marker;
	}

	public void init(Classpath classpath, ClassNode cls, MethodNode method) {
		this.classpath = classpath;
		this.cls = cls;
		this.method = method;

		Type desc = Type.getMethodType(this.method.desc);

		this.params = desc.getArgumentTypes();
		this.insns = this.method.instructions;

		this.frames = new StackFrame[this.insns.size()];
		this.frames[0] = new StackFrame(this.method.maxLocals, this.method.maxStack);

		this.livenesses = new BitSet[this.insns.size()];

		this.insnsToProcess = new int[this.insns.size()];
		this.inInsnsToProcess = new BitSet(this.insns.size());

		for (int i = 0; i < this.livenesses.length; i++) {
			this.livenesses[i] = new BitSet(this.method.maxLocals);
		}
	}

	/**
	 * Compute the initial stack frame.
	 */
	public void computeInitialFrame() {
		String owner = null;

		if ((this.method.access & ACC_STATIC) == 0) {
			owner = this.cls.name;
		}

		this.frames[0].compute(owner, this.params);
	}

	/**
	 * Expand frames from frame instructions.
	 */
	public void expandFrames() {
		StackFrame frame = new StackFrame(this.frames[0]);
		int lastInsnOrFrameIndex = -1;

		for (int insnIndex = 0; insnIndex < this.insns.size(); insnIndex++) {
			AbstractInsnNode insn = this.insns.get(insnIndex);
			int insnType = insn.getType();

			if (insnType == AbstractInsnNode.FRAME) {
				frame.expand(this.cls.name, this.params, (FrameNode) insn);

				if (lastInsnOrFrameIndex < insnIndex) {
					// make sure it's applied to any directly preceding label
					// or line number insns too
					this.frames[lastInsnOrFrameIndex + 1] = new StackFrame(frame);
				}
			}
			if (insnType == AbstractInsnNode.FRAME || !ASM.isPseudoInsn(insnType)) {
				lastInsnOrFrameIndex = insnIndex;
			}
		}
	}

	/**
	 * Compute the stack frames for all instructions.
	 */
	public void computeFrames() {
		// put insns on the stack in reverse order so they'll be processed in order
		for (int insnIndex = this.insns.size() - 1; insnIndex >= 0; insnIndex--) {
			// frames have already been expanded which means it is not guaranteed
			// that all insns will be visited when starting at 0
			if (this.marker.entry[insnIndex]) {
				this.enqueueInsn(insnIndex);
			}
		}

		StackFrame frame = new StackFrame(this.frames[0]);

		for (int insnIndex; (insnIndex = this.nextInsn()) != -1; ) {
			AbstractInsnNode insn = this.insns.get(insnIndex);

			// init frame for this insn
			StackFrame oldFrame = this.frames[insnIndex];
			frame = frame.init(oldFrame);

			int insnType = insn.getType();

			if (ASM.isPseudoInsn(insnType)) {
				this.enqueueInsn(insnIndex + 1, frame);
			} else {
				try {
					frame.compute(insn);
				} catch (Exception e) {
					throw new RuntimeException("error computing frame at instruction " + insnIndex, e);
				}

				this.marker.processThrowInsn(insn, frame);

				if (insnType == AbstractInsnNode.JUMP_INSN || insnType == AbstractInsnNode.TABLESWITCH_INSN || insnType == AbstractInsnNode.LOOKUPSWITCH_INSN) {
					int[] targetIndices = this.marker.jumpTargets[insnIndex];

					for (int targetIndex : targetIndices) {
						this.enqueueInsn(targetIndex, frame);
					}
				} else {
					int opcode = insn.getOpcode();

					if (opcode == RET) {
						throw new UnsupportedOperationException(); // TODO
					} else if (opcode != ATHROW && (opcode < IRETURN || opcode > RETURN)) {
						// execution stops after a return so no insns to enqueue
						// a throw could direct execution to a try-catch handler
						// but that is handled below
						this.enqueueInsn(insnIndex + 1, frame);
					}
				}

				// if this insn is inside a try-catch block, an exception could
				// be thrown in which case execution would flow to the exception
				// handler of that try-catch block
				for (int i = 0; i < this.method.tryCatchBlocks.size(); i++) {
					if (insnIndex < this.marker.tryCatchBlockStarts[i] || insnIndex > this.marker.tryCatchBlockEnds[i]) {
						continue;
					}

					TryCatchBlockNode tryCatchBlock = this.method.tryCatchBlocks.get(i);
					Type handlerType = (tryCatchBlock.type == null)
						? ASM.THROWABLE_TYPE
						: Type.getObjectType(tryCatchBlock.type);
					int handlerIndex = this.insns.indexOf(tryCatchBlock.handler);

					StackFrame handlerFrame = new StackFrame(oldFrame);

					handlerFrame.clear();
					handlerFrame.push(handlerType);

					this.enqueueInsn(handlerIndex, handlerFrame);
				}
			}
		}
	}

	private boolean saveFrame(int insnIndex, StackFrame frame) {
		StackFrame oldFrame = this.frames[insnIndex];

		if (oldFrame == null) {
			this.frames[insnIndex] = new StackFrame(frame);
			return true;
		} else {
			return oldFrame.merge(frame, this.classpath);
		}
	}

	private void enqueueInsn(int insnIndex, StackFrame frame) {
		// if the frame did not change, it must have already been computed
		// in that case, do not enqueue this instruction
		if (this.saveFrame(insnIndex, frame)) {
			this.enqueueInsn(insnIndex);
		}
	}

	private void enqueueInsn(int insnIndex) {
		if (!this.inInsnsToProcess.get(insnIndex)) {
			this.insnsToProcess[this.insnsToProcessCount++] = insnIndex;
			this.inInsnsToProcess.set(insnIndex);
		}
	}

	private int nextInsn() {
		int insnIndex = -1;

		if (this.insnsToProcessCount > 0) {
			insnIndex = this.insnsToProcess[--this.insnsToProcessCount];

			this.insnsToProcess[this.insnsToProcessCount] = -1;
			this.inInsnsToProcess.clear(insnIndex);
		}

		return insnIndex;
	}

	/**
	 * Compute the liveness of all locals for all instructions.
	 */
	public void computeLiveness() {
		// At this stage, locals never 'die' within a linear control flow block.
		// This is to say that, a var index that is first occupied in some frame
		// in the block, is not emptied in any of the subsequent frames in that
		// block.

		// Find the liveness of each var index for each insn. A var index is
		// considered live in all insns between a store and load insn and also
		// in all insns between a load insn and the insn that pops that value off
		// the stack.

		boolean updateLiveness = true;

		while (updateLiveness) {
			updateLiveness = false;

			int firstInsnIndex = 0;
			int lastInsnIndex = this.insns.size() - 1;

			BitSet liveness = new BitSet(this.method.maxLocals);

			for (int insnIndex = lastInsnIndex; insnIndex >= firstInsnIndex; insnIndex--) {
				StackFrame frame = this.frames[insnIndex];

				if (frame == null) {
					continue;
				}

				// find liveness after execution of this insn
				liveness.clear();

				int[] dsts = this.marker.jumpTargets[insnIndex];

				if (dsts != null) {
					for (int dst : dsts) {
						liveness.or(this.livenesses[dst]);
					}
				} else if (insnIndex < lastInsnIndex) {
					liveness.or(this.livenesses[insnIndex + 1]);
				}

				// then compute liveness before execution of this insn
				AbstractInsnNode insn = this.insns.get(insnIndex);

				// some insns pop values off the stack, and some of
				// those values were pushed onto the stack from locals
				int stackDemand = ASM.getStackDemand(insn);

				for (int offset = 1; offset <= stackDemand; offset++) {
					if (frame.peek(offset) == Type.VOID_TYPE) {
						continue;
					}

					// TODO: handle situations where multiple different locals
					//       could be loaded at this point, depending on which
					//       execution path was taken
					int varIndex = frame.peekLocal(offset);

					if (varIndex >= 0) {
						// this local was pushed on to the stack but now popped off!
						liveness.set(varIndex);
					}
				}

				int opcode = insn.getOpcode();

				if (opcode >= ILOAD && opcode <= ALOAD) {
					// locals must be live when pushed onto the stack
					liveness.set(((VarInsnNode) insn).var);

					// do not consider top vars as live (thank you mc indev)
//					if (opcode == LSTORE || opcode == DSTORE) {
//						liveness |= (1 << ((VarInsnNode) insn).var + 1);
//					}
				} else if (opcode >= ISTORE && opcode <= ASTORE) {
					// locals are not live before a store insn
					liveness.clear(((VarInsnNode) insn).var);

					// do not consider top vars as alive (thank you mc indev)
//					if (opcode == LSTORE || opcode == DSTORE) {
//						liveness.set(((VarInsnNode) insn).var + 1);
//					}
				}

				updateLiveness |= this.saveLiveness(insnIndex, liveness);
			}
		}

		// For each store insn, make sure the corresponding local is live at
		// least until the end of that block. This prevents cases where vars
		// are never live.

		BitSet liveness = new BitSet(this.method.maxLocals);
		BitSet storedLocals = new BitSet(this.method.maxLocals);

		int firstInsnIndex = 0;
		int lastInsnIndex = this.insns.size() - 1;

		for (int insnIndex = firstInsnIndex; insnIndex <= lastInsnIndex; insnIndex++) {
			// reset stored locals at the start of a new block
			if (this.marker.entry[insnIndex]) {
				storedLocals.clear();
			}

			// init liveness before execution of this insn
			liveness.clear();
			liveness.or(this.livenesses[insnIndex]);

			// do not update locals that are already live!
			storedLocals.andNot(liveness);

			// update liveness
			liveness.or(storedLocals);

			this.saveLiveness(insnIndex, liveness);

			// then process the insn and update stored locals
			AbstractInsnNode insn = this.insns.get(insnIndex);

			if (!ASM.isPseudoInsn(insn)) {
				int opcode = insn.getOpcode();

				if (opcode >= ISTORE && opcode <= ASTORE) {
					VarInsnNode varInsn = (VarInsnNode) insn;
					int varIndex = varInsn.var;

					storedLocals.set(varIndex);
				}
			}
		}
	}

	private boolean saveLiveness(int insnIndex, BitSet liveness) {
		BitSet oldLiveness = this.livenesses[insnIndex];
		BitSet newLiveness = new BitSet(this.method.maxLocals);

		newLiveness.or(liveness);

		// mark any vars as dead if they do not exist
		// in the frame for this insn
		StackFrame frame = this.frames[insnIndex];

		if (frame != null) {
			for (int varIndex = 0; varIndex < this.method.maxLocals; varIndex++) {
				Type local = frame.getLocal(varIndex);

				// do not consider top vars as alive (thank you mc indev)
				if (local == null || local == Type.VOID_TYPE) {
					newLiveness.clear(varIndex);
				}
			}
		}

		// find whether the liveness changed
		BitSet changedLiveness = new BitSet(this.method.maxLocals);

		changedLiveness.or(newLiveness);
		changedLiveness.andNot(oldLiveness);

		// update liveness to new value
		oldLiveness.or(changedLiveness);

		// return whether the liveness changed
		return !changedLiveness.isEmpty();
	}

	/**
	 * Clean up all stack frames:
	 * <br> - remove unused locals
	 */
	public void processFrames() {
		this.removeUnusedLocals();
	}

	private void removeUnusedLocals() {
		boolean removeLocals = true;

		while (removeLocals) {
			removeLocals = false;

			for (int exitInsnIndex = this.insns.size() - 1; exitInsnIndex > 0; exitInsnIndex--) {
				int[] dsts = this.marker.jumpTargets[exitInsnIndex];

				if (dsts == null) {
					continue;
				}

				// locals used by the exit frame
				BitSet suppliedLocals = new BitSet(this.method.maxLocals);
				// locals used by any of the jump target frames
				BitSet usedLocals = new BitSet(this.method.maxLocals);

				this.frames[exitInsnIndex].markLocals(suppliedLocals);

				for (int dst : dsts) {
					this.frames[dst].markLocals(usedLocals);
				}

				// locals supplied by exit frame but not used by any jump target frame
				BitSet unusedLocals = new BitSet(this.method.maxLocals);

				unusedLocals.or(suppliedLocals);
				unusedLocals.andNot(usedLocals);

				// iterate backwards over each insn in the exit block and remove
				// any unused local from the stack frames until an insn that uses
				// it is encountered
				// stop once the entry of the block is reached or a stack frame
				// is not modified
				// the entry point is the result of any preceding insns, and thus
				// belongs to the corresponding linear control flow blocks
				for (int insnIndex = exitInsnIndex; insnIndex > 0; insnIndex--) {
					if (this.marker.entry[insnIndex]) {
						break;
					}

					BitSet liveness = this.livenesses[insnIndex];

					int unusedLocal = -1;
					int localsRemoved = 0;

					while ((unusedLocal = unusedLocals.nextSetBit(unusedLocal + 1)) != -1) {
						if (liveness.get(unusedLocal)) {
							// do not remove locals that are live this frame
							unusedLocals.clear(unusedLocal);
						} else {
							StackFrame frame = this.frames[insnIndex];

							if (frame.removeLocal(unusedLocal) != null) {
								localsRemoved++;
							}
						}
					}

					if (localsRemoved > 0) {
						// check each insn again, as the previous block might now
						// also be able to remove locals!
						removeLocals = true;
					} else {
						break;
					}
				}
			}
		}
	}
}
