/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

/** Structural preflight contracts for compressed exact factors. */
public class ExactCompressedFactorStructureTest {
	@Test
	public void residualOverflowRetainsErrorAndReportsBoundedOriginWithoutEvaluation() {
		var first = new ExactCategoricalSolver.Variable("long-origin-".repeat(1_000), 1_500);
		var second = new ExactCategoricalSolver.Variable("second", 1_500);
		var third = new ExactCategoricalSolver.Variable("third", 1_500);
		AtomicInteger evaluations = new AtomicInteger();
		var relation = ExactCategoricalSolver.Factor.lazy(List.of(first, second, third), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalReducedSolver.reducedModel(3, List.of(first, second, third),
				List.of(relation), new ExactCategoricalSolver.Limits(Long.MAX_VALUE, Long.MAX_VALUE)));
		Assert.assertEquals("EXACT_VE_FACTOR_CELL_OVERFLOW", failure.getMessage());
		Assert.assertEquals(0, evaluations.get());
		Assert.assertEquals(1, failure.getSuppressed().length);
		String context = failure.getSuppressed()[0].getMessage();
		Assert.assertTrue(context, context.contains("EXACT_REDUCTION_INPUT_OVERFLOW"));
		Assert.assertTrue(context, context.contains("factor=0"));
		Assert.assertTrue(context, context.contains("domains=[1500,1500,1500]"));
		Assert.assertTrue(context, context.contains("ExactCompressedFactorStructureTest"));
		Assert.assertTrue(context, context.contains("second"));
		Assert.assertTrue(context, context.length() < 4_096);
		Assert.assertFalse(context, context.contains(first.key()));
	}

