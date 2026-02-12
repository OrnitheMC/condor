package net.ornithemc.condor.lvt;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.ornithemc.condor.representation.Classpath;
import net.ornithemc.condor.util.ASM;

public class LocalVariableTweaker implements Opcodes {

	private final InstructionMarker marker;
	private final FrameBuilder frames;

	private Classpath classpath;
	private MethodNode method;

	private Type[] params;
	private Type ret;
	private InsnList insns;

	/**
	 * for each var index, whether it has been processed at each insn
	 */
	BitSet[] processed;

	public LocalVariableTweaker(InstructionMarker marker, FrameBuilder frames) {
		this.marker = marker;
		this.frames = frames;
	}

	public void init(Classpath classpath, ClassNode cls, MethodNode method) {
		this.classpath = classpath;
		this.method = method;

		Type desc = Type.getMethodType(this.method.desc);

		this.params = desc.getArgumentTypes();
		this.ret = desc.getReturnType();
		this.insns = this.method.instructions;

		this.processed = new BitSet[this.method.maxLocals];

		for (int i = 0; i < this.method.maxLocals; i++) {
			this.processed[i] = new BitSet(this.insns.size());
		}
	}

	/**
	 * Tweak locals that are pushed onto the stack and popped by field, method,
	 * or return instructions, and record int local types that are popped by
	 * arithmetic or logic ops.
	 */
	public void processLocalsOnInsn() {
		// first pass: process insns that necessitate that locals have
		// a certain type, like field, method, and return insns
		for (int insnIndex = 0; insnIndex < this.insns.size(); insnIndex++) {
			AbstractInsnNode insn = this.insns.get(insnIndex);
			StackFrame frame = this.frames.frames[insnIndex];

			if (!ASM.isPseudoInsn(insn)) {
				int opcode = insn.getOpcode();

				switch (opcode) {
				case SALOAD:
				case CALOAD:
				case BALOAD:
				case IALOAD:
				case LALOAD:
				case FALOAD:
				case DALOAD:
				case AALOAD:
					{
						Type arrayType = frame.peek2();

						Type value = (opcode == AALOAD)
							? Type.getType(arrayType.getDescriptor().substring(1))
							: arrayType.getElementType();

						this.processLocalsAfterInsn(insnIndex, value);
					}

					break;
				case IASTORE:
				case LASTORE:
				case FASTORE:
				case DASTORE:
				case AASTORE:
				case BASTORE:
				case CASTORE:
				case SASTORE:
					{
						int offset = 3;
						if (opcode == LASTORE || opcode == DASTORE) {
							offset++;
						}
						Type arrayType = frame.peek(offset);

						Type value = (opcode == AASTORE)
							? Type.getType(arrayType.getDescriptor().substring(1))
							: arrayType.getElementType();

						this.processLocalsBeforeInsn(insnIndex, 0, value);
					}

					break;
				case L2I:
				case F2I:
				case D2I:
					{
						this.processLocalsAfterInsn(insnIndex, Type.INT_TYPE);
					}

					break;

				case I2B:
					{
						this.processLocalsBeforeInsn(insnIndex, 0, Type.INT_TYPE);

						this.processLocalsAfterInsn(insnIndex, Type.BYTE_TYPE);
					}

					break;
				case I2S:
					{
						this.processLocalsBeforeInsn(insnIndex, 0, Type.INT_TYPE);

						this.processLocalsAfterInsn(insnIndex, Type.SHORT_TYPE);
					}

					break;
				case I2C:
					{
						this.processLocalsBeforeInsn(insnIndex, 0, Type.INT_TYPE);

						this.processLocalsAfterInsn(insnIndex, Type.CHAR_TYPE);
					}

					break;
				case GETFIELD:
				case GETSTATIC:
					{
						FieldInsnNode fieldInsn = (FieldInsnNode) insn;
						Type fieldType = Type.getType(fieldInsn.desc);

						if (opcode == GETSTATIC) {
							String owner = ((FieldInsnNode) insn).owner;
							Type ownerType = Type.getObjectType(owner);

							this.processLocalsBeforeInsn(insnIndex, 0, ownerType);
						}

						this.processLocalsAfterInsn(insnIndex, fieldType);
					}

					break;
				case PUTFIELD:
				case PUTSTATIC:
					{
						FieldInsnNode fieldInsn = (FieldInsnNode) insn;
						Type fieldType = Type.getType(fieldInsn.desc);

						this.processLocalsBeforeInsn(insnIndex, 0, fieldType);

						if (opcode != PUTSTATIC) {
							String owner = ((FieldInsnNode) insn).owner;
							Type ownerType = Type.getObjectType(owner);

							this.processLocalsBeforeInsn(insnIndex, fieldType.getSize(), ownerType);
						}
					}

					break;
				case INVOKEVIRTUAL:
				case INVOKESPECIAL:
				case INVOKESTATIC:
				case INVOKEINTERFACE:
				case INVOKEDYNAMIC:
					{
						String desc = (opcode == INVOKEDYNAMIC)
							? ((InvokeDynamicInsnNode) insn).desc
							: ((MethodInsnNode) insn).desc;
						Type type = Type.getMethodType(desc);
						Type[] args = type.getArgumentTypes();
						Type ret = type.getReturnType();

						int offset = 0;

						for (int i = args.length - 1; i >= 0; i--) {
							this.processLocalsBeforeInsn(insnIndex, offset, args[i]);

							if (i >= 0) {
								offset += args[i].getSize();
							}
						}

						if (opcode != INVOKESTATIC && opcode != INVOKEDYNAMIC) {
							String owner = ((MethodInsnNode) insn).owner;
							Type ownerType = Type.getObjectType(owner);

							this.processLocalsBeforeInsn(insnIndex, offset, ownerType);
						}

						if (ret != Type.VOID_TYPE) {
							this.processLocalsAfterInsn(insnIndex, ret);
						}
					}

					break;
				case IRETURN:
				case LRETURN:
				case FRETURN:
				case DRETURN:
				case ARETURN:
					{
						this.processLocalsBeforeInsn(insnIndex, 0, this.ret);
					}

					break;
				}
			}
		}

		// second pass: process insns that restrict what type(s) locals
		// can have, like constants, and arithmetic and logic ops
		for (int insnIndex = 0; insnIndex < this.insns.size(); insnIndex++) {
			AbstractInsnNode insn = this.insns.get(insnIndex);
			StackFrame frame = this.frames.frames[insnIndex];

			if (!ASM.isPseudoInsn(insn)) {
				int opcode = insn.getOpcode();

				switch (opcode) {
				case ICONST_M1:
				//case ICONST_0:
				//case ICONST_1:
				case ICONST_2:
				case ICONST_3:
				case ICONST_4:
				case ICONST_5:
				case BIPUSH:
				case SIPUSH:
					// constants 0 and 1 are used as false/true
					// use of other constants mean it's not a boolean expression
					{
						this.processLocalsAfterInsn(insnIndex, Type.INT_TYPE);
					}

					break;
				case IADD:
				case ISUB:
				case IMUL:
				case IDIV:
				case IREM:
					{
						Type value2 = this.updateOperandType(frame.peek(), Type.INT_TYPE);
						Type value1 = this.updateOperandType(frame.peek2(), Type.INT_TYPE);
						Type result = ASM.getIntType(value2, value1);

						this.processLocalsBeforeInsn(insnIndex, 0, value2);
						this.processLocalsBeforeInsn(insnIndex, 1, value1);

						this.processLocalsAfterInsn(insnIndex, result);
					}

					break;
				case ISHL:
				case ISHR:
				case IUSHR:
					{
						Type amount = this.updateOperandType(frame.peek(), Type.INT_TYPE);
						Type value = this.updateOperandType(frame.peek2(), Type.INT_TYPE);

						this.processLocalsBeforeInsn(insnIndex, 0, amount);
						this.processLocalsBeforeInsn(insnIndex, 1, value);

						this.processLocalsAfterInsn(insnIndex, value);
					}

					break;
				case LSHL:
				case LSHR:
				case LUSHR:
					{
						this.processLocalsBeforeInsn(insnIndex, 0, this.updateOperandType(frame.peek(), Type.INT_TYPE));
					}

					break;
				case IINC:
					// iinc does not modify a value on the stack,
					// but the local in the frame directly
					{
						IincInsnNode iincInsn = (IincInsnNode) insn;
						int varIndex = iincInsn.var;
						Type localType = frame.getLocal(iincInsn.var);
						Type value = this.updateOperandType(localType, Type.INT_TYPE);

						BitSet storeInsns = new BitSet(this.insns.size());

						this.collectStoredLocals(storeInsns, insnIndex, varIndex, localType, value);

						if (!storeInsns.isEmpty()) {
							this.tweakLocals(storeInsns, value);
						}
					}

					break;
				case IFLT:
				case IFGE:
				case IFGT:
				case IFLE:
					// equals checks may be used for boolean expressions
					// less than/greater than checks only for int types
					{
						Type value = this.updateOperandType(frame.peek(), Type.INT_TYPE);

						this.processLocalsBeforeInsn(insnIndex, 0, value);
					}

					break;
				case IFNULL:
				case IFNONNULL:
					{
						this.processLocalsBeforeInsn(insnIndex, 0, frame.peek());
					}

					break;
				case IF_ICMPLT:
				case IF_ICMPGE:
				case IF_ICMPGT:
				case IF_ICMPLE:
				case IF_ACMPEQ:
				case IF_ACMPNE:
					// equals checks may be used for boolean expressions
					// less than/greater than checks only for int types
					{
						Type value2 = this.updateOperandType(frame.peek(), Type.INT_TYPE);
						Type value1 = this.updateOperandType(frame.peek2(), Type.INT_TYPE);

						this.processLocalsBeforeInsn(insnIndex, 0, value2);
						this.processLocalsBeforeInsn(insnIndex, 1, value1);
					}

					break;
				}
			}
		}

		// third pass: process insns that may indicate locals might have
		// another type, like equality checks and comparisons
		for (int insnIndex = 0; insnIndex < this.insns.size(); insnIndex++) {
			AbstractInsnNode insn = this.insns.get(insnIndex);
			StackFrame frame = this.frames.frames[insnIndex];

			if (!ASM.isPseudoInsn(insn)) {
				int opcode = insn.getOpcode();

				switch (opcode) {
				case IAND:
				case IOR:
				case IXOR:
					{
						Type value2 = frame.peek();
						Type value1 = frame.peek2();

						Type tweaked2 = this.processLocalsBeforeInsn(insnIndex, 0, Type.BOOLEAN_TYPE);
						Type tweaked1 = this.processLocalsBeforeInsn(insnIndex, 1, Type.BOOLEAN_TYPE);

						if (tweaked2 == Type.BOOLEAN_TYPE && tweaked1 == Type.BOOLEAN_TYPE) {
							this.processLocalsAfterInsn(insnIndex, Type.BOOLEAN_TYPE);
						} else {
							// if the change to boolean was not successful, revert partial change
							if (tweaked2 == Type.BOOLEAN_TYPE) {
								this.resetLocalsBeforeInsn(insnIndex, 0, value2, tweaked2);
							}
							if (tweaked1 == Type.BOOLEAN_TYPE) {
								this.resetLocalsBeforeInsn(insnIndex, 1, value1, tweaked1);
							}

							this.processLocalsAfterInsn(insnIndex, Type.INT_TYPE);
						}
					}

					break;
				case IFEQ:
				case IFNE:
					{
						this.processLocalsBeforeInsn(insnIndex, 0, Type.BOOLEAN_TYPE);
					}

					break;
				case IF_ICMPEQ:
				case IF_ICMPNE:
					{
						Type value2 = frame.peek();
						Type value1 = frame.peek2();

						Type tweaked2 = this.processLocalsBeforeInsn(insnIndex, 0, Type.BOOLEAN_TYPE);
						Type tweaked1 = this.processLocalsBeforeInsn(insnIndex, 1, Type.BOOLEAN_TYPE);

						// if the change to boolean was not successful, revert partial change
						if (tweaked2 == Type.BOOLEAN_TYPE && tweaked1 != Type.BOOLEAN_TYPE) {
							this.resetLocalsBeforeInsn(insnIndex, 0, value2, tweaked2);
						}
						if (tweaked1 == Type.BOOLEAN_TYPE && tweaked2 != Type.BOOLEAN_TYPE) {
							this.resetLocalsBeforeInsn(insnIndex, 1, value1, tweaked1);
						}
					}

					break;
				}
			}
		}
	}

