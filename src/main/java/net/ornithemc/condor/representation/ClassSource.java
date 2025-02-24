package net.ornithemc.condor.representation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.tree.ClassNode;

import net.ornithemc.condor.ASM;

public class ClassSource {

	private final Map<String, ClassInstance> classInstances;

	protected ClassSource() {
		this.classInstances = new HashMap<>();
	}

	public void open() throws IOException {
	}

	public void close() throws IOException {
	}

	public Collection<ClassInstance> getClasses() {
		return this.classInstances.values();
	}

	public ClassInstance getClass(String name) throws IOException {
		return this.classInstances.get(name);
	}

	public boolean hasClass(String name) {
		return this.classInstances.containsKey(name);
	}

	protected void addClass(ClassInstance cls) {
		this.classInstances.put(cls.getName(), cls);
	}

	protected void readClass(InputStream is, boolean fully) throws IOException {
		ClassReader reader = new ClassReader(is);
		ClassNode node = fully ? new ClassNode() : null;
		ClassVisitor visitor = new ClassVisitor(ASM.API_VERSION, node) {

			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				ClassSource.this.addClass(new ClassInstance(node, access, name, superName, interfaces));

				super.visit(version, access, name, signature, superName, interfaces);
			}
		};

		reader.accept(visitor, fully ? 0 : ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
	}

	public void writeClass(String name) throws IOException {
	}
}
