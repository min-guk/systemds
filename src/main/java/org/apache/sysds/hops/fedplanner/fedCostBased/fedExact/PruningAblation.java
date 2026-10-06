/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

/** Production pruning defaults and explicit feature gates for ablation experiments. */
final class PruningAblation {
	static final String PROPERTY = "sysds.fedplanner.pruning.ablation";

	enum Variant {
		BASELINE(false),
		LOCAL_ONLY(true);

		private final boolean local;

		Variant(boolean local) {
			this.local = local;
		}

		boolean local() { return local; }
	}

	record Options(boolean explicit, Variant variant) {
		boolean local() { return variant.local(); }
	}

	private PruningAblation() { }

	static Options current() {
		String configured = System.getProperty(PROPERTY);
		if(configured == null)
			return new Options(false, Variant.LOCAL_ONLY);
		try {
			return new Options(true, Variant.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT)));
		}
		catch(IllegalArgumentException invalid) {
			throw new IllegalArgumentException("FED_PLANNER_PRUNING_ABLATION_INVALID|value=" + configured,
				invalid);
		}
	}
}
