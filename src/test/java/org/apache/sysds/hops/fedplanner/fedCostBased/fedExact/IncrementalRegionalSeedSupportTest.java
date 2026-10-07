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
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class IncrementalRegionalSeedSupportTest {
	private static final double INF = Double.POSITIVE_INFINITY;
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(1_000_000, 10_000_000);

	@Test
	public void dirtyPropagationPreservesReverseCascadeAndSkipsDisconnectedFactors() {
		var original = variable("original", 3);
		var first = variable("first", 3);
		var second = variable("second", 3);
		var third = variable("third", 3);
		var disconnected = variable("disconnected", 3);
		var repeated = equality(second, third);
		var root = ExactPhysicalReducedSolver.reducedModel(1,
			List.of(original, first, second, third, disconnected),
			List.of(repeated, equality(first, second), equality(original, first),
				ExactCategoricalSolver.Factor.dense(List.of(disconnected), 4d, 1d, 3d),
				ExactCategoricalSolver.Factor.dense(List.of(), 0d), repeated), LIMITS);
		var statistics = new IncrementalRegionalSeed.SupportStatistics();

		int[] actual = IncrementalRegionalSeed.lift(root, List.of(2), LIMITS, statistics);
		Assert.assertArrayEquals(bruteForce(root, List.of(2)), actual);
		Assert.assertArrayEquals(new int[] {2, 2, 2, 2, 1}, actual);
		long legacyRevisions = legacyRevisionCount(root, List.of(2));
		Assert.assertTrue("dirty incidence must avoid unchanged disconnected rescans: optimized="
			+ statistics.factorRevisions + " legacy=" + legacyRevisions,
			statistics.factorRevisions < legacyRevisions);
		Assert.assertTrue(statistics.visitedCells > 0);
	}

	@Test
	public void randomizedUnequalDomainsMatchExactConditionalOracle() {
		Random random = new Random(0x5eed51L);
		for(int trial = 0; trial < 80; trial++) {
			var o0 = variable("o0-" + trial, 2);
			var o1 = variable("o1-" + trial, 3);
			var a = variable("a-" + trial, 2);
			var b = variable("b-" + trial, 3);
			List<ExactCategoricalSolver.Variable> variables = List.of(o0, o1, a, b);
			List<ExactCategoricalSolver.Factor> factors = List.of(
				randomFactor(random, List.of(o0, a)),
				randomFactor(random, List.of(a, b)),
				randomFactor(random, List.of(o1, b)),
				randomFactor(random, List.of(o0, o1, a)),
				ExactCategoricalSolver.Factor.dense(List.of(), trial % 3));
			ExactPhysicalReducedSolver.CompactModel root;
			try {
				root = ExactPhysicalReducedSolver.reducedModel(2, variables, factors, LIMITS);
			}
			catch(IllegalArgumentException globallyInfeasible) {
				continue;
			}
			for(int left = 0; left < 2; left++)
				for(int right = 0; right < 3; right++) {
					List<Integer> seed = List.of(left, right);
					int[] expected = bruteForce(root, seed);
					try {
						int[] actual = IncrementalRegionalSeed.lift(root, seed, LIMITS);
						Assert.assertNotNull("lift accepted a seed with no exact completion", expected);
						Assert.assertEquals("trial=" + trial + " seed=" + seed,
							total(root, expected), total(root, actual), 0d);
						for(int variable = 0; variable < root.originalDecisionCount(); variable++)
							Assert.assertEquals(root.reducedValue(variable, seed.get(variable)), actual[variable]);
					}
					catch(IllegalArgumentException failure) {
						Assert.assertNull("lift rejected an exactly completable seed at trial=" + trial
							+ " seed=" + seed + " message=" + failure.getMessage(), expected);
						Assert.assertEquals("INCREMENTAL_REGIONAL_SEED_INFEASIBLE", failure.getMessage());
					}
				}
		}
	}

	@Test
	public void repeatedLiftsUseFrozenFactorsWithoutReplayingLazyCallbacks() {
		var original = variable("callback-original", 2);
		var auxiliary = variable("callback-aux", 2);
		AtomicInteger calls = new AtomicInteger();
		var lazy = ExactCategoricalSolver.Factor.lazy(List.of(original, auxiliary), values -> {
			calls.incrementAndGet();
			return values[0] == values[1] ? 0d : INF;
		});
		var root = ExactPhysicalReducedSolver.reducedModel(1, List.of(original, auxiliary),
			List.of(lazy), LIMITS);
		int frozenCalls = calls.get();

		Assert.assertArrayEquals(new int[] {0, 0},
			IncrementalRegionalSeed.lift(root, List.of(0), LIMITS));
		Assert.assertArrayEquals(new int[] {1, 1},
			IncrementalRegionalSeed.lift(root, List.of(1), LIMITS));
		Assert.assertEquals("seed propagation must read immutable frozen tables", frozenCalls, calls.get());
	}

	@Test
	public void overflowAndConditionalLimitsKeepExistingFailures() {
		var original = variable("overflow-original", 1);
		var auxiliary = variable("overflow-aux", 2);
		var overflow = ExactPhysicalReducedSolver.reducedModel(1, List.of(original), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(original), Double.MAX_VALUE),
			ExactCategoricalSolver.Factor.dense(List.of(original), Double.MAX_VALUE)), LIMITS);
		assertFailure("INCREMENTAL_REGIONAL_SEED_INFEASIBLE",
			() -> IncrementalRegionalSeed.lift(overflow, List.of(0), LIMITS));

		var free = ExactPhysicalReducedSolver.reducedModel(1, List.of(original, auxiliary), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(original), 0d),
			ExactCategoricalSolver.Factor.dense(List.of(auxiliary), 2d, 1d)), LIMITS);
		assertFailure("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED|source=seed-condition|cells=3|limit=2",
			() -> IncrementalRegionalSeed.lift(free, List.of(0),
				new ExactCategoricalSolver.Limits(2, 2)));
	}

	private static ExactCategoricalSolver.Factor randomFactor(Random random,
		List<ExactCategoricalSolver.Variable> scope) {
		int cells = scope.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		double[] values = new double[cells];
		for(int cell = 0; cell < cells; cell++)
			values[cell] = random.nextInt(5) == 0 ? INF : random.nextInt(7);
		values[random.nextInt(cells)] = random.nextInt(7);
		return ExactCategoricalSolver.Factor.dense(scope, values);
	}

	private static int[] bruteForce(ExactPhysicalReducedSolver.CompactModel root,
		List<Integer> sourceSeed) {
		int[] fixed = new int[root.variables().size()];
		Arrays.fill(fixed, -1);
		for(int variable = 0; variable < root.originalDecisionCount(); variable++) {
			int reduced;
			try {
				reduced = root.reducedValue(variable, sourceSeed.get(variable));
			}
			catch(IllegalArgumentException invalid) {
				return null;
			}
			if(reduced < 0)
				return null;
			fixed[variable] = reduced;
		}
		int cells = root.variables().stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		int[] candidate = new int[fixed.length];
		int[] best = null;
		double bestCost = INF;
		for(int cell = 0; cell < cells; cell++) {
			decode(cell, root.variables(), candidate);
			boolean matches = true;
			for(int variable = 0; variable < root.originalDecisionCount(); variable++)
				matches &= candidate[variable] == fixed[variable];
			if(!matches)
				continue;
			double cost = total(root, candidate);
			if(cost < bestCost) {
				bestCost = cost;
				best = candidate.clone();
			}
		}
		return best;
	}

	private static double total(ExactPhysicalReducedSolver.CompactModel root, int[] assignment) {
		double total = 0d;
		Map<ExactCategoricalSolver.Variable,Integer> index = new IdentityHashMap<>();
		for(int variable = 0; variable < root.variables().size(); variable++)
			index.put(root.variables().get(variable), variable);
		for(var factor : root.factors()) {
			int[] local = factor.scope().stream().mapToInt(variable -> assignment[index.get(variable)]).toArray();
			double cost = factor.cost(local);
			if(!Double.isFinite(cost))
				return INF;
			total += cost;
		}
		return Double.isFinite(total) ? total : INF;
	}

	private static long legacyRevisionCount(ExactPhysicalReducedSolver.CompactModel root,
		List<Integer> sourceSeed) {
		Map<ExactCategoricalSolver.Variable,Integer> index = new IdentityHashMap<>();
		boolean[][] active = new boolean[root.variables().size()][];
		for(int variable = 0; variable < active.length; variable++) {
			index.put(root.variables().get(variable), variable);
			active[variable] = new boolean[root.variables().get(variable).domainSize()];
			Arrays.fill(active[variable], true);
		}
		for(int variable = 0; variable < root.originalDecisionCount(); variable++) {
			Arrays.fill(active[variable], false);
			active[variable][root.reducedValue(variable, sourceSeed.get(variable))] = true;
		}
		long revisions = 0;
		boolean changed;
		do {
			changed = false;
			for(var factor : root.factors()) {
				revisions++;
				boolean[][] supported = new boolean[factor.scope().size()][];
				int[] scope = new int[factor.scope().size()];
				for(int position = 0; position < scope.length; position++) {
					scope[position] = index.get(factor.scope().get(position));
					supported[position] = new boolean[active[scope[position]].length];
				}
				legacyMark(factor, scope, active, supported, new int[scope.length], 0);
				for(int position = 0; position < scope.length; position++)
					for(int value = 0; value < active[scope[position]].length; value++)
						if(active[scope[position]][value] && !supported[position][value]) {
							active[scope[position]][value] = false;
							changed = true;
						}
			}
		} while(changed);
		return revisions;
	}

	private static boolean legacyMark(ExactCategoricalSolver.Factor factor, int[] scope,
		boolean[][] active, boolean[][] supported, int[] values, int position) {
		if(position == scope.length) {
			if(!Double.isFinite(factor.cost(values)))
				return false;
			for(int current = 0; current < values.length; current++)
				supported[current][values[current]] = true;
			return true;
		}
		boolean finite = false;
		for(int value = 0; value < active[scope[position]].length; value++)
			if(active[scope[position]][value]) {
				values[position] = value;
				finite |= legacyMark(factor, scope, active, supported, values, position + 1);
			}
		return finite;
	}

	private static void decode(int cell, List<ExactCategoricalSolver.Variable> variables,
		int[] values) {
		for(int position = variables.size() - 1; position >= 0; position--) {
			values[position] = cell % variables.get(position).domainSize();
			cell /= variables.get(position).domainSize();
		}
	}

	private static void assertFailure(String message, Runnable operation) {
		try {
			operation.run();
			Assert.fail("expected " + message);
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals(message, expected.getMessage());
		}
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private static ExactCategoricalSolver.Factor equality(
		ExactCategoricalSolver.Variable left, ExactCategoricalSolver.Variable right) {
		double[] values = new double[left.domainSize() * right.domainSize()];
		Arrays.fill(values, INF);
		for(int value = 0; value < Math.min(left.domainSize(), right.domainSize()); value++)
			values[value * right.domainSize() + value] = 0d;
		return ExactCategoricalSolver.Factor.dense(List.of(left, right), values);
	}
}
