/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/**
 * Deterministic local min-sum optimization over the shared categorical factor model.
 *
 * <p>The solver performs one ordered local pass, repairs connected hard-factor
 * conflicts by optimizing only their incident variable blocks, and then solves
 * each caller-supplied local interaction block once in structural order. Callers
 * may explicitly request a bounded number of deterministic dirty-block revisit
 * passes after that legacy pass and deferred-block discovery have completed.
 * It never truncates a frontier by cardinality: within a complete state key it
 * retains the minimum-cost representative, while distinct state keys remain
 * incomparable.</p>
 */
final class LocalCategoricalOptimizer {
	@FunctionalInterface
	interface PreparedBlockSolver {
		ExactCategoricalSolver.Result solve();
	}

	@FunctionalInterface
	interface BlockPreparation {
		/** Original decision indexes/values in, original block values out. */
		PreparedBlockSolver prepare(int[] assignment, int[] block);
	}

	static final String COMPACT_PROPERTY = "sysds.fedplanner.regional.compact";

	@FunctionalInterface
	interface StateKeyProvider {
		Object stateKey(Variable variable, int value);
	}

	@FunctionalInterface
	interface DeferredBlockProvider {
		List<List<Variable>> localBlocks(List<Integer> assignmentInVariableOrder);
	}

	/**
	 * Timing values use {@link System#nanoTime()}. Total optimization includes input
	 * validation, context construction, the ordered pass, repairs, local blocks, and
	 * final validation. Exact preparation and solve are accumulated nested subsets:
	 * preparation covers block incidence plus factor reduction/compilation, while
	 * solve covers direct enumeration or execution of a compiled factorized block.
	 */
	record Statistics(long rawLocalAlternatives, long retainedLocalStates,
		long prunedLocalRepresentatives, int initialHardViolations,
		int finalHardViolations, int conflictBlocksSolved, int conflictBlockExpansions,
		int localBlocks, int localBlockImprovements, int localBlockRevisits,
		int factorizedBlockCompilations, int factorizedBlockSolves,
		int factorwiseMinimumSkips, long factorwiseMinimumAssignments,
		int maximumBlockVariables,
		long maximumBlockAssignments, long blockAssignments,
		long totalOptimizationNanos, long exactBlockPreparationNanos,
		long exactBlockSolveNanos) {
		Statistics(long rawLocalAlternatives, long retainedLocalStates,
			long prunedLocalRepresentatives, int initialHardViolations,
			int finalHardViolations, int conflictBlocksSolved, int conflictBlockExpansions,
			int localBlocks, int localBlockImprovements, int localBlockRevisits,
			int factorizedBlockCompilations, int factorizedBlockSolves,
			int factorwiseMinimumSkips, int maximumBlockVariables,
			long maximumBlockAssignments, long blockAssignments) {
			this(rawLocalAlternatives, retainedLocalStates, prunedLocalRepresentatives,
				initialHardViolations, finalHardViolations, conflictBlocksSolved,
				conflictBlockExpansions, localBlocks, localBlockImprovements,
				localBlockRevisits, factorizedBlockCompilations, factorizedBlockSolves,
				factorwiseMinimumSkips, 0L, maximumBlockVariables, maximumBlockAssignments,
				blockAssignments, 0L, 0L, 0L);
		}
	}

	record Result(double objective, List<Integer> assignmentInVariableOrder,
		Statistics statistics) {
		Result {
			assignmentInVariableOrder = List.copyOf(assignmentInVariableOrder);
			Objects.requireNonNull(statistics, "statistics");
		}
	}

	private record IndexedFactor(Factor factor, int[] scope, int ordinal) { }
	private record LocalChoice(int value, int hardViolations, double cost) { }
	private record BlockSolution(int[] valuesInCanonicalBlockOrder, double incidentCost,
		long searchAssignments) { }
	private record PreparedBlock(int[] variables, List<IndexedFactor> incidentHard,
		List<IndexedFactor> incidentCost, int[] dependencyVariables) { }
	private record BlockStateKey(List<Object> stateKeys) {
		BlockStateKey { stateKeys = List.copyOf(stateKeys); }
	}
	private record BlockCandidate(int[] values, double cost) { }

	private static final class MutableStatistics {
		long rawLocalAlternatives;
		long retainedLocalStates;
		long prunedLocalRepresentatives;
		int initialHardViolations;
		int conflictBlocksSolved;
		int conflictBlockExpansions;
		int localBlocks;
		int localBlockImprovements;
		int localBlockRevisits;
		int factorizedBlockCompilations;
		int factorizedBlockSolves;
		int factorwiseMinimumSkips;
		long factorwiseMinimumAssignments;
		int maximumBlockVariables;
		long maximumBlockAssignments;
		long blockAssignments;
		long totalOptimizationNanos;
		long exactBlockPreparationNanos;
		long exactBlockSolveNanos;

		Statistics freeze(int finalHardViolations) {
			return new Statistics(rawLocalAlternatives, retainedLocalStates,
				prunedLocalRepresentatives, initialHardViolations, finalHardViolations,
				conflictBlocksSolved, conflictBlockExpansions, localBlocks,
				localBlockImprovements, localBlockRevisits,
				factorizedBlockCompilations, factorizedBlockSolves,
				factorwiseMinimumSkips, factorwiseMinimumAssignments,
				maximumBlockVariables, maximumBlockAssignments,
				blockAssignments, totalOptimizationNanos,
				exactBlockPreparationNanos, exactBlockSolveNanos);
		}
	}

	private static final class Context {
		final List<Variable> variables;
		final List<IndexedFactor> hardFactors;
		final List<IndexedFactor> costFactors;
		final List<List<IndexedFactor>> incidentHard;
		final List<List<IndexedFactor>> incidentCost;
		final IdentityHashMap<Variable,Integer> positions;
		final Set<Factor> validatedDenseNonNegativeCostFactors;
		final Set<Factor> nonDenseCostFactors;
		final StateKeyProvider stateKeys;
		final boolean compact;
		final ExactEliminationOrderPolicy.Configuration orderPolicy;
		final BlockPreparation sharedPreparation;

