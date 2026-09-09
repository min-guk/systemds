/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class SharedRegionalPreparationTest {
	private static final Limits GENEROUS = new Limits(1_000_000, 10_000_000);

	@Test
	public void sameBoundaryReusesConditionedTableAndChangedBoundaryRebuilds() {
		Variable block = variable("block", 2);
		Variable boundary = variable("boundary", 2);
		List<Variable> variables = List.of(block, boundary);
		List<Factor> factors = List.of(Factor.dense(List.of(block, boundary),
			5d, 1d,
			2d, 7d));
		SharedRegionalPreparation preparation = shared(variables, factors);

		assertMatchesIndependentOracle(preparation, variables, factors, new int[] {0, 0}, 0);
		Assert.assertEquals(1L, preparation.tableBuilds());
		Assert.assertEquals(0L, preparation.cacheHits());

		assertMatchesIndependentOracle(preparation, variables, factors, new int[] {1, 0}, 0);
		Assert.assertEquals(1L, preparation.tableBuilds());
		Assert.assertEquals(1L, preparation.cacheHits());

		assertMatchesIndependentOracle(preparation, variables, factors, new int[] {1, 1}, 0);
		Assert.assertEquals(2L, preparation.tableBuilds());
		Assert.assertEquals(1L, preparation.cacheHits());
		Assert.assertEquals(3L, preparation.blocks());
		Assert.assertEquals(0L, preparation.fallbacks());
	}

	@Test
	public void reducedBlockSolutionReturnsOriginalSourceValue() {
		Variable block = variable("reduced-block", 3);
		Variable boundary = variable("reduced-boundary", 2);
		List<Variable> variables = List.of(block, boundary);
		List<Factor> factors = List.of(Factor.dense(List.of(block, boundary),
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
			4d, 0d,
			1d, 3d));
		SharedRegionalPreparation preparation = shared(variables, factors);

		LocalCategoricalOptimizer.PreparedBlockSolver solver =
			preparation.prepare(new int[] {1, 0}, new int[] {0});
		Assert.assertNotNull(solver);
		ExactCategoricalSolver.Result result = solver.solve();

		Assert.assertEquals(List.of(2), result.assignmentInVariableOrder());
		Assert.assertEquals(1d, result.objective(), 0d);
		assertMatchesIndependentOracle(preparation, variables, factors, new int[] {2, 1}, 0);
		Assert.assertEquals(0L, preparation.fallbacks());
	}

	@Test
	public void removedFixedBoundaryUsesOriginalPreparationFallback() {
		Variable block = variable("fallback-block", 2);
		Variable boundary = variable("fallback-boundary", 3);
		List<Variable> variables = List.of(block, boundary);
		List<Factor> factors = List.of(
			Factor.dense(List.of(block, boundary),
				1d, 2d, 3d,
				3d, 2d, 1d),
			Factor.dense(List.of(boundary), Double.POSITIVE_INFINITY, 0d, 0d));
		SharedRegionalPreparation preparation = shared(variables, factors);

		Assert.assertNull(preparation.prepare(new int[] {0, 0}, new int[] {0}));
		Assert.assertEquals(1L, preparation.fallbacks());
		Assert.assertEquals(0L, preparation.blocks());
	}

	private static SharedRegionalPreparation shared(List<Variable> variables, List<Factor> factors) {
		return new SharedRegionalPreparation(RegionalSearchProblem.generic(variables, factors), GENEROUS, false);
	}

	private static void assertMatchesIndependentOracle(SharedRegionalPreparation preparation,
		List<Variable> variables, List<Factor> factors, int[] assignment, int block) {
		LocalCategoricalOptimizer.PreparedBlockSolver solver =
			preparation.prepare(assignment, new int[] {block});
		Assert.assertNotNull(solver);
		ExactCategoricalSolver.Result result = solver.solve();
		Oracle oracle = oracle(variables, factors, assignment, block);
		Assert.assertEquals(oracle.objective, result.objective(), 0d);
		Assert.assertEquals(List.of(oracle.value), result.assignmentInVariableOrder());
	}

	private static Oracle oracle(List<Variable> variables, List<Factor> factors,
		int[] assignment, int block) {
		double optimum = Double.POSITIVE_INFINITY;
		int value = -1;
		for(int candidate = 0; candidate < variables.get(block).domainSize(); candidate++) {
			List<Integer> current = new ArrayList<>(assignment.length);
			for(int index = 0; index < assignment.length; index++)
				current.add(index == block ? candidate : assignment[index]);
			double objective = ExactCategoricalSolver.evaluate(variables, factors, GENEROUS, current);
			if(objective < optimum) {
				optimum = objective;
				value = candidate;
			}
		}
		return new Oracle(optimum, value);
	}

	private static Variable variable(String key, int domain) {
		return new Variable(key, domain);
	}

	private record Oracle(double objective, int value) { }
}
