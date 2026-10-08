/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/** Deterministic work and fallback contracts for certified suffix cost pruning. */
public class CertifiedCostPruningWorkTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(1_000_000L, 10_000_000L);
	private static final double INF = Double.POSITIVE_INFINITY;

	@After
	public void clearAblation() {
		System.clearProperty(PruningAblation.PROPERTY);
	}

	@Test
	public void localOrdinarySuffixBoundSkipsTheUnreadSevenCost() {
		var x = variable("local-ordinary-x", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x);
		List<ExactCategoricalSolver.BoundaryMessage> leaves = leaves(variables, List.of(
			factor(x, 3d, 4d), factor(x, 7d, 9d)));
		var legacyCounters = counters();
		var suffixCounters = counters();
		var legacy = merge(leaves, List.of(), ExactCategoricalSolver.CostPruningMode.LEGACY,
			legacyCounters);
		var suffix = merge(leaves, List.of(), ExactCategoricalSolver.CostPruningMode.SUFFIX,
			suffixCounters);

		assertMessageParity(legacy, suffix, variables);
		Assert.assertEquals(10d, suffix.minimum(), 0d);
		int[] assignment = {-1};
		suffix.decodeInto(assignment, variables);
		Assert.assertArrayEquals(new int[] {0}, assignment);
		assertSuffixReduction(legacyCounters, suffixCounters);
		Assert.assertEquals(4L, legacyCounters.childEvaluations());
		Assert.assertEquals(3L, suffixCounters.childEvaluations());
	}

	@Test
	public void localSupportQuotientUsesSuffixBoundsWithoutChangingLogicalRows() {
		var boundary = variable("local-support-boundary", 12);
		var internal = variable("local-support-internal", 8);
		List<ExactCategoricalSolver.Variable> variables = List.of(boundary, internal);
		double[] support = new double[96];
		double[] first = new double[96];
		double[] second = new double[96];
		for(int boundaryValue = 0; boundaryValue < 12; boundaryValue++)
			for(int internalValue = 0; internalValue < 8; internalValue++) {
				int cell = boundaryValue * 8 + internalValue;
				support[cell] = internalValue < 2 ? 0d : INF;
				first[cell] = internalValue == 0 ? 3d : internalValue == 1 ? 4d : 100d;
				second[cell] = internalValue == 0 ? 7d : internalValue == 1 ? 9d : 100d;
			}
		List<ExactCategoricalSolver.BoundaryMessage> leaves = leaves(variables, List.of(
			ExactCategoricalSolver.Factor.dense(List.of(boundary, internal), support),
			ExactCategoricalSolver.Factor.dense(List.of(boundary, internal), first),
			ExactCategoricalSolver.Factor.dense(List.of(boundary, internal), second)));
		var legacyCounters = counters();
		var suffixCounters = counters();
		var legacy = merge(leaves, List.of(boundary), ExactCategoricalSolver.CostPruningMode.LEGACY,
			legacyCounters);
		var suffix = merge(leaves, List.of(boundary), ExactCategoricalSolver.CostPruningMode.SUFFIX,
			suffixCounters);

		assertMessageParity(legacy, suffix, variables);
		Assert.assertArrayEquals(fill(12, 10d), suffix.minMarginals(boundary), 0d);
		Assert.assertArrayEquals(fill(12, 10d), suffix.lowerMinMarginals(boundary), 0d);
		Assert.assertTrue("fixture must use the >64-cell support path",
			suffixCounters.supportCellsExamined() > 0);
		Assert.assertTrue("repeated boundary profiles must use quotient storage",
			suffix.retainedCells() < suffix.cells() * 4L);
		assertSuffixReduction(legacyCounters, suffixCounters);
	}

	@Test
	public void globalDenseSuffixBoundReducesReadsAndPreservesEveryStep() {
		var x = variable("global-dense-x", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x);
		var compiled = compile(variables, List.of(factor(x, 3d, 4d), factor(x, 7d, 9d)),
			List.of(x.key()));
		GlobalRun legacy = solve(compiled, null, null,
			ExactCategoricalSolver.CostPruningMode.LEGACY);
		GlobalRun suffix = solve(compiled, null, null,
			ExactCategoricalSolver.CostPruningMode.SUFFIX);

		assertRunParity(legacy, suffix);
		Assert.assertEquals(10d, suffix.result().objective(), 0d);
		Assert.assertEquals(List.of(0), suffix.result().assignmentInVariableOrder());
		assertSuffixReduction(legacy.counters(), suffix.counters());
	}

	@Test
	public void globalSparseHardJoinUsesTheSameCertifiedSuffixCut() {
		var x = variable("global-sparse-x", 4);
		var boundary = variable("global-sparse-boundary", 20);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, boundary);
		double[] support = new double[80];
		Arrays.fill(support, INF);
		for(int value = 0; value < 20; value++) {
			support[value * 4] = 0d;
			support[value * 4 + 1] = 0d;
		}
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(boundary, x), support),
			factor(x, 3d, 4d, 100d, 100d),
			factor(x, 7d, 9d, 100d, 100d));
		var compiled = compile(variables, factors, List.of(x.key(), boundary.key()));
		GlobalRun legacy = solve(compiled, null, null,
			ExactCategoricalSolver.CostPruningMode.LEGACY);
		GlobalRun suffix = solve(compiled, null, null,
			ExactCategoricalSolver.CostPruningMode.SUFFIX);

		assertRunParity(legacy, suffix);
		Assert.assertEquals(10d, suffix.result().objective(), 0d);
		Assert.assertEquals(List.of(0, 0), suffix.result().assignmentInVariableOrder());
		Assert.assertTrue("hard join must avoid the full dense candidate reads",
			legacy.counters().childEvaluations() < legacy.counters().fullChildEvaluations());
		assertSuffixReduction(legacy.counters(), suffix.counters());
		Assert.assertTrue(suffix.counters().boundCellsExamined() > 0);
	}

	@Test
	public void localTypedHardJoinUnderSupportThresholdUsesCertifiedSuffixCut() {
		var boundary = variable("local-hard-boundary", 3);
		var internal = variable("local-hard-internal", 4);
		List<ExactCategoricalSolver.Variable> variables = List.of(boundary, internal);
		// Two finite internal values per boundary: 12 logical cells remains below the
		// >64 support/quotient dispatch threshold and exercises the typed-hard join.
		var hard = ExactCategoricalSolver.Factor.finiteSupport(
			List.of(boundary, internal), 0, 1, 4, 5, 8, 9);
		double[] first = new double[12];
		double[] second = new double[12];
		for(int row = 0; row < 3; row++) {
			first[row * 4] = 3d;
			first[row * 4 + 1] = 4d;
			first[row * 4 + 2] = first[row * 4 + 3] = 100d;
			second[row * 4] = 7d;
			second[row * 4 + 1] = 9d;
			second[row * 4 + 2] = second[row * 4 + 3] = 100d;
		}
		var inputs = leaves(variables, List.of(hard,
			ExactCategoricalSolver.Factor.dense(List.of(boundary, internal), first),
			ExactCategoricalSolver.Factor.dense(List.of(boundary, internal), second)));
		var legacyCounters = counters();
		var suffixCounters = counters();
		var legacy = merge(inputs, List.of(boundary),
			ExactCategoricalSolver.CostPruningMode.LEGACY, legacyCounters);
		var suffix = merge(inputs, List.of(boundary),
			ExactCategoricalSolver.CostPruningMode.SUFFIX, suffixCounters);

		assertMessageParity(legacy, suffix, variables);
		Assert.assertArrayEquals(fill(3, 10d), suffix.minMarginals(boundary), 0d);
		Assert.assertEquals("under-threshold fixture must not use support/quotient dispatch",
			0L, suffixCounters.supportCellsExamined());
		assertSuffixReduction(legacyCounters, suffixCounters);
	}

	@Test
	public void sparseNumericLowerRowsUseStoredIndexesForCertification() throws Exception {
		var x = variable("sparse-lower-x", 4);
		List<ExactCategoricalSolver.Variable> variables = List.of(x);
		var sparse = sparseNumericMessage(variables, x,
			new int[] {0, 2}, new double[] {3d, 4d}, new double[] {3d, 4d});
		var suffixLeaf = ExactCategoricalSolver.boundaryLeaf(variables,
			factor(x, 7d, 100d, 9d, 100d), LIMITS);
		var legacyCounters = counters();
		var suffixCounters = counters();
		var legacy = merge(List.of(sparse, suffixLeaf), List.of(),
			ExactCategoricalSolver.CostPruningMode.LEGACY, legacyCounters);
		var suffix = merge(List.of(sparse, suffixLeaf), List.of(),
			ExactCategoricalSolver.CostPruningMode.SUFFIX, suffixCounters);

		assertMessageParity(legacy, suffix, variables);
		Assert.assertEquals(10d, suffix.minimum(), 0d);
		Assert.assertEquals("compact lower rows must retain the nonnegative certificate",
			1L, suffixCounters.certifiedCostBuckets());
		assertSuffixReduction(legacyCounters, suffixCounters);
	}

	@Test
	public void dyadicSeparatorMajorQuotientUsesScaledCertifiedBounds() {
		var x = variable("dyadic-x", 2);
		var boundary = variable("dyadic-boundary", 16);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, boundary);
		double[] first = new double[32];
		for(int value = 0; value < 16; value++) {
			first[value * 2] = 3d;
			first[value * 2 + 1] = 4d;
		}
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(boundary, x), first),
			factor(x, 7d, 9d));
		var compiled = compile(variables, factors, List.of(x.key(), boundary.key()));
		var certificate = ExactDyadicCosts.certify(0, 8, factors.size(), true);
		GlobalRun legacy = solve(compiled, null, certificate,
			ExactCategoricalSolver.CostPruningMode.LEGACY);
		GlobalRun suffix = solve(compiled, null, certificate,
			ExactCategoricalSolver.CostPruningMode.SUFFIX);

		assertRunParity(legacy, suffix);
		Assert.assertEquals(10d, suffix.result().objective(), 0d);
		Assert.assertTrue("separator rows must share one quotient coordinate",
			suffix.steps().stream().anyMatch(step -> step.storedCells() < step.high().length));
		assertSuffixReduction(legacy.counters(), suffix.counters());
		Assert.assertTrue(suffix.counters().boundCellsExamined() > 0);
	}

	@Test
	public void dyadicDenseAndSparseCutsPreserveCarryBeyondFiftyThreeBits() {
		double large = 0x1p52;
		var denseX = variable("dyadic-carry-dense-x", 2);
		List<ExactCategoricalSolver.Variable> denseVariables = List.of(denseX);
		List<ExactCategoricalSolver.Factor> denseFactors = List.of(
			factor(denseX, large, large + 1d), factor(denseX, large, large + 3d));
		var denseCompiled = compile(denseVariables, denseFactors, List.of(denseX.key()));
		var certificate = ExactDyadicCosts.certify(0, 60, denseFactors.size(), true);
		GlobalRun densePrefix = solve(denseCompiled, null, certificate,
			ExactCategoricalSolver.CostPruningMode.LEGACY);
		GlobalRun denseSuffix = solve(denseCompiled, null, certificate,
			ExactCategoricalSolver.CostPruningMode.SUFFIX);
		assertRunParity(densePrefix, denseSuffix);
		Assert.assertEquals(0x1p53, denseSuffix.result().objective(), 0d);
		assertSuffixReduction(densePrefix.counters(), denseSuffix.counters());

		var sparseX = variable("dyadic-carry-sparse-x", 4);
		var boundary = variable("dyadic-carry-boundary", 20);
		List<ExactCategoricalSolver.Variable> sparseVariables = List.of(sparseX, boundary);
		double[] support = new double[80];
		Arrays.fill(support, INF);
		for(int row = 0; row < 20; row++) {
			support[row * 4] = 0d;
			support[row * 4 + 1] = 0d;
		}
		List<ExactCategoricalSolver.Factor> sparseFactors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(boundary, sparseX), support),
			factor(sparseX, large, large + 1d, large + 16d, large + 16d),
			factor(sparseX, large, large + 3d, large + 16d, large + 16d));
		var sparseCompiled = compile(sparseVariables, sparseFactors,
			List.of(sparseX.key(), boundary.key()));
		var sparseCertificate = ExactDyadicCosts.certify(0, 60, sparseFactors.size(), true);
		GlobalRun sparsePrefix = solve(sparseCompiled, null, sparseCertificate,
			ExactCategoricalSolver.CostPruningMode.LEGACY);
		GlobalRun sparseSuffix = solve(sparseCompiled, null, sparseCertificate,
			ExactCategoricalSolver.CostPruningMode.SUFFIX);
		assertRunParity(sparsePrefix, sparseSuffix);
		Assert.assertEquals(0x1p53, sparseSuffix.result().objective(), 0d);
		Assert.assertTrue(sparsePrefix.counters().childEvaluations()
			< sparsePrefix.counters().fullChildEvaluations());
		assertSuffixReduction(sparsePrefix.counters(), sparseSuffix.counters());
	}

	@Test
	public void sparseAccumulatorCrossesToDenseAfterSixtyFourDistinctRowsAndStillCuts() {
		var x = variable("crossover-x", 4);
		var boundary = variable("crossover-boundary", 80);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, boundary);
		double[] support = new double[320];
		double[] first = new double[320];
		Arrays.fill(support, INF);
		for(int row = 0; row < 80; row++) {
			support[row * 4] = support[row * 4 + 1] = 0d;
			first[row * 4] = row + 3d;
			first[row * 4 + 1] = row + 4d;
			first[row * 4 + 2] = first[row * 4 + 3] = row + 100d;
		}
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(boundary, x), support),
			ExactCategoricalSolver.Factor.dense(List.of(boundary, x), first),
			factor(x, 7d, 9d, 100d, 100d));
		var compiled = compile(variables, factors, List.of(x.key(), boundary.key()));
		GlobalRun legacy = solve(compiled, null, null,
			ExactCategoricalSolver.CostPruningMode.LEGACY);
		GlobalRun suffix = solve(compiled, null, null,
			ExactCategoricalSolver.CostPruningMode.SUFFIX);
		assertRunParity(legacy, suffix);
		var firstStep = suffix.steps().get(0);
		Assert.assertEquals(80, firstStep.high().length);
		Assert.assertEquals("all distinct rows must survive the sparse-map to dense crossover",
			80, firstStep.storedCells());
		assertSuffixReduction(legacy.counters(), suffix.counters());
	}

	@Test
	public void equalityIsNotAValidStrictSuffixCutAndKeepsTheFirstWitness() {
		var x = variable("strict-tie-x", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x);
		var compiled = compile(variables, List.of(factor(x, 3d, 4d), factor(x, 7d, 6d)),
			List.of(x.key()));
		GlobalRun suffix = solve(compiled, null, null,
			ExactCategoricalSolver.CostPruningMode.SUFFIX);

		Assert.assertEquals(10d, suffix.result().objective(), 0d);
		Assert.assertEquals(List.of(0), suffix.result().assignmentInVariableOrder());
		Assert.assertEquals("an equal lower bound is not a strict suffix proof",
			0L, suffix.counters().suffixCostCuts());
		Assert.assertEquals(suffix.counters().fullChildEvaluations(),
			suffix.counters().childEvaluations());
	}

	@Test
	public void allInfinityAndDefaultLegacyModesPreserveResultsAndErrors() {
		var x = variable("fallback-x", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x);
		List<ExactCategoricalSolver.Factor> finite = List.of(factor(x, 3d, 4d), factor(x, 7d, 9d));
		List<ExactCategoricalSolver.BoundaryMessage> finiteLeaves = leaves(variables, finite);
		var defaultMessage = ExactCategoricalSolver.mergeBoundary(finiteLeaves, List.of(), LIMITS);
		var legacyMessage = merge(finiteLeaves, List.of(), ExactCategoricalSolver.CostPruningMode.LEGACY,
			counters());
		assertMessageParity(defaultMessage, legacyMessage, variables);

		var compiled = compile(variables, finite, List.of(x.key()));
		var defaultResult = ExactCategoricalSolver.solve(compiled);
		var legacy = solve(compiled, null, null, ExactCategoricalSolver.CostPruningMode.LEGACY);
		Assert.assertEquals(defaultResult.objective(), legacy.result().objective(), 0d);
		Assert.assertEquals(defaultResult.assignmentInVariableOrder(),
			legacy.result().assignmentInVariableOrder());

		List<ExactCategoricalSolver.Factor> impossible = List.of(
			factor(x, INF, INF), factor(x, 7d, 9d));
		var impossibleCompiled = compile(variables, impossible, List.of(x.key()));
		for(var mode : ExactCategoricalSolver.CostPruningMode.values()) {
			IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
				() -> solve(impossibleCompiled, null, null, mode));
			Assert.assertEquals("EXACT_VE_NO_FEASIBLE_ASSIGNMENT", failure.getMessage());
		}
	}

	@Test
	public void negativeResidueAndWideLatticeBucketsFallBackWithoutSuffixCuts() {
		var x = variable("uncertified-x", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			factor(x, 1e16, 1e16), factor(x, .125d, .25d), factor(x, -1e16, -1e16));
		var compiled = compile(variables, factors, List.of(x.key()));
		GlobalRun legacy = solve(compiled, null, null,
			ExactCategoricalSolver.CostPruningMode.LEGACY);
		GlobalRun suffix = solve(compiled, null, null,
			ExactCategoricalSolver.CostPruningMode.SUFFIX);
		assertRunParity(legacy, suffix);
		Assert.assertEquals(0L, suffix.counters().suffixCostCuts());
		Assert.assertTrue(suffix.counters().uncertifiedCostBuckets() > 0);

		double wide = 0x1p53;
		List<ExactCategoricalSolver.BoundaryMessage> wideLeaves = leaves(variables, List.of(
			factor(x, wide, wide + 2d), factor(x, 1d, 1d)));
		var wideCounters = counters();
		merge(wideLeaves, List.of(), ExactCategoricalSolver.CostPruningMode.SUFFIX, wideCounters);
		Assert.assertEquals(0L, wideCounters.suffixCostCuts());
		Assert.assertTrue(wideCounters.uncertifiedCostBuckets() > 0);
	}

	@Test
	public void tieCallbackRemainsFullyObservableAndDisablesAllCostCuts() {
		var x = variable("callback-x", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x);
		var compiled = compile(variables, List.of(factor(x, 3d, 4d), factor(x, 7d, 9d)),
			List.of(x.key()));
		AtomicInteger calls = new AtomicInteger();
		var counters = counters();
		List<ExactCategoricalSolver.EliminationSnapshot> steps = new ArrayList<>();
		var result = ExactCategoricalSolver.solveWithPruningForTest(compiled,
			(variable, value) -> { calls.incrementAndGet(); return 0L; }, null,
			ExactCategoricalSolver.CostPruningMode.SUFFIX, counters, steps::add);

		Assert.assertEquals(10d, result.objective(), 0d);
		Assert.assertEquals(2, calls.get());
		Assert.assertEquals(0L, counters.costCuts());
		Assert.assertEquals(0L, counters.suffixCostCuts());
		Assert.assertEquals(counters.fullChildEvaluations(), counters.childEvaluations());
	}

	@Test
	public void subnormalAndNearMaximumBoundsPreserveRawLegacyResults() {
		for(double scale : new double[] {
			Double.MIN_VALUE, Double.MIN_NORMAL, 0x1p-500, 1d, 0x1p960, 0x1p1020}) {
			var x = variable("scaled-x-" + Double.doubleToRawLongBits(scale), 2);
			List<ExactCategoricalSolver.Variable> variables = List.of(x);
			List<ExactCategoricalSolver.Factor> factors = List.of(
				factor(x, 3d * scale, 4d * scale), factor(x, 7d * scale, 9d * scale));
			var compiled = compile(variables, factors, List.of(x.key()));
			GlobalRun legacy = solve(compiled, null, null,
				ExactCategoricalSolver.CostPruningMode.LEGACY);
			GlobalRun suffix = solve(compiled, null, null,
				ExactCategoricalSolver.CostPruningMode.SUFFIX);
			assertRunParity(legacy, suffix);
			assertSuffixReduction(legacy.counters(), suffix.counters());

			var certificate = ExactDyadicCosts.certifyTables(List.of(
				new double[] {3d * scale, 4d * scale},
				new double[] {7d * scale, 9d * scale}));
			Assert.assertTrue(certificate.reason(), certificate.supported());
			GlobalRun dyadicLegacy = solve(compiled, null, certificate,
				ExactCategoricalSolver.CostPruningMode.LEGACY);
			GlobalRun dyadicSuffix = solve(compiled, null, certificate,
				ExactCategoricalSolver.CostPruningMode.SUFFIX);
			assertRunParity(dyadicLegacy, dyadicSuffix);
			assertSuffixReduction(dyadicLegacy.counters(), dyadicSuffix.counters());

			var legacyCounters = counters();
			var suffixCounters = counters();
			assertMessageParity(merge(leaves(variables, factors), List.of(),
				ExactCategoricalSolver.CostPruningMode.LEGACY, legacyCounters),
				merge(leaves(variables, factors), List.of(),
					ExactCategoricalSolver.CostPruningMode.SUFFIX, suffixCounters), variables);
			assertSuffixReduction(legacyCounters, suffixCounters);
		}
	}

	@Test
	public void hundredFiveBitDyadicBoundsPreserveRawWordsChoicesAndObjective() {
		var x = variable("wide-dyadic-x", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x);
		List<double[]> tables = List.of(new double[] {0x1p104, 0x1p104},
			new double[] {0d, 1d}, new double[] {1d, 2d});
		List<ExactCategoricalSolver.Factor> factors = tables.stream()
			.map(table -> factor(x, table)).toList();
		var certificate = ExactDyadicCosts.certifyTables(tables);
		Assert.assertTrue(certificate.reason(), certificate.supported());
		Assert.assertTrue("fixture must exceed two-word double integer precision",
			certificate.maximumSumBits() > 100);
		var compiled = compile(variables, factors, List.of(x.key()));
		GlobalRun legacy = solve(compiled, null, certificate,
			ExactCategoricalSolver.CostPruningMode.LEGACY);
		GlobalRun suffix = solve(compiled, null, certificate,
			ExactCategoricalSolver.CostPruningMode.SUFFIX);

		assertRunParity(legacy, suffix);
		assertSuffixReduction(legacy.counters(), suffix.counters());
	}

	@Test
	public void malformedDominatedSuffixCostCannotBeHiddenByPruning() {
		for(double invalid : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, -0d}) {
			String expectedMessage = null;
			for(var mode : ExactCategoricalSolver.CostPruningMode.values()) {
				var x = variable("invalid-x-" + mode + '-' + Double.doubleToRawLongBits(invalid), 2);
				List<ExactCategoricalSolver.Variable> variables = List.of(x);
				List<ExactCategoricalSolver.Factor> factors = List.of(factor(x, 3d, 100d),
					ExactCategoricalSolver.Factor.lazy(List.of(x),
						assignment -> assignment[0] == 0 ? 7d : invalid));
				IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
					() -> solve(compile(variables, factors, List.of(x.key())), null, null, mode));
				Assert.assertTrue(failure.getMessage(), failure.getMessage().contains("COST"));
				if(expectedMessage == null)
					expectedMessage = failure.getMessage();
				else
					Assert.assertEquals(expectedMessage, failure.getMessage());
			}
		}
	}

	@Test
	public void localResidueLowerBoundDifferentFromExactPreservesRawWitnesses() {
		var x = variable("lower-residue-x", 2);
		var padA = variable("lower-residue-pad-a", 5);
		var padB = variable("lower-residue-pad-b", 7);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, padA, padB);
		List<ExactCategoricalSolver.Factor> residueFactors = List.of(
			factor(x, 1.0e16, 1.0e16), factor(x, 1d, 1d), factor(x, 1d, 1d));
		var residueLeaves = leaves(variables, residueFactors);
		var legacyResidue = merge(residueLeaves, List.of(x),
			ExactCategoricalSolver.CostPruningMode.LEGACY, counters());
		var suffixResidue = merge(residueLeaves, List.of(x),
			ExactCategoricalSolver.CostPruningMode.SUFFIX, counters());
		assertMessageParity(legacyResidue, suffixResidue, variables);
		Assert.assertNotEquals(Double.doubleToRawLongBits(suffixResidue.minimum()),
			Double.doubleToRawLongBits(suffixResidue.lowerBound()));

		double[] sparse = new double[70];
		Arrays.fill(sparse, INF);
		sparse[0] = sparse[35] = 0d;
		var sparseLeaf = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(x, padA, padB), sparse), LIMITS);
		var legacy = merge(List.of(legacyResidue, sparseLeaf), List.of(),
			ExactCategoricalSolver.CostPruningMode.LEGACY, counters());
		var suffix = merge(List.of(suffixResidue, sparseLeaf), List.of(),
			ExactCategoricalSolver.CostPruningMode.SUFFIX, counters());
		assertMessageParity(legacy, suffix, variables);
	}

	@Test
	public void localNonabsorbingInfinityPreservesFiniteLowerBound() throws Exception {
		var x = variable("lower-infinity-x", 2);
		var padA = variable("lower-infinity-pad-a", 5);
		var padB = variable("lower-infinity-pad-b", 7);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, padA, padB);
		double[] sparse = new double[70];
		Arrays.fill(sparse, INF);
		sparse[0] = sparse[35] = 0d;
		var guarded = lowerFiniteInfinityMessage(variables, x);
		var sparseLeaf = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(x, padA, padB), sparse), LIMITS);
		var legacy = merge(List.of(guarded, sparseLeaf), List.of(),
			ExactCategoricalSolver.CostPruningMode.LEGACY, counters());
		var suffix = merge(List.of(guarded, sparseLeaf), List.of(),
			ExactCategoricalSolver.CostPruningMode.SUFFIX, counters());

		assertMessageParity(legacy, suffix, variables);
		Assert.assertEquals(Double.doubleToRawLongBits(7d),
			Double.doubleToRawLongBits(suffix.lowerBound()));
		Assert.assertEquals(10d, suffix.minimum(), 0d);
	}

	@Test
	public void localOverflowFailureIsIdenticalWithSuffixPruning() {
		var a = variable("overflow-a", 5);
		var b = variable("overflow-b", 5);
		var c = variable("overflow-c", 5);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c);
		double[] nearMax = {Double.MAX_VALUE, INF, INF, INF, INF};
		double[] impossible = {INF, INF, INF, INF, INF};
		var leaves = leaves(variables, List.of(
			factor(a, nearMax), factor(b, nearMax), factor(c, impossible)));
		IllegalArgumentException legacy = Assert.assertThrows(IllegalArgumentException.class,
			() -> merge(leaves, List.of(), ExactCategoricalSolver.CostPruningMode.LEGACY,
				counters()));
		IllegalArgumentException suffix = Assert.assertThrows(IllegalArgumentException.class,
			() -> merge(leaves, List.of(), ExactCategoricalSolver.CostPruningMode.SUFFIX,
				counters()));

		Assert.assertEquals(legacy.getMessage(), suffix.getMessage());
		Assert.assertTrue(suffix.getMessage(),
			suffix.getMessage().startsWith("EXACT_VE_OBJECTIVE_OVERFLOW"));
	}

	private static GlobalRun solve(ExactCategoricalSolver.CompiledProblem compiled,
		ExactCategoricalSolver.TieCostFunction tie,
		ExactDyadicCosts.Certificate certificate,
		ExactCategoricalSolver.CostPruningMode mode) {
		var counters = counters();
		List<ExactCategoricalSolver.EliminationSnapshot> steps = new ArrayList<>();
		var result = ExactCategoricalSolver.solveWithPruningForTest(
			compiled, tie, certificate, mode, counters, steps::add);
		return new GlobalRun(result, counters, steps);
	}

	private static ExactCategoricalSolver.CompiledProblem compile(
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, List<String> order) {
		return ExactCategoricalSolver.compilePreferred(variables, factors, LIMITS, order);
	}

	private static ExactCategoricalSolver.BoundaryMessage merge(
		List<ExactCategoricalSolver.BoundaryMessage> leaves,
		List<ExactCategoricalSolver.Variable> boundary,
		ExactCategoricalSolver.CostPruningMode mode,
		ExactCategoricalSolver.BoundaryMergeCounters counters) {
		return ExactCategoricalSolver.mergeBoundaryWithPruningForTest(
			leaves, boundary, LIMITS, mode, counters);
	}

	private static List<ExactCategoricalSolver.BoundaryMessage> leaves(
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors) {
		return ExactCategoricalSolver.boundaryLeaves(variables, factors, LIMITS);
	}

	private static ExactCategoricalSolver.Factor factor(
		ExactCategoricalSolver.Variable variable, double... values) {
		return ExactCategoricalSolver.Factor.dense(List.of(variable), values);
	}

	private static ExactCategoricalSolver.BoundaryMergeCounters counters() {
		return new ExactCategoricalSolver.BoundaryMergeCounters();
	}

	private static ExactCategoricalSolver.BoundaryMessage sparseNumericMessage(
		List<ExactCategoricalSolver.Variable> variables,
		ExactCategoricalSolver.Variable variable, int[] sparseCells,
		double[] values, double[] lowers) throws Exception {
		var constructor = Arrays.stream(ExactCategoricalSolver.BoundaryMessage.class
			.getDeclaredConstructors()).filter(candidate -> candidate.getParameterCount() == 17)
			.findFirst().orElseThrow();
		constructor.setAccessible(true);
		return (ExactCategoricalSolver.BoundaryMessage)constructor.newInstance(
			variables, new int[] {variable.domainSize()}, List.of(variable), new int[] {0},
			values, null, lowers, List.of(), null, null, 0L, (long)variable.domainSize(),
			null, null, 0d, null, sparseCells);
	}

	private static ExactCategoricalSolver.BoundaryMessage lowerFiniteInfinityMessage(
		List<ExactCategoricalSolver.Variable> variables,
		ExactCategoricalSolver.Variable variable) throws Exception {
		var constructor = ExactCategoricalSolver.BoundaryMessage.class.getDeclaredConstructor(
			List.class, int[].class, List.class, int[].class, double[].class, double[].class,
			double[].class, List.class, int[].class, int[].class, long.class, long.class);
		constructor.setAccessible(true);
		int[] domains = variables.stream()
			.mapToInt(ExactCategoricalSolver.Variable::domainSize).toArray();
		return constructor.newInstance(variables, domains, List.of(variable), new int[] {0},
			new double[] {INF, 10d}, null, new double[] {7d, 10d}, List.of(), null, null, 0L, 2L);
	}

	private static void assertSuffixReduction(
		ExactCategoricalSolver.BoundaryMergeCounters prefix,
		ExactCategoricalSolver.BoundaryMergeCounters suffix) {
		Assert.assertTrue("suffix bounds must skip at least one numeric factor read",
			suffix.childEvaluations() < prefix.childEvaluations());
		Assert.assertTrue(suffix.suffixCostCuts() > 0);
		Assert.assertTrue(suffix.certifiedCostBuckets() > 0);
		Assert.assertTrue(suffix.boundCellsExamined() >= 0);
		Assert.assertTrue(suffix.boundPreparationNanos() >= 0);
	}

	private static void assertRunParity(GlobalRun expected, GlobalRun actual) {
		Assert.assertEquals(Double.doubleToRawLongBits(expected.result().objective()),
			Double.doubleToRawLongBits(actual.result().objective()));
		Assert.assertEquals(expected.result().assignmentInVariableOrder(),
			actual.result().assignmentInVariableOrder());
		Assert.assertEquals(expected.steps().size(), actual.steps().size());
		for(int index = 0; index < expected.steps().size(); index++) {
			var left = expected.steps().get(index);
			var right = actual.steps().get(index);
			Assert.assertEquals(left.variable(), right.variable());
			Assert.assertArrayEquals(left.scope(), right.scope());
			Assert.assertArrayEquals(left.choices(), right.choices());
			assertRaw(left.high(), right.high());
			assertRaw(left.low(), right.low());
		}
	}

	private static void assertMessageParity(ExactCategoricalSolver.BoundaryMessage expected,
		ExactCategoricalSolver.BoundaryMessage actual,
		List<ExactCategoricalSolver.Variable> variables) {
		Assert.assertEquals(expected.scope(), actual.scope());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.minimum()),
			Double.doubleToRawLongBits(actual.minimum()));
		Assert.assertEquals(Double.doubleToRawLongBits(expected.lowerBound()),
			Double.doubleToRawLongBits(actual.lowerBound()));
		for(ExactCategoricalSolver.Variable variable : expected.scope()) {
			assertRaw(expected.minMarginals(variable), actual.minMarginals(variable));
			assertRaw(expected.lowerMinMarginals(variable), actual.lowerMinMarginals(variable));
		}
		int cells = Math.toIntExact(expected.cells());
		int[] local = new int[expected.scope().size()];
		for(int cell = 0; cell < cells; cell++) {
			decode(cell, expected.scope(), local);
			int[] expectedAssignment = new int[variables.size()];
			Arrays.fill(expectedAssignment, -1);
			for(int axis = 0; axis < local.length; axis++)
				expectedAssignment[variables.indexOf(expected.scope().get(axis))] = local[axis];
			int[] actualAssignment = expectedAssignment.clone();
			Assert.assertEquals(Double.doubleToRawLongBits(
				expected.valueForAssignment(expectedAssignment, variables)),
				Double.doubleToRawLongBits(actual.valueForAssignment(actualAssignment, variables)));
			expected.decodeInto(expectedAssignment, variables);
			actual.decodeInto(actualAssignment, variables);
			Assert.assertArrayEquals(expectedAssignment, actualAssignment);
		}
	}

	private static void decode(int cell, List<ExactCategoricalSolver.Variable> scope, int[] values) {
		for(int axis = scope.size() - 1; axis >= 0; axis--) {
			values[axis] = cell % scope.get(axis).domainSize();
			cell /= scope.get(axis).domainSize();
		}
	}

	private static double[] fill(int size, double value) {
		double[] result = new double[size];
		Arrays.fill(result, value);
		return result;
	}

	private static void assertRaw(double[] expected, double[] actual) {
		Assert.assertEquals(expected.length, actual.length);
		for(int cell = 0; cell < expected.length; cell++)
			Assert.assertEquals("cell=" + cell, Double.doubleToRawLongBits(expected[cell]),
				Double.doubleToRawLongBits(actual[cell]));
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private record GlobalRun(ExactCategoricalSolver.Result result,
		ExactCategoricalSolver.BoundaryMergeCounters counters,
		List<ExactCategoricalSolver.EliminationSnapshot> steps) { }
}
