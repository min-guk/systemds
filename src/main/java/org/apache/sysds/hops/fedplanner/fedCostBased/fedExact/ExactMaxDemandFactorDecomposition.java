/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact bounded-width representation of a reusable maximum-demand charge. */
final class ExactMaxDemandFactorDecomposition {
	private ExactMaxDemandFactorDecomposition() { }

	record Demand(ExactCategoricalSolver.Variable consumer,
		boolean[] activeConsumerValues, double[] sourcePrices) {
		Demand {
			Objects.requireNonNull(consumer, "consumer");
			activeConsumerValues = activeConsumerValues.clone();
			sourcePrices = sourcePrices.clone();
			if(activeConsumerValues.length != consumer.domainSize())
				throw new IllegalArgumentException("EXACT_MAX_DEMAND_CONSUMER_DOMAIN_MISMATCH");
			for(double price : sourcePrices)
				if(!Double.isFinite(price) || price < 0d
					|| Double.doubleToRawLongBits(price) == Double.doubleToRawLongBits(-0d))
					throw new IllegalArgumentException("EXACT_MAX_DEMAND_PRICE_INVALID");
		}

		@Override
		public boolean[] activeConsumerValues() { return activeConsumerValues.clone(); }

		@Override
		public double[] sourcePrices() { return sourcePrices.clone(); }
	}

	record Decomposition(ExactCategoricalSolver.Factor canonicalFactor,
		List<ExactCategoricalSolver.Variable> auxiliaryVariables,
		List<ExactCategoricalSolver.Factor> solverFactors, String semanticDescriptor) {
		Decomposition {
			Objects.requireNonNull(canonicalFactor, "canonicalFactor");
			auxiliaryVariables = List.copyOf(auxiliaryVariables);
			solverFactors = List.copyOf(solverFactors);
			if(semanticDescriptor == null || semanticDescriptor.isBlank())
				throw new IllegalArgumentException("EXACT_MAX_DEMAND_DESCRIPTOR_INVALID");
		}
	}

