/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class IncrementalRegionalSeedPreparedTest {
	private static final Limits LIMITS = new Limits(1_000_000, 10_000_000);

	@Test
	public void repeatedLiftsCompileOnePlanAndKeepPropagationWorkIdentical() {
		Variable original = new Variable("original", 4), auxiliary = new Variable("auxiliary", 4);
		var root = ExactPhysicalReducedSolver.reducedModel(1, List.of(original, auxiliary),
			List.of(Factor.functionalMap(original, auxiliary, new int[] {3, 2, 1, 0})), LIMITS);
		var cachedStats = new IncrementalRegionalSeed.SupportStatistics();
		var referenceStats = new IncrementalRegionalSeed.SupportStatistics();
		var prepared = IncrementalRegionalSeed.prepare(root, cachedStats);
		for(int iteration = 0; iteration < 120; iteration++) {
			List<Integer> seed = List.of(iteration % 4);
			Assert.assertArrayEquals(IncrementalRegionalSeed.lift(root, seed, LIMITS, referenceStats),
				prepared.lift(seed, LIMITS, cachedStats));
		}
		Assert.assertEquals(120L, referenceStats.supportPlanBuilds);
		Assert.assertEquals(1L, cachedStats.supportPlanBuilds);
		Assert.assertEquals(referenceStats.factorRevisions, cachedStats.factorRevisions);
		Assert.assertEquals(referenceStats.visitedCells, cachedStats.visitedCells);
		Assert.assertEquals(referenceStats.functionalRowsVisited, cachedStats.functionalRowsVisited);
	}

	@Test
	public void fixedSeedRandomRepeatedAssignmentsAndFailuresMatchFreshPlans() {
		Random random = new Random(0x5072657061726564L);
		for(int trial = 0; trial < 100; trial++) {
			List<Variable> variables = List.of(new Variable("o0", 2), new Variable("o1", 3),
				new Variable("a", 2), new Variable("b", 3));
			List<Factor> factors = List.of(randomFactor(random, List.of(variables.get(2), variables.get(3))),
				randomFactor(random, List.of(variables.get(0), variables.get(2))),
				randomFactor(random, List.of(variables.get(1), variables.get(3))), Factor.dense(List.of(), 0.1d));
			ExactPhysicalReducedSolver.CompactModel root;
			try { root = ExactPhysicalReducedSolver.reducedModel(2, variables, factors, LIMITS); }
			catch(IllegalArgumentException noGlobalSupport) { continue; }
			var prepared = IncrementalRegionalSeed.prepare(root);
			for(int repeat = 0; repeat < 3; repeat++)
				for(int a = 0; a < 2; a++) for(int b = 0; b < 3; b++) {
					List<Integer> seed = List.of(a, b);
					assertSameOutcome(() -> IncrementalRegionalSeed.lift(root, seed, LIMITS),
						() -> prepared.lift(seed, LIMITS));
				}
		}
	}

	@Test
	public void limitsInvalidSeedsAndCallerMutationDoNotLeakIntoNextLift() {
		Variable original = new Variable("original", 1), auxiliary = new Variable("free", 2);
		var root = ExactPhysicalReducedSolver.reducedModel(1, List.of(original, auxiliary),
			List.of(Factor.dense(List.of(original), 0d), Factor.dense(List.of(auxiliary), 4d, 1d)), LIMITS);
		var prepared = IncrementalRegionalSeed.prepare(root);
		Limits tight = new Limits(2, 2);
		assertSameOutcome(() -> IncrementalRegionalSeed.lift(root, List.of(0), tight),
			() -> prepared.lift(List.of(0), tight));
		for(List<Integer> invalid : List.of(List.<Integer>of(), List.of(-1), List.of(99), Arrays.asList((Integer)null)))
			assertSameOutcome(() -> IncrementalRegionalSeed.lift(root, invalid, LIMITS),
				() -> prepared.lift(invalid, LIMITS));
		List<Integer> seed = new ArrayList<>(List.of(0));
		int[] assignment = prepared.lift(seed, LIMITS);
		Arrays.fill(assignment, 99);
		seed.set(0, 99);
		Assert.assertArrayEquals(IncrementalRegionalSeed.lift(root, List.of(0), LIMITS),
			prepared.lift(List.of(0), LIMITS));
	}

	@Test
	public void structurallyEqualVariablesInDifferentRootsNeverSharePreparedAuthority() {
		var first = distinctRoot(3d, 1d);
		var second = distinctRoot(1d, 3d);
		Assert.assertNotSame(first.variables().get(0), second.variables().get(0));
		var firstPrepared = IncrementalRegionalSeed.prepare(first);
		var secondPrepared = IncrementalRegionalSeed.prepare(second);
		for(int iteration = 0; iteration < 20; iteration++) {
			Assert.assertArrayEquals(IncrementalRegionalSeed.lift(first, List.of(0), LIMITS),
				firstPrepared.lift(List.of(0), LIMITS));
			Assert.assertArrayEquals(IncrementalRegionalSeed.lift(second, List.of(0), LIMITS),
				secondPrepared.lift(List.of(0), LIMITS));
		}
		Assert.assertFalse(Arrays.equals(firstPrepared.lift(List.of(0), LIMITS),
			secondPrepared.lift(List.of(0), LIMITS)));
	}

	private static ExactPhysicalReducedSolver.CompactModel distinctRoot(double left, double right) {
		Variable original = new Variable("same-original", 1), auxiliary = new Variable("same-auxiliary", 2);
		return ExactPhysicalReducedSolver.reducedModel(1, List.of(original, auxiliary),
			List.of(Factor.dense(List.of(original), 0d), Factor.dense(List.of(auxiliary), left, right)), LIMITS);
	}

	private static Factor randomFactor(Random random, List<Variable> scope) {
		int cells = scope.stream().mapToInt(Variable::domainSize).reduce(1, Math::multiplyExact);
		double[] costs = new double[cells];
		for(int cell = 0; cell < cells; cell++)
			costs[cell] = random.nextInt(4) == 0 ? Double.POSITIVE_INFINITY : random.nextInt(5) * 0.1d;
		return Factor.dense(scope, costs);
	}

	private static void assertSameOutcome(Supplier<int[]> reference, Supplier<int[]> actual) {
		int[] expected;
		try { expected = reference.get(); }
		catch(IllegalArgumentException rejected) {
			try { actual.get(); Assert.fail("prepared accepted rejected seed: " + rejected.getMessage()); }
			catch(IllegalArgumentException failure) { Assert.assertEquals(rejected.getMessage(), failure.getMessage()); }
			return;
		}
		Assert.assertArrayEquals(expected, actual.get());
	}
}
