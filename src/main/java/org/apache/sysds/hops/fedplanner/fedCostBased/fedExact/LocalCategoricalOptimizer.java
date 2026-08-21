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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/**
 * Deterministic local min-sum optimization over the shared categorical factor model.
 *
 * <p>The solver performs one ordered local pass, repairs connected hard-factor
 * conflicts by optimizing only their incident variable blocks, performs one legal
 * coordinate pass, and finally optimizes caller-supplied shared-producer blocks.
 * It never truncates a frontier by cardinality: within a complete state key it
 * retains the minimum-cost representative, while distinct state keys remain
 * incomparable.</p>
 */
final class LocalCategoricalOptimizer {
	@FunctionalInterface
	interface StateKeyProvider {
		Object stateKey(Variable variable, int value);
	}

	record Statistics(long rawLocalAlternatives, long retainedLocalStates,
		long prunedLocalRepresentatives, int initialHardViolations,
		int finalHardViolations, int conflictBlocksSolved, int conflictBlockExpansions,
		int coordinateBlocksSolved, int sharedBlocksSolved, int maximumBlockVariables,
		long maximumBlockAssignments, long blockAssignments) { }

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
		long completeAssignments, long distinctStates, long prunedRepresentatives) { }
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
		int coordinateBlocksSolved;
		int sharedBlocksSolved;
		int maximumBlockVariables;
		long maximumBlockAssignments;
		long blockAssignments;

		Statistics freeze(int finalHardViolations) {
			return new Statistics(rawLocalAlternatives, retainedLocalStates,
				prunedLocalRepresentatives, initialHardViolations, finalHardViolations,
				conflictBlocksSolved, conflictBlockExpansions, coordinateBlocksSolved,
				sharedBlocksSolved, maximumBlockVariables, maximumBlockAssignments,
				blockAssignments);
		}
	}

	private static final class Context {
		final List<Variable> variables;
		final List<IndexedFactor> hardFactors;
		final List<IndexedFactor> costFactors;
		final List<List<IndexedFactor>> incidentHard;
		final List<List<IndexedFactor>> incidentCost;
		final IdentityHashMap<Variable,Integer> positions;
		final StateKeyProvider stateKeys;

		Context(List<Variable> variables, List<Factor> hardFactors,
			List<Factor> costFactors, StateKeyProvider stateKeys) {
			this.variables = List.copyOf(Objects.requireNonNull(variables, "variables"));
			this.stateKeys = Objects.requireNonNull(stateKeys, "stateKeys");
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
		long prunedRepresentatives;

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
				return new BlockSolution(best.values().clone(), best.cost(), completeAssignments,
					duplicateStateKeys ? representatives.size() : completeAssignments,
					prunedRepresentatives);
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
				if(prior != null)
					prunedRepresentatives++;
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

	private LocalCategoricalOptimizer() { }

	static Result optimize(List<Variable> variables, List<Factor> hardFactors,
		List<Factor> costFactors, List<Variable> localOrder,
		List<List<Variable>> sharedBlocks, StateKeyProvider stateKeys) {
		Context context = new Context(variables, hardFactors, costFactors, stateKeys);
		List<Integer> order = validateOrder(context, localOrder);
		List<int[]> blocks = normalizeBlocks(context, sharedBlocks);
		MutableStatistics statistics = new MutableStatistics();
		int[] assignment = new int[context.variables.size()];
		Arrays.fill(assignment, -1);

		for(int variable : order)
			selectLocalState(context, assignment, variable, statistics);

		List<Integer> violations = violatedHardFactors(context, assignment);
		statistics.initialHardViolations = violations.size();
		repairHardConflicts(context, assignment, statistics);

		for(int index = order.size() - 1; index >= 0; index--) {
			int variable = order.get(index);
			BlockSolution solution = solveBlock(context, assignment, new int[] {variable});
			if(solution == null)
				throw new IllegalArgumentException("LOCAL_COORDINATE_HAS_NO_LEGAL_STATE|variable="
					+ context.variables.get(variable).key());
			apply(assignment, new int[] {variable}, solution.valuesInCanonicalBlockOrder());
			statistics.coordinateBlocksSolved++;
			recordBlockStatistics(statistics, 1, solution);
		}

		for(int[] block : blocks) {
			BlockSolution solution = solveBlock(context, assignment, block);
			if(solution == null)
				throw new IllegalArgumentException("LOCAL_SHARED_BLOCK_HAS_NO_LEGAL_ASSIGNMENT|variables="
					+ variableKeys(context, block));
			apply(assignment, block, solution.valuesInCanonicalBlockOrder());
			statistics.sharedBlocksSolved++;
			recordBlockStatistics(statistics, block.length, solution);
		}

		violations = violatedHardFactors(context, assignment);
		if(!violations.isEmpty())
			throw new IllegalArgumentException("LOCAL_FINAL_HARD_CONFLICT|factors=" + violations);
		double objective = evaluateCost(context.costFactors, assignment);
		if(!Double.isFinite(objective))
			throw new IllegalArgumentException("LOCAL_FINAL_OBJECTIVE_INVALID|value=" + objective);
		return new Result(objective, Arrays.stream(assignment).boxed().toList(),
			statistics.freeze(violations.size()));
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
				solution = solveBlock(context, assignment, block);
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

	private static BlockSolution solveBlock(Context context, int[] assignment, int[] rawBlock) {
		int[] block = Arrays.stream(rawBlock).distinct().sorted().toArray();
		if(block.length == 0)
			throw new IllegalArgumentException("LOCAL_BLOCK_EMPTY");
		List<IndexedFactor> hard = incidentFactors(context.hardFactors, block);
		List<IndexedFactor> cost = incidentFactors(context.costFactors, block);
		return new BlockSearch(context, assignment, block, hard, cost).solve();
	}

	private static List<IndexedFactor> incidentFactors(List<IndexedFactor> factors, int[] block) {
		Set<Integer> variables = new LinkedHashSet<>();
		for(int variable : block)
			variables.add(variable);
		return factors.stream().filter(factor -> intersects(factor.scope(), variables)).toList();
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
		List<List<Variable>> sharedBlocks) {
		Objects.requireNonNull(sharedBlocks, "sharedBlocks");
		List<Set<Integer>> blocks = new ArrayList<>();
		for(List<Variable> raw : sharedBlocks) {
			Set<Integer> block = new LinkedHashSet<>();
			for(Variable variable : raw) {
				Integer position = context.positions.get(variable);
				if(position == null)
					throw new IllegalArgumentException("LOCAL_SHARED_BLOCK_FOREIGN_VARIABLE");
				block.add(position);
			}
			if(block.size() > 1)
				blocks.add(block);
		}
		boolean merged;
		do {
			merged = false;
			outer:
			for(int left = 0; left < blocks.size(); left++)
				for(int right = left + 1; right < blocks.size(); right++)
					if(!Collections.disjoint(blocks.get(left), blocks.get(right))) {
						blocks.get(left).addAll(blocks.remove(right));
						merged = true;
						break outer;
					}
		}
		while(merged);
		return blocks.stream().map(block -> block.stream().sorted()
			.mapToInt(Integer::intValue).toArray()).sorted(LocalCategoricalOptimizer::compareBlocks)
			.toList();
	}

	private static int compareBlocks(int[] left, int[] right) {
		for(int index = 0; index < Math.min(left.length, right.length); index++) {
			int comparison = Integer.compare(left[index], right[index]);
			if(comparison != 0)
				return comparison;
		}
		return Integer.compare(left.length, right.length);
	}

	private static void recordBlockStatistics(MutableStatistics statistics, int variables,
		BlockSolution solution) {
		statistics.maximumBlockVariables = Math.max(statistics.maximumBlockVariables, variables);
		statistics.maximumBlockAssignments = Math.max(statistics.maximumBlockAssignments,
			solution.completeAssignments());
		statistics.blockAssignments = saturatedAdd(statistics.blockAssignments,
			solution.completeAssignments());
		statistics.prunedLocalRepresentatives = saturatedAdd(
			statistics.prunedLocalRepresentatives, solution.prunedRepresentatives());
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
