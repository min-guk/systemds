/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.ToDoubleFunction;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/** Shared original-model authority and conditioning for the three certified searches.
 * Nonnegative factors are the same caller contract as CertifiedRegionalOptimizer.
 * Arbitrary canonical-evaluator / auxiliary-encoding pairs are deliberately not exposed.
 */
final class RegionalSearchProblem {
	private final List<Variable> variables;
	private final List<Factor> factors;
	private final int decisionCount;
	private final ToDoubleFunction<List<Integer>> evaluator;
	private final IdentityHashMap<Variable,Integer> positions = new IdentityHashMap<>();
	private final List<int[]> scopes = new ArrayList<>();
	private final List<List<Integer>> incidence = new ArrayList<>();
	private final String orderFingerprint;
	private final boolean compactPreparation;
	private PendingPreparation pendingPreparation;

	record Conditional(List<Variable> variables, List<Factor> factors,
		int originalFreeCount, List<Integer> freeIndexes, int[] fixed) { }
	record Solution(boolean feasible, double objective, List<Integer> assignment,
		ExactCategoricalSolver.Statistics statistics) {
		Solution { assignment = List.copyOf(assignment); }
	}
	record RegionalWork(boolean admitted, boolean hardResourceLimited,
		long eliminationAssignments, long materializedFactorCells,
		long maximumFactorCells, long preparationNanos) {
		RegionalWork(boolean admitted, boolean hardResourceLimited,
			long eliminationAssignments, long materializedFactorCells,
			long maximumFactorCells) {
			this(admitted, hardResourceLimited, eliminationAssignments,
				materializedFactorCells, maximumFactorCells, 0L);
		}
	}
	private record PreparationKey(String kind, List<Integer> fixed,
		List<Integer> region, List<Integer> reference, Limits limits) { }
	private record PendingPreparation(PreparationKey key, Conditional conditional,
		ExactPhysicalReducedSolver.Prepared prepared) { }

	static RegionalSearchProblem generic(List<Variable> variables, List<Factor> factors) {
		return new RegionalSearchProblem(variables, factors, variables.size(),
			assignment -> CertifiedRegionalOptimizer.evaluate(variables, factors, assignment));
	}

	static RegionalSearchProblem physical(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface, ExactPhysicalForcedStateAudit.Constraint forced) {
		if(surface.owner() != model.analysis()
			|| !surface.ownerFingerprint().equals(model.analysis().analysisFingerprint())
			|| surface.variables().size() != model.variables().size()
			|| surface.exactSolverVariables().size() < model.variables().size())
			throw new IllegalArgumentException("REGIONAL_SEARCH_PHYSICAL_SURFACE_MISMATCH");
		for(int i = 0; i < model.variables().size(); i++)
			if(surface.variables().get(i) != model.variables().get(i)
				|| surface.exactSolverVariables().get(i) != model.variables().get(i))
				throw new IllegalArgumentException("REGIONAL_SEARCH_VARIABLE_IDENTITY_MISMATCH");
		List<Factor> hard = new ArrayList<>(model.hardFactors());
		if(forced != null)
			hard.add(forced.factor());
		List<Factor> encoded = new ArrayList<>(hard);
		encoded.addAll(surface.exactSolverFactors());
		return new RegionalSearchProblem(surface.exactSolverVariables(), encoded, model.variables().size(),
			assignment -> Double.isFinite(CertifiedRegionalOptimizer.evaluate(model.variables(), hard, assignment))
				? Double.longBitsToDouble(surface.evaluateCanonical(assignment)) : Double.POSITIVE_INFINITY);
	}

	private RegionalSearchProblem(List<Variable> variables, List<Factor> factors, int decisionCount,
		ToDoubleFunction<List<Integer>> evaluator) {
		this(variables, factors, decisionCount, evaluator, false);
	}

