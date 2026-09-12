/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoublePredicate;

/**
 * Bounded dual lower bound over clusters made directly from original factors.
 *
 * <p>Every original factor is owned by exactly one cluster. Pairwise cluster
 * intersections define the consistency constraints. The implementation keeps a
 * zero-sum reparameterization across every separator, so the sum of exact cluster
 * minima is a lower bound on the original finite categorical problem after every
 * completed update. It makes no claim that coordinate diffusion reaches the
 * optimum of the corresponding LP relaxation.</p>
 */
final class RegionDualLowerBound {
	private RegionDualLowerBound() { }

	enum Reason {
		COMPLETED,
		TARGET_REACHED,
		BUDGET_EXHAUSTED,
		CANCELLED,
		RESOURCE_LIMIT,
		WORK_LIMIT
	}

	record Options(boolean mergeFactors, long maximumClusterCells,
		long maximumMaterializedCells, long maximumSeparatorCells, int maximumSweeps,
		long maximumScannedCells, long budgetNanos) {
		Options {
			if(maximumClusterCells <= 0 || maximumMaterializedCells <= 0
				|| maximumSeparatorCells <= 0)
				throw new IllegalArgumentException("REGION_DUAL_CELL_LIMIT_INVALID");
			if(maximumSweeps < 0)
				throw new IllegalArgumentException("REGION_DUAL_SWEEP_LIMIT_INVALID");
			if(maximumScannedCells <= 0)
				throw new IllegalArgumentException("REGION_DUAL_WORK_LIMIT_INVALID");
			if(budgetNanos < 0)
				throw new IllegalArgumentException("REGION_DUAL_BUDGET_INVALID");
		}
	}

	record Statistics(long preparationNanos, long updateNanos, long scannedCells,
		long updates, int sweeps, long materializedCells, long maximumClusterCells,
		long iterativeCells, long maximumIterativeClusterCells, boolean cancelled,
		boolean resourceLimited) { }

	record Result(double lowerBound, Prepared prepared, Statistics statistics, Reason reason) { }

	/** Immutable description of the exact cluster relaxation used by the bound. */
	static final class Prepared {
		private final int[] domains;
		private final List<Cluster> clusters;
		private final List<Separator> separators;
		private final long materializedCells;
		private final long maximumClusterCells;
		private final double safetyMargin;
		private final boolean mergedFactors;

		private Prepared(int[] domains, List<Cluster> clusters, List<Separator> separators,
			long materializedCells, long maximumClusterCells, double safetyMargin,
			boolean mergedFactors) {
			this.domains = domains.clone();
			this.clusters = List.copyOf(clusters);
			this.separators = List.copyOf(separators);
			this.materializedCells = materializedCells;
			this.maximumClusterCells = maximumClusterCells;
			this.safetyMargin = safetyMargin;
			this.mergedFactors = mergedFactors;
		}

		int[] domains() { return domains.clone(); }
		List<Cluster> clusters() { return clusters; }
		List<Separator> separators() { return separators; }
		long materializedCells() { return materializedCells; }
		long maximumClusterCells() { return maximumClusterCells; }
		double safetyMargin() { return safetyMargin; }
		boolean mergedFactors() { return mergedFactors; }
	}

	static final class Cluster {
		private final int[] scope;
		private final int[] originalFactors;
		private final double[] costs;

		private Cluster(int[] scope, int[] originalFactors, double[] costs) {
			this.scope = scope.clone();
			this.originalFactors = originalFactors.clone();
			this.costs = costs;
		}

		int[] scope() { return scope.clone(); }
		int[] originalFactors() { return originalFactors.clone(); }
		double[] costs() { return costs.clone(); }
	}

	static final class Separator {
		private final int leftCluster;
		private final int rightCluster;
		private final int[] scope;

		private Separator(int leftCluster, int rightCluster, int[] scope) {
			this.leftCluster = leftCluster;
			this.rightCluster = rightCluster;
			this.scope = scope.clone();
		}

		int leftCluster() { return leftCluster; }
		int rightCluster() { return rightCluster; }
		int[] scope() { return scope.clone(); }
	}

