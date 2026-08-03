/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exact deterministic min-sum variable elimination over finite categorical variables.
 *
 * <p>The implementation deliberately materializes dense factors.  A caller-supplied
 * cell budget is checked before any lazy factor is evaluated and before elimination
 * starts, so an oversized model fails closed rather than switching algorithms or
 * returning an approximation. Positive infinity denotes a forbidden assignment.</p>
 */
public final class MinStExactCategoricalSolver {
	private MinStExactCategoricalSolver() { }

	@FunctionalInterface
	public interface CostFunction {
		/** Values are reused by the solver and must not be retained or modified. */
		double cost(int[] valuesInScopeOrder);
	}

	@FunctionalInterface
	public interface TieCostFunction {
		/** Non-negative additive secondary cost charged once for a selected variable value. */
		long cost(Variable variable, int value);
	}

	public record Variable(String key, int domainSize) {
		public Variable {
			if(key == null || key.isBlank())
				throw new IllegalArgumentException("MINST_VE_VARIABLE_KEY_INVALID");
			if(domainSize <= 0)
				throw new IllegalArgumentException("MINST_VE_DOMAIN_INVALID|key=" + key);
		}
	}

	public static final class Factor {
		private final List<Variable> scope;
		private final double[] denseValues;
		private final CostFunction evaluator;

		private Factor(List<Variable> scope, double[] denseValues, CostFunction evaluator) {
			this.scope = List.copyOf(Objects.requireNonNull(scope, "scope"));
			this.denseValues = denseValues == null ? null : denseValues.clone();
			this.evaluator = evaluator;
			if((denseValues == null) == (evaluator == null))
				throw new IllegalArgumentException("MINST_VE_FACTOR_REPRESENTATION_INVALID");
		}

		public static Factor dense(List<Variable> scope, double... values) {
			return new Factor(scope, Objects.requireNonNull(values, "values"), null);
		}

		public static Factor lazy(List<Variable> scope, CostFunction evaluator) {
			return new Factor(scope, null, Objects.requireNonNull(evaluator, "evaluator"));
		}

		List<Variable> scope() { return scope; }
		double denseCostAt(int cell) {
			if(denseValues == null)
				throw new IllegalStateException("MINST_VE_FACTOR_NOT_DENSE");
			return denseValues[cell];
		}
		double cost(int[] values) {
			if(values == null || values.length != scope.size())
				throw new IllegalArgumentException("MINST_VE_FACTOR_ASSIGNMENT_SIZE_MISMATCH");
			if(evaluator != null)
				return evaluator.cost(values);
			int cell = 0;
			for(int index = 0; index < values.length; index++) {
				if(values[index] < 0 || values[index] >= scope.get(index).domainSize())
					throw new IllegalArgumentException("MINST_VE_FACTOR_ASSIGNMENT_VALUE_INVALID");
				cell = cell * scope.get(index).domainSize() + values[index];
			}
			return denseValues[cell];
		}
	}

	public record Limits(long maximumFactorCells, long maximumMaterializedCells) {
		public Limits {
			if(maximumFactorCells <= 0 || maximumMaterializedCells <= 0)
				throw new IllegalArgumentException("MINST_VE_LIMIT_INVALID");
		}
	}

	public record Statistics(List<String> eliminationOrder, int inducedWidth,
		long maximumFactorCells, long materializedFactorCells,
		long maximumEliminationAssignments, long eliminationAssignments) {
		public Statistics { eliminationOrder = List.copyOf(eliminationOrder); }
	}

	public record Result(double objective, List<Integer> assignmentInVariableOrder,
		Statistics statistics) {
		public Result { assignmentInVariableOrder = List.copyOf(assignmentInVariableOrder); }
		int value(Variable variable, List<Variable> variablesInCanonicalOrder) {
			int index = variablesInCanonicalOrder.indexOf(variable);
			if(index < 0)
				throw new IllegalArgumentException("MINST_VE_RESULT_VARIABLE_UNKNOWN");
			return assignmentInVariableOrder.get(index);
		}
	}

	public static Statistics analyze(List<Variable> variables, List<Factor> factors, Limits limits) {
		return prepare(variables, factors, limits).statistics;
	}

	public static Result solve(List<Variable> variables, List<Factor> factors, Limits limits) {
		return solve(variables, factors, limits, (variable, value) -> 0L);
	}

