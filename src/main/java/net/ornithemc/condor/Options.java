package net.ornithemc.condor;

public class Options {

	public final boolean removeInvalidEntries;
	public final boolean keepParameterNames;
	public final boolean obfuscateNames;

	private Options(boolean removeInvalidEntries, boolean keepParameterNames, boolean obfuscateNames) {
		this.removeInvalidEntries = removeInvalidEntries;
		this.keepParameterNames = keepParameterNames;
		this.obfuscateNames = obfuscateNames;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private boolean removeInvalidLvtEntries;
		private boolean keepParameterNames;
		private boolean obfuscateNames;

		public Builder removeInvalidEntries() {
			this.removeInvalidLvtEntries = true;
			return this;
		}

		public Builder keepParameterNames() {
			this.keepParameterNames = true;
			return this;
		}

		public Builder obfuscateNames() {
			this.obfuscateNames = true;
			return this;
		}

		public Options build() {
			return new Options(this.removeInvalidLvtEntries, this.keepParameterNames, this.obfuscateNames);
		}
	}
}
