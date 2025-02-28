package net.ornithemc.condor;

public class Options {

	public final boolean removeInvalidEntries;
	public final boolean keepParameterNames;

	private Options(boolean removeInvalidEntries, boolean keepParameterNames) {
		this.removeInvalidEntries = removeInvalidEntries;
		this.keepParameterNames = keepParameterNames;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private boolean removeInvalidLvtEntries;
		private boolean keepParameterNames;

		public Builder removeInvalidEntries() {
			this.removeInvalidLvtEntries = true;
			return this;
		}

		public Builder keepParameterNames() {
			this.keepParameterNames = true;
			return this;
		}

		public Options build() {
			return new Options(this.removeInvalidLvtEntries, this.keepParameterNames);
		}
	}
}