		Context(List<Variable> variables, List<Factor> hardFactors,
			List<Factor> costFactors, StateKeyProvider stateKeys, boolean compact,
			BlockPreparation sharedPreparation) {
			this.variables = List.copyOf(Objects.requireNonNull(variables, "variables"));
			this.stateKeys = Objects.requireNonNull(stateKeys, "stateKeys");
			this.compact = compact;
			orderPolicy = ExactEliminationOrderPolicy.localConfigured(compact);
			this.sharedPreparation = sharedPreparation;
			positions = new IdentityHashMap<>();
			Set<String> keys = new LinkedHashSet<>();
			for(int index = 0; index < this.variables.size(); index++) {
				Variable variable = Objects.requireNonNull(this.variables.get(index), "variable");
				if(positions.put(variable, index) != null || !keys.add(variable.key()))
					throw new IllegalArgumentException("LOCAL_VARIABLE_DUPLICATE|key=" + variable.key());
			}
			incidentHard = emptyIncidence(this.variables.size());
			incidentCost = emptyIncidence(this.variables.size());
			this.hardFactors = indexFactors(hardFactors, incidentHard, "LOCAL_HARD_FACTOR");
			this.costFactors = indexFactors(costFactors, incidentCost, "LOCAL_COST_FACTOR");
			validatedDenseNonNegativeCostFactors =
				Collections.newSetFromMap(new IdentityHashMap<>());
			nonDenseCostFactors = Collections.newSetFromMap(new IdentityHashMap<>());
			for(Variable variable : this.variables)
				for(int value = 0; value < variable.domainSize(); value++)
					Objects.requireNonNull(stateKeys.stateKey(variable, value),
						"local state key for " + variable.key() + ':' + value);
		}

		private List<IndexedFactor> indexFactors(List<Factor> factors,
			List<List<IndexedFactor>> incidence, String errorPrefix) {
			Objects.requireNonNull(factors, "factors");
			List<IndexedFactor> indexed = new ArrayList<>(factors.size());
			for(int ordinal = 0; ordinal < factors.size(); ordinal++) {
				Factor factor = Objects.requireNonNull(factors.get(ordinal), "factor");
				int[] scope = new int[factor.scope().size()];
				Set<Integer> unique = new LinkedHashSet<>();
				for(int local = 0; local < scope.length; local++) {
					Integer global = positions.get(factor.scope().get(local));
					if(global == null)
						throw new IllegalArgumentException(errorPrefix + "_FOREIGN_VARIABLE");
					if(!unique.add(global))
						throw new IllegalArgumentException(errorPrefix + "_DUPLICATE_VARIABLE");
					scope[local] = global;
				}
				IndexedFactor current = new IndexedFactor(factor, scope, ordinal);
				indexed.add(current);
				for(int global : scope)
					incidence.get(global).add(current);
			}
			return List.copyOf(indexed);
		}
	}

	private static final class LocalBlockOptimizer {
		final Context context;
		final int[] assignment;
		final MutableStatistics statistics;
		final List<int[]> blocks = new ArrayList<>();
		final List<PreparedBlock> prepared = new ArrayList<>();
		final List<Boolean> active = new ArrayList<>();
		final List<int[]> lastObservedAssignments = new ArrayList<>();

		LocalBlockOptimizer(Context context, int[] assignment,
			MutableStatistics statistics) {
			this.context = context;
			this.assignment = assignment;
			this.statistics = statistics;
		}

		List<Integer> addBlocks(List<int[]> candidates) {
			List<Integer> added = new ArrayList<>();
			for(int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
				int[] candidate = candidates.get(candidateIndex);
				// Exact optimization of a superset dominates every contained block: any
				// contained move is also a legal superset move with the remaining values
				// fixed. Keep only maximal neighborhoods instead of solving identical exact
				// subproblems at multiple granularities.
				// Look through the complete incoming batch before preparing factor incidence.
				// Otherwise an ascending subset followed by its superset is retired before
				// optimization but still pays the full preparation cost.
				if(hasActiveSuperset(candidate)
					|| hasStrictSuperset(candidates, candidateIndex, candidate))
					continue;
				for(int prior = 0; prior < blocks.size(); prior++)
					if(active.get(prior) && containsAll(candidate, blocks.get(prior)))
						active.set(prior, false);
				int index = blocks.size();
				int[] stored = candidate.clone();
				long preparationStart = System.nanoTime();
				PreparedBlock block;
				try {
					block = prepareBlock(context, stored);
				}
				finally {
					statistics.exactBlockPreparationNanos += elapsedNanos(preparationStart);
				}
				blocks.add(stored);
				prepared.add(block);
				active.add(true);
				lastObservedAssignments.add(null);
				added.add(index);
			}
			statistics.localBlocks = (int) active.stream().filter(Boolean::booleanValue).count();
			return List.copyOf(added);
		}

		private boolean hasActiveSuperset(int[] candidate) {
			for(int index = 0; index < blocks.size(); index++)
				if(active.get(index) && containsAll(blocks.get(index), candidate))
					return true;
			return false;
		}

		private static boolean hasStrictSuperset(List<int[]> candidates,
				int candidateIndex, int[] candidate) {
			for(int index = 0; index < candidates.size(); index++) {
				int[] other = candidates.get(index);
				if(index != candidateIndex && other.length > candidate.length
					&& containsAll(other, candidate))
					return true;
			}
			return false;
		}

		boolean optimize(List<Integer> initialBlocks) {
			return optimize(initialBlocks, false);
		}

		boolean optimize(List<Integer> initialBlocks, boolean requireFullObjectiveImprovement) {
			boolean improved = false;
			for(int blockIndex : initialBlocks) {
				if(blockIndex < 0 || blockIndex >= prepared.size())
					throw new IllegalArgumentException("LOCAL_INITIAL_BLOCK_INDEX_INVALID|index="
						+ blockIndex);
				if(!active.get(blockIndex))
					continue;
				improved |= optimizeBlock(blockIndex, requireFullObjectiveImprovement);
			}
			return improved;
		}

		boolean revisitDirtyBlocks() {
			boolean improved = false;
			for(int blockIndex = 0; blockIndex < prepared.size(); blockIndex++) {
				if(!active.get(blockIndex) || !isDirty(blockIndex))
					continue;
				statistics.localBlockRevisits++;
				improved |= optimizeBlock(blockIndex, true);
			}
			return improved;
		}

