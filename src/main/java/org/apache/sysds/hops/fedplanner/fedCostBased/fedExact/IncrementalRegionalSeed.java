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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Lifts an original-decision Regional seed into the shared reduced encoded model. */
final class IncrementalRegionalSeed {
	private static final String INFEASIBLE = "INCREMENTAL_REGIONAL_SEED_INFEASIBLE";
	static final class SupportStatistics {
		long factorRevisions;
		long visitedCells;
	}
	private record SupportFactor(ExactCategoricalSolver.Factor factor, int[] scopeIndex) { }
	private record SupportPlan(SupportFactor[] factors, int[][] incident, boolean[][] supported) { }

	/** Ascending live prefixes avoid rescanning the mostly fixed original domains. */
	private static final class ActiveDomains {
		private final int[][] values;
		private final int[] sizes;
		private ActiveDomains(boolean[][] active) {
			values = new int[active.length][];
			sizes = new int[active.length];
			for(int variable = 0; variable < active.length; variable++) {
				for(boolean value : active[variable])
					if(value)
						sizes[variable]++;
				values[variable] = PlannerResourceGuard.allocateInts(
					sizes[variable], "regional-seed-active-values");
				for(int value = 0, target = 0; value < active[variable].length; value++)
					if(active[variable][value])
						values[variable][target++] = value;
			}
		}
	}

	private IncrementalRegionalSeed() { }

	/**
	 * Keeps every original decision fixed and completes only encoded auxiliary values.
	 * Finite-support propagation handles forced auxiliaries without compiling an
	 * elimination problem. Any auxiliaries that remain free are solved conditionally.
	 */
	static int[] lift(ExactPhysicalReducedSolver.CompactModel root,
		List<Integer> originalAssignment, ExactCategoricalSolver.Limits limits) {
		return lift(root, originalAssignment, limits, null);
	}

	static int[] lift(ExactPhysicalReducedSolver.CompactModel root,
		List<Integer> originalAssignment, ExactCategoricalSolver.Limits limits,
		SupportStatistics statistics) {
		Objects.requireNonNull(root, "root");
		Objects.requireNonNull(originalAssignment, "originalAssignment");
		Objects.requireNonNull(limits, "limits");
		List<ExactCategoricalSolver.Variable> variables = root.variables();
		if(originalAssignment.size() != root.originalDecisionCount())
			throw new IllegalArgumentException("INCREMENTAL_REGIONAL_SEED_SIZE_MISMATCH");
		if(variables.size() < root.originalDecisionCount())
			throw new IllegalArgumentException("INCREMENTAL_REGIONAL_SEED_MODEL_INVALID");

		Map<ExactCategoricalSolver.Variable, Integer> index = new IdentityHashMap<>();
		boolean[][] active = new boolean[variables.size()][];
		int[] assignment = new int[variables.size()];
		Arrays.fill(assignment, -1);
		for(int variable = 0; variable < variables.size(); variable++) {
			index.put(variables.get(variable), variable);
			active[variable] = new boolean[variables.get(variable).domainSize()];
			Arrays.fill(active[variable], true);
		}
		for(int variable = 0; variable < root.originalDecisionCount(); variable++) {
			Integer sourceValue = originalAssignment.get(variable);
			if(sourceValue == null)
				throw new IllegalArgumentException("INCREMENTAL_REGIONAL_SEED_VALUE_INVALID");
			int reducedValue = root.reducedValue(variable, sourceValue);
			if(reducedValue < 0)
				throw new IllegalArgumentException(INFEASIBLE);
			Arrays.fill(active[variable], false);
			active[variable][reducedValue] = true;
			assignment[variable] = reducedValue;
		}

		propagateFiniteSupport(compileSupportPlan(root.factors(), index, variables.size()), active,
			statistics);
		for(int variable = root.originalDecisionCount(); variable < variables.size(); variable++) {
			int singleton = singleton(active[variable]);
			if(singleton >= 0)
				assignment[variable] = singleton;
		}
		completeFreeAuxiliaries(root.factors(), variables, index, assignment,
			root.originalDecisionCount(), limits);

		for(int variable = 0; variable < root.originalDecisionCount(); variable++) {
			int expected = root.reducedValue(variable, originalAssignment.get(variable));
			if(assignment[variable] != expected)
				throw new IllegalStateException("INCREMENTAL_REGIONAL_SEED_ORIGINAL_CHANGED");
		}
		if(!isFinite(root.factors(), index, assignment))
			throw new IllegalArgumentException(INFEASIBLE);
		return assignment;
	}

