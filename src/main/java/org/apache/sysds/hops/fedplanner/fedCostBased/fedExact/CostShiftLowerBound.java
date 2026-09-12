/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoublePredicate;

/** Bounded min-sum cost shifting over the fixed mini-bucket join graph. */
final class CostShiftLowerBound {
	private static final int DEFAULT_MAX_SWEEPS = 1_000;

	private CostShiftLowerBound() { }

	enum Reason {
		COMPLETED,
		TARGET_REACHED,
		BUDGET_EXHAUSTED,
		CANCELLED,
		RESOURCE_LIMIT,
		WORK_LIMIT
	}

	record Statistics(long preparationNanos, long updateNanos, long scannedCells,
		long updates, int sweeps, long materializedCells, long maxFactorCells,
		boolean cancelled, boolean resourceLimited) { }

	record Result(double lowerBound, Statistics statistics, Reason reason) { }

	static Result compute(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int width,
		ExactCategoricalSolver.Limits limits, long budgetNanos,
		BooleanSupplier cancelled, DoublePredicate targetReached, DoubleConsumer progress) {
		long maximumScannedCells = saturatedMultiply(limits.maximumMaterializedCells(),
			4L * DEFAULT_MAX_SWEEPS);
		return compute(variables, factors, width, limits, budgetNanos, DEFAULT_MAX_SWEEPS,
			maximumScannedCells, cancelled, targetReached, progress);
	}

	static Result compute(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int width,
		ExactCategoricalSolver.Limits limits, long budgetNanos, int maxSweeps,
		long maximumScannedCells, BooleanSupplier cancelled,
		DoublePredicate targetReached, DoubleConsumer progress) {
		Objects.requireNonNull(variables, "variables");
		Objects.requireNonNull(factors, "factors");
		Objects.requireNonNull(limits, "limits");
		Objects.requireNonNull(cancelled, "cancelled");
		Objects.requireNonNull(targetReached, "targetReached");
		Objects.requireNonNull(progress, "progress");
		if(budgetNanos < 0)
			throw new IllegalArgumentException("COST_SHIFT_BUDGET_INVALID");
		if(maxSweeps < 0)
			throw new IllegalArgumentException("COST_SHIFT_SWEEP_LIMIT_INVALID");
		if(maximumScannedCells <= 0)
			throw new IllegalArgumentException("COST_SHIFT_WORK_LIMIT_INVALID");

		long started = System.nanoTime();
		long deadline = deadline(started, budgetNanos);
		State state = new State(started, deadline, maximumScannedCells, cancelled);
		long preparationFinished = started;
		List<Potential> potentials = null;
		MiniBucketLowerBound.JoinGraphPlan graph = null;
		double safetyMargin = 0d;
		long materializedCells = 0L;
		long maximumFactorCells = 0L;
		double published = 0d;
		int sweeps = 0;
		long updates = 0L;
		Reason reason = Reason.COMPLETED;
		try {
			state.check();
			graph = MiniBucketLowerBound.joinGraphPlan(variables, factors, width, limits,
				() -> { state.check(); return false; });
			Prepared prepared = materialize(variables, factors, graph, limits, state);
			potentials = prepared.potentials;
			materializedCells = prepared.peakCells;
			maximumFactorCells = prepared.maximumFactorCells;
			safetyMargin = forwardErrorMargin(prepared.maximumFiniteOriginalSum, factors.size());
			preparationFinished = System.nanoTime();
			published = Math.max(published, safeBound(potentials, safetyMargin, state));
			progress.accept(published);
			if(targetReached.test(published))
				reason = Reason.TARGET_REACHED;
			for(MiniBucketLowerBound.JoinEdge edge : graph.edges()) {
				if(reason != Reason.COMPLETED)
					break;
				if(edge.generatedMessage() && transferMinimum(potentials.get(edge.leftCluster()),
					potentials.get(edge.rightCluster()), edge.separator(), graph.domains(), state)) {
					updates++;
					double bound = safeBound(potentials, safetyMargin, state);
					if(bound > published) {
						published = bound;
						progress.accept(published);
					}
					if(targetReached.test(published))
						reason = Reason.TARGET_REACHED;
				}
			}

			for(int sweep = 0; reason == Reason.COMPLETED && sweep < maxSweeps; sweep++) {
				boolean changed = false;
				for(MiniBucketLowerBound.JoinEdge edge : graph.edges()) {
					state.check();
					if(shiftPair(potentials.get(edge.leftCluster()),
						potentials.get(edge.rightCluster()), edge.separator(), graph.domains(), state)) {
						updates++;
						changed = true;
						double bound = safeBound(potentials, safetyMargin, state);
						if(bound > published) {
							published = bound;
							progress.accept(published);
						}
						if(targetReached.test(published)) {
							reason = Reason.TARGET_REACHED;
							break;
						}
					}
				}
				sweeps++;
				if(!changed)
					break;
			}
		}
		catch(Stop stop) {
			reason = stop.reason;
		}
		catch(CancellationException exception) {
			reason = Reason.CANCELLED;
		}
		catch(MiniBucketLowerBound.ResourceLimitException exception) {
			reason = Reason.RESOURCE_LIMIT;
		}
		long finished = System.nanoTime();
		if(preparationFinished == started)
			preparationFinished = finished;
		Statistics statistics = new Statistics(preparationFinished - started,
			Math.max(0L, finished - preparationFinished), state.scannedCells, updates, sweeps,
			materializedCells, maximumFactorCells, reason == Reason.CANCELLED,
			reason == Reason.RESOURCE_LIMIT || reason == Reason.WORK_LIMIT);
		return new Result(published, statistics, reason);
	}

