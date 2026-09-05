/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact solve-time reduction for the physical categorical model.
 *
 * <p>The reducer first removes values that lack finite support in a unary or
 * binary factor. It then quotients only original physical-decision variables,
 * and only when two values have byte-for-byte identical observations in every
 * incident frozen factor over the remaining active domains. Consequently each
 * reduced assignment has a representative full assignment with the same
 * objective, and every discarded full assignment has an objective-identical
 * representative. This is an exact quotient, not candidate pruning.</p>
 */
final class ExactPhysicalReducedSolver {
	private ExactPhysicalReducedSolver() { }

	static ExactCategoricalSolver.Result solve(int originalVariableCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits) {
		return solve(originalVariableCount, variables, factors, limits,
			(variable, value) -> 0L);
	}

	static ExactCategoricalSolver.Result solve(int originalVariableCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits,
		ExactCategoricalSolver.TieCostFunction tieCost) {
		return solve(originalVariableCount, variables, factors, limits, tieCost, false);
	}

	static ExactCategoricalSolver.Result solveWithConstantObservationHashForTesting(
		int originalVariableCount, List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, ExactCategoricalSolver.Limits limits) {
		return solve(originalVariableCount, variables, factors, limits,
			(variable, value) -> 0L, true);
	}

	private static ExactCategoricalSolver.Result solve(int originalVariableCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits,
		ExactCategoricalSolver.TieCostFunction tieCost, boolean constantObservationHash) {
		Objects.requireNonNull(tieCost, "tieCost");
		if(originalVariableCount < 0 || originalVariableCount > variables.size())
			throw new IllegalArgumentException("EXACT_PHYSICAL_REDUCED_PREFIX_INVALID");

		// Validates scopes, domains and both input budgets before evaluating a lazy
		// factor. Lazy factors are then evaluated exactly once for this solve.
		ExactCategoricalSolver.FrozenInputs frozen =
			ExactCategoricalSolver.freezeInputs(variables, factors, limits);
		int variableCount = variables.size();
		boolean[][] active = new boolean[variableCount][];
		long[][] tieCosts = new long[variableCount][];
		for(int variable = 0; variable < variableCount; variable++) {
			active[variable] = new boolean[frozen.domainSize(variable)];
			Arrays.fill(active[variable], true);
			tieCosts[variable] = new long[frozen.domainSize(variable)];
			for(int value = 0; value < tieCosts[variable].length; value++) {
				long cost = tieCost.cost(variables.get(variable), value);
				if(cost < 0)
					throw new IllegalArgumentException("EXACT_VE_TIE_COST_INVALID");
				tieCosts[variable][value] = cost;
			}
		}
		arcConsistency(frozen, active);

		List<List<Integer>> incident = incidentFactors(frozen, variableCount);
		int[][][] classValues = new int[variableCount][][];
		int[][] representatives = new int[variableCount][];
		List<ExactCategoricalSolver.Variable> reducedVariables =
			new ArrayList<>(variableCount);
		for(int variable = 0; variable < variableCount; variable++) {
			classValues[variable] = variable < originalVariableCount
				? quotientClasses(frozen, variable, active, incident.get(variable),
					tieCosts[variable], constantObservationHash)
				: singletonClasses(active[variable]);
			representatives[variable] = Arrays.stream(classValues[variable])
				.mapToInt(values -> values[0]).toArray();
			reducedVariables.add(new ExactCategoricalSolver.Variable(
				"exact-reduced|" + variable + '|' + variables.get(variable).key(),
				classValues[variable].length));
		}
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> reducedIndexes =
			new IdentityHashMap<>();
		for(int variable = 0; variable < variableCount; variable++)
			reducedIndexes.put(reducedVariables.get(variable), variable);

		List<ExactCategoricalSolver.Factor> reducedFactors = new ArrayList<>(frozen.factorCount());
		for(int factor = 0; factor < frozen.factorCount(); factor++) {
			int[] scope = frozen.scope(factor);
			List<ExactCategoricalSolver.Variable> reducedScope = Arrays.stream(scope)
				.mapToObj(reducedVariables::get).toList();
			double[] source = frozen.values(factor);
			int cells = reducedScope.stream().mapToInt(
				ExactCategoricalSolver.Variable::domainSize).reduce(1, Math::multiplyExact);
			double[] values = new double[cells];
			int[] reducedLocal = new int[scope.length];
			int[] originalLocal = new int[scope.length];
			for(int cell = 0; cell < cells; cell++) {
				decode(cell, reducedScope, reducedLocal);
				for(int position = 0; position < scope.length; position++)
					originalLocal[position] = representatives[scope[position]][reducedLocal[position]];
				values[cell] = source[encodeOriginal(originalLocal, scope, frozen)];
			}
			reducedFactors.add(ExactCategoricalSolver.Factor.dense(reducedScope, values));
		}

		ExactCategoricalSolver.Result reduced = ExactCategoricalSolver.solve(
			reducedVariables, reducedFactors, limits, (variable, reducedValue) -> {
				Integer original = reducedIndexes.get(variable);
				if(original == null)
					throw new IllegalArgumentException("EXACT_PHYSICAL_REDUCED_VARIABLE_UNKNOWN");
				return tieCosts[original][representatives[original][reducedValue]];
			});
		List<Integer> expanded = new ArrayList<>(variableCount);
		for(int variable = 0; variable < variableCount; variable++)
			expanded.add(representatives[variable][reduced.assignmentInVariableOrder().get(variable)]);
		return new ExactCategoricalSolver.Result(reduced.objective(), expanded, reduced.statistics());
	}

