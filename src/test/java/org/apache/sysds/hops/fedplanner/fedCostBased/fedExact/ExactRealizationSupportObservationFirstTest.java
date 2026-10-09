/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/** Compare observation-first preparation to the original explicit legal-cell path. */
public class ExactRealizationSupportObservationFirstTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(1_000_000L, 10_000_000L);

	@Test
	public void encodedRelationDoesNotAllocateLegalPairIds() {
		int[] handles = new int[512];
		List<Set<Integer>> requirements = new ArrayList<>();
		for(int value = 0; value < handles.length; value++) {
			handles[value] = value % 16;
			requirements.add(Set.of(value % 16));
		}
		var scope = scope(requirements.size(), handles.length, false);
		Map<ExactCategoricalSolver.Factor,ExactHardFactorObservationDecomposition.Result> encodings =
			new IdentityHashMap<>();
		try(var allocations = Mockito.mockStatic(PlannerResourceGuard.class, Mockito.CALLS_REAL_METHODS)) {
			var actual = ExactPhysicalModel.realizationSupportFactor(
				"allocation", scope, requirements, handles, encodings);
			Assert.assertNotNull(encodings.get(actual));
			allocations.verify(() -> PlannerResourceGuard.allocateInts(
				Mockito.anyInt(), Mockito.eq("exact-realization-support-cells")), Mockito.never());
			Assert.assertFalse(actual.isFiniteSupport());
		}
		int[] legacy = ExactPhysicalModel.sparseRealizationSupportCells(requirements, handles, false);
		Assert.assertEquals("logical legal pair count is unchanged", 16_384, legacy.length);
	}

	@Test
	public void randomRelationsPreserveCanonicalAndEncodedTruthAndSolverAssignments() {
		Random random = new Random(2541987L);
		int encoded = 0;
		int sparse = 0;
		for(int sample = 0; sample < 150; sample++) {
			int consumers = 8 + random.nextInt(25);
			int[] handles = new int[8 + random.nextInt(25)];
			int distinct = sample % 3 == 0 ? handles.length : 2 + random.nextInt(5);
			for(int value = 0; value < handles.length; value++)
				handles[value] = value % distinct;
			List<Set<Integer>> requirements = new ArrayList<>();
			for(int value = 0; value < consumers; value++) {
				int kind = random.nextInt(10);
				requirements.add(kind == 0 ? null : kind < 5 ? Set.of() : kind == 5
					? Set.of(0, distinct + 3) : Set.of(random.nextInt(distinct + 2)));
			}
			// Guarantee a feasible row, including duplicate/missing-handle and wildcard cases elsewhere.
			requirements.set(0, Set.of(0));
			var scope = scope(consumers, handles.length, false);
			var old = legacy("random", scope, requirements, handles, false);
			Map<ExactCategoricalSolver.Factor,ExactHardFactorObservationDecomposition.Result> encodings =
				new IdentityHashMap<>();
			var actual = ExactPhysicalModel.realizationSupportFactor(
				"random", scope, requirements, handles, encodings);
			assertFactorTruth(old.factor(), actual);
			var decomposition = encodings.get(actual);
			if(old.encoded() == null) {
				Assert.assertNull(decomposition);
				Assert.assertEquals(old.factor().isFiniteSupport(), actual.isFiniteSupport());
				if(actual.isFiniteSupport())
					sparse++;
			}
			else {
				encoded++;
				Assert.assertNotNull(decomposition);
				Assert.assertFalse(actual.isFiniteSupport());
				assertEncoding(old.encoded(), decomposition);
			}
			if(sample < 35)
				assertSolvers(scope, old, new Prepared(actual, decomposition), random);
		}
		Assert.assertTrue("random fixture must exercise compression", encoded > 0);
		Assert.assertTrue("random fixture must exercise exact sparse fallback", sparse > 0);
	}

	@Test
	public void selfScopesAndDenseFallbackKeepTheirOriginalRepresentation() {
		Random random = new Random(9426L);
		for(int sample = 0; sample < 80; sample++) {
			int[] handles = new int[24];
			List<Set<Integer>> requirements = new ArrayList<>();
			for(int value = 0; value < handles.length; value++) {
				handles[value] = value % 5;
				int kind = random.nextInt(12);
				requirements.add(kind == 0 ? null : kind < 10 ? Set.of() : Set.of(kind - 10));
			}
			var scope = scope(handles.length, handles.length, true);
			var old = legacy("self", scope, requirements, handles, true);
			Map<ExactCategoricalSolver.Factor,ExactHardFactorObservationDecomposition.Result> encodings =
				new IdentityHashMap<>();
			var actual = ExactPhysicalModel.realizationSupportFactor(
				"self", scope, requirements, handles, encodings);
			Assert.assertTrue(encodings.isEmpty());
			Assert.assertEquals(old.factor().isFiniteSupport(), actual.isFiniteSupport());
			assertFactorTruth(old.factor(), actual);
		}
		var scope = scope(3, 4, false);
		Map<ExactCategoricalSolver.Factor,ExactHardFactorObservationDecomposition.Result> encodings =
			new IdentityHashMap<>();
		var actual = ExactPhysicalModel.realizationSupportFactor("dense", scope,
			Arrays.asList(null, null, Set.of(1)), new int[] {0,1,2,3}, encodings);
		Assert.assertTrue(encodings.isEmpty());
		Assert.assertFalse(actual.isFiniteSupport());
	}

	@Test
	public void indexedCanonicalPreservesFiniteFactorRangeChecksAndNoPartialTruth() {
		var scope = scope(32, 32, false);
		List<Set<Integer>> requirements = new ArrayList<>();
		int[] handles = new int[32];
		for(int value = 0; value < handles.length; value++) {
			handles[value] = value % 8;
			requirements.add(value == 0 ? null : Set.of(value % 8));
		}
		Map<ExactCategoricalSolver.Factor,ExactHardFactorObservationDecomposition.Result> encodings =
			new IdentityHashMap<>();
		var factor = ExactPhysicalModel.realizationSupportFactor(
			"range", scope, requirements, handles, encodings);
		Assert.assertNotNull(encodings.get(factor));
		Assert.assertFalse(factor.supportsPartialTruth());
		for(int[] values : new int[][] {{-1,0}, {32,0}, {0,-1}, {0,32}}) {
			var failure = Assert.assertThrows(IllegalArgumentException.class, () -> factor.cost(values));
			Assert.assertEquals("EXACT_VE_FACTOR_ASSIGNMENT_VALUE_INVALID", failure.getMessage());
		}
	}

	private static Prepared legacy(String key, List<ExactCategoricalSolver.Variable> scope,
		List<Set<Integer>> requirements, int[] handles, boolean self) {
		int[] cells = ExactPhysicalModel.sparseRealizationSupportCells(requirements, handles, self);
		var factor = cells == null ? ExactCategoricalSolver.Factor.lazy(scope, values -> {
			Set<Integer> required = requirements.get(values[0]);
			if(required == null)
				return 0d;
			if(required.isEmpty())
				return Double.POSITIVE_INFINITY;
			return required.contains(handles[values[self ? 0 : 1]]) ? 0d : Double.POSITIVE_INFINITY;
		}) : ExactCategoricalSolver.Factor.finiteSupport(scope, cells);
		List<Object> observations = new ArrayList<>();
		for(Set<Integer> required : requirements)
			observations.add(required == null ? null : required.size() == 1
				? required.iterator().next() : required);
		return new Prepared(factor, self ? null : ExactHardFactorObservationDecomposition.create(
			key, factor, new List<?>[] {observations, ExactHardFactorObservationDecomposition.keys(handles)}));
	}

	private static void assertEncoding(ExactHardFactorObservationDecomposition.Result expected,
		ExactHardFactorObservationDecomposition.Result actual) {
		Assert.assertEquals(expected.descriptor(), actual.descriptor());
		Assert.assertEquals(expected.canonicalCells(), actual.canonicalCells());
		Assert.assertEquals(expected.encodedCells(), actual.encodedCells());
		Assert.assertEquals(expected.auxiliaryVariables(), actual.auxiliaryVariables());
		for(int position = 0; position < expected.observations().size(); position++)
			Assert.assertArrayEquals(expected.observations().get(position), actual.observations().get(position));
		for(int index = 0; index < expected.solverFactors().size(); index++)
			assertFactorTruth(expected.solverFactors().get(index), actual.solverFactors().get(index));
	}

	private static void assertFactorTruth(ExactCategoricalSolver.Factor expected,
		ExactCategoricalSolver.Factor actual) {
		Assert.assertEquals(expected.scope(), actual.scope());
		Assert.assertEquals(expected.supportsPartialTruth(), actual.supportsPartialTruth());
		int cells = expected.scope().stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		int[] values = new int[expected.scope().size()];
		for(int cell = 0; cell < cells; cell++) {
			int rest = cell;
			for(int position = values.length - 1; position >= 0; position--) {
				values[position] = rest % expected.scope().get(position).domainSize();
				rest /= expected.scope().get(position).domainSize();
			}
			Assert.assertEquals("cell=" + cell, Double.doubleToRawLongBits(expected.cost(values)),
				Double.doubleToRawLongBits(actual.cost(values)));
		}
	}

	private static void assertSolvers(List<ExactCategoricalSolver.Variable> scope,
		Prepared old, Prepared actual, Random random) {
		List<ExactCategoricalSolver.Variable> variables = new ArrayList<>(scope);
		List<ExactCategoricalSolver.Factor> oldFactors = new ArrayList<>();
		List<ExactCategoricalSolver.Factor> newFactors = new ArrayList<>();
		if(old.encoded() == null) {
			oldFactors.add(old.factor());
			newFactors.add(actual.factor());
		}
		else {
			variables.addAll(old.encoded().auxiliaryVariables());
			oldFactors.addAll(old.encoded().solverFactors());
			newFactors.addAll(actual.encoded().solverFactors());
		}
		for(var variable : scope) {
			double[] costs = new double[variable.domainSize()];
			for(int value = 0; value < costs.length; value++)
				costs[value] = random.nextInt(5); // Includes ties, preserving original ordinals.
			var cost = ExactCategoricalSolver.Factor.dense(List.of(variable), costs);
			oldFactors.add(cost);
			newFactors.add(cost);
		}
		var expected = ExactCategoricalSolver.solve(variables, oldFactors, LIMITS);
		var found = ExactCategoricalSolver.solve(variables, newFactors, LIMITS);
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(found.objective()));
		Assert.assertEquals(expected.assignmentInVariableOrder(), found.assignmentInVariableOrder());
		var oldMessage = ExactCategoricalSolver.mergeBoundary(
			ExactCategoricalSolver.boundaryLeaves(variables, oldFactors, LIMITS), List.of(), LIMITS);
		var newMessage = ExactCategoricalSolver.mergeBoundary(
			ExactCategoricalSolver.boundaryLeaves(variables, newFactors, LIMITS), List.of(), LIMITS);
		Assert.assertEquals(Double.doubleToRawLongBits(oldMessage.minimum()),
			Double.doubleToRawLongBits(newMessage.minimum()));
		int[] oldAssignment = new int[variables.size()];
		int[] newAssignment = new int[variables.size()];
		oldMessage.decodeInto(oldAssignment, variables);
		newMessage.decodeInto(newAssignment, variables);
		Assert.assertArrayEquals(oldAssignment, newAssignment);
	}

	private static List<ExactCategoricalSolver.Variable> scope(int consumers, int sources, boolean self) {
		var consumer = new ExactCategoricalSolver.Variable("consumer", consumers);
		return self ? List.of(consumer) : List.of(consumer, new ExactCategoricalSolver.Variable("source", sources));
	}
	private record Prepared(ExactCategoricalSolver.Factor factor,
		ExactHardFactorObservationDecomposition.Result encoded) { }
}
