/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class ExactPartialHardFactorTest {
	private static final ExactCategoricalSolver.Limits GENEROUS =
		new ExactCategoricalSolver.Limits(10_000, 100_000);

	private static final class PrefixEvaluator
		implements ExactCategoricalSolver.PartialHardCostFunction {
		private final AtomicInteger leaves;

		private PrefixEvaluator(AtomicInteger leaves) {
			this.leaves = leaves;
		}

		@Override
		public double cost(int[] values) {
			leaves.incrementAndGet();
			if(values[0] == 0)
				return Double.POSITIVE_INFINITY;
			if(values[1] == 0)
				return 0d;
			return 7 + values[2];
		}

		@Override
		public ExactCategoricalSolver.PartialTruth partialTruth(int[] values) {
			if(values[0] == 0)
				return ExactCategoricalSolver.PartialTruth.ALL_FORBIDDEN;
			if(values[0] == 1 && values[1] == 0)
				return ExactCategoricalSolver.PartialTruth.ALL_ZERO;
			return ExactCategoricalSolver.PartialTruth.UNKNOWN;
		}
	}

	@Test
	public void provenPrefixesBulkFillWithoutChangingDenseTableBits() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		var c = variable("c", 3);
		AtomicInteger leaves = new AtomicInteger();
		var frozen = ExactCategoricalSolver.freezeValidatedFactor(
			ExactCategoricalSolver.Factor.lazy(List.of(a, b, c), new PrefixEvaluator(leaves)));
		var generic = ExactCategoricalSolver.freezeValidatedFactor(
			ExactCategoricalSolver.Factor.lazy(List.of(a, b, c), values -> {
				if(values[0] == 0)
					return Double.POSITIVE_INFINITY;
				if(values[1] == 0)
					return 0d;
				return 7 + values[2];
			}));
		double[] expected = {
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
			0d, 0d, 0d, 7d, 8d, 9d
		};
		for(int cell = 0; cell < expected.length; cell++) {
			Assert.assertEquals(Double.doubleToRawLongBits(expected[cell]),
				Double.doubleToRawLongBits(generic.denseCostAt(cell)));
			Assert.assertEquals(Double.doubleToRawLongBits(generic.denseCostAt(cell)),
				Double.doubleToRawLongBits(frozen.denseCostAt(cell)));
		}
		Assert.assertEquals(3, leaves.get());
	}

	@Test
	public void unknownPrefixesRetainOriginalLeafOrder() {
		var a = variable("a", 2);
		var b = variable("b", 3);
		List<String> visits = new ArrayList<>();
		ExactCategoricalSolver.PartialHardCostFunction evaluator =
			new ExactCategoricalSolver.PartialHardCostFunction() {
				@Override public double cost(int[] values) {
					visits.add(Arrays.toString(values));
					return values[0] * 3 + values[1];
				}
				@Override public ExactCategoricalSolver.PartialTruth partialTruth(int[] values) {
					return ExactCategoricalSolver.PartialTruth.UNKNOWN;
				}
			};
		var frozen = ExactCategoricalSolver.freezeValidatedFactor(
			ExactCategoricalSolver.Factor.lazy(List.of(a, b), evaluator));
		for(int cell = 0; cell < 6; cell++) {
			Assert.assertEquals(Arrays.toString(new int[] {cell / 3, cell % 3}), visits.get(cell));
			Assert.assertEquals(cell, frozen.denseCostAt(cell), 0d);
		}
	}

	@Test
	public void legalExpensiveAssignmentAndCanonicalTieArePreserved() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		AtomicInteger partialLeaves = new AtomicInteger();
		ExactCategoricalSolver.PartialHardCostFunction partial =
			new ExactCategoricalSolver.PartialHardCostFunction() {
				@Override public double cost(int[] values) {
					partialLeaves.incrementAndGet();
					return values[0] == 0 ? Double.POSITIVE_INFINITY : 0d;
				}
				@Override public ExactCategoricalSolver.PartialTruth partialTruth(int[] values) {
					if(values[0] == 0)
						return ExactCategoricalSolver.PartialTruth.ALL_FORBIDDEN;
					if(values[0] == 1)
						return ExactCategoricalSolver.PartialTruth.ALL_ZERO;
					return ExactCategoricalSolver.PartialTruth.UNKNOWN;
				}
			};
		var costs = ExactCategoricalSolver.Factor.dense(List.of(a), 0d, 5d);
		var proof = ExactCategoricalSolver.solve(List.of(a, b), List.of(
			ExactCategoricalSolver.Factor.lazy(List.of(a, b), partial), costs), GENEROUS);
		var generic = ExactCategoricalSolver.solve(List.of(a, b), List.of(
			ExactCategoricalSolver.Factor.lazy(List.of(a, b), values ->
				values[0] == 0 ? Double.POSITIVE_INFINITY : 0d), costs), GENEROUS);
		Assert.assertEquals(generic.assignmentInVariableOrder(), proof.assignmentInVariableOrder());
		Assert.assertEquals(List.of(1, 0), proof.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(generic.objective()),
			Double.doubleToRawLongBits(proof.objective()));
		Assert.assertEquals(0, partialLeaves.get());
	}

	@Test
	public void resourceAndDenseValidationPreflightPrecedePartialCallbacks() {
		var a = variable("a", 2);
		var b = variable("b", 3);
		AtomicInteger partialCalls = new AtomicInteger();
		ExactCategoricalSolver.PartialHardCostFunction evaluator = proofAllZero(partialCalls);
		var lazy = ExactCategoricalSolver.Factor.lazy(List.of(a, b), evaluator);
		Assert.assertThrows(IllegalArgumentException.class, () -> ExactCategoricalSolver.freezeInputs(
			List.of(a, b), List.of(lazy), new ExactCategoricalSolver.Limits(5, 6)));
		Assert.assertEquals(0, partialCalls.get());
		var invalid = ExactCategoricalSolver.Factor.dense(List.of(a), 0d, Double.NaN);
		Assert.assertThrows(IllegalArgumentException.class, () -> ExactCategoricalSolver.freezeInputs(
			List.of(a, b), List.of(lazy, invalid), GENEROUS));
		Assert.assertEquals(0, partialCalls.get());
	}

	@Test
	public void constantScopeCanBeProvenWithoutLeafEvaluation() {
		AtomicInteger partialCalls = new AtomicInteger();
		AtomicInteger leaves = new AtomicInteger();
		ExactCategoricalSolver.PartialHardCostFunction evaluator =
			new ExactCategoricalSolver.PartialHardCostFunction() {
				@Override public double cost(int[] values) { leaves.incrementAndGet(); return 0d; }
				@Override public ExactCategoricalSolver.PartialTruth partialTruth(int[] values) {
					partialCalls.incrementAndGet();
					return ExactCategoricalSolver.PartialTruth.ALL_ZERO;
				}
			};
		var frozen = ExactCategoricalSolver.freezeValidatedFactor(
			ExactCategoricalSolver.Factor.lazy(List.of(), evaluator));
		Assert.assertEquals(0d, frozen.denseCostAt(0), 0d);
		Assert.assertEquals(1, partialCalls.get());
		Assert.assertEquals(0, leaves.get());
	}

	@Test
	public void observationRepresentativeMappingPreservesProofCapability() {
		var a = variable("source-a", 8);
		var b = variable("source-b", 8);
		AtomicInteger leaves = new AtomicInteger();
		ExactCategoricalSolver.PartialHardCostFunction canonicalEvaluator =
			new ExactCategoricalSolver.PartialHardCostFunction() {
				@Override public double cost(int[] values) {
					leaves.incrementAndGet();
					return values[0] % 2 == 0 ? Double.POSITIVE_INFINITY : 0d;
				}
				@Override public ExactCategoricalSolver.PartialTruth partialTruth(int[] values) {
					if(values[0] < 0)
						return ExactCategoricalSolver.PartialTruth.UNKNOWN;
					return values[0] % 2 == 0 ? ExactCategoricalSolver.PartialTruth.ALL_FORBIDDEN
						: ExactCategoricalSolver.PartialTruth.ALL_ZERO;
				}
			};
		var canonical = ExactCategoricalSolver.Factor.lazy(List.of(a, b), canonicalEvaluator);
		List<?>[] keys = new List<?>[] {
			List.of(0, 1, 0, 1, 0, 1, 0, 1),
			List.of(0, 1, 0, 1, 0, 1, 0, 1)
		};
		var decomposition = ExactHardFactorObservationDecomposition.create("partial", canonical, keys);
		Assert.assertNotNull(decomposition);
		var truth = decomposition.solverFactors().get(decomposition.solverFactors().size() - 1);
		Assert.assertTrue(truth.supportsPartialTruth());
		var frozen = ExactCategoricalSolver.freezeValidatedFactor(truth);
		for(int cell = 0; cell < 4; cell++) {
			int firstCategory = cell / 2;
			double expected = firstCategory == 0 ? Double.POSITIVE_INFINITY : 0d;
			Assert.assertEquals(Double.doubleToRawLongBits(expected),
				Double.doubleToRawLongBits(frozen.denseCostAt(cell)));
		}
		Assert.assertEquals(0, leaves.get());
	}

	@Test
	public void supportedDomainMappingPreservesPartialSourceValues() {
		var a = variable("mapped-a", 3);
		var b = variable("mapped-b", 2);
		var c = variable("mapped-c", 2);
		AtomicInteger leaves = new AtomicInteger();
		ExactCategoricalSolver.PartialHardCostFunction hard =
			new ExactCategoricalSolver.PartialHardCostFunction() {
				@Override public double cost(int[] values) {
					leaves.incrementAndGet();
					return values[0] == 1 ? Double.POSITIVE_INFINITY : 0d;
				}
				@Override public ExactCategoricalSolver.PartialTruth partialTruth(int[] values) {
					return values[0] == 1 ? ExactCategoricalSolver.PartialTruth.ALL_FORBIDDEN
						: ExactCategoricalSolver.PartialTruth.UNKNOWN;
				}
			};
		var result = ExactPhysicalReducedSolver.solve(3, List.of(a, b, c), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a), Double.POSITIVE_INFINITY, 0d, 0d),
			ExactCategoricalSolver.Factor.lazy(List.of(a, b, c), hard)), GENEROUS);
		var generic = ExactPhysicalReducedSolver.solve(3, List.of(a, b, c), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a), Double.POSITIVE_INFINITY, 0d, 0d),
			ExactCategoricalSolver.Factor.lazy(List.of(a, b, c), values ->
				values[0] == 1 ? Double.POSITIVE_INFINITY : 0d)), GENEROUS);
		Assert.assertEquals(generic.assignmentInVariableOrder(), result.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(generic.objective()),
			Double.doubleToRawLongBits(result.objective()));
		Assert.assertEquals(List.of(2, 0, 0), result.assignmentInVariableOrder());
		Assert.assertEquals(4, leaves.get());
	}

	@Test
	public void sharedSourceProfilingUsesTheSameProofAwareMaterialization() {
		var a = variable("profile-a", 2);
		var b = variable("profile-b", 3);
		AtomicInteger leaves = new AtomicInteger();
		ExactCategoricalSolver.PartialHardCostFunction evaluator =
			new ExactCategoricalSolver.PartialHardCostFunction() {
				@Override public double cost(int[] values) {
					leaves.incrementAndGet();
					return values[0] == 0 ? Double.POSITIVE_INFINITY : 0d;
				}
				@Override public ExactCategoricalSolver.PartialTruth partialTruth(int[] values) {
					if(values[0] < 0)
						return ExactCategoricalSolver.PartialTruth.UNKNOWN;
					return values[0] == 0 ? ExactCategoricalSolver.PartialTruth.ALL_FORBIDDEN
						: ExactCategoricalSolver.PartialTruth.ALL_ZERO;
				}
			};
		int[] classes = ExactPhysicalSharedSourceEncoding.profileAxisClassesForTest(
			ExactCategoricalSolver.Factor.lazy(List.of(a, b), evaluator), 2, 3, GENEROUS);
		int[] genericClasses = ExactPhysicalSharedSourceEncoding.profileAxisClassesForTest(
			ExactCategoricalSolver.Factor.lazy(List.of(a, b), values ->
				values[0] == 0 ? Double.POSITIVE_INFINITY : 0d), 2, 3, GENEROUS);
		Assert.assertArrayEquals(genericClasses, classes);
		Assert.assertNotEquals(classes[0], classes[1]);
		Assert.assertEquals(0, leaves.get());
	}

	private static ExactCategoricalSolver.PartialHardCostFunction proofAllZero(
		AtomicInteger partialCalls) {
		return new ExactCategoricalSolver.PartialHardCostFunction() {
			@Override public double cost(int[] values) { return 0d; }
			@Override public ExactCategoricalSolver.PartialTruth partialTruth(int[] values) {
				partialCalls.incrementAndGet();
				return ExactCategoricalSolver.PartialTruth.ALL_ZERO;
			}
		};
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}
}