	private static Prepared materialize(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, MiniBucketLowerBound.JoinGraphPlan graph,
		ExactCategoricalSolver.Limits limits, State state) {
		int[] domains = graph.domains();
		Map<ExactCategoricalSolver.Variable, Integer> indices = new IdentityHashMap<>();
		for(int index = 0; index < variables.size(); index++)
			indices.put(variables.get(index), index);
		List<int[]> callerScopes = new ArrayList<>(factors.size());
		for(ExactCategoricalSolver.Factor factor : factors) {
			int[] scope = new int[factor.scope().size()];
			for(int position = 0; position < scope.length; position++)
				scope[position] = indices.get(factor.scope().get(position));
			callerScopes.add(scope);
		}

		long persistent = 0L;
		long maximum = 0L;
		long maximumPairScratch = 0L;
		List<Potential> potentials = new ArrayList<>(graph.clusters().size());
		for(MiniBucketLowerBound.JoinCluster cluster : graph.clusters()) {
			state.check();
			int[] scope = cluster.scope();
			long cells = cells(scope, domains);
			if(cells > limits.maximumFactorCells() || cells > Integer.MAX_VALUE)
				throw new MiniBucketLowerBound.ResourceLimitException(
					"COST_SHIFT_FACTOR_LIMIT_EXCEEDED|cells=" + cells);
			persistent = addCells(persistent, cells, limits.maximumMaterializedCells());
			maximum = Math.max(maximum, cells);
			potentials.add(new Potential(scope, domains, new double[Math.toIntExact(cells)]));
		}
		for(MiniBucketLowerBound.JoinEdge edge : graph.edges()) {
			long separatorCells = cells(edge.separator(), domains);
			long pairScratch = potentials.get(edge.leftCluster()).values.length
				+ (long)potentials.get(edge.rightCluster()).values.length;
			if(separatorCells > (Long.MAX_VALUE - pairScratch) / 3L)
				throw new MiniBucketLowerBound.ResourceLimitException("COST_SHIFT_SCRATCH_CELL_OVERFLOW");
			pairScratch += 3L * separatorCells;
			maximumPairScratch = Math.max(maximumPairScratch, pairScratch);
		}
		long peak = addCells(persistent, maximumPairScratch, limits.maximumMaterializedCells());

		double maximumFiniteOriginalSum = 0d;
		double[] factorMaxima = new double[factors.size()];
		Arrays.fill(factorMaxima, Double.NEGATIVE_INFINITY);
		int[][] localValues = new int[factors.size()][];
		for(int factor = 0; factor < factors.size(); factor++)
			localValues[factor] = new int[callerScopes.get(factor).length];
		int[] global = new int[variables.size()];
		for(int clusterIndex = 0; clusterIndex < graph.clusters().size(); clusterIndex++) {
			MiniBucketLowerBound.JoinCluster cluster = graph.clusters().get(clusterIndex);
			Potential potential = potentials.get(clusterIndex);
			int[] originals = cluster.originalFactors();
			double minimum = Double.POSITIVE_INFINITY;
			for(int cell = 0; cell < potential.values.length; cell++) {
				state.scan();
				decode(cell, potential.scope, domains, global);
				double total = 0d;
				for(int original : originals) {
					state.check();
					int[] originalScope = callerScopes.get(original);
					int[] local = localValues[original];
					for(int position = 0; position < local.length; position++)
						local[position] = global[originalScope[position]];
					double value = factors.get(original).cost(local);
					validateOriginalCost(value);
					if(Double.isFinite(value))
						factorMaxima[original] = Math.max(factorMaxima[original], value);
					total = addDownSigned(total, value);
				}
				potential.values[cell] = total;
				minimum = Math.min(minimum, total);
			}
			potential.minimum = minimum;
		}
		for(double maximumValue : factorMaxima) {
			if(maximumValue != Double.NEGATIVE_INFINITY)
				maximumFiniteOriginalSum = addUp(maximumFiniteOriginalSum, maximumValue);
		}
		return new Prepared(List.copyOf(potentials), peak, maximum, maximumFiniteOriginalSum);
	}

