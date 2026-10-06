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
	static final String FAST_BLOCK_ORDER_PROPERTY = "sysds.fedplanner.regional.fastBlockOrder";
	static final String FAST_BLOCK_ASSIGNMENTS_PROPERTY =
		"sysds.fedplanner.regional.fastBlockAssignments";
	private final RegionalSearchProblem problem;
	private final Limits rootLimits;
	private final Limits limits;
	private final long maximumBlockAssignments;
	private final long maximumAdditionalBlockCells;
	private final boolean compact;
	private final ExactEliminationOrderPolicy.Configuration orderPolicy;
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
	private long fastBlockOrderAccepted;
	private long fastBlockOrderFallbacks;
	private long maximumFastBlockOrderAssignments;
	private String lastFallbackReason;

	/** One entry per source factor: cache cells can never exceed root input cells. */
	private record Conditioned(int[] boundary, Factor factor) { }

	SharedRegionalPreparation(RegionalSearchProblem problem, Limits limits, boolean compact) {
		this(problem,limits,limits,0L,-1L,compact);
	}

	SharedRegionalPreparation(RegionalSearchProblem problem, Limits rootLimits,
		Limits blockLimits, long maximumBlockAssignments, long maximumAdditionalBlockCells,
		boolean compact) {
		this.problem = problem;
		this.rootLimits = rootLimits;
		this.limits = blockLimits;
		if(maximumBlockAssignments < 0)
			throw new IllegalArgumentException("REGIONAL_BLOCK_ASSIGNMENTS_LIMIT_INVALID");
		this.maximumBlockAssignments = maximumBlockAssignments;
		if(maximumAdditionalBlockCells == 0)
			throw new IllegalArgumentException("REGIONAL_BLOCK_CELLS_LIMIT_INVALID");
		this.maximumAdditionalBlockCells = maximumAdditionalBlockCells;
		this.compact = compact;
		orderPolicy = ExactEliminationOrderPolicy.localConfigured(compact);
	}

	static boolean configuredFastBlockOrder() {
		String value = System.getProperty(FAST_BLOCK_ORDER_PROPERTY, "false");
		if(!value.equals("true") && !value.equals("false"))
			throw new IllegalArgumentException("REGIONAL_FAST_BLOCK_ORDER_INVALID|value=" + value);
		return Boolean.parseBoolean(value);
	}

	static long configuredFastBlockAssignments() {
		String value = System.getProperty(FAST_BLOCK_ASSIGNMENTS_PROPERTY, "100000");
		try {
			long parsed = Long.parseLong(value);
			if(parsed <= 0)
				throw new NumberFormatException();
			return parsed;
		}
		catch(NumberFormatException failure) {
			throw new IllegalArgumentException("REGIONAL_FAST_BLOCK_ASSIGNMENTS_INVALID|value=" + value,
				failure);
		}
	}

	private void initialize() {
		if(root != null)
			return;
		root = problem.reducedRoot(rootLimits);
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
		lastFallbackReason = null;
		try {
			initialize();
			return prepareReduced(assignment, block);
		}
		catch(IllegalArgumentException failure) {
			if(!RegionalSearchProblem.isResourceLimit(failure))
				throw failure;
			// An optional shared preparation must not remove the original Regional path.
			fallbacks++;
			lastFallbackReason = failure.getMessage() == null ?
				"RESOURCE_LIMIT" : failure.getMessage();
			return null;
		}
		finally { blockPreparationNanos += System.nanoTime() - started; }
	}

	@Override
	public int[] expandRepairBlock(int[] assignment, int[] block) {
		try {
			initialize();
			int decisions = problem.decisionCount();
			boolean[] free = new boolean[root.variables().size()];
			boolean[] selected = new boolean[scopes.size()];
			ArrayDeque<Integer> frontier = new ArrayDeque<>();
			for(int original : block) {
				free[original] = true;
				frontier.add(original);
			}
			while(!frontier.isEmpty()) {
				int variable = frontier.removeFirst();
				for(int factor : incidence.get(variable)) {
					if(selected[factor])
						continue;
					selected[factor] = true;
					for(int next : scopes.get(factor)) {
						boolean unsupportedDecision = next < decisions && !free[next]
							&& root.reducedValue(next, assignment[next]) < 0;
						if((next >= decisions || unsupportedDecision) && !free[next]) {
							free[next] = true;
							frontier.add(next);
						}
					}
				}
			}
			int[] expanded = new int[decisions];
			int count = 0;
			for(int decision = 0; decision < decisions; decision++)
				if(free[decision])
					expanded[count++] = decision;
			return Arrays.copyOf(expanded, count);
		}
		catch(IllegalArgumentException failure) {
			if(!RegionalSearchProblem.isResourceLimit(failure))
				throw failure;
			return block;
		}
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
		Limits solveLimits = conditionalLimits(selected,free);
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
						lastFallbackReason = "UNSUPPORTED_INCUMBENT_BOUNDARY";
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
				block.length, variables, factors, solveLimits, orderPolicy, "local-shared-compact");
			recordOrder(prepared.orderCompilation());
			LocalCategoricalOptimizer.tracePreparation("encoded-regional-block", prepared.preparationStatistics());
			tracePreSolveWork(block, variables, factors, prepared.statistics(),
				prepared.compiledVariableCount(), "local-shared-compact", solveLimits);
			if(maximumBlockAssignments > 0
				&& prepared.statistics().maximumEliminationAssignments() > maximumBlockAssignments)
				throw new IllegalArgumentException("EXACT_VE_ASSIGNMENT_LIMIT_EXCEEDED|source=local-shared-compact"
					+ "|assignments=" + prepared.statistics().maximumEliminationAssignments()
					+ "|limit=" + maximumBlockAssignments);
			solver = () -> ExactPhysicalReducedSolver.solve(prepared);
		}
		else {
			// Support/quotient preprocessing belongs to the shared root. The slice
			// reuses those immutable domains and tables; only its elimination plan is new.
			long compileStarted = System.nanoTime();
			ExactCategoricalSolver.OrderCompilation compilation =
				ExactEliminationOrderPolicy.compile(variables,factors,solveLimits,orderPolicy,
					"local-shared");
			ExactCategoricalSolver.CompiledProblem compiled = compilation.compiled();
			recordOrder(compilation);
			if(maximumBlockAssignments > 0
				&& ExactCategoricalSolver.statistics(compiled).maximumEliminationAssignments()
				> maximumBlockAssignments)
				compiled = ExactCategoricalSolver.compileWithinMaximumEliminationAssignments(
					variables,factors,solveLimits,maximumBlockAssignments);
			long compileNanos = System.nanoTime() - compileStarted;
			LocalCategoricalOptimizer.tracePreparation("encoded-regional-block",
				new ExactPhysicalReducedSolver.PreparationStatistics(0, 0, 0, 0, compileNanos, compileNanos));
			ExactCategoricalSolver.Statistics statistics = ExactCategoricalSolver.statistics(compiled);
			tracePreSolveWork(block, variables, factors, statistics,
				statistics.eliminationOrder().size(), "local-shared", solveLimits);
			ExactCategoricalSolver.CompiledProblem selectedCompilation = compiled;
			solver = () -> ExactCategoricalSolver.solve(selectedCompilation);
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

	/** Borrowed root tables are excluded; projected inputs and intermediates share one additional budget. */
	private Limits conditionalLimits(boolean[] selected, boolean[] free) {
		if(maximumAdditionalBlockCells < 0)
			return limits;
		long borrowed = 0L;
		long projected = 0L;
		long maximumBorrowed = 1L;
		for(int factor=0; factor<selected.length; factor++) {
			if(!selected[factor])
				continue;
			long cells = 1L;
			boolean allFree = true;
			for(int variable : scopes.get(factor)) {
				if(free[variable]) {
					int domain = root.variables().get(variable).domainSize();
					cells = cells > Long.MAX_VALUE/domain ? Long.MAX_VALUE : cells*domain;
				}
				else
					allFree = false;
			}
			if(cells > Math.max(limits.maximumFactorCells(),maximumAdditionalBlockCells))
				throw new IllegalArgumentException("EXACT_VE_FACTOR_LIMIT_EXCEEDED|source=local-shared-condition"
					+ "|cells=" + cells + "|limit=" + maximumAdditionalBlockCells);
			if(allFree) {
				borrowed = saturatedAdd(borrowed,cells);
				maximumBorrowed = Math.max(maximumBorrowed,cells);
			}
			else
				projected = saturatedAdd(projected,cells);
			if(projected > maximumAdditionalBlockCells)
				throw new IllegalArgumentException("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED"
					+ "|source=local-shared-condition|cells=" + projected
					+ "|limit=" + maximumAdditionalBlockCells);
		}
		return new Limits(Math.max(maximumBorrowed,maximumAdditionalBlockCells),
			saturatedAdd(borrowed,maximumAdditionalBlockCells));
	}

	private static long saturatedAdd(long left, long right) {
		return left > Long.MAX_VALUE-right ? Long.MAX_VALUE : left+right;
	}

	private void tracePreSolveWork(int[] block, List<Variable> variables, List<Factor> factors,
		ExactCategoricalSolver.Statistics statistics, int compiledVariableCount, String caller,
		Limits solveLimits) {
		if(!FederatedPlannerTrace.isEnabled() || blocks != 0L)
			return;
		FederatedPlannerTrace.logGlobal("Exact-PreSolveWork",
			"caller=" + caller
				+ " compact=" + compact
				+ " rootDecisionCount=" + problem.decisionCount()
				+ " rootVariableCount=" + root.variables().size()
				+ " rootFactorCount=" + root.factors().size()
				+ " blockOriginalIndices=" + Arrays.toString(block)
				+ " blockOriginalCount=" + block.length
				+ " inputVariableKeys=" + variables.stream().map(Variable::key).toList()
				+ " inputVariableDomains=" + variables.stream().map(Variable::domainSize).toList()
				+ " inputFactorCount=" + factors.size()
				+ " compiledVariableCount=" + compiledVariableCount
				+ " eliminationOrder=" + statistics.eliminationOrder()
				+ " inducedWidth=" + statistics.inducedWidth()
				+ " maximumFactorCells=" + statistics.maximumFactorCells()
				+ " materializedFactorCells=" + statistics.materializedFactorCells()
				+ " maximumEliminationAssignments=" + statistics.maximumEliminationAssignments()
				+ " eliminationAssignments=" + statistics.eliminationAssignments()
				+ " maximumFactorCellsLimit=" + solveLimits.maximumFactorCells()
				+ " maximumMaterializedCellsLimit=" + solveLimits.maximumMaterializedCells()
				+ " fastOrderConfigured=" + orderPolicy.fastOrder()
				+ " fastOrderAssignmentsLimit=" + orderPolicy.maximumAssignments()
				+ " fastOrderSource=" + orderPolicy.source()
				+ " plannerElapsedNanos=" + FederatedPlannerTrace.plannerElapsedNanos());
	}

	private void recordOrder(ExactCategoricalSolver.OrderCompilation compilation) {
		if(compilation == null || !compilation.fastOrderConfigured())
			return;
		maximumFastBlockOrderAssignments = Math.max(maximumFastBlockOrderAssignments,
			compilation.fastOrderAssignments());
		if(compilation.fastOrderAccepted())
			fastBlockOrderAccepted++;
		else if(compilation.fastOrderFallback())
			fastBlockOrderFallbacks++;
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
	long fastBlockOrderAccepted() { return fastBlockOrderAccepted; }
	long fastBlockOrderFallbacks() { return fastBlockOrderFallbacks; }
	long maximumFastBlockOrderAssignments() { return maximumFastBlockOrderAssignments; }
	String lastFallbackReason() { return lastFallbackReason; }

	void trace() {
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("DP-RegionalSharedPreparation",
				"encoded=true compact=" + compact + " rootBuilds=" + (root == null ? 0 : 1)
					+ " rootPreparationNanos=" + problem.reducedRootNanos()
					+ " conditionNanos=" + conditionNanos + " blockPreparationNanos=" + blockPreparationNanos
					+ " blocks=" + blocks + " unchangedTables=" + unchangedTables
					+ " conditionedTableBuilds=" + tableBuilds + " conditionedTableHits=" + cacheHits
					+ " resourceOrUnsupportedBoundaryFallbacks=" + fallbacks
					+ " fastOrderConfigured=" + orderPolicy.fastOrder()
					+ " fastOrderAssignmentsLimit=" + orderPolicy.maximumAssignments()
					+ " fastOrderSource=" + orderPolicy.source()
					+ " fastOrderAccepted=" + fastBlockOrderAccepted
					+ " fastOrderFallbacks=" + fastBlockOrderFallbacks
					+ " fastOrderMaximumEstimatedAssignments=" + maximumFastBlockOrderAssignments
					+ " fastBlockOrderAccepted=" + fastBlockOrderAccepted
					+ " fastBlockOrderFallbacks=" + fastBlockOrderFallbacks
					+ " maximumFastBlockOrderAssignments=" + maximumFastBlockOrderAssignments
					+ " cachePolicy=one-entry-per-root-factor");
	}
}
