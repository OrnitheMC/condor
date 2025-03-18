package net.ornithemc.condor.representation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.objectweb.asm.Type;

import net.ornithemc.condor.util.ASM;

public class Classpath {

	private final ClassSource jre;
	private final ClassSource jar;
	private final ClassSource[] libs;

	private ClassInstance object;

	public Classpath(Path jar, List<Path> libs) throws IOException {
		this.jre = new JavaRuntimeEnvironment();
		this.jar = new JarInstance(jar, true);
		this.libs = new ClassSource[libs.size()];
		for (int i = 0; i < libs.size(); i++) {
			this.libs[i] = new JarInstance(libs.get(i), false);
		}
	}

	public void open() throws IOException {
		this.jre.open();
		this.jar.open();
		for (ClassSource lib : this.libs) {
			lib.open();
		}
	}

	public void close() throws IOException {
		this.jre.close();
		this.jar.close();
		for (ClassSource lib : this.libs) {
			lib.close();
		}
	}

	public ClassSource getMainJar() {
		return this.jar;
	}

	public ClassInstance getClass(Type type) {
		return this.getClass(type.getInternalName());
	}

	public ClassInstance getClass(String name) {
		try {
			ClassInstance cls = jar.getClass(name);

			if (cls != null) {
				return cls;
			}

			for (ClassSource lib : this.libs) {
				cls = lib.getClass(name);

				if (cls != null) {
					return cls;
				}
			}

			return this.jre.getClass(name);
		} catch (IOException e) {
			throw new RuntimeException("could not find class " + name, e);
		}
	}

	public ClassInstance getObject() {
		if (this.object == null) {
			this.object = this.getClass("java/lang/Object");
		}

		return this.object;
	}

	public Type getCommonSuperClass(Type type1, Type type2) {
		return this.getCommonSuperClass(this.getClass(type1), this.getClass(type2)).getType();
	}

	public ClassInstance getCommonSuperClass(ClassInstance cls1, ClassInstance cls2) {
		if (cls1.hasSuperClass(this, cls2)) {
			return cls2;
		} else if (cls2.hasSuperClass(this, cls1)) {
			return cls1;
		} else if (cls1.isInterface() || cls2.isInterface()) {
			return this.getObject();
		}

		do {
			cls1 = cls1.getSuperClass(this);
			if (cls1 == null) {
				return this.getObject();
			}
		} while (!cls2.hasSuperClass(this, cls1));

		return cls1;
	}

	public Type getCommonSuperType(Type type1, Type type2) {
		if (type1 == type2 || type1.equals(type2)) {
			return type1;
		}
		if (type1 == ASM.NULL_TYPE) {
			return type2;
		}
		if (type2 == ASM.NULL_TYPE) {
			return type1;
		}
		if (type1.getSort() < Type.ARRAY || type2.getSort() < Type.ARRAY) {
			if (type1.getSort() >= Type.BOOLEAN && type1.getSort() <= Type.INT && type2.getSort() >= Type.BOOLEAN && type2.getSort() <= Type.INT) {
				return ASM.getIntType(type1, type2);
			}
			// incompatible primitive types
			return null;
		}
		if (type1.getSort() == Type.ARRAY && type2.getSort() == Type.ARRAY) {
			int dims1 = type1.getDimensions();
			Type elem1 = type1.getElementType();
			int dims2 = type2.getDimensions();
			Type elem2 = type2.getElementType();

			if (dims1 == dims2) {
				Type commonSuperType;

				if (elem1.equals(elem2)) {
					commonSuperType = elem1;
				} else if (elem1.getSort() == Type.OBJECT && elem2.getSort() == Type.OBJECT) {
					commonSuperType = this.getCommonSuperClass(elem1, elem2);
				} else {
					return ASM.getArrayType(ASM.OBJECT_TYPE, dims1 - 1);
				}

				return ASM.getArrayType(commonSuperType, dims1);
			} else {
				int shared;
				Type smaller;

				if (dims1 < dims2) {
					smaller = elem1;
					shared = dims1 - 1;
				} else {
					smaller = elem2;
					shared = dims2 - 1;
				}
				if (smaller.getSort() == Type.OBJECT) {
					shared++;
				}

				return ASM.getArrayType(ASM.OBJECT_TYPE, shared);
			}
		}
		if (type1.getSort() == Type.OBJECT && type2.getSort() == Type.OBJECT) {
			return this.getCommonSuperClass(type1, type2);
		}
		if ((type1.getSort() == Type.ARRAY && type2.getSort() == Type.OBJECT) || (type1.getSort() == Type.OBJECT && type2.getSort() == Type.ARRAY)) {
			return ASM.OBJECT_TYPE;
		}

		throw new IllegalArgumentException("given illegal type(s) " + type1 + " and " + type2);
	}
}
