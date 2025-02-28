package net.ornithemc.condor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.ornithemc.condor.representation.ClassInstance;
import net.ornithemc.condor.representation.Classpath;

public class Condor {

	public static void run(Path jar, Path... libs) throws IOException {
		run(jar, Arrays.asList(libs), Options.builder().build());
	}

	public static void run(Path jar, List<Path> libs, Options options) throws IOException {
		Classpath classpath = new Classpath(jar, libs);

		try {
			// open file systems parse main jar
			classpath.open();

			// create a local variable namer that can be reused
			LocalVariableNamer localVariableNamer = new LocalVariableNamer();

			// generate local variable tables for the main jar
			for (ClassInstance cls : classpath.getMainJar().getClasses()) {
				ClassNode node = cls.getNode();

				for (MethodNode mtd : node.methods) {
					boolean generateLvt = !LocalVariableTables.isComplete(mtd);

					if (generateLvt) {
						LocalVariableTables.generate(classpath, node, mtd);

						if (options.removeInvalidEntries) {
							LocalVariableTables.removeInvalidEntries(node, mtd);
						}
					}

					// generate local variable names
					localVariableNamer.init(options, mtd);
					localVariableNamer.run(generateLvt);

					if (generateLvt) {
						// mark the class for saving
						cls.markDirty();
					}
				}

				// and write the class if any of its method has a new lvt
				if (cls.isDirty()) {
					classpath.getMainJar().writeClass(cls.getName());
				}
			}
		} finally {
			// close file systems
			classpath.close();
		}
	}
}
