/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.ConditionalRegion;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

/** Lock both regional conditioning consumers against an independent explicit table. */
public class ExactSupportConditioningTest {
	private static final Limits LIMITS = new Limits(10_000_000, 20_000_000);

	@Test public void largeSparseSliceKeepsOnlyAdmittedRows() throws Exception {
		Variable left = new Variable("left", 400), fixed = new Variable("fixed", 2),
			right = new Variable("right", 400);
		Factor source = Factor.finiteSupport(List.of(left, fixed, right), 0, 399, 400, 319_600, 319_999);
		for(boolean seed : List.of(false, true)) {
			Factor result = condition(source, new int[] {-1, 1, -1}, seed);
			Assert.assertTrue("conditioning must retain sparse support", result.isFiniteSupport());
			Assert.assertEquals(List.of(left, right), result.scope());
			Assert.assertArrayEquals(new int[] {0, 159_600, 159_999}, result.finiteSupportCells());
		}
	}

	@Test public void everySmallBoundaryAndRandomSparseHoleMatchesExplicitCells() throws Exception {
		Random random = new Random(20261009L);
		for(int trial = 0; trial < 35; trial++) {
			List<Variable> variables = List.of(new Variable("a", 2), new Variable("b", 3),
				new Variable("c", 2));
			List<Integer> admitted = new ArrayList<>();
			for(int row = 0; row < 12; row++) if(random.nextBoolean()) admitted.add(row);
			Factor source = Factor.finiteSupport(variables, admitted.stream().mapToInt(Integer::intValue).toArray());
			for(int a = -1; a < 2; a++) for(int b = -1; b < 3; b++) for(int c = -1; c < 2; c++)
				assertParity(source, new int[] {a, b, c}, true);
		}
	}

	@Test public void functionalFixedSourceTargetAndHolesKeepExactSupport() throws Exception {
		Variable source = new Variable("source", 5), target = new Variable("target", 3);
		Factor function = Factor.functionalMap(source, target, new int[] {2, -1, 0, 2, 1});
		for(Factor input : List.of(function, ExactCategoricalSolver.freezeValidatedFactor(function)))
			for(int row = -1; row < 5; row++) for(int column = -1; column < 3; column++)
				assertParity(input, new int[] {row, column}, true);
	}

	@Test public void conditionalSliceKeepsProductsAndForbiddenSelectors() throws Exception {
		Variable left = new Variable("left", 400), selector = new Variable("selector", 3),
			fixed = new Variable("fixed", 2), right = new Variable("right", 400);
		Factor source = Factor.conditionalSupport(List.of(left, selector, fixed, right), 1,
			new int[] {0, 1}, List.of(
				new ConditionalRegion(0, new int[][] {{1, 300}, null, {1}, {2, 299}}),
				new ConditionalRegion(1, new int[][] {{0}, null, {0}, {0}})));
		for(boolean seed : List.of(false, true)) {
			Factor actual = condition(source, new int[] {-1, -1, 1, -1}, seed);
			Assert.assertTrue("480000 cells must remain a union of products", actual.isConditionalSupport());
			Assert.assertEquals(List.of(left, selector, right), actual.scope());
			Assert.assertSame(selector, actual.conditionalSelectorVariable());
			Assert.assertTrue(actual.conditionalStoredValues() < 20);
			Assert.assertEquals(0d, actual.cost(new int[] {300, 0, 299}), 0d);
			Assert.assertEquals(Double.POSITIVE_INFINITY, actual.cost(new int[] {0, 0, 299}), 0d);
			Assert.assertEquals("a removed region must not turn its selector into a wildcard",
				Double.POSITIVE_INFINITY, actual.cost(new int[] {0, 1, 0}), 0d);
			Assert.assertEquals("unconstrained selectors remain wildcards", 0d,
				actual.cost(new int[] {399, 2, 399}), 0d);
		}
	}

