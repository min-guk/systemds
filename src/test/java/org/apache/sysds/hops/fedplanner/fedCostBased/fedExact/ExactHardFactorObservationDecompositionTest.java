/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

public class ExactHardFactorObservationDecompositionTest {
	@Test
	public void encodedRelationIsExactForEveryCanonicalCell() {
		var left = new ExactCategoricalSolver.Variable("left", 20);
		var right = new ExactCategoricalSolver.Variable("right", 20);
		var canonical = ExactCategoricalSolver.Factor.lazy(List.of(left, right), values ->
			(values[0] & 1) == (values[1] & 1) ? 0.0 : Double.POSITIVE_INFINITY);
		List<?>[] observations = new List<?>[] {
			java.util.stream.IntStream.range(0, 20).map(value -> value & 1).boxed().toList(),
			java.util.stream.IntStream.range(0, 20).map(value -> value & 1).boxed().toList()
		};
		var encoded = ExactHardFactorObservationDecomposition.create(
			"parity", canonical, observations);
		Assert.assertNotNull(encoded);
		Assert.assertEquals(400L, encoded.canonicalCells());
		Assert.assertEquals(84L, encoded.encodedCells());
		encoded.observations().get(0)[0] = 17;
		Assert.assertEquals("observation maps must be immutable snapshots", 0,
			encoded.observations().get(0)[0]);
		for(int leftValue = 0; leftValue < 20; leftValue++)
			for(int rightValue = 0; rightValue < 20; rightValue++) {
				Map<ExactCategoricalSolver.Variable,Integer> assignment = new IdentityHashMap<>();
				assignment.put(left, leftValue);
				assignment.put(right, rightValue);
				assignment.put(encoded.auxiliaryVariables().get(0),
					encoded.observations().get(0)[leftValue]);
				assignment.put(encoded.auxiliaryVariables().get(1),
					encoded.observations().get(1)[rightValue]);
				Assert.assertEquals(Double.doubleToRawLongBits(canonical.cost(
					new int[] {leftValue, rightValue})), Double.doubleToRawLongBits(
					encodedCost(encoded, assignment)));
			}
	}

	@Test
	public void inconsistentObservationAssignmentIsRejected() {
		var left = new ExactCategoricalSolver.Variable("left", 20);
		var right = new ExactCategoricalSolver.Variable("right", 20);
		var canonical = ExactCategoricalSolver.Factor.lazy(List.of(left, right), ignored -> 0.0);
		List<?>[] observations = new List<?>[] {
			java.util.stream.IntStream.range(0, 20).map(value -> value & 1).boxed().toList(),
			java.util.stream.IntStream.range(0, 20).map(value -> value & 1).boxed().toList()
		};
		var encoded = ExactHardFactorObservationDecomposition.create(
			"inconsistent", canonical, observations);
		Map<ExactCategoricalSolver.Variable,Integer> assignment = new IdentityHashMap<>();
		assignment.put(left, 0);
		assignment.put(right, 0);
		assignment.put(encoded.auxiliaryVariables().get(0), 1);
		assignment.put(encoded.auxiliaryVariables().get(1), 0);
		Assert.assertTrue(Double.isInfinite(encodedCost(encoded, assignment)));
	}

	@Test
	public void exhaustiveOracleDetectsUnderDiscriminatingObservation() {
		var left = new ExactCategoricalSolver.Variable("left", 20);
		var right = new ExactCategoricalSolver.Variable("right", 20);
		var canonical = ExactCategoricalSolver.Factor.lazy(List.of(left, right), values ->
			values[0] == values[1] ? 0.0 : Double.POSITIVE_INFINITY);
		List<?> constant = java.util.Collections.nCopies(20, 0);
		var encoded = ExactHardFactorObservationDecomposition.create("under-discriminating",
			canonical, new List<?>[] {constant, constant});
		Assert.assertNotNull(encoded);
		boolean mismatch = false;
		for(int leftValue = 0; leftValue < 20 && !mismatch; leftValue++)
			for(int rightValue = 0; rightValue < 20; rightValue++)
				if(Double.doubleToRawLongBits(canonical.cost(new int[] {leftValue, rightValue}))
					!= Double.doubleToRawLongBits(encoded.solverFactors().get(2).cost(new int[] {0, 0}))) {
					mismatch = true;
					break;
				}
		Assert.assertTrue("exhaustive relation oracle must reject an insufficient observation", mismatch);
	}

	private static double encodedCost(ExactHardFactorObservationDecomposition.Result encoded,
		Map<ExactCategoricalSolver.Variable,Integer> assignment) {
		double result = 0.0;
		for(var factor : encoded.solverFactors()) {
			int[] values = factor.scope().stream().mapToInt(assignment::get).toArray();
			result += factor.cost(values);
		}
		return result;
	}
}