	/**
	 * Solves the primary binary64 objective exactly as exposed by {@link Result#objective()},
	 * then minimizes the caller-supplied additive tie cost without perturbing primary factors.
	 */
	public static Result solve(List<Variable> variables, List<Factor> factors, Limits limits,
		TieCostFunction tieCostFunction) {
		Objects.requireNonNull(tieCostFunction, "tieCostFunction");
		Prepared prepared = prepare(variables, factors, limits);
		List<DenseFactor> active = materializeInputs(prepared, factors);
		List<Backpointer> backpointers = new ArrayList<>(variables.size());
		int[] global = new int[variables.size()];

		for(Step step : prepared.steps) {
			List<DenseFactor> bucket = new ArrayList<>();
			for(DenseFactor factor : active)
				if(factor.contains(step.variable))
					bucket.add(factor);
			active.removeAll(bucket);
			int outputCells = checkedCells(step.separator, prepared.domains,
				"MINST_VE_FACTOR_CELL_OVERFLOW");
			double[] output = new double[outputCells];
			double[] outputLow = null;
			long[] outputTie = null;
			int[] choices = new int[outputCells];
			int[] separatorValues = new int[step.separator.length];
			for(int cell = 0; cell < outputCells; cell++) {
				decode(cell, step.separator, prepared.domains, separatorValues, global);
				PreciseCost best = PreciseCost.POSITIVE_INFINITY;
				int bestValue = 0;
				for(int value = 0; value < prepared.domains[step.variable]; value++) {
					global[step.variable] = value;
					long tieCost = tieCostFunction.cost(
						prepared.variables.get(step.variable), value);
					if(tieCost < 0)
						throw new IllegalArgumentException("MINST_VE_TIE_COST_INVALID");
					PreciseCost candidate = preciseSum(bucket, global).plusTie(tieCost);
					if(candidate.compareTo(best) < 0) {
						best = candidate;
						bestValue = value;
					}
				}
				output[cell] = best.high;
				if(best.low != 0d) {
					if(outputLow == null)
						outputLow = new double[outputCells];
					outputLow[cell] = best.low;
				}
				if(best.tieCost != 0L) {
					if(outputTie == null)
						outputTie = new long[outputCells];
					outputTie[cell] = best.tieCost;
				}
				choices[cell] = bestValue;
			}
			DenseFactor reduced = new DenseFactor(
				step.separator, prepared.domains, output, outputLow, outputTie);
			active.add(reduced);
			backpointers.add(new Backpointer(step.variable, step.separator, choices));
		}

		double objective = preciseSum(active, global).rounded();
		if(objective == Double.POSITIVE_INFINITY)
			throw new IllegalArgumentException("MINST_VE_NO_FEASIBLE_ASSIGNMENT");
		for(int index = backpointers.size() - 1; index >= 0; index--) {
			Backpointer backpointer = backpointers.get(index);
			int cell = encode(backpointer.separator, prepared.domains, global);
			global[backpointer.variable] = backpointer.choices[cell];
		}
		List<Integer> assignment = Arrays.stream(global).boxed().toList();
		return new Result(objective, assignment, prepared.statistics);
	}

	/** Evaluates one complete assignment using the same validation and arithmetic as solve. */
	public static double evaluate(List<Variable> variables, List<Factor> factors, Limits limits,
		List<Integer> assignmentInVariableOrder) {
		Prepared prepared = prepare(variables, factors, limits);
		if(assignmentInVariableOrder == null || assignmentInVariableOrder.size() != variables.size())
			throw new IllegalArgumentException("MINST_VE_ASSIGNMENT_SIZE_MISMATCH");
		int[] assignment = new int[variables.size()];
		for(int index = 0; index < assignment.length; index++) {
			Integer value = assignmentInVariableOrder.get(index);
			if(value == null || value < 0 || value >= prepared.domains[index])
				throw new IllegalArgumentException("MINST_VE_ASSIGNMENT_VALUE_INVALID|index=" + index);
			assignment[index] = value;
		}
		return preciseSum(materializeInputs(prepared, factors), assignment).rounded();
	}

