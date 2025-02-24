package net.ornithemc.condor;

import java.util.List;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import net.ornithemc.condor.representation.Classpath;

public class Verifier extends SimpleVerifier {
	private static final Type OBJECT_TYPE = Type.getType(Object.class);

	private final Classpath classpath;

	public Verifier(int api, Type currentClass, Type currentSuperClass, List<Type> currentClassInterfaces, boolean isInterface, Classpath classpath) {
		super(api, currentClass, currentSuperClass, currentClassInterfaces, isInterface);

		this.classpath = classpath;
	}

	@Override
	protected boolean isInterface(Type type) {
		if (type.getSort() != Type.OBJECT) {
			return false;
		}
		return this.classpath.getClass(type).isInterface();
	}

	@Override
	protected boolean isSubTypeOf(BasicValue value, BasicValue expected) {
		Type expectedType = expected.getType();
		Type type = value.getType();
		switch (expectedType.getSort()) {
			case Type.INT:
			case Type.FLOAT:
			case Type.LONG:
			case Type.DOUBLE:
				return type.equals(expectedType);
			case Type.ARRAY:
			case Type.OBJECT:
				if (type.equals(NULL_TYPE)) {
					return true;
				} else if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
					if (isAssignableFrom(expectedType, type)) {
						return true;
					}
					if (expectedType.getSort() == Type.ARRAY) {
						if (type.getSort() != Type.ARRAY) {
							return false;
						}
						int dim = expectedType.getDimensions();
						expectedType = expectedType.getElementType();
						if (dim > type.getDimensions() || expectedType.getSort() != Type.OBJECT) {
							return false;
						}
						type = Type.getType(type.getDescriptor().substring(dim));
					}
					if (isInterface(expectedType)) {
						// The merge of class or interface types can only yield class types (because it is not
						// possible in general to find an unambiguous common super interface, due to multiple
						// inheritance). Because of this limitation, we need to relax the subtyping check here
						// if 'value' is an interface.
						return type.getSort() >= Type.ARRAY;
					} else {
						return false;
					}
				} else {
					return false;
				}
			default:
				throw new AssertionError();
		}
	}

	@Override
	protected boolean isAssignableFrom(Type type1, Type type2) {
		return type1.equals(getCommonSupertype(type1, type2));
	}

	@Override
	public BasicValue merge(BasicValue value1, BasicValue value2) {
		if (value1.equals(value2)) {
			return value1;
		}
		if (value1.equals(BasicValue.UNINITIALIZED_VALUE) || value2.equals(BasicValue.UNINITIALIZED_VALUE)) {
			return BasicValue.UNINITIALIZED_VALUE;
		}
		Type supertype = getCommonSupertype(value1.getType(), value2.getType());
		return newValue(supertype);
	}

	private Type getCommonSupertype(Type type1, Type type2) {
		if (type1.equals(type2) || type2.equals(NULL_TYPE)) {
			return type1;
		}
		if (type1.equals(NULL_TYPE)) {
			return type2;
		}
		if (type1.getSort() < Type.ARRAY || type2.getSort() < Type.ARRAY) {
			// We know they're not the same, so they must be incompatible.
			return null;
		}
		if (type1.getSort() == Type.ARRAY && type2.getSort() == Type.ARRAY) {
			int dim1 = type1.getDimensions();
			Type elem1 = type1.getElementType();
			int dim2 = type2.getDimensions();
			Type elem2 = type2.getElementType();
			if (dim1 == dim2) {
				Type commonSupertype;
				if (elem1.equals(elem2)) {
					commonSupertype = elem1;
				} else if (elem1.getSort() == Type.OBJECT && elem2.getSort() == Type.OBJECT) {
					commonSupertype = getCommonSupertype(elem1, elem2);
				} else {
					return arrayType(OBJECT_TYPE, dim1 - 1);
				}
				return arrayType(commonSupertype, dim1);
			}
			Type smaller;
			int shared;
			if (dim1 < dim2) {
				smaller = elem1;
				shared = dim1 - 1;
			} else {
				smaller = elem2;
				shared = dim2 - 1;
			}
			if (smaller.getSort() == Type.OBJECT) {
				shared++;
			}
			return arrayType(OBJECT_TYPE, shared);
		}
		if (type1.getSort() == Type.ARRAY && type2.getSort() == Type.OBJECT || type2.getSort() == Type.ARRAY && type1.getSort() == Type.OBJECT) {
			return OBJECT_TYPE;
		}
		return this.classpath.getCommonSuperClass(this.classpath.getClass(type1), this.classpath.getClass(type2)).getType();
	}

	private static Type arrayType(final Type type, final int dimensions) {
		if (dimensions == 0) {
			return type;
		} else {
			StringBuilder descriptor = new StringBuilder();
			for (int i = 0; i < dimensions; ++i) {
				descriptor.append('[');
			}
			descriptor.append(type.getDescriptor());
			return Type.getType(descriptor.toString());
		}
	}

	@Override
	protected Class<?> getClass(Type type) {
		throw new UnsupportedOperationException(
				String.format(
						"Live-loading of %s attempted by MixinVerifier! This should never happen!",
						type.getClassName()
				)
		);
	}
}
