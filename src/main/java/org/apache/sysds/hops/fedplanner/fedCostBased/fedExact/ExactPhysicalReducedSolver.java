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
		private final List<ExactCategoricalSolver.Variable> sourceVariables;
		private final List<ExactCategoricalSolver.Factor> sourceFactors;
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
			ExactCategoricalSolver.OrderCompilation orderCompilation,
			List<ExactCategoricalSolver.Variable> sourceVariables,
			List<ExactCategoricalSolver.Factor> sourceFactors) {
			this.sourceVariables = List.copyOf(sourceVariables);
			this.sourceFactors = List.copyOf(sourceFactors);
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
		ExactCategoricalSolver.CompiledProblem compiledProblem() {
			if(infeasible())
				throw new IllegalArgumentException("EXACT_VE_NO_FEASIBLE_ASSIGNMENT");
			return compiled;
		}
		void requireSource(List<ExactCategoricalSolver.Variable> variables,
			List<ExactCategoricalSolver.Factor> factors) {
			if(variables.size() != sourceVariables.size() || factors.size() != sourceFactors.size())
				throw new IllegalArgumentException("EXACT_DYADIC_REDUCTION_SOURCE_MISMATCH");
			for(int index = 0; index < variables.size(); index++)
				if(variables.get(index) != sourceVariables.get(index))
					throw new IllegalArgumentException("EXACT_DYADIC_REDUCTION_VARIABLE_MISMATCH");
			for(int index = 0; index < factors.size(); index++)
				if(factors.get(index) != sourceFactors.get(index))
					throw new IllegalArgumentException("EXACT_DYADIC_REDUCTION_FACTOR_MISMATCH");
		}
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
	private record EarlyReduction(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int[][] sourceValues) { }
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
	private static class SupportedValueEvaluator implements ExactCategoricalSolver.CostFunction {
		protected final ExactCategoricalSolver.Factor source;
		protected final int[] scope;
		protected final int[][] sourceValues;
		protected final int[] sourceLocal;

		private SupportedValueEvaluator(ExactCategoricalSolver.Factor source, int[] scope,
			int[][] sourceValues) {
			this.source = source;
			this.scope = scope.clone();
			this.sourceValues = sourceValues;
			sourceLocal = new int[scope.length];
		}

		@Override
		public synchronized double cost(int[] reducedLocal) {
			map(reducedLocal);
			return source.cost(sourceLocal);
		}

		protected void map(int[] reducedLocal) {
			for(int position = 0; position < scope.length; position++)
				sourceLocal[position] = reducedLocal[position] < 0 ? -1
					: sourceValues[scope[position]][reducedLocal[position]];
		}
	}

	private static final class PartialSupportedValueEvaluator extends SupportedValueEvaluator
		implements ExactCategoricalSolver.PartialHardCostFunction {
		private PartialSupportedValueEvaluator(ExactCategoricalSolver.Factor source, int[] scope,
			int[][] sourceValues) {
			super(source, scope, sourceValues);
		}

		@Override
		public synchronized ExactCategoricalSolver.PartialTruth partialTruth(int[] reducedLocal) {
			map(reducedLocal);
			return source.partialTruth(sourceLocal);
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
		return prepare(originalVariableCount, variables, factors, limits, orderPolicy, caller, false);
	}

	private static Prepared prepare(int originalVariableCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits,
		ExactEliminationOrderPolicy.Configuration orderPolicy, String caller, boolean dyadicStorage) {
		PreparationTimer timer = new PreparationTimer();
		try {
			Reduction reduction = reduce(originalVariableCount, variables, factors, limits,
				(variable, value) -> 0L, false, timer);
			long compileStarted = System.nanoTime();
			ExactCategoricalSolver.OrderCompilation orderCompilation;
			long compileNanos;
			try {
				orderCompilation = dyadicStorage ? ExactEliminationOrderPolicy.compileDyadic(
					reduction.variables(), reduction.factors(), limits, orderPolicy, caller)
					: ExactEliminationOrderPolicy.compile(
						reduction.variables(), reduction.factors(), limits, orderPolicy, caller);
			}
			finally {
				compileNanos = elapsedNanos(compileStarted);
			}
			ExactCategoricalSolver.CompiledProblem compiled = orderCompilation.compiled();
			return new Prepared(reduction.variableCount(), reduction.representatives(), null, compiled,
				ExactCategoricalSolver.statistics(compiled), timer.freeze(compileNanos, 0L),
				orderCompilation, variables, factors);
		}
		catch(IllegalArgumentException failure) {
			if(!"EXACT_VE_NO_FEASIBLE_ASSIGNMENT".equals(failure.getMessage()))
				throw failure;
			return new Prepared(variables.size(), null, null, null,
				new ExactCategoricalSolver.Statistics(List.of(), 0, 0L, 0L, 0L, 0L),
				timer.freeze(0L, 0L), null, variables, factors);
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
		return prepareCompacted(originalVariableCount, variables, factors, limits, orderPolicy, caller, false);
	}

	/** Defers message-cell budgeting only for an owner-certified exact physical encoding. */
	static Prepared prepareSharedSource(ExactPhysicalSharedSourceEncoding.Encoding encoding,
		ExactDyadicCosts.Certificate certificate, ExactCategoricalSolver.Limits limits,
		ExactEliminationOrderPolicy.Configuration orderPolicy, String caller, boolean compact) {
		Objects.requireNonNull(encoding, "encoding");
		Objects.requireNonNull(certificate, "certificate");
		if(!certificate.supported())
			throw new IllegalArgumentException("EXACT_SHARED_SOURCE_NUMERIC_UNSUPPORTED|" + certificate.reason());
		certificate.validateSurface(encoding.sourceSurface());
		certificate.validateSourceFactors(encoding.sourceSurface().exactSolverFactors());
		if(!encoding.statistics().transformed())
			throw new IllegalArgumentException("EXACT_DYADIC_ENCODING_NOT_TRANSFORMED");
		return compact ? prepareCompacted(encoding.decisionPrefixCount(), encoding.variables(),
			encoding.factors(), limits, orderPolicy, caller, true)
			: prepare(encoding.decisionPrefixCount(), encoding.variables(), encoding.factors(),
				limits, orderPolicy, caller, true);
	}

	private static Prepared prepareCompacted(int originalVariableCount,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits,
		ExactEliminationOrderPolicy.Configuration orderPolicy, String caller, boolean dyadicStorage) {
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
				orderCompilation = dyadicStorage ? ExactEliminationOrderPolicy.compileDyadic(
					compact.variables(), compact.factors(), limits, orderPolicy, caller)
					: ExactEliminationOrderPolicy.compile(
						compact.variables(), compact.factors(), limits, orderPolicy, caller);
			}
			finally {
				compileNanos = elapsedNanos(compileStarted);
			}
			ExactCategoricalSolver.CompiledProblem compiled = orderCompilation.compiled();
			return new Prepared(reduction.variableCount(), reduction.representatives(),
				compact.reducedToCompiled(), compiled, ExactCategoricalSolver.statistics(compiled),
				timer.freeze(compileNanos, compactNanos), orderCompilation, variables, factors);
		}
		catch(IllegalArgumentException failure) {
			if(!"EXACT_VE_NO_FEASIBLE_ASSIGNMENT".equals(failure.getMessage()))
				throw failure;
			return new Prepared(variables.size(), null, null, null,
				new ExactCategoricalSolver.Statistics(List.of(), 0, 0L, 0L, 0L, 0L),
				timer.freeze(0L, 0L), null, variables, factors);
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

	static ExactCategoricalSolver.Result solveDyadic(Prepared prepared,
		ExactDyadicCosts.Certificate certificate) {
		Objects.requireNonNull(prepared, "prepared");
		if(prepared.infeasible())
			throw new IllegalArgumentException("EXACT_VE_NO_FEASIBLE_ASSIGNMENT");
		ExactCategoricalSolver.Result reduced = ExactCategoricalSolver.solveDyadic(
			prepared.compiled, certificate);
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
			// Removed axes have domain one. Last-axis-fastest cell order and count are
			// therefore unchanged, so both dense and packed hard storage can be rebound.
			compactFactors.add(factor.rebindOwned(compactScope));
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
		Objects.requireNonNull(limits, "limits");
		if(originalVariableCount < 0 || originalVariableCount > variables.size())
			throw new IllegalArgumentException("EXACT_PHYSICAL_REDUCED_PREFIX_INVALID");

		// Validate the entire raw model, then materialize only unary/binary support.
		// This permits hard support to shrink a high-order lazy Cartesian product
		// before the ordinary bounded full freeze.
		long phaseStarted = System.nanoTime();
		ExactCategoricalSolver.validateReductionInputStructure(variables, factors, limits);
		int variableCount = variables.size();
		long[][] sourceTieCosts = originalTieCosts(variables, tieCost);
		List<ExactCategoricalSolver.Factor> unaryFactors = factors.stream()
			.filter(factor -> factor.scope().size() <= 1).toList();
		ExactCategoricalSolver.FrozenInputs unary =
			ExactCategoricalSolver.freezeInputs(variables, unaryFactors, limits);
		timer.freezeNanos = elapsedNanos(phaseStarted);
		phaseStarted = System.nanoTime();
		boolean[][] unaryActive = fullDomains(variables);
		arcConsistency(unary, unaryActive);
		timer.supportNanos = elapsedNanos(phaseStarted);
		phaseStarted = System.nanoTime();
		EarlyReduction unaryReduced = restrictToSupportedValues(
			variables, factors, unaryFactors, unary, unaryActive, false);
		timer.rebuildNanos = elapsedNanos(phaseStarted);

		phaseStarted = System.nanoTime();
		List<ExactCategoricalSolver.Factor> binaryFactors = unaryReduced.factors().stream()
			.filter(factor -> factor.scope().size() <= 2).toList();
		ExactCategoricalSolver.FrozenInputs binary = freezeNonPartialBinaryInputs(
			unaryReduced.variables(), binaryFactors, limits);
		timer.freezeNanos += elapsedNanos(phaseStarted);
		phaseStarted = System.nanoTime();
		boolean[][] binaryActive = fullDomains(unaryReduced.variables());
		arcConsistency(unaryReduced.variables(), binaryFactors, binary, binaryActive);
		timer.supportNanos += elapsedNanos(phaseStarted);
		phaseStarted = System.nanoTime();
		EarlyReduction binaryReduced = restrictToSupportedValues(unaryReduced.variables(),
			unaryReduced.factors(), binaryFactors, binary, binaryActive, true);
		EarlyReduction early = composeSourceValues(unaryReduced, binaryReduced);
		timer.rebuildNanos += elapsedNanos(phaseStarted);

		phaseStarted = System.nanoTime();
		ExactCategoricalSolver.FrozenInputs frozen;
		try {
			frozen = ExactCategoricalSolver.freezeInputs(early.variables(), early.factors(), limits);
		}
		finally {
			timer.freezeNanos += elapsedNanos(phaseStarted);
		}
		phaseStarted = System.nanoTime();
		boolean[][] active = new boolean[variableCount][];
		long[][] tieCosts = new long[variableCount][];
		for(int variable = 0; variable < variableCount; variable++) {
			active[variable] = new boolean[frozen.domainSize(variable)];
			Arrays.fill(active[variable], true);
			tieCosts[variable] = new long[frozen.domainSize(variable)];
			for(int value = 0; value < tieCosts[variable].length; value++)
				tieCosts[variable][value] =
					sourceTieCosts[variable][early.sourceValues()[variable][value]];
		}
		try {
			arcConsistency(frozen, active);
		}
		finally {
			timer.supportNanos += elapsedNanos(phaseStarted);
		}

		phaseStarted = System.nanoTime();
		List<List<Integer>> incident = incidentFactors(frozen, variableCount);
		ObservationHashes observationHashes = constantObservationHash ? null
			: compileObservationHashes(frozen, active, tieCosts, originalVariableCount, null);
		int[][][] classValues = new int[variableCount][][];
		int[][] frozenRepresentatives = new int[variableCount][];
		int[][] representatives = new int[variableCount][];
		int[][] sourceToReducedValue = new int[variableCount][];
		List<ExactCategoricalSolver.Variable> reducedVariables =
			new ArrayList<>(variableCount);
		for(int variable = 0; variable < variableCount; variable++) {
			classValues[variable] = variable < originalVariableCount
				? quotientClasses(frozen, variable, active, incident.get(variable),
					tieCosts[variable], observationHashes, constantObservationHash)
				: singletonClasses(active[variable]);
			frozenRepresentatives[variable] = Arrays.stream(classValues[variable])
				.mapToInt(values -> values[0]).toArray();
			int[] supportedSourceValues = early.sourceValues()[variable];
			representatives[variable] = Arrays.stream(frozenRepresentatives[variable])
				.map(value -> supportedSourceValues[value]).toArray();
			sourceToReducedValue[variable] = new int[variables.get(variable).domainSize()];
			Arrays.fill(sourceToReducedValue[variable], -1);
			for(int reducedValue = 0; reducedValue < classValues[variable].length; reducedValue++)
				for(int frozenValue : classValues[variable][reducedValue])
					sourceToReducedValue[variable][early.sourceValues()[variable][frozenValue]] = reducedValue;
			reducedVariables.add(new ExactCategoricalSolver.Variable(
				"exact-reduced|" + variable + '|' + variables.get(variable).key(),
					classValues[variable].length));
		}
		observationHashes = null;
		timer.quotientNanos = elapsedNanos(phaseStarted);
		phaseStarted = System.nanoTime();
		List<ExactCategoricalSolver.Factor> reducedFactors = new ArrayList<>(frozen.factorCount());
		for(int factor = 0; factor < frozen.factorCount(); factor++) {
			int[] scope = frozen.scope(factor);
			List<ExactCategoricalSolver.Variable> reducedScope = Arrays.stream(scope)
				.mapToObj(reducedVariables::get).toList();
			// All observations are complete. Release each old table as its replacement
			// is built instead of retaining two complete copies of the factor model.
			ExactCategoricalSolver.Factor source = frozen.takeFactor(factor);
			boolean identity = true;
			for(int variable : scope)
				if(!identityRepresentatives(frozenRepresentatives[variable],
					frozen.domainSize(variable))) {
					identity = false;
					break;
				}
			if(identity) {
				reducedFactors.add(source.rebindOwned(reducedScope));
				continue;
			}
			reducedFactors.add(projectFrozenFactor(source, reducedScope, scope, frozen, frozenRepresentatives));
		}

		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> reducedIndexes =
			new IdentityHashMap<>();
		for(int variable = 0; variable < variableCount; variable++)
			reducedIndexes.put(reducedVariables.get(variable), variable);
		ExactCategoricalSolver.TieCostFunction reducedTieCost = (variable, reducedValue) -> {
			Integer original = reducedIndexes.get(variable);
			if(original == null)
				throw new IllegalArgumentException("EXACT_PHYSICAL_REDUCED_VARIABLE_UNKNOWN");
			return tieCosts[original][frozenRepresentatives[original][reducedValue]];
		};
		timer.rebuildNanos += elapsedNanos(phaseStarted);
		return new Reduction(variableCount, representatives, sourceToReducedValue,
			List.copyOf(reducedVariables), List.copyOf(reducedFactors), reducedTieCost);
	}

	private static long[][] originalTieCosts(
		List<ExactCategoricalSolver.Variable> variables,
		ExactCategoricalSolver.TieCostFunction tieCost) {
		long[][] tieCosts = new long[variables.size()][];
		for(int variable = 0; variable < variables.size(); variable++) {
			tieCosts[variable] = new long[variables.get(variable).domainSize()];
			for(int value = 0; value < tieCosts[variable].length; value++) {
				long cost = tieCost.cost(variables.get(variable), value);
				if(cost < 0)
					throw new IllegalArgumentException("EXACT_VE_TIE_COST_INVALID");
				tieCosts[variable][value] = cost;
			}
		}
		return tieCosts;
	}

	private static boolean[][] fullDomains(List<ExactCategoricalSolver.Variable> variables) {
		boolean[][] active = new boolean[variables.size()][];
		for(int variable = 0; variable < variables.size(); variable++) {
			active[variable] = new boolean[variables.get(variable).domainSize()];
			Arrays.fill(active[variable], true);
		}
		return active;
	}

	private static ExactCategoricalSolver.FrozenInputs freezeNonPartialBinaryInputs(
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.Limits limits) {
		// Preserve the full binary batch's structural and logical-cell validation before
		// invoking any lazy evaluator. Partial-hard factors are then validated in the
		// same factor order without retaining their Cartesian products.
		ExactCategoricalSolver.validateInputStructure(variables,factors,limits);
		List<ExactCategoricalSolver.Factor> materialized = new ArrayList<>();
		for(ExactCategoricalSolver.Factor factor : factors) {
			if(factor.supportsPartialTruth())
				validatePartialHardFactor(factor);
			else
				materialized.add(ExactCategoricalSolver.freezeValidatedFactor(factor));
		}
		return ExactCategoricalSolver.freezeInputs(variables,materialized,limits);
	}

	private static void validatePartialHardFactor(ExactCategoricalSolver.Factor factor) {
		int[] local = new int[factor.scope().size()];
		Arrays.fill(local,-1);
		validatePartialHardFactor(factor,local,0);
	}

	private static void validatePartialHardFactor(ExactCategoricalSolver.Factor factor,
		int[] local, int depth) {
		ExactCategoricalSolver.PartialTruth truth = factor.partialTruth(local);
		if(truth != ExactCategoricalSolver.PartialTruth.UNKNOWN)
			return;
		if(depth == local.length) {
			validateDeferredCost(factor.cost(local));
			return;
		}
		for(int value=0; value<factor.scope().get(depth).domainSize(); value++) {
			local[depth] = value;
			validatePartialHardFactor(factor,local,depth+1);
		}
		local[depth] = -1;
	}

	private static void validateDeferredCost(double value) {
		if(Double.isNaN(value) || value == Double.NEGATIVE_INFINITY
			|| Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(-0.0d))
			throw new IllegalArgumentException("EXACT_VE_FACTOR_COST_INVALID|value=" + value);
	}

	private static EarlyReduction composeSourceValues(EarlyReduction source,
		EarlyReduction reduced) {
		int[][] composed = new int[source.sourceValues().length][];
		for(int variable = 0; variable < composed.length; variable++) {
			int[] sourceValues = source.sourceValues()[variable];
			composed[variable] = Arrays.stream(reduced.sourceValues()[variable])
				.map(value -> sourceValues[value]).toArray();
		}
		return new EarlyReduction(reduced.variables(), reduced.factors(), composed);
	}

	private static EarlyReduction restrictToSupportedValues(
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		List<ExactCategoricalSolver.Factor> supportFactors,
		ExactCategoricalSolver.FrozenInputs support, boolean[][] active,
		boolean deferredPartialHard) {
		int[][] sourceValues = new int[variables.size()][];
		List<ExactCategoricalSolver.Variable> reducedVariables = new ArrayList<>(variables.size());
		Map<ExactCategoricalSolver.Variable,Integer> sourceIndexes = new LinkedHashMap<>();
		for(int variable = 0; variable < variables.size(); variable++) {
			sourceIndexes.put(variables.get(variable), variable);
			sourceValues[variable] = activeValues(active[variable]);
			reducedVariables.add(identityRepresentatives(sourceValues[variable],
				variables.get(variable).domainSize()) ? variables.get(variable)
				: new ExactCategoricalSolver.Variable(
					"exact-supported|" + variable + '|' + variables.get(variable).key(),
					sourceValues[variable].length));
		}
		List<ExactCategoricalSolver.Factor> reducedFactors = new ArrayList<>(factors.size());
		int supportOrdinal = 0;
		int materializedOrdinal = 0;
		for(ExactCategoricalSolver.Factor factor : factors) {
			int[] scope = factor.scope().stream().mapToInt(sourceIndexes::get).toArray();
			List<ExactCategoricalSolver.Variable> reducedScope = Arrays.stream(scope)
				.mapToObj(reducedVariables::get).toList();
			Integer supportIndex = supportOrdinal < supportFactors.size()
				&& factor == supportFactors.get(supportOrdinal) ? supportOrdinal++ : null;
			if(supportIndex != null && !(deferredPartialHard && factor.supportsPartialTruth())) {
				int frozenIndex = materializedOrdinal++;
				ExactCategoricalSolver.Factor source = support.takeFactor(frozenIndex);
				boolean identity = true;
				for(int variable : scope)
					if(!identityRepresentatives(sourceValues[variable],
						support.domainSize(variable))) {
						identity = false;
						break;
					}
				if(identity) {
					reducedFactors.add(source.rebindOwned(reducedScope));
					continue;
				}
				reducedFactors.add(projectFrozenFactor(source, reducedScope, scope, support, sourceValues));
			}
			else {
				boolean identity = true;
				for(int variable : scope)
					if(!identityRepresentatives(sourceValues[variable],
						variables.get(variable).domainSize())) {
						identity = false;
						break;
					}
				if(identity) {
					reducedFactors.add(factor);
					continue;
				}
				ExactCategoricalSolver.Factor functional = scope.length == 2
					? factor.projectFunctionalMap(reducedScope, sourceValues[scope[0]], sourceValues[scope[1]])
					: null;
				if(functional != null) {
					reducedFactors.add(functional);
					continue;
				}
				ExactCategoricalSolver.CostFunction evaluator = factor.supportsPartialTruth()
					? new PartialSupportedValueEvaluator(factor, scope, sourceValues)
					: new SupportedValueEvaluator(factor, scope, sourceValues);
				reducedFactors.add(ExactCategoricalSolver.Factor.lazy(reducedScope, evaluator));
			}
		}
		if(supportOrdinal != supportFactors.size())
			throw new IllegalArgumentException("EXACT_PHYSICAL_REDUCED_SUPPORT_ORDER_INVALID");
		if(materializedOrdinal != support.factorCount())
			throw new IllegalArgumentException("EXACT_PHYSICAL_REDUCED_SUPPORT_ORDER_INVALID");
		return new EarlyReduction(List.copyOf(reducedVariables),
			List.copyOf(reducedFactors), sourceValues);
	}

	/** Copy a frozen factor in target row-major order, advancing its source offset without division. */
	private static ExactCategoricalSolver.Factor projectFrozenFactor(
		ExactCategoricalSolver.Factor source, List<ExactCategoricalSolver.Variable> reducedScope,
		int[] scope, ExactCategoricalSolver.FrozenInputs frozen, int[][] representatives) {
		ExactCategoricalSolver.Factor functional = scope.length == 2
			? source.projectFunctionalMap(reducedScope, representatives[scope[0]], representatives[scope[1]])
			: null;
		if(functional != null)
			return functional;
		if(source.isFiniteSupport()) {
			int[] sourceCells = source.finiteSupportCells();
			int[] projected = new int[sourceCells.length];
			int projectedCount = 0;
			int[] coordinates = new int[scope.length];
			for(int sourceCell : sourceCells) {
				int remaining = sourceCell;
				boolean retained = true;
				for(int axis = scope.length - 1; axis >= 0; axis--) {
					int domain = frozen.domainSize(scope[axis]);
					int sourceValue = remaining % domain;
					remaining /= domain;
					coordinates[axis] = Arrays.binarySearch(
						representatives[scope[axis]], sourceValue);
					retained &= coordinates[axis] >= 0;
				}
				if(retained) {
					int reducedCell = 0;
					for(int axis = 0; axis < scope.length; axis++)
						reducedCell = reducedCell * reducedScope.get(axis).domainSize()
							+ coordinates[axis];
					projected[projectedCount++] = reducedCell;
				}
			}
			return ExactCategoricalSolver.Factor.finiteSupport(reducedScope,
				Arrays.copyOf(projected, projectedCount));
		}
		int cells = reducedScope.stream().mapToInt(
			ExactCategoricalSolver.Variable::domainSize).reduce(1, Math::multiplyExact);
		ExactCategoricalSolver.HardTable hard = source.isHardTable()
			? ExactCategoricalSolver.HardTable.allocate(cells) : null;
		if(hard == null)
			PlannerResourceGuard.checkAdditionalCells(cells, "exact-reduced-factor");
		double[] values = hard == null
			? PlannerResourceGuard.allocateDoubles(cells, "exact-numeric") : null;
		int[] coordinates = new int[scope.length];
		int[] strides = new int[scope.length];
		int sourceCell = 0;
		for(int axis = scope.length - 1, stride = 1; axis >= 0; axis--) {
			strides[axis] = stride;
			sourceCell += representatives[scope[axis]][0] * stride;
			stride *= frozen.domainSize(scope[axis]);
		}
		for(int cell = 0; cell < cells; cell++) {
			double value = source.denseCostAt(sourceCell);
			if(hard != null) {
				if(value == Double.POSITIVE_INFINITY)
					hard.forbid(cell);
			}
			else
				values[cell] = value;
			for(int axis = scope.length - 1; axis >= 0; axis--) {
				int[] mapped = representatives[scope[axis]];
				int previous = coordinates[axis];
				int next = previous + 1;
				if(next < mapped.length) {
					sourceCell += (mapped[next] - mapped[previous]) * strides[axis];
					coordinates[axis] = next;
					break;
				}
				sourceCell -= (mapped[previous] - mapped[0]) * strides[axis];
				coordinates[axis] = 0;
			}
		}
		return hard == null ? ExactCategoricalSolver.Factor.denseOwned(reducedScope, values)
			: ExactCategoricalSolver.Factor.hardOwned(reducedScope, hard.compactAllFeasible());
	}

	private static int[] activeValues(boolean[] active) {
		int count = 0;
		for(boolean value : active)
			if(value)
				count++;
		int[] values = new int[count];
		for(int source = 0, target = 0; source < active.length; source++)
			if(active[source])
				values[target++] = source;
		return values;
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

	/** Per-call epochs track removals; each frozen factor occurrence retains its pre-visit epochs. */
	private static final class FrozenSupportEpochs {
		private final int[] removals;
		private final int[][] seen;

		private FrozenSupportEpochs(int variables, int factors) {
			removals = new int[variables];
			seen = new int[factors][];
		}

		private boolean needsVisit(int factor, int[] scope) {
			int[] previous = seen[factor];
			boolean changed = previous == null;
			if(previous == null)
				previous = seen[factor] = new int[scope.length];
			for(int axis = 0; axis < scope.length; axis++) {
				changed |= previous[axis] != removals[scope[axis]];
				previous[axis] = removals[scope[axis]];
			}
			return changed;
		}
	}

	private static void arcConsistency(ExactCategoricalSolver.FrozenInputs frozen,
		boolean[][] active) {
		FrozenSupportEpochs epochs = new FrozenSupportEpochs(active.length, frozen.factorCount());
		boolean changed;
		do {
			changed = false;
			for(int factor=0; factor<frozen.factorCount(); factor++)
				if(epochs.needsVisit(factor, frozen.scope(factor)))
					changed |= reviseFrozenSupport(frozen,factor,active,epochs.removals);
			for(boolean[] domain : active)
				if(none(domain))
					throw new IllegalArgumentException("EXACT_VE_NO_FEASIBLE_ASSIGNMENT");
		}
		while(changed);
	}

	private static void arcConsistency(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.FrozenInputs materialized, boolean[][] active) {
		Map<ExactCategoricalSolver.Variable,Integer> indexes = new LinkedHashMap<>();
		for(int variable=0; variable<variables.size(); variable++)
			indexes.put(variables.get(variable),variable);
		FrozenSupportEpochs epochs = new FrozenSupportEpochs(active.length, materialized.factorCount());
		boolean changed;
		do {
			changed = false;
			int materializedOrdinal = 0;
			for(ExactCategoricalSolver.Factor factor : factors) {
				if(factor.supportsPartialTruth()) {
					int[] scope = factor.scope().stream().mapToInt(indexes::get).toArray();
					changed |= revisePartialHardFactor(factor,scope,active,epochs.removals);
				}
				else {
					int ordinal = materializedOrdinal++;
					if(epochs.needsVisit(ordinal, materialized.scope(ordinal)))
						changed |= reviseFrozenSupport(materialized,ordinal,active,epochs.removals);
				}
			}
			if(materializedOrdinal != materialized.factorCount())
				throw new IllegalArgumentException("EXACT_PHYSICAL_REDUCED_SUPPORT_ORDER_INVALID");
			for(boolean[] domain : active)
				if(none(domain))
					throw new IllegalArgumentException("EXACT_VE_NO_FEASIBLE_ASSIGNMENT");
		}
		while(changed);
	}

	private static boolean reviseFrozenSupport(ExactCategoricalSolver.FrozenInputs frozen,
		int factor, boolean[][] active) {
		return reviseFrozenSupport(frozen, factor, active, null);
	}

	private static boolean reviseFrozenSupport(ExactCategoricalSolver.FrozenInputs frozen,
		int factor, boolean[][] active, int[] removals) {
		int[] scope = frozen.scope(factor);
		ExactCategoricalSolver.FunctionalMap mapping = frozen.factor(factor).functionalMapping();
		if(mapping != null)
			return reviseFunctionalSupport(mapping, scope, active, removals);
		ExactCategoricalSolver.Factor frozenFactor = frozen.factor(factor);
		if(frozenFactor.isFiniteSupport() && scope.length <= 2)
			return reviseFiniteSupport(frozenFactor.finiteSupportCells(), scope, active, removals);
		boolean changed = false;
		if(scope.length == 1) {
			for(int value=0; value<active[scope[0]].length; value++)
				if(active[scope[0]][value] && !Double.isFinite(frozen.costAt(factor,value))) {
					active[scope[0]][value] = false;
					if(removals != null)
						removals[scope[0]]++;
					changed = true;
				}
		}
		else if(scope.length == 2) {
			for(int side=0; side<2; side++) {
				int other = 1-side;
				for(int value=0; value<active[scope[side]].length; value++) {
					if(!active[scope[side]][value])
						continue;
					boolean supported = false;
					for(int otherValue=0; otherValue<active[scope[other]].length; otherValue++) {
						if(!active[scope[other]][otherValue])
							continue;
						int cell = side == 0
							? value * active[scope[1]].length + otherValue
							: otherValue * active[scope[1]].length + value;
						if(Double.isFinite(frozen.costAt(factor,cell))) {
							supported = true;
							break;
						}
					}
					if(!supported) {
						active[scope[side]][value] = false;
						if(removals != null)
							removals[scope[side]]++;
						changed = true;
					}
				}
			}
		}
		return changed;
	}

	private static boolean reviseFiniteSupport(int[] cells, int[] scope,
		boolean[][] active, int[] removals) {
		boolean[][] supported = new boolean[scope.length][];
		for(int axis = 0; axis < scope.length; axis++)
			supported[axis] = new boolean[active[scope[axis]].length];
		int[] coordinates = new int[scope.length];
		for(int cell : cells) {
			int remaining = cell;
			boolean activeCell = true;
			for(int axis = scope.length - 1; axis >= 0; axis--) {
				int domain = active[scope[axis]].length;
				coordinates[axis] = remaining % domain;
				remaining /= domain;
				activeCell &= active[scope[axis]][coordinates[axis]];
			}
			if(activeCell)
				for(int axis = 0; axis < scope.length; axis++)
					supported[axis][coordinates[axis]] = true;
		}
		boolean changed = false;
		for(int axis = 0; axis < scope.length; axis++)
			for(int value = 0; value < active[scope[axis]].length; value++)
				if(active[scope[axis]][value] && !supported[axis][value]) {
					active[scope[axis]][value] = false;
					if(removals != null)
						removals[scope[axis]]++;
					changed = true;
				}
		return changed;
	}

	/** The same row-first, column-second binary revision, over the complete finite relation. */
	private static boolean reviseFunctionalSupport(ExactCategoricalSolver.FunctionalMap mapping,
		int[] scope, boolean[][] active, int[] removals) {
		boolean[] rows = active[scope[0]], columns = active[scope[1]];
		boolean[] reached = new boolean[columns.length];
		boolean changed = false;
		for(int row = 0; row < rows.length; row++) {
			if(!rows[row])
				continue;
			int target = mapping.target(row);
			if(target >= 0 && columns[target])
				reached[target] = true;
			else {
				rows[row] = false;
				if(removals != null)
					removals[scope[0]]++;
				changed = true;
			}
		}
		for(int column = 0; column < columns.length; column++)
			if(columns[column] && !reached[column]) {
				columns[column] = false;
				if(removals != null)
				removals[scope[1]]++;
				changed = true;
			}
		return changed;
	}

	private static boolean reviseDenseSupport(int[] scope, double[] values,
		boolean[][] active) {
		boolean changed = false;
		if(scope.length == 1) {
			for(int value=0; value<active[scope[0]].length; value++)
				if(active[scope[0]][value] && !Double.isFinite(values[value])) {
					active[scope[0]][value] = false;
					changed = true;
				}
		}
		else if(scope.length == 2) {
			for(int side=0; side<2; side++) {
				int other = 1-side;
				for(int value=0; value<active[scope[side]].length; value++) {
					if(!active[scope[side]][value])
						continue;
					boolean supported = false;
					for(int otherValue=0; otherValue<active[scope[other]].length; otherValue++) {
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
		return changed;
	}

	private static boolean revisePartialHardFactor(ExactCategoricalSolver.Factor factor,
		int[] scope, boolean[][] active) {
		return revisePartialHardFactor(factor, scope, active, null);
	}

	private static boolean revisePartialHardFactor(ExactCategoricalSolver.Factor factor,
		int[] scope, boolean[][] active, int[] removals) {
		boolean changed = false;
		int[] local = new int[scope.length];
		Arrays.fill(local,-1);
		if(scope.length == 1) {
			for(int value=0; value<active[scope[0]].length; value++)
				if(active[scope[0]][value]) {
					local[0] = value;
					if(!partialAssignmentFinite(factor,local)) {
						active[scope[0]][value] = false;
						if(removals != null)
							removals[scope[0]]++;
						changed = true;
					}
				}
		}
		else if(scope.length == 2) {
			for(int side=0; side<2; side++) {
				int other = 1-side;
				for(int value=0; value<active[scope[side]].length; value++) {
					if(!active[scope[side]][value])
						continue;
					Arrays.fill(local,-1);
					local[side] = value;
					ExactCategoricalSolver.PartialTruth truth = side == 0
						? factor.partialTruth(local) : ExactCategoricalSolver.PartialTruth.UNKNOWN;
					boolean supported = truth == ExactCategoricalSolver.PartialTruth.ALL_ZERO
						? !none(active[scope[other]]) : false;
					if(truth == ExactCategoricalSolver.PartialTruth.UNKNOWN)
						for(int otherValue=0; otherValue<active[scope[other]].length; otherValue++) {
							if(!active[scope[other]][otherValue])
								continue;
							local[other] = otherValue;
							if(partialAssignmentFinite(factor,local)) {
								supported = true;
								break;
							}
						}
					if(!supported) {
						active[scope[side]][value] = false;
						if(removals != null)
							removals[scope[side]]++;
						changed = true;
					}
				}
			}
		}
		return changed;
	}

	private static boolean partialAssignmentFinite(ExactCategoricalSolver.Factor factor,
		int[] local) {
		ExactCategoricalSolver.PartialTruth truth = factor.partialTruth(local);
		return truth == ExactCategoricalSolver.PartialTruth.ALL_ZERO
			|| truth == ExactCategoricalSolver.PartialTruth.UNKNOWN
				&& Double.isFinite(factor.cost(local));
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
		long[][] allTieCosts = new long[active.length][];
		for(int index = 0; index < allTieCosts.length; index++)
			allTieCosts[index] = index == variable ? tieCosts : new long[active[index].length];
		ObservationHashes hashes = constantObservationHash ? null
			: compileObservationHashes(frozen,active,allTieCosts,variable + 1,
				incident.stream().mapToInt(Integer::intValue).toArray());
		return quotientClasses(frozen,variable,active,incident,tieCosts,hashes,
			constantObservationHash);
	}

	private static int[][] quotientClasses(ExactCategoricalSolver.FrozenInputs frozen,
		int variable, boolean[][] active, List<Integer> incident, long[] tieCosts,
		ObservationHashes observationHashes, boolean constantObservationHash) {
		ObservationTraversal[] observations = incident.stream()
			.map(factor -> new ObservationTraversal(frozen, factor, variable))
			.toArray(ObservationTraversal[]::new);
		Map<ObservationHash,List<List<Integer>>> buckets = new LinkedHashMap<>();
		for(int value = 0; value < active[variable].length; value++) {
			if(!active[variable][value])
				continue;
			ObservationHash hash = constantObservationHash ? new ObservationHash(0L, 0L)
				: observationHashes.hash(variable,value);
			List<List<Integer>> candidates = buckets.computeIfAbsent(hash,
				ignored -> new ArrayList<>());
			List<Integer> equivalent = null;
			for(List<Integer> candidate : candidates)
				if(observationsEqual(frozen, candidate.get(0), value, active,
					observations, tieCosts)) {
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
		int value, boolean[][] active, ObservationTraversal[] observations, long tieCost) {
		return observationHash(frozen, value, active, observations, tieCost,
			new ObservationHashAccumulator());
	}

	private static ObservationHash observationHash(ExactCategoricalSolver.FrozenInputs frozen,
		int value, boolean[][] active, ObservationTraversal[] observations, long tieCost,
		ObservationHashAccumulator accumulator) {
		accumulator.reset(mix(0x9e3779b97f4a7c15L, tieCost),
			mix(0xc2b2ae3d27d4eb4fL, tieCost));
		if(observations.length == 0)
			return new ObservationHash(accumulator.first,accumulator.second);
		int variable = observations[0].scope[observations[0].variablePosition];
		long[][] tieCosts = new long[active.length][];
		for(int index = 0; index < tieCosts.length; index++)
			tieCosts[index] = new long[active[index].length];
		tieCosts[variable][value] = tieCost;
		boolean[][] compilationActive = active.clone();
		compilationActive[variable] = active[variable].clone();
		compilationActive[variable][value] = true;
		int[] factorOrder = Arrays.stream(observations)
			.mapToInt(observation -> observation.factor).toArray();
		ObservationHashes hashes = compileObservationHashes(frozen,compilationActive,tieCosts,
			active.length,factorOrder);
		return hashes.hash(variable,value);
	}

	private static boolean observationsEqual(ExactCategoricalSolver.FrozenInputs frozen,
		int left, int right, boolean[][] active, ObservationTraversal[] observations,
		long[] tieCosts) {
		if(tieCosts[left] != tieCosts[right])
			return false;
		for(ObservationTraversal observation : observations)
			if(!factorObservationsEqual(frozen, observation, left, right, active, 0, 0))
				return false;
		return true;
	}

	private static boolean factorObservationsEqual(ExactCategoricalSolver.FrozenInputs frozen,
		ObservationTraversal observation, int left, int right, boolean[][] active,
		int position, int cell) {
		if(position == 0 && observation.variablePosition == 0) {
			ExactCategoricalSolver.FunctionalMap mapping = frozen.factor(observation.factor).functionalMapping();
			if(mapping != null) {
				boolean[] targets = active[observation.scope[1]];
				return activeFunctionalTarget(mapping, left, targets)
					== activeFunctionalTarget(mapping, right, targets);
			}
		}
		ExactCategoricalSolver.Factor factor = frozen.factor(observation.factor);
		if(position == 0 && factor.isFiniteSupport())
			return finiteSupportObservationsEqual(factor.finiteSupportCells(), observation,
				left, right, active);
		if(position == observation.scope.length) {
			int stride = observation.strides[observation.variablePosition];
			long leftBits = Double.doubleToRawLongBits(
				frozen.costAt(observation.factor, cell + left * stride));
			return leftBits == Double.doubleToRawLongBits(
				frozen.costAt(observation.factor, cell + right * stride));
		}
		if(position == observation.variablePosition)
			return factorObservationsEqual(frozen, observation, left, right, active,
				position + 1, cell);
		int scopedVariable = observation.scope[position];
		int stride = observation.strides[position];
		for(int value = 0; value < active[scopedVariable].length; value++)
			if(active[scopedVariable][value]
				&& !factorObservationsEqual(frozen, observation, left, right, active,
					position + 1, cell + value * stride))
				return false;
		return true;
	}

	private static boolean finiteSupportObservationsEqual(int[] cells,
		ObservationTraversal observation, int left, int right, boolean[][] active) {
		int leftIndex = 0;
		int rightIndex = 0;
		while(true) {
			long leftNext = nextFiniteProjection(cells,leftIndex,observation,left,active);
			long rightNext = nextFiniteProjection(cells,rightIndex,observation,right,active);
			if(leftNext == -1L || rightNext == -1L)
				return leftNext == rightNext;
			if((int)leftNext != (int)rightNext)
				return false;
			leftIndex = (int)(leftNext >>> 32);
			rightIndex = (int)(rightNext >>> 32);
		}
	}

	private static long nextFiniteProjection(int[] cells, int start,
		ObservationTraversal observation, int selected, boolean[][] active) {
		int axis = observation.variablePosition;
		int stride = observation.strides[axis];
		int domain = active[observation.scope[axis]].length;
		for(int index = start; index < cells.length; index++) {
			int cell = cells[index];
			if((cell / stride) % domain != selected)
				continue;
			int remaining = cell;
			boolean activeCell = true;
			for(int position = observation.scope.length - 1; position >= 0; position--) {
				int scopedVariable = observation.scope[position];
				int scopedDomain = active[scopedVariable].length;
				int value = remaining % scopedDomain;
				remaining /= scopedDomain;
				if(position != axis)
					activeCell &= active[scopedVariable][value];
			}
			if(activeCell) {
				int coordinate = (cell / (domain * stride)) * stride + cell % stride;
				return ((long)(index + 1) << 32) | (coordinate & 0xffffffffL);
			}
		}
		return -1L;
	}

	private static ObservationHashes compileObservationHashes(
		ExactCategoricalSolver.FrozenInputs frozen, boolean[][] active, long[][] tieCosts,
		int quotientVariableCount, int[] factorOrder) {
		long[][] first = new long[quotientVariableCount][];
		long[][] second = new long[quotientVariableCount][];
		int maxArity = 0;
		for(int variable = 0; variable < quotientVariableCount; variable++) {
			first[variable] = PlannerResourceGuard.allocateLongs(active[variable].length,
				"exact-quotient-observation-hash");
			second[variable] = PlannerResourceGuard.allocateLongs(active[variable].length,
				"exact-quotient-observation-hash");
			for(int value = 0; value < active[variable].length; value++)
				if(active[variable][value]) {
					first[variable][value] = mix(0x9e3779b97f4a7c15L,tieCosts[variable][value]);
					second[variable][value] = mix(0xc2b2ae3d27d4eb4fL,tieCosts[variable][value]);
				}
		}
		int factorCount = factorOrder == null ? frozen.factorCount() : factorOrder.length;
		for(int ordinal = 0; ordinal < factorCount; ordinal++) {
			int factor = factorOrder == null ? ordinal : factorOrder[ordinal];
			int[] scope = frozen.scope(factor);
			for(int scopedVariable : scope)
				if(scopedVariable < quotientVariableCount) {
				maxArity = Math.max(maxArity,scope.length);
					break;
				}
		}
		ObservationHashes hashes = new ObservationHashes(first,second,
			PlannerResourceGuard.allocateInts(maxArity,"exact-quotient-observation-coordinates"));
		for(int ordinal = 0; ordinal < factorCount; ordinal++) {
			int factor = factorOrder == null ? ordinal : factorOrder[ordinal];
			int[] scope = frozen.scope(factor);
			boolean relevant = false;
			for(int scopedVariable : scope)
				if(scopedVariable < quotientVariableCount) {
					relevant = true;
					for(int value = 0; value < active[scopedVariable].length; value++)
						if(active[scopedVariable][value]) {
							hashes.first[scopedVariable][value] = mix(
								hashes.first[scopedVariable][value],factor);
							hashes.second[scopedVariable][value] = mix(
								hashes.second[scopedVariable][value],~factor);
						}
				}
			if(relevant) {
				ExactCategoricalSolver.Factor frozenFactor = frozen.factor(factor);
				ExactCategoricalSolver.FunctionalMap mapping = frozenFactor.functionalMapping();
				if(mapping != null)
					compileFunctionalObservations(mapping,scope,active,quotientVariableCount,hashes);
				else if(frozenFactor.isFiniteSupport())
					compileFiniteSupportObservations(frozenFactor.finiteSupportCells(),scope,active,
						quotientVariableCount,hashes);
				else if(frozenFactor.isHardTable()
					&& preferSparseHardObservations(frozenFactor.hardTable(),scope,active))
					compileHardObservations(frozenFactor.hardTable(),scope,active,
						quotientVariableCount,hashes);
				else
					compileFactorObservations(frozen,factor,scope,active,quotientVariableCount,
						hashes,0,0);
			}
		}
		return hashes;
	}

	private static void compileFiniteSupportObservations(int[] cells, int[] scope,
		boolean[][] active, int quotientVariableCount, ObservationHashes hashes) {
		int logicalCells = 1;
		for(int variable : scope)
			logicalCells = Math.multiplyExact(logicalCells, active[variable].length);
		for(int cell : cells) {
			int remaining = cell;
			boolean activeCell = true;
			for(int position = scope.length - 1; position >= 0; position--) {
				int domain = active[scope[position]].length;
				int value = remaining % domain;
				remaining /= domain;
				hashes.coordinates[position] = value;
				activeCell &= active[scope[position]][value];
			}
			if(!activeCell)
				continue;
			hashes.hardExceptions++;
			for(int axis = 0, stride = logicalCells; axis < scope.length; axis++) {
				int domain = active[scope[axis]].length;
				stride /= domain;
				int variable = scope[axis];
				if(variable < quotientVariableCount) {
					long coordinate = (long)(cell / (domain * stride)) * stride + cell % stride;
					long token = coordinate << 1;
					int value = hashes.coordinates[axis];
					hashes.first[variable][value] = mix(hashes.first[variable][value],token);
					hashes.second[variable][value] = mix(hashes.second[variable][value],
						Long.rotateLeft(token,23));
				}
			}
		}
	}

	/** Choose one representation for the complete factor occurrence, without changing its domain. */
	private static boolean preferSparseHardObservations(ExactCategoricalSolver.HardTable table,
		int[] scope, boolean[][] active) {
		if(!table.packed())
			return false;
		long activeCartesian = 1L;
		for(int variable : scope) {
			int activeValues = 0;
			for(boolean value : active[variable])
				if(value)
					activeValues++;
			if(activeValues == 0)
				return false;
			activeCartesian = saturatedMultiply(activeCartesian,activeValues);
		}
		long sparseWork = table.sparseExceptionCount() == 0 ? 0L
			: saturatedAdd(table.packedWordCount(),
				saturatedMultiply(scope.length,table.sparseExceptionCount()));
		return sparseWork < activeCartesian;
	}

	private static long saturatedMultiply(long left, long right) {
		return left == 0L || right == 0L ? 0L
			: left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
	}

	private static long saturatedAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}

	private static int activeFunctionalTarget(ExactCategoricalSolver.FunctionalMap mapping,
		int row, boolean[] activeTargets) {
		int target = mapping.target(row);
		return target >= 0 && activeTargets[target] ? target : -1;
	}

	/**
	 * Hash only an exact active row-profile key for this closed representation. Hashes
	 * are private bucket keys, not certificates: the subsequent full observation
	 * equality check remains authoritative, and canonical representative order is unchanged.
	 */
	private static void compileFunctionalObservations(ExactCategoricalSolver.FunctionalMap mapping,
		int[] scope, boolean[][] active, int quotientVariableCount, ObservationHashes hashes) {
		int source = scope[0];
		int target = scope[1];
		boolean[] reached = target < quotientVariableCount
			? new boolean[active[target].length] : null;
		for(int row = 0; row < active[source].length; row++) {
			if(active[source][row]) {
				hashes.functionalRows++;
				int mapped = mapping.target(row);
				if(reached != null && mapped >= 0)
					reached[mapped] = true;
				if(source < quotientVariableCount) {
					long profile = activeFunctionalTarget(mapping, row, active[scope[1]]) + 1L;
					hashes.first[source][row] = mix(hashes.first[source][row], profile);
					hashes.second[source][row] = mix(hashes.second[source][row],
						Long.rotateLeft(profile, 23));
				}
			}
		}
		if(reached != null)
			for(int column = 0; column < reached.length; column++)
				if(active[target][column]) {
					long profile = reached[column] ? column + 1L : 0L;
					hashes.first[target][column] = mix(hashes.first[target][column],profile);
					hashes.second[target][column] = mix(hashes.second[target][column],
						Long.rotateLeft(profile,23));
				}
	}

	/**
	 * Compile an exact hard profile from the smaller global polarity. Each token is
	 * the row-major exception coordinate with the observed axis removed. The full
	 * equality check remains authoritative for collisions.
	 */
	private static void compileHardObservations(ExactCategoricalSolver.HardTable table,
		int[] scope, boolean[][] active, int quotientVariableCount, ObservationHashes hashes) {
		if(!table.packed())
			throw new IllegalArgumentException("EXACT_VE_HARD_PROFILE_REPRESENTATION_INVALID");
		if(table.sparseExceptionCount() == 0)
			return;
		boolean exceptionsForbidden = table.sparseExceptionsForbidden();
		for(int word = 0; word < table.packedWordCount(); word++) {
			long exceptions = table.sparseExceptionWord(word,exceptionsForbidden);
			hashes.hardWords++;
			while(exceptions != 0L) {
				int bit = Long.numberOfTrailingZeros(exceptions);
				int cell = (word << 6) + bit;
				exceptions &= exceptions - 1L;
				hashes.hardExceptions++;
				int remaining = cell;
				boolean activeCell = true;
				for(int position = scope.length - 1; position >= 0; position--) {
					int domain = active[scope[position]].length;
					int value = remaining % domain;
					remaining /= domain;
					hashes.coordinates[position] = value;
					activeCell &= active[scope[position]][value];
				}
				if(!activeCell)
					continue;
				for(int axis = 0, stride = table.cells(); axis < scope.length; axis++) {
					int domain = active[scope[axis]].length;
					stride /= domain;
					int variable = scope[axis];
					if(variable < quotientVariableCount) {
						long coordinate = (long)(cell / (domain * stride)) * stride + cell % stride;
						long token = (coordinate << 1) | (exceptionsForbidden ? 1L : 0L);
						int value = hashes.coordinates[axis];
						hashes.first[variable][value] = mix(hashes.first[variable][value],token);
						hashes.second[variable][value] = mix(hashes.second[variable][value],
							Long.rotateLeft(token,23));
					}
				}
			}
		}
	}

	private static void compileFactorObservations(ExactCategoricalSolver.FrozenInputs frozen,
		int factor, int[] scope, boolean[][] active, int quotientVariableCount,
		ObservationHashes hashes, int position, int cell) {
		if(position == scope.length) {
			long bits = Double.doubleToRawLongBits(frozen.costAt(factor,cell));
			hashes.cellReads++;
			for(int axis = 0; axis < scope.length; axis++) {
				int variable = scope[axis];
				if(variable < quotientVariableCount) {
					int value = hashes.coordinates[axis];
					hashes.first[variable][value] = mix(hashes.first[variable][value],bits);
					hashes.second[variable][value] = mix(hashes.second[variable][value],
						Long.rotateLeft(bits,23));
				}
			}
			return;
		}
		int variable = scope[position];
		for(int value = 0; value < active[variable].length; value++)
			if(active[variable][value]) {
				hashes.coordinates[position] = value;
				compileFactorObservations(frozen,factor,scope,active,quotientVariableCount,
					hashes,position + 1,cell * active[variable].length + value);
			}
	}

	private static final class ObservationTraversal {
		private final int factor;
		private final int[] scope;
		private final int[] strides;
		private final int variablePosition;

		private ObservationTraversal(ExactCategoricalSolver.FrozenInputs frozen,
			int factor, int variable) {
			this.factor = factor;
			scope = frozen.scope(factor);
			strides = new int[scope.length];
			int found = -1;
			int stride = 1;
			for(int position = scope.length - 1; position >= 0; position--) {
				strides[position] = stride;
				stride = Math.multiplyExact(stride, frozen.domainSize(scope[position]));
				if(scope[position] == variable)
					found = position;
			}
			if(found < 0)
				throw new IllegalArgumentException("EXACT_VE_FACTOR_VARIABLE_UNKNOWN");
			variablePosition = found;
		}
	}

	private static int[][] singletonClasses(boolean[] active) {
		List<int[]> values = new ArrayList<>();
		for(int value = 0; value < active.length; value++)
			if(active[value])
				values.add(new int[] {value});
		return values.toArray(int[][]::new);
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

	private static final class ObservationHashAccumulator {
		private long first;
		private long second;

		private void reset(long nextFirst, long nextSecond) {
			first = nextFirst;
			second = nextSecond;
		}

	}

	private static final class ObservationHashes {
		private final long[][] first;
		private final long[][] second;
		private final int[] coordinates;
		private long cellReads;
		private long hardWords;
		private long hardExceptions;
		private long functionalRows;

		private ObservationHashes(long[][] first, long[][] second, int[] coordinates) {
			this.first = first;
			this.second = second;
			this.coordinates = coordinates;
		}

		private ObservationHash hash(int variable, int value) {
			return new ObservationHash(first[variable][value],second[variable][value]);
		}
	}

	private record ObservationHash(long first, long second) { }
}