	/**
	 * Check whether the value used by an instruction at the given stack offset was
	 * loaded from locals and whether the type(s) of those locals should be tweaked.
	 */
	private Type processLocalsBeforeInsn(int insnIndex, int stackOffset, Type expectedType) {
		StackFrame frame = this.frames.frames[insnIndex];
		int expectedStackSize = frame.getStackSize() - stackOffset;

		if (expectedStackSize < expectedType.getSize()) {
			return null;
		}

		// check if the value at the given offset on the stack was loaded from a local
		Type stackType = frame.peek(stackOffset + expectedType.getSize());
		int varIndex = frame.peekLocal(stackOffset + expectedType.getSize());

		if (varIndex < 0) {
			return stackType;
		}

		// while we know one varIndex now, due to branching it could be one of several
		// varIndex's loaded depending on which path is taken!
		BitSet storeInsns = new BitSet(this.insns.size());

		this.collectLoadedLocals(storeInsns, insnIndex, expectedStackSize, expectedType);

		// no locals found! the value was pushed onto the stack by another insn
		if (storeInsns.isEmpty()) {
			return stackType;
		}

		return this.tweakLocals(storeInsns, expectedType);
	}

	private void resetLocalsBeforeInsn(int insnIndex, int stackOffset, Type originalType, Type tweakedType) {
		StackFrame frame = this.frames.frames[insnIndex];
		int expectedStackSize = frame.getStackSize() - stackOffset;

		if (expectedStackSize < tweakedType.getSize()) {
			return;
		}

		// check if the value at the given offset on the stack was loaded from a local
		int varIndex = frame.peekLocal(stackOffset + tweakedType.getSize());

		if (varIndex < 0) {
			return;
		}

		// while we know one varIndex, due to branching it could be one of several
		// varIndex's loaded depending on which path is taken!
		BitSet storeInsns = new BitSet(this.insns.size());

		this.collectLoadedLocals(storeInsns, insnIndex, expectedStackSize, tweakedType);

		// no locals found! the value was pushed onto the stack by another insn
		if (storeInsns.isEmpty()) {
			return;
		}

		int storeInsnIndex = -1;

		while ((storeInsnIndex = storeInsns.nextSetBit(storeInsnIndex + 1)) != -1) {
			VarInsnNode storeInsn = (VarInsnNode) this.insns.get(storeInsnIndex);
			StackFrame nextFrame = this.frames.frames[storeInsnIndex + 1];

			int storedVarIndex = storeInsn.var;
			Type storedLocalType = nextFrame.getLocal(storedVarIndex);

			// the local is not in the frame until after the store insn!
			this.tweakLocals(storeInsnIndex + 1, storedVarIndex, storedLocalType, originalType);
		}
	}

