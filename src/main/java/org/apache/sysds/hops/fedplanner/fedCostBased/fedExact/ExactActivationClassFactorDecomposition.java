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
import java.util.List;
import java.util.Objects;

/** Exact bounded-width Boolean-OR representation of one activation-class charge. */
final class ExactActivationClassFactorDecomposition {
	private ExactActivationClassFactorDecomposition() { }

	record Demand(ExactCategoricalSolver.Variable consumer, boolean[] activeConsumerValues) {
		Demand {
			Objects.requireNonNull(consumer, "consumer");
			activeConsumerValues = Objects.requireNonNull(activeConsumerValues,
				"activeConsumerValues").clone();
			if(activeConsumerValues.length != consumer.domainSize())
				throw new IllegalArgumentException(
					"EXACT_ACTIVATION_CLASS_CONSUMER_DOMAIN_MISMATCH");
		}

		@Override
		public boolean[] activeConsumerValues() { return activeConsumerValues.clone(); }
	}

	record Decomposition(ExactCategoricalSolver.Factor canonicalFactor,
		List<ExactCategoricalSolver.Variable> auxiliaryVariables,
		List<ExactCategoricalSolver.Factor> solverFactors, String semanticDescriptor) {
		Decomposition {
			Objects.requireNonNull(canonicalFactor, "canonicalFactor");
			auxiliaryVariables = List.copyOf(auxiliaryVariables);
			solverFactors = List.copyOf(solverFactors);
			if(semanticDescriptor == null || semanticDescriptor.isBlank())
				throw new IllegalArgumentException(
					"EXACT_ACTIVATION_CLASS_DESCRIPTOR_INVALID");
		}
	}

	static Decomposition create(String key, ExactCategoricalSolver.Variable source,
		boolean[] activeSourceValues, double[] sourcePrices, List<Demand> inputDemands) {
		if(key == null || key.isBlank())
			throw new IllegalArgumentException("EXACT_ACTIVATION_CLASS_KEY_INVALID");
		Objects.requireNonNull(source, "source");
		boolean[] activeSource = Objects.requireNonNull(activeSourceValues,
			"activeSourceValues").clone();
		double[] prices = Objects.requireNonNull(sourcePrices, "sourcePrices").clone();
		if(activeSource.length != source.domainSize())
			throw new IllegalArgumentException(
				"EXACT_ACTIVATION_CLASS_SOURCE_DOMAIN_MISMATCH");
		if(prices.length != source.domainSize())
			throw new IllegalArgumentException(
				"EXACT_ACTIVATION_CLASS_PRICE_DOMAIN_MISMATCH");
		for(double price : prices)
			if(!Double.isFinite(price) || price < 0d
				|| Double.doubleToRawLongBits(price) == Double.doubleToRawLongBits(-0d))
				throw new IllegalArgumentException("EXACT_ACTIVATION_CLASS_PRICE_INVALID");
		List<Demand> demands = List.copyOf(Objects.requireNonNull(inputDemands,
			"inputDemands"));

		List<ExactCategoricalSolver.Variable> scope = new ArrayList<>();
		scope.add(source);
		IdentityHashMap<ExactCategoricalSolver.Variable,boolean[]> mergedMasks =
			new IdentityHashMap<>();
		List<ExactCategoricalSolver.Variable> consumers = new ArrayList<>();
		for(Demand demand : demands) {
			Objects.requireNonNull(demand, "demand");
			ExactCategoricalSolver.Variable consumer = demand.consumer;
			if(consumer != source && consumer.equals(source))
				throw new IllegalArgumentException(
					"EXACT_ACTIVATION_CLASS_VARIABLE_IDENTITY_COLLISION");
			boolean[] merged = mergedMasks.get(consumer);
			if(merged == null) {
				if(consumer != source && scope.contains(consumer))
					throw new IllegalArgumentException(
						"EXACT_ACTIVATION_CLASS_VARIABLE_IDENTITY_COLLISION");
				merged = new boolean[consumer.domainSize()];
				mergedMasks.put(consumer, merged);
				consumers.add(consumer);
				if(consumer != source)
					scope.add(consumer);
			}
			for(int value = 0; value < merged.length; value++)
				merged[value] |= demand.activeConsumerValues[value];
		}
		scope = List.copyOf(scope);
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions = positions(scope);
		ExactCategoricalSolver.Factor canonical = ExactCategoricalSolver.Factor.lazy(scope, values -> {
			int sourceValue = values[positions.get(source)];
			if(!activeSource[sourceValue])
				return 0d;
			for(ExactCategoricalSolver.Variable consumer : consumers)
				if(mergedMasks.get(consumer)[values[positions.get(consumer)]])
					return prices[sourceValue];
			return 0d;
		});

		List<ExactCategoricalSolver.Variable> activeConsumers = consumers.stream()
			.filter(consumer -> anyTrue(mergedMasks.get(consumer))).toList();
		boolean identicallyZero = activeConsumers.isEmpty() || !hasActivePositivePrice(
			activeSource, prices);
		if(identicallyZero)
			return new Decomposition(canonical, List.of(), List.of(), descriptor(key, source,
				activeSource, prices, demands, consumers, mergedMasks, List.of(), List.of())
				+ "|identicallyZero=true");

		List<ExactCategoricalSolver.Variable> auxiliaries = new ArrayList<>();
		List<ExactCategoricalSolver.Factor> solverFactors = new ArrayList<>();
		ExactCategoricalSolver.Variable previous = null;
		for(int index = 0; index < activeConsumers.size(); index++) {
			ExactCategoricalSolver.Variable consumer = activeConsumers.get(index);
			boolean[] mask = mergedMasks.get(consumer);
			ExactCategoricalSolver.Variable accumulator = new ExactCategoricalSolver.Variable(
				key + "|active-after=" + index, 2);
			auxiliaries.add(accumulator);
			if(previous == null)
				solverFactors.add(ExactCategoricalSolver.Factor.lazy(
					List.of(consumer, accumulator), values ->
						values[1] == (mask[values[0]] ? 1 : 0) ? 0d
							: Double.POSITIVE_INFINITY));
			else {
				ExactCategoricalSolver.Variable prior = previous;
				solverFactors.add(ExactCategoricalSolver.Factor.lazy(
					List.of(consumer, prior, accumulator), values -> {
						int expected = values[1] != 0 || mask[values[0]] ? 1 : 0;
						return values[2] == expected ? 0d : Double.POSITIVE_INFINITY;
					}));
			}
			previous = accumulator;
		}
		ExactCategoricalSolver.Variable last = previous;
		solverFactors.add(ExactCategoricalSolver.Factor.lazy(List.of(source, last), values ->
			activeSource[values[0]] && values[1] != 0 ? prices[values[0]] : 0d));

		return new Decomposition(canonical, auxiliaries, solverFactors, descriptor(key, source,
			activeSource, prices, demands, consumers, mergedMasks, auxiliaries, solverFactors));
	}

