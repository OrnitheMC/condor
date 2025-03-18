package net.ornithemc.condor.lvt;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.ornithemc.condor.representation.Classpath;
import net.ornithemc.condor.util.ASM;

public class StackFrame implements Opcodes {

	private Type[] locals;
	private Type[] stack;
	private int[] stackLocals;

	private int localsSize;
	private int stackSize;

	// whether this frame was expanded from a frame insn
	private boolean expanded;

	public StackFrame(int maxLocals, int maxStack) {
		this.locals = new Type[maxLocals];
		this.stack = new Type[maxStack];
		this.stackLocals = new int[maxStack];

		Arrays.fill(this.stackLocals, -1);
	}

	public StackFrame(StackFrame other) {
		this.init(other);
	}

	public StackFrame init(StackFrame other) {
		this.locals = Arrays.copyOf(other.locals, other.locals.length);
		this.stack = Arrays.copyOf(other.stack, other.stack.length);
		this.stackLocals = Arrays.copyOf(other.stackLocals, other.stackLocals.length);

		this.localsSize = other.localsSize;
		this.stackSize = other.stackSize;

		this.expanded = other.expanded;

		return this;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (Type local : this.locals) {
			if (local == null) {
				sb.append('T');
			} else {
				sb.append(local);
			}
		}
		sb.append(' ');
		for (int i = 0; i < this.stackSize; i++) {
			if (this.stackLocals[i] >= 0) {
				sb.append('[');
				sb.append(this.stackLocals[i]);
				sb.append(']');
			}
			sb.append(this.stack[i]);
		}
		return sb.toString();
	}

	public void setLocal(int varIndex, Type type) {
		this.locals[varIndex] = type;
		if (type.getSize() == 2) {
			this.locals[varIndex + 1] = Type.VOID_TYPE;
		}
	}

	public Type getLocal(int varIndex) {
		return this.locals[varIndex];
	}

	public Type removeLocal(int varIndex) {
		Type type = this.locals[varIndex];
		if (type != null) {
			this.locals[varIndex] = null;
			if (type.getSize() == 2) {
				this.locals[varIndex + 1] = null;
			}
		}
		return type;
	}

	public void push(Type type) {
		this.push(type, -1);
	}

	public void push(Type type, int varIndex) {
		this.stack[this.stackSize] = type;
		this.stackLocals[this.stackSize++] = varIndex;
		if (type.getSize() == 2) {
			this.stack[this.stackSize] = Type.VOID_TYPE;
			this.stackLocals[stackSize++] = varIndex + 1;
		}
	}

	public Type peek() {
		return this.stack[this.stackSize - 1];
	}

	public Type peek2() {
		return this.stack[this.stackSize - 2];
	}

	public Type peek(int offset) {
		return this.stack[this.stackSize - offset];
	}

	public int peekLocal() {
		return this.stackLocals[this.stackSize - 1];
	}

	public int peekLocal2() {
		return this.stackLocals[this.stackSize - 2];
	}

	public int peekLocal(int offset) {
		return this.stackLocals[this.stackSize - offset];
	}

	public int getLocalsSize() {
		return this.localsSize;
	}

	public Type pop() {
		return this.stack[--this.stackSize];
	}

	public Type pop2() {
		if (this.pop() != Type.VOID_TYPE) {
			throw new IllegalStateException("no top value!");
		}

		return this.pop();
	}

	public void clear() {
		while (this.stackSize > 0) {
			this.pop();
		}
	}

	public int getStackSize() {
		return this.stackSize;
	}

	public void markLocals(BitSet present) {
		for (int i = 0; i < this.locals.length; i++) {
			if (this.locals[i] != null) {
				present.set(i);
			}
		}
	}

	public void compute(String owner, Type[] params) {
		this.localsSize = 0;

		if (owner != null) {
			this.setLocal(this.localsSize++, Type.getObjectType(owner));
		}

		for (Type param : params) {
			this.locals[this.localsSize++] = param;

			if (param.getSize() == 2) {
				this.locals[this.localsSize++] = Type.VOID_TYPE;
			}
		}

		this.expanded = false;
	}