	private void collectLoadedLocals(BitSet storeInsns, int endInsnIndex, int expectedStackSize, Type expectedType) {
		this.collectLoadedLocals(new BitSet(this.insns.size()), storeInsns, endInsnIndex, true, expectedStackSize, expectedType);
	}

	private void collectLoadedLocals(BitSet visitedInsns, BitSet storeInsns, int endInsnIndex, boolean skipEndInsn, int expectedStackSize, Type expectedType) {
		for (int insnIndex = endInsnIndex; insnIndex >= 0; insnIndex--) {
			// each insns only needs to be visited once
			if (visitedInsns.get(insnIndex)) {
				break;
			}

			// keep track of which insns are visisted
			visitedInsns.set(insnIndex);

			if (!skipEndInsn || insnIndex != endInsnIndex) {
				AbstractInsnNode insn = this.insns.get(insnIndex);
				int opcode = insn.getOpcode();

				StackFrame frame = this.frames.frames[insnIndex];
				int stackSize = frame.getStackSize();

				if (stackSize == expectedStackSize - expectedType.getSize()) {
					// this frame's stack size matches the expected stack size
					// before the local has been pushed onto it
					if (opcode >= ILOAD && opcode <= ALOAD) {
						// found a load insn, now check that the local type needs
						// tweaking, then find the preceding store insn(s)
						int varIndex = ((VarInsnNode) insn).var;
						Type localType = frame.getLocal(varIndex);

						if (this.isLocalCompatibleWith(localType, expectedType)) {
							this.collectStoredLocals(storeInsns, insnIndex, varIndex, localType, expectedType);
						}
					}

					// for every actual insn, the search ends here?
					if (opcode >= 0) {
						return;
					}
				} else if (stackSize < expectedStackSize) {
					// might happen after branching
					return;
				}
			}

			int[] srcs = this.marker.jumpSources[insnIndex];

			if (srcs != null) {
				for (int src : srcs) {
					this.collectLoadedLocals(visitedInsns, storeInsns, src, false, expectedStackSize, expectedType);
				}

				return;
			}
		}
	}

