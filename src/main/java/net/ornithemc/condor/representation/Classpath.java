package net.ornithemc.condor.representation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.objectweb.asm.Type;

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
}