	private static boolean anyTrue(boolean[] values) {
		for(boolean value : values)
			if(value)
				return true;
		return false;
	}

	private static boolean hasActivePositivePrice(boolean[] activeSource, double[] prices) {
		for(int value = 0; value < activeSource.length; value++)
			if(activeSource[value] && prices[value] > 0d)
				return true;
		return false;
	}

	private static String descriptor(String key, ExactCategoricalSolver.Variable source,
		boolean[] activeSource, double[] prices, List<Demand> demands,
		List<ExactCategoricalSolver.Variable> consumers,
		IdentityHashMap<ExactCategoricalSolver.Variable,boolean[]> mergedMasks,
		List<ExactCategoricalSolver.Variable> accumulators,
		List<ExactCategoricalSolver.Factor> solverFactors) {
		StringBuilder descriptor = new StringBuilder("EXACT_ACTIVATION_CLASS_OR_V1|key=")
			.append(key).append("|source=").append(source.key()).append(':')
			.append(source.domainSize()).append("|sourceActive=")
			.append(Arrays.toString(activeSource)).append("|sourcePrices=");
		for(double price : prices)
			descriptor.append(Long.toUnsignedString(Double.doubleToRawLongBits(price), 16))
				.append(',');
		for(int index = 0; index < demands.size(); index++) {
			Demand demand = demands.get(index);
			descriptor.append("|demand=").append(index).append(':')
				.append(demand.consumer.key()).append(':').append(demand.consumer.domainSize())
				.append(":active=").append(Arrays.toString(demand.activeConsumerValues));
		}
		for(ExactCategoricalSolver.Variable consumer : consumers)
			descriptor.append("|merged=").append(consumer.key()).append(':')
				.append(Arrays.toString(mergedMasks.get(consumer)));
		descriptor.append("|accumulators=").append(accumulators.stream()
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
}
