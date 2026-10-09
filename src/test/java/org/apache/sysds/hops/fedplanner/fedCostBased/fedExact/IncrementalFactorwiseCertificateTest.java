/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.ConditionalRegion;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class IncrementalFactorwiseCertificateTest {
	private static final Limits GENEROUS = new Limits(1_000_000,10_000_000);

	@Test
	public void hardFunctionalAndConditionalRelationsRetainTheExactIncumbent() {
		Variable left = new Variable("recognized-left",3);
		Variable right = new Variable("recognized-right",3);
		List<Variable> variables = List.of(left,right);
		List<Factor> representations = List.of(
			ExactCategoricalSolver.freezeValidatedFactor(Factor.lazy(variables,
				values -> values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY)),
			Factor.functionalMap(left,right,new int[] {0,1,2}),
			Factor.conditionalSupport(variables,0,List.of(
				new ConditionalRegion(0,new int[][] {null,{0}}),
				new ConditionalRegion(1,new int[][] {null,{1}}),
				new ConditionalRegion(2,new int[][] {null,{2}}))));
		for(Factor relation : representations) {
			RegionalSearchProblem problem = RegionalSearchProblem.generic(variables,List.of(relation));
			ExactPhysicalReducedSolver.CompactModel root = problem.reducedRoot(GENEROUS);
			int[] source = {2,2};
			int[] incumbent = compact(root,source);
			for(int[] block : List.of(new int[] {0},new int[] {0,1})) {
				SharedRegionalPreparation fast = new SharedRegionalPreparation(problem,GENEROUS,false);
				SharedRegionalPreparation reference = new SharedRegionalPreparation(problem,GENEROUS,false);
				SharedRegionalPreparation.ConditionalResult actual = fast.prepareConditional(
					source,block,root,incumbent).solve(incumbent);
				SharedRegionalPreparation.ConditionalResult expected = reference.prepareConditional(
					source,block,root).solve(incumbent);
				Assert.assertEquals(Double.doubleToRawLongBits(expected.block().objective()),
					Double.doubleToRawLongBits(actual.block().objective()));
				Assert.assertEquals(Arrays.stream(block).map(index -> source[index]).boxed().toList(),
					actual.block().assignmentInVariableOrder());
				Assert.assertArrayEquals(incumbent,actual.rootWitness());
				Assert.assertEquals(1L,fast.factorwiseCertifiedPreparations());
				Assert.assertEquals(0L,fast.blocks());
				Assert.assertEquals(1L,reference.blocks());
			}
		}
	}

	@Test
	public void feasibleSparseIncumbentSkipsBlockCompilationAndMatchesLegacyObjective() {
		Variable left = new Variable("certificate-left",100);
		Variable right = new Variable("certificate-right",100);
		int[] diagonal = new int[100];
		for(int value = 0; value < diagonal.length; value++)
			diagonal[value] = value * 100 + value;
		RegionalSearchProblem problem = RegionalSearchProblem.generic(List.of(left,right),
			List.of(Factor.finiteSupport(List.of(left,right),diagonal)));
		ExactPhysicalReducedSolver.CompactModel root = problem.reducedRoot(GENEROUS);
		int[] incumbent = compact(root,37,37);
		int[] source = {37,37};

		SharedRegionalPreparation certified = new SharedRegionalPreparation(problem,GENEROUS,false);
		SharedRegionalPreparation.ConditionalResult fast = certified.prepareConditional(
			source,new int[] {0},root,incumbent).solve(incumbent);
		SharedRegionalPreparation reference = new SharedRegionalPreparation(problem,GENEROUS,false);
		SharedRegionalPreparation.ConditionalResult exact = reference.prepareConditional(
			source,new int[] {0},root).solve(incumbent);

		Assert.assertEquals(Double.doubleToRawLongBits(exact.block().objective()),
			Double.doubleToRawLongBits(fast.block().objective()));
		Assert.assertEquals(List.of(37),fast.block().assignmentInVariableOrder());
		Assert.assertArrayEquals(incumbent,fast.rootWitness());
		Assert.assertEquals(1L,certified.factorwiseCertifiedPreparations());
		Assert.assertEquals("the certificate must bypass elimination preparation",0L,certified.blocks());
		Assert.assertEquals("a root relation certificate must also bypass conditioning",0L,
			certified.tableBuilds());
		Assert.assertEquals(0L,reference.factorwiseCertifiedPreparations());
		Assert.assertEquals(1L,reference.blocks());
		Assert.assertEquals(1L,reference.tableBuilds());
	}

	@Test
	public void infeasibleHardCellAndNumericFactorBothUseLegacyPreparation() {
		Variable left = new Variable("fallback-left",2);
		Variable right = new Variable("fallback-right",2);
		List<Variable> variables = List.of(left,right);
		RegionalSearchProblem sparseProblem = RegionalSearchProblem.generic(variables,
			List.of(Factor.finiteSupport(variables,0,3)));
		ExactPhysicalReducedSolver.CompactModel sparseRoot = sparseProblem.reducedRoot(GENEROUS);
		int[] hole = compact(sparseRoot,0,1);
		SharedRegionalPreparation sparse = new SharedRegionalPreparation(sparseProblem,GENEROUS,false);
		SharedRegionalPreparation.ConditionalResult sparseResult = sparse.prepareConditional(
			new int[] {0,1},new int[] {0,1},sparseRoot,hole).solve(hole);

		Assert.assertEquals(List.of(0,0),sparseResult.block().assignmentInVariableOrder());
		Assert.assertEquals(0L,sparse.factorwiseCertifiedPreparations());
		Assert.assertEquals(1L,sparse.blocks());

		RegionalSearchProblem numericProblem = RegionalSearchProblem.generic(variables,
			List.of(Factor.dense(variables,0d,0d,0d,1d)));
		ExactPhysicalReducedSolver.CompactModel numericRoot = numericProblem.reducedRoot(GENEROUS);
		int[] numericIncumbent = compact(numericRoot,0,0);
		SharedRegionalPreparation numeric = new SharedRegionalPreparation(numericProblem,GENEROUS,false);
		numeric.prepareConditional(new int[] {0,0},new int[] {0,1},numericRoot,numericIncumbent)
			.solve(numericIncumbent);
		Assert.assertEquals("unknown numeric minima must fail closed",0L,
			numeric.factorwiseCertifiedPreparations());
		Assert.assertEquals(1L,numeric.blocks());
	}

	@Test
	public void auxiliaryTieKeepsIncumbentWitnessWhileLegacyMayChooseCanonicalTuple() {
		Variable decision = new Variable("tie-decision",2);
		Variable auxiliary = new Variable("tie-auxiliary",2);
		List<Variable> variables = List.of(decision,auxiliary);
		List<Factor> factors = List.of(Factor.finiteSupport(variables,0,3));
		RegionalSearchProblem problem = new RegionalSearchProblem(variables,factors,1,ignored -> 0d);
		ExactPhysicalReducedSolver.CompactModel root = problem.reducedRoot(GENEROUS);
		int[] incumbent = compact(root,1,1);

		SharedRegionalPreparation certified = new SharedRegionalPreparation(problem,GENEROUS,false);
		SharedRegionalPreparation.ConditionalResult fast = certified.prepareConditional(
			new int[] {1},new int[] {0},root,incumbent).solve(incumbent);
		SharedRegionalPreparation reference = new SharedRegionalPreparation(problem,GENEROUS,false);
		SharedRegionalPreparation.ConditionalResult exact = reference.prepareConditional(
			new int[] {1},new int[] {0},root).solve(incumbent);

		Assert.assertEquals(0d,fast.block().objective(),0d);
		Assert.assertEquals(0d,exact.block().objective(),0d);
		Assert.assertEquals(List.of(1),fast.block().assignmentInVariableOrder());
		Assert.assertArrayEquals("the certified witness retains every incumbent auxiliary tie",
			incumbent,fast.rootWitness());
		Assert.assertEquals("the exact reference is free to choose its canonical tied tuple",
			List.of(0),exact.block().assignmentInVariableOrder());
	}

	@Test
	public void incumbentFromAnotherFixedBoundaryCannotCertifyCanonicalTie() {
		Variable free = new Variable("boundary-mismatch-free",2);
		Variable fixed = new Variable("boundary-mismatch-fixed",2);
		Variable marker = new Variable("boundary-mismatch-marker",2);
		List<Variable> variables = List.of(free,fixed,marker);
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables,
			List.of(Factor.finiteSupport(variables,0,3,4,7)));
		ExactPhysicalReducedSolver.CompactModel root = problem.reducedRoot(GENEROUS);
		int[] foreignIncumbent = compact(root,1,1,1);
		int[] conditionedSource = {1,0,0};

		SharedRegionalPreparation guarded = new SharedRegionalPreparation(problem,GENEROUS,false);
		SharedRegionalPreparation.ConditionalResult actual = guarded.prepareConditional(
			conditionedSource,new int[] {0},root,foreignIncumbent).solve(foreignIncumbent);
		SharedRegionalPreparation reference = new SharedRegionalPreparation(problem,GENEROUS,false);
		SharedRegionalPreparation.ConditionalResult expected = reference.prepareConditional(
			conditionedSource,new int[] {0},root).solve(foreignIncumbent);

		Assert.assertEquals(Double.doubleToRawLongBits(expected.block().objective()),
			Double.doubleToRawLongBits(actual.block().objective()));
		Assert.assertEquals("the guarded path must retain the legacy canonical tie",
			expected.block().assignmentInVariableOrder(),actual.block().assignmentInVariableOrder());
		Assert.assertEquals(List.of(0),actual.block().assignmentInVariableOrder());
		Assert.assertNull(actual.rootWitness());
		Assert.assertEquals(0L,guarded.factorwiseCertifiedPreparations());
		Assert.assertEquals(1L,guarded.blocks());
	}

	@Test
	public void localConditionalCertificatePreservesRawBitsAndStrictTiePolicy() {
		Variable decision = new Variable("local-policy-decision",2);
		Variable auxiliary = new Variable("local-policy-auxiliary",2);
		List<Variable> variables = List.of(decision,auxiliary);
		RegionalSearchProblem tiedProblem = new RegionalSearchProblem(variables,
			List.of(Factor.finiteSupport(variables,0,3)),1,ignored -> 0d);
		ExactPhysicalReducedSolver.CompactModel tiedRoot = tiedProblem.reducedRoot(GENEROUS);
		int[] tiedIncumbent = compact(tiedRoot,1,1);
		SharedRegionalPreparation certified = new SharedRegionalPreparation(tiedProblem,GENEROUS,false);
		SharedRegionalPreparation.ConditionalResult tiedFast = certified.prepareConditional(
			new int[] {1},new int[] {0},tiedRoot,tiedIncumbent).solve(tiedIncumbent);
		SharedRegionalPreparation legacyTie = new SharedRegionalPreparation(tiedProblem,GENEROUS,false);
		SharedRegionalPreparation.ConditionalResult tiedExact = legacyTie.prepareConditional(
			new int[] {1},new int[] {0},tiedRoot).solve(tiedIncumbent);

		Assert.assertEquals(Double.doubleToRawLongBits(tiedExact.block().objective()),
			Double.doubleToRawLongBits(tiedFast.block().objective()));
		Assert.assertEquals("a certified local tie retains the incumbent decision",
			List.of(1),tiedFast.block().assignmentInVariableOrder());
		Assert.assertArrayEquals(tiedIncumbent,tiedFast.rootWitness());
		Assert.assertEquals(1L,certified.factorwiseCertifiedPreparations());

		Variable boundary = new Variable("local-policy-boundary",2);
		List<Variable> numericVariables = List.of(decision,boundary);
		RegionalSearchProblem improvingProblem = RegionalSearchProblem.generic(numericVariables,
			List.of(Factor.dense(numericVariables,0d,0d,2d,2d)));
		ExactPhysicalReducedSolver.CompactModel improvingRoot = improvingProblem.reducedRoot(GENEROUS);
		int[] expensiveIncumbent = compact(improvingRoot,1,0);
		SharedRegionalPreparation guarded = new SharedRegionalPreparation(improvingProblem,GENEROUS,false);
		SharedRegionalPreparation.ConditionalResult improved = guarded.prepareConditional(
			new int[] {1,0},new int[] {0},improvingRoot,expensiveIncumbent).solve(expensiveIncumbent);
		SharedRegionalPreparation legacyImprove = new SharedRegionalPreparation(improvingProblem,GENEROUS,false);
		SharedRegionalPreparation.ConditionalResult expectedImprovement = legacyImprove.prepareConditional(
			new int[] {1,0},new int[] {0},improvingRoot).solve(expensiveIncumbent);

		Assert.assertEquals(Double.doubleToRawLongBits(expectedImprovement.block().objective()),
			Double.doubleToRawLongBits(improved.block().objective()));
		Assert.assertEquals(List.of(0),improved.block().assignmentInVariableOrder());
		Assert.assertEquals(expectedImprovement.block().assignmentInVariableOrder(),
			improved.block().assignmentInVariableOrder());
		Assert.assertEquals("numeric costs must retain the strict-improvement solve",0L,
			guarded.factorwiseCertifiedPreparations());
		Assert.assertEquals(1L,guarded.blocks());
	}

	@Test
	public void resourcePreflightRejectsBeforeCertificate() {
		Variable block = new Variable("resource-block",2);
		Variable boundary = new Variable("resource-boundary",2);
		List<Variable> variables = List.of(block,boundary);
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables,
			List.of(Factor.finiteSupport(variables,0,1,2)));
		ExactPhysicalReducedSolver.CompactModel root = problem.reducedRoot(GENEROUS);
		int[] incumbent = compact(root,0,0);
		SharedRegionalPreparation preparation = new SharedRegionalPreparation(
			problem,GENEROUS,GENEROUS,100L,1L,false);

		Assert.assertNull(preparation.prepareConditional(
			new int[] {0,0},new int[] {0},root,incumbent));
		Assert.assertEquals(0L,preparation.factorwiseCertifiedPreparations());
		Assert.assertEquals(0L,preparation.blocks());
		Assert.assertTrue(preparation.lastFallbackReason().startsWith(
			"EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED|source=local-shared-condition"));
	}

	@Test
	public void fixedSeedSparseRelationsMatchLegacyAcrossBoundariesAndDeadOptions() {
		Random random = new Random(0x5eedc0deL);
		for(int trial = 0; trial < 40; trial++) {
			List<Variable> variables = List.of(new Variable("random-a-" + trial,3),
				new Variable("random-b-" + trial,3),new Variable("random-c-" + trial,3));
			boolean[] admitted = new boolean[27];
			for(int cell = 0; cell < admitted.length; cell++)
				admitted[cell] = random.nextBoolean();
			int chosen = random.nextInt(admitted.length);
			admitted[chosen] = true;
			List<Integer> cells = new ArrayList<>();
			for(int cell = 0; cell < admitted.length; cell++)
				if(admitted[cell])
					cells.add(cell);
			RegionalSearchProblem problem = RegionalSearchProblem.generic(variables,List.of(
				Factor.finiteSupport(variables,cells.stream().mapToInt(Integer::intValue).toArray())));
			ExactPhysicalReducedSolver.CompactModel root = problem.reducedRoot(GENEROUS);
			int[] source = {chosen / 9,(chosen / 3) % 3,chosen % 3};
			int[] incumbent = compact(root,source);
			int[] block = trial % 2 == 0 ? new int[] {0,1} : new int[] {1,2};
			SharedRegionalPreparation certified = new SharedRegionalPreparation(problem,GENEROUS,false);
			SharedRegionalPreparation.ConditionalResult fast = certified.prepareConditional(
				source,block,root,incumbent).solve(incumbent);
			SharedRegionalPreparation reference = new SharedRegionalPreparation(problem,GENEROUS,false);
			SharedRegionalPreparation.ConditionalResult exact = reference.prepareConditional(
				source,block,root).solve(incumbent);

			Assert.assertEquals("trial=" + trial,
				Double.doubleToRawLongBits(exact.block().objective()),
				Double.doubleToRawLongBits(fast.block().objective()));
			Assert.assertEquals("trial=" + trial,Arrays.stream(block)
				.map(index -> source[index]).boxed().toList(),fast.block().assignmentInVariableOrder());
			Assert.assertArrayEquals("trial=" + trial,incumbent,fast.rootWitness());
			Assert.assertEquals("trial=" + trial,1L,certified.factorwiseCertifiedPreparations());
			Assert.assertEquals("trial=" + trial,0L,certified.blocks());
			Assert.assertEquals("trial=" + trial,1L,reference.blocks());
		}
	}

	private static int[] compact(ExactPhysicalReducedSolver.CompactModel root, int... source) {
		int[] compact = new int[root.variables().size()];
		for(int index = 0; index < source.length; index++) {
			compact[index] = root.reducedValue(index,source[index]);
			Assert.assertTrue("source value must survive root reduction",compact[index] >= 0);
		}
		return compact;
	}
}
