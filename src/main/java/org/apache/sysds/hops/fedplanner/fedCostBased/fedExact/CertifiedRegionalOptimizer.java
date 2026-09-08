/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.ToDoubleFunction;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/**
 * An anytime envelope around a feasible Regional incumbent in a nonnegative cost model.
 * Lower bounds use the entire unconditioned model; only incumbent improvement fixes
 * decisions outside the region. Original decisions precede exact encoding auxiliaries.
 * Direct models derive their evaluator from the same factors. The physical entry
 * accepts the owner-bound surface whose exact auxiliary encoding is constructed
 * and verified by ExactPhysicalCostModel; arbitrary evaluator/encoding pairs are
 * deliberately not an API. Original-model feasibility and cost establish the upper
 * bound even before any auxiliary elimination is attempted.
 * Nonnegativity of every factor is a caller precondition (guaranteed by the physical
 * cost-surface constructors), including when a budget prevents exhaustive validation.
 */
final class CertifiedRegionalOptimizer {
	static final String PROPERTY_PREFIX = "sysds.fedplanner.regional.";
	enum ExpansionPolicy { DISAGREEMENT, STRUCTURAL, RANDOM }
	enum StopReason { GAP, ITERATION_LIMIT, TIME_BUDGET, RESOURCE_LIMIT, REGION_LIMIT, CERTIFIED, GLOBAL_EXACT }