	private static Prepared prepare(List<Variable> variables, List<Factor> factors, Limits limits) {
		Objects.requireNonNull(variables, "variables");
		Objects.requireNonNull(factors, "factors");
		Objects.requireNonNull(limits, "limits");
		List<Variable> canonical = List.copyOf(variables);
		Map<Variable,Integer> index = new LinkedHashMap<>();
		Map<String,Variable> keys = new HashMap<>();
		int[] domains = new int[canonical.size()];
		for(int i = 0; i < canonical.size(); i++) {
			Variable variable = Objects.requireNonNull(canonical.get(i), "variable");
			if(index.put(variable, i) != null || keys.put(variable.key(), variable) != null)
				throw new IllegalArgumentException("MINST_VE_VARIABLE_DUPLICATE|key=" + variable.key());
			domains[i] = variable.domainSize();
		}

		List<int[]> scopes = new ArrayList<>(factors.size());
		long inputCells = 0;
		for(Factor factor : factors) {
			Objects.requireNonNull(factor, "factor");
			int[] scope = new int[factor.scope.size()];
			Set<Integer> unique = new HashSet<>();
			for(int i = 0; i < scope.length; i++) {
				Integer variableIndex = index.get(factor.scope.get(i));
				if(variableIndex == null)
					throw new IllegalArgumentException("MINST_VE_FACTOR_VARIABLE_UNKNOWN");
				if(!unique.add(variableIndex))
					throw new IllegalArgumentException("MINST_VE_FACTOR_VARIABLE_DUPLICATE");
				scope[i] = variableIndex;
			}
			int cells = checkedCells(scope, domains, "MINST_VE_FACTOR_CELL_OVERFLOW");
			if(factor.denseValues != null && factor.denseValues.length != cells)
				throw new IllegalArgumentException("MINST_VE_DENSE_FACTOR_SIZE_MISMATCH");
			inputCells = checkedAdd(inputCells, cells, "MINST_VE_MATERIALIZED_CELL_OVERFLOW");
			scopes.add(scope);
		}

		Plan plan = minFillPlan(canonical, domains, scopes);
		long totalCells = inputCells;
		long maximumCells = 0;
		for(int[] scope : scopes)
			maximumCells = Math.max(maximumCells, checkedCells(scope, domains,
				"MINST_VE_FACTOR_CELL_OVERFLOW"));
		for(Step step : plan.steps) {
			long cells = checkedCells(step.separator, domains, "MINST_VE_FACTOR_CELL_OVERFLOW");
			maximumCells = Math.max(maximumCells, cells);
			totalCells = checkedAdd(totalCells, cells, "MINST_VE_MATERIALIZED_CELL_OVERFLOW");
		}
		if(maximumCells > limits.maximumFactorCells())
			throw new IllegalArgumentException("MINST_VE_FACTOR_LIMIT_EXCEEDED|cells=" + maximumCells
				+ "|limit=" + limits.maximumFactorCells());
		if(totalCells > limits.maximumMaterializedCells())
			throw new IllegalArgumentException("MINST_VE_MATERIALIZED_LIMIT_EXCEEDED|cells=" + totalCells
				+ "|limit=" + limits.maximumMaterializedCells());
		long maximumAssignments = 0;
		long assignments = 0;
		for(Step step : plan.steps) {
			long cells = checkedCells(step.separator, domains, "MINST_VE_FACTOR_CELL_OVERFLOW");
			if(cells > Long.MAX_VALUE / domains[step.variable])
				throw new IllegalArgumentException("MINST_VE_ELIMINATION_ASSIGNMENT_OVERFLOW");
			long stepAssignments = cells * domains[step.variable];
			maximumAssignments = Math.max(maximumAssignments, stepAssignments);
			assignments = checkedAdd(assignments, stepAssignments,
				"MINST_VE_ELIMINATION_ASSIGNMENT_OVERFLOW");
		}
		Statistics statistics = new Statistics(plan.steps.stream()
			.map(step -> canonical.get(step.variable).key()).toList(), plan.inducedWidth,
			maximumCells, totalCells, maximumAssignments, assignments);
		return new Prepared(canonical, domains, scopes, plan.steps, statistics);
	}