	private static boolean isFinite(List<ExactCategoricalSolver.Factor> factors,
		Map<ExactCategoricalSolver.Variable, Integer> index, int[] assignment) {
		double total = 0d;
		for(ExactCategoricalSolver.Factor factor : factors) {
			int[] local = new int[factor.scope().size()];
			for(int position = 0; position < local.length; position++)
				local[position] = assignment[requireIndex(index, factor.scope().get(position))];
			double cost = factor.cost(local);
			if(!Double.isFinite(cost))
				return false;
			total += cost;
		}
		return Double.isFinite(total);
	}

	private static SupportPlan compileSupportPlan(List<ExactCategoricalSolver.Factor> factors,
		Map<ExactCategoricalSolver.Variable, Integer> index, int variableCount) {
		SupportFactor[] compiled = new SupportFactor[factors.size()];
		int maximumArity = 0;
		for(ExactCategoricalSolver.Factor factor : factors)
			maximumArity = Math.max(maximumArity, factor.scope().size());
		int[] supportWidths = new int[maximumArity];
		int[] incidentCounts = new int[variableCount];
		for(int ordinal = 0; ordinal < factors.size(); ordinal++) {
			ExactCategoricalSolver.Factor factor = factors.get(ordinal);
			int[] scopeIndex = new int[factor.scope().size()];
			for(int position = 0; position < scopeIndex.length; position++) {
				scopeIndex[position] = requireIndex(index, factor.scope().get(position));
				incidentCounts[scopeIndex[position]]++;
				supportWidths[position] = Math.max(supportWidths[position],
					factor.scope().get(position).domainSize());
			}
			compiled[ordinal] = new SupportFactor(factor, scopeIndex);
		}
		int[][] incident = Arrays.stream(incidentCounts).mapToObj(int[]::new).toArray(int[][]::new);
		Arrays.fill(incidentCounts, 0);
		for(int ordinal = 0; ordinal < compiled.length; ordinal++)
			for(int variable : compiled[ordinal].scopeIndex())
				incident[variable][incidentCounts[variable]++] = ordinal;
		boolean[][] supported = Arrays.stream(supportWidths).mapToObj(boolean[]::new).toArray(boolean[][]::new);
		return new SupportPlan(compiled, incident, supported);
	}

	private static void propagateFiniteSupport(SupportPlan plan, boolean[][] active,
		SupportStatistics statistics) {
		ActiveDomains live = new ActiveDomains(active);
		boolean[] dirty = new boolean[plan.factors().length];
		Arrays.fill(dirty, true);
		boolean pending;
		do {
			pending = false;
			for(int ordinal = 0; ordinal < plan.factors().length; ordinal++) {
				if(!dirty[ordinal])
					continue;
				dirty[ordinal] = false;
				SupportFactor factor = plan.factors()[ordinal];
				for(int position = 0; position < factor.scopeIndex().length; position++) {
					int variable = factor.scopeIndex()[position];
					for(int offset = 0; offset < live.sizes[variable]; offset++)
						plan.supported()[position][live.values[variable][offset]] = false;
				}
				if(statistics != null)
					statistics.factorRevisions++;
				boolean finite = markFiniteSupports(factor, active, live, plan.supported(), 0, 0, statistics);
				if(!finite)
					throw new IllegalArgumentException(INFEASIBLE);
				for(int position = 0; position < factor.scopeIndex().length; position++) {
					int variable = factor.scopeIndex()[position];
					int retained = 0;
					int previousSize = live.sizes[variable];
					for(int offset = 0; offset < previousSize; offset++) {
						int value = live.values[variable][offset];
						if(plan.supported()[position][value])
							live.values[variable][retained++] = value;
						else
							active[variable][value] = false;
					}
					live.sizes[variable] = retained;
					if(retained != previousSize)
						for(int affected : plan.incident()[variable])
							dirty[affected] = true;
					if(retained == 0)
						throw new IllegalArgumentException(INFEASIBLE);
				}
			}
			for(boolean factorDirty : dirty)
				pending |= factorDirty;
		} while(pending);
	}

	private static boolean markFiniteSupports(SupportFactor factor, boolean[][] active,
		ActiveDomains live, boolean[][] supported, int position, int cell, SupportStatistics statistics) {
		if(position == factor.scopeIndex().length) {
			if(statistics != null)
				statistics.visitedCells++;
			if(!Double.isFinite(factor.factor().denseCostAt(cell)))
				return false;
			return true;
		}
		boolean finite = false;
		int variable = factor.scopeIndex()[position];
		for(int offset = 0; offset < live.sizes[variable]; offset++) {
			int value = live.values[variable][offset];
			boolean completion = markFiniteSupports(factor, active, live, supported, position + 1,
				cell * active[variable].length + value, statistics);
			if(completion) {
				supported[position][value] = true;
				finite = true;
			}
		}
		return finite;
	}