	@Test public void randomConditionalSlicesMatchExplicitIncludingFixedSelectorFallback() throws Exception {
		Random random = new Random(0x636f6e646974L);
		for(int trial = 0; trial < 30; trial++) {
			List<Variable> variables = List.of(new Variable("a", 2), new Variable("b", 3),
				new Variable("c", 2));
			int selector = trial % 3;
			int[] constrained = {0, variables.get(selector).domainSize() - 1};
			List<ConditionalRegion> regions = new ArrayList<>();
			for(int row = 0, count = random.nextInt(6); row < count; row++) {
				int[][] allowed = new int[3][];
				for(int axis = 0; axis < 3; axis++) if(axis != selector) {
					List<Integer> options = new ArrayList<>();
					for(int value = 0; value < variables.get(axis).domainSize(); value++)
						if(random.nextBoolean()) options.add(value);
					allowed[axis] = options.stream().mapToInt(Integer::intValue).toArray();
				}
				regions.add(new ConditionalRegion(constrained[random.nextInt(2)], allowed));
			}
			Factor source = Factor.conditionalSupport(variables, selector, constrained, regions);
			for(int a = -1; a < 2; a++) for(int b = -1; b < 3; b++) for(int c = -1; c < 2; c++) {
				int[] boundary = {a, b, c};
				assertParity(source, boundary, false);
				if(boundary[selector] < 0)
					for(boolean seed : List.of(false, true))
						Assert.assertTrue(condition(source, boundary, seed).isConditionalSupport());
			}
		}
	}

	@Test public void numericalFallbackRetainsRawCostsAndCanonicalMinimum() throws Exception {
		Variable a = new Variable("a", 3), b = new Variable("b", 2);
		Factor source = Factor.dense(List.of(a, b), 0.1, 0d, 0.1, Double.POSITIVE_INFINITY, 1e16, 0.2);
		for(int left = -1; left < 3; left++) for(int right = -1; right < 2; right++)
			assertParity(source, new int[] {left, right}, false);
	}

	private static void assertParity(Factor source, int[] boundary, boolean sparse) throws Exception {
		List<Variable> free = new ArrayList<>();
		for(int axis = 0; axis < boundary.length; axis++) if(boundary[axis] < 0) free.add(source.scope().get(axis));
		int cells = free.stream().mapToInt(Variable::domainSize).reduce(1, Math::multiplyExact);
		double[] explicit = new double[cells];
		for(int cell = 0; cell < cells; cell++) {
			int[] values = boundary.clone();
			int remainder = cell;
			for(int axis = values.length - 1; axis >= 0; axis--) if(values[axis] < 0) {
				values[axis] = remainder % source.scope().get(axis).domainSize();
				remainder /= source.scope().get(axis).domainSize();
			}
			explicit[cell] = source.cost(values);
		}
		for(boolean seed : List.of(false, true)) {
			Factor actual = condition(source, boundary, seed);
			Assert.assertEquals(free, actual.scope());
			if(sparse) Assert.assertTrue(actual.isFiniteSupport() || actual.functionalMapping() != null);
			Factor frozen = ExactCategoricalSolver.freezeValidatedFactor(actual);
			for(int cell = 0; cell < cells; cell++) {
				int[] values = new int[free.size()];
				int remainder = cell;
				for(int axis = values.length - 1; axis >= 0; axis--) {
					values[axis] = remainder % free.get(axis).domainSize();
					remainder /= free.get(axis).domainSize();
				}
				Assert.assertEquals(Double.doubleToRawLongBits(explicit[cell]),
					Double.doubleToRawLongBits(actual.cost(values)));
				Assert.assertEquals(Double.doubleToRawLongBits(explicit[cell]),
					Double.doubleToRawLongBits(frozen.cost(values)));
			}
			if(Arrays.stream(explicit).anyMatch(Double::isFinite)) {
				var expected = ExactCategoricalSolver.solve(free, List.of(Factor.dense(free, explicit)), LIMITS);
				var result = ExactCategoricalSolver.solve(free, List.of(actual), LIMITS);
				Assert.assertEquals(expected.assignmentInVariableOrder(), result.assignmentInVariableOrder());
				Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
					Double.doubleToRawLongBits(result.objective()));
			}
		}
	}

	private static Factor condition(Factor source, int[] boundary, boolean seed) throws Exception {
		if(!seed) {
			// SharedRegionalPreparation receives frozen factors from its reduced root.
			Method method = SharedRegionalPreparation.class.getDeclaredMethod("condition", Factor.class, int[].class);
			method.setAccessible(true);
			return (Factor)method.invoke(null, ExactCategoricalSolver.freezeValidatedFactor(source), boundary);
		}
		Map<Variable,Integer> positions = new IdentityHashMap<>();
		for(int axis = 0; axis < source.scope().size(); axis++) positions.put(source.scope().get(axis), axis);
		Method method = IncrementalRegionalSeed.class.getDeclaredMethod("condition", Factor.class,
			Map.class, int[].class, Limits.class, long[].class);
		method.setAccessible(true);
		return (Factor)method.invoke(null, source, positions, boundary, LIMITS, new long[] {0});
	}
}