	public void expand(String owner, Type[] params, FrameNode frame) {
		switch (frame.type) {
		case F_NEW:
		case F_FULL:
			{
				this.localsSize = this.unpackFrameValues(owner, frame.local, this.locals, 0);
				this.stackSize = this.unpackFrameValues(owner, frame.stack, this.stack, 0);

				// type data in frame insns removes information about int related types
				// 
			}

			break;
		case F_APPEND:
			{
				this.localsSize = this.unpackFrameValues(owner, frame.local, this.locals, this.localsSize);
				this.stackSize = this.unpackFrameValues(owner, Collections.emptyList(), this.stack, 0);
			}

			break;
		case F_CHOP:
			{
				for (int i = frame.local.size() - 1; i >= 0; i--) {
					int varIndex = this.localsSize - 1;
					Type type = this.locals[varIndex];

					this.locals[--this.localsSize] = null;

					if (type == Type.VOID_TYPE) {
						this.locals[--this.localsSize] = null;
					}
				}

				this.stackSize = this.unpackFrameValues(owner, Collections.emptyList(), this.stack, 0);
			}

			break;
		case F_SAME:
			{
				this.stackSize = this.unpackFrameValues(owner, Collections.emptyList(), this.stack, 0);
			}

			break;
		case F_SAME1:
			{
				this.stackSize = this.unpackFrameValues(owner, frame.stack, this.stack, 0);
			}

			break;
		default:
			throw new UnsupportedOperationException("Illegal frame type " + frame.type);
		}

		this.expanded = true;
	}

	private int unpackFrameValues(String owner, List<Object> values, Type[] types, int start) {
		int size = start;

		for (int i = 0; i < values.size(); i++) {
			Object value = values.get(i);
			Type type = this.parseFrameValue(owner, value);

			types[size++] = type;

			if (type != null && type.getSize() == 2) {
				types[size++] = Type.VOID_TYPE;
			}
		}
		for (int i = size; i < types.length; i++) {
			types[i] = null;
		}

		return size;
	}

	private Type parseFrameValue(String owner, Object type) {
		if (type == TOP) {
			return null;
		} else if (type == INTEGER) {
			return Type.INT_TYPE;
		} else if (type == FLOAT) {
			return Type.FLOAT_TYPE;
		} else if (type == DOUBLE) {
			return Type.DOUBLE_TYPE;
		} else if (type == LONG) {
			return Type.LONG_TYPE;
		} else if (type == NULL) {
			return ASM.NULL_TYPE;
		} else if (type == UNINITIALIZED_THIS) {
			return Type.getObjectType(owner);
		} else if (type instanceof String) {
			return Type.getObjectType((String) type);
		} else if (type instanceof LabelNode) {
			AbstractInsnNode insn = (LabelNode) type;

			while (insn != null && insn.getOpcode() < 0) {
				insn = insn.getNext();
			}
			if (insn == null || insn.getOpcode() != Opcodes.NEW) {
				throw new UnsupportedOperationException("LabelNode does not designate a NEW instruction");
			}

			return Type.getObjectType(((TypeInsnNode) insn).desc);
		}

		throw new UnsupportedOperationException("Illegal frame value " + type);
	}

