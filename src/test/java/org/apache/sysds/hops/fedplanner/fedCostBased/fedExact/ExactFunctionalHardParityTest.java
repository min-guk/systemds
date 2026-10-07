/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

/** Independent dense/lazy parity checks for functional hard relations. */
public class ExactFunctionalHardParityTest {
	private static final Limits GENEROUS = new Limits(20_000_000, 100_000_000);

	@Test
	public void mappingIsAnImmutableValidatedSnapshot() {
		Variable source = new Variable("functional-snapshot-source", 4);
		Variable target = new Variable("functional-snapshot-target", 5);
		int[] mapping = {3, -1, 0, 4};
		Factor factor = Factor.functionalMap(source, target, mapping);
		mapping[0] = 1;
		mapping[1] = 2;

		for(int sourceValue = 0; sourceValue < source.domainSize(); sourceValue++)
			for(int targetValue = 0; targetValue < target.domainSize(); targetValue++)
				assertRaw(expected(new int[] {3, -1, 0, 4}, sourceValue, targetValue),
					factor.cost(new int[] {sourceValue, targetValue}));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> Factor.functionalMap(source, target, new int[] {0, 1, 2}));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> Factor.functionalMap(source, target, new int[] {0, 1, 2, -2}));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> Factor.functionalMap(source, target, new int[] {0, 1, 2, 5}));
	}

	@Test
	public void randomizedFunctionalDenseAndLazyReductionSolveIdentically() {
		Random random = new Random(0x4f756e6374696f6eL);
		for(int trial = 0; trial < 60; trial++) {
			int sourceDomain = 2 + random.nextInt(6);
			int targetDomain = 2 + random.nextInt(6);
			Variable source = new Variable("functional-random-source-" + trial, sourceDomain);
			Variable target = new Variable("functional-random-target-" + trial, targetDomain);
			int[] mapping = new int[sourceDomain];
			for(int value = 0; value < mapping.length; value++)
				mapping[value] = random.nextInt(targetDomain + 2) == targetDomain + 1
					? -1 : random.nextInt(targetDomain);
			if(Arrays.stream(mapping).allMatch(value -> value < 0))
				mapping[random.nextInt(sourceDomain)] = random.nextInt(targetDomain);
			double[] sourceCosts = numericCosts(sourceDomain, 0x1.0000000000001p-8);
			double[] targetCosts = numericCosts(targetDomain, 0x1.0000000000001p-9);
			List<Variable> variables = List.of(source, target);
			List<Factor> functional = List.of(Factor.dense(List.of(source), sourceCosts),
				Factor.dense(List.of(target), targetCosts), Factor.functionalMap(source, target, mapping));
			List<Factor> lazy = List.of(Factor.dense(List.of(source), sourceCosts),
				Factor.dense(List.of(target), targetCosts), lazyMap(source, target, mapping));
			List<Factor> dense = List.of(Factor.dense(List.of(source), sourceCosts),
				Factor.dense(List.of(target), targetCosts), denseMap(source, target, mapping));

			assertReductionParity(variables, functional, lazy);
			assertReductionParity(variables, functional, dense);
			assertSolveParity(variables, functional, lazy);
			assertSolveParity(variables, functional, dense);
		}
	}

	@Test
	public void unaryFilteringMissingTargetsAndSingletonCompactionRemainExact() {
		Variable source = new Variable("functional-filter-source", 5);
		Variable target = new Variable("functional-filter-target", 6);
		int[] mapping = {4, -1, 2, 5, 0};
		double inf = Double.POSITIVE_INFINITY;
		Factor sourceFilter = Factor.dense(List.of(source), inf, inf, 3d, 1d, inf);
		Factor targetFilter = Factor.dense(List.of(target), inf, inf, 2d, inf, inf, 0.5d);
		List<Variable> variables = List.of(source, target);
		List<Factor> functional = List.of(sourceFilter, targetFilter,
			Factor.functionalMap(source, target, mapping));
		List<Factor> lazy = List.of(sourceFilter, targetFilter, lazyMap(source, target, mapping));

		assertReductionParity(variables, functional, lazy);
		var reduced = ExactPhysicalReducedSolver.reducedModel(2, variables, functional, GENEROUS);
		Assert.assertEquals(-1, reduced.reducedValue(0, 0));
		Assert.assertEquals(-1, reduced.reducedValue(0, 1));
		Assert.assertTrue(reduced.reducedValue(0, 2) >= 0);
		Assert.assertTrue(reduced.reducedValue(0, 3) >= 0);
		Assert.assertEquals(-1, reduced.reducedValue(0, 4));
		Assert.assertEquals(-1, reduced.reducedValue(1, 0));
		Assert.assertTrue(reduced.reducedValue(1, 2) >= 0);
		Assert.assertTrue(reduced.reducedValue(1, 5) >= 0);
		assertSolveParity(variables, functional, lazy);

		Factor sourcePin = Factor.dense(List.of(source), inf, inf, inf, 0d, inf);
		Factor targetPin = Factor.dense(List.of(target), inf, inf, inf, inf, inf, 0d);
		List<Factor> pinned = List.of(sourcePin, targetPin,
			Factor.functionalMap(source, target, mapping));
		var compact = ExactPhysicalReducedSolver.reducedModel(2, variables, pinned, GENEROUS);
		Assert.assertEquals(1, compact.variables().get(0).domainSize());
		Assert.assertEquals(1, compact.variables().get(1).domainSize());
		Assert.assertEquals(List.of(3, 5), compact.expandAssignment(List.of(0, 0)));
		Assert.assertEquals(List.of(3, 5), ExactPhysicalReducedSolver.solve(
			2, variables, pinned, GENEROUS).assignmentInVariableOrder());
		var prefixOnly = ExactPhysicalReducedSolver.reducedModel(1, variables, pinned, GENEROUS);
		Assert.assertEquals(List.of(3, 5), prefixOnly.expandAssignment(List.of(0, 0)));
		Assert.assertEquals(List.of(3, 5), ExactPhysicalReducedSolver.solve(
			1, variables, pinned, GENEROUS).assignmentInVariableOrder());
	}

	@Test
	public void repeatedFunctionalOccurrencesAndSharedSourcesPreserveBackpointers() {
		Variable source = new Variable("functional-shared-source", 6);
		Variable first = new Variable("functional-shared-first", 5);
		Variable second = new Variable("functional-shared-second", 4);
		int[] firstMap = {2, 4, -1, 1, 3, 0};
		int[] secondMap = {1, 1, 2, -1, 3, 0};
		Factor repeated = Factor.functionalMap(source, first, firstMap);
		List<Variable> variables = List.of(source, first, second);
		List<Factor> functional = new ArrayList<>(List.of(repeated, repeated,
			Factor.functionalMap(source, second, secondMap),
			Factor.dense(List.of(source), 8d, 4d, 7d, 2d, 3d, 1d),
			Factor.dense(List.of(first), 9d, 1d, 8d, 2d, 3d),
			Factor.dense(List.of(second), 4d, 3d, 2d, 1d)));
		List<Factor> lazy = new ArrayList<>(List.of(lazyMap(source, first, firstMap),
			lazyMap(source, first, firstMap), lazyMap(source, second, secondMap),
			functional.get(3), functional.get(4), functional.get(5)));

		assertReductionParity(variables, functional, lazy);
		assertSolveParity(variables, functional, lazy);
		var result = ExactPhysicalReducedSolver.solve(3, variables, functional, GENEROUS,
			(variable, value) -> value == 5 ? 0L : value + 1L);
		Assert.assertEquals(firstMap[result.assignmentInVariableOrder().get(0)],
			(int)result.assignmentInVariableOrder().get(1));
		Assert.assertEquals(secondMap[result.assignmentInVariableOrder().get(0)],
			(int)result.assignmentInVariableOrder().get(2));
	}

	@Test(timeout = 30_000)
	public void fiftyThousandSquareRelationShrinksBeforeBinaryMaterialization() {
		int domain = 50_000;
		int selectedSource = 41_237;
		int selectedTarget = 17_311;
		Variable source = new Variable("functional-large-source", domain);
		Variable target = new Variable("functional-large-target", domain);
		int[] mapping = new int[domain];
		for(int value = 0; value < domain; value++)
			mapping[value] = (int)(((long)value * 31 + 7) % domain);
		mapping[selectedSource] = selectedTarget;
		double[] sourcePin = new double[domain];
		double[] targetPin = new double[domain];
		Arrays.fill(sourcePin, Double.POSITIVE_INFINITY);
		Arrays.fill(targetPin, Double.POSITIVE_INFINITY);
		sourcePin[selectedSource] = 0d;
		targetPin[selectedTarget] = 0d;
		List<Factor> factors = List.of(Factor.dense(List.of(source), sourcePin),
			Factor.dense(List.of(target), targetPin), Factor.functionalMap(source, target, mapping));

		var reduced = ExactPhysicalReducedSolver.reducedModel(2, List.of(source, target), factors,
			new Limits(1_000_000, 5_000_000));
		Assert.assertEquals(1, reduced.variables().get(0).domainSize());
		Assert.assertEquals(1, reduced.variables().get(1).domainSize());
		Assert.assertEquals(List.of(selectedSource, selectedTarget),
			reduced.expandAssignment(List.of(0, 0)));
		var result = ExactPhysicalReducedSolver.solve(2, List.of(source, target), factors,
			new Limits(1_000_000, 5_000_000));
		Assert.assertEquals(List.of(selectedSource, selectedTarget),
			result.assignmentInVariableOrder());
		assertRaw(0d, result.objective());
	}

	private static void assertReductionParity(List<Variable> variables,
		List<Factor> expectedFactors, List<Factor> actualFactors) {
		var expected = ExactPhysicalReducedSolver.reducedModel(
			variables.size(), variables, expectedFactors, GENEROUS);
		var actual = ExactPhysicalReducedSolver.reducedModel(
			variables.size(), variables, actualFactors, GENEROUS);
		Assert.assertEquals(expected.variables(), actual.variables());
		Assert.assertEquals(expected.factors().size(), actual.factors().size());
		for(int variable = 0; variable < variables.size(); variable++) {
			for(int source = 0; source < variables.get(variable).domainSize(); source++)
				Assert.assertEquals(expected.reducedValue(variable, source),
					actual.reducedValue(variable, source));
			for(int reduced = 0; reduced < expected.variables().get(variable).domainSize(); reduced++)
				Assert.assertEquals(expected.sourceValue(variable, reduced),
					actual.sourceValue(variable, reduced));
		}
		for(int factor = 0; factor < expected.factors().size(); factor++) {
			Factor left = expected.factors().get(factor);
			Factor right = actual.factors().get(factor);
			Assert.assertEquals(left.scope(), right.scope());
			int cells = left.scope().stream().mapToInt(Variable::domainSize)
				.reduce(1, Math::multiplyExact);
			for(int cell = 0; cell < cells; cell++) {
				int[] coordinates = decode(cell, left.scope());
				assertRaw(left.cost(coordinates), right.cost(coordinates));
			}
		}
	}

	private static void assertSolveParity(List<Variable> variables,
		List<Factor> expectedFactors, List<Factor> actualFactors) {
		ExactCategoricalSolver.TieCostFunction tie = (variable, value) ->
			(value * 17L + variable.key().length()) % 13L;
		var expected = ExactPhysicalReducedSolver.solve(
			variables.size(), variables, expectedFactors, GENEROUS, tie);
		var actual = ExactPhysicalReducedSolver.solve(
			variables.size(), variables, actualFactors, GENEROUS, tie);
		assertRaw(expected.objective(), actual.objective());
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
	}

	private static Factor lazyMap(Variable source, Variable target, int[] mapping) {
		int[] snapshot = mapping.clone();
		return Factor.lazy(List.of(source, target), values ->
			expected(snapshot, values[0], values[1]));
	}

	private static Factor denseMap(Variable source, Variable target, int[] mapping) {
		double[] values = new double[Math.multiplyExact(source.domainSize(), target.domainSize())];
		Arrays.fill(values, Double.POSITIVE_INFINITY);
		for(int sourceValue = 0; sourceValue < mapping.length; sourceValue++)
			if(mapping[sourceValue] >= 0)
				values[sourceValue * target.domainSize() + mapping[sourceValue]] = 0d;
		return Factor.dense(List.of(source, target), values);
	}

	private static double expected(int[] mapping, int source, int target) {
		return mapping[source] == target ? 0d : Double.POSITIVE_INFINITY;
	}

	private static double[] numericCosts(int size, double unit) {
		double[] values = new double[size];
		for(int value = 0; value < size; value++)
			values[value] = unit * (value + 1);
		return values;
	}

	private static int[] decode(int cell, List<Variable> scope) {
		int[] values = new int[scope.size()];
		for(int axis = values.length - 1; axis >= 0; axis--) {
			values[axis] = cell % scope.get(axis).domainSize();
			cell /= scope.get(axis).domainSize();
		}
		return values;
	}

	private static void assertRaw(double expected, double actual) {
		Assert.assertEquals(Double.doubleToRawLongBits(expected),
			Double.doubleToRawLongBits(actual));
	}
}