	private void collectStoredLocals(BitSet storeInsns, int endInsnIndex, int varIndex, Type expectedLocalType, Type expectedType) {
		this.collectStoredLocals(new BitSet(this.insns.size()), storeInsns, endInsnIndex, varIndex, expectedLocalType, expectedType);
	}

	private void collectStoredLocals(BitSet visitedInsns, BitSet storeInsns, int endInsnIndex, int varIndex, Type expectedLocalType, Type expectedType) {
		for (int insnIndex = endInsnIndex; insnIndex >= 0; insnIndex--) {
			// each insns only needs to be visited once
			if (visitedInsns.get(insnIndex)) {
				break;
			}

			// keep track of which insns are visisted
			visitedInsns.set(insnIndex);

			AbstractInsnNode insn = this.insns.get(insnIndex);
			int opcode = insn.getOpcode();

			if (opcode >= ISTORE && opcode <= ASTORE) {
				VarInsnNode varInsn = (VarInsnNode) insn;
				
				if (varInsn.var == varIndex) {
					// found a store insn, now check that the local type
					// matches the one that needs tweaking
					// the local is not in the frame until the next insn!
					StackFrame frame = this.frames.frames[insnIndex + 1];
					Type localType = frame.getLocal(varIndex);

					// a store insn may be followed by a label where the local
					// does not exist
					if (localType != null) {
						if (this.isLocalCompatibleWith(localType, expectedLocalType) && this.isLocalCompatibleWith(localType, expectedType)) {
							storeInsns.set(insnIndex);
						}
					}

					if (!storeInsns.get(insnIndex)) {
						return; // stop searching
					}
				}
			}

			int[] srcs = this.marker.jumpSources[insnIndex];

			if (srcs != null) {
				for (int src : srcs) {
					this.collectStoredLocals(visitedInsns, storeInsns, src, varIndex, expectedLocalType, expectedType);
				}

				return;
			}
		}
	}

