/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/** Shared configuration and factor evaluation for the retained Regional search. */
final class CertifiedRegionalOptimizer {
	static final String PROPERTY_PREFIX = "sysds.fedplanner.regional.";

	record Options(int initialWidth, long timeBudgetMillis, double absoluteTolerance,
		double relativeTolerance, Limits limits) {
		Options {
			if(initialWidth < 1 || timeBudgetMillis < 0
				|| !Double.isFinite(absoluteTolerance) || absoluteTolerance < 0d
				|| !Double.isFinite(relativeTolerance) || relativeTolerance < 0d)
				throw new IllegalArgumentException("REGIONAL_OPTIONS_INVALID");
			Objects.requireNonNull(limits, "limits");
		}

		/** Null means the unchanged plain Regional planner. Options are read once per invocation. */
		static Options configured() {
			String mode = System.getProperty(PROPERTY_PREFIX + "mode", "off").toLowerCase(Locale.ROOT);
			if(mode.equals("off"))
				return null;
			if(!mode.equals("remaining-exact") && !mode.equals("anytime"))
				throw new IllegalArgumentException("REGIONAL_MODE_INVALID|mode=" + mode
					+ "|supported=off,remaining-exact,anytime");
			Limits production = ExactPhysicalOptimizer.PRODUCTION_LIMITS;
			long factorCells = longOption("factorCells", 1_000_000L);
			long totalCells = longOption("totalCells", 5_000_000L);
			if(factorCells > production.maximumFactorCells()
				|| totalCells > production.maximumMaterializedCells())
				throw new IllegalArgumentException("REGIONAL_LIMIT_EXCEEDS_PRODUCTION_CEILING");
			return new Options(intOption("width", 2), longOption("timeMillis", 1000L),
				doubleOption("absoluteGap", 0d), doubleOption("relativeGap", 0.05d),
				new Limits(factorCells, totalCells));
		}

		private static int intOption(String key, int fallback) {
			return Integer.parseInt(System.getProperty(PROPERTY_PREFIX + key, Integer.toString(fallback)));
		}
		private static long longOption(String key, long fallback) {
			return Long.parseLong(System.getProperty(PROPERTY_PREFIX + key, Long.toString(fallback)));
		}
		private static double doubleOption(String key, double fallback) {
			return Double.parseDouble(System.getProperty(PROPERTY_PREFIX + key, Double.toString(fallback)));
		}
	}

	private CertifiedRegionalOptimizer() { }

	/** Reevaluate a complete factor model, including hard constraints and constants. */
	static double evaluate(List<Variable> variables, List<Factor> factors, List<Integer> assignment) {
		if(assignment == null || assignment.size() != variables.size())
			throw new IllegalArgumentException("REGIONAL_ASSIGNMENT_SIZE_INVALID");
		IdentityHashMap<Variable,Integer> positions = new IdentityHashMap<>();
		Set<String> keys = new LinkedHashSet<>();
		for(int index = 0; index < variables.size(); index++) {
			Variable variable = variables.get(index);
			if(positions.put(variable, index) != null || !keys.add(variable.key()))
				throw new IllegalArgumentException("REGIONAL_VARIABLE_DUPLICATE");
			Integer value = assignment.get(index);
			if(value == null || value < 0 || value >= variable.domainSize())
				throw new IllegalArgumentException("REGIONAL_ASSIGNMENT_VALUE_INVALID");
		}
		ExactCompensatedCostSum sum = new ExactCompensatedCostSum();
		for(Factor factor : factors) {
			int[] values = new int[factor.scope().size()];
			for(int local = 0; local < values.length; local++) {
				Integer position = positions.get(factor.scope().get(local));
				if(position == null)
					throw new IllegalArgumentException("REGIONAL_FACTOR_SCOPE_INVALID");
				values[local] = assignment.get(position);
			}
			double cost = factor.cost(values);
			if(cost == Double.POSITIVE_INFINITY)
				return cost;
			sum.addBits(Double.doubleToRawLongBits(cost), "REGIONAL_COST_INVALID", "REGIONAL_TOTAL_INVALID");
		}
		return Double.longBitsToDouble(sum.totalBits("REGIONAL_TOTAL_INVALID"));
	}
}