	@Test
	public void reductionPreflightAcceptsHugeFunctionalRelationByStoredSupport() {
		var source = new ExactCategoricalSolver.Variable("source", 50_000);
		var target = new ExactCategoricalSolver.Variable("target", 50_000);
		int[] mapping = new int[source.domainSize()];
		Arrays.fill(mapping, -1);
		mapping[mapping.length - 1] = target.domainSize() - 1;
		var functional = ExactCategoricalSolver.Factor.functionalMap(source, target, mapping);
		var limits = new ExactCategoricalSolver.Limits(1, 1);

		ExactCategoricalSolver.validateReductionInputStructure(
			List.of(source, target), List.of(functional), limits);
		IllegalArgumentException strict = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.validateInputStructure(
				List.of(source, target), List.of(functional), limits));
		Assert.assertEquals("EXACT_VE_FACTOR_CELL_OVERFLOW", strict.getMessage());
	}

	@Test
	public void reductionPreflightDefersWideGenericRelationWithoutEvaluatingIt() {
		var first = new ExactCategoricalSolver.Variable("first", 2_000);
		var second = new ExactCategoricalSolver.Variable("second", 2_000);
		var third = new ExactCategoricalSolver.Variable("third", 2_000);
		AtomicInteger evaluations = new AtomicInteger();
		var allSupported = ExactCategoricalSolver.Factor.lazy(
			List.of(first, second, third), values -> {
				evaluations.incrementAndGet();
				return 0d;
			});
		var limits = new ExactCategoricalSolver.Limits(10, 10);

		ExactCategoricalSolver.validateReductionInputStructure(
			List.of(first, second, third), List.of(allSupported), limits);
		Assert.assertEquals(0, evaluations.get());
		IllegalArgumentException strict = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.validateInputStructure(
				List.of(first, second, third), List.of(allSupported), limits));
		Assert.assertEquals("EXACT_VE_FACTOR_CELL_OVERFLOW", strict.getMessage());
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void compressedAndOrdinaryStorageShareOneWholeModelBudget() {
		var source = new ExactCategoricalSolver.Variable("source", 50_000);
		var target = new ExactCategoricalSolver.Variable("target", 50_000);
		var ordinaryVariable = new ExactCategoricalSolver.Variable("ordinary", 3);
		int[] mapping = new int[source.domainSize()];
		Arrays.fill(mapping, -1);
		mapping[0] = 0;
		var functional = ExactCategoricalSolver.Factor.functionalMap(source, target, mapping);
		AtomicInteger evaluations = new AtomicInteger();
		var ordinary = ExactCategoricalSolver.Factor.lazy(List.of(ordinaryVariable), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});

		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.validateCompressedCostInputStructure(
				List.of(source, target, ordinaryVariable), List.of(functional), List.of(ordinary),
				new ExactCategoricalSolver.Limits(3, 3)));
		Assert.assertTrue(failure.getMessage(),
			failure.getMessage().startsWith("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED"));
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void compressedCostPreflightCountsSmallDeferredSolverFactorsWithoutEvaluation() {
		var source = new ExactCategoricalSolver.Variable("source", 6);
		var target = new ExactCategoricalSolver.Variable("target", 2);
		var ordinaryVariable = new ExactCategoricalSolver.Variable("ordinary", 8);
		AtomicInteger evaluations = new AtomicInteger();
		var solverOnly = ExactCategoricalSolver.Factor.lazy(
			List.of(source, target), values -> {
				evaluations.incrementAndGet();
				return 0d;
			});
		var ordinary = ExactCategoricalSolver.Factor.lazy(
			List.of(ordinaryVariable), values -> {
				evaluations.incrementAndGet();
				return 0d;
			});

		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.validateCompressedCostInputStructure(
				List.of(source, target, ordinaryVariable), List.of(solverOnly),
				List.of(ordinary), new ExactCategoricalSolver.Limits(100, 19)));
		Assert.assertEquals(0, evaluations.get());
		ExactCategoricalSolver.validateCompressedCostInputStructure(
			List.of(source, target, ordinaryVariable), List.of(solverOnly),
			List.of(ordinary), new ExactCategoricalSolver.Limits(100, 20));
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void reductionPreflightLeavesSmallDeferredFactorForUnaryShrink() {
		var source = new ExactCategoricalSolver.Variable("source", 4);
		var target = new ExactCategoricalSolver.Variable("target", 4);
		AtomicInteger evaluations = new AtomicInteger();
		var pin = ExactCategoricalSolver.Factor.dense(List.of(source),
			0d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
		var deferred = ExactCategoricalSolver.Factor.lazy(
			List.of(source, target), values -> {
				evaluations.incrementAndGet();
				return 0d;
			});

		ExactCategoricalSolver.validateReductionInputStructure(
			List.of(source, target), List.of(pin, deferred),
			new ExactCategoricalSolver.Limits(4, 4));
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void reductionPreflightLeavesRawFunctionalMapForUnaryShrink() {
		var source = new ExactCategoricalSolver.Variable("source", 4);
		var target = new ExactCategoricalSolver.Variable("target", 4);
		var pin = ExactCategoricalSolver.Factor.dense(List.of(source),
			0d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
		var functional = ExactCategoricalSolver.Factor.functionalMap(
			source, target, new int[] {0, 1, 2, 3});

		ExactCategoricalSolver.validateReductionInputStructure(
			List.of(source, target), List.of(pin, functional),
			new ExactCategoricalSolver.Limits(4, 4));
	}

	@Test
	public void malformedOrOversizedOrdinaryInputFailsBeforeAnyEvaluator() {
		var left = new ExactCategoricalSolver.Variable("left", 50_000);
		var right = new ExactCategoricalSolver.Variable("right", 50_000);
		AtomicInteger evaluations = new AtomicInteger();
		var deferred = ExactCategoricalSolver.Factor.lazy(List.of(left, right), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});
		var malformed = ExactCategoricalSolver.Factor.dense(List.of(
			new ExactCategoricalSolver.Variable("small", 2)), 0d);

		IllegalArgumentException malformedFailure = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.validateCompressedCostInputStructure(
				List.of(left, right, malformed.scope().get(0)), List.of(deferred), List.of(malformed),
				new ExactCategoricalSolver.Limits(10, 10)));
		Assert.assertEquals("EXACT_VE_DENSE_FACTOR_SIZE_MISMATCH", malformedFailure.getMessage());
		Assert.assertEquals(0, evaluations.get());

		IllegalArgumentException oversized = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.validateCompressedCostInputStructure(
				List.of(left, right), List.of(), List.of(deferred),
				new ExactCategoricalSolver.Limits(10, 10)));
		Assert.assertTrue(oversized.getMessage(),
			oversized.getMessage().startsWith("EXACT_VE_FACTOR_CELL_OVERFLOW|factor=0"));
		Assert.assertTrue(oversized.getMessage(), oversized.getMessage().contains("domains=[50000,50000]"));
		Assert.assertTrue(oversized.getMessage(), oversized.getMessage().contains("representation=LAZY"));
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void finiteAndConditionalRelationsChargeStoredValues() {
		var selector = new ExactCategoricalSolver.Variable("selector", 10);
		var value = new ExactCategoricalSolver.Variable("value", 10);
		var finite = ExactCategoricalSolver.Factor.finiteSupport(
			List.of(selector, value), 0, 99);
		int[][] allowed = new int[2][];
		allowed[1] = new int[] {1, 2, 3};
		var conditional = ExactCategoricalSolver.Factor.conditionalSupport(
			List.of(selector, value), 0, new int[] {0},
			List.of(new ExactCategoricalSolver.ConditionalRegion(0, allowed)));

		ExactCategoricalSolver.validateReductionInputStructure(
			List.of(selector, value), List.of(finite),
			new ExactCategoricalSolver.Limits(2, 2));
		ExactCategoricalSolver.validateReductionInputStructure(
			List.of(selector, value), List.of(conditional),
			new ExactCategoricalSolver.Limits(3, 3));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.validateReductionInputStructure(
				List.of(selector, value), List.of(finite),
				new ExactCategoricalSolver.Limits(1, 2)));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.validateReductionInputStructure(
				List.of(selector, value), List.of(conditional),
				new ExactCategoricalSolver.Limits(2, 3)));
	}

	@Test
	public void smallFunctionalAndDenseFactorsHaveIdenticalExactSolution() {
		var source = new ExactCategoricalSolver.Variable("source", 2);
		var target = new ExactCategoricalSolver.Variable("target", 3);
		var functional = ExactCategoricalSolver.Factor.functionalMap(
			source, target, new int[] {2, 0});
		var dense = ExactCategoricalSolver.Factor.dense(List.of(source, target),
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0d,
			0d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
		var limits = new ExactCategoricalSolver.Limits(100, 200);

		var compressedResult = ExactCategoricalSolver.solve(
			List.of(source, target), List.of(functional), limits);
		var denseResult = ExactCategoricalSolver.solve(
			List.of(source, target), List.of(dense), limits);
		Assert.assertEquals(denseResult.assignmentInVariableOrder(),
			compressedResult.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(denseResult.objective()),
			Double.doubleToRawLongBits(compressedResult.objective()));
	}
}