		private boolean optimizeBlock(int blockIndex, boolean requireFullObjectiveImprovement) {
			PreparedBlock block = prepared.get(blockIndex);
			if(isFactorwiseMinimum(context, assignment, block, statistics)) {
				statistics.factorwiseMinimumSkips++;
				observe(blockIndex);
				return false;
			}
			double incidentBefore = evaluateCost(block.incidentCost(), assignment);
			double fullBefore = requireFullObjectiveImprovement
				? evaluateCost(context.costFactors, assignment) : 0d;
			BlockSolution solution = solveBlock(block);
			if(solution == null)
				throw new IllegalArgumentException(
					"LOCAL_INTERACTION_BLOCK_HAS_NO_LEGAL_ASSIGNMENT|variables="
						+ variableKeys(context, block.variables()));
			recordBlockStatistics(statistics, block.variables().length, solution);
			int comparison = Double.compare(solution.incidentCost(), incidentBefore);
			if(comparison > 0)
				throw new IllegalArgumentException("LOCAL_INTERACTION_BLOCK_COST_INCREASE|before="
					+ incidentBefore + "|after=" + solution.incidentCost() + "|variables="
					+ variableKeys(context, block.variables()));
			if(comparison == 0) {
				observe(blockIndex);
				return false;
			}
			int[] saved = values(assignment, block.variables());
			if(Arrays.equals(saved, solution.valuesInCanonicalBlockOrder()))
				throw new IllegalArgumentException(
					"LOCAL_INTERACTION_BLOCK_COST_CHANGED_WITHOUT_ASSIGNMENT");
			apply(assignment, block.variables(), solution.valuesInCanonicalBlockOrder());
			if(requireFullObjectiveImprovement) {
				List<Integer> violations = violatedHardFactors(context, assignment);
				double fullAfter = evaluateCost(context.costFactors, assignment);
				if(!violations.isEmpty() || Double.compare(fullAfter, fullBefore) >= 0) {
					apply(assignment, block.variables(), saved);
					if(!violations.isEmpty())
						throw new IllegalArgumentException(
							"LOCAL_REVISIT_HARD_CONFLICT|factors=" + violations);
					observe(blockIndex);
					return false;
				}
			}
			statistics.localBlockImprovements++;
			observe(blockIndex);
			return true;
		}

		private boolean isDirty(int blockIndex) {
			int[] observed = lastObservedAssignments.get(blockIndex);
			return observed != null
				&& !Arrays.equals(observed,
					values(assignment, prepared.get(blockIndex).dependencyVariables()));
		}

		private void observe(int blockIndex) {
			lastObservedAssignments.set(blockIndex,
				values(assignment, prepared.get(blockIndex).dependencyVariables()));
		}

		private BlockSolution solveBlock(PreparedBlock block) {
			if(block.variables().length == 1)
				return solveDirectBlock(context, assignment, block, statistics);
			long preparationStart = System.nanoTime();
			FactorizedBlockSolver solver;
			try {
				solver = new FactorizedBlockSolver(context, assignment,
					block.variables(), block.incidentHard(), block.incidentCost());
			}
			finally {
				statistics.exactBlockPreparationNanos += elapsedNanos(preparationStart);
			}
			statistics.factorizedBlockCompilations++;
			statistics.factorizedBlockSolves++;
			long solveStart = System.nanoTime();
			try {
				return solver.solve();
			}
			finally {
				statistics.exactBlockSolveNanos += elapsedNanos(solveStart);
			}
		}
	}

	private static final class BlockSearch {
		final Context context;
		final int[] assignment;
		final int[] canonicalBlock;
		final int[] searchOrder;
		final List<IndexedFactor> incidentHard;
		final List<IndexedFactor> incidentCost;
		final boolean duplicateStateKeys;
		final Map<BlockStateKey,BlockCandidate> representatives;
		BlockCandidate best;
		long completeAssignments;

		BlockSearch(Context context, int[] assignment, int[] canonicalBlock,
			List<IndexedFactor> incidentHard, List<IndexedFactor> incidentCost) {
			this.context = context;
			this.assignment = assignment;
			this.canonicalBlock = canonicalBlock;
			this.incidentHard = incidentHard;
			this.incidentCost = incidentCost;
			this.searchOrder = orderForSearch(context, canonicalBlock);
			this.duplicateStateKeys = hasDuplicateStateKeys(context, canonicalBlock);
			this.representatives = duplicateStateKeys ? new LinkedHashMap<>() : null;
		}

		BlockSolution solve() {
			int[] saved = new int[canonicalBlock.length];
			for(int index = 0; index < canonicalBlock.length; index++) {
				saved[index] = assignment[canonicalBlock[index]];
				assignment[canonicalBlock[index]] = -1;
			}
			try {
				search(0);
				if(duplicateStateKeys)
					for(BlockCandidate candidate : representatives.values())
						retainBest(candidate);
				if(best == null)
					return null;
				return new BlockSolution(best.values().clone(), best.cost(), completeAssignments);
			}
			finally {
				for(int index = 0; index < canonicalBlock.length; index++)
					assignment[canonicalBlock[index]] = saved[index];
			}
		}

		private void search(int depth) {
			if(depth == searchOrder.length) {
				completeAssignments++;
				double cost = evaluateCost(incidentCost, assignment);
				if(!Double.isFinite(cost))
					return;
				int[] values = new int[canonicalBlock.length];
				for(int index = 0; index < canonicalBlock.length; index++)
					values[index] = assignment[canonicalBlock[index]];
				BlockCandidate candidate = new BlockCandidate(values, cost);
				if(!duplicateStateKeys) {
					retainBest(candidate);
					return;
				}
				List<Object> keys = new ArrayList<>(canonicalBlock.length);
				for(int index = 0; index < canonicalBlock.length; index++) {
					int global = canonicalBlock[index];
					keys.add(context.stateKeys.stateKey(context.variables.get(global), values[index]));
				}
				BlockStateKey state = new BlockStateKey(keys);
				BlockCandidate prior = representatives.get(state);
				if(prior == null || compare(candidate, prior) < 0)
					representatives.put(state, candidate);
			}
			else {
				int global = searchOrder[depth];
				Variable variable = context.variables.get(global);
				for(int value = 0; value < variable.domainSize(); value++) {
					assignment[global] = value;
					if(hardFactorsSatisfiedWhenClosed(context.incidentHard.get(global), assignment))
						search(depth + 1);
				}
				assignment[global] = -1;
			}
		}

		private void retainBest(BlockCandidate candidate) {
			if(best == null || compare(candidate, best) < 0)
				best = candidate;
		}
	}

	/** Reuses one local block's validated factor scopes and elimination topology. */
	private static final class FactorizedBlockSolver {
		final Context context;
		final int[] assignment;
		final int[] block;
		final List<IndexedFactor> hard;
		final List<IndexedFactor> cost;
		final ExactPhysicalReducedSolver.Prepared prepared;
		final PreparedBlockSolver shared;