	private static Plan minFillPlan(List<Variable> variables, int[] domains, List<int[]> initialScopes) {
		List<Set<Integer>> graph = new ArrayList<>(variables.size());
		for(int i = 0; i < variables.size(); i++)
			graph.add(new HashSet<>());
		for(int[] scope : initialScopes)
			for(int i = 0; i < scope.length; i++)
				for(int j = i + 1; j < scope.length; j++) {
					graph.get(scope[i]).add(scope[j]);
					graph.get(scope[j]).add(scope[i]);
				}
		Set<Integer> remaining = new HashSet<>();
		for(int i = 0; i < variables.size(); i++)
			remaining.add(i);
		List<Step> steps = new ArrayList<>(variables.size());
		int width = 0;
		while(!remaining.isEmpty()) {
			int selected = remaining.stream().min(Comparator
				.comparingLong((Integer variable) -> fillEdges(variable, graph, remaining))
				.thenComparingLong(variable -> neighborCells(variable, graph, remaining, domains))
				.thenComparing(variable -> variables.get(variable).key())).orElseThrow();
			int[] separator = graph.get(selected).stream().filter(remaining::contains)
				.sorted().mapToInt(Integer::intValue).toArray();
			width = Math.max(width, separator.length);
			for(int i = 0; i < separator.length; i++)
				for(int j = i + 1; j < separator.length; j++) {
					graph.get(separator[i]).add(separator[j]);
					graph.get(separator[j]).add(separator[i]);
				}
			remaining.remove(selected);
			steps.add(new Step(selected, separator));
		}
		return new Plan(List.copyOf(steps), width);
	}

	private static long fillEdges(int variable, List<Set<Integer>> graph, Set<Integer> remaining) {
		int[] neighbors = graph.get(variable).stream().filter(remaining::contains)
			.sorted().mapToInt(Integer::intValue).toArray();
		long missing = 0;
		for(int i = 0; i < neighbors.length; i++)
			for(int j = i + 1; j < neighbors.length; j++)
				if(!graph.get(neighbors[i]).contains(neighbors[j]))
					missing++;
		return missing;
	}

	private static long neighborCells(int variable, List<Set<Integer>> graph,
		Set<Integer> remaining, int[] domains) {
		long cells = 1;
		for(int neighbor : graph.get(variable)) {
			if(!remaining.contains(neighbor))
				continue;
			if(cells > Long.MAX_VALUE / domains[neighbor])
				return Long.MAX_VALUE;
			cells *= domains[neighbor];
		}
		return cells;
	}

	private static List<DenseFactor> materializeInputs(Prepared prepared, List<Factor> factors) {
		List<DenseFactor> result = new ArrayList<>(factors.size());
		int[] global = new int[prepared.variables.size()];
		for(int factorIndex = 0; factorIndex < factors.size(); factorIndex++) {
			Factor factor = factors.get(factorIndex);
			int[] scope = prepared.scopes.get(factorIndex);
			int cells = checkedCells(scope, prepared.domains, "MINST_VE_FACTOR_CELL_OVERFLOW");
			double[] values = factor.denseValues == null ? new double[cells] : factor.denseValues.clone();
			int[] local = new int[scope.length];
			for(int cell = 0; cell < cells; cell++) {
				if(factor.evaluator != null) {
					decode(cell, scope, prepared.domains, local, global);
					values[cell] = factor.evaluator.cost(local);
				}
				validateCost(values[cell]);
			}
			result.add(new DenseFactor(scope, prepared.domains, values, null, null));
		}
		return result;
	}

	private static PreciseCost preciseSum(List<DenseFactor> factors, int[] global) {
		PreciseCost total = PreciseCost.ZERO;
		for(DenseFactor factor : factors) {
			PreciseCost value = factor.value(global);
			if(value.high == Double.POSITIVE_INFINITY)
				return value;
			total = total.plus(value);
		}
		return total;
	}

	private static void validateCost(double value) {
		if(Double.isNaN(value) || value == Double.NEGATIVE_INFINITY || isNegativeZero(value))
			throw new IllegalArgumentException("MINST_VE_FACTOR_COST_INVALID|value=" + value);
	}

	private static boolean isNegativeZero(double value) {
		return Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(-0.0d);
	}

	private static int checkedCells(int[] scope, int[] domains, String reason) {
		long cells = 1;
		for(int variable : scope) {
			if(cells > Integer.MAX_VALUE / domains[variable])
				throw new IllegalArgumentException(reason);
			cells *= domains[variable];
		}
		return (int)cells;
	}

	private static long checkedAdd(long left, long right, String reason) {
		if(left > Long.MAX_VALUE - right)
			throw new IllegalArgumentException(reason);
		return left + right;
	}

	private static void decode(int cell, int[] scope, int[] domains,
		int[] local, int[] global) {
		for(int index = scope.length - 1; index >= 0; index--) {
			int value = cell % domains[scope[index]];
			cell /= domains[scope[index]];
			local[index] = value;
			global[scope[index]] = value;
		}
	}

	private static int encode(int[] scope, int[] domains, int[] global) {
		int cell = 0;
		for(int variable : scope)
			cell = cell * domains[variable] + global[variable];
		return cell;
	}