	private void processLocalsAfterInsn(int startInsnIndex, Type expectedType) {
		this.processLocalsAfterInsn(new BitSet(this.insns.size()), startInsnIndex, true, expectedType);
	}

	/**
	 * Check whether the value pushed onto the stack by an instruction is stored in
	 * any locals and whether the types of those locals should be tweaked.
	 */
	private void processLocalsAfterInsn(BitSet visitedInsns, int startInsnIndex, boolean skipStartInsn, Type expectedType) {
		for (int insnIndex = startInsnIndex; insnIndex < this.insns.size(); insnIndex++) {
			// each insns only needs to be visited once
			if (visitedInsns.get(insnIndex)) {
				break;
			}

			// keep track of which insns are visisted
			visitedInsns.set(insnIndex);

			if (!skipStartInsn || insnIndex != startInsnIndex) {
				AbstractInsnNode insn = this.insns.get(insnIndex);

				if (!ASM.isPseudoInsn(insn)) {
					int opcode = insn.getOpcode();

					switch (opcode) {
					case GOTO:
						break; // there could be a goto to a store insn
					case ISTORE:
					case LSTORE:
					case FSTORE:
					case DSTORE:
					case ASTORE:
						{
							int varIndex = ((VarInsnNode) insn).var;

							// the local is not in the frame until the next insn!
							StackFrame frame = this.frames.frames[insnIndex + 1];
							Type localType = frame.getLocal(varIndex);

							if (localType != null) {
								Type tweakedType = this.shouldTweakLocal(localType, expectedType)
									? expectedType
									: localType;

								this.tweakLocals(insnIndex + 1, varIndex, localType, tweakedType);
							}
						}

						// fall through
					default:
						return;
					}
				}
			}

			int[] dsts = this.marker.jumpTargets[insnIndex];

			if (dsts != null) {
				for (int dst : dsts) {
					this.processLocalsAfterInsn(visitedInsns, dst, false, expectedType);
				}

				break;
			}
		}
	}