	record Options(int initialWidth, int maximumWidth, int rounds, int regionGrowth,
		int maximumRegionVariables, long timeBudgetMillis, double absoluteTolerance,
		double relativeTolerance, boolean refineBound, boolean expandRegions,
		ExpansionPolicy policy, long seed, Limits limits) {
		Options {
			if(initialWidth < 1 || maximumWidth < initialWidth || rounds < 1
				|| regionGrowth < 1 || maximumRegionVariables < 1 || timeBudgetMillis < 0
				|| !Double.isFinite(absoluteTolerance) || absoluteTolerance < 0d
				|| !Double.isFinite(relativeTolerance) || relativeTolerance < 0d)
				throw new IllegalArgumentException("REGIONAL_OPTIONS_INVALID");
			Objects.requireNonNull(policy, "policy");
			Objects.requireNonNull(limits, "limits");
		}

		/** Null means the unchanged Regional baseline. Options are read once per invocation. */
		static Options configured() {
			String mode = System.getProperty(PROPERTY_PREFIX + "mode", "off").toLowerCase(Locale.ROOT);
			if(mode.equals("off"))
				return null;
			if(!mode.equals("certify") && !mode.equals("anytime"))
				throw new IllegalArgumentException("REGIONAL_MODE_INVALID|mode=" + mode);
			Limits production = ExactPhysicalOptimizer.PRODUCTION_LIMITS;
			long factorCells = longOption("factorCells", 1_000_000L);
			long totalCells = longOption("totalCells", 5_000_000L);
			if(factorCells > production.maximumFactorCells()
				|| totalCells > production.maximumMaterializedCells())
				throw new IllegalArgumentException("REGIONAL_LIMIT_EXCEEDS_PRODUCTION_CEILING");
			return new Options(intOption("width", 2), intOption("maxWidth", 8),
				intOption("rounds", 4), intOption("regionGrowth", 8), intOption("maxRegion", 64),
				longOption("timeMillis", 1000L), doubleOption("absoluteGap", 0d),
				doubleOption("relativeGap", 0.01d), booleanOption("refineBound", mode.equals("anytime")),
				mode.equals("anytime"), ExpansionPolicy.valueOf(System.getProperty(
					PROPERTY_PREFIX + "policy", "DISAGREEMENT").toUpperCase(Locale.ROOT)),
				longOption("seed", 20260908L), new Limits(factorCells, totalCells));
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
		private static boolean booleanOption(String key, boolean fallback) {
			String value = System.getProperty(PROPERTY_PREFIX + key, Boolean.toString(fallback));
			if(!value.equals("true") && !value.equals("false"))
				throw new IllegalArgumentException("REGIONAL_BOOLEAN_OPTION_INVALID|key=" + key);
			return Boolean.parseBoolean(value);
		}
	}

	record Checkpoint(int iteration, String phase, int width, int regionVariables,
		double rawLowerBound, double lowerBound, double upperBound, long elapsedNanos,
		long boundNanos, long regionNanos, int splitBuckets, int disagreements,
		long maximumFactorCells, long boundMaterializedCells, long boundAssignments,
		long regionAssignments, boolean improved, List<Integer> assignment) {
		Checkpoint { assignment = List.copyOf(assignment); }
		double absoluteGap() { return gap(lowerBound, upperBound); }
		double relativeGap() { return relative(lowerBound, upperBound); }
	}

	record Result(List<Integer> assignment, double lowerBound, double upperBound,
		StopReason stopReason, List<Checkpoint> checkpoints) {
		Result {
			assignment = List.copyOf(assignment);
			checkpoints = List.copyOf(checkpoints);
		}
		double absoluteGap() { return gap(lowerBound, upperBound); }
		double relativeGap() { return relative(lowerBound, upperBound); }
	}

	private CertifiedRegionalOptimizer() { }

	static Result optimize(List<Variable> variables, List<Factor> factors,
		List<Integer> initialAssignment, Options options) {
		return run(variables, factors, variables.size(), initialAssignment,
			assignment -> evaluate(variables, factors, assignment), options, ignored -> { });
	}

	/** The existing physical cost builder proves equivalence of original and auxiliary factors. */
	static Result optimizePhysical(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface, ExactPhysicalForcedStateAudit.Constraint forced,
		List<Integer> initialAssignment, Options options,
		java.util.function.Consumer<Checkpoint> observer) {
		if(surface.owner() != model.analysis()
			|| !surface.ownerFingerprint().equals(model.analysis().analysisFingerprint())
			|| surface.variables().size() != model.variables().size())
			throw new IllegalArgumentException("REGIONAL_PHYSICAL_SURFACE_MISMATCH");
		for(int index = 0; index < model.variables().size(); index++)
			if(surface.variables().get(index) != model.variables().get(index))
				throw new IllegalArgumentException("REGIONAL_PHYSICAL_VARIABLE_IDENTITY_MISMATCH");
		List<Factor> hard = new ArrayList<>(model.hardFactors());
		if(forced != null)
			hard.add(forced.factor());
		List<Factor> global = new ArrayList<>(hard);
		global.addAll(surface.exactSolverFactors());
		return run(surface.exactSolverVariables(), global, model.variables().size(), initialAssignment,
			candidate -> {
				// Canonical costs alone do not establish feasibility. The original hard
				// constraints include privacy, authority and any forced-state audit.
				if(!Double.isFinite(evaluate(model.variables(), hard, candidate)))
					return Double.POSITIVE_INFINITY;
				return Double.longBitsToDouble(surface.evaluateCanonical(candidate));
			}, options, observer);
	}

	private static Result run(List<Variable> variables, List<Factor> factors, int decisionCount,
		List<Integer> initialAssignment, ToDoubleFunction<List<Integer>> evaluator, Options options,
		java.util.function.Consumer<Checkpoint> observer) {
		Objects.requireNonNull(options, "options");
		Objects.requireNonNull(evaluator, "evaluator");
		Objects.requireNonNull(observer, "observer");
		Context context = new Context(variables, factors, decisionCount);
		List<Integer> assignment = checkedAssignment(context, initialAssignment);
		double upper = checkedCost(evaluator.applyAsDouble(assignment));
		double lower = 0d; // All factors in this model are nonnegative, including hard 0/+inf factors.
		long start = System.nanoTime();
		long budgetNanos = options.timeBudgetMillis() > Long.MAX_VALUE / 1_000_000L
			? Long.MAX_VALUE : options.timeBudgetMillis() * 1_000_000L;
		java.util.function.BooleanSupplier expired = () ->
			Thread.currentThread().isInterrupted() || System.nanoTime() - start >= budgetNanos;
		List<Checkpoint> history = new ArrayList<>();
		Set<Integer> region = new LinkedHashSet<>();
		List<Integer> randomOrder = new ArrayList<>();
		for(int index = 0; index < decisionCount; index++)
			randomOrder.add(index);
		Collections.shuffle(randomOrder, new Random(options.seed()));
		MiniBucketLowerBound.Result bound = null;
		int width = options.initialWidth();
		double rawLower = 0d;
		publish(history, observer, new Checkpoint(0, "INITIAL", 0, 0, rawLower, lower, upper,
			System.nanoTime() - start, 0L, 0L, 0, 0, 0L, 0L, 0L, 0L, false, assignment));
		if(reachedGap(lower, upper, options))
			return result(assignment, lower, upper, StopReason.GAP, history);
		boolean limited = false;
		for(int iteration = 1; iteration <= options.rounds(); iteration++) {
			if(expired.getAsBoolean())
				return result(assignment, lower, upper, StopReason.TIME_BUDGET, history);
			long boundNanos = 0L;
			if(iteration == 1 || (options.refineBound() && width < options.maximumWidth())) {
				if(iteration > 1)
					width++;
				long phaseStart = System.nanoTime();
				limited = false;
				try {
					bound = MiniBucketLowerBound.compute(variables, factors, width, options.limits(), expired);
					rawLower = bound.lowerBound();
					if(!Double.isFinite(rawLower) || rawLower > upper)
						throw new IllegalStateException("REGIONAL_BOUND_INVALID|lower=" + rawLower + "|upper=" + upper);
					lower = Math.max(lower, rawLower);
				}
				catch(MiniBucketLowerBound.ResourceLimitException exhausted) {
					limited = true;
					rawLower = Double.NaN; // No raw result was completed at this attempted width.
				}
				catch(CancellationException cancelled) {
					publish(history, observer, checkpoint(iteration, "BOUND_CANCELLED", width,
						region.size(), Double.NaN, lower, upper, start,
						System.nanoTime() - phaseStart, 0L, bound, assignment));
					return result(assignment, lower, upper, StopReason.TIME_BUDGET, history);
				}
				boundNanos = System.nanoTime() - phaseStart;
				publish(history, observer, checkpoint(iteration, limited ? "BOUND_LIMIT" : "BOUND", width,
					region.size(), rawLower, lower, upper, start, boundNanos, 0L, bound, assignment));
			}
			if(reachedGap(lower, upper, options))
				return result(assignment, lower, upper, StopReason.GAP, history);
			if(expired.getAsBoolean())
				return result(assignment, lower, upper, StopReason.TIME_BUDGET, history);
			if(!options.expandRegions()) {
				if(limited || !options.refineBound() || width >= options.maximumWidth())
					return result(assignment, lower, upper,
						limited ? StopReason.RESOURCE_LIMIT : StopReason.CERTIFIED, history);
				continue;
			}
			int target = (int) Math.min(Math.min((long) region.size() + options.regionGrowth(),
				options.maximumRegionVariables()), decisionCount);
			if(target == region.size() && target < decisionCount) {
				// This region has already been solved exactly with the same boundary.
				// Further bound-only rounds can still tighten its certificate.
				if(options.refineBound() && width < options.maximumWidth())
					continue;
				return result(assignment, lower, upper, StopReason.REGION_LIMIT, history);
			}
			context.growRegion(region, target, bound == null ? List.of() : bound.conflicts(),
				options.policy(), randomOrder);
			if(target == 0 && decisionCount > 0)
				return result(assignment, lower, upper, StopReason.REGION_LIMIT, history);
			long phaseStart = System.nanoTime();
			try {
				// Exact elimination is bounded by cells, but is not preemptible. The time
				// budget is a scheduling budget checked before and after this phase.
				RegionalSolution solved = context.solveRegion(region, assignment, options.limits());
				List<Integer> candidate = checkedAssignment(context, solved.assignment());
				double candidateCost = checkedCost(evaluator.applyAsDouble(candidate));
				if(Double.doubleToRawLongBits(candidateCost) != Double.doubleToRawLongBits(solved.objective()))
					throw new IllegalStateException("REGIONAL_CANONICAL_OBJECTIVE_MISMATCH|solver="
						+ solved.objective() + "|canonical=" + candidateCost);
				if(candidateCost < lower)
					throw new IllegalStateException("REGIONAL_INCUMBENT_BELOW_BOUND");
				boolean improved = candidateCost < upper;
				if(improved) {
					assignment = candidate;
					upper = candidateCost;
				}
				boolean global = region.size() == decisionCount;
				if(global) {
					if(candidateCost > upper)
						throw new IllegalStateException("REGIONAL_GLOBAL_WORSE_THAN_INCUMBENT");
					lower = upper;
				}
				publish(history, observer, checkpoint(iteration, global ? "GLOBAL" : "REGION", width,
					region.size(), rawLower, lower, upper, start, 0L, System.nanoTime() - phaseStart,
					bound, assignment, solved.statistics().eliminationAssignments(), improved));
				if(global)
					return result(assignment, lower, upper, StopReason.GLOBAL_EXACT, history);
			}
			catch(IllegalArgumentException failure) {
				if(!isExactResourceLimit(failure))
					throw failure;
				publish(history, observer, checkpoint(iteration, "REGION_LIMIT", width,
					region.size(), rawLower, lower, upper, start, 0L, System.nanoTime() - phaseStart, bound, assignment));
				return result(assignment, lower, upper, StopReason.RESOURCE_LIMIT, history);
			}
			if(reachedGap(lower, upper, options))
				return result(assignment, lower, upper, StopReason.GAP, history);
			if(region.size() >= options.maximumRegionVariables()
				&& (!options.refineBound() || width >= options.maximumWidth()))
				return result(assignment, lower, upper, StopReason.REGION_LIMIT, history);
		}
		return result(assignment, lower, upper, expired.getAsBoolean() ? StopReason.TIME_BUDGET
			: limited ? StopReason.RESOURCE_LIMIT : StopReason.ITERATION_LIMIT, history);
	}

	private static boolean isExactResourceLimit(IllegalArgumentException failure) {
		String reason = failure.getMessage();
		return reason != null && (reason.startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED")
			|| reason.startsWith("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED")
			|| reason.startsWith("EXACT_VE_FACTOR_CELL_OVERFLOW")
			|| reason.startsWith("EXACT_VE_MATERIALIZED_CELL_OVERFLOW")
			|| reason.startsWith("EXACT_VE_ELIMINATION_ASSIGNMENT_OVERFLOW"));
	}

	private record RegionalSolution(double objective, List<Integer> assignment,
		ExactCategoricalSolver.Statistics statistics) { }

	private static final class Context {
		final List<Variable> variables;
		final List<Factor> factors;
		final int decisionCount;
		final IdentityHashMap<Variable,Integer> positions = new IdentityHashMap<>();
		final List<int[]> scopes = new ArrayList<>();
		final List<List<Integer>> incidence = new ArrayList<>();

		Context(List<Variable> variables, List<Factor> factors, int decisionCount) {
			this.variables = List.copyOf(variables);
			this.factors = List.copyOf(factors);
			this.decisionCount = decisionCount;
			if(decisionCount < 0 || decisionCount > variables.size())
				throw new IllegalArgumentException("REGIONAL_DECISION_PREFIX_INVALID");
			Set<String> keys = new LinkedHashSet<>();
			for(int index = 0; index < variables.size(); index++) {
				Variable variable = variables.get(index);
				if(positions.put(variable, index) != null || !keys.add(variable.key()))
					throw new IllegalArgumentException("REGIONAL_VARIABLE_DUPLICATE");
				incidence.add(new ArrayList<>());
			}
			for(Factor factor : factors) {
				int[] scope = new int[factor.scope().size()];
				Set<Integer> unique = new LinkedHashSet<>();
				for(int local = 0; local < scope.length; local++) {
					Integer index = positions.get(factor.scope().get(local));
					if(index == null || !unique.add(index))
						throw new IllegalArgumentException("REGIONAL_FACTOR_SCOPE_INVALID");
					scope[local] = index;
					incidence.get(index).add(scopes.size());
				}
				scopes.add(scope);
			}
		}

		RegionalSolution solveRegion(Set<Integer> region, List<Integer> incumbent, Limits limits) {
			List<Integer> freeIndexes = new ArrayList<>(region.stream().sorted().toList());
			int originalFreeCount = freeIndexes.size();
			for(int index = decisionCount; index < variables.size(); index++)
				freeIndexes.add(index);
			List<Variable> free = freeIndexes.stream().map(variables::get).toList();
			Set<Integer> freeSet = new LinkedHashSet<>(freeIndexes);
			List<Factor> conditional = new ArrayList<>();
			for(int factorIndex = 0; factorIndex < factors.size(); factorIndex++) {
				Factor factor = factors.get(factorIndex);
				int[] scope = scopes.get(factorIndex);
				List<Variable> restricted = new ArrayList<>();
				int[] mapping = new int[scope.length];
				int[] fixed = new int[scope.length];
				for(int local = 0; local < scope.length; local++) {
					if(freeSet.contains(scope[local])) {
						mapping[local] = restricted.size();
						restricted.add(variables.get(scope[local]));
					}
					else {
						mapping[local] = -1;
						fixed[local] = incumbent.get(scope[local]);
					}
				}
				conditional.add(Factor.lazy(restricted, values -> {
					int[] full = fixed.clone();
					for(int local = 0; local < full.length; local++)
						if(mapping[local] >= 0)
							full[local] = values[mapping[local]];
					return factor.cost(full);
				}));
			}
			ExactCategoricalSolver.Result solved = ExactPhysicalReducedSolver.solve(
				originalFreeCount, free, conditional, limits);
			List<Integer> candidate = new ArrayList<>(incumbent);
			for(int index = 0; index < originalFreeCount; index++)
				candidate.set(freeIndexes.get(index), solved.assignmentInVariableOrder().get(index));
			return new RegionalSolution(solved.objective(), List.copyOf(candidate), solved.statistics());
		}

		void growRegion(Set<Integer> region, int target, List<MiniBucketLowerBound.Conflict> conflicts,
			ExpansionPolicy policy, List<Integer> randomOrder) {
			if(policy == ExpansionPolicy.RANDOM) {
				for(int index : randomOrder) {
					if(region.size() >= target)
						break;
					region.add(index);
				}
				return;
			}
			ArrayDeque<Integer> queue = new ArrayDeque<>();
			if(policy == ExpansionPolicy.DISAGREEMENT)
				conflicts.stream().filter(conflict -> conflict.disagreement() > 0d)
					.sorted(Comparator.comparingDouble(MiniBucketLowerBound.Conflict::disagreement).reversed()
						.thenComparing(conflict -> conflict.variable().key()))
					.forEach(conflict -> {
						queue.addLast(positions.get(conflict.variable()));
						for(Variable variable : conflict.scope())
							queue.addLast(positions.get(variable));
					});
			queue.addAll(region);
			boolean[] visited = new boolean[variables.size()];
			boolean[] visitedFactors = new boolean[factors.size()];
			int next = 0;
			while(region.size() < target) {
				if(queue.isEmpty()) {
					while(next < decisionCount && visited[next])
						next++;
					if(next == decisionCount)
						break;
					queue.addLast(next);
				}
				int variable = queue.removeFirst();
				if(visited[variable])
					continue;
				visited[variable] = true;
				if(variable < decisionCount)
					region.add(variable);
				for(int factor : incidence.get(variable)) {
					if(visitedFactors[factor])
						continue;
					visitedFactors[factor] = true;
					for(int neighbor : scopes.get(factor))
						if(!visited[neighbor])
							queue.addLast(neighbor);
				}
			}
		}
	}

	/** Reevaluate a direct original factor model, checking hard constraints as well as costs. */
	static double evaluate(List<Variable> variables, List<Factor> factors, List<Integer> assignment) {
		Context context = new Context(variables, factors, variables.size());
		checkedAssignment(context, assignment);
		ExactCompensatedCostSum sum = new ExactCompensatedCostSum();
		for(int factor = 0; factor < factors.size(); factor++) {
			int[] values = java.util.Arrays.stream(context.scopes.get(factor))
				.map(assignment::get).toArray();
			double cost = factors.get(factor).cost(values);
			if(cost == Double.POSITIVE_INFINITY)
				return cost;
			sum.addBits(Double.doubleToRawLongBits(cost), "REGIONAL_COST_INVALID", "REGIONAL_TOTAL_INVALID");
		}
		return Double.longBitsToDouble(sum.totalBits("REGIONAL_TOTAL_INVALID"));
	}

	private static List<Integer> checkedAssignment(Context context, List<Integer> assignment) {
		if(assignment == null || assignment.size() != context.decisionCount)
			throw new IllegalArgumentException("REGIONAL_ASSIGNMENT_SIZE_INVALID");
		for(int index = 0; index < assignment.size(); index++)
			if(assignment.get(index) == null || assignment.get(index) < 0
				|| assignment.get(index) >= context.variables.get(index).domainSize())
				throw new IllegalArgumentException("REGIONAL_ASSIGNMENT_VALUE_INVALID");
		return List.copyOf(assignment);
	}

	private static double checkedCost(double cost) {
		if(!Double.isFinite(cost) || cost < 0d || Double.doubleToRawLongBits(cost) == Long.MIN_VALUE)
			throw new IllegalArgumentException("REGIONAL_INCUMBENT_NOT_FEASIBLE|cost=" + cost);
		return cost;
	}

	private static Checkpoint checkpoint(int iteration, String phase, int width, int region,
		double raw, double lower, double upper, long start, long boundNanos, long regionNanos,
		MiniBucketLowerBound.Result bound, List<Integer> assignment) {
		return checkpoint(iteration, phase, width, region, raw, lower, upper, start, boundNanos,
			regionNanos, bound, assignment, 0L, false);
	}

	private static Checkpoint checkpoint(int iteration, String phase, int width, int region,
		double raw, double lower, double upper, long start, long boundNanos, long regionNanos,
		MiniBucketLowerBound.Result bound, List<Integer> assignment, long regionAssignments, boolean improved) {
		return new Checkpoint(iteration, phase, width, region, raw, lower, upper, System.nanoTime() - start,
			boundNanos, regionNanos, bound == null ? 0 : bound.statistics().splitBuckets(),
			bound == null ? 0 : bound.conflicts().size(),
			bound == null ? 0L : bound.statistics().maximumFactorCells(),
			bound == null ? 0L : bound.statistics().materializedCells(),
			bound == null ? 0L : bound.statistics().evaluatedAssignments(), regionAssignments, improved, assignment);
	}
	private static void publish(List<Checkpoint> history, java.util.function.Consumer<Checkpoint> observer,
		Checkpoint checkpoint) {
		history.add(checkpoint);
		observer.accept(checkpoint);
	}
	private static Result result(List<Integer> assignment, double lower, double upper,
		StopReason reason, List<Checkpoint> history) {
		return new Result(assignment, lower, upper, reason, history);
	}
	private static boolean reachedGap(double lower, double upper, Options options) {
		return gap(lower, upper) <= options.absoluteTolerance()
			|| relative(lower, upper) <= options.relativeTolerance();
	}
	private static double gap(double lower, double upper) {
		return lower == upper ? 0d : Math.nextUp(upper - lower);
	}
	private static double relative(double lower, double upper) {
		if(lower == upper)
			return 0d;
		return lower > 0d ? Math.nextUp(gap(lower, upper) / lower) : Double.POSITIVE_INFINITY;
	}
}