	static Decomposition create(String key, ExactCategoricalSolver.Variable source,
		boolean[] activeSourceValues, List<Demand> inputDemands) {
		if(key == null || key.isBlank())
			throw new IllegalArgumentException("EXACT_MAX_DEMAND_KEY_INVALID");
		Objects.requireNonNull(source, "source");
		boolean[] activeSource = activeSourceValues.clone();
		if(activeSource.length != source.domainSize())
			throw new IllegalArgumentException("EXACT_MAX_DEMAND_SOURCE_DOMAIN_MISMATCH");
		List<Demand> demands = List.copyOf(inputDemands);
		if(demands.isEmpty())
			throw new IllegalArgumentException("EXACT_MAX_DEMAND_EMPTY");
		for(Demand demand : demands)
			if(demand.sourcePrices.length != source.domainSize())
				throw new IllegalArgumentException("EXACT_MAX_DEMAND_PRICE_DOMAIN_MISMATCH");

		List<ExactCategoricalSolver.Variable> scope = new ArrayList<>();
		scope.add(source);
		for(Demand demand : demands) {
			if(demand.consumer != source && demand.consumer.equals(source))
				throw new IllegalArgumentException("EXACT_MAX_DEMAND_VARIABLE_IDENTITY_COLLISION");
			if(scope.stream().noneMatch(variable -> variable == demand.consumer)) {
				if(scope.contains(demand.consumer))
					throw new IllegalArgumentException("EXACT_MAX_DEMAND_VARIABLE_IDENTITY_COLLISION");
				scope.add(demand.consumer);
			}
		}
		scope = List.copyOf(scope);
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions = positions(scope);
		ExactCategoricalSolver.Factor canonical = ExactCategoricalSolver.Factor.lazy(scope, values -> {
			int sourceValue = values[positions.get(source)];
			if(!activeSource[sourceValue])
				return 0d;
			double maximum = 0d;
			for(Demand demand : demands)
				if(demand.activeConsumerValues[values[positions.get(demand.consumer)]])
					maximum = Math.max(maximum, demand.sourcePrices[sourceValue]);
			return maximum;
		});

		Projection sourceProjection = sourceProjection(key, source, activeSource, demands);
		IdentityHashMap<ExactCategoricalSolver.Variable,List<Integer>> demandIndexes =
			new IdentityHashMap<>();
		List<ExactCategoricalSolver.Variable> consumers = new ArrayList<>();
		for(int index = 0; index < demands.size(); index++) {
			ExactCategoricalSolver.Variable consumer = demands.get(index).consumer;
			List<Integer> indexes = demandIndexes.get(consumer);
			if(indexes == null) {
				indexes = new ArrayList<>();
				demandIndexes.put(consumer, indexes);
				consumers.add(consumer);
			}
			indexes.add(index);
		}
		IdentityHashMap<ExactCategoricalSolver.Variable,Projection> consumerProjections =
			new IdentityHashMap<>();
		for(ExactCategoricalSolver.Variable consumer : consumers) {
			Projection projection = consumerProjection(key, consumer, demands,
				demandIndexes.get(consumer));
			consumerProjections.put(consumer, projection);
		}
		if(isIdenticallyZero(activeSource, demands)) {
			String descriptor = descriptor(key, source, activeSource, demands, scope,
				sourceProjection, consumerProjections, consumers, List.of(), List.of())
				+ "|identicallyZero=true";
			return new Decomposition(canonical, List.of(), List.of(), descriptor);
		}

		List<ExactCategoricalSolver.Variable> auxiliaries = new ArrayList<>();
		List<ExactCategoricalSolver.Factor> solverFactors = new ArrayList<>();
		auxiliaries.add(sourceProjection.variable);
		solverFactors.add(sourceProjection.linkFactor(source));
		for(ExactCategoricalSolver.Variable consumer : consumers) {
			Projection projection = consumerProjections.get(consumer);
			auxiliaries.add(projection.variable);
			solverFactors.add(projection.linkFactor(consumer));
		}

		List<ExactCategoricalSolver.Variable> maxima = new ArrayList<>(demands.size());
		for(int index = 0; index < demands.size(); index++) {
			ExactCategoricalSolver.Variable maximum = new ExactCategoricalSolver.Variable(
				key + "|maximum-after=" + index, index + 2);
			maxima.add(maximum);
			auxiliaries.add(maximum);
			Demand demand = demands.get(index);
			Projection consumerProjection = consumerProjections.get(demand.consumer);
			int demandIndex = index;
			if(index == 0)
				solverFactors.add(ExactCategoricalSolver.Factor.lazy(
					List.of(sourceProjection.variable, consumerProjection.variable, maximum), values -> {
						int expected = updatedMaximum(0, demandIndex, values[0], values[1],
							sourceProjection, consumerProjection, demands);
						return values[2] == expected ? 0d : Double.POSITIVE_INFINITY;
					}));
			else {
				ExactCategoricalSolver.Variable previous = maxima.get(index - 1);
				solverFactors.add(ExactCategoricalSolver.Factor.lazy(List.of(sourceProjection.variable,
					consumerProjection.variable, previous, maximum), values -> {
						int expected = updatedMaximum(values[2], demandIndex, values[0], values[1],
							sourceProjection, consumerProjection, demands);
						return values[3] == expected ? 0d : Double.POSITIVE_INFINITY;
					}));
			}
		}
		ExactCategoricalSolver.Variable last = maxima.get(maxima.size() - 1);
		solverFactors.add(ExactCategoricalSolver.Factor.lazy(
			List.of(sourceProjection.variable, last), values -> {
				if(values[1] == 0)
					return 0d;
				int representativeSource = sourceProjection.representativeOriginalValue[values[0]];
				return demands.get(values[1] - 1).sourcePrices[representativeSource];
			}));

		String descriptor = descriptor(key, source, activeSource, demands, scope,
			sourceProjection, consumerProjections, consumers, maxima, solverFactors);
		return new Decomposition(canonical, auxiliaries, solverFactors, descriptor);
	}

	private static boolean isIdenticallyZero(boolean[] activeSource, List<Demand> demands) {
		for(int sourceValue = 0; sourceValue < activeSource.length; sourceValue++) {
			if(!activeSource[sourceValue])
				continue;
			for(Demand demand : demands) {
				if(demand.sourcePrices[sourceValue] == 0d)
					continue;
				for(boolean activeConsumer : demand.activeConsumerValues)
					if(activeConsumer)
						return false;
			}
		}
		return true;
	}

	private static int updatedMaximum(int previous, int demandIndex, int sourceClass,
		int consumerClass, Projection sourceProjection, Projection consumerProjection,
		List<Demand> demands) {
		int sourceValue = sourceProjection.representativeOriginalValue[sourceClass];
		if(!sourceProjection.activeSourceClass[sourceClass]
			|| !consumerProjection.observations[consumerClass][demandIndex])
			return previous;
		if(previous == 0)
			return demandIndex + 1;
		double previousPrice = demands.get(previous - 1).sourcePrices[sourceValue];
		double currentPrice = demands.get(demandIndex).sourcePrices[sourceValue];
		return currentPrice > previousPrice ? demandIndex + 1 : previous;
	}

	private static Projection sourceProjection(String key,
		ExactCategoricalSolver.Variable source, boolean[] activeSource, List<Demand> demands) {
		List<String> signatures = new ArrayList<>(source.domainSize());
		for(int value = 0; value < source.domainSize(); value++) {
			StringBuilder signature = new StringBuilder(Boolean.toString(activeSource[value]));
			for(Demand demand : demands)
				signature.append(':').append(Long.toUnsignedString(
					Double.doubleToRawLongBits(demand.sourcePrices[value]), 16));
			signatures.add(signature.toString());
		}
		Projection projection = projection(key + "|source-class", signatures, demands.size());
		boolean[] activeClasses = new boolean[projection.variable.domainSize()];
		for(int original = 0; original < source.domainSize(); original++)
			activeClasses[projection.originalToClass[original]] = activeSource[original];
		return projection.withActiveSourceClass(activeClasses);
	}

