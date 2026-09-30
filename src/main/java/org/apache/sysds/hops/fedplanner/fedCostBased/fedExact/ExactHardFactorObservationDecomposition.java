/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.math.BigInteger;

/** Lossless exact-kernel encoding of one hard factor through factor-local observations. */
final class ExactHardFactorObservationDecomposition {
	private static final class RepresentativeTruthEvaluator
		implements ExactCategoricalSolver.CostFunction {
		private final ExactCategoricalSolver.Factor canonical;
		private final List<int[]> representatives;
		private final int[] sourceValues;

		private RepresentativeTruthEvaluator(ExactCategoricalSolver.Factor canonical,
			List<int[]> representatives) {
			this.canonical = canonical;
			this.representatives = representatives;
			sourceValues = new int[canonical.scope().size()];
		}

		@Override
		public synchronized double cost(int[] values) {
			for(int position = 0; position < sourceValues.length; position++)
				sourceValues[position] = representatives.get(position)[values[position]];
			return canonical.cost(sourceValues);
		}
	}

	record Result(List<ExactCategoricalSolver.Variable> auxiliaryVariables,
		List<ExactCategoricalSolver.Factor> solverFactors, long canonicalCells,
		long encodedCells, List<int[]> observations, String descriptor) {
		Result {
			auxiliaryVariables = List.copyOf(auxiliaryVariables);
			solverFactors = List.copyOf(solverFactors);
			observations = observations.stream().map(int[]::clone).toList();
		}
		@Override
		public List<int[]> observations() {
			return observations.stream().map(int[]::clone).toList();
		}
	}

	private ExactHardFactorObservationDecomposition() { }

	static Result create(String key, ExactCategoricalSolver.Factor canonical,
		List<?>[] observationKeys) {
		if(observationKeys.length != canonical.scope().size())
			throw new IllegalArgumentException("EXACT_HARD_OBSERVATION_SCOPE_MISMATCH");
		List<int[]> observations = new ArrayList<>(observationKeys.length);
		List<int[]> representatives = new ArrayList<>(observationKeys.length);
		List<ExactCategoricalSolver.Variable> auxiliaries = new ArrayList<>(observationKeys.length);
		BigInteger canonicalCellCount = BigInteger.ONE;
		BigInteger encodedCellCount = BigInteger.ZERO;
		for(int position = 0; position < observationKeys.length; position++) {
			var source = canonical.scope().get(position);
			if(observationKeys[position].size() != source.domainSize())
				throw new IllegalArgumentException("EXACT_HARD_OBSERVATION_DOMAIN_MISMATCH");
			Map<Object,Integer> categories = new LinkedHashMap<>();
			int[] encoded = new int[source.domainSize()];
			List<Integer> categoryRepresentatives = new ArrayList<>();
			for(int value = 0; value < source.domainSize(); value++) {
				Object observation = observationKeys[position].get(value);
				Integer category = categories.get(observation);
				if(category == null && !categories.containsKey(observation)) {
					category = categories.size();
					categories.put(observation, category);
					categoryRepresentatives.add(value);
				}
				encoded[value] = category;
			}
			int categoryCount = categories.size();
			observations.add(encoded);
			representatives.add(categoryRepresentatives.stream().mapToInt(Integer::intValue).toArray());
			auxiliaries.add(new ExactCategoricalSolver.Variable(
				"exact-hard-observation|" + key + "|position=" + position, categoryCount));
			canonicalCellCount = canonicalCellCount.multiply(BigInteger.valueOf(source.domainSize()));
			encodedCellCount = encodedCellCount.add(BigInteger.valueOf(source.domainSize())
				.multiply(BigInteger.valueOf(categoryCount)));
		}
		BigInteger truthCellCount = BigInteger.ONE;
		for(var auxiliary : auxiliaries)
			truthCellCount = truthCellCount.multiply(BigInteger.valueOf(auxiliary.domainSize()));
		encodedCellCount = encodedCellCount.add(truthCellCount);
		if(encodedCellCount.compareTo(canonicalCellCount) >= 0)
			return null;
		long canonicalCells = saturatedLong(canonicalCellCount);
		long encodedCells = saturatedLong(encodedCellCount);

		List<ExactCategoricalSolver.Factor> solverFactors = new ArrayList<>();
		for(int position = 0; position < auxiliaries.size(); position++) {
			var source = canonical.scope().get(position);
			var auxiliary = auxiliaries.get(position);
			int[] categories = observations.get(position);
			solverFactors.add(ExactCategoricalSolver.Factor.lazy(List.of(source, auxiliary), values ->
				categories[values[0]] == values[1] ? 0.0 : Double.POSITIVE_INFINITY));
		}
		solverFactors.add(ExactCategoricalSolver.Factor.lazy(auxiliaries,
			new RepresentativeTruthEvaluator(canonical, representatives)));
		String descriptor = key + "|canonicalScope=" + canonical.scope().stream()
			.map(ExactCategoricalSolver.Variable::key).toList() + "|canonicalCells="
			+ canonicalCellCount + "|encodedCells=" + encodedCellCount + "|categories="
			+ auxiliaries.stream().map(ExactCategoricalSolver.Variable::domainSize).toList()
			+ "|maps=" + observations.stream().map(Arrays::toString).toList()
			+ "|representatives=" + representatives.stream().map(Arrays::toString).toList();
		return new Result(auxiliaries, solverFactors, canonicalCells, encodedCells,
			observations, descriptor);
	}

	private static long saturatedLong(BigInteger value) {
		return value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
			? Long.MAX_VALUE : value.longValueExact();
	}

	static List<?> keys(int[] values) {
		return Arrays.stream(values).boxed().toList();
	}
}