		FactorizedBlockSolver(Context context, int[] assignment, int[] block,
			List<IndexedFactor> hard, List<IndexedFactor> cost) {
			this.context = context;
			this.assignment = assignment;
			this.block = block.clone();
			this.hard = hard;
			this.cost = cost;
			shared = context.sharedPreparation == null ? null
				: context.sharedPreparation.prepare(assignment, block);
			if(shared != null) {
				prepared = null;
				return;
			}
			long preparationStart = System.nanoTime();
			Set<Integer> blockVariables = new LinkedHashSet<>();
			for(int variable : block)
				blockVariables.add(variable);
			List<Variable> variables = Arrays.stream(block)
				.mapToObj(context.variables::get).toList();
			List<Factor> reducedFactors = new ArrayList<>(hard.size() + cost.size());
			for(IndexedFactor factor : hard)
				reducedFactors.add(reduceFactor(context, assignment, blockVariables, factor));
			for(IndexedFactor factor : cost)
				reducedFactors.add(reduceFactor(context, assignment, blockVariables, factor));
			// Match Global's exact domain reduction on this conditional block.
			// The compact flag controls only singleton substitution after reduction.
			ExactPhysicalReducedSolver.Prepared reduced = null;
			boolean usedCompact = false;
			String fallback = "none";
			try {
				if(context.compact) {
					try {
						reduced = ExactPhysicalReducedSolver.prepareCompacted(block.length,
							variables, reducedFactors, ExactPhysicalOptimizer.PRODUCTION_LIMITS,
							context.orderPolicy, "local-block-compact");
						usedCompact = true;
					}
					catch(IllegalArgumentException failure) {
						if(!RegionalSearchProblem.isResourceLimit(failure))
							throw failure;
						fallback = failure.getMessage().split("\\|", 2)[0];
						reduced = ExactPhysicalReducedSolver.prepare(block.length,
							variables, reducedFactors,
							ExactPhysicalOptimizer.PRODUCTION_LIMITS, context.orderPolicy,
							"local-block-compact-fallback");
					}
				}
				else
					reduced = ExactPhysicalReducedSolver.prepare(block.length,
						variables, reducedFactors,
						ExactPhysicalOptimizer.PRODUCTION_LIMITS, context.orderPolicy,
						"local-block");
			}
			finally {
				if(FederatedPlannerTrace.isEnabled()) {
					if(reduced != null)
						tracePreparation("original-regional-block", reduced.preparationStatistics());
					int compiledVariables = reduced == null
						? variables.size() : reduced.compiledVariableCount();
					ExactCategoricalSolver.OrderCompilation order = reduced == null
						? null : reduced.orderCompilation();
					FederatedPlannerTrace.logGlobal("DP-RegionalBlockPreparation",
						String.format(Locale.ROOT,
							"requestedCompact=%s compact=%s exactReduction=true inputVariables=%d "
								+ "compiledVariables=%d "
								+ "fastOrderConfigured=%s fastOrderAccepted=%s fastOrderFallback=%s "
								+ "fastOrderEstimatedAssignments=%d fastOrderAssignmentsLimit=%d fastOrderSource=%s "
								+ "preparationNanos=%d resourceFallback=%s fallbackReason=%s",
							context.compact, usedCompact, variables.size(), compiledVariables,
							context.orderPolicy.fastOrder(), order != null && order.fastOrderAccepted(),
							order != null && order.fastOrderFallback(),
							order == null ? 0L : order.fastOrderAssignments(),
							context.orderPolicy.maximumAssignments(), context.orderPolicy.source(),
							elapsedNanos(preparationStart), !"none".equals(fallback), fallback));
				}
			}
			prepared = reduced;
		}

		BlockSolution solve() {
			ExactCategoricalSolver.Result solved;
			try {
				solved = shared == null ? ExactPhysicalReducedSolver.solve(prepared) : shared.solve();
			}
			catch(IllegalArgumentException ex) {
				if("EXACT_VE_NO_FEASIBLE_ASSIGNMENT".equals(ex.getMessage()))
					return null;
				throw ex;
			}
			int[] values = solved.assignmentInVariableOrder().stream()
				.mapToInt(Integer::intValue).toArray();
			int[] completed = assignment.clone();
			apply(completed, block, values);
			if(hard.stream().anyMatch(factor -> evaluate(factor, completed)
				== Double.POSITIVE_INFINITY))
				throw new IllegalArgumentException("LOCAL_FACTORIZED_BLOCK_HARD_CONFLICT");
			double incidentCost = evaluateCost(cost, completed);
			long assignments = solved.statistics().eliminationAssignments();
			return new BlockSolution(values, incidentCost, assignments);
		}
	}

	private LocalCategoricalOptimizer() { }