	private static void completeFreeAuxiliaries(List<ExactCategoricalSolver.Factor> factors,
		List<ExactCategoricalSolver.Variable> variables,
		Map<ExactCategoricalSolver.Variable, Integer> index, int[] assignment,
		int originalDecisionCount, ExactCategoricalSolver.Limits limits) {
		List<ExactCategoricalSolver.Variable> free = new ArrayList<>();
		for(int variable = originalDecisionCount; variable < variables.size(); variable++)
			if(assignment[variable] < 0)
				free.add(variables.get(variable));
		if(free.isEmpty())
			return;

		List<ExactCategoricalSolver.Factor> conditioned = new ArrayList<>(factors.size());
		long[] materializedCells = {0L};
		for(ExactCategoricalSolver.Factor factor : factors)
			conditioned.add(condition(factor, index, assignment, limits, materializedCells));
		ExactCategoricalSolver.Result result = ExactCategoricalSolver.solve(free, conditioned, limits);
		for(int freeIndex = 0; freeIndex < free.size(); freeIndex++)
			assignment[requireIndex(index, free.get(freeIndex))] =
				result.assignmentInVariableOrder().get(freeIndex);
	}

	private static ExactCategoricalSolver.Factor condition(ExactCategoricalSolver.Factor factor,
		Map<ExactCategoricalSolver.Variable, Integer> index, int[] assignment,
		ExactCategoricalSolver.Limits limits, long[] materializedCells) {
		List<ExactCategoricalSolver.Variable> freeScope = new ArrayList<>();
		for(ExactCategoricalSolver.Variable variable : factor.scope())
			if(assignment[requireIndex(index, variable)] < 0)
				freeScope.add(variable);
		long cellsLong = 1L;
		for(ExactCategoricalSolver.Variable variable : freeScope)
			cellsLong = Math.multiplyExact(cellsLong, variable.domainSize());
		if(cellsLong > limits.maximumFactorCells() || cellsLong > Integer.MAX_VALUE)
			throw new IllegalArgumentException("EXACT_VE_FACTOR_LIMIT_EXCEEDED|source=seed-condition"
				+ "|cells=" + cellsLong + "|limit=" + limits.maximumFactorCells());
		materializedCells[0] = Math.addExact(materializedCells[0], cellsLong);
		if(materializedCells[0] > limits.maximumMaterializedCells())
			throw new IllegalArgumentException("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED"
				+ "|source=seed-condition|cells=" + materializedCells[0]
				+ "|limit=" + limits.maximumMaterializedCells());
		int[] boundary = new int[factor.scope().size()];
		for(int axis = 0; axis < boundary.length; axis++)
			boundary[axis] = assignment[requireIndex(index, factor.scope().get(axis))];
		ExactCategoricalSolver.Factor support = factor.conditionSupport(boundary);
		if(support != null)
			return support;
		int cells = (int) cellsLong;
		double[] costs = new double[cells];
		int[] freeValues = new int[freeScope.size()];
		int[] localValues = new int[factor.scope().size()];
		for(int cell = 0; cell < cells; cell++) {
			decode(cell, freeScope, freeValues);
			int freePosition = 0;
			for(int position = 0; position < factor.scope().size(); position++) {
				int variable = requireIndex(index, factor.scope().get(position));
				localValues[position] = assignment[variable] >= 0
					? assignment[variable] : freeValues[freePosition++];
			}
			costs[cell] = factor.cost(localValues);
		}
		return ExactCategoricalSolver.Factor.denseOwned(freeScope, costs);
	}

	private static void decode(int cell, List<ExactCategoricalSolver.Variable> variables,
		int[] values) {
		for(int position = variables.size() - 1; position >= 0; position--) {
			values[position] = cell % variables.get(position).domainSize();
			cell /= variables.get(position).domainSize();
		}
	}

	/** Returns the value, -1 for multiple active values, or -2 for an empty domain. */
	private static int singleton(boolean[] active) {
		int selected = -2;
		for(int value = 0; value < active.length; value++)
			if(active[value]) {
				if(selected >= 0)
					return -1;
				selected = value;
			}
		return selected;
	}

	private static int requireIndex(Map<ExactCategoricalSolver.Variable, Integer> index,
		ExactCategoricalSolver.Variable variable) {
		Integer value = index.get(variable);
		if(value == null)
			throw new IllegalArgumentException("INCREMENTAL_REGIONAL_SEED_FACTOR_VARIABLE_UNKNOWN");
		return value;
	}
}
