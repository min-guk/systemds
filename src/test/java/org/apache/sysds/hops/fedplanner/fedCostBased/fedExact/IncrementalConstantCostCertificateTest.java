/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
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

public class IncrementalConstantCostCertificateTest {
	private static final Limits GENEROUS = new Limits(1_000_000, 10_000_000);

	@Test
	public void positiveConstantWithInfinityHolesSkipsPreparationAndMatchesReferenceBits() {
		Variable left = new Variable("constant-left", 2), right = new Variable("constant-right", 2);
		List<Variable> variables = List.of(left, right);
		Factor numeric = Factor.dense(variables, 2d, Double.POSITIVE_INFINITY,
			Double.POSITIVE_INFINITY, 2d);
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables, List.of(numeric));
		assertCertifiedParity(problem, new int[] {1, 1}, new int[] {0, 1}, 2d, 0, 0);
		Assert.assertEquals(Double.doubleToRawLongBits(2d), numeric.constantFiniteCostBits().getAsLong());
	}

	@Test
	public void boundaryConditionCanCreateAConstantNumericSlice() {
		Variable free = new Variable("conditioned-free", 2), boundary = new Variable("conditioned-boundary", 2);
		List<Variable> variables = List.of(free, boundary);
		Factor numeric = Factor.dense(variables, 3d, 4d, 3d, 5d);
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables, List.of(numeric));
		assertCertifiedParity(problem, new int[] {1, 0}, new int[] {0}, 3d, 1, 0);
		Assert.assertTrue("the unconditional factor is not constant",
			numeric.constantFiniteCostBits().isEmpty());

		ExactPhysicalReducedSolver.CompactModel root = problem.reducedRoot(GENEROUS);
		int[] source = {1, 0}, incumbent = compact(root, source);
		SharedRegionalPreparation warmed = new SharedRegionalPreparation(problem, GENEROUS, false);
		warmed.prepareConditional(source, new int[] {0}, root).solve(incumbent);
		var cached = warmed.prepareConditional(source, new int[] {0}, root, incumbent).solve(incumbent);
		Assert.assertEquals(Double.doubleToRawLongBits(3d),
			Double.doubleToRawLongBits(cached.block().objective()));
		Assert.assertEquals(1L, warmed.tableBuilds());
		Assert.assertEquals(1L, warmed.cacheHits());
		Assert.assertEquals(1L, warmed.factorwiseCertifiedPreparations());
		Assert.assertEquals("only the uncertified warm-up compiles", 1L, warmed.blocks());
	}

	@Test
	public void variedNumericSliceFallsBackToExactPreparation() {
		Variable free = new Variable("varied-free", 2), boundary = new Variable("varied-boundary", 2);
		List<Variable> variables = List.of(free, boundary);
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables,
			List.of(Factor.dense(variables, 3d, 4d, 5d, 6d)));
		ExactPhysicalReducedSolver.CompactModel root = problem.reducedRoot(GENEROUS);
		int[] source = {0, 0}, incumbent = compact(root, source);
		SharedRegionalPreparation guarded = new SharedRegionalPreparation(problem, GENEROUS, false);
		var actual = guarded.prepareConditional(source, new int[] {0}, root, incumbent).solve(incumbent);
		SharedRegionalPreparation reference = new SharedRegionalPreparation(problem, GENEROUS, false);
		var expected = reference.prepareConditional(source, new int[] {0}, root).solve(incumbent);
		Assert.assertEquals(expected.block().assignmentInVariableOrder(), actual.block().assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.block().objective()),
			Double.doubleToRawLongBits(actual.block().objective()));
		Assert.assertEquals(0L, guarded.factorwiseCertifiedPreparations());
		Assert.assertEquals(1L, guarded.blocks());
	}

	@Test
	public void subnormalAndMaximumConstantsPreserveCompensatedObjectiveBits() {
		Variable decision = new Variable("raw-bits-decision", 2);
		List<Variable> variables = List.of(decision);
		Factor maximum = Factor.dense(variables, Double.MAX_VALUE, Double.MAX_VALUE);
		Factor subnormal = Factor.dense(variables, Double.MIN_VALUE, Double.MIN_VALUE);
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables, List.of(maximum, subnormal));
		assertCertifiedParity(problem, new int[] {0}, new int[] {0}, Double.MAX_VALUE, 0, 0);
		Assert.assertEquals(Double.doubleToRawLongBits(Double.MAX_VALUE),
			maximum.constantFiniteCostBits().getAsLong());
		Assert.assertEquals(Double.doubleToRawLongBits(Double.MIN_VALUE),
			subnormal.constantFiniteCostBits().getAsLong());
	}

	@Test
	public void certifiedTieRetainsAuxiliaryWitness() {
		Variable decision = new Variable("aux-decision", 2), auxiliary = new Variable("aux-value", 2);
		List<Variable> variables = List.of(decision, auxiliary);
		List<Factor> factors = List.of(
			Factor.dense(variables, 5d, 5d, 5d, 5d),
			Factor.finiteSupport(variables, 0, 3));
		RegionalSearchProblem problem = new RegionalSearchProblem(variables, factors, 1,
			assignment -> RegionalSearchProblem.evaluateFactors(variables, factors,
				List.of(assignment.get(0), assignment.get(0))));
		ExactPhysicalReducedSolver.CompactModel root = problem.reducedRoot(GENEROUS);
		int[] source = {1}, incumbent = compact(root, 1, 1);
		SharedRegionalPreparation preparation = new SharedRegionalPreparation(problem, GENEROUS, false);
		var result = preparation.prepareConditional(source, new int[] {0}, root, incumbent).solve(incumbent);
		Assert.assertEquals(Double.doubleToRawLongBits(5d),
			Double.doubleToRawLongBits(result.block().objective()));
		Assert.assertArrayEquals(incumbent, result.rootWitness());
		Assert.assertEquals(1L, preparation.factorwiseCertifiedPreparations());
		Assert.assertEquals(0L, preparation.blocks());
	}

	@Test
	public void invalidOrNonconstantDenseCostsNeverReceiveConstantAuthority() {
		Variable value = new Variable("classification", 2);
		Assert.assertTrue(Factor.dense(List.of(value), 1d, 2d).constantFiniteCostBits().isEmpty());
		Assert.assertTrue(Factor.dense(List.of(value), -1d, -1d).constantFiniteCostBits().isEmpty());
		Assert.assertTrue(Factor.dense(List.of(value), -0d, -0d).constantFiniteCostBits().isEmpty());
		Assert.assertTrue(Factor.dense(List.of(value), Double.NaN, Double.NaN)
			.constantFiniteCostBits().isEmpty());
		Assert.assertTrue(Factor.dense(List.of(value), Double.POSITIVE_INFINITY,
			Double.POSITIVE_INFINITY).constantFiniteCostBits().isEmpty());
	}

	@Test
	public void fixedBoundaryMismatchAndResourcePreflightStillFailClosed() {
		Variable free = new Variable("guard-free", 2), boundary = new Variable("guard-boundary", 2);
		List<Variable> variables = List.of(free, boundary);
		List<Factor> factors = List.of(
			Factor.dense(variables, 7d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 7d),
			Factor.finiteSupport(variables, 0, 3));
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables, factors);
		ExactPhysicalReducedSolver.CompactModel root = problem.reducedRoot(GENEROUS);
		int[] foreign = compact(root, 1, 1);
		SharedRegionalPreparation mismatch = new SharedRegionalPreparation(problem, GENEROUS, false);
		mismatch.prepareConditional(new int[] {0, 0}, new int[] {0}, root, foreign).solve(foreign);
		Assert.assertEquals(0L, mismatch.factorwiseCertifiedPreparations());
		Assert.assertEquals(1L, mismatch.blocks());

		int[] incumbent = compact(root, 0, 0);
		SharedRegionalPreparation limited = new SharedRegionalPreparation(
			problem, GENEROUS, GENEROUS, 100L, 1L, false);
		Assert.assertNull(limited.prepareConditional(new int[] {0, 0}, new int[] {0}, root, incumbent));
		Assert.assertEquals(0L, limited.factorwiseCertifiedPreparations());
		Assert.assertTrue(limited.lastFallbackReason().startsWith(
			"EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED|source=local-shared-condition"));
	}

	@Test
	public void fixedSeedConstantRelationsMatchLegacyReference() {
		Random random = new Random(0x636f6e7374616e74L);
		for(int trial = 0; trial < 40; trial++) {
			List<Variable> variables = List.of(new Variable("random-left-" + trial, 3),
				new Variable("random-right-" + trial, 3));
			double constant = trial % 3 == 0 ? Double.MIN_VALUE : trial + 0.25d;
			double[] values = new double[9];
			Arrays.fill(values, Double.POSITIVE_INFINITY);
			List<Integer> admitted = new ArrayList<>();
			for(int cell = 0; cell < values.length; cell++)
				if(random.nextBoolean()) {
					values[cell] = constant;
					admitted.add(cell);
				}
			if(admitted.isEmpty()) {
				values[0] = constant;
				admitted.add(0);
			}
			int selected = admitted.get(random.nextInt(admitted.size()));
			RegionalSearchProblem problem = RegionalSearchProblem.generic(variables,
				List.of(Factor.dense(variables, values)));
			assertCertifiedParity(problem, new int[] {selected / 3, selected % 3},
				new int[] {0, 1}, constant, 0, 0);
		}
	}

	private static void assertCertifiedParity(RegionalSearchProblem problem, int[] source, int[] block,
		double expectedObjective, long expectedTableBuilds, long expectedBlocks) {
		ExactPhysicalReducedSolver.CompactModel root = problem.reducedRoot(GENEROUS);
		int[] incumbent = compact(root, source);
		SharedRegionalPreparation certified = new SharedRegionalPreparation(problem, GENEROUS, false);
		var actual = certified.prepareConditional(source, block, root, incumbent).solve(incumbent);
		SharedRegionalPreparation reference = new SharedRegionalPreparation(problem, GENEROUS, false);
		var expected = reference.prepareConditional(source, block, root).solve(incumbent);
		Assert.assertEquals("a certified local tie retains the incumbent block",
			Arrays.stream(block).map(index -> root.sourceValue(index, incumbent[index])).boxed().toList(),
			actual.block().assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.block().objective()),
			Double.doubleToRawLongBits(actual.block().objective()));
		Assert.assertEquals(Double.doubleToRawLongBits(expectedObjective),
			Double.doubleToRawLongBits(actual.block().objective()));
		Assert.assertArrayEquals(incumbent, actual.rootWitness());
		Assert.assertEquals(1L, certified.factorwiseCertifiedPreparations());
		Assert.assertEquals(expectedTableBuilds, certified.tableBuilds());
		Assert.assertEquals(expectedBlocks, certified.blocks());
	}

	private static int[] compact(ExactPhysicalReducedSolver.CompactModel root, int... source) {
		int[] result = new int[root.variables().size()];
		for(int index = 0; index < source.length; index++) {
			result[index] = root.reducedValue(index, source[index]);
			Assert.assertTrue("source value must survive root reduction", result[index] >= 0);
		}
		return result;
	}
}