	/**
	 * Tweak locals that store the result of a boolean expression.
	 */
	public void processLocalsOnStore() {
		boolean processLocals = true;

		while (processLocals) {
			processLocals = false;

			for (int insnIndex = 0; insnIndex < this.insns.size(); insnIndex++) {
				AbstractInsnNode insn = this.insns.get(insnIndex);

				if (insn.getOpcode() == ISTORE) {
					int varIndex = ((VarInsnNode) insn).var;
					BitSet processed = this.processed[varIndex];

					// if this varIndex at this insn has already been processed, it
					// either already is a boolean, or its type has been restricted
					// to another int related type
					if (!processed.get(insnIndex + 1)) {
						StackFrame nextFrame = this.frames.frames[insnIndex + 1];
						Type localType = nextFrame.getLocal(varIndex);

						if (localType != null && localType != Type.BOOLEAN_TYPE) {
							if (this.checkBooleanExpression(insnIndex)) {
								this.tweakLocals(insnIndex + 1, varIndex, localType, Type.BOOLEAN_TYPE);

								if (processed.get(insnIndex + 1)) {
									processLocals = true;
								}
							}
						}
					}
				}
			}
		}
	}

	private boolean checkBooleanExpression(int endInsnIndex) {
		return this.checkBooleanExpression(new BitSet(this.insns.size()), endInsnIndex, true);
	}