	private static void arcConsistency(ExactCategoricalSolver.FrozenInputs frozen,
		boolean[][] active) {
		boolean changed;
		do {
			changed = false;
			for(int factor = 0; factor < frozen.factorCount(); factor++) {
				int[] scope = frozen.scope(factor);
				double[] values = frozen.values(factor);
				if(scope.length == 1) {
					for(int value = 0; value < active[scope[0]].length; value++)
						if(active[scope[0]][value] && !Double.isFinite(values[value])) {
							active[scope[0]][value] = false;
							changed = true;
						}
				}
				else if(scope.length == 2) {
					for(int side = 0; side < 2; side++) {
						int other = 1 - side;
						for(int value = 0; value < active[scope[side]].length; value++) {
							if(!active[scope[side]][value])
								continue;
							boolean supported = false;
							for(int otherValue = 0; otherValue < active[scope[other]].length; otherValue++) {
								if(!active[scope[other]][otherValue])
									continue;
								int cell = side == 0
									? value * active[scope[1]].length + otherValue
									: otherValue * active[scope[1]].length + value;
								if(Double.isFinite(values[cell])) {
									supported = true;
									break;
								}
							}
							if(!supported) {
								active[scope[side]][value] = false;
								changed = true;
							}
						}
					}
				}
			}
			for(boolean[] domain : active)
				if(none(domain))
					throw new IllegalArgumentException("EXACT_VE_NO_FEASIBLE_ASSIGNMENT");
		}
		while(changed);
	}

	private static List<List<Integer>> incidentFactors(ExactCategoricalSolver.FrozenInputs frozen,
		int variableCount) {
		List<List<Integer>> incident = new ArrayList<>(variableCount);
		for(int variable = 0; variable < variableCount; variable++)
			incident.add(new ArrayList<>());
		for(int factor = 0; factor < frozen.factorCount(); factor++)
			for(int variable : frozen.scope(factor))
				incident.get(variable).add(factor);
		return incident;
	}

	private static int[][] quotientClasses(ExactCategoricalSolver.FrozenInputs frozen,
		int variable, boolean[][] active, List<Integer> incident, long[] tieCosts,
		boolean constantObservationHash) {
		Map<ObservationHash,List<List<Integer>>> buckets = new LinkedHashMap<>();
		for(int value = 0; value < active[variable].length; value++) {
			if(!active[variable][value])
				continue;
			ObservationHash hash = constantObservationHash ? new ObservationHash(0L, 0L)
				: observationHash(frozen, variable, value, active, incident, tieCosts[value]);
			List<List<Integer>> candidates = buckets.computeIfAbsent(hash,
				ignored -> new ArrayList<>());
			List<Integer> equivalent = null;
			for(List<Integer> candidate : candidates)
				if(observationsEqual(frozen, variable, candidate.get(0), value, active,
					incident, tieCosts)) {
					equivalent = candidate;
					break;
				}
			if(equivalent == null) {
				equivalent = new ArrayList<>();
				candidates.add(equivalent);
			}
			equivalent.add(value);
		}
		return buckets.values().stream().flatMap(List::stream)
			.map(values -> values.stream().mapToInt(Integer::intValue).toArray())
			.sorted((left, right) -> Integer.compare(left[0], right[0])).toArray(int[][]::new);
	}

	private static ObservationHash observationHash(ExactCategoricalSolver.FrozenInputs frozen,
		int variable, int value, boolean[][] active, List<Integer> incident, long tieCost) {
		long first = mix(0x9e3779b97f4a7c15L, tieCost);
		long second = mix(0xc2b2ae3d27d4eb4fL, tieCost);
		for(int factor : incident) {
			first = mix(first, factor);
			second = mix(second, ~factor);
			long[] state = {first, second};
			visitObservations(frozen, factor, variable, value, active, bits -> {
				state[0] = mix(state[0], bits);
				state[1] = mix(state[1], Long.rotateLeft(bits, 23));
			});
			first = state[0];
			second = state[1];
		}
		return new ObservationHash(first, second);
	}