	private record Step(int variable, int[] separator) {
		Step { separator = separator.clone(); }
	}
	private record Plan(List<Step> steps, int inducedWidth) { }
	private record Prepared(List<Variable> variables, int[] domains, List<int[]> scopes,
		List<Step> steps, Statistics statistics) { }
	private record Backpointer(int variable, int[] separator, int[] choices) { }

	/**
	 * Normalized double-double accumulator.  Reduced factors retain the rounding
	 * residue instead of collapsing it after every elimination step.  This makes
	 * the exact min-sum decision independent of where an algebraically identical
	 * elementary cost is attached in the factor graph.
	 */
	private record PreciseCost(double high, double low, long tieCost)
		implements Comparable<PreciseCost> {
		private static final PreciseCost ZERO = new PreciseCost(0d, 0d, 0L);
		private static final PreciseCost POSITIVE_INFINITY =
			new PreciseCost(Double.POSITIVE_INFINITY, 0d, 0L);

		private PreciseCost plus(PreciseCost that) {
			if(high == Double.POSITIVE_INFINITY || that.high == Double.POSITIVE_INFINITY)
				return POSITIVE_INFINITY;
			double sum = high + that.high;
			if(!Double.isFinite(sum))
				throw new IllegalArgumentException("MINST_VE_OBJECTIVE_OVERFLOW");
			double virtual = sum - high;
			double error = (high - (sum - virtual)) + (that.high - virtual);
			error += low + that.low;
			if(!Double.isFinite(error))
				throw new IllegalArgumentException("MINST_VE_OBJECTIVE_OVERFLOW");
			double normalizedHigh = sum + error;
			if(!Double.isFinite(normalizedHigh))
				throw new IllegalArgumentException("MINST_VE_OBJECTIVE_OVERFLOW");
			double normalizedLow = error - (normalizedHigh - sum);
			long combinedTie;
			try {
				combinedTie = Math.addExact(tieCost, that.tieCost);
			}
			catch(ArithmeticException ex) {
				throw new IllegalArgumentException("MINST_VE_TIE_COST_OVERFLOW", ex);
			}
			return new PreciseCost(normalizedHigh, normalizedLow, combinedTie);
		}

		private PreciseCost plusTie(long extraTieCost) {
			try {
				return new PreciseCost(high, low, Math.addExact(tieCost, extraTieCost));
			}
			catch(ArithmeticException ex) {
				throw new IllegalArgumentException("MINST_VE_TIE_COST_OVERFLOW", ex);
			}
		}

		private double rounded() {
			if(high == Double.POSITIVE_INFINITY)
				return high;
			double result = high + low;
			if(!Double.isFinite(result))
				throw new IllegalArgumentException("MINST_VE_OBJECTIVE_OVERFLOW");
			return result;
		}

		@Override
		public int compareTo(PreciseCost that) {
			// Result.objective is a binary64 value, so alternatives whose retained
			// double-double sums round to the same representable objective are true
			// solver ties.  Let the caller-controlled domain order resolve them instead
			// of making an unobservable residue an accidental policy decision.
			int byPrimary = Double.compare(rounded(), that.rounded());
			return byPrimary != 0 ? byPrimary : Long.compare(tieCost, that.tieCost);
		}
	}

	private static final class DenseFactor {
		private final int[] scope;
		private final int[] strides;
		private final double[] values;
		private final double[] lowValues;
		private final long[] tieCosts;

		private DenseFactor(int[] scope, int[] domains, double[] values, double[] lowValues,
			long[] tieCosts) {
			this.scope = scope.clone();
			this.values = values;
			this.lowValues = lowValues;
			this.tieCosts = tieCosts;
			this.strides = new int[scope.length];
			int stride = 1;
			for(int index = scope.length - 1; index >= 0; index--) {
				strides[index] = stride;
				stride = Math.multiplyExact(stride, domains[scope[index]]);
			}
		}

		private boolean contains(int variable) {
			for(int candidate : scope)
				if(candidate == variable)
					return true;
			return false;
		}

		private PreciseCost value(int[] global) {
			int cell = 0;
			for(int index = 0; index < scope.length; index++)
				cell += global[scope[index]] * strides[index];
			return new PreciseCost(values[cell], lowValues == null ? 0d : lowValues[cell],
				tieCosts == null ? 0L : tieCosts[cell]);
		}
	}
}
