package net.ornithemc.condor.representation;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

public class ClassInstance {

	private final ClassNode node;

	private final int access;
	private final String className;
	private final String superClassName;
	private final String[] interfaceNames;

	private ClassInstance superClass;
	private ClassInstance[] interfaces;

	private boolean dirty;

	ClassInstance(ClassNode node, int access, String className, String superClassName, String[] interfaceNames) {
		this.node = node;

		this.access = access;
		this.className = className;
		this.superClassName = superClassName;
		this.interfaceNames = interfaceNames == null ? new String[0] : interfaceNames;
	}

	public ClassNode getNode() {
		return this.node;
	}

	public boolean isInterface() {
		return (this.access & Opcodes.ACC_INTERFACE) != 0;
	}

	public String getName() {
		return this.className;
	}

	public Type getType() {
		return Type.getObjectType(this.className);
	}

	public ClassInstance getSuperClass(Classpath classpath) {
		if (this.superClass == null && this.superClassName != null) {
			this.superClass = classpath.getClass(this.superClassName);
		}

		return this.superClass;
	}

	public ClassInstance[] getInterfaces(Classpath classpath) {
		if (this.interfaces == null) {
			this.interfaces = new ClassInstance[this.interfaceNames.length];

			for (int i = 0; i < this.interfaceNames.length; i++) {
				this.interfaces[i] = classpath.getClass(this.interfaceNames[i]);
			}
		}

		return this.interfaces;
	}

	public boolean hasSuperClass(Classpath classpath, ClassInstance cls) {
		if (cls == classpath.getObject()) {
			return true;
		}

		return this.findSuperClass(classpath, cls.className) != null;
	}

	public ClassInstance findSuperClass(Classpath classpath, String superClassName) {
		ClassInstance superClass = this.getSuperClass(classpath);

		if (superClass != null) {
			if (superClass.className.equals(superClassName)) {
				return superClass;
			}

			superClass = superClass.findSuperClass(classpath, superClassName);

			if (superClass != null) {
				return superClass;
			}
		}

		ClassInstance intrface = this.findSuperClassInInterfaces(classpath, superClassName);

		if (intrface != null) {
			return intrface;
		}

		return null;
	}

	public ClassInstance findSuperClassInInterfaces(Classpath classpath, String superClassName) {
		for (ClassInstance intrface : this.getInterfaces(classpath)) {
			if (intrface.className.equals(superClassName)) {
				return intrface;
			}

			intrface = intrface.findSuperClassInInterfaces(classpath, superClassName);

			if (intrface != null) {
				return intrface;
			}
		}

		return null;
	}

	public void markDirty() {
		this.dirty = true;
	}

	public boolean isDirty() {
		return this.dirty;
	}
}