	@SuppressWarnings("unused")
	public void compute(AbstractInsnNode insn) {
		switch (insn.getOpcode()) {
		case NOP:
			break;
		case ACONST_NULL:
			{
				this.push(ASM.NULL_TYPE);
			}

			break;
		case ICONST_M1:
		case ICONST_0:
		case ICONST_1:
		case ICONST_2:
		case ICONST_3:
		case ICONST_4:
		case ICONST_5:
			{
				this.push(Type.INT_TYPE);
			}

			break;
		case LCONST_0:
		case LCONST_1:
			{
				this.push(Type.LONG_TYPE);
			}

			break;
		case FCONST_0:
		case FCONST_1:
		case FCONST_2:
			{
				this.push(Type.FLOAT_TYPE);
			}

			break;
		case DCONST_0:
		case DCONST_1:
			{
				this.push(Type.DOUBLE_TYPE);
			}

			break;
		case BIPUSH:
		case SIPUSH:
			// while pushing BYTE and SHORT respectively may yield better
			// results in some cases, int variables are much more common
			// pushing the more specific types then leads to issues in the
			// local variable tweaker, enforcing those types where it is
			// not appropriate
			{
				this.push(Type.INT_TYPE);
			}

			break;
		case LDC:
			{
				Object value = ((LdcInsnNode) insn).cst;

				if (value instanceof Integer) {
					this.push(Type.INT_TYPE);
				} else if (value instanceof Float) {
					this.push(Type.FLOAT_TYPE);
				} else if (value instanceof Long) {
					this.push(Type.LONG_TYPE);
				} else if (value instanceof Double) {
					this.push(Type.DOUBLE_TYPE);
				} else if (value instanceof String) {
					this.push(ASM.STRING_TYPE);
				} else if (value instanceof Type) {
					int valueSort = ((Type) value).getSort();

					if (valueSort == Type.OBJECT || valueSort == Type.ARRAY) {
						this.push(ASM.CLASS_TYPE);
					} else if (valueSort == Type.METHOD) {
						this.push(ASM.METHOD_TYPE);
					} else {
						throw new IllegalStateException("unsupported LDC type sort " + valueSort);
					}
				} else {
					throw new IllegalStateException("unsupported LDC value " + value.getClass());
				}
			}

			break;
		case ILOAD:
		case LLOAD:
		case FLOAD:
		case DLOAD:
		case ALOAD:
			{
				int varIndex = ((VarInsnNode) insn).var;
				Type local = this.getLocal(varIndex);

				this.push(local, varIndex);
			}

			break;
		case ISTORE:
		case LSTORE:
		case FSTORE:
		case DSTORE:
		case ASTORE:
			{
				int opcode = insn.getOpcode();
				int varIndex = ((VarInsnNode) insn).var;

				Type value = (opcode == LSTORE || opcode == DSTORE)
					? this.pop2()
					: this.pop();

				this.setLocal(varIndex, value);
			}

			break;
		case SALOAD:
		case CALOAD:
		case BALOAD:
		case IALOAD:
		case LALOAD:
		case FALOAD:
		case DALOAD:
		case AALOAD:
			{
				int opcode = insn.getOpcode();

				this.pop(); // index
				Type arrayType = this.pop();

				Type value = (opcode == AALOAD)
					? Type.getType(arrayType.getDescriptor().substring(1))
					: arrayType.getElementType();

				this.push(value);
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
				int opcode = insn.getOpcode();

				if (opcode == LASTORE || opcode == DASTORE) {
					this.pop2(); // value
				} else {
					this.pop(); // value
				}
				this.pop(); // index
				this.pop(); // type
			}

			break;
		case POP:
			{
				this.pop();
			}

			break;
		case POP2:
			{
				this.pop2();
			}

			break;
		case DUP:
			{
				this.push(this.peek());
			}

			break;
		case DUP_X1:
			{
				Type value1 = this.pop();
				Type value2 = this.pop();

				this.push(value1);
				this.push(value2);
				this.push(value1);
			}

			break;
		case DUP_X2:
			{
				Type value1 = this.pop();
				Type value2 = this.pop();
				Type value3 = this.pop();

				this.push(value1);
				this.push(value3);
				this.push(value2);
				this.push(value1);
			}

			break;
		case DUP2:
			{
				if (this.peek() == Type.VOID_TYPE) {
					this.push(this.peek2());
				} else {
					Type value2 = this.pop();
					Type value1 = this.peek();

					this.push(value2);
					this.push(value1);
					this.push(value2);
				}
			}

			break;
		case DUP2_X1:
			{
				if (this.peek() == Type.VOID_TYPE) {
					Type value2 = this.pop2();
					Type value1 = this.pop();

					this.push(value2);
					this.push(value1);
					this.push(value2);
				} else {
					Type value3 = this.pop();
					Type value2 = this.pop();
					Type value1 = this.pop();

					this.push(value2);
					this.push(value3);
					this.push(value1);
					this.push(value2);
					this.push(value3);
				}
			}

			break;
		case DUP2_X2:
			{
				if (this.peek() == Type.VOID_TYPE) {
					Type value3 = this.pop2();

					if (this.peek() == Type.VOID_TYPE) {
						Type value1 = this.pop2();

						this.push(value3);
						this.push(value1);
						this.push(value3);
					} else {
						Type value2 = this.pop();
						Type value1 = this.pop();

						this.push(value3);
						this.push(value1);
						this.push(value2);
						this.push(value3);
					}
				} else {
					Type value4 = this.pop();
					Type value3 = this.pop();

					if (this.peek() == Type.VOID_TYPE) {
						Type value1 = this.pop2();

						this.push(value3);
						this.push(value4);
						this.push(value1);
						this.push(value3);
						this.push(value4);
					} else {
						Type value2 = this.pop();
						Type value1 = this.pop();

						this.push(value3);
						this.push(value4);
						this.push(value1);
						this.push(value2);
						this.push(value3);
						this.push(value4);
					}
				}
			}

			break;
		case SWAP:
			{
				Type value2 = this.pop();
				Type value1 = this.pop();

				this.push(value2);
				this.push(value1);
			}

			break;
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
			{
				Type value2 = this.pop();
				Type value1 = this.pop();

				this.push(ASM.getIntType(value1, value2));
			}

			break;
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
			{
				Type value2 = this.pop2();
				Type value1 = this.pop2();

				this.push(value1);
			}

			break;
		case ISHL:
		case ISHR:
		case IUSHR:
			{
				int opcode = insn.getOpcode();

				Type value2 = this.pop(); // shift amount
				Type value1 = this.pop();

				this.push(value1);
			}

			break;
		case IAND:
		case IOR:
		case IXOR:
			{
				int opcode = insn.getOpcode();

				Type value2 = this.pop();
				Type value1 = this.pop();

				this.push(ASM.getIntType(value1, value2));
			}

			break;
		case LSHL:
		case LSHR:
		case LUSHR:
		case LAND:
		case LOR:
		case LXOR:
			{
				int opcode = insn.getOpcode();

				Type value2 = (opcode == LAND || opcode == LOR || opcode == LXOR)
					? this.pop2()
					: this.pop(); // amount for shift ops
				Type value1 = this.pop2();

				this.push(value1);
			}

			break;
		case FCMPL:
		case FCMPG:
			{
				this.pop(); // value2
				this.pop(); // value1

				this.push(Type.INT_TYPE);
			}

			break;
		case LCMP:
		case DCMPL:
		case DCMPG:
			{
				this.pop2(); // value2
				this.pop2(); // value1

				this.push(Type.INT_TYPE);
			}

			break;
		case INEG:
		case FNEG:
			{
				this.push(this.pop());
			}

			break;
		case LNEG:
		case DNEG:
			{
				this.push(this.pop2());
			}

			break;
		case IINC:
			{
				int varIndex = ((IincInsnNode) insn).var;
				this.setLocal(varIndex, this.getLocal(varIndex));
			}

			break;
		case I2B:
			{
				this.pop();
				this.push(Type.BYTE_TYPE);
			}

			break;
		case I2S:
			{
				this.pop();
				this.push(Type.SHORT_TYPE);
			}

			break;
		case I2C:
			{
				this.pop();
				this.push(Type.CHAR_TYPE);
			}

			break;
		case I2F:
			{
				this.pop();
				this.push(Type.FLOAT_TYPE);
			}

			break;
		case I2L:
		case F2L:
			{
				this.pop();
				this.push(Type.LONG_TYPE);
			}

			break;
		case I2D:
		case F2D:
			{
				this.pop();
				this.push(Type.DOUBLE_TYPE);
			}

			break;
		case F2I:
			{
				this.pop();
				this.push(Type.INT_TYPE);
			}

			break;
		case L2I:
		case D2I:
			{
				this.pop2();
				this.push(Type.INT_TYPE);
			}

			break;
		case L2F:
		case D2F:
			{
				this.pop2();
				this.push(Type.FLOAT_TYPE);
			}

			break;
		case L2D:
			{
				this.pop2();
				this.push(Type.DOUBLE_TYPE);
			}

			break;
		case D2L:
			{
				this.pop2();
				this.push(Type.LONG_TYPE);
			}

			break;
		case IFEQ:
		case IFNE:
		case IFLT:
		case IFGE:
		case IFGT:
		case IFLE:
		case IFNULL:
		case IFNONNULL:
			{
				this.pop();
			}

			break;
		case IF_ICMPEQ:
		case IF_ICMPNE:
		case IF_ICMPLT:
		case IF_ICMPGE:
		case IF_ICMPGT:
		case IF_ICMPLE:
		case IF_ACMPEQ:
		case IF_ACMPNE:
			{
				this.pop(); // value2
				this.pop(); // value1
			}

			break;
		case GOTO:
			break;
		case GETFIELD:
		case GETSTATIC:
		case PUTFIELD:
		case PUTSTATIC:
			{
				int opcode = insn.getOpcode();

				String desc = ((FieldInsnNode) insn).desc;
				Type type = Type.getType(desc);

				if (opcode == PUTFIELD || opcode == PUTSTATIC) {
					if (type.getSize() == 2) {
						this.pop2(); // value
					} else {
						this.pop(); // value
					}
				}
				if (opcode == GETFIELD || opcode == PUTFIELD) {
					this.pop(); // obj ref
				}
				if (opcode == GETFIELD || opcode == GETSTATIC) {
					this.push(type);
				}
			}

			break;
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
				Type ret = type.getReturnType();

				for (int j = args.length - 1; j >= 0; j--) {
					if (args[j].getSize() == 2) {
						this.pop2(); // arg
					} else {
						this.pop(); // arg
					}
				}

				if (opcode != INVOKESTATIC && opcode != INVOKEDYNAMIC) {
					this.pop(); // obj ref
				}

				if (ret != Type.VOID_TYPE) {
					this.push(ret);
				}
			}

			break;
		case JSR:
		case RET:
			throw new UnsupportedOperationException(); // TODO
		case TABLESWITCH:
			{
				this.pop(); // index
			}

			break;
		case LOOKUPSWITCH:
			{
				this.pop(); // key
			}

			break;
		case IRETURN:
		case LRETURN:
		case FRETURN:
		case DRETURN:
		case ARETURN:
			{
				if (this.peek() == Type.VOID_TYPE) {
					this.pop2(); // return value
				} else {
					this.pop(); // return value;
				}

				this.clear();
			}

			break;
		case RETURN:
			{
				this.clear();
			}

			break;
		case NEW:
			{
				String desc = ((TypeInsnNode) insn).desc;
				Type type = Type.getObjectType(desc);

				this.push(type);
			}

			break;
		case NEWARRAY:
			{
				int operand = ((IntInsnNode) insn).operand;
				String desc;

				switch (operand) {
				case T_BOOLEAN:
					desc = "[Z";
					break;
				case T_BYTE:
					desc = "[B";
					break;
				case T_SHORT:
					desc = "[S";
					break;
				case T_CHAR:
					desc = "[C";
					break;
				case T_INT:
					desc = "[I";
					break;
				case T_FLOAT:
					desc = "[F";
					break;
				case T_LONG:
					desc = "[J";
					break;
				case T_DOUBLE:
					desc = "[D";
					break;
				default:
					throw new UnsupportedOperationException("unknown NEWARRAY operand: " + operand);
				}

				Type type = Type.getType(desc);

				this.pop(); // size
				this.push(type);
			}

			break;
		case ANEWARRAY:
			{
				String desc = ((TypeInsnNode) insn).desc;
				Type elementType = Type.getObjectType(desc);
				Type type = Type.getType("[" + elementType.getDescriptor());

				this.pop(); // size
				this.push(type);
			}

			break;
		case MULTIANEWARRAY:
			{
				String desc = ((MultiANewArrayInsnNode) insn).desc;
				Type type = Type.getType(desc);
				int dims = ((MultiANewArrayInsnNode) insn).dims;

				for (int j = 0; j < dims; j++) {
					this.pop(); // size
				}

				this.push(type);
			}

			break;
		case ARRAYLENGTH:
			{
				this.pop(); // obj ref
				this.push(Type.INT_TYPE);
			}

			break;
		case ATHROW:
			{
				Type type = this.pop();

				this.clear();
				this.push(type);
			}

			break;
		case CHECKCAST:
			{
				String desc = ((TypeInsnNode) insn).desc;
				Type check = Type.getObjectType(desc);

				this.pop(); // type
				this.push(check);
			}

			break;
		case INSTANCEOF:
			{
				this.pop(); // type
				this.push(Type.BOOLEAN_TYPE); // result
			}

			break;
		case MONITORENTER:
		case MONITOREXIT:
			{
				this.pop();
			}

			break;
		case -1:
			break;
		default:
			throw new UnsupportedOperationException("Illegal opcode " + insn.getOpcode());
		}

