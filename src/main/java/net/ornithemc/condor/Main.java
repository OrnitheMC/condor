package net.ornithemc.condor;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {

	public static void main(String... args) throws Exception {
		if (args.length < 1) {
			System.out.println("Expected 1 argument, got " + args.length);
			System.out.println("Usage: <jar> [<lib>...]");

			System.exit(1);
		}

		Path jar = Paths.get(args[0]);
		Path[] libs = new Path[args.length - 1];
		for (int i = 1; i < args.length; i++) {
			libs[i - 1] = Paths.get(args[i]);
		}

		Condor.run(jar, libs);
	}
}