	private static boolean shiftPair(Potential left, Potential right, int[] separator,
		int[] domains, State state) {
		int separatorCells = Math.toIntExact(cells(separator, domains));
		double[] leftMarginal = new double[separatorCells];
		double[] rightMarginal = new double[separatorCells];
		Arrays.fill(leftMarginal, Double.POSITIVE_INFINITY);
		Arrays.fill(rightMarginal, Double.POSITIVE_INFINITY);
		minMarginal(left, separator, domains, leftMarginal, state);
		minMarginal(right, separator, domains, rightMarginal, state);
		double[] shifts = new double[separatorCells];
		boolean any = false;
		for(int cell = 0; cell < separatorCells; cell++) {
			state.check();
			if(!Double.isFinite(leftMarginal[cell]) || !Double.isFinite(rightMarginal[cell]))
				continue;
			double shift = leftMarginal[cell] / 2d - rightMarginal[cell] / 2d;
			if(Double.isFinite(shift) && shift != 0d) {
				shifts[cell] = shift;
				any = true;
			}
		}
		if(!any)
			return false;

		double[] nextLeft = left.values.clone();
		double[] nextRight = right.values.clone();
		double nextLeftMinimum = applyShift(nextLeft, left, separator, domains, shifts, -1d, state);
		if(Double.isNaN(nextLeftMinimum))
			return false;
		double nextRightMinimum = applyShift(nextRight, right, separator, domains, shifts, 1d, state);
		if(Double.isNaN(nextRightMinimum))
			return false;
		left.values = nextLeft;
		left.minimum = nextLeftMinimum;
		right.values = nextRight;
		right.minimum = nextRightMinimum;
		return true;
	}