	private static Projection consumerProjection(String key,
		ExactCategoricalSolver.Variable consumer, List<Demand> demands, List<Integer> indexes) {
		List<String> signatures = new ArrayList<>(consumer.domainSize());
		for(int value = 0; value < consumer.domainSize(); value++) {
			StringBuilder signature = new StringBuilder();
			for(int index : indexes)
				signature.append(demands.get(index).activeConsumerValues[value] ? '1' : '0');
			signatures.add(signature.toString());
		}
		Projection projection = projection(key + "|consumer-class=" + consumer.key(),
			signatures, demands.size());
		boolean[][] observations = new boolean[projection.variable.domainSize()][demands.size()];
		for(int original = 0; original < consumer.domainSize(); original++)
			for(int index : indexes)
				observations[projection.originalToClass[original]][index] =
					demands.get(index).activeConsumerValues[original];
		return projection.withObservations(observations);
	}

	private static Projection projection(String key, List<String> signatures,
		int observationCount) {
		Map<String,Integer> classes = new LinkedHashMap<>();
		int[] originalToClass = new int[signatures.size()];
		List<Integer> representatives = new ArrayList<>();
		for(int value = 0; value < signatures.size(); value++) {
			Integer prior = classes.get(signatures.get(value));
			if(prior == null) {
				prior = classes.size();
				classes.put(signatures.get(value), prior);
				representatives.add(value);
			}
			originalToClass[value] = prior;
		}
		return new Projection(new ExactCategoricalSolver.Variable(key, classes.size()),
			originalToClass, representatives.stream().mapToInt(Integer::intValue).toArray(),
			new boolean[classes.size()][observationCount], new boolean[classes.size()]);
	}

	private static String descriptor(String key, ExactCategoricalSolver.Variable source,
		boolean[] activeSource, List<Demand> demands,
		List<ExactCategoricalSolver.Variable> scope, Projection sourceProjection,
		IdentityHashMap<ExactCategoricalSolver.Variable,Projection> consumerProjections,
		List<ExactCategoricalSolver.Variable> consumers,
		List<ExactCategoricalSolver.Variable> maxima,
		List<ExactCategoricalSolver.Factor> solverFactors) {
		StringBuilder descriptor = new StringBuilder("EXACT_MAX_DEMAND_V1|key=").append(key)
			.append("|scope=").append(scope.stream().map(ExactCategoricalSolver.Variable::key).toList())
			.append("|source=").append(source.key()).append("|sourceActive=")
			.append(Arrays.toString(activeSource));
		for(int index = 0; index < demands.size(); index++) {
			Demand demand = demands.get(index);
			descriptor.append("|demand=").append(index).append(':').append(demand.consumer.key())
				.append(":active=").append(Arrays.toString(demand.activeConsumerValues))
				.append(":prices=");
			for(double price : demand.sourcePrices)
				descriptor.append(Long.toUnsignedString(Double.doubleToRawLongBits(price), 16)).append(',');
		}
		descriptor.append("|sourceProjection=").append(Arrays.toString(sourceProjection.originalToClass));
		for(ExactCategoricalSolver.Variable consumer : consumers)
			descriptor.append("|consumerProjection=").append(consumer.key()).append(':')
				.append(Arrays.toString(consumerProjections.get(consumer).originalToClass));
		descriptor.append("|maxima=").append(maxima.stream()
			.map(variable -> variable.key() + ':' + variable.domainSize()).toList());
		descriptor.append("|solverScopes=").append(solverFactors.stream().map(factor ->
			factor.scope().stream().map(variable -> variable.key() + ':' + variable.domainSize())
				.toList()).toList());
		return descriptor.toString();
	}

	private static IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions(
		List<ExactCategoricalSolver.Variable> variables) {
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> result = new IdentityHashMap<>();
		for(int index = 0; index < variables.size(); index++)
			result.put(variables.get(index), index);
		return result;
	}

	private record Projection(ExactCategoricalSolver.Variable variable, int[] originalToClass,
		int[] representativeOriginalValue, boolean[][] observations,
		boolean[] activeSourceClass) {
		private Projection withObservations(boolean[][] replacement) {
			return new Projection(variable, originalToClass, representativeOriginalValue,
				replacement, activeSourceClass);
		}

		private Projection withActiveSourceClass(boolean[] replacement) {
			return new Projection(variable, originalToClass, representativeOriginalValue,
				observations, replacement);
		}

		private ExactCategoricalSolver.Factor linkFactor(
			ExactCategoricalSolver.Variable original) {
			return ExactCategoricalSolver.Factor.lazy(List.of(original, variable), values ->
				originalToClass[values[0]] == values[1] ? 0d : Double.POSITIVE_INFINITY);
		}
	}
}
