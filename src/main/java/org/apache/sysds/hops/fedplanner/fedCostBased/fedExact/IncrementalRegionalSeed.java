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

	private IncrementalRegionalSeed() { }

	/**
	 * Keeps every original decision fixed and completes only encoded auxiliary values.
	 * Finite-support propagation handles forced auxiliaries without compiling an
	 * elimination problem. Any auxiliaries that remain free are solved conditionally.
	 */
	static int[] lift(ExactPhysicalReducedSolver.CompactModel root,
		List<Integer> originalAssignment, ExactCategoricalSolver.Limits limits) {
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

		propagateFiniteSupport(root.factors(), index, active);
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

	private static void propagateFiniteSupport(List<ExactCategoricalSolver.Factor> factors,
		Map<ExactCategoricalSolver.Variable, Integer> index, boolean[][] active) {
		boolean changed;
		do {
			changed = false;
			for(ExactCategoricalSolver.Factor factor : factors) {
				List<ExactCategoricalSolver.Variable> scope = factor.scope();
				boolean[][] supported = new boolean[scope.size()][];
				int[] scopeIndex = new int[scope.size()];
				for(int position = 0; position < scope.size(); position++) {
					scopeIndex[position] = requireIndex(index, scope.get(position));
					supported[position] = new boolean[scope.get(position).domainSize()];
				}
				boolean finite = markFiniteSupports(factor, scopeIndex, active, supported,
					new int[scope.size()], 0);
				if(!finite)
					throw new IllegalArgumentException(INFEASIBLE);
				for(int position = 0; position < scope.size(); position++) {
					int variable = scopeIndex[position];
					for(int value = 0; value < active[variable].length; value++)
						if(active[variable][value] && !supported[position][value]) {
							active[variable][value] = false;
							changed = true;
						}
					if(singleton(active[variable]) == -2)
						throw new IllegalArgumentException(INFEASIBLE);
				}
			}
		} while(changed);
	}

	private static boolean markFiniteSupports(ExactCategoricalSolver.Factor factor,
		int[] scopeIndex, boolean[][] active, boolean[][] supported, int[] values, int position) {
		if(position == scopeIndex.length) {
			if(!Double.isFinite(factor.cost(values)))
				return false;
			for(int current = 0; current < values.length; current++)
				supported[current][values[current]] = true;
			return true;
		}
		boolean finite = false;
		for(int value = 0; value < active[scopeIndex[position]].length; value++)
			if(active[scopeIndex[position]][value]) {
				values[position] = value;
				finite |= markFiniteSupports(factor, scopeIndex, active, supported, values,
					position + 1);
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