	private static boolean transferMinimum(Potential left, Potential right, int[] separator,
		int[] domains, State state) {
		double[] marginal = new double[Math.toIntExact(cells(separator, domains))];
		Arrays.fill(marginal, Double.POSITIVE_INFINITY);
		minMarginal(left, separator, domains, marginal, state);
		double[] shifts = new double[marginal.length];
		boolean any = false;
		for(int cell = 0; cell < marginal.length; cell++) {
			state.check();
			if(Double.isFinite(marginal[cell]) && marginal[cell] != 0d) {
				shifts[cell] = marginal[cell];
				any = true;
			}
		}
		if(!any)
			return false;
		double[] nextLeft = left.values.clone();
		double[] nextRight = right.values.clone();
		double nextLeftMinimum = applyShift(nextLeft, left, separator, domains, shifts, -1d, state);
		if(Double.isNaN(nextLeftMinimum))
			return false;
		double nextRightMinimum = applyShift(nextRight, right, separator, domains, shifts, 1d, state);
		if(Double.isNaN(nextRightMinimum))
			return false;
		left.values = nextLeft;
		left.minimum = nextLeftMinimum;
		right.values = nextRight;
		right.minimum = nextRightMinimum;
		return true;
	}

	private static void minMarginal(Potential potential, int[] separator, int[] domains,
		double[] marginal, State state) {
		Projection projection = projection(potential, separator, domains);
		for(int cell = 0; cell < potential.values.length; cell++) {
			state.scan();
			int separatorCell = projection.cell(cell);
			marginal[separatorCell] = Math.min(marginal[separatorCell], potential.values[cell]);
		}
	}

	private static double applyShift(double[] destination, Potential source, int[] separator,
		int[] domains, double[] shifts, double direction, State state) {
		Projection projection = projection(source, separator, domains);
		double minimum = Double.POSITIVE_INFINITY;
		for(int cell = 0; cell < destination.length; cell++) {
			state.scan();
			double value = destination[cell];
			if(value == Double.POSITIVE_INFINITY) {
				minimum = Math.min(minimum, value);
				continue;
			}
			double delta = direction * shifts[projection.cell(cell)];
			double updated = addDownSigned(value, delta);
			if(!Double.isFinite(updated))
				return Double.NaN;
			destination[cell] = updated;
			minimum = Math.min(minimum, updated);
		}
		return minimum;
	}

	private static double safeBound(List<Potential> potentials, double safetyMargin, State state) {
		double bound = 0d;
		for(Potential potential : potentials) {
			state.check();
			bound = addDownSigned(bound, potential.minimum);
		}
		if(bound == Double.POSITIVE_INFINITY)
			return bound;
		return addDownSigned(bound, -safetyMargin);
	}

	private static double forwardErrorMargin(double maximumSum, int factorCount) {
		if(factorCount <= 1 || maximumSum == 0d)
			return 0d;
		double unitRoundoff = Math.scalb(1d, -53);
		double operations = 2d * factorCount + 1d;
		double product = operations * unitRoundoff;
		if(product >= 0.5d)
			return Double.MAX_VALUE;
		double margin = maximumSum * (product / (1d - product));
		if(!Double.isFinite(margin))
			return Double.MAX_VALUE;
		double subnormalAllowance = factorCount * Double.MIN_VALUE;
		return addUp(Math.nextUp(margin), subnormalAllowance);
	}

	private static double addDownSigned(double left, double right) {
		if(left == Double.POSITIVE_INFINITY || right == Double.POSITIVE_INFINITY)
			return Double.POSITIVE_INFINITY;
		if(left == 0d)
			return right;
		if(right == 0d)
			return left;
		double sum = left + right;
		if(sum == Double.POSITIVE_INFINITY)
			return Double.MAX_VALUE;
		if(sum == Double.NEGATIVE_INFINITY)
			return Double.NEGATIVE_INFINITY;
		return Math.nextDown(sum);
	}

	private static double addUp(double left, double right) {
		double sum = left + right;
		return Double.isFinite(sum) ? Math.nextUp(sum) : Double.MAX_VALUE;
	}

	private static void validateOriginalCost(double value) {
		if(Double.isNaN(value) || value < 0d || value == Double.NEGATIVE_INFINITY
			|| Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(-0d))
			throw new IllegalArgumentException("COST_SHIFT_FACTOR_COST_INVALID|value=" + value);
	}