	private boolean checkBooleanExpression(BitSet visitedInsns, int endInsnIndex, boolean skipEndInsn) {
		for (int insnIndex = endInsnIndex; insnIndex >= 0; insnIndex--) {
			// each insns only needs to be visited once
			if (visitedInsns.get(insnIndex)) {
				break;
			}

			// keep track of which insns are visisted
			visitedInsns.set(insnIndex);

			if (!skipEndInsn || insnIndex != endInsnIndex) {
				AbstractInsnNode insn = this.insns.get(insnIndex);

				if (!ASM.isPseudoInsn(insn)) {
					int opcode = insn.getOpcode();

					switch (opcode) {
					case ICONST_0:
					case ICONST_1:
						break; // valid in boolean expressions, check preceding insns
					case ILOAD:
						{
							StackFrame frame = this.frames.frames[insnIndex];
							int varIndex = ((VarInsnNode) insn).var;

							return frame.getLocal(varIndex) == Type.BOOLEAN_TYPE;
						}
					case GETFIELD:
					case GETSTATIC:
						{
							FieldInsnNode fieldInsn = (FieldInsnNode) insn;
							Type fieldType = Type.getType(fieldInsn.desc);

							return fieldType == Type.BOOLEAN_TYPE;
						}
					case INVOKEVIRTUAL:
					case INVOKESPECIAL:
					case INVOKESTATIC:
					case INVOKEINTERFACE:
					case INVOKEDYNAMIC:
						{
							String desc = (opcode == INVOKEDYNAMIC)
								? ((InvokeDynamicInsnNode) insn).desc
								: ((MethodInsnNode) insn).desc;
							Type type = Type.getMethodType(desc);
							Type ret = type.getReturnType();

							return ret == Type.BOOLEAN_TYPE;
						}
					case IFEQ:
					case IFNE:
					case IFLT:
					case IFGE:
					case IFGT:
					case IFLE:
					case IFNULL:
					case IFNONNULL:
						return true;
					case GOTO:
						break;
					default:
						return false;
					}
					// TODO: check logical operators (IAND, IOR, IXOR, INEG), other equality checks?
					// also check other insns that push ints onto the stack that can be compared and
					// result in a boolean value?
				}
			}

			int[] srcs = this.marker.jumpSources[insnIndex];

			if (srcs != null) {
				// assume srcs.length > 0
				boolean isBooleanExpression = true;

				for (int src : srcs) {
					isBooleanExpression &= this.checkBooleanExpression(visitedInsns, src, false);
				}

				return isBooleanExpression;
			}
		}

		return false;
	}

	private Type tweakLocals(BitSet storeInsns, Type expectedType) {
		// collect unique local types
		Set<Type> localTypes = new HashSet<>();

		int storeInsnIndex = -1;

		while ((storeInsnIndex = storeInsns.nextSetBit(storeInsnIndex + 1)) != -1) {
			VarInsnNode storeInsn = (VarInsnNode) this.insns.get(storeInsnIndex);
			StackFrame nextFrame = this.frames.frames[storeInsnIndex + 1];

			int storedVarIndex = storeInsn.var;
			Type storedLocalType = nextFrame.getLocal(storedVarIndex);

			if (storedLocalType != ASM.NULL_TYPE) {
				localTypes.add(storedLocalType);
			}
		}

		// assign null type to ensure it is updated
		Type commonLocalType = ASM.NULL_TYPE;
		Type tweakedType = ASM.NULL_TYPE;
		Type resultType = ASM.NULL_TYPE;

		for (Type localType : localTypes) {
			commonLocalType = this.classpath.getCommonSuperType(commonLocalType, localType);
		}

		// TODO: special cases for assigning List, Set, Map types and
		//       perhaps other very common types?
		if (this.shouldTweakLocal(commonLocalType, expectedType)) {
			tweakedType = expectedType;
		} else {
			tweakedType = commonLocalType;
		}

		storeInsnIndex = -1;

		while ((storeInsnIndex = storeInsns.nextSetBit(storeInsnIndex + 1)) != -1) {
			VarInsnNode storeInsn = (VarInsnNode) this.insns.get(storeInsnIndex);
			StackFrame nextFrame = this.frames.frames[storeInsnIndex + 1];

			int storedVarIndex = storeInsn.var;
			Type storedLocalType = nextFrame.getLocal(storedVarIndex);

			// the local is not in the frame until after the store insn!
			this.tweakLocals(storeInsnIndex + 1, storedVarIndex, storedLocalType, tweakedType);

			resultType = this.classpath.getCommonSuperType(resultType, nextFrame.getLocal(storedVarIndex));
		}

		return resultType;
	}

