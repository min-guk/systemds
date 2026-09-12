/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;

/** Shared, fail-closed elimination-order configuration for Global and Local exact solves. */
final class ExactEliminationOrderPolicy {
	static final String FAST_ORDER_PROPERTY = "sysds.fedplanner.exact.fastOrder";
	static final String FAST_ORDER_ASSIGNMENTS_PROPERTY =
		"sysds.fedplanner.exact.fastOrderAssignments";
	static final String LEGACY_LOCAL_FAST_ORDER_PROPERTY =
		"sysds.fedplanner.regional.fastBlockOrder";
	static final String LEGACY_LOCAL_FAST_ORDER_ASSIGNMENTS_PROPERTY =
		"sysds.fedplanner.regional.fastBlockAssignments";
	static final long DEFAULT_MAXIMUM_ASSIGNMENTS = 1_000_000L;
	static final long LEGACY_LOCAL_DEFAULT_MAXIMUM_ASSIGNMENTS = 100_000L;

	record Configuration(boolean fastOrder, long maximumAssignments, String source) {
		Configuration {
			if(maximumAssignments <= 0)
				throw new IllegalArgumentException(
					"EXACT_FAST_ORDER_ASSIGNMENTS_INVALID|value=" + maximumAssignments);
			if(source == null || source.isBlank())
				throw new IllegalArgumentException("EXACT_FAST_ORDER_SOURCE_INVALID");
		}
	}

	private ExactEliminationOrderPolicy() { }

	static Configuration globalConfigured() {
		return commonConfigured(false);
	}

	static Configuration localConfigured(boolean compact) {
		if(System.getProperty(FAST_ORDER_PROPERTY) != null
			|| System.getProperty(FAST_ORDER_ASSIGNMENTS_PROPERTY) != null)
			return commonConfigured(true);
		boolean enabled = !compact && parseBoolean(LEGACY_LOCAL_FAST_ORDER_PROPERTY,
			"false", "REGIONAL_FAST_BLOCK_ORDER_INVALID");
		long assignments = enabled ? parsePositiveLong(
			LEGACY_LOCAL_FAST_ORDER_ASSIGNMENTS_PROPERTY,
			Long.toString(LEGACY_LOCAL_DEFAULT_MAXIMUM_ASSIGNMENTS),
			"REGIONAL_FAST_BLOCK_ASSIGNMENTS_INVALID")
			: LEGACY_LOCAL_DEFAULT_MAXIMUM_ASSIGNMENTS;
		return new Configuration(enabled, assignments, "legacy-regional");
	}

	static ExactCategoricalSolver.OrderCompilation compile(
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits, Configuration configuration) {
		return compile(variables, factors, limits, configuration, "unspecified");
	}

	static ExactCategoricalSolver.OrderCompilation compile(
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits, Configuration configuration,
		String caller) {
		if(configuration == null)
			throw new IllegalArgumentException("EXACT_FAST_ORDER_CONFIGURATION_MISSING");
		if(caller == null || !caller.matches("[a-z0-9-]+"))
			throw new IllegalArgumentException("EXACT_FAST_ORDER_CALLER_INVALID|value=" + caller);
		long started = System.nanoTime();
		ExactCategoricalSolver.OrderCompilation compilation =
			ExactCategoricalSolver.compileWithFastOrder(variables, factors, limits,
				configuration.fastOrder(), configuration.maximumAssignments());
		long compileNanos = System.nanoTime() - started;
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("Exact-OrderSelection",
				"caller=" + caller
					+ " fastOrderSource=" + configuration.source()
					+ " fastOrderConfigured=" + configuration.fastOrder()
					+ " fastOrderAssignmentsLimit=" + configuration.maximumAssignments()
					+ " fastOrderAccepted=" + compilation.fastOrderAccepted()
					+ " fastOrderFallback=" + compilation.fastOrderFallback()
					+ " fastOrderEstimatedAssignments=" + compilation.fastOrderAssignments()
					+ " compileNanos=" + compileNanos);
		return compilation;
	}

	private static Configuration commonConfigured(boolean requireExplicitFlag) {
		String raw = System.getProperty(FAST_ORDER_PROPERTY);
		if(raw == null && requireExplicitFlag
			&& System.getProperty(FAST_ORDER_ASSIGNMENTS_PROPERTY) != null)
			throw new IllegalArgumentException("EXACT_FAST_ORDER_OPTION_MISSING");
		boolean enabled = parseBoolean(FAST_ORDER_PROPERTY, "false",
			"EXACT_FAST_ORDER_OPTION_INVALID");
		long assignments = enabled ? parsePositiveLong(FAST_ORDER_ASSIGNMENTS_PROPERTY,
			Long.toString(DEFAULT_MAXIMUM_ASSIGNMENTS),
			"EXACT_FAST_ORDER_ASSIGNMENTS_INVALID") : DEFAULT_MAXIMUM_ASSIGNMENTS;
		return new Configuration(enabled, assignments, "common-exact");
	}

	private static boolean parseBoolean(String property, String defaultValue, String error) {
		String value = System.getProperty(property, defaultValue);
		if(!"true".equals(value) && !"false".equals(value))
			throw new IllegalArgumentException(error + "|value=" + value);
		return Boolean.parseBoolean(value);
	}

	private static long parsePositiveLong(String property, String defaultValue, String error) {
		String value = System.getProperty(property, defaultValue);
		try {
			long parsed = Long.parseLong(value);
			if(parsed <= 0)
				throw new NumberFormatException();
			return parsed;
		}
		catch(NumberFormatException failure) {
			throw new IllegalArgumentException(error + "|value=" + value, failure);
		}
	}
}
