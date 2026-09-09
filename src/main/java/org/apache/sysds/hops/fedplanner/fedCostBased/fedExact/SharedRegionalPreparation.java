/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/** Run-owned encoded tables and root reduction, shared by Regional and its global certificate. */
final class SharedRegionalPreparation implements LocalCategoricalOptimizer.BlockPreparation {
	static final String PROPERTY = "sysds.fedplanner.regional.sharedPreparation";
	private final RegionalSearchProblem problem;
	private final Limits limits;
	private final boolean compact;
	private ExactPhysicalReducedSolver.CompactModel root;
	private final List<int[]> scopes = new ArrayList<>();
	private final List<List<Integer>> incidence = new ArrayList<>();
	private Conditioned[] cache;
	private long cacheHits;
	private long tableBuilds;
	private long unchangedTables;
	private long conditionNanos;
	private long blockPreparationNanos;
	private long blocks;
	private long fallbacks;

	/** One entry per source factor: cache cells can never exceed root input cells. */
	private record Conditioned(int[] boundary, Factor factor) { }

	SharedRegionalPreparation(RegionalSearchProblem problem, Limits limits, boolean compact) {
		this.problem = problem;
		this.limits = limits;
		this.compact = compact;
	}

	static boolean configured() {
		String value = System.getProperty(PROPERTY, "true");
		if(!value.equals("true") && !value.equals("false"))
			throw new IllegalArgumentException("REGIONAL_SHARED_PREPARATION_INVALID|value=" + value);
		return Boolean.parseBoolean(value);
	}

	private void initialize() {
		if(root != null)
			return;
		root = problem.reducedRoot(limits);
		LocalCategoricalOptimizer.tracePreparation("shared-root", root.preparationStatistics());
		if(root.variables().size() != problem.variables().size())
			throw new IllegalStateException("REGIONAL_SHARED_ROOT_VARIABLE_REMOVAL");
		IdentityHashMap<Variable,Integer> positions = new IdentityHashMap<>();
		for(int i = 0; i < root.variables().size(); i++) {
			positions.put(root.variables().get(i), i);
			incidence.add(new ArrayList<>());
		}
		for(Factor factor : root.factors()) {
			int[] scope = factor.scope().stream().mapToInt(positions::get).toArray();
			for(int variable : scope)
				incidence.get(variable).add(scopes.size());
			scopes.add(scope);
		}
		cache = new Conditioned[scopes.size()];
	}

	@Override
	public LocalCategoricalOptimizer.PreparedBlockSolver prepare(int[] assignment, int[] block) {
		long started = System.nanoTime();
		try {
			initialize();
			return prepareReduced(assignment, block);
		}
		catch(IllegalArgumentException failure) {
			if(!RegionalSearchProblem.isResourceLimit(failure))
				throw failure;
			// An optional shared preparation must not remove the original Regional path.
			fallbacks++;
			return null;
		}
		finally { blockPreparationNanos += System.nanoTime() - started; }
	}