	// TODO: tweak stack too?
	private void tweakLocals(int startInsn, int varIndex, Type expectedLocalType, Type tweakedType) {
		BitSet processed = this.processed[varIndex];

		for (int insnIndex = startInsn; insnIndex < this.insns.size(); insnIndex++) {
			StackFrame frame = this.frames.frames[insnIndex];

			if (frame == null) {
				break;
			}

			Type localType = frame.getLocal(varIndex);

			if (!expectedLocalType.equals(localType)) {
				break; // type does not match, different var!
			}
			if (tweakedType.equals(localType) && processed.get(insnIndex)) {
				break; // local was already tweaked
			}

			// if this local was processed before, its current type cannot be
			// discarded, and a common super type must be found instead
			if (processed.get(insnIndex)) {
				tweakedType = this.classpath.getCommonSuperType(tweakedType, localType);
			} else {
				processed.set(insnIndex);
			}

			// set the local type to the new value
			frame.setLocal(varIndex, tweakedType);

			int[] dsts = this.marker.jumpTargets[insnIndex];

			if (dsts != null) {
				for (int dst : dsts) {
					this.tweakLocals(dst, varIndex, expectedLocalType, tweakedType);
				}

				break;
			}
		}
	}

	/**
	 * updates the given operand type so as to be compatible with the other type
	 */
	private Type updateOperandType(Type operand, Type other) {
		if (operand == Type.BOOLEAN_TYPE) {
			return other == Type.BOOLEAN_TYPE ? operand : other;
		}

		return operand; // TODO: use ASM.getIntType(...) instead?
	}

	/**
	 * @return whether the given local type is compatible with the expected type,
	 *         that is to say, whether the local type could be the return value
	 *         of a method that returns the expected type
	 */
	private boolean isLocalCompatibleWith(Type localType, Type expectedType) {
		if (localType == expectedType || localType.equals(expectedType)) {
			return true;
		}

		// given the two types are not equal,
		// check if they are compatible anyway

		int expectedSort = expectedType.getSort();
		int localSort = localType.getSort();

		// check int related types
		if (expectedSort < Type.INT) {
			return localSort == Type.INT;
		}
		// check int type itself
		if (expectedSort == Type.INT) {
			return localSort >= Type.CHAR && localSort <= Type.SHORT;
		}
		// check other primitives (float, long, double)
		if (expectedSort <= Type.DOUBLE) {
			// localType must be equal, which was already checked!
			return false;
		}
		// check array and object types
		if (expectedSort >= Type.ARRAY && localSort >= Type.ARRAY) {
			return localType == ASM.NULL_TYPE || this.classpath.getCommonSuperType(localType, expectedType).equals(expectedType);
		}

		return false;
	}

	/**
	 * @return whether the local type should be tweaked to match the expected
	 *         type, given that it is compatible with the expected type
	 */
	private boolean shouldTweakLocal(Type localType, Type expectedType) {
		assert this.isLocalCompatibleWith(localType, expectedType);

		// given the two types are compatible,
		// check for edge cases

		int expectedSort = expectedType.getSort();
		int localSort = localType.getSort();

		// while ints are compatible with int related types on the JVM level,
		// we'd like the reconstructed locals to have more specific types
		if (expectedSort < Type.INT) {
			return localSort == Type.INT;
		}
		// and the same goes for the null type
		if (expectedSort >= Type.ARRAY && localType.getSort() >= Type.ARRAY) {
			return localType == ASM.NULL_TYPE;
		}

		return false;
	}
}
