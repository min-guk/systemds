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
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.BoundaryMessage;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.ConditionalRegion;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class ExactConditionalSupportMarginalTest {
	private static final Limits LIMITS = new Limits(1_000_000, 10_000_000);

	@Test
	public void bulkMarginalsMatchTheExistingPerValueReference() {
		Random random = new Random(0x4d415247494e414cL);
		for(int trial = 0; trial < 200; trial++) {
			int selectorAxis = trial % 3;
			List<Variable> variables = List.of(variable("a" + trial, 2),
				variable("b" + trial, 3), variable("c" + trial, 4));
			int selectorDomain = variables.get(selectorAxis).domainSize();
			int[] constrained = java.util.stream.IntStream.range(0, selectorDomain)
				.filter(ignored -> random.nextBoolean()).toArray();
			List<ConditionalRegion> regions = new ArrayList<>();
			for(int selector : constrained) {
				int copies = random.nextInt(4);
				for(int copy = 0; copy < copies; copy++) {
					int[][] allowed = new int[variables.size()][];
					for(int axis = 0; axis < variables.size(); axis++) {
						if(axis == selectorAxis)
							continue;
						List<Integer> values = new ArrayList<>();
						for(int value = 0; value < variables.get(axis).domainSize(); value++)
							if(random.nextBoolean())
								values.add(value);
						allowed[axis] = values.stream().mapToInt(Integer::intValue).toArray();
					}
					regions.add(new ConditionalRegion(selector, allowed));
				}
			}
			Factor conditional = Factor.conditionalSupport(variables, selectorAxis, constrained, regions);
			BoundaryMessage message = ExactCategoricalSolver.boundaryLeaves(
				variables, List.of(conditional), LIMITS).get(0);
			for(Variable variable : variables) {
				double[] bulk = message.minMarginals(variable);
				for(int value = 0; value < variable.domainSize(); value++)
					Assert.assertEquals("trial=" + trial + " variable=" + variable.key() + " value=" + value,
						Double.doubleToRawLongBits(message.minMarginal(variable, value)),
						Double.doubleToRawLongBits(bulk[value]));
			}
		}
	}

	@Test
	public void wildcardAndEmptyRegionsKeepExactSelectorAndAxisMasks() {
		Variable left = variable("left", 4), selector = variable("selector", 4), right = variable("right", 3);
		List<Variable> variables = List.of(left, selector, right);
		Factor conditional = Factor.conditionalSupport(variables, 1, new int[] {0, 1, 3}, List.of(
			region(0, new int[0], null, new int[] {0}),
			region(1, new int[] {1, 3}, null, new int[] {0, 2})));
		BoundaryMessage message = ExactCategoricalSolver.boundaryLeaves(
			variables, List.of(conditional), LIMITS).get(0);
		assertRaw(new double[] {0d, 0d, 0d, 0d}, message.minMarginals(left));
		assertRaw(new double[] {Double.POSITIVE_INFINITY, 0d, 0d, Double.POSITIVE_INFINITY},
			message.minMarginals(selector));
		assertRaw(new double[] {0d, 0d, 0d}, message.minMarginals(right));
	}

	@Test(timeout = 5000)
	public void targetMarginalDoesNotNeedAnUnrelatedLargeAxisResult() {
		Variable target = variable("target", 2), selector = variable("large-selector", 2),
			irrelevant = variable("irrelevant", 1_000_000);
		List<Variable> variables = List.of(target, selector, irrelevant);
		Factor conditional = Factor.conditionalSupport(variables, 1, new int[] {0, 1}, List.of(
			region(0, new int[] {1}, null, new int[] {999_999}),
			region(1, new int[] {0}, null, new int[0])));
		BoundaryMessage message = ExactCategoricalSolver.boundaryLeaves(
			variables, List.of(conditional), LIMITS).get(0);
		assertRaw(new double[] {Double.POSITIVE_INFINITY, 0d}, message.minMarginals(target));
	}

	@Test
	public void boundaryMergeAndSolveMatchAnExplicitDenseRelation() {
		Variable selector = variable("merge-selector", 3), left = variable("merge-left", 4),
			right = variable("merge-right", 3);
		List<Variable> variables = List.of(selector, left, right);
		Factor conditional = Factor.conditionalSupport(variables, 0, new int[] {0, 1, 2}, List.of(
			region(0, null, new int[] {0, 3}, new int[] {1}),
			region(1, null, new int[0], new int[] {0, 2}),
			region(2, null, new int[] {1, 2}, new int[] {0, 2})));
		Factor dense = Factor.dense(variables, denseCosts(conditional));
		List<Factor> numeric = List.of(
			Factor.dense(List.of(selector), 2d, 0d, 1d),
			Factor.dense(List.of(left), 4d, 2d, 1d, 0d),
			Factor.dense(List.of(right), 3d, 1d, 0d));
		BoundaryMessage compactBoundary = ExactCategoricalSolver.mergeBoundary(
			ExactCategoricalSolver.boundaryLeaves(variables,
				List.of(conditional, numeric.get(0), numeric.get(1), numeric.get(2)), LIMITS),
			List.of(left), LIMITS, new ExactCategoricalSolver.BoundaryMergeCounters());
		BoundaryMessage denseBoundary = ExactCategoricalSolver.mergeBoundary(
			ExactCategoricalSolver.boundaryLeaves(variables,
				List.of(dense, numeric.get(0), numeric.get(1), numeric.get(2)), LIMITS),
			List.of(left), LIMITS, new ExactCategoricalSolver.BoundaryMergeCounters());
		assertRaw(denseBoundary.minMarginals(left), compactBoundary.minMarginals(left));
		Assert.assertEquals(Double.doubleToRawLongBits(denseBoundary.minimum()),
			Double.doubleToRawLongBits(compactBoundary.minimum()));

		List<Factor> compactFactors = List.of(conditional, numeric.get(0), numeric.get(1), numeric.get(2));
		List<Factor> denseFactors = List.of(dense, numeric.get(0), numeric.get(1), numeric.get(2));
		var compact = ExactCategoricalSolver.solve(variables, compactFactors, LIMITS);
		var explicit = ExactCategoricalSolver.solve(variables, denseFactors, LIMITS);
		Assert.assertEquals(explicit.assignmentInVariableOrder(), compact.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(explicit.objective()),
			Double.doubleToRawLongBits(compact.objective()));
	}

	private static ConditionalRegion region(int selector, int[]... allowed) {
		return new ConditionalRegion(selector, allowed);
	}

	private static Variable variable(String key, int domain) {
		return new Variable(key, domain);
	}

	private static double[] denseCosts(Factor factor) {
		int cells = factor.scope().stream().mapToInt(Variable::domainSize).reduce(1, Math::multiplyExact);
		double[] result = new double[cells];
		int[] values = new int[factor.scope().size()];
		for(int cell = 0; cell < cells; cell++) {
			int remaining = cell;
			for(int axis = values.length - 1; axis >= 0; axis--) {
				values[axis] = remaining % factor.scope().get(axis).domainSize();
				remaining /= factor.scope().get(axis).domainSize();
			}
			result[cell] = factor.cost(values);
		}
		return result;
	}

	private static void assertRaw(double[] expected, double[] actual) {
		Assert.assertEquals(expected.length, actual.length);
		for(int value = 0; value < expected.length; value++)
			Assert.assertEquals("value=" + value, Double.doubleToRawLongBits(expected[value]),
				Double.doubleToRawLongBits(actual[value]));
	}
}