	private RegionalSearchProblem(List<Variable> variables, List<Factor> factors, int decisionCount,
		ToDoubleFunction<List<Integer>> evaluator, boolean compactPreparation) {
		this.variables = List.copyOf(variables);
		this.factors = List.copyOf(factors);
		this.decisionCount = decisionCount;
		this.evaluator = evaluator;
		this.compactPreparation = compactPreparation;
		Set<String> keys = new LinkedHashSet<>();
		StringBuilder identity = new StringBuilder();
		for(int i = 0; i < variables.size(); i++) {
			Variable variable = variables.get(i);
			if(positions.put(variable, i) != null || !keys.add(variable.key()))
				throw new IllegalArgumentException("REGIONAL_SEARCH_DUPLICATE_VARIABLE");
			incidence.add(new ArrayList<>());
			identity.append(variable.key().length()).append(':').append(variable.key())
				.append(':').append(variable.domainSize()).append(';');
		}
		for(Factor factor : factors) {
			int[] scope = new int[factor.scope().size()];
			Set<Integer> unique = new LinkedHashSet<>();
			for(int j = 0; j < scope.length; j++) {
				Integer index = positions.get(factor.scope().get(j));
				if(index == null || !unique.add(index))
					throw new IllegalArgumentException("REGIONAL_SEARCH_FACTOR_SCOPE_INVALID");
				scope[j] = index;
				incidence.get(index).add(scopes.size());
			}
			scopes.add(scope);
			identity.append(Arrays.toString(scope));
		}
		try {
			orderFingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(identity.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}

	List<Variable> variables() { return variables; }
	List<Factor> factors() { return factors; }
	int decisionCount() { return decisionCount; }
	int domainSize(int decision) { return variables.get(decision).domainSize(); }
	int indexOf(Variable variable) { return positions.getOrDefault(variable, -1); }
	String orderFingerprint() { return orderFingerprint; }
	/** Whole original model only: conditional/Regional optima are not global lower bounds. */
	ExactPhysicalReducedSolver.CompactModel compactRoot(Limits limits) {
		return ExactPhysicalReducedSolver.compactModel(decisionCount, variables, factors, limits);
	}
	RegionalSearchProblem usingCompactedPreparation(boolean enabled) {
		// Each mode has its own pending prepared solve. Original factor identities,
		// ordering and the canonical evaluator remain the same.
		return compactPreparation == enabled ? this
			: new RegionalSearchProblem(variables, factors, decisionCount, evaluator, enabled);
	}
	int[] unconstrained() {
		int[] fixed = new int[decisionCount];
		Arrays.fill(fixed, -1);
		return fixed;
	}

	void checkCondition(int[] fixed) {
		if(fixed.length != decisionCount)
			throw new IllegalArgumentException("REGIONAL_SEARCH_CONDITION_SIZE_INVALID");
		for(int i = 0; i < fixed.length; i++)
			if(fixed[i] < -1 || fixed[i] >= domainSize(i))
				throw new IllegalArgumentException("REGIONAL_SEARCH_CONDITION_VALUE_INVALID");
	}
	boolean matches(int[] fixed, List<Integer> assignment) {
		checkCondition(fixed);
		if(assignment.size() != decisionCount)
			return false;
		for(int i = 0; i < fixed.length; i++)
			if(fixed[i] >= 0 && fixed[i] != assignment.get(i))
				return false;
		return true;
	}

	double evaluate(List<Integer> assignment) {
		if(assignment == null || assignment.size() != decisionCount)
			throw new IllegalArgumentException("REGIONAL_SEARCH_ASSIGNMENT_SIZE_INVALID");
		for(int i = 0; i < assignment.size(); i++)
			if(assignment.get(i) == null || assignment.get(i) < 0 || assignment.get(i) >= domainSize(i))
				throw new IllegalArgumentException("REGIONAL_SEARCH_ASSIGNMENT_VALUE_INVALID");
		double cost = evaluator.applyAsDouble(assignment);
		if(!Double.isFinite(cost) || cost < 0 || Double.doubleToRawLongBits(cost) == Long.MIN_VALUE)
			throw new IllegalArgumentException("REGIONAL_SEARCH_PLAN_NOT_FEASIBLE|cost=" + cost);
		return cost;
	}

	/** Only the specified originals are fixed. All auxiliaries and constants are retained. */
	Conditional condition(int[] fixed) {
		checkCondition(fixed);
		int[] snapshot = fixed.clone();
		List<Integer> freeIndexes = new ArrayList<>();
		for(int i = 0; i < decisionCount; i++)
			if(snapshot[i] < 0)
				freeIndexes.add(i);
		int originalFree = freeIndexes.size();
		for(int i = decisionCount; i < variables.size(); i++)
			freeIndexes.add(i);
		List<Variable> free = freeIndexes.stream().map(variables::get).toList();
		List<Factor> reduced = new ArrayList<>();
		for(int f = 0; f < factors.size(); f++) {
			Factor source = factors.get(f);
			int[] scope = scopes.get(f);
			int[] mapping = new int[scope.length];
			int[] values = new int[scope.length];
			List<Variable> localFree = new ArrayList<>();
			for(int j = 0; j < scope.length; j++) {
				int variable = scope[j];
				if(variable >= decisionCount || snapshot[variable] < 0) {
					mapping[j] = localFree.size();
					localFree.add(variables.get(variable));
				}
				else {
					mapping[j] = -1;
					values[j] = snapshot[variable];
				}
			}
			reduced.add(Factor.lazy(localFree, local -> {
				int[] full = values.clone();
				for(int j = 0; j < full.length; j++)
					if(mapping[j] >= 0)
						full[j] = local[mapping[j]];
				return source.cost(full);
			}));
		}
		return new Conditional(free, List.copyOf(reduced), originalFree, List.copyOf(freeIndexes), snapshot);
	}

	MiniBucketLowerBound.Result bound(int[] fixed, int width, Limits limits, BooleanSupplier cancelled) {
		Conditional conditional = condition(fixed);
		return MiniBucketLowerBound.compute(conditional.variables(), conditional.factors(), width, limits, cancelled);
	}

	boolean canSolveWhole(int[] fixed, Limits limits, long maximumAssignments) {
		pendingPreparation = null;
		Conditional conditional = condition(fixed);
		PreparationKey key = wholeKey(fixed, limits);
		try {
			ExactPhysicalReducedSolver.Prepared prepared = prepare(conditional, limits);
			boolean admitted = prepared.statistics().eliminationAssignments() <= maximumAssignments;
			if(admitted)
				pendingPreparation = new PendingPreparation(key, conditional, prepared);
			return admitted;
		}
		catch(IllegalArgumentException failure) {
			if(isResourceLimit(failure))
				return false;
			throw failure;
		}
	}
	RegionalWork preflightWhole(int[] fixed, Limits limits, long maximumAssignments,
		BooleanSupplier cancelled) {
		pendingPreparation = null;
		if(maximumAssignments < 0)
			throw new IllegalArgumentException("REGIONAL_SEARCH_WORK_LIMIT_INVALID");
		if(cancelled.getAsBoolean())
			throw new CancellationException("REGIONAL_SEARCH_CANCELLED_BEFORE_PREFLIGHT");
		Conditional conditional = condition(fixed);
		PreparationKey key = wholeKey(fixed, limits);
		long start = System.nanoTime();
		try {
			ExactPhysicalReducedSolver.Prepared prepared = prepare(conditional, limits);
			ExactCategoricalSolver.Statistics statistics = prepared.statistics();
			if(cancelled.getAsBoolean())
				throw new CancellationException("REGIONAL_SEARCH_CANCELLED_AFTER_PREFLIGHT");
			boolean admitted = statistics.eliminationAssignments() <= maximumAssignments;
			if(admitted)
				pendingPreparation = new PendingPreparation(key, conditional, prepared);
			return new RegionalWork(admitted,
				false, statistics.eliminationAssignments(), statistics.materializedFactorCells(),
				statistics.maximumFactorCells(), System.nanoTime() - start);
		}
		catch(IllegalArgumentException failure) {
			if(!isResourceLimit(failure))
				throw failure;
			return new RegionalWork(false, true, 0L, 0L, 0L, System.nanoTime() - start);
		}
	}

	Solution solveWhole(int[] fixed, Limits limits, BooleanSupplier cancelled) {
		PreparationKey key = wholeKey(fixed, limits);
		PendingPreparation pending = takePreparation(key);
		return pending == null ? solve(condition(fixed), limits, cancelled)
			: solve(pending.conditional(), pending.prepared(), cancelled);
	}
	Solution solveRegion(int[] fixed, Set<Integer> region, List<Integer> reference,
		Limits limits, BooleanSupplier cancelled) {
		PreparationKey key = regionKey(fixed, region, reference, limits);
		PendingPreparation pending = takePreparation(key);
		return pending == null ? solve(regionalConditional(fixed, region, reference), limits, cancelled)
			: solve(pending.conditional(), pending.prepared(), cancelled);
	}
	RegionalWork preflightRegion(int[] fixed, Set<Integer> region, List<Integer> reference,
		Limits limits, long maximumAssignments, BooleanSupplier cancelled) {
		pendingPreparation = null;
		if(maximumAssignments < 0)
			throw new IllegalArgumentException("REGIONAL_SEARCH_WORK_LIMIT_INVALID");
		if(cancelled.getAsBoolean())
			throw new CancellationException("REGIONAL_SEARCH_CANCELLED_BEFORE_PREFLIGHT");
		Conditional conditional = regionalConditional(fixed, region, reference);
		PreparationKey key = regionKey(fixed, region, reference, limits);
		long start = System.nanoTime();
		try {
			ExactPhysicalReducedSolver.Prepared prepared = prepare(conditional, limits);
			ExactCategoricalSolver.Statistics statistics = prepared.statistics();
			if(cancelled.getAsBoolean())
				throw new CancellationException("REGIONAL_SEARCH_CANCELLED_AFTER_PREFLIGHT");
			boolean admitted = statistics.eliminationAssignments() <= maximumAssignments;
			if(admitted)
				pendingPreparation = new PendingPreparation(key, conditional, prepared);
			return new RegionalWork(admitted,
				false, statistics.eliminationAssignments(), statistics.materializedFactorCells(),
				statistics.maximumFactorCells(), System.nanoTime() - start);
		}
		catch(IllegalArgumentException failure) {
			if(!isResourceLimit(failure))
				throw failure;
			return new RegionalWork(false, true, 0L, 0L, 0L, System.nanoTime() - start);
		}
	}
	private Conditional regionalConditional(int[] fixed, Set<Integer> region,
		List<Integer> reference) {
		checkCondition(fixed);
		evaluate(reference);
		for(int variable : region)
			if(variable < 0 || variable >= decisionCount)
				throw new IllegalArgumentException("REGIONAL_SEARCH_REGION_INVALID");
		int[] boundary = fixed.clone();
		for(int i = 0; i < decisionCount; i++) {
			if(fixed[i] >= 0 && !region.contains(i))
				throw new IllegalArgumentException("REGIONAL_SEARCH_BRANCH_OUTSIDE_REGION");
			if(!region.contains(i))
				boundary[i] = reference.get(i);
		}
		return condition(boundary);
	}
	private Solution solve(Conditional conditional, Limits limits, BooleanSupplier cancelled) {
		if(cancelled.getAsBoolean())
			throw new CancellationException("REGIONAL_SEARCH_CANCELLED_BEFORE_EXACT");
		ExactPhysicalReducedSolver.Prepared prepared = prepare(conditional, limits);
		if(cancelled.getAsBoolean())
			throw new CancellationException("REGIONAL_SEARCH_CANCELLED_AFTER_EXACT_PREPARATION");
		return solve(conditional, prepared, cancelled);
	}

	private Solution solve(Conditional conditional, ExactPhysicalReducedSolver.Prepared prepared,
		BooleanSupplier cancelled) {
		if(cancelled.getAsBoolean())
			throw new CancellationException("REGIONAL_SEARCH_CANCELLED_BEFORE_EXACT");
		ExactCategoricalSolver.Result solved;
		try {
			solved = ExactPhysicalReducedSolver.solve(prepared);
		}
		catch(IllegalArgumentException failure) {
			if("EXACT_VE_NO_FEASIBLE_ASSIGNMENT".equals(failure.getMessage()))
				return new Solution(false, Double.POSITIVE_INFINITY, List.of(), null);
			throw failure;
		}
		List<Integer> assignment = new ArrayList<>();
		for(int value : conditional.fixed())
			assignment.add(value);
		for(int i = 0; i < conditional.originalFreeCount(); i++)
			assignment.set(conditional.freeIndexes().get(i), solved.assignmentInVariableOrder().get(i));
		double canonical = evaluate(assignment);
		if(Double.doubleToRawLongBits(canonical) != Double.doubleToRawLongBits(solved.objective()))
			throw new IllegalStateException("REGIONAL_SEARCH_CANONICAL_MISMATCH|solver="
				+ solved.objective() + "|canonical=" + canonical);
		return new Solution(true, canonical, assignment, solved.statistics());
	}

	private ExactPhysicalReducedSolver.Prepared prepare(Conditional conditional, Limits limits) {
		return compactPreparation
			? ExactPhysicalReducedSolver.prepareCompacted(conditional.originalFreeCount(),
				conditional.variables(), conditional.factors(), limits)
			: ExactPhysicalReducedSolver.prepare(conditional.originalFreeCount(),
				conditional.variables(), conditional.factors(), limits);
	}

	private PendingPreparation takePreparation(PreparationKey key) {
		PendingPreparation pending = pendingPreparation;
		pendingPreparation = null;
		return pending != null && pending.key().equals(key) ? pending : null;
	}

	private static PreparationKey wholeKey(int[] fixed, Limits limits) {
		return new PreparationKey("WHOLE", integers(fixed), List.of(), List.of(), limits);
	}

	private static PreparationKey regionKey(int[] fixed, Set<Integer> region,
		List<Integer> reference, Limits limits) {
		return new PreparationKey("REGION", integers(fixed), region.stream().sorted().toList(),
			List.copyOf(reference), limits);
	}

	private static List<Integer> integers(int[] values) {
		return Arrays.stream(values).boxed().toList();
	}

	List<Integer> rankedDecisions(int[] fixed, List<MiniBucketLowerBound.Conflict> conflicts) {
		checkCondition(fixed);
		Set<Integer> ranked = new LinkedHashSet<>();
		conflicts.stream().sorted(Comparator.comparingDouble(MiniBucketLowerBound.Conflict::disagreement)
			.reversed().thenComparing(conflict -> conflict.variable().key())).forEach(conflict -> {
				int index = indexOf(conflict.variable());
				if(index >= 0 && index < decisionCount && fixed[index] < 0)
					ranked.add(index);
				for(Variable variable : conflict.scope()) {
					index = indexOf(variable);
					if(index >= 0 && index < decisionCount && fixed[index] < 0)
						ranked.add(index);
				}
			});
		for(int i = 0; i < decisionCount; i++)
			if(fixed[i] < 0)
				ranked.add(i);
		return List.copyOf(ranked);
	}

	void growRegion(Set<Integer> region, int target, List<MiniBucketLowerBound.Conflict> conflicts) {
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		conflicts.stream().sorted(Comparator.comparingDouble(MiniBucketLowerBound.Conflict::disagreement)
			.reversed().thenComparing(conflict -> conflict.variable().key())).forEach(conflict -> {
				int index = indexOf(conflict.variable());
				if(index >= 0)
					queue.add(index);
				for(Variable variable : conflict.scope())
					if(indexOf(variable) >= 0)
						queue.add(indexOf(variable));
			});
		queue.addAll(region);
		boolean[] seen = new boolean[variables.size()];
		boolean[] factorSeen = new boolean[factors.size()];
		int next = 0;
		while(region.size() < Math.min(target, decisionCount)) {
			if(queue.isEmpty()) {
				while(next < decisionCount && seen[next])
					next++;
				if(next == decisionCount)
					break;
				queue.add(next);
			}
			int variable = queue.remove();
			if(seen[variable])
				continue;
			seen[variable] = true;
			if(variable < decisionCount)
				region.add(variable);
			for(int factor : incidence.get(variable))
				if(!factorSeen[factor]) {
					factorSeen[factor] = true;
					for(int neighbor : scopes.get(factor))
						if(!seen[neighbor])
							queue.add(neighbor);
				}
		}
	}

	static boolean isResourceLimit(IllegalArgumentException failure) {
		String message = failure.getMessage();
		return message != null && (message.startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED")
			|| message.startsWith("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED")
			|| message.startsWith("EXACT_VE_FACTOR_CELL_OVERFLOW")
			|| message.startsWith("EXACT_VE_MATERIALIZED_CELL_OVERFLOW")
			|| message.startsWith("EXACT_VE_ELIMINATION_ASSIGNMENT_OVERFLOW")
			|| message.startsWith("REGIONAL_SEARCH_WORK_LIMIT_EXCEEDED"));
	}
}