	static boolean configuredCompaction() {
		String value = System.getProperty(COMPACT_PROPERTY, "false");
		if(!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
			throw new IllegalArgumentException("LOCAL_COMPACT_OPTION_INVALID|" + value);
		return Boolean.parseBoolean(value);
	}

	static Result optimize(List<Variable> variables, List<Factor> hardFactors,
		List<Factor> costFactors, List<Variable> localOrder,
		List<List<Variable>> localBlocks, StateKeyProvider stateKeys) {
		return optimize(variables, hardFactors, costFactors, localOrder, localBlocks,
			ignored -> List.of(), stateKeys, 0);
	}

	static Result optimize(List<Variable> variables, List<Factor> hardFactors,
		List<Factor> costFactors, List<Variable> localOrder,
		List<List<Variable>> localBlocks, StateKeyProvider stateKeys, int revisitPasses) {
		return optimize(variables, hardFactors, costFactors, localOrder, localBlocks,
			ignored -> List.of(), stateKeys, revisitPasses);
	}

	static Result optimize(List<Variable> variables, List<Factor> hardFactors,
		List<Factor> costFactors, List<Variable> localOrder,
		List<List<Variable>> localBlocks, DeferredBlockProvider deferredBlocks,
		StateKeyProvider stateKeys) {
		return optimize(variables, hardFactors, costFactors, localOrder, localBlocks,
			deferredBlocks, stateKeys, 0);
	}

	static Result optimize(List<Variable> variables, List<Factor> hardFactors,
		List<Factor> costFactors, List<Variable> localOrder,
		List<List<Variable>> localBlocks, DeferredBlockProvider deferredBlocks,
		StateKeyProvider stateKeys, int revisitPasses) {
		return optimize(variables, hardFactors, costFactors, localOrder, localBlocks,
			deferredBlocks, stateKeys, revisitPasses, configuredCompaction());
	}

	static Result optimize(List<Variable> variables, List<Factor> hardFactors,
		List<Factor> costFactors, List<Variable> localOrder,
		List<List<Variable>> localBlocks, DeferredBlockProvider deferredBlocks,
		StateKeyProvider stateKeys, int revisitPasses, boolean compact) {
		return optimize(variables, hardFactors, costFactors, localOrder, localBlocks,
			deferredBlocks, stateKeys, revisitPasses, compact, null);
	}

	static Result optimize(List<Variable> variables, List<Factor> hardFactors,
		List<Factor> costFactors, List<Variable> localOrder,
		List<List<Variable>> localBlocks, DeferredBlockProvider deferredBlocks,
		StateKeyProvider stateKeys, int revisitPasses, boolean compact,
		BlockPreparation sharedPreparation) {
		long optimizationStart = System.nanoTime();
		if(revisitPasses < 0 || revisitPasses > 16)
			throw new IllegalArgumentException(
				"LOCAL_REVISIT_PASSES_INVALID|value=" + revisitPasses + "|range=0..16");
		Context context = new Context(variables, hardFactors, costFactors, stateKeys, compact, sharedPreparation);
		List<Integer> order = validateOrder(context, localOrder);
		Objects.requireNonNull(deferredBlocks, "deferredBlocks");
		MutableStatistics statistics = new MutableStatistics();
		int[] assignment = new int[context.variables.size()];
		Arrays.fill(assignment, -1);

		for(int variable : order)
			selectLocalState(context, assignment, variable, statistics);

		List<Integer> violations = violatedHardFactors(context, assignment);
		statistics.initialHardViolations = violations.size();
		repairHardConflicts(context, assignment, statistics);

		LocalBlockOptimizer blockOptimizer =
			new LocalBlockOptimizer(context, assignment, statistics);
		List<int[]> initialBlocks = new ArrayList<>(normalizeBlocks(context, localBlocks));
		// Derive materialization-sensitive blocks from the first complete feasible
		// assignment before solving ordinary neighborhoods. Adding both sets as one
		// batch lets a broader conflict region retire its contained operator blocks
		// before they are compiled and solved. The provider is still reevaluated below
		// because a later local move can expose a new materialization boundary.
		initialBlocks.addAll(normalizeBlocks(context,
			deferredBlocks.localBlocks(Arrays.stream(assignment).boxed().toList())));
		blockOptimizer.optimize(blockOptimizer.addBlocks(initialBlocks));

		while(true) {
			List<Integer> added = blockOptimizer.addBlocks(normalizeBlocks(context,
				deferredBlocks.localBlocks(Arrays.stream(assignment).boxed().toList())));
			if(added.isEmpty())
				break;
			blockOptimizer.optimize(added);
		}

		for(int pass = 0; pass < revisitPasses; pass++) {
			boolean improved = blockOptimizer.revisitDirtyBlocks();
			if(!improved)
				break;
			while(true) {
				List<Integer> added = blockOptimizer.addBlocks(normalizeBlocks(context,
					deferredBlocks.localBlocks(Arrays.stream(assignment).boxed().toList())));
				if(added.isEmpty())
					break;
				blockOptimizer.optimize(added, true);
			}
		}

		violations = violatedHardFactors(context, assignment);
		if(!violations.isEmpty())
			throw new IllegalArgumentException("LOCAL_FINAL_HARD_CONFLICT|factors=" + violations);
		double objective = evaluateCost(context.costFactors, assignment);
		if(!Double.isFinite(objective))
			throw new IllegalArgumentException("LOCAL_FINAL_OBJECTIVE_INVALID|value=" + objective);
		statistics.totalOptimizationNanos = elapsedNanos(optimizationStart);
		return new Result(objective, Arrays.stream(assignment).boxed().toList(),
			statistics.freeze(violations.size()));
	}

	private static PreparedBlock prepareBlock(Context context, int[] block) {
		// Context already owns the variable-to-factor incidence maps.  Reusing them
		// avoids rescanning the complete whole-program factor surface for every local
		// block and revisit.  Preserve canonical factor order by sorting on the stable
		// construction ordinal after the incidence union.
		List<IndexedFactor> hard = incidentFactors(
			context.incidentHard, context.hardFactors.size(), block);
		List<IndexedFactor> cost = incidentFactors(
			context.incidentCost, context.costFactors.size(), block);
		int[] dependencies = java.util.stream.Stream.concat(hard.stream(), cost.stream())
			.flatMapToInt(factor -> Arrays.stream(factor.scope())).distinct().sorted().toArray();
		return new PreparedBlock(block, hard, cost, dependencies);
	}

	/**
	 * Proves that the current block assignment cannot improve without solving the
	 * coupled subproblem. If the current value independently minimizes every incident
	 * additive cost factor, it also minimizes their sum over every legal joint
	 * assignment. This is a lower-bound proof, not a cardinality cutoff.
	 */
	private static boolean isFactorwiseMinimum(Context context, int[] assignment,
		PreparedBlock block, MutableStatistics statistics) {
		boolean[] inBlock = new boolean[context.variables.size()];
		for(int variable : block.variables())
			inBlock[variable] = true;
		for(IndexedFactor factor : block.incidentCost()) {
			double current = evaluate(factor, assignment);
			if(!Double.isFinite(current))
				return false;
			requireNonNegativeCost(current, "LOCAL_COST_FACTOR_INVALID");
			if(current == 0d && hasValidatedZeroFloor(context, factor))
				continue;
			int count = 0;
			for(int variable : factor.scope())
				if(inBlock[variable])
					count++;
			int[] variables = new int[count];
			for(int index = 0, selected = 0; index < factor.scope().length; index++) {
				int variable = factor.scope()[index];
				if(inBlock[variable])
					variables[selected++] = variable;
			}
			int[] saved = new int[variables.length];
			for(int index = 0; index < variables.length; index++)
				saved[index] = assignment[variables[index]];
			try {
				if(hasLowerFactorValue(context, factor, assignment, variables, 0, current,
					statistics))
					return false;
			}
			finally {
				apply(assignment, variables, saved);
			}
		}
		return true;
	}

	private static boolean hasLowerFactorValue(Context context, IndexedFactor factor,
		int[] assignment, int[] variables, int depth, double current,
		MutableStatistics statistics) {
		if(depth == variables.length) {
			statistics.factorwiseMinimumAssignments++;
			double candidate = evaluate(factor, assignment);
			if(candidate == Double.POSITIVE_INFINITY)
				return false;
			requireNonNegativeCost(candidate, "LOCAL_COST_FACTOR_INVALID");
			return Double.compare(candidate, current) < 0;
		}
		int variable = variables[depth];
		for(int value = 0; value < context.variables.get(variable).domainSize(); value++) {
			assignment[variable] = value;
			if(hasLowerFactorValue(context, factor, assignment, variables, depth + 1, current,
				statistics))
				return true;
		}
		return false;
	}

	private static boolean hasValidatedZeroFloor(Context context, IndexedFactor factor) {
		if(context.validatedDenseNonNegativeCostFactors.contains(factor.factor()))
			return true;
		if(context.nonDenseCostFactors.contains(factor.factor()))
			return false;
		if(validateDenseNonNegativeFactor(factor)) {
			context.validatedDenseNonNegativeCostFactors.add(factor.factor());
			return true;
		}
		context.nonDenseCostFactors.add(factor.factor());
		return false;
	}

	private static boolean validateDenseNonNegativeFactor(IndexedFactor indexed) {
		long cells = 1L;
		try {
			for(Variable variable : indexed.factor().scope())
				cells = Math.multiplyExact(cells, variable.domainSize());
		}
		catch(ArithmeticException exception) {
			return false;
		}
		if(cells > Integer.MAX_VALUE)
			return false;
		for(int cell = 0; cell < cells; cell++) {
			double value;
			try {
				value = indexed.factor().denseCostAt(cell);
			}
			catch(IllegalStateException exception) {
				return false;
			}
			if(value == Double.POSITIVE_INFINITY)
				continue;
			requireNonNegativeCost(value, "LOCAL_COST_FACTOR_INVALID");
		}
		return true;
	}

	private static void selectLocalState(Context context, int[] assignment, int variable,
		MutableStatistics statistics) {
		Variable decision = context.variables.get(variable);
		Map<Object,LocalChoice> representatives = new LinkedHashMap<>();
		for(int value = 0; value < decision.domainSize(); value++) {
			statistics.rawLocalAlternatives++;
			assignment[variable] = value;
			LocalChoice candidate = closedIncidentChoice(context, assignment, variable, value);
			Object state = context.stateKeys.stateKey(decision, value);
			LocalChoice prior = representatives.get(state);
			if(prior == null || compare(candidate, prior) < 0)
				representatives.put(state, candidate);
		}
		assignment[variable] = -1;
		statistics.retainedLocalStates += representatives.size();
		statistics.prunedLocalRepresentatives += decision.domainSize() - representatives.size();
		LocalChoice selected = representatives.values().stream().min(LocalCategoricalOptimizer::compare)
			.orElseThrow(() -> new IllegalArgumentException("LOCAL_DOMAIN_EMPTY|variable=" + decision.key()));
		assignment[variable] = selected.value();
	}

	private static LocalChoice closedIncidentChoice(Context context, int[] assignment,
		int variable, int value) {
		int violations = 0;
		for(IndexedFactor factor : context.incidentHard.get(variable)) {
			if(!allAssigned(factor.scope(), assignment))
				continue;
			double cost = evaluate(factor, assignment);
			if(cost == Double.POSITIVE_INFINITY)
				violations++;
			else
				requireNonNegativeCost(cost, "LOCAL_HARD_FACTOR_COST_INVALID");
		}
		double cost = evaluateClosedCost(context.incidentCost.get(variable), assignment);
		return new LocalChoice(value, violations, cost);
	}

	private static void repairHardConflicts(Context context, int[] assignment,
		MutableStatistics statistics) {
		while(true) {
			List<Integer> violated = violatedHardFactors(context, assignment);
			if(violated.isEmpty())
				return;
			Set<Integer> componentFactors = connectedViolatedComponent(context, violated, violated.get(0));
			Set<Integer> variables = new LinkedHashSet<>();
			for(int factor : componentFactors)
				for(int variable : context.hardFactors.get(factor).scope())
					variables.add(variable);
			// Include the immediate hard-factor response region before solving.  A
			// currently satisfied factor can still expose a cheaper simultaneous repair
			// than freezing its external variable and accepting the first coherent fix.
			Set<Integer> response = new LinkedHashSet<>(variables);
			for(IndexedFactor factor : context.hardFactors)
				if(intersects(factor.scope(), variables))
					for(int variable : factor.scope())
						response.add(variable);
			variables = response;
			int before = violated.size();
			BlockSolution solution;
			int[] block;
			while(true) {
				block = variables.stream().sorted().mapToInt(Integer::intValue).toArray();
				solution = solveBlock(context, assignment, block, statistics);
				if(solution != null)
					break;
				boolean expanded = false;
				for(IndexedFactor factor : context.hardFactors) {
					if(!intersects(factor.scope(), variables))
						continue;
					for(int variable : factor.scope())
						expanded |= variables.add(variable);
				}
				if(!expanded)
					throw new IllegalArgumentException("LOCAL_CONFLICT_BLOCK_INFEASIBLE|variables="
						+ variableKeys(context, block));
				statistics.conflictBlockExpansions++;
			}
			apply(assignment, block, solution.valuesInCanonicalBlockOrder());
			statistics.conflictBlocksSolved++;
			recordBlockStatistics(statistics, block.length, solution);
			int after = violatedHardFactors(context, assignment).size();
			if(after >= before)
				throw new IllegalArgumentException("LOCAL_CONFLICT_REPAIR_NO_PROGRESS|before="
					+ before + "|after=" + after + "|variables=" + variableKeys(context, block));
		}
	}

	private static Set<Integer> connectedViolatedComponent(Context context,
		List<Integer> violated, int seed) {
		Set<Integer> violatedSet = new LinkedHashSet<>(violated);
		Set<Integer> factors = new LinkedHashSet<>();
		Set<Integer> variables = new LinkedHashSet<>();
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		queue.add(seed);
		while(!queue.isEmpty()) {
			int current = queue.removeFirst();
			if(!factors.add(current))
				continue;
			for(int variable : context.hardFactors.get(current).scope()) {
				if(!variables.add(variable))
					continue;
				for(IndexedFactor incident : context.incidentHard.get(variable))
					if(violatedSet.contains(incident.ordinal()) && !factors.contains(incident.ordinal()))
						queue.addLast(incident.ordinal());
			}
		}
		return factors;
	}

	private static BlockSolution solveBlock(Context context, int[] assignment, int[] rawBlock,
		MutableStatistics statistics) {
		int[] block = Arrays.stream(rawBlock).distinct().sorted().toArray();
		if(block.length == 0)
			throw new IllegalArgumentException("LOCAL_BLOCK_EMPTY");
		long preparationStart = System.nanoTime();
		PreparedBlock prepared;
		try {
			prepared = prepareBlock(context, block);
		}
		finally {
			statistics.exactBlockPreparationNanos += elapsedNanos(preparationStart);
		}
		return solveBlock(context, assignment, prepared, statistics);
	}

	private static BlockSolution solveBlock(Context context, int[] assignment,
		PreparedBlock block, MutableStatistics statistics) {
		// A singleton block has no internal coupling, so direct state-minimum
		// enumeration is cheaper than constructing a factorized solver.  Multi-variable
		// blocks are solved exactly by local variable elimination; this compares every
		// legal local assignment logically without materializing the Cartesian product.
		if(block.variables().length == 1)
			return solveDirectBlock(context, assignment, block, statistics);
		long preparationStart = System.nanoTime();
		FactorizedBlockSolver solver;
		try {
			solver = new FactorizedBlockSolver(context, assignment, block.variables(),
				block.incidentHard(), block.incidentCost());
		}
		finally {
			statistics.exactBlockPreparationNanos += elapsedNanos(preparationStart);
		}
		long solveStart = System.nanoTime();
		try {
			return solver.solve();
		}
		finally {
			statistics.exactBlockSolveNanos += elapsedNanos(solveStart);
		}
	}

	private static BlockSolution solveDirectBlock(Context context, int[] assignment,
		PreparedBlock block, MutableStatistics statistics) {
		long solveStart = System.nanoTime();
		try {
			return new BlockSearch(context, assignment, block.variables(), block.incidentHard(),
				block.incidentCost()).solve();
		}
		finally {
			statistics.exactBlockSolveNanos += elapsedNanos(solveStart);
		}
	}

	static void tracePreparation(String scope, ExactPhysicalReducedSolver.PreparationStatistics timing) {
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("Exact-PreparationPhases", "scope=" + scope
				+ " freezeNanos=" + timing.freezeNanos() + " supportNanos=" + timing.supportNanos()
				+ " quotientNanos=" + timing.quotientNanos() + " rebuildNanos=" + timing.rebuildNanos()
				+ " compileNanos=" + timing.compileNanos() + " totalNanos=" + timing.totalNanos());
	}

	private static long elapsedNanos(long start) {
		return Math.max(0L, System.nanoTime() - start);
	}

	private static Factor reduceFactor(Context context, int[] assignment,
		Set<Integer> blockVariables, IndexedFactor indexed) {
		int[] globalScope = indexed.scope();
		int[] localPositionByScope = new int[globalScope.length];
		Arrays.fill(localPositionByScope, -1);
		List<Variable> localScope = new ArrayList<>();
		for(int scopePosition = 0; scopePosition < globalScope.length; scopePosition++) {
			int global = globalScope[scopePosition];
			if(blockVariables.contains(global)) {
				localPositionByScope[scopePosition] = localScope.size();
				localScope.add(context.variables.get(global));
			}
		}
		if(localScope.isEmpty())
			throw new IllegalArgumentException("LOCAL_BLOCK_FACTOR_NOT_INCIDENT");
		int[] originalValues = new int[globalScope.length];
		for(int position = 0; position < globalScope.length; position++) {
			if(localPositionByScope[position] >= 0)
				continue;
			originalValues[position] = assignment[globalScope[position]];
			if(originalValues[position] < 0)
				throw new IllegalArgumentException("LOCAL_BLOCK_BOUNDARY_INCOMPLETE");
		}
		// Solve-local and serial: snapshot boundary once; no per-cell allocation.
		return Factor.lazy(localScope, localValues -> {
			for(int scopePosition = 0; scopePosition < originalValues.length; scopePosition++) {
				int local = localPositionByScope[scopePosition];
				if(local >= 0)
					originalValues[scopePosition] = localValues[local];

			}
			return indexed.factor().cost(originalValues);
		});
	}

	private static List<IndexedFactor> incidentFactors(
		List<List<IndexedFactor>> incidence, int factorCount, int[] block) {
		boolean[] retained = new boolean[factorCount];
		List<IndexedFactor> factors = new ArrayList<>();
		for(int variable : block)
			for(IndexedFactor factor : incidence.get(variable))
				if(!retained[factor.ordinal()]) {
					retained[factor.ordinal()] = true;
					factors.add(factor);
				}
		factors.sort(Comparator.comparingInt(IndexedFactor::ordinal));
		return List.copyOf(factors);
	}

	private static boolean hardFactorsSatisfiedWhenClosed(List<IndexedFactor> factors,
		int[] assignment) {
		for(IndexedFactor factor : factors) {
			if(!allAssigned(factor.scope(), assignment))
				continue;
			double cost = evaluate(factor, assignment);
			if(cost == Double.POSITIVE_INFINITY)
				return false;
			requireNonNegativeCost(cost, "LOCAL_HARD_FACTOR_COST_INVALID");
		}
		return true;
	}

	private static List<Integer> violatedHardFactors(Context context, int[] assignment) {
		List<Integer> violated = new ArrayList<>();
		for(IndexedFactor factor : context.hardFactors) {
			double cost = evaluate(factor, assignment);
			if(cost == Double.POSITIVE_INFINITY)
				violated.add(factor.ordinal());
			else
				requireNonNegativeCost(cost, "LOCAL_HARD_FACTOR_COST_INVALID");
		}
		return violated;
	}

	private static double evaluateClosedCost(List<IndexedFactor> factors, int[] assignment) {
		ExactCompensatedCostSum total = new ExactCompensatedCostSum();
		for(IndexedFactor factor : factors) {
			if(!allAssigned(factor.scope(), assignment))
				continue;
			double cost = evaluate(factor, assignment);
			if(cost == Double.POSITIVE_INFINITY)
				return cost;
			total.addBits(Double.doubleToRawLongBits(cost), "LOCAL_COST_FACTOR_INVALID",
				"LOCAL_COST_TOTAL_INVALID");
		}
		return Double.longBitsToDouble(total.totalBits("LOCAL_COST_TOTAL_INVALID"));
	}

	private static double evaluateCost(List<IndexedFactor> factors, int[] assignment) {
		ExactCompensatedCostSum total = new ExactCompensatedCostSum();
		for(IndexedFactor factor : factors) {
			double cost = evaluate(factor, assignment);
			if(cost == Double.POSITIVE_INFINITY)
				return cost;
			total.addBits(Double.doubleToRawLongBits(cost), "LOCAL_COST_FACTOR_INVALID",
				"LOCAL_COST_TOTAL_INVALID");
		}
		return Double.longBitsToDouble(total.totalBits("LOCAL_COST_TOTAL_INVALID"));
	}

	private static double evaluate(IndexedFactor factor, int[] assignment) {
		int[] local = new int[factor.scope().length];
		for(int index = 0; index < local.length; index++) {
			int value = assignment[factor.scope()[index]];
			if(value < 0)
				throw new IllegalArgumentException("LOCAL_FACTOR_ASSIGNMENT_INCOMPLETE");
			local[index] = value;
		}
		return factor.factor().cost(local);
	}

	private static void requireNonNegativeCost(double cost, String reason) {
		long bits = Double.doubleToRawLongBits(cost);
		if(!Double.isFinite(cost) || cost < 0d
			|| bits == Double.doubleToRawLongBits(-0d))
			throw new IllegalArgumentException(reason + "|value=" + cost);
	}

	private static boolean allAssigned(int[] scope, int[] assignment) {
		for(int variable : scope)
			if(assignment[variable] < 0)
				return false;
		return true;
	}

	private static int[] orderForSearch(Context context, int[] block) {
		return Arrays.stream(block).boxed().sorted(Comparator
			.<Integer>comparingInt(variable -> -context.incidentHard.get(variable).size())
			.thenComparingInt(variable -> context.variables.get(variable).domainSize())
			.thenComparingInt(Integer::intValue)).mapToInt(Integer::intValue).toArray();
	}

	private static boolean hasDuplicateStateKeys(Context context, int[] block) {
		for(int variable : block) {
			Set<Object> keys = new LinkedHashSet<>();
			Variable decision = context.variables.get(variable);
			for(int value = 0; value < decision.domainSize(); value++)
				if(!keys.add(context.stateKeys.stateKey(decision, value)))
					return true;
		}
		return false;
	}

	private static int compare(LocalChoice left, LocalChoice right) {
		int comparison = Integer.compare(left.hardViolations(), right.hardViolations());
		if(comparison != 0)
			return comparison;
		comparison = Double.compare(left.cost(), right.cost());
		return comparison != 0 ? comparison : Integer.compare(left.value(), right.value());
	}

	private static int compare(BlockCandidate left, BlockCandidate right) {
		int comparison = Double.compare(left.cost(), right.cost());
		if(comparison != 0)
			return comparison;
		for(int index = 0; index < left.values().length; index++) {
			comparison = Integer.compare(left.values()[index], right.values()[index]);
			if(comparison != 0)
				return comparison;
		}
		return 0;
	}

	private static void apply(int[] assignment, int[] block, int[] values) {
		if(block.length != values.length)
			throw new IllegalArgumentException("LOCAL_BLOCK_ASSIGNMENT_SIZE_MISMATCH");
		for(int index = 0; index < block.length; index++)
			assignment[block[index]] = values[index];
	}

	private static int[] values(int[] assignment, int[] variables) {
		int[] values = new int[variables.length];
		for(int index = 0; index < variables.length; index++)
			values[index] = assignment[variables[index]];
		return values;
	}

	private static List<Integer> validateOrder(Context context, List<Variable> localOrder) {
		Objects.requireNonNull(localOrder, "localOrder");
		List<Integer> order = new ArrayList<>(localOrder.size());
		Set<Integer> unique = new LinkedHashSet<>();
		for(Variable variable : localOrder) {
			Integer position = context.positions.get(variable);
			if(position == null || !unique.add(position))
				throw new IllegalArgumentException("LOCAL_ORDER_INVALID");
			order.add(position);
		}
		if(order.size() != context.variables.size())
			throw new IllegalArgumentException("LOCAL_ORDER_INCOMPLETE");
		return List.copyOf(order);
	}

	private static List<int[]> normalizeBlocks(Context context,
		List<List<Variable>> localBlocks) {
		Objects.requireNonNull(localBlocks, "localBlocks");
		List<Set<Integer>> blocks = new ArrayList<>();
		for(List<Variable> raw : localBlocks) {
			Set<Integer> block = new LinkedHashSet<>();
			for(Variable variable : raw) {
				Integer position = context.positions.get(variable);
				if(position == null)
					throw new IllegalArgumentException("LOCAL_INTERACTION_BLOCK_FOREIGN_VARIABLE");
				block.add(position);
			}
			if(block.size() > 1)
				blocks.add(block);
		}
		List<int[]> normalized = new ArrayList<>(blocks.size());
		for(Set<Integer> block : blocks) {
			int[] values = block.stream().sorted().mapToInt(Integer::intValue).toArray();
			if(normalized.stream().noneMatch(prior -> Arrays.equals(prior, values)))
				normalized.add(values);
		}
		return List.copyOf(normalized);
	}

	private static boolean containsAll(int[] superset, int[] subset) {
		if(superset.length < subset.length)
			return false;
		int outer = 0;
		for(int value : subset) {
			while(outer < superset.length && superset[outer] < value)
				outer++;
			if(outer == superset.length || superset[outer] != value)
				return false;
		}
		return true;
	}

	private static void recordBlockStatistics(MutableStatistics statistics, int variables,
		BlockSolution solution) {
		statistics.maximumBlockVariables = Math.max(statistics.maximumBlockVariables, variables);
		statistics.maximumBlockAssignments = Math.max(statistics.maximumBlockAssignments,
			solution.searchAssignments());
		statistics.blockAssignments = saturatedAdd(statistics.blockAssignments,
			solution.searchAssignments());
	}

	private static long saturatedAdd(long left, long right) {
		return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
	}

	private static boolean intersects(int[] scope, Set<Integer> variables) {
		for(int variable : scope)
			if(variables.contains(variable))
				return true;
		return false;
	}

	private static String variableKeys(Context context, int[] block) {
		return Arrays.stream(block).mapToObj(index -> context.variables.get(index).key()).toList().toString();
	}

	private static List<List<IndexedFactor>> emptyIncidence(int size) {
		List<List<IndexedFactor>> result = new ArrayList<>(size);
		for(int index = 0; index < size; index++)
			result.add(new ArrayList<>());
		return result;
	}
}