	static Result compute(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, Options options,
		BooleanSupplier cancelled) {
		return compute(variables, factors, options, cancelled, bound -> false, bound -> { });
	}

	static Result compute(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, Options options,
		BooleanSupplier cancelled, DoublePredicate targetReached, DoubleConsumer progress) {
		Objects.requireNonNull(variables, "variables");
		Objects.requireNonNull(factors, "factors");
		Objects.requireNonNull(options, "options");
		Objects.requireNonNull(cancelled, "cancelled");
		Objects.requireNonNull(targetReached, "targetReached");
		Objects.requireNonNull(progress, "progress");

		long started = System.nanoTime();
		State state = new State(started, deadline(started, options.budgetNanos()),
			options.maximumScannedCells(), cancelled);
		long preparationFinished = started;
		Prepared prepared = null;
		double published = 0d;
		long updates = 0L;
		int sweeps = 0;
		long iterativeCells = 0L;
		long maximumIterativeClusterCells = 0L;
		Reason reason = Reason.COMPLETED;
		try {
			state.check();
			Preparation construction = prepare(variables, factors, options, state);
			prepared = construction.prepared;
			List<int[]> iterativeScopes = iterativeScopes(prepared);
			List<Potential> potentials = new ArrayList<>(prepared.clusters.size());
			for(int clusterIndex = 0; clusterIndex < prepared.clusters.size(); clusterIndex++) {
				Cluster cluster = prepared.clusters.get(clusterIndex);
				Potential potential = options.maximumSweeps() == 0
					? new Potential(cluster.scope, construction.domains, cluster.costs)
					: eliminatePrivateVariables(cluster, iterativeScopes.get(clusterIndex),
						construction.domains, state);
				potentials.add(potential);
				iterativeCells = saturatedAdd(iterativeCells, potential.values.length);
				maximumIterativeClusterCells = Math.max(maximumIterativeClusterCells,
					potential.values.length);
			}
			List<UpdateEdge> updateEdges = new ArrayList<>(prepared.separators.size());
			for(Separator separator : prepared.separators) {
				Potential left = potentials.get(separator.leftCluster);
				Potential right = potentials.get(separator.rightCluster);
				updateEdges.add(new UpdateEdge(separator.leftCluster, separator.rightCluster,
					projection(left, separator.scope, construction.domains, state),
					projection(right, separator.scope, construction.domains, state)));
			}
			preparationFinished = System.nanoTime();
			published = Math.max(0d, safeBound(potentials, construction.safetyMargin, state));
			progress.accept(published);
			if(targetReached.test(published))
				reason = Reason.TARGET_REACHED;
			for(int sweep = 0; reason == Reason.COMPLETED && sweep < options.maximumSweeps(); sweep++) {
				boolean changed = false;
				for(UpdateEdge edge : updateEdges) {
					state.check();
					if(shiftPair(potentials.get(edge.leftCluster),
						potentials.get(edge.rightCluster), edge.leftProjection,
						edge.rightProjection, state)) {
						updates++;
						changed = true;
						double bound = Math.max(0d,
							safeBound(potentials, construction.safetyMargin, state));
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
		catch(ResourceLimit exception) {
			reason = Reason.RESOURCE_LIMIT;
		}
		long finished = System.nanoTime();
		if(preparationFinished == started)
			preparationFinished = finished;
		long materialized = prepared == null ? 0L : prepared.materializedCells;
		long maximum = prepared == null ? 0L : prepared.maximumClusterCells;
		Statistics statistics = new Statistics(preparationFinished - started,
			Math.max(0L, finished - preparationFinished), state.scannedCells, updates, sweeps,
			materialized, maximum, iterativeCells, maximumIterativeClusterCells,
			reason == Reason.CANCELLED,
			reason == Reason.RESOURCE_LIMIT || reason == Reason.WORK_LIMIT);
		return new Result(published, prepared, statistics, reason);
	}

	private static Preparation prepare(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, Options options, State state) {
		int[] domains = new int[variables.size()];
		Map<ExactCategoricalSolver.Variable, Integer> indices = new IdentityHashMap<>();
		for(int index = 0; index < variables.size(); index++) {
			state.check();
			if(indices.put(variables.get(index), index) != null)
				throw new IllegalArgumentException("REGION_DUAL_VARIABLE_DUPLICATED");
			domains[index] = variables.get(index).domainSize();
		}
		List<int[]> callerScopes = new ArrayList<>(factors.size());
		for(ExactCategoricalSolver.Factor factor : factors) {
			state.check();
			int[] caller = new int[factor.scope().size()];
			for(int position = 0; position < caller.length; position++) {
				Integer index = indices.get(factor.scope().get(position));
				if(index == null)
					throw new IllegalArgumentException("REGION_DUAL_FACTOR_VARIABLE_UNKNOWN");
				caller[position] = index;
			}
			int[] sorted = caller.clone();
			Arrays.sort(sorted);
			for(int position = 1; position < sorted.length; position++)
				if(sorted[position] == sorted[position - 1])
					throw new IllegalArgumentException("REGION_DUAL_FACTOR_SCOPE_DUPLICATED");
			callerScopes.add(caller);
		}

		double[] factorMaxima = new double[factors.size()];
		Arrays.fill(factorMaxima, Double.NEGATIVE_INFINITY);
		OriginalTable[] originalTables = new OriginalTable[factors.size()];
		List<Integer> constantFactors = new ArrayList<>();
		double constantCost = 0d;
		long retainedOriginalCells = 0L;
		long maximumOriginalPreparationCells = 0L;
		int[] global = new int[variables.size()];
		for(int factorIndex = 0; factorIndex < factors.size(); factorIndex++) {
			state.check();
			int[] informativeScope = Arrays.stream(callerScopes.get(factorIndex))
				.filter(index -> domains[index] > 1).sorted().toArray();
			long tableCells = cells(informativeScope, domains);
			if(tableCells > options.maximumClusterCells() || tableCells > Integer.MAX_VALUE)
				throw new ResourceLimit();
			int count = Math.toIntExact(tableCells);
			long transientCells = addCells(retainedOriginalCells, tableCells,
				options.maximumMaterializedCells());
			maximumOriginalPreparationCells = Math.max(maximumOriginalPreparationCells,
				transientCells);
			double[] values = new double[count];
			int[] local = new int[callerScopes.get(factorIndex).length];
			double first = Double.NaN;
			boolean invariant = true;
			for(int cell = 0; cell < count; cell++) {
				state.scan();
				decode(cell, informativeScope, domains, global);
				int[] callerScope = callerScopes.get(factorIndex);
				for(int position = 0; position < local.length; position++)
					local[position] = global[callerScope[position]];
				double value = factors.get(factorIndex).cost(local);
				validateOriginalCost(value);
				values[cell] = value;
				if(Double.isFinite(value))
					factorMaxima[factorIndex] = Math.max(factorMaxima[factorIndex], value);
				if(cell == 0)
					first = value;
				else if(Double.doubleToLongBits(value) != Double.doubleToLongBits(first))
					invariant = false;
			}
			if(invariant) {
				constantFactors.add(factorIndex);
				constantCost = addDownSigned(constantCost, first);
			}
			else {
				originalTables[factorIndex] = new OriginalTable(informativeScope, values);
				retainedOriginalCells = addCells(retainedOriginalCells, count,
					options.maximumMaterializedCells());
			}
		}

		List<ClusterPlan> plans = aggregate(originalTables, domains, options, state);
		if(!constantFactors.isEmpty())
			plans.add(new ClusterPlan(new int[0], new ArrayList<>(constantFactors)));
		long persistentCells = 0L;
		long maximumClusterCells = 0L;
		for(ClusterPlan plan : plans) {
			long count = cells(plan.scope, domains);
			if(count > options.maximumClusterCells() || count > Integer.MAX_VALUE)
				throw new ResourceLimit();
			persistentCells = addCells(persistentCells, count,
				options.maximumMaterializedCells());
			maximumClusterCells = Math.max(maximumClusterCells, count);
		}

		List<Separator> separators = new ArrayList<>();
		long maximumPairScratch = 0L;
		for(int left = 0; left < plans.size(); left++) {
			for(int right = left + 1; right < plans.size(); right++) {
				state.check();
				int[] intersection = intersection(plans.get(left).scope, plans.get(right).scope);
				if(intersection.length == 0)
					continue;
				long separatorCells = cells(intersection, domains);
				if(separatorCells > options.maximumSeparatorCells()
					|| separatorCells > Integer.MAX_VALUE)
					throw new ResourceLimit();
				separators.add(new Separator(left, right, intersection));
				long pairScratch = cells(plans.get(left).scope, domains)
					+ cells(plans.get(right).scope, domains);
				pairScratch = saturatedAdd(pairScratch, 3L * separatorCells);
				maximumPairScratch = Math.max(maximumPairScratch, pairScratch);
			}
		}
		separators = independentSeparators(separators, plans.size(), state);
		long projectionCells = 0L;
		for(Separator separator : separators) {
			projectionCells = addCells(projectionCells,
				cells(plans.get(separator.leftCluster).scope, domains), options.maximumMaterializedCells());
			projectionCells = addCells(projectionCells,
				cells(plans.get(separator.rightCluster).scope, domains), options.maximumMaterializedCells());
		}
		long preparationPeak = addCells(retainedOriginalCells, persistentCells,
			options.maximumMaterializedCells());
		preparationPeak = Math.max(preparationPeak, maximumOriginalPreparationCells);
		long updatePeak = persistentCells;
		// Count each cached int projection as a full table cell conservatively.
		updatePeak = addCells(updatePeak, projectionCells, options.maximumMaterializedCells());
		if(options.maximumSweeps() > 0) {
			long updatePersistent = addCells(updatePeak, persistentCells,
				options.maximumMaterializedCells());
			updatePeak = addCells(updatePersistent, maximumPairScratch,
				options.maximumMaterializedCells());
		}
		long peakCells = Math.max(preparationPeak, updatePeak);

		List<Cluster> clusters = new ArrayList<>(plans.size());
		for(ClusterPlan plan : plans) {
			int count = Math.toIntExact(cells(plan.scope, domains));
			double[] costs = new double[count];
			boolean constantCluster = plan.scope.length == 0 && !plan.factors.isEmpty()
				&& originalTables[plan.factors.get(0)] == null;
			for(int cell = 0; cell < count; cell++) {
				state.scan();
				decode(cell, plan.scope, domains, global);
				double total = constantCluster ? constantCost : 0d;
				if(!constantCluster)
					for(int factorIndex : plan.factors)
						total = addDownSigned(total,
							originalTables[factorIndex].value(global, domains));
				costs[cell] = total;
			}
			clusters.add(new Cluster(plan.scope, plan.factors.stream().mapToInt(i -> i).toArray(),
				costs));
		}
		double maximumFiniteOriginalSum = 0d;
		for(double maximum : factorMaxima)
			if(maximum != Double.NEGATIVE_INFINITY)
				maximumFiniteOriginalSum = addUp(maximumFiniteOriginalSum, maximum);
		double safetyMargin = forwardErrorMargin(maximumFiniteOriginalSum, factors.size());
		Prepared prepared = new Prepared(domains, clusters, separators, peakCells,
			maximumClusterCells, safetyMargin, options.mergeFactors());
		return new Preparation(prepared, domains, safetyMargin);
	}

	/** Equality on S is implied by a path whose every separator contains S. */
	private static List<Separator> independentSeparators(List<Separator> edges,
		int clusterCount, State state) {
		List<Separator> ordered = new ArrayList<>(edges);
		ordered.sort(Comparator.<Separator>comparingInt(edge -> edge.scope.length).reversed());
		Map<List<Integer>, List<Separator>> groups = new LinkedHashMap<>();
		for(Separator edge : ordered) {
			state.check();
			groups.computeIfAbsent(Arrays.stream(edge.scope).boxed().toList(),
				ignored -> new ArrayList<>()).add(edge);
		}
		List<Separator> retained = new ArrayList<>();
		for(List<Separator> group : groups.values()) {
			int[] scope = group.get(0).scope;
			int[] parents = new int[clusterCount];
			for(int cluster = 0; cluster < clusterCount; cluster++)
				parents[cluster] = cluster;
			for(Separator prior : retained) {
				state.check();
				if(intersectionSize(prior.scope, scope) == scope.length)
					parents[root(parents, prior.leftCluster)] = root(parents, prior.rightCluster);
			}
			for(Separator edge : group) {
				state.check();
				int left = root(parents, edge.leftCluster);
				int right = root(parents, edge.rightCluster);
				if(left != right) {
					retained.add(edge);
					parents[left] = right;
				}
			}
		}
		return retained;
	}

	private static int root(int[] parents, int vertex) {
		while(parents[vertex] != vertex) {
			parents[vertex] = parents[parents[vertex]];
			vertex = parents[vertex];
		}
		return vertex;
	}

	private static List<ClusterPlan> aggregate(OriginalTable[] tables, int[] domains,
		Options options, State state) {
		List<Integer> order = new ArrayList<>(tables.length);
		for(int factor = 0; factor < tables.length; factor++)
			if(tables[factor] != null)
				order.add(factor);
		order.sort(Comparator.<Integer>comparingInt(factor -> tables[factor].scope.length)
			.reversed().thenComparingInt(factor -> factor));
		List<ClusterPlan> result = new ArrayList<>();
		for(int factor : order) {
			state.check();
			int[] scope = tables[factor].scope;
			int selected = -1;
			int selectedOverlap = -1;
			long selectedCells = Long.MAX_VALUE;
			if(options.mergeFactors() && scope.length > 0) {
				for(int candidate = 0; candidate < result.size(); candidate++) {
					ClusterPlan cluster = result.get(candidate);
					int overlap = intersectionSize(cluster.scope, scope);
					if(overlap == 0)
						continue;
					int[] union = union(cluster.scope, scope);
					long count = cells(union, domains);
					if(count <= options.maximumClusterCells()
						&& (overlap > selectedOverlap
							|| overlap == selectedOverlap && count < selectedCells)) {
						selected = candidate;
						selectedOverlap = overlap;
						selectedCells = count;
					}
				}
			}
			if(selected < 0)
				result.add(new ClusterPlan(scope.clone(), new ArrayList<>(List.of(factor))));
			else {
				ClusterPlan cluster = result.get(selected);
				cluster.scope = union(cluster.scope, scope);
				cluster.factors.add(factor);
			}
		}
		return result;
	}

	private static List<int[]> iterativeScopes(Prepared prepared) {
		List<int[]> scopes = new ArrayList<>(prepared.clusters.size());
		for(int cluster = 0; cluster < prepared.clusters.size(); cluster++)
			scopes.add(new int[0]);
		for(Separator separator : prepared.separators) {
			scopes.set(separator.leftCluster,
				union(scopes.get(separator.leftCluster), separator.scope));
			scopes.set(separator.rightCluster,
				union(scopes.get(separator.rightCluster), separator.scope));
		}
		return scopes;
	}

	private static Potential eliminatePrivateVariables(Cluster cluster, int[] retainedScope,
		int[] domains, State state) {
		double[] values = new double[Math.toIntExact(cells(retainedScope, domains))];
		Arrays.fill(values, Double.POSITIVE_INFINITY);
		int[] fullStrides = strides(cluster.scope, domains);
		int[] retainedStrides = strides(retainedScope, domains);
		int[] retainedPositions = new int[retainedScope.length];
		for(int index = 0; index < retainedScope.length; index++) {
			retainedPositions[index] = Arrays.binarySearch(cluster.scope, retainedScope[index]);
			if(retainedPositions[index] < 0)
				throw new IllegalStateException("REGION_DUAL_ITERATIVE_SCOPE_INVALID");
		}
		for(int cell = 0; cell < cluster.costs.length; cell++) {
			state.scan();
			int retainedCell = 0;
			for(int index = 0; index < retainedPositions.length; index++) {
				int position = retainedPositions[index];
				int value = (cell / fullStrides[position]) % domains[cluster.scope[position]];
				retainedCell += value * retainedStrides[index];
			}
			values[retainedCell] = Math.min(values[retainedCell], cluster.costs[cell]);
		}
		return new Potential(retainedScope, domains, values);
	}

	private static boolean shiftPair(Potential left, Potential right,
		Projection leftProjection, Projection rightProjection, State state) {
		int separatorCells = leftProjection.separatorCells();
		if(separatorCells != rightProjection.separatorCells())
			throw new IllegalStateException("REGION_DUAL_SEPARATOR_SIZE_MISMATCH");
		double[] leftMarginal = new double[separatorCells];
		double[] rightMarginal = new double[separatorCells];
		Arrays.fill(leftMarginal, Double.POSITIVE_INFINITY);
		Arrays.fill(rightMarginal, Double.POSITIVE_INFINITY);
		minMarginal(left, leftProjection, leftMarginal, state);
		minMarginal(right, rightProjection, rightMarginal, state);
		double[] shifts = new double[separatorCells];
		boolean any = false;
		for(int cell = 0; cell < separatorCells; cell++) {
			state.check();
			if(!Double.isFinite(leftMarginal[cell]) || !Double.isFinite(rightMarginal[cell])) {
				// Equality forbids a separator tuple on both sides if either side
				// has no finite support. This preserves every original feasible plan.
				any |= Double.isFinite(leftMarginal[cell]) != Double.isFinite(rightMarginal[cell]);
				continue;
			}
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
		double nextLeftMinimum = applyShift(nextLeft, leftProjection, shifts,
			rightMarginal, -1d, state);
		if(Double.isNaN(nextLeftMinimum))
			return false;
		double nextRightMinimum = applyShift(nextRight, rightProjection, shifts,
			leftMarginal, 1d, state);
		if(Double.isNaN(nextRightMinimum))
			return false;
		left.values = nextLeft;
		left.minimum = nextLeftMinimum;
		right.values = nextRight;
		right.minimum = nextRightMinimum;
		return true;
	}

	private static void minMarginal(Potential potential, Projection projection,
		double[] marginal, State state) {
		for(int cell = 0; cell < potential.values.length; cell++) {
			state.scan();
			int projected = projection.cell(cell);
			marginal[projected] = Math.min(marginal[projected], potential.values[cell]);
		}
	}

	private static double applyShift(double[] destination, Projection projection,
		double[] shifts, double[] otherMarginal, double direction, State state) {
		double minimum = Double.POSITIVE_INFINITY;
		for(int cell = 0; cell < destination.length; cell++) {
			state.scan();
			double value = destination[cell];
			if(value == Double.POSITIVE_INFINITY) {
				minimum = Math.min(minimum, value);
				continue;
			}
			int projected = projection.cell(cell);
			if(otherMarginal[projected] == Double.POSITIVE_INFINITY) {
				destination[cell] = Double.POSITIVE_INFINITY;
				continue;
			}
			double updated = addDownSigned(value,
				direction * shifts[projected]);
			if(!Double.isFinite(updated))
				return Double.NaN;
			destination[cell] = updated;
			minimum = Math.min(minimum, updated);
		}
		return minimum;
	}

	private static double safeBound(List<Potential> potentials, double safetyMargin,
		State state) {
		double bound = 0d;
		for(Potential potential : potentials) {
			state.check();
			bound = addDownSigned(bound, potential.minimum);
		}
		if(bound == Double.POSITIVE_INFINITY)
			return bound;
		return addDownSigned(bound, -safetyMargin);
	}

	private static Projection projection(Potential potential, int[] separator, int[] domains,
		State state) {
		int[] positions = new int[separator.length];
		int[] separatorStrides = strides(separator, domains);
		for(int index = 0; index < separator.length; index++) {
			positions[index] = Arrays.binarySearch(potential.scope, separator[index]);
			if(positions[index] < 0)
				throw new IllegalStateException("REGION_DUAL_SEPARATOR_NOT_IN_SCOPE");
		}
		int[] cellMap = new int[potential.values.length];
		for(int cell = 0; cell < cellMap.length; cell++) {
			state.scan();
			int projected = 0;
			for(int index = 0; index < positions.length; index++) {
				int position = positions[index];
				int value = (cell / potential.strides[position]) % domains[potential.scope[position]];
				projected += value * separatorStrides[index];
			}
			cellMap[cell] = projected;
		}
		return new Projection(cellMap, Math.toIntExact(cells(separator, domains)));
	}

	private static int[] intersection(int[] left, int[] right) {
		int[] result = new int[Math.min(left.length, right.length)];
		int l = 0, r = 0, count = 0;
		while(l < left.length && r < right.length) {
			if(left[l] == right[r]) {
				result[count++] = left[l];
				l++;
				r++;
			}
			else if(left[l] < right[r])
				l++;
			else
				r++;
		}
		return Arrays.copyOf(result, count);
	}

	private static int intersectionSize(int[] left, int[] right) {
		return intersection(left, right).length;
	}

	private static int[] union(int[] left, int[] right) {
		int[] result = new int[left.length + right.length];
		int l = 0, r = 0, count = 0;
		while(l < left.length || r < right.length) {
			int value;
			if(r >= right.length || l < left.length && left[l] < right[r])
				value = left[l++];
			else if(l >= left.length || right[r] < left[l])
				value = right[r++];
			else {
				value = left[l++];
				r++;
			}
			result[count++] = value;
		}
		return Arrays.copyOf(result, count);
	}

	private static long cells(int[] scope, int[] domains) {
		long result = 1L;
		for(int variable : scope) {
			if(result > Long.MAX_VALUE / domains[variable])
				throw new ResourceLimit();
			result *= domains[variable];
		}
		return result;
	}

	private static long addCells(long left, long right, long limit) {
		long sum = saturatedAdd(left, right);
		if(sum > limit)
			throw new ResourceLimit();
		return sum;
	}

	private static long saturatedAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
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

	private static void validateOriginalCost(double value) {
		if(Double.isNaN(value) || value < 0d || value == Double.NEGATIVE_INFINITY
			|| Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(-0d))
			throw new IllegalArgumentException("REGION_DUAL_FACTOR_COST_INVALID|value=" + value);
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
		return addUp(Math.nextUp(margin), factorCount * Double.MIN_VALUE);
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

	private static long deadline(long started, long budgetNanos) {
		if(budgetNanos == Long.MAX_VALUE || started > Long.MAX_VALUE - budgetNanos)
			return Long.MAX_VALUE;
		return started + budgetNanos;
	}

	private record Preparation(Prepared prepared, int[] domains, double safetyMargin) { }

	private static final class ClusterPlan {
		private int[] scope;
		private final List<Integer> factors;

		private ClusterPlan(int[] scope, List<Integer> factors) {
			this.scope = scope;
			this.factors = factors;
		}
	}

	private static final class OriginalTable {
		private final int[] scope;
		private final double[] values;

		private OriginalTable(int[] scope, double[] values) {
			this.scope = scope;
			this.values = values;
		}

		private double value(int[] global, int[] domains) {
			int cell = 0;
			for(int position = 0; position < scope.length; position++)
				cell = cell * domains[scope[position]] + global[scope[position]];
			return values[cell];
		}
	}

	private static final class Potential {
		private final int[] scope;
		private final int[] strides;
		private double[] values;
		private double minimum;

		private Potential(int[] scope, int[] domains, double[] values) {
			this.scope = scope;
			this.strides = strides(scope, domains);
			this.values = values;
			this.minimum = Arrays.stream(values).min().orElse(Double.POSITIVE_INFINITY);
		}
	}

	private record Projection(int[] cells, int separatorCells) {
		private int cell(int clusterCell) {
			return cells[clusterCell];
		}
	}

	private record UpdateEdge(int leftCluster, int rightCluster,
		Projection leftProjection, Projection rightProjection) { }

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
			// Cell limits remain exact. Poll cancellation/time at bounded intervals;
			// reading the clock for every cheap table cell dominated the update cost.
			if((scannedCells & 255L) == 0L)
				check();
		}
	}

	private static final class Stop extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final Reason reason;

		private Stop(Reason reason) { this.reason = reason; }
	}

	private static final class ResourceLimit extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