		this.expanded = false;
	}

	public boolean merge(StackFrame other, Classpath classpath) {
		if (this.locals.length != other.locals.length) {
			throw new UnsupportedOperationException("incompatible maxLocals");
		}
		if (this.stack.length != other.stack.length) {
			throw new UnsupportedOperationException("incompatible maxStack");
		}
		if (this.stackSize != other.stackSize) {
			throw new UnsupportedOperationException("incompatible stackSize");
		}

		boolean changed = false;

		for (int i = 0; i < this.locals.length; i++) {
			Type type = this.mergeTypes(this.locals[i], other.locals[i], classpath);

			if (!Objects.equals(this.locals[i], type)) {
				this.locals[i] = type;

				changed = true;
			}
		}
		for (int i = 0; i < this.stackSize; i++) {
			Type type = this.mergeTypes(this.stack[i], other.stack[i], classpath);
			int varIndex = this.mergeVarIndices(this.stackLocals[i], other.stackLocals[i], type);

			if (!Objects.equals(this.stack[i], type)) {
				this.stack[i] = type;

				changed = true;
			}
			if (this.stackLocals[i] != varIndex) {
				this.stackLocals[i] = varIndex;

				changed = true;
			}
		}

		// once a computed frame is merged in, special cases for merging
		// into expanded frames are no longer necessary
		this.expanded = false;

		return changed;
	}

	private Type mergeTypes(Type type1, Type type2, Classpath classpath) {
		if (type1 == null || type2 == null) {
			return null;
		}
		if (type1 == Type.VOID_TYPE || type2 == Type.VOID_TYPE) {
			return Type.VOID_TYPE;
		}

		if (type1 == type2 || type1.equals(type2)) {
			return type1;
		}

		// special cases for expanded frames
		if (this.expanded) {
			// frame insns reduces all int related types to int
			if (type1 == Type.INT_TYPE && type2.getSort() < Type.INT) {
				return type2;
			}
		}

		return classpath.getCommonSuperType(type1, type2);
	}

	private int mergeVarIndices(int varIndex1, int varIndex2, Type type) {
		if (type == null || type == Type.VOID_TYPE) {
			return -1;
		}

		return varIndex1 < 0 ? varIndex2 : varIndex1;
	}
}
