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
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalReducedSolverTest {
	private static final ExactCategoricalSolver.Limits GENEROUS =
		new ExactCategoricalSolver.Limits(1_000_000, 5_000_000);

	@Test
	public void randomUnaryBinaryTernaryModelsMatchUnreducedExactSolve() {
		Random random = new Random(709_2026L);
		for(int trial = 0; trial < 80; trial++) {
			List<ExactCategoricalSolver.Variable> variables = List.of(
				variable("a", 2 + random.nextInt(3)), variable("b", 2 + random.nextInt(3)),
				variable("c", 2 + random.nextInt(3)));
			List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
			factors.add(randomFactor(random, List.of(variables.get(0))));
			factors.add(randomFactor(random, List.of(variables.get(0), variables.get(1))));
			factors.add(randomFactor(random, List.of(variables.get(0), variables.get(1),
				variables.get(2))));
			// Preserve at least one feasible assignment independently of random infinities.
			factors.add(ExactCategoricalSolver.Factor.dense(List.of(), 0d));
			try {
				ExactCategoricalSolver.Result expected = ExactCategoricalSolver.solve(
					variables, factors, GENEROUS);
				ExactCategoricalSolver.Result actual = ExactPhysicalReducedSolver.solve(
					variables.size(), variables, factors, GENEROUS);
				Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
					Double.doubleToRawLongBits(actual.objective()));
				Assert.assertEquals(Double.doubleToRawLongBits(actual.objective()),
					Double.doubleToRawLongBits(ExactCategoricalSolver.evaluate(
						variables, factors, GENEROUS, actual.assignmentInVariableOrder())));
			}
			catch(IllegalArgumentException failure) {
				Assert.assertTrue(failure.getMessage(),
					failure.getMessage().startsWith("EXACT_VE_NO_FEASIBLE_ASSIGNMENT"));
				try {
					ExactPhysicalReducedSolver.solve(variables.size(), variables, factors, GENEROUS);
					Assert.fail("reduced model accepted an infeasible original model");
				}
				catch(IllegalArgumentException reducedFailure) {
					Assert.assertTrue(reducedFailure.getMessage(), reducedFailure.getMessage()
						.startsWith("EXACT_VE_NO_FEASIBLE_ASSIGNMENT"));
				}
			}
		}
	}

	@Test
	public void completeObservationQuotientBreaksDenseCliqueWithoutChangingObjective() {
		List<ExactCategoricalSolver.Variable> variables = variables(4, 8);
		List<ExactCategoricalSolver.Factor> factors = completeBinaryClique(variables, false);
		ExactCategoricalSolver.Limits small = new ExactCategoricalSolver.Limits(100, 2_000);
		try {
			ExactCategoricalSolver.solve(variables, factors, small);
			Assert.fail("unreduced clique unexpectedly fit");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(),
				expected.getMessage().startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED"));
		}
		ExactCategoricalSolver.Result result = ExactPhysicalReducedSolver.solve(
			variables.size(), variables, factors, small);
		Assert.assertEquals(Double.doubleToRawLongBits(0d),
			Double.doubleToRawLongBits(result.objective()));
		Assert.assertEquals(List.of(0, 0, 0, 0), result.assignmentInVariableOrder());
	}

	@Test
	public void preparedReductionExposesAdmissibleWorkAndReusesFrozenLazyFactors() {
		List<ExactCategoricalSolver.Variable> variables = variables(4, 8);
		AtomicInteger evaluations = new AtomicInteger();
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		for(int left = 0; left < variables.size(); left++)
			for(int right = left + 1; right < variables.size(); right++)
				factors.add(ExactCategoricalSolver.Factor.lazy(
					List.of(variables.get(left), variables.get(right)), values -> {
						evaluations.incrementAndGet();
						return 0d;
					}));
		ExactCategoricalSolver.Limits small = new ExactCategoricalSolver.Limits(100, 2_000);

		ExactPhysicalReducedSolver.Prepared prepared = ExactPhysicalReducedSolver.prepare(
			variables.size(), variables, factors, small);
		int afterPreparation = evaluations.get();

		Assert.assertFalse(prepared.infeasible());
		Assert.assertTrue(prepared.statistics().maximumFactorCells() <= 100);
		Assert.assertEquals(List.of(0, 0, 0, 0),
			ExactPhysicalReducedSolver.solve(prepared).assignmentInVariableOrder());
		Assert.assertEquals("prepared solve reevaluated original lazy factors",
			afterPreparation, evaluations.get());
	}

	@Test
	public void compactPreparationSubstitutesMixedNoncontiguousSingletons() {
		var originalForced = variable("original-forced", 3);
		var originalFree = variable("original-free", 3);
		var originalForcedSecond = variable("original-forced-second", 2);
		var auxiliaryFree = variable("auxiliary-free", 2);
		var auxiliaryForced = variable("auxiliary-forced", 3);
		List<ExactCategoricalSolver.Variable> variables = List.of(originalForced, originalFree,
			originalForcedSecond, auxiliaryFree, auxiliaryForced);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(originalForced),
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 2d),
			ExactCategoricalSolver.Factor.dense(List.of(originalFree), 4d, 1d, 3d),
			ExactCategoricalSolver.Factor.dense(List.of(originalForcedSecond),
				Double.POSITIVE_INFINITY, 5d),
			ExactCategoricalSolver.Factor.dense(List.of(auxiliaryFree), 2d, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(auxiliaryForced),
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 7d),
			// Ternary collapse exercises retained scope order after substituting both ends.
			ExactCategoricalSolver.Factor.dense(
				List.of(originalForced, originalFree, auxiliaryForced),
				0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d,
				0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d,
				0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d));

		ExactPhysicalReducedSolver.Prepared prepared = ExactPhysicalReducedSolver.prepareCompacted(
			3, variables, factors, GENEROUS);
		ExactCategoricalSolver.Result result = ExactPhysicalReducedSolver.solve(prepared);

		Assert.assertEquals(2, prepared.compiledVariableCount());
		Assert.assertEquals(List.of(2, 1, 1, 1, 2), result.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(15d),
			Double.doubleToRawLongBits(result.objective()));
		Assert.assertEquals(Double.doubleToRawLongBits(result.objective()),
			Double.doubleToRawLongBits(ExactCategoricalSolver.evaluate(
				variables, factors, GENEROUS, result.assignmentInVariableOrder())));
	}

	@Test
	public void compactModelExposesStableMixedSourceMappingWithoutExactCompile() {
		var originalForced = variable("original-forced", 2);
		var originalFree = variable("original-free", 3);
		var auxiliaryFree = variable("auxiliary-free", 2);
		var auxiliaryForced = variable("auxiliary-forced", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(originalForced, originalFree,
			auxiliaryFree, auxiliaryForced);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			forcingFactor(originalForced, 1, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(originalFree), 2d, 0d, 1d),
			ExactCategoricalSolver.Factor.dense(List.of(auxiliaryFree), 1d, 0d),
			forcingFactor(auxiliaryForced, 0, 0d),
			ExactCategoricalSolver.Factor.dense(
				List.of(originalForced, originalFree, auxiliaryFree, auxiliaryForced),
				new double[24]));

		ExactPhysicalReducedSolver.CompactModel compact = ExactPhysicalReducedSolver.compactModel(
			2, variables, factors, GENEROUS);

		Assert.assertEquals(2, compact.originalDecisionCount());
		Assert.assertEquals(2, compact.variables().size());
		Assert.assertSame(originalFree, compact.sourceVariables().get(0));
		Assert.assertSame(auxiliaryFree, compact.sourceVariables().get(1));
		Assert.assertEquals(List.of(1, 2, 1, 0), compact.expandAssignment(List.of(2, 1)));
		try {
			compact.variables().add(variable("forbidden", 2));
			Assert.fail("compact variable list was mutable");
		}
		catch(UnsupportedOperationException expected) {
			// Expected immutable view.
		}
	}

	@Test
	public void compactModelDoesNotCompileExactEliminationPlan() {
		List<ExactCategoricalSolver.Variable> variables = variables(4, 3);
		List<ExactCategoricalSolver.Factor> factors = completeBinaryClique(variables, true, 3);
		ExactCategoricalSolver.Limits inputFitsButEliminationDoesNot =
			new ExactCategoricalSolver.Limits(10, 1_000);

		ExactPhysicalReducedSolver.CompactModel compact = ExactPhysicalReducedSolver.compactModel(
			4, variables, factors, inputFitsButEliminationDoesNot);
		Assert.assertEquals(4, compact.variables().size());
		try {
			ExactPhysicalReducedSolver.prepareCompacted(4, variables, factors,
				inputFitsButEliminationDoesNot);
			Assert.fail("exact compilation unexpectedly fit the factor limit");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(),
				expected.getMessage().startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED"));
		}
	}

	@Test
	public void compactModelExpansionRejectsInvalidAssignments() {
		var forced = variable("forced", 2);
		var free = variable("free", 2);
		ExactPhysicalReducedSolver.CompactModel compact = ExactPhysicalReducedSolver.compactModel(
			1, List.of(forced, free), List.of(forcingFactor(forced, 1, 0d)), GENEROUS);

		assertCompactExpansionFailure(compact, List.of(),
			"EXACT_PHYSICAL_COMPACT_ASSIGNMENT_SIZE_MISMATCH");
		assertCompactExpansionFailure(compact, List.of(-1),
			"EXACT_PHYSICAL_COMPACT_ASSIGNMENT_VALUE_INVALID");
		assertCompactExpansionFailure(compact, List.of(2),
			"EXACT_PHYSICAL_COMPACT_ASSIGNMENT_VALUE_INVALID");
	}

	@Test
	public void compactModelChecksRawCapBeforeFreezingLazyFactor() {
		var a = variable("a", 3);
		var b = variable("b", 3);
		AtomicInteger evaluations = new AtomicInteger();
		var lazy = ExactCategoricalSolver.Factor.lazy(List.of(a, b), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});
		try {
			ExactPhysicalReducedSolver.compactModel(2, List.of(a, b), List.of(lazy),
				new ExactCategoricalSolver.Limits(8, 100));
			Assert.fail("oversized raw factor accepted by shared compact model");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(),
				expected.getMessage().startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED"));
		}
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void compactPreparationPreservesArityCollapseConstantsAndAllSingletonSolve() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		var c = variable("c", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(), 1.0e16),
			ExactCategoricalSolver.Factor.dense(List.of(a),
				Double.POSITIVE_INFINITY, 0.25d),
			ExactCategoricalSolver.Factor.dense(List.of(a, b),
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY, 0.5d),
			ExactCategoricalSolver.Factor.dense(List.of(a, b, c),
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY, 0.75d),
			ExactCategoricalSolver.Factor.dense(List.of(c),
				Double.POSITIVE_INFINITY, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(), 1d),
			ExactCategoricalSolver.Factor.dense(List.of(), 0.125d));

		ExactCategoricalSolver.Result expected = ExactPhysicalReducedSolver.solve(
			ExactPhysicalReducedSolver.prepare(3, variables, factors, GENEROUS));
		ExactPhysicalReducedSolver.Prepared compact = ExactPhysicalReducedSolver.prepareCompacted(
			3, variables, factors, GENEROUS);
		ExactCategoricalSolver.Result actual = ExactPhysicalReducedSolver.solve(compact);

		Assert.assertEquals(0, compact.compiledVariableCount());
		Assert.assertEquals(List.of(1, 1, 1), actual.assignmentInVariableOrder());
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
	}

	@Test
	public void compactPreparationReportsInfeasibility() {
		var a = variable("a", 2);
		var impossible = ExactCategoricalSolver.Factor.dense(List.of(a),
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
		ExactPhysicalReducedSolver.Prepared prepared = ExactPhysicalReducedSolver.prepareCompacted(
			1, List.of(a), List.of(impossible), GENEROUS);
		Assert.assertTrue(prepared.infeasible());
		try {
			ExactPhysicalReducedSolver.solve(prepared);
			Assert.fail("compacted infeasible model was solved");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals("EXACT_VE_NO_FEASIBLE_ASSIGNMENT", expected.getMessage());
		}
	}

	@Test
	public void compactPreparationRejectsRawLazyCapBeforeEvaluation() {
		var a = variable("a", 3);
		var b = variable("b", 3);
		AtomicInteger evaluations = new AtomicInteger();
		var lazy = ExactCategoricalSolver.Factor.lazy(List.of(a, b), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});
		try {
			ExactPhysicalReducedSolver.prepareCompacted(2, List.of(a, b), List.of(lazy),
				new ExactCategoricalSolver.Limits(8, 100));
			Assert.fail("oversized raw factor accepted by compact preparation");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(),
				expected.getMessage().startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED"));
		}
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void compactPreparationsFreezeOnceAndRefreshLazyValues() {
		var a = variable("a", 2);
		AtomicInteger generation = new AtomicInteger();
		AtomicInteger evaluations = new AtomicInteger();
		var lazy = ExactCategoricalSolver.Factor.lazy(List.of(a), values -> {
			evaluations.incrementAndGet();
			return values[0] == generation.get() ? 0d : 1d;
		});

		ExactPhysicalReducedSolver.Prepared first = ExactPhysicalReducedSolver.prepareCompacted(
			1, List.of(a), List.of(lazy), GENEROUS);
		Assert.assertEquals(2, evaluations.get());
		Assert.assertEquals(List.of(0),
			ExactPhysicalReducedSolver.solve(first).assignmentInVariableOrder());
		Assert.assertEquals("prepared compact solve refroze a lazy factor", 2, evaluations.get());

		generation.set(1);
		ExactPhysicalReducedSolver.Prepared second = ExactPhysicalReducedSolver.prepareCompacted(
			1, List.of(a), List.of(lazy), GENEROUS);
		Assert.assertEquals(4, evaluations.get());
		Assert.assertEquals(List.of(1),
			ExactPhysicalReducedSolver.solve(second).assignmentInVariableOrder());
		Assert.assertEquals(4, evaluations.get());
	}

	@Test
	public void randomForcedModelsMatchOriginalAndLegacyPreparedSolvesBitForBit() {
		Random random = new Random(809_2026L);
		for(int trial = 0; trial < 60; trial++) {
			var a = variable("a-" + trial, 3);
			var b = variable("b-" + trial, 3);
			var c = variable("c-" + trial, 2);
			var auxiliary = variable("aux-" + trial, 3);
			List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c, auxiliary);
			int forcedA = random.nextInt(3);
			int forcedC = random.nextInt(2);
			int forcedAuxiliary = random.nextInt(3);
			List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
			factors.add(forcingFactor(a, forcedA, random.nextInt(4) * 0.25d));
			factors.add(randomFiniteFactor(random, List.of(a, b)));
			factors.add(ExactCategoricalSolver.Factor.dense(List.of(b), 0d, 0.25d, 0.5d));
			factors.add(forcingFactor(c, forcedC, random.nextInt(4) * 0.25d));
			factors.add(randomFiniteFactor(random, List.of(b, c, auxiliary)));
			factors.add(forcingFactor(auxiliary, forcedAuxiliary,
				random.nextInt(4) * 0.25d));
			factors.add(ExactCategoricalSolver.Factor.dense(List.of(), 0.125d));

			ExactCategoricalSolver.Result original = ExactCategoricalSolver.solve(
				variables, factors, GENEROUS);
			ExactCategoricalSolver.Result legacy = ExactPhysicalReducedSolver.solve(
				ExactPhysicalReducedSolver.prepare(3, variables, factors, GENEROUS));
			ExactPhysicalReducedSolver.Prepared compactPrepared =
				ExactPhysicalReducedSolver.prepareCompacted(3, variables, factors, GENEROUS);
			ExactCategoricalSolver.Result compact = ExactPhysicalReducedSolver.solve(compactPrepared);

			Assert.assertEquals(1, compactPrepared.compiledVariableCount());
			Assert.assertEquals(original.assignmentInVariableOrder(), compact.assignmentInVariableOrder());
			Assert.assertEquals(legacy.assignmentInVariableOrder(), compact.assignmentInVariableOrder());
			Assert.assertEquals(Double.doubleToRawLongBits(original.objective()),
				Double.doubleToRawLongBits(compact.objective()));
			Assert.assertEquals(Double.doubleToRawLongBits(legacy.objective()),
				Double.doubleToRawLongBits(compact.objective()));
			Assert.assertEquals(Double.doubleToRawLongBits(compact.objective()),
				Double.doubleToRawLongBits(ExactCategoricalSolver.evaluate(
					variables, factors, GENEROUS, compact.assignmentInVariableOrder())));
		}
	}

	@Test
	public void glmLikeSingletonModelDropsExactWorkWithoutChangingObjective() {
		List<ExactCategoricalSolver.Variable> variables = variables(18, 2);
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		for(int variable = 0; variable < 16; variable++)
			factors.add(forcingFactor(variables.get(variable), variable % 2, variable * 0.125d));
		factors.add(ExactCategoricalSolver.Factor.dense(List.of(variables.get(16)), 1d, 0d));
		factors.add(ExactCategoricalSolver.Factor.dense(List.of(variables.get(17)), 0d, 2d));
		for(int variable = 0; variable < 16; variable++)
			factors.add(ExactCategoricalSolver.Factor.dense(
				List.of(variables.get(variable), variables.get(16), variables.get(17)),
				0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d));

		ExactPhysicalReducedSolver.Prepared legacy = ExactPhysicalReducedSolver.prepare(
			12, variables, factors, GENEROUS);
		ExactPhysicalReducedSolver.Prepared compact = ExactPhysicalReducedSolver.prepareCompacted(
			12, variables, factors, GENEROUS);
		ExactCategoricalSolver.Result expected = ExactPhysicalReducedSolver.solve(legacy);
		ExactCategoricalSolver.Result actual = ExactPhysicalReducedSolver.solve(compact);

		Assert.assertEquals(18, legacy.compiledVariableCount());
		Assert.assertEquals(2, compact.compiledVariableCount());
		Assert.assertTrue(compact.statistics().eliminationAssignments()
			< legacy.statistics().eliminationAssignments());
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
	}

	@Test
	public void distinctRawObservationsAreNeverMerged() {
		List<ExactCategoricalSolver.Variable> variables = variables(4, 8);
		List<ExactCategoricalSolver.Factor> factors = completeBinaryClique(variables, true);
		ExactCategoricalSolver.Limits small = new ExactCategoricalSolver.Limits(100, 2_000);
		try {
			ExactPhysicalReducedSolver.solveWithConstantObservationHashForTesting(
				variables.size(), variables, factors, small);
			Assert.fail("distinct observations were improperly merged");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(),
				expected.getMessage().startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED"));
		}
	}

	@Test
	public void tieCostIsPartOfTheObservation() {
		List<ExactCategoricalSolver.Variable> variables = variables(4, 8);
		List<ExactCategoricalSolver.Factor> factors = completeBinaryClique(variables, false);
		ExactCategoricalSolver.Limits small = new ExactCategoricalSolver.Limits(100, 2_000);
		try {
			ExactPhysicalReducedSolver.solve(variables.size(), variables, factors, small,
				(variable, value) -> value);
			Assert.fail("tie-distinct alternatives were improperly merged");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(),
				expected.getMessage().startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED"));
		}
	}

	@Test
	public void reducedSolvePreservesSpecifiedSecondaryTieChoice() {
		var a = variable("a", 3);
		var constant = ExactCategoricalSolver.Factor.dense(List.of(a), 0d, 0d, 0d);
		ExactCategoricalSolver.Result result = ExactPhysicalReducedSolver.solve(1,
			List.of(a), List.of(constant), GENEROUS,
			(variable, value) -> new long[] {5L, 0L, 3L}[value]);
		Assert.assertEquals(List.of(1), result.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(0d),
			Double.doubleToRawLongBits(result.objective()));
	}

	@Test
	public void unaryAndBinaryArcConsistencyCanProveEmptyDomain() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a), 0d, Double.POSITIVE_INFINITY),
			ExactCategoricalSolver.Factor.dense(List.of(a, b),
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0d, 0d));
		try {
			ExactPhysicalReducedSolver.solve(2, List.of(a, b), factors, GENEROUS);
			Assert.fail("AC-3 failed to prove an empty domain");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals("EXACT_VE_NO_FEASIBLE_ASSIGNMENT", expected.getMessage());
		}
	}

	@Test
	public void forcedUnaryValueIsPreservedInExpandedAssignment() {
		var a = variable("a", 4);
		var forced = ExactCategoricalSolver.Factor.dense(List.of(a),
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 7d,
			Double.POSITIVE_INFINITY);
		ExactCategoricalSolver.Result result = ExactPhysicalReducedSolver.solve(
			1, List.of(a), List.of(forced), GENEROUS);
		Assert.assertEquals(List.of(2), result.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(7d),
			Double.doubleToRawLongBits(result.objective()));
	}

	@Test
	public void oversizedLazyInputFailsBeforeEvaluation() {
		var a = variable("a", 3);
		var b = variable("b", 3);
		AtomicInteger evaluations = new AtomicInteger();
		var lazy = ExactCategoricalSolver.Factor.lazy(List.of(a, b), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});
		try {
			ExactPhysicalReducedSolver.solve(2, List.of(a, b), List.of(lazy),
				new ExactCategoricalSolver.Limits(8, 100));
			Assert.fail("oversized input factor accepted");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(),
				expected.getMessage().startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED"));
		}
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void totalInputBudgetFailsBeforeEvaluation() {
		var a = variable("a", 3);
		AtomicInteger evaluations = new AtomicInteger();
		var first = ExactCategoricalSolver.Factor.lazy(List.of(a), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});
		var second = ExactCategoricalSolver.Factor.lazy(List.of(a), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});
		try {
			ExactPhysicalReducedSolver.solve(1, List.of(a), List.of(first, second),
				new ExactCategoricalSolver.Limits(10, 5));
			Assert.fail("oversized total input accepted");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(),
				expected.getMessage().startsWith("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED"));
		}
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void invalidDenseInputFailsBeforeAnyLazyEvaluation() {
		var a = variable("a", 2);
		AtomicInteger evaluations = new AtomicInteger();
		var lazy = ExactCategoricalSolver.Factor.lazy(List.of(a), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});
		var invalid = ExactCategoricalSolver.Factor.dense(List.of(a), 0d, Double.NaN);
		try {
			ExactPhysicalReducedSolver.solve(1, List.of(a), List.of(lazy, invalid), GENEROUS);
			Assert.fail("invalid dense factor accepted");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(),
				expected.getMessage().startsWith("EXACT_VE_FACTOR_COST_INVALID"));
		}
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void lazyFactorsAreFrozenOncePerSolveAndRefreshedBetweenSolves() {
		var a = variable("a", 2);
		AtomicInteger generation = new AtomicInteger();
		AtomicInteger evaluations = new AtomicInteger();
		var lazy = ExactCategoricalSolver.Factor.lazy(List.of(a), values -> {
			evaluations.incrementAndGet();
			return values[0] == generation.get() ? 0d : 1d;
		});
		Assert.assertEquals(List.of(0), ExactPhysicalReducedSolver.solve(1, List.of(a),
			List.of(lazy), GENEROUS).assignmentInVariableOrder());
		Assert.assertEquals(2, evaluations.get());
		generation.set(1);
		Assert.assertEquals(List.of(1), ExactPhysicalReducedSolver.solve(1, List.of(a),
			List.of(lazy), GENEROUS).assignmentInVariableOrder());
		Assert.assertEquals(4, evaluations.get());
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private static List<ExactCategoricalSolver.Variable> variables(int count, int domain) {
		List<ExactCategoricalSolver.Variable> result = new ArrayList<>();
		for(int index = 0; index < count; index++)
			result.add(variable("v" + index, domain));
		return result;
	}

	private static List<ExactCategoricalSolver.Factor> completeBinaryClique(
		List<ExactCategoricalSolver.Variable> variables, boolean distinct) {
		return completeBinaryClique(variables, distinct, 8);
	}

	private static List<ExactCategoricalSolver.Factor> completeBinaryClique(
		List<ExactCategoricalSolver.Variable> variables, boolean distinct, int domain) {
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		for(int left = 0; left < variables.size(); left++)
			for(int right = left + 1; right < variables.size(); right++) {
				double[] values = new double[domain * domain];
				if(distinct)
					for(int row = 0; row < domain; row++)
						for(int column = 0; column < domain; column++)
							values[row * domain + column] = row + column / 16d;
				factors.add(ExactCategoricalSolver.Factor.dense(
					List.of(variables.get(left), variables.get(right)), values));
			}
		return factors;
	}

	private static void assertCompactExpansionFailure(
		ExactPhysicalReducedSolver.CompactModel compact, List<Integer> assignment,
		String expectedMessage) {
		try {
			compact.expandAssignment(assignment);
			Assert.fail("invalid compact assignment was expanded");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals(expectedMessage, expected.getMessage());
		}
	}

	private static ExactCategoricalSolver.Factor randomFactor(Random random,
		List<ExactCategoricalSolver.Variable> scope) {
		int cells = scope.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		double[] values = new double[cells];
		for(int cell = 0; cell < cells; cell++)
			values[cell] = random.nextInt(13) == 0 ? Double.POSITIVE_INFINITY
				: random.nextInt(4) * 0.25d;
		return ExactCategoricalSolver.Factor.dense(scope, values);
	}

	private static ExactCategoricalSolver.Factor forcingFactor(
		ExactCategoricalSolver.Variable variable, int forcedValue, double cost) {
		double[] values = new double[variable.domainSize()];
		java.util.Arrays.fill(values, Double.POSITIVE_INFINITY);
		values[forcedValue] = cost;
		return ExactCategoricalSolver.Factor.dense(List.of(variable), values);
	}

	private static ExactCategoricalSolver.Factor randomFiniteFactor(Random random,
		List<ExactCategoricalSolver.Variable> scope) {
		int cells = scope.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		double[] values = new double[cells];
		for(int cell = 0; cell < cells; cell++)
			values[cell] = random.nextInt(8) * 0.125d;
		return ExactCategoricalSolver.Factor.dense(scope, values);
	}
}
