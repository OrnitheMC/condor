package net.ornithemc.condor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class Main {

	public static void main(String... args) throws Exception {
		if (args.length < 1) {
			System.out.println("Expected at least 1 argument, got " + args.length);
			System.out.println("Usage: <jar> [<lib>...] [--remove-invalid-entries] [--keep-parameter-names] [--obfuscate-names]");

			System.exit(1);
		}

		Path jar = Paths.get(args[0]);
		List<Path> libs = new ArrayList<>();
		Options.Builder options = Options.builder();

		for (int i = 1; i < args.length; i++) {
			String arg = args[i];

			if (arg.startsWith("--")) {
				String option = arg.substring(2);

				switch (option) {
				case "remove-invalid-entries":
					options.removeInvalidEntries();
					break;
				case "keep-parameter-names":
					options.keepParameterNames();
					break;
				case "obfuscate-names":
					options.obfuscateNames();
					break;
				default:
					throw new IllegalArgumentException("unknown option " + option);
				}
			} else {
				libs.add(Paths.get(arg));
			}
		}

		Condor.run(jar, libs, options.build());
	}
}
