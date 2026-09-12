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

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class IncrementalRegionalSeedTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(100_000, 1_000_000);

	@Test
	public void supportPropagationCompletesAuxiliaryChain() {
		var original = variable("original", 2);
		var first = variable("first-aux", 2);
		var second = variable("second-aux", 2);
		var root = ExactPhysicalReducedSolver.reducedModel(1,
			List.of(original, first, second),
			List.of(equality(original, first), equality(first, second)), LIMITS);

		Assert.assertArrayEquals(new int[] {1, 1, 1},
			IncrementalRegionalSeed.lift(root, List.of(1), LIMITS));
	}

	@Test
	public void originalValueUsesExactReducedDomainMapping() {
		var original = variable("equivalent-original", 3);
		var auxiliary = variable("forced-aux", 2);
		var root = ExactPhysicalReducedSolver.reducedModel(1, List.of(original, auxiliary),
			List.of(
				ExactCategoricalSolver.Factor.dense(List.of(original),
					0d, 0d, Double.POSITIVE_INFINITY),
				ExactCategoricalSolver.Factor.dense(List.of(auxiliary),
					Double.POSITIVE_INFINITY, 0d)), LIMITS);

		int[] lifted = IncrementalRegionalSeed.lift(root, List.of(1), LIMITS);
		Assert.assertEquals(root.reducedValue(0, 1), lifted[0]);
		Assert.assertEquals(root.reducedValue(1, 1), lifted[1]);
	}

	@Test
	public void unresolvedAuxiliaryIsCompletedByConditionalExactSolve() {
		var original = variable("fixed-original", 2);
		var auxiliary = variable("free-aux", 2);
		var root = ExactPhysicalReducedSolver.reducedModel(1, List.of(original, auxiliary),
			List.of(
				ExactCategoricalSolver.Factor.dense(List.of(original), 0d, 2d),
				ExactCategoricalSolver.Factor.dense(List.of(auxiliary), 4d, 1d)), LIMITS);

		Assert.assertArrayEquals(new int[] {1, 1},
			IncrementalRegionalSeed.lift(root, List.of(1), LIMITS));
	}

	@Test
	public void unsupportedFixedOriginalFailsClosed() {
		var original = variable("unsupported-original", 2);
		var auxiliary = variable("aux", 2);
		var root = ExactPhysicalReducedSolver.reducedModel(1, List.of(original, auxiliary),
			List.of(
				equality(original, auxiliary),
				ExactCategoricalSolver.Factor.dense(List.of(auxiliary),
					0d, Double.POSITIVE_INFINITY)), LIMITS);

		try {
			IncrementalRegionalSeed.lift(root, List.of(1), LIMITS);
			Assert.fail("unsupported Regional seed was accepted");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals("INCREMENTAL_REGIONAL_SEED_INFEASIBLE", expected.getMessage());
		}
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private static ExactCategoricalSolver.Factor equality(
		ExactCategoricalSolver.Variable left, ExactCategoricalSolver.Variable right) {
		return ExactCategoricalSolver.Factor.dense(List.of(left, right),
			0d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0d);
	}
}