	private LocalCategoricalOptimizer.PreparedBlockSolver prepareReduced(int[] assignment, int[] block) {
		int decisions = problem.decisionCount();
		boolean[] free = new boolean[root.variables().size()];
		boolean[] selected = new boolean[scopes.size()];
		ArrayDeque<Integer> frontier = new ArrayDeque<>();
		for(int original : block) {
			free[original] = true;
			frontier.add(original);
		}
		// Traverse through auxiliaries only. Fixed original decisions are boundaries,
		// but every factor constraining an included auxiliary must remain present.
		while(!frontier.isEmpty()) {
			int variable = frontier.removeFirst();
			for(int factor : incidence.get(variable)) {
				if(selected[factor])
					continue;
				selected[factor] = true;
				for(int next : scopes.get(factor))
					if(next >= decisions && !free[next]) {
						free[next] = true;
						frontier.add(next);
					}
			}
		}
		List<Integer> indexes = new ArrayList<>();
		for(int original : block)
			indexes.add(original);
		for(int auxiliary = decisions; auxiliary < free.length; auxiliary++)
			if(free[auxiliary])
				indexes.add(auxiliary);
		List<Variable> variables = indexes.stream().map(root.variables()::get).toList();
		List<Factor> factors = new ArrayList<>();
		long conditionStarted = System.nanoTime();
		for(int factor = 0; factor < selected.length; factor++) {
			if(!selected[factor])
				continue;
			int[] scope = scopes.get(factor);
			int[] boundary = new int[scope.length];
			boolean allFree = true;
			for(int i = 0; i < scope.length; i++) {
				int variable = scope[i];
				boundary[i] = free[variable] ? -1 : root.reducedValue(variable, assignment[variable]);
				if(!free[variable]) {
					allFree = false;
					if(boundary[i] < 0) {
						// A hard-repair intermediate can use an unsupported original value.
						// Preserve the old conditional repair/expansion semantics in that case.
						fallbacks++;
						conditionNanos += System.nanoTime() - conditionStarted;
						return null;
					}
				}
			}
			Factor source = root.factors().get(factor);
			if(allFree) {
				unchangedTables++;
				factors.add(source);
			}
			else if(cache[factor] != null && Arrays.equals(boundary, cache[factor].boundary())) {
				cacheHits++;
				factors.add(cache[factor].factor());
			}
			else {
				Factor conditioned = condition(source, boundary);
				cache[factor] = new Conditioned(boundary, conditioned);
				tableBuilds++;
				factors.add(conditioned);
			}
		}
		conditionNanos += System.nanoTime() - conditionStarted;
		LocalCategoricalOptimizer.PreparedBlockSolver solver;
		if(compact) {
			ExactPhysicalReducedSolver.Prepared prepared = ExactPhysicalReducedSolver.prepareCompacted(
				block.length, variables, factors, limits);
			LocalCategoricalOptimizer.tracePreparation("encoded-regional-block", prepared.preparationStatistics());
			solver = () -> ExactPhysicalReducedSolver.solve(prepared);
		}
		else {
			// Support/quotient preprocessing belongs to the shared root. The slice
			// reuses those immutable domains and tables; only its elimination plan is new.
			long compileStarted = System.nanoTime();
			ExactCategoricalSolver.CompiledProblem compiled = ExactCategoricalSolver.compile(variables, factors, limits);
			long compileNanos = System.nanoTime() - compileStarted;
			LocalCategoricalOptimizer.tracePreparation("encoded-regional-block",
				new ExactPhysicalReducedSolver.PreparationStatistics(0, 0, 0, 0, compileNanos, compileNanos));
			solver = () -> ExactCategoricalSolver.solve(compiled);
		}
		blocks++;
		int[] originalBlock = block.clone();
		return () -> {
			ExactCategoricalSolver.Result solved = solver.solve();
			List<Integer> originalValues = new ArrayList<>();
			for(int i = 0; i < originalBlock.length; i++)
				originalValues.add(root.sourceValue(originalBlock[i], solved.assignmentInVariableOrder().get(i)));
			return new ExactCategoricalSolver.Result(solved.objective(), originalValues, solved.statistics());
		};
	}

	private static Factor condition(Factor source, int[] boundary) {
		List<Variable> free = new ArrayList<>();
		int[] stride = new int[boundary.length];
		int cells = 1;
		int base = 0;
		for(int i = boundary.length - 1; i >= 0; i--) {
			stride[i] = cells;
			cells = Math.multiplyExact(cells, source.scope().get(i).domainSize());
			if(boundary[i] >= 0)
				base += stride[i] * boundary[i];
		}
		List<Integer> freeStrides = new ArrayList<>();
		for(int i = 0; i < boundary.length; i++)
			if(boundary[i] < 0) {
				free.add(source.scope().get(i));
				freeStrides.add(stride[i]);
			}
		int offset = base;
		int[] steps = freeStrides.stream().mapToInt(Integer::intValue).toArray();
		// Each conditioned table is no larger than its already-preflighted root table.
		// Lookup uses a dense offset, with no allocation/evaluator call per cell.
		return ExactCategoricalSolver.freezeValidatedFactor(Factor.lazy(free, values -> {
			int cell = offset;
			for(int i = 0; i < values.length; i++)
				cell += values[i] * steps[i];
			return source.denseCostAt(cell);
		}));
	}

	long cacheHits() { return cacheHits; }
	long tableBuilds() { return tableBuilds; }
	long blocks() { return blocks; }
	long fallbacks() { return fallbacks; }

	void trace() {
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("DP-RegionalSharedPreparation",
				"encoded=true compact=" + compact + " rootBuilds=" + (root == null ? 0 : 1)
					+ " rootPreparationNanos=" + problem.reducedRootNanos()
					+ " conditionNanos=" + conditionNanos + " blockPreparationNanos=" + blockPreparationNanos
					+ " blocks=" + blocks + " unchangedTables=" + unchangedTables
					+ " conditionedTableBuilds=" + tableBuilds + " conditionedTableHits=" + cacheHits
					+ " resourceOrUnsupportedBoundaryFallbacks=" + fallbacks
					+ " cachePolicy=one-entry-per-root-factor");
	}
}
