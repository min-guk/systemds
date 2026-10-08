/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.Arrays;
import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Result;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class ExactFiniteSupportInputFactorTest {
	private static final Limits GENEROUS = new Limits(1_000_000, 4_000_000);

	@Test
	public void finiteSupportMatchesDenseHardRelationAndTieChoice() {
		Variable left = new Variable("left", 3);
		Variable right = new Variable("right", 4);
		int[] finite = {1, 6, 8, 11};
		double[] dense = new double[12];
		Arrays.fill(dense, Double.POSITIVE_INFINITY);
		for(int cell : finite)
			dense[cell] = 0d;
		List<Factor> prices = List.of(
			Factor.dense(List.of(left), 4d, 1d, 1d),
			Factor.dense(List.of(right), 3d, 2d, 1d, 0d));
		Result expected = ExactCategoricalSolver.solve(List.of(left, right), List.of(
			Factor.dense(List.of(left, right), dense), prices.get(0), prices.get(1)), GENEROUS);
		Result actual = ExactCategoricalSolver.solve(List.of(left, right), List.of(
			Factor.finiteSupport(List.of(left, right), finite), prices.get(0), prices.get(1)), GENEROUS);
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
	}

	@Test
	public void finiteSupportPreservesSecondaryTieSemantics() {
		Variable left = new Variable("tie-left", 2);
		Variable right = new Variable("tie-right", 2);
		double inf = Double.POSITIVE_INFINITY;
		List<Variable> variables = List.of(left, right);
		ExactCategoricalSolver.TieCostFunction ties = (variable, value) ->
			variable == left ? 1L - value : value;
		Result expected = ExactCategoricalSolver.solve(variables, List.of(
			Factor.dense(variables, 0d, inf, inf, 0d)), GENEROUS, ties);
		Result actual = ExactCategoricalSolver.solve(variables, List.of(
			Factor.finiteSupport(variables, 0, 3)), GENEROUS, ties);
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
	}

	@Test(timeout = 5000)
	public void hugeLogicalProductWithThreeRowsDoesNotRequireCartesianInputStorage() {
		Variable left = new Variable("large-left", 20_000);
		Variable right = new Variable("large-right", 20_000);
		int last = 20_000 * 20_000 - 1;
		Factor support = Factor.finiteSupport(List.of(left, right), 0, 200_010_000, last);
		Limits storedRowLimits = new Limits(100_000, 200_000);
		Result result = ExactCategoricalSolver.solve(List.of(left, right), List.of(support), storedRowLimits);
		Assert.assertEquals(List.of(0, 0), result.assignmentInVariableOrder());
		Assert.assertEquals(0L, Double.doubleToRawLongBits(result.objective()));
		Assert.assertTrue(result.statistics().maximumFactorCells() <= storedRowLimits.maximumFactorCells());
		Assert.assertTrue(result.statistics().materializedFactorCells()
			<= storedRowLimits.maximumMaterializedCells());
	}

	@Test
	public void finiteSupportRejectsInvalidCellAuthorityBeforeSolve() {
		Variable value = new Variable("value", 4);
		assertFailure("EXACT_VE_FINITE_SUPPORT_CELLS_NOT_STRICTLY_SORTED",
			() -> Factor.finiteSupport(List.of(value), 1, 1));
		assertFailure("EXACT_VE_FINITE_SUPPORT_CELLS_NOT_STRICTLY_SORTED",
			() -> Factor.finiteSupport(List.of(value), 2, 1));
		assertFailure("EXACT_VE_FINITE_SUPPORT_CELL_INVALID",
			() -> Factor.finiteSupport(List.of(value), -1));
		assertFailure("EXACT_VE_FINITE_SUPPORT_CELL_INVALID",
			() -> Factor.finiteSupport(List.of(value), 4));
	}

	@Test(timeout = 5000)
	public void reducedSolverKeepsLargeTernarySupportSparseAndPreservesCanonicalChoice() {
		Variable first = new Variable("reduced-first", 500);
		Variable second = new Variable("reduced-second", 500);
		Variable third = new Variable("reduced-third", 500);
		List<Variable> variables = List.of(first, second, third);
		Factor support = Factor.finiteSupport(variables,
			0, 123 * 500 * 500 + 234 * 500 + 345, 499 * 500 * 500 + 499 * 500 + 499);
		Limits sparseLimits = new Limits(300_000, 1_000_000);

		Result expected = ExactCategoricalSolver.solve(variables, List.of(support), sparseLimits);
		Result actual = ExactPhysicalReducedSolver.solve(variables.size(), variables,
			List.of(support), sparseLimits);

		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		Assert.assertEquals(List.of(0, 0, 0), actual.assignmentInVariableOrder());
		Assert.assertTrue(actual.statistics().maximumFactorCells() <= sparseLimits.maximumFactorCells());
	}

	@Test(timeout = 5000)
	public void regionalBoundaryConsumesLargeSparseSupportWithoutProductScan() {
		int size = 20_000;
		Variable left = new Variable("regional-left", size);
		Variable singleton = new Variable("regional-singleton", 1);
		Variable right = new Variable("regional-right", size);
		List<Variable> variables = List.of(left, singleton, right);
		int middle = 10_000 * size + 10_001;
		int last = size * size - 1;
		double[] leftCosts = new double[size];
		double[] rightCosts = new double[size];
		Arrays.fill(leftCosts, 10d);
		Arrays.fill(rightCosts, 10d);
		leftCosts[10_000] = 2d;
		rightCosts[10_001] = 3d;
		List<Factor> factors = List.of(
			Factor.finiteSupport(variables, 0, middle, last),
			Factor.dense(List.of(left), leftCosts),
			Factor.dense(List.of(right), rightCosts));
		Limits sparseLimits = new Limits(100_000, 500_000);

		var leaves = ExactCategoricalSolver.boundaryLeaves(variables, factors, sparseLimits);
		var projectedSupport = ExactCategoricalSolver.projectSingletons(leaves.get(0));
		Assert.assertEquals(List.of(left, right), projectedSupport.scope());
		Assert.assertEquals(3, projectedSupport.hardSupport().size());
		var message = ExactCategoricalSolver.mergeBoundary(leaves, List.of(left), sparseLimits);

		Assert.assertEquals(5d, message.minimum(), 0d);
		Assert.assertEquals(5d, message.minMarginal(left, 10_000), 0d);
		Assert.assertEquals(Double.POSITIVE_INFINITY, message.minMarginal(left, 7), 0d);
		Assert.assertTrue(message.retainedCells() < 100_000);
	}

	@Test(timeout = 5000)
	public void functionalMapUsesSameSparseInputPipelineWithoutLosingItsMarker() {
		int size = 20_000;
		Variable source = new Variable("functional-sparse-source", size);
		Variable target = new Variable("functional-sparse-target", size);
		int[] mapping = new int[size];
		Arrays.fill(mapping, -1);
		mapping[7] = 11;
		mapping[10_000] = 10_001;
		mapping[size - 1] = size - 1;
		Factor functional = Factor.functionalMap(source, target, mapping);
		Limits sparseLimits = new Limits(100_000, 200_000);

		Assert.assertNotNull(functional.functionalMapping());
		Result result = ExactCategoricalSolver.solve(
			List.of(source, target), List.of(functional), sparseLimits);
		var leaf = ExactCategoricalSolver.boundaryLeaves(
			List.of(source, target), List.of(functional), sparseLimits).get(0);

		Assert.assertEquals(List.of(7, 11), result.assignmentInVariableOrder());
		Assert.assertEquals(3, leaf.hardSupport().size());
		Assert.assertEquals(3L, leaf.retainedCells());
	}

	@Test
	public void functionalSparseBackingPreservesExactTieAndOrderSemantics() {
		Variable source = new Variable("functional-tie-source", 4);
		Variable target = new Variable("functional-tie-target", 3);
		List<Variable> variables = List.of(source, target);
		Factor functional = Factor.functionalMap(source, target, new int[] {2, 0, 2, -1});
		Factor finite = Factor.finiteSupport(variables, 2, 3, 8);
		ExactCategoricalSolver.TieCostFunction ties = (variable, value) -> variable == source
			? Math.abs(value - 2L) : 0L;

		Result expected = ExactCategoricalSolver.solve(variables, List.of(finite), GENEROUS, ties);
		Result actual = ExactCategoricalSolver.solve(variables, List.of(functional), GENEROUS, ties);

		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		Assert.assertEquals(List.of(2, 2), actual.assignmentInVariableOrder());
	}

	private static void assertFailure(String expected, Runnable action) {
		try {
			action.run();
			Assert.fail("Expected " + expected);
		}
		catch(IllegalArgumentException actual) {
			Assert.assertTrue(actual.getMessage(), actual.getMessage().startsWith(expected));
		}
	}
}
