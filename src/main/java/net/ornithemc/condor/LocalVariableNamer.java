package net.ornithemc.condor;

import java.util.HashSet;
import java.util.Set;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

public class LocalVariableNamer {

	private MethodNode method;

	// current states for primitive types that count up
	// through chars (as opposed to adding a number suffix)
	private CharCounter charName = new CharCounter('c');
	private CharCounter byteName = new CharCounter('b');
	private CharCounter shortName = new CharCounter('s');
	private CharCounter intName = new CharCounter('i');
	private CharCounter floatName = new CharCounter('f');
	private CharCounter longName = new CharCounter('l');
	private CharCounter doubleName = new CharCounter('d');

	private Set<String> names = new HashSet<>();
	private Set<String> duplicates = new HashSet<>();

	public void init(MethodNode method) {
		this.method = method;

		this.charName.reset();
		this.byteName.reset();
		this.shortName.reset();
		this.intName.reset();
		this.floatName.reset();
		this.longName.reset();
		this.doubleName.reset();

		this.names.clear();
		this.duplicates.clear();
	}

	public void generateNames() {
		// generate names based on the variable types
		for (LocalVariableNode localVariable : this.method.localVariables) {
			String desc = localVariable.desc;
			Type type = Type.getType(desc);

			String name = this.generateName(type);

			if (!this.names.add(name)) {
				this.duplicates.add(name);
			}

			localVariable.name = name;
		}
		// then fix up duplicate names
		for (int i = 0; i < this.method.localVariables.size(); i++) {
			LocalVariableNode localVariable = this.method.localVariables.get(i);

			if (this.duplicates.contains(localVariable.name)) {
				localVariable.name += i;
			}
		}
	}

	private String generateName(Type type) {
		return this.generateName(type, false);
	}

	private String generateName(Type type, boolean array) {
		switch (type.getSort()) {
		case Type.BOOLEAN:
			return "bl";
		case Type.CHAR:
			return this.charName.increment(array);
		case Type.BYTE:
			return this.byteName.increment(array);
		case Type.SHORT:
			return this.shortName.increment(array);
		case Type.INT:
			return this.intName.increment(array);
		case Type.FLOAT:
			return this.floatName.increment(array);
		case Type.LONG:
			return this.longName.increment(array);
		case Type.DOUBLE:
			return this.doubleName.increment(array);
		case Type.ARRAY:
			return this.generateName(type.getElementType(), true) + "s";
		case Type.OBJECT:
			// find simple name of this object type
			String simpleName = this.getSimpleName(type.getInternalName());
			// make camelCase
			return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
		}

		throw new IllegalStateException("type sort " + type.getSort() + " is not a valid variable type!");
	}

	private String getSimpleName(String className) {
		int i = className.lastIndexOf('$');

		if (i < 0) {
			// not inner class, find simple name based on package separator
			return className.substring(className.lastIndexOf('/') + 1);
		}

		// skip to first char after inner name separator
		int j = ++i;

		// class could be local, skip number prefix
		while (j < className.length() && Character.isDigit(className.charAt(j))) {
			j++;
		}

		// if all chars after the last $ are digits, the class is anonymous
		// then just use the anonymous class number as simple name
		return className.substring(j == className.length() ? i : j);
	}

	private static class CharCounter {

		private char start;
		private String name;
		private String arrayName;

		public CharCounter(char start) {
			this.start = start;
		}

		public void reset() {
			this.name = String.valueOf(this.start);
			this.arrayName = String.valueOf(this.start);
		}

		public String increment(boolean array) {
			String name;

			if (array) {
				name = this.arrayName;
				this.arrayName = this.increment(this.arrayName);
			} else {
				name = this.name;
				this.name = this.increment(this.name);
			}

			return name;
		}

		private String increment(String name) {
			StringBuilder sb = new StringBuilder(name);

			// the current char index in the name
			int place = name.length() - 1;

			while (place >= 0) {
				char chr = name.charAt(place);

				// roll over if needed
				if (chr++ == 'z') {
					chr = 'a';
				}

				sb.setCharAt(place, chr);

				if (chr == this.start) {
					if (place == 0) {
						// every char counted up, prepend a new one
						sb.insert(0, this.start);
					}

					place--;
				} else {
					// current char can count up further still
					place = -1;
				}
			}

			return sb.toString();
		}
	}
}
