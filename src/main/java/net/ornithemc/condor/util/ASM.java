package net.ornithemc.condor.util;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;

public class ASM implements Opcodes {

	public static final int API_VERSION = Opcodes.ASM9;

	public static final Type NULL_TYPE = Type.getObjectType("null");
	public static final Type OBJECT_TYPE = Type.getObjectType("java/lang/Object");
	public static final Type STRING_TYPE = Type.getObjectType("java/lang/String");
	public static final Type THROWABLE_TYPE = Type.getObjectType("java/lang/Throwable");
	public static final Type CLASS_TYPE = Type.getObjectType("java/lang/Class");
	public static final Type METHOD_TYPE = Type.getObjectType("java/lang/invoke/MethodType");

	public static Type getArrayType(Type elementType, int dimensions) {
		StringBuilder sb = new StringBuilder();

		for (int i = 0; i < dimensions; i++) {
			sb.append('[');
		}
		sb.append(elementType.getDescriptor());

		return Type.getType(sb.toString());
	}

	public static Type getIntType(Type type1, Type type2) {
		if (type1 == type2) {
			return type1;
		}
		if (type1 == Type.BOOLEAN_TYPE) {
			return type2;
		}
		if (type2 == Type.BOOLEAN_TYPE) {
			return type1;
		}

		return Type.INT_TYPE;
	}

	public static boolean isPseudoInsn(AbstractInsnNode insn) {
		return isPseudoInsn(insn.getType());
	}

	public static boolean isPseudoInsn(int insnType) {
		return insnType == AbstractInsnNode.LABEL || insnType == AbstractInsnNode.LINE || insnType == AbstractInsnNode.FRAME;
	}

	public static int getStackDemand(AbstractInsnNode insn) {
		switch (insn.getOpcode()) {
		case NOP:
			return 0;
		case ACONST_NULL:
		case ICONST_M1:
		case ICONST_0:
		case ICONST_1:
		case ICONST_2:
		case ICONST_3:
		case ICONST_4:
		case ICONST_5:
		case LCONST_0:
		case LCONST_1:
		case FCONST_0:
		case FCONST_1:
		case FCONST_2:
		case DCONST_0:
		case DCONST_1:
			return 0;
		case BIPUSH:
		case SIPUSH:
			return 0;
		case LDC:
			return 0;
		case ILOAD:
		case LLOAD:
		case FLOAD:
		case DLOAD:
		case ALOAD:
			return 0;
		case ISTORE:
		case FSTORE:
		case ASTORE:
			return 1;
		case LSTORE:
		case DSTORE:
			return 2;
		case SALOAD:
		case CALOAD:
		case BALOAD:
		case IALOAD:
		case LALOAD:
		case FALOAD:
		case DALOAD:
		case AALOAD:
			return 2;
		case IASTORE:
		case FASTORE:
		case AASTORE:
		case BASTORE:
		case CASTORE:
		case SASTORE:
			return 3;
		case LASTORE:
		case DASTORE:
			return 4;
		case POP:
			return 1;
		case POP2:
			return 2;
		case DUP:
			return 1;
		case DUP_X1:
			return 2;
		case DUP_X2:
			return 3;
		case DUP2:
			return 2;
		case DUP2_X1:
			return 3;
		case DUP2_X2:
			return 4;
		case SWAP:
			return 2;
		case IADD:
		case FADD:
		case ISUB:
		case FSUB:
		case IMUL:
		case FMUL:
		case IDIV:
		case FDIV:
		case IREM:
		case FREM:
			return 2;
		case LADD:
		case DADD:
		case LSUB:
		case DSUB:
		case LMUL:
		case DMUL:
		case LDIV:
		case DDIV:
		case LREM:
		case DREM:
			return 4;
		case ISHL:
		case ISHR:
		case IUSHR:
		case IAND:
		case IOR:
		case IXOR:
			return 2;
		case LSHL:
		case LSHR:
		case LUSHR:
			return 3;
		case LAND:
		case LOR:
		case LXOR:
			return 4;
		case FCMPL:
		case FCMPG:
			return 2;
		case LCMP:
		case DCMPL:
		case DCMPG:
			return 4;
		case INEG:
		case FNEG:
			return 1;
		case LNEG:
		case DNEG:
			return 2;
		case IINC:
			return 0;
		case I2B:
		case I2S:
		case I2C:
		case I2F:
		case I2L:
		case F2L:
		case I2D:
		case F2D:
		case F2I:
			return 1;
		case L2I:
		case D2I:
		case L2F:
		case D2F:
		case L2D:
		case D2L:
			return 2;
		case IFEQ:
		case IFNE:
		case IFLT:
		case IFGE:
		case IFGT:
		case IFLE:
		case IFNULL:
		case IFNONNULL:
			return 1;
		case IF_ICMPEQ:
		case IF_ICMPNE:
		case IF_ICMPLT:
		case IF_ICMPGE:
		case IF_ICMPGT:
		case IF_ICMPLE:
		case IF_ACMPEQ:
		case IF_ACMPNE:
			return 2;
		case GOTO:
			return 0;
		case GETFIELD:
			return 1;
		case GETSTATIC:
			return 0;
		case PUTFIELD:
		case PUTSTATIC:
			{
				int opcode = insn.getOpcode();

				String desc = ((FieldInsnNode) insn).desc;
				Type type = Type.getType(desc);

				int demand = type.getSize();

				if (opcode == PUTFIELD) {
					demand++; // obj ref
				}

				return demand;
			}
		case INVOKEVIRTUAL:
		case INVOKESPECIAL:
		case INVOKESTATIC:
		case INVOKEINTERFACE:
		case INVOKEDYNAMIC:
			{
				int opcode = insn.getOpcode();

				String desc = (opcode == INVOKEDYNAMIC)
					? ((InvokeDynamicInsnNode) insn).desc
					: ((MethodInsnNode) insn).desc;
				Type type = Type.getMethodType(desc);
				Type[] args = type.getArgumentTypes();

				int demand = 0;

				if (opcode != INVOKESTATIC && opcode != INVOKEDYNAMIC) {
					demand++; // obj ref
				}

				for (int i = args.length - 1; i >= 0; i--) {
					demand += args[i].getSize();
				}

				return demand;
			}
		case JSR:
		case RET:
			throw new UnsupportedOperationException(); // TODO
		case TABLESWITCH:
		case LOOKUPSWITCH:
			return 1;
		case IRETURN:
		case FRETURN:
		case ARETURN:
			return 1;
		case LRETURN:
		case DRETURN:
			return 2;
		case RETURN:
			return 0;
		case NEW:
			return 0;
		case NEWARRAY:
		case ANEWARRAY:
			return 1;
		case MULTIANEWARRAY:
			return ((MultiANewArrayInsnNode) insn).dims;
		case ARRAYLENGTH:
			return 1;
		case ATHROW:
			return 1;
		case CHECKCAST:
		case INSTANCEOF:
			return 1;
		case MONITORENTER:
		case MONITOREXIT:
			return 1;
		case -1:
			return 0;
		default:
			throw new UnsupportedOperationException("Illegal opcode " + insn.getOpcode());
		}
	}
}