	private static boolean observationsEqual(ExactCategoricalSolver.FrozenInputs frozen,
		int variable, int left, int right, boolean[][] active, List<Integer> incident,
		long[] tieCosts) {
		if(tieCosts[left] != tieCosts[right])
			return false;
		for(int factor : incident)
			if(!factorObservationsEqual(frozen, factor, variable, left, right, active))
				return false;
		return true;
	}

	private static boolean factorObservationsEqual(ExactCategoricalSolver.FrozenInputs frozen,
		int factor, int variable, int left, int right, boolean[][] active) {
		int[] scope = frozen.scope(factor);
		int variablePosition = 0;
		while(scope[variablePosition] != variable)
			variablePosition++;
		return factorObservationsEqual(frozen, scope, frozen.values(factor), variable,
			variablePosition, left, right, active, new int[scope.length], 0);
	}

	private static boolean factorObservationsEqual(ExactCategoricalSolver.FrozenInputs frozen,
		int[] scope, double[] values, int variable, int variablePosition, int left, int right,
		boolean[][] active, int[] local, int position) {
		if(position == scope.length) {
			local[variablePosition] = left;
			long leftBits = Double.doubleToRawLongBits(
				values[encodeOriginal(local, scope, frozen)]);
			local[variablePosition] = right;
			return leftBits == Double.doubleToRawLongBits(
				values[encodeOriginal(local, scope, frozen)]);
		}
		int scopedVariable = scope[position];
		if(scopedVariable == variable)
			return factorObservationsEqual(frozen, scope, values, variable, variablePosition,
				left, right, active, local, position + 1);
		for(int value = 0; value < active[scopedVariable].length; value++)
			if(active[scopedVariable][value]) {
				local[position] = value;
				if(!factorObservationsEqual(frozen, scope, values, variable, variablePosition,
					left, right, active, local, position + 1))
					return false;
			}
		return true;
	}

	private static void visitObservations(ExactCategoricalSolver.FrozenInputs frozen,
		int factor, int variable, int fixedValue, boolean[][] active, LongVisitor visitor) {
		int[] scope = frozen.scope(factor);
		double[] values = frozen.values(factor);
		int[] local = new int[scope.length];
		visitObservations(frozen, scope, values, variable, fixedValue, active, visitor, local, 0);
	}

	private static void visitObservations(ExactCategoricalSolver.FrozenInputs frozen,
		int[] scope, double[] values, int variable, int fixedValue, boolean[][] active,
		LongVisitor visitor, int[] local, int position) {
		if(position == scope.length) {
			visitor.accept(Double.doubleToRawLongBits(values[encodeOriginal(local, scope, frozen)]));
			return;
		}
		int scopedVariable = scope[position];
		if(scopedVariable == variable) {
			local[position] = fixedValue;
			visitObservations(frozen, scope, values, variable, fixedValue, active, visitor,
				local, position + 1);
			return;
		}
		for(int value = 0; value < active[scopedVariable].length; value++)
			if(active[scopedVariable][value]) {
				local[position] = value;
				visitObservations(frozen, scope, values, variable, fixedValue, active, visitor,
					local, position + 1);
			}
	}

	private static int[][] singletonClasses(boolean[] active) {
		List<int[]> values = new ArrayList<>();
		for(int value = 0; value < active.length; value++)
			if(active[value])
				values.add(new int[] {value});
		return values.toArray(int[][]::new);
	}

	private static int encodeOriginal(int[] local, int[] scope,
		ExactCategoricalSolver.FrozenInputs frozen) {
		int cell = 0;
		for(int position = 0; position < scope.length; position++)
			cell = Math.addExact(Math.multiplyExact(cell, frozen.domainSize(scope[position])),
				local[position]);
		return cell;
	}

	private static void decode(int cell, List<ExactCategoricalSolver.Variable> scope,
		int[] values) {
		for(int position = scope.size() - 1; position >= 0; position--) {
			values[position] = cell % scope.get(position).domainSize();
			cell /= scope.get(position).domainSize();
		}
	}

	private static boolean none(boolean[] values) {
		for(boolean value : values)
			if(value)
				return false;
		return true;
	}

	private static long mix(long hash, long value) {
		long mixed = value * 0x9e3779b97f4a7c15L;
		mixed ^= mixed >>> 29;
		return Long.rotateLeft(hash ^ mixed, 27) * 5 + 0x52dce729;
	}

	@FunctionalInterface
	private interface LongVisitor { void accept(long value); }
	private record ObservationHash(long first, long second) { }
}
