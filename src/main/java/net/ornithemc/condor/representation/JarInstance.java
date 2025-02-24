package net.ornithemc.condor.representation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

public class JarInstance extends ClassSource {

	private final Path path;
	private final boolean mainJar;
	private final Set<String> classesToRead;

	// the filesystem currently in use
	// this is reused to avoid opening a new one for each class read
	private FileSystem fs;

	public JarInstance(Path path, boolean mainJar) throws IOException {
		this.path = path;
		this.mainJar = mainJar;
		this.classesToRead = new HashSet<>();
	}

	@Override
	public void open() throws IOException {
		if (this.fs == null) {
			this.fs = FileSystems.newFileSystem(this.path, (ClassLoader) null);
		}

		this.findClasses();
	}

	@Override
	public void close() throws IOException {
		if (this.fs != null) {
			this.fs.close();
			this.fs = null;
		}

		this.classesToRead.clear();
	}

	@Override
	public ClassInstance getClass(String name) throws IOException {
		if (this.classesToRead.remove(name)) {
			this.readClass(name);
		}

		return super.getClass(name);
	}

	@Override
	public void writeClass(String name) throws IOException {
		Path classFile = this.getClassFile(name);

		if (classFile != null) {
			ClassInstance cls = this.getClass(name);
			ClassNode node = cls.getNode();

			if (node != null) {
				ClassWriter writer = new ClassWriter(0);
				node.accept(writer);

				Files.write(classFile, writer.toByteArray());
			}
		}
	}

	private void findClasses() throws IOException {
		for (Path root : this.fs.getRootDirectories()) {
			try (Stream<Path> classFiles = Files.find(root, Integer.MAX_VALUE, (p, a) -> a.isRegularFile() && p.toString().endsWith(".class"))) {
				classFiles.forEach(classFile -> {
					Path relativePath = root.relativize(classFile);
					String pathName = relativePath.toString();
					String className = pathName.substring(0, pathName.length() - ".class".length());

					if (JarInstance.this.mainJar) {
						// for the main jar, all classes need to be parsed anyway
						// for the local variable table generation
						try (InputStream is = Files.newInputStream(classFile)) {
							JarInstance.this.readClass(is, JarInstance.this.mainJar);
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					} else {
						if (!JarInstance.this.hasClass(className)) {
							JarInstance.this.classesToRead.add(className);
						}
					}
				});
			} catch (UncheckedIOException e) {
				throw e.getCause();
			}
		}
	}

	private void readClass(String name) throws IOException {
		Path classFile = this.getClassFile(name);

		if (classFile != null) {
			try (InputStream is = Files.newInputStream(classFile)) {
				this.readClass(is, this.mainJar);
			}
		}
	}

	private Path getClassFile(String name) {
		for (Path root : fs.getRootDirectories()) {
			Path classFile = root.resolve(name + ".class");

			if (Files.exists(classFile) && Files.isRegularFile(classFile)) {
				return classFile;
			}
		}

		return null;
	}
}
