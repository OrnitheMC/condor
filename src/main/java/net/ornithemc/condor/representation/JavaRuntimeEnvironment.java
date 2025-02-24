package net.ornithemc.condor.representation;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

public class JavaRuntimeEnvironment extends ClassSource {

	// fast lookup for classes that can't be found in system resources
	private final Set<String> unknownClasses = new HashSet<>();

	@Override
	public ClassInstance getClass(String name) throws IOException {
		if (this.unknownClasses.contains(name)) {
			return null;
		}

		ClassInstance cls = super.getClass(name);

		if (cls == null) {
			this.readClass(name);
		}

		cls = super.getClass(name);

		if (cls == null) {
			this.unknownClasses.add(name);
		}

		return cls;
	}

	private void readClass(String name) throws IOException {
		InputStream is = ClassLoader.getSystemResourceAsStream(name + ".class");

		if (is != null) {
			this.readClass(is, false);
		}
	}
}
