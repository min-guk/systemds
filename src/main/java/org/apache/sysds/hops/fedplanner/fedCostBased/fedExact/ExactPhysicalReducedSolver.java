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
	record PreparationStatistics(long freezeNanos, long supportNanos,
		long quotientNanos, long rebuildNanos, long compileNanos, long totalNanos) {
		PreparationStatistics {
			if(freezeNanos < 0 || supportNanos < 0 || quotientNanos < 0
				|| rebuildNanos < 0 || compileNanos < 0 || totalNanos < 0)
				throw new IllegalArgumentException("EXACT_PHYSICAL_PREPARATION_TIMING_INVALID");
		}
	}

	/**
	 * Immutable exact-reduced input model shared by exact and bounded-width solvers.
	 * This object contains no elimination plan and therefore performs no exact compile.
	 */
	static final class CompactModel {
		private final int originalDecisionCount;
		private final List<ExactCategoricalSolver.Variable> variables;
		private final List<ExactCategoricalSolver.Factor> factors;
		private final List<ExactCategoricalSolver.Variable> sourceVariables;
		private final int sourceVariableCount;
		private final int[][] representatives;
		private final int[][] sourceToReducedValue;
		private final int[] sourceToCompact;
		private final PreparationStatistics preparationStatistics;

		private CompactModel(int originalDecisionCount,
			List<ExactCategoricalSolver.Variable> variables,
			List<ExactCategoricalSolver.Factor> factors,
			List<ExactCategoricalSolver.Variable> sourceVariables,
			int sourceVariableCount, int[][] representatives, int[][] sourceToReducedValue,
			int[] sourceToCompact, PreparationStatistics preparationStatistics) {
			this.originalDecisionCount = originalDecisionCount;
			this.variables = List.copyOf(variables);
			this.factors = List.copyOf(factors);
			this.sourceVariables = List.copyOf(sourceVariables);
			this.sourceVariableCount = sourceVariableCount;
			this.representatives = Arrays.stream(representatives)
				.map(int[]::clone).toArray(int[][]::new);
			this.sourceToReducedValue = Arrays.stream(sourceToReducedValue)
				.map(int[]::clone).toArray(int[][]::new);
			this.sourceToCompact = sourceToCompact.clone();
			this.preparationStatistics = Objects.requireNonNull(
				preparationStatistics, "preparationStatistics");
		}

		int originalDecisionCount() { return originalDecisionCount; }
		List<ExactCategoricalSolver.Variable> variables() { return variables; }
		List<ExactCategoricalSolver.Factor> factors() { return factors; }
		List<ExactCategoricalSolver.Variable> sourceVariables() { return sourceVariables; }
		PreparationStatistics preparationStatistics() { return preparationStatistics; }

		int sourceValue(int sourceIndex, int reducedValue) {
			if(sourceIndex < 0 || sourceIndex >= sourceVariableCount)
				throw new IllegalArgumentException("EXACT_PHYSICAL_SOURCE_INDEX_INVALID");
			if(reducedValue < 0 || reducedValue >= representatives[sourceIndex].length)
				throw new IllegalArgumentException("EXACT_PHYSICAL_REDUCED_VALUE_INVALID");
			return representatives[sourceIndex][reducedValue];
		}

		int reducedValue(int sourceIndex, int sourceValue) {
			if(sourceIndex < 0 || sourceIndex >= sourceVariableCount)
				throw new IllegalArgumentException("EXACT_PHYSICAL_SOURCE_INDEX_INVALID");
			if(sourceValue < 0 || sourceValue >= sourceToReducedValue[sourceIndex].length)
				throw new IllegalArgumentException("EXACT_PHYSICAL_SOURCE_VALUE_INVALID");
			return sourceToReducedValue[sourceIndex][sourceValue];
		}

		List<Integer> expandAssignment(List<Integer> compactAssignment) {
			Objects.requireNonNull(compactAssignment, "compactAssignment");
			if(compactAssignment.size() != variables.size())
				throw new IllegalArgumentException(
					"EXACT_PHYSICAL_COMPACT_ASSIGNMENT_SIZE_MISMATCH");
			for(int compact = 0; compact < compactAssignment.size(); compact++) {
				Integer value = compactAssignment.get(compact);
				if(value == null || value < 0 || value >= variables.get(compact).domainSize())
					throw new IllegalArgumentException(
						"EXACT_PHYSICAL_COMPACT_ASSIGNMENT_VALUE_INVALID");
			}
			List<Integer> expanded = new ArrayList<>(sourceVariableCount);
			for(int source = 0; source < sourceVariableCount; source++) {
				int compact = sourceToCompact[source];
				int reducedValue = compact < 0 ? 0 : compactAssignment.get(compact);
				expanded.add(representatives[source][reducedValue]);
			}
			return List.copyOf(expanded);
		}
	}

	static final class Prepared {
		private final int variableCount;
		private final int[][] representatives;
		private final int[] reducedToCompiled;
		private final ExactCategoricalSolver.CompiledProblem compiled;
		private final ExactCategoricalSolver.Statistics statistics;
		private final PreparationStatistics preparationStatistics;
		private final ExactCategoricalSolver.OrderCompilation orderCompilation;

		private Prepared(int variableCount, int[][] representatives, int[] reducedToCompiled,
			ExactCategoricalSolver.CompiledProblem compiled,
			ExactCategoricalSolver.Statistics statistics,
			PreparationStatistics preparationStatistics,
			ExactCategoricalSolver.OrderCompilation orderCompilation) {
			this.variableCount = variableCount;
			this.representatives = representatives;
			this.reducedToCompiled = reducedToCompiled;
			this.compiled = compiled;
			this.statistics = Objects.requireNonNull(statistics, "statistics");
			this.preparationStatistics = Objects.requireNonNull(
				preparationStatistics, "preparationStatistics");
			this.orderCompilation = orderCompilation;
		}

		ExactCategoricalSolver.Statistics statistics() { return statistics; }
		PreparationStatistics preparationStatistics() { return preparationStatistics; }
		ExactCategoricalSolver.OrderCompilation orderCompilation() { return orderCompilation; }
		boolean infeasible() { return compiled == null; }
		int compiledVariableCount() {
			if(reducedToCompiled == null)
				return variableCount;
			int count = 0;
			for(int compiled : reducedToCompiled)
				if(compiled >= 0)
					count++;
			return count;
		}
	}

	private record Reduction(int variableCount, int[][] representatives,
		int[][] sourceToReducedValue,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.TieCostFunction tieCost) { }
	private record Compaction(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int[] reducedToCompiled) { }
	private static final class PreparationTimer {
		private final long startedNanos = System.nanoTime();
		private long freezeNanos;
		private long supportNanos;
		private long quotientNanos;
		private long rebuildNanos;

		private PreparationStatistics freeze(long compileNanos, long extraRebuildNanos) {
			return new PreparationStatistics(freezeNanos, supportNanos, quotientNanos,
				rebuildNanos + extraRebuildNanos, compileNanos, elapsedNanos(startedNanos));
		}
	}

	private ExactPhysicalReducedSolver() { }

	static ExactCategoricalSolver.Result solve(int originalVariableCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits) {
		return solve(prepare(originalVariableCount, variables, factors, limits));
	}

	static Prepared prepare(int originalVariableCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits) {
		return prepare(originalVariableCount, variables, factors, limits,
			ExactEliminationOrderPolicy.globalConfigured(), "exact-reduced");
	}

	static Prepared prepare(int originalVariableCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits,
		ExactEliminationOrderPolicy.Configuration orderPolicy) {
		return prepare(originalVariableCount, variables, factors, limits, orderPolicy,
			"exact-reduced");
	}

	static Prepared prepare(int originalVariableCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits,
		ExactEliminationOrderPolicy.Configuration orderPolicy, String caller) {
		PreparationTimer timer = new PreparationTimer();
		try {
			Reduction reduction = reduce(originalVariableCount, variables, factors, limits,
				(variable, value) -> 0L, false, timer);
			long compileStarted = System.nanoTime();
			ExactCategoricalSolver.OrderCompilation orderCompilation;
			long compileNanos;
			try {
				orderCompilation = ExactEliminationOrderPolicy.compile(
					reduction.variables(), reduction.factors(), limits, orderPolicy, caller);
			}
			finally {
				compileNanos = elapsedNanos(compileStarted);
			}
			ExactCategoricalSolver.CompiledProblem compiled = orderCompilation.compiled();
			return new Prepared(reduction.variableCount(), reduction.representatives(), null, compiled,
				ExactCategoricalSolver.statistics(compiled), timer.freeze(compileNanos, 0L),
				orderCompilation);
		}
		catch(IllegalArgumentException failure) {
			if(!"EXACT_VE_NO_FEASIBLE_ASSIGNMENT".equals(failure.getMessage()))
				throw failure;
			return new Prepared(variables.size(), null, null, null,
				new ExactCategoricalSolver.Statistics(List.of(), 0, 0L, 0L, 0L, 0L),
				timer.freeze(0L, 0L), null);
		}
	}

	/**
	 * Prepares the same exact quotient as {@link #prepare(int, List, List,
	 * ExactCategoricalSolver.Limits)}, then substitutes every singleton reduced
	 * variable before compiling the elimination problem.
	 */
	static Prepared prepareCompacted(int originalVariableCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits) {
		return prepareCompacted(originalVariableCount, variables, factors, limits,
			ExactEliminationOrderPolicy.globalConfigured(), "exact-reduced-compact");
	}

	static Prepared prepareCompacted(int originalVariableCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits,
		ExactEliminationOrderPolicy.Configuration orderPolicy) {
		return prepareCompacted(originalVariableCount, variables, factors, limits,
			orderPolicy, "exact-reduced-compact");
	}

	static Prepared prepareCompacted(int originalVariableCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits,
		ExactEliminationOrderPolicy.Configuration orderPolicy, String caller) {
		PreparationTimer timer = new PreparationTimer();
		try {
			Reduction reduction = reduce(originalVariableCount, variables, factors, limits,
				(variable, value) -> 0L, false, timer);
			long compactStarted = System.nanoTime();
			Compaction compact = compact(reduction);
			long compactNanos = elapsedNanos(compactStarted);
			long compileStarted = System.nanoTime();
			ExactCategoricalSolver.OrderCompilation orderCompilation;
			long compileNanos;
			try {
				orderCompilation = ExactEliminationOrderPolicy.compile(
					compact.variables(), compact.factors(), limits, orderPolicy, caller);
			}
			finally {
				compileNanos = elapsedNanos(compileStarted);
			}
			ExactCategoricalSolver.CompiledProblem compiled = orderCompilation.compiled();
			return new Prepared(reduction.variableCount(), reduction.representatives(),
				compact.reducedToCompiled(), compiled, ExactCategoricalSolver.statistics(compiled),
				timer.freeze(compileNanos, compactNanos), orderCompilation);
		}
		catch(IllegalArgumentException failure) {
			if(!"EXACT_VE_NO_FEASIBLE_ASSIGNMENT".equals(failure.getMessage()))
				throw failure;
			return new Prepared(variables.size(), null, null, null,
				new ExactCategoricalSolver.Statistics(List.of(), 0, 0L, 0L, 0L, 0L),
				timer.freeze(0L, 0L), null);
		}
	}

	static CompactModel compactModel(int originalDecisionCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits) {
		PreparationTimer timer = new PreparationTimer();
		Reduction reduction = reduce(originalDecisionCount, variables, factors, limits,
			(variable, value) -> 0L, false, timer);
		long compactStarted = System.nanoTime();
		Compaction compaction = compact(reduction);
		long compactNanos = elapsedNanos(compactStarted);
		List<ExactCategoricalSolver.Variable> compactSources = new ArrayList<>();
		for(int source = 0; source < reduction.variableCount(); source++)
			if(compaction.reducedToCompiled()[source] >= 0)
				compactSources.add(variables.get(source));
		return new CompactModel(originalDecisionCount, compaction.variables(),
			compaction.factors(), compactSources, reduction.variableCount(),
			reduction.representatives(), reduction.sourceToReducedValue(),
			compaction.reducedToCompiled(), timer.freeze(0L, compactNanos));
	}

	/** The same exact domain quotient as prepare(), retaining every singleton variable. */
	static CompactModel reducedModel(int originalDecisionCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits) {
		PreparationTimer timer = new PreparationTimer();
		Reduction reduction = reduce(originalDecisionCount, variables, factors, limits,
			(variable, value) -> 0L, false, timer);
		int[] identity = new int[reduction.variableCount()];
		for(int index = 0; index < identity.length; index++)
			identity[index] = index;
		return new CompactModel(originalDecisionCount, reduction.variables(), reduction.factors(),
			variables, reduction.variableCount(), reduction.representatives(),
			reduction.sourceToReducedValue(), identity, timer.freeze(0L, 0L));
	}

	static ExactCategoricalSolver.Result solveCompacted(int originalDecisionCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits) {
		return solve(prepareCompacted(originalDecisionCount, variables, factors, limits));
	}

	static ExactCategoricalSolver.Result solve(Prepared prepared) {
		Objects.requireNonNull(prepared, "prepared");
		if(prepared.infeasible())
			throw new IllegalArgumentException("EXACT_VE_NO_FEASIBLE_ASSIGNMENT");
		ExactCategoricalSolver.Result reduced = ExactCategoricalSolver.solve(prepared.compiled);
		return expand(reduced, prepared.variableCount, prepared.representatives,
			prepared.reducedToCompiled);
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
		Reduction reduction = reduce(originalVariableCount, variables, factors, limits,
			tieCost, constantObservationHash, new PreparationTimer());
		ExactCategoricalSolver.Result reduced = ExactCategoricalSolver.solve(
			reduction.variables(), reduction.factors(), limits, reduction.tieCost());
		return expand(reduced, reduction.variableCount(), reduction.representatives(), null);
	}

	private static Compaction compact(Reduction reduction) {
		int[] reducedToCompiled = new int[reduction.variableCount()];
		Arrays.fill(reducedToCompiled, -1);
		List<ExactCategoricalSolver.Variable> compactVariables = new ArrayList<>();
		for(int variable = 0; variable < reduction.variableCount(); variable++)
			if(reduction.variables().get(variable).domainSize() > 1) {
				reducedToCompiled[variable] = compactVariables.size();
				compactVariables.add(reduction.variables().get(variable));
			}

		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> reducedIndexes =
			new IdentityHashMap<>();
		for(int variable = 0; variable < reduction.variableCount(); variable++)
			reducedIndexes.put(reduction.variables().get(variable), variable);
		List<ExactCategoricalSolver.Factor> compactFactors =
			new ArrayList<>(reduction.factors().size());
		for(ExactCategoricalSolver.Factor factor : reduction.factors()) {
			List<ExactCategoricalSolver.Variable> compactScope = factor.scope().stream()
				.filter(variable -> reducedToCompiled[reducedIndexes.get(variable)] >= 0).toList();
			int cells = compactScope.stream().mapToInt(
				ExactCategoricalSolver.Variable::domainSize).reduce(1, Math::multiplyExact);
			double[] values = new double[cells];
			int[] compactLocal = new int[compactScope.size()];
			int[] reducedLocal = new int[factor.scope().size()];
			for(int cell = 0; cell < cells; cell++) {
				decode(cell, compactScope, compactLocal);
				int compactPosition = 0;
				for(int position = 0; position < factor.scope().size(); position++) {
					int reducedVariable = reducedIndexes.get(factor.scope().get(position));
					reducedLocal[position] = reducedToCompiled[reducedVariable] < 0
						? 0 : compactLocal[compactPosition++];
				}
				values[cell] = factor.cost(reducedLocal);
			}
			compactFactors.add(ExactCategoricalSolver.Factor.denseOwned(compactScope, values));
		}
		return new Compaction(List.copyOf(compactVariables), List.copyOf(compactFactors),
			reducedToCompiled);
	}

	private static Reduction reduce(int originalVariableCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits,
		ExactCategoricalSolver.TieCostFunction tieCost, boolean constantObservationHash,
		PreparationTimer timer) {
		Objects.requireNonNull(tieCost, "tieCost");
		Objects.requireNonNull(timer, "timer");
		if(originalVariableCount < 0 || originalVariableCount > variables.size())
			throw new IllegalArgumentException("EXACT_PHYSICAL_REDUCED_PREFIX_INVALID");

		// Validates scopes, domains and both input budgets before evaluating a lazy
		// factor. Lazy factors are then evaluated exactly once for this solve.
		long phaseStarted = System.nanoTime();
		ExactCategoricalSolver.FrozenInputs frozen;
		try {
			frozen = ExactCategoricalSolver.freezeInputs(variables, factors, limits);
		}
		finally {
			timer.freezeNanos = elapsedNanos(phaseStarted);
		}
		phaseStarted = System.nanoTime();
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
		try {
			arcConsistency(frozen, active);
		}
		finally {
			timer.supportNanos = elapsedNanos(phaseStarted);
		}

		phaseStarted = System.nanoTime();
		List<List<Integer>> incident = incidentFactors(frozen, variableCount);
		int[][][] classValues = new int[variableCount][][];
		int[][] representatives = new int[variableCount][];
		int[][] sourceToReducedValue = new int[variableCount][];
		List<ExactCategoricalSolver.Variable> reducedVariables =
			new ArrayList<>(variableCount);
		for(int variable = 0; variable < variableCount; variable++) {
			classValues[variable] = variable < originalVariableCount
				? quotientClasses(frozen, variable, active, incident.get(variable),
					tieCosts[variable], constantObservationHash)
				: singletonClasses(active[variable]);
			representatives[variable] = Arrays.stream(classValues[variable])
				.mapToInt(values -> values[0]).toArray();
			sourceToReducedValue[variable] = new int[frozen.domainSize(variable)];
			Arrays.fill(sourceToReducedValue[variable], -1);
			for(int reducedValue = 0; reducedValue < classValues[variable].length; reducedValue++)
				for(int sourceValue : classValues[variable][reducedValue])
					sourceToReducedValue[variable][sourceValue] = reducedValue;
			reducedVariables.add(new ExactCategoricalSolver.Variable(
				"exact-reduced|" + variable + '|' + variables.get(variable).key(),
					classValues[variable].length));
		}
		timer.quotientNanos = elapsedNanos(phaseStarted);
		phaseStarted = System.nanoTime();
		List<ExactCategoricalSolver.Factor> reducedFactors = new ArrayList<>(frozen.factorCount());
		for(int factor = 0; factor < frozen.factorCount(); factor++) {
			int[] scope = frozen.scope(factor);
			List<ExactCategoricalSolver.Variable> reducedScope = Arrays.stream(scope)
				.mapToObj(reducedVariables::get).toList();
			double[] source = frozen.values(factor);
			boolean identity = true;
			for(int variable : scope)
				if(!identityRepresentatives(representatives[variable],
					frozen.domainSize(variable))) {
					identity = false;
					break;
				}
			if(identity) {
				reducedFactors.add(ExactCategoricalSolver.Factor.denseOwned(reducedScope, source));
				continue;
			}
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
			reducedFactors.add(ExactCategoricalSolver.Factor.denseOwned(reducedScope, values));
		}

		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> reducedIndexes =
			new IdentityHashMap<>();
		for(int variable = 0; variable < variableCount; variable++)
			reducedIndexes.put(reducedVariables.get(variable), variable);
		ExactCategoricalSolver.TieCostFunction reducedTieCost = (variable, reducedValue) -> {
			Integer original = reducedIndexes.get(variable);
			if(original == null)
				throw new IllegalArgumentException("EXACT_PHYSICAL_REDUCED_VARIABLE_UNKNOWN");
			return tieCosts[original][representatives[original][reducedValue]];
		};
		timer.rebuildNanos = elapsedNanos(phaseStarted);
		return new Reduction(variableCount, representatives, sourceToReducedValue,
			List.copyOf(reducedVariables), List.copyOf(reducedFactors), reducedTieCost);
	}

	private static boolean identityRepresentatives(int[] representatives, int domainSize) {
		if(representatives.length != domainSize)
			return false;
		for(int value = 0; value < domainSize; value++)
			if(representatives[value] != value)
				return false;
		return true;
	}

	private static long elapsedNanos(long startedNanos) {
		return Math.max(0L, System.nanoTime() - startedNanos);
	}

	private static ExactCategoricalSolver.Result expand(ExactCategoricalSolver.Result reduced,
		int variableCount, int[][] representatives, int[] reducedToCompiled) {
		List<Integer> expanded = new ArrayList<>(variableCount);
		for(int variable = 0; variable < variableCount; variable++) {
			int compiledVariable = reducedToCompiled == null ? variable : reducedToCompiled[variable];
			int reducedValue = compiledVariable < 0 ? 0
				: reduced.assignmentInVariableOrder().get(compiledVariable);
			expanded.add(representatives[variable][reducedValue]);
		}
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