	private static Projection projection(Potential potential, int[] separator, int[] domains) {
		int[] positions = new int[separator.length];
		int[] separatorStrides = strides(separator, domains);
		for(int index = 0; index < separator.length; index++) {
			positions[index] = Arrays.binarySearch(potential.scope, separator[index]);
			if(positions[index] < 0)
				throw new IllegalStateException("COST_SHIFT_SEPARATOR_NOT_IN_SCOPE");
		}
		return new Projection(potential.strides, positions, separatorStrides, domains,
			potential.scope);
	}

	private static long cells(int[] scope, int[] domains) {
		long result = 1L;
		for(int variable : scope) {
			if(result > Long.MAX_VALUE / domains[variable])
				throw new MiniBucketLowerBound.ResourceLimitException("COST_SHIFT_CELL_OVERFLOW");
			result *= domains[variable];
		}
		return result;
	}

	private static long addCells(long left, long right, long limit) {
		if(left > Long.MAX_VALUE - right || left + right > limit)
			throw new MiniBucketLowerBound.ResourceLimitException(
				"COST_SHIFT_MATERIALIZED_LIMIT_EXCEEDED");
		return left + right;
	}

	private static int[] strides(int[] scope, int[] domains) {
		int[] result = new int[scope.length];
		int stride = 1;
		for(int position = scope.length - 1; position >= 0; position--) {
			result[position] = stride;
			stride = Math.multiplyExact(stride, domains[scope[position]]);
		}
		return result;
	}

	private static void decode(int cell, int[] scope, int[] domains, int[] global) {
		for(int position = scope.length - 1; position >= 0; position--) {
			global[scope[position]] = cell % domains[scope[position]];
			cell /= domains[scope[position]];
		}
	}

	private static long deadline(long started, long budgetNanos) {
		if(budgetNanos == Long.MAX_VALUE || started > Long.MAX_VALUE - budgetNanos)
			return Long.MAX_VALUE;
		return started + budgetNanos;
	}

	private static long saturatedMultiply(long left, long right) {
		return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
	}

	private record Prepared(List<Potential> potentials, long peakCells,
		long maximumFactorCells, double maximumFiniteOriginalSum) { }

	private static final class Potential {
		private final int[] scope;
		private final int[] strides;
		private double[] values;
		private double minimum;

		private Potential(int[] scope, int[] domains, double[] values) {
			this.scope = scope.clone();
			strides = strides(scope, domains);
			this.values = values;
		}
	}

	private record Projection(int[] clusterStrides, int[] positions,
		int[] separatorStrides, int[] domains, int[] scope) {
		private int cell(int clusterCell) {
			int result = 0;
			for(int index = 0; index < positions.length; index++) {
				int position = positions[index];
				int value = (clusterCell / clusterStrides[position]) % domains[scope[position]];
				result += value * separatorStrides[index];
			}
			return result;
		}
	}

	private static final class State {
		private final long started;
		private final long deadline;
		private final long maximumScannedCells;
		private final BooleanSupplier cancelled;
		private long scannedCells;

		private State(long started, long deadline, long maximumScannedCells,
			BooleanSupplier cancelled) {
			this.started = started;
			this.deadline = deadline;
			this.maximumScannedCells = maximumScannedCells;
			this.cancelled = cancelled;
		}

		private void check() {
			if(cancelled.getAsBoolean())
				throw new Stop(Reason.CANCELLED);
			if(deadline != Long.MAX_VALUE && System.nanoTime() - started >= deadline - started)
				throw new Stop(Reason.BUDGET_EXHAUSTED);
		}

		private void scan() {
			if(scannedCells >= maximumScannedCells)
				throw new Stop(Reason.WORK_LIMIT);
			scannedCells++;
			check();
		}
	}

	private static final class Stop extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final Reason reason;

		private Stop(Reason reason) { this.reason = reason; }
	}
}
