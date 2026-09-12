/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class IncrementalBoundaryMessageTest {
	private static final ExactCategoricalSolver.Limits GENEROUS =
		new ExactCategoricalSolver.Limits(1_000_000L, 4_000_000L);

	@Test
	public void mergeComputesBoundaryMinimaAndRecursivelyDecodesExactEndpoint() {
		var a = variable("merge-a", 2);
		var b = variable("merge-b", 2);
		var c = variable("merge-c", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c);
		var left = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(a, b), 4d, 0d, 1d, 5d), GENEROUS);
		var right = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(b, c), 0d, 4d, 3d, 1d), GENEROUS);

		var boundary = ExactCategoricalSolver.mergeBoundary(
			left, right, List.of(b), GENEROUS, 8L);
		Assert.assertEquals(List.of(b), boundary.scope());
		Assert.assertEquals(2L, boundary.cells());
		Assert.assertEquals(8L, boundary.retainedCells());
		Assert.assertEquals(8L, boundary.assignments());
		Assert.assertEquals(1d, boundary.minimum(), 0d);
		Assert.assertEquals(1d, boundary.minMarginal(b, 0), 0d);
		Assert.assertEquals(1d, boundary.minMarginal(b, 1), 0d);
		Assert.assertTrue(boundary.lowerBound() <= boundary.minimum());

		int[] selected = {-1, 1, -1};
		boundary.decodeInto(selected, variables);
		Assert.assertArrayEquals(new int[] {0, 1, 1}, selected);

		var constant = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(), 2d), GENEROUS);
		var root = ExactCategoricalSolver.mergeBoundary(
			boundary, constant, List.of(), GENEROUS, 2L);
		Assert.assertEquals(List.of(), root.scope());
		Assert.assertEquals(3d, root.minimum(), 0d);
		Assert.assertTrue(root.lowerBound() <= 3d);
		int[] optimum = {-1, -1, -1};
		root.decodeInto(optimum, variables);
		Assert.assertArrayEquals(new int[] {1, 0, 0}, optimum);
	}

	@Test
	public void disconnectedSingletonsAndConstantReachExactEmptyBoundary() {
		var x = variable("disconnected-x", 2);
		var y = variable("disconnected-y", 3);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, y);
		var xMessage = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(x), 5d, 1d), GENEROUS);
		var yMessage = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(y), 2d, 3d, 0d), GENEROUS);
		var root = ExactCategoricalSolver.mergeBoundary(
			xMessage, yMessage, List.of(), GENEROUS, 6L);

		Assert.assertEquals(1d, root.minimum(), 0d);
		Assert.assertTrue(root.lowerBound() <= 1d);
		int[] assignment = {-1, -1};
		root.decodeInto(assignment, variables);
		Assert.assertArrayEquals(new int[] {1, 2}, assignment);
	}

	@Test
	public void leavesFreezeLazyFactorsAndRejectedMergeIsAtomic() {
		var a = variable("lazy-a", 2);
		var b = variable("lazy-b", 2);
		var c = variable("lazy-c", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c);
		AtomicInteger leftEvaluations = new AtomicInteger();
		AtomicInteger rightEvaluations = new AtomicInteger();
		var left = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.lazy(List.of(a, b), values -> {
				leftEvaluations.incrementAndGet();
				return values[0] + values[1];
			}), GENEROUS);
		var right = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.lazy(List.of(b, c), values -> {
				rightEvaluations.incrementAndGet();
				return values[0] + values[1];
			}), GENEROUS);
		Assert.assertEquals(4, leftEvaluations.get());
		Assert.assertEquals(4, rightEvaluations.get());

		IllegalArgumentException rejected = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.mergeBoundary(
				left, right, List.of(b), GENEROUS, 7L));
		Assert.assertTrue(rejected.getMessage().startsWith(
			"INCREMENTAL_MESSAGE_RESOURCE|operation=merge|kind=assignments"));
		Assert.assertEquals(4, leftEvaluations.get());
		Assert.assertEquals(4, rightEvaluations.get());

		var accepted = ExactCategoricalSolver.mergeBoundary(
			left, right, List.of(b), GENEROUS, 8L);
		Assert.assertEquals(0d, accepted.minimum(), 0d);
		Assert.assertEquals(4, leftEvaluations.get());
		Assert.assertEquals(4, rightEvaluations.get());
	}

	@Test
	public void leafResourcePreflightRunsBeforeLazyEvaluation() {
		var a = variable("preflight-a", 2);
		var b = variable("preflight-b", 2);
		AtomicInteger evaluations = new AtomicInteger();
		var factor = ExactCategoricalSolver.Factor.lazy(List.of(a, b), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});

		IllegalArgumentException rejected = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.boundaryLeaf(List.of(a, b), factor,
				new ExactCategoricalSolver.Limits(3L, 100L)));
		Assert.assertTrue(rejected.getMessage().startsWith(
			"INCREMENTAL_MESSAGE_RESOURCE|operation=leaves"));
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void infeasibleBoundaryCannotBeDecoded() {
		var a = variable("infeasible-a", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(a);
		var leaf = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(a),
				0d, Double.POSITIVE_INFINITY), GENEROUS);
		int[] assignment = {1};
		IllegalArgumentException rejected = Assert.assertThrows(IllegalArgumentException.class,
			() -> leaf.decodeInto(assignment, variables));
		Assert.assertEquals("INCREMENTAL_MESSAGE_BOUNDARY_INFEASIBLE", rejected.getMessage());
	}

	@Test
	public void mergeRetainsPrecisionResidueAndCanonicalTieChoice() {
		var x = variable("precision-x", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x);
		var large = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(x), 1.0e16, 1.0e16), GENEROUS);
		var middle = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(x), 1d, 2d), GENEROUS);
		var last = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(x), 1d, 0d), GENEROUS);

		var retained = ExactCategoricalSolver.mergeBoundary(
			large, middle, List.of(x), GENEROUS, 2L);
		var root = ExactCategoricalSolver.mergeBoundary(
			retained, last, List.of(), GENEROUS, 2L);

		Assert.assertEquals(1.0e16 + 2d, root.minimum(), 0d);
		int[] assignment = {-1};
		root.decodeInto(assignment, variables);
		Assert.assertArrayEquals(new int[] {0}, assignment);
	}

	@Test
	public void cachedMinimaPreserveResidueInfinityAndSingletonProjection() {
		var singleton = variable("cached-singleton", 1);
		var x = variable("cached-x", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(singleton, x);
		List<ExactCategoricalSolver.BoundaryMessage> leaves =
			ExactCategoricalSolver.boundaryLeaves(variables, List.of(
				ExactCategoricalSolver.Factor.dense(List.of(singleton, x),
					1.0e16, Double.POSITIVE_INFINITY),
				ExactCategoricalSolver.Factor.dense(List.of(singleton, x), 1d, 0d),
				ExactCategoricalSolver.Factor.dense(List.of(singleton, x), 1d, 0d)),
				GENEROUS);
		var merged = ExactCategoricalSolver.mergeBoundary(
			leaves, List.of(singleton, x), GENEROUS, 2L);
		var projected = ExactCategoricalSolver.projectSingletons(merged);

		double exactMinimum = 1.0e16 + 2d;
		Assert.assertEquals(exactMinimum, merged.minimum(), 0d);
		Assert.assertEquals(exactMinimum, projected.minimum(), 0d);
		Assert.assertEquals(merged.lowerBound(), projected.lowerBound(), 0d);
		Assert.assertTrue(projected.lowerBound() <= exactMinimum);
		Assert.assertArrayEquals(new double[] {exactMinimum, Double.POSITIVE_INFINITY},
			projected.minMarginals(x), 0d);
		// Repeated access observes the same immutable summaries without table recomputation.
		Assert.assertEquals(exactMinimum, projected.minimum(), 0d);
		Assert.assertEquals(merged.lowerBound(), projected.lowerBound(), 0d);
	}

	@Test
	public void batchLeavesPreserveFactorOrdinalsAndDuplicateOccurrences() {
		var a = variable("batch-a", 2);
		var b = variable("batch-b", 3);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b);
		AtomicInteger evaluations = new AtomicInteger();
		var repeated = ExactCategoricalSolver.Factor.lazy(List.of(b), values -> {
			evaluations.incrementAndGet();
			return values[0] + 1d;
		});
		var trailing = ExactCategoricalSolver.Factor.lazy(List.of(a), values -> {
			evaluations.incrementAndGet();
			return 2d - values[0];
		});

		List<ExactCategoricalSolver.BoundaryMessage> leaves =
			ExactCategoricalSolver.boundaryLeaves(
				variables, List.of(repeated, repeated, trailing), GENEROUS);

		Assert.assertEquals(3, leaves.size());
		Assert.assertEquals(List.of(b), leaves.get(0).scope());
		Assert.assertEquals(List.of(b), leaves.get(1).scope());
		Assert.assertEquals(List.of(a), leaves.get(2).scope());
		Assert.assertEquals(8, evaluations.get());
		var doubled = ExactCategoricalSolver.mergeBoundary(
			leaves.get(0), leaves.get(1), List.of(b), GENEROUS, 3L);
		Assert.assertEquals(2d, doubled.minimum(), 0d);
		Assert.assertEquals(8, evaluations.get());
	}

	@Test
	public void batchCollectiveResourcePreflightRunsBeforeEveryLazyEvaluator() {
		var a = variable("batch-limit-a", 2);
		var b = variable("batch-limit-b", 2);
		AtomicInteger evaluations = new AtomicInteger();
		var first = ExactCategoricalSolver.Factor.lazy(List.of(a, b), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});
		var second = ExactCategoricalSolver.Factor.lazy(List.of(a, b), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});

		IllegalArgumentException rejected = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.boundaryLeaves(List.of(a, b), List.of(first, second),
				new ExactCategoricalSolver.Limits(4L, 7L)));
		Assert.assertTrue(rejected.getMessage().startsWith(
			"INCREMENTAL_MESSAGE_RESOURCE|operation=leaves|kind=cells"));
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void multiwayMergePreservesExternalBoundaryAndNestedBacktrace() {
		var a = variable("multi-a", 2);
		var b = variable("multi-b", 2);
		var c = variable("multi-c", 2);
		var d = variable("multi-d", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c, d);
		List<ExactCategoricalSolver.BoundaryMessage> leaves =
			ExactCategoricalSolver.boundaryLeaves(variables, List.of(
				ExactCategoricalSolver.Factor.dense(List.of(a, b), 5d, 1d, 0d, 4d),
				ExactCategoricalSolver.Factor.dense(List.of(b, c), 2d, 0d, 3d, 1d),
				ExactCategoricalSolver.Factor.dense(List.of(b, d), 4d, 0d, 2d, 1d)),
				GENEROUS);

		IllegalArgumentException capped = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.mergeBoundary(
				leaves, List.of(b), GENEROUS, 15L));
		Assert.assertTrue(capped.getMessage().startsWith(
			"INCREMENTAL_MESSAGE_RESOURCE|operation=merge|kind=assignments"));

		var boundary = ExactCategoricalSolver.mergeBoundary(
			leaves, List.of(b), GENEROUS, 16L);
		Assert.assertEquals(16L, boundary.assignments());
		Assert.assertEquals(2L, boundary.cells());
		Assert.assertArrayEquals(new double[] {0d, 3d}, boundary.minMarginals(b), 0d);
		int[] conditional = {-1, 1, -1, -1};
		boundary.decodeInto(conditional, variables);
		Assert.assertArrayEquals(new int[] {0, 1, 1, 1}, conditional);

		var preference = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(b), 10d, 0d), GENEROUS);
		var root = ExactCategoricalSolver.mergeBoundary(
			List.of(boundary, preference), List.of(), GENEROUS, 2L);
		Assert.assertEquals(3d, root.minimum(), 0d);
		int[] optimum = {-1, -1, -1, -1};
		root.decodeInto(optimum, variables);
		Assert.assertArrayEquals(new int[] {0, 1, 1, 1}, optimum);
	}

	@Test
	public void multiwayKernelPreservesCanonicalTieAcrossInterleavedAxes() {
		var a = variable("interleaved-a", 2);
		var b = variable("interleaved-b", 2);
		var c = variable("interleaved-c", 2);
		var d = variable("interleaved-d", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c, d);
		List<ExactCategoricalSolver.BoundaryMessage> leaves =
			ExactCategoricalSolver.boundaryLeaves(variables, List.of(
				ExactCategoricalSolver.Factor.dense(List.of(a, c), 5d, 0d, 0d, 5d),
				ExactCategoricalSolver.Factor.dense(List.of(b), 0d, 0d),
				ExactCategoricalSolver.Factor.dense(List.of(d), 0d, 0d)), GENEROUS);

		// Reversed output order exercises output axes interleaved with both internal axes.
		var boundary = ExactCategoricalSolver.mergeBoundary(
			leaves, List.of(d, b), GENEROUS, 16L);
		int[] assignment = {-1, 1, -1, 1};
		boundary.decodeInto(assignment, variables);
		Assert.assertArrayEquals(new int[] {0, 1, 1, 1}, assignment);
		Assert.assertEquals(0d, boundary.minimum(), 0d);
		Assert.assertEquals(0d, boundary.lowerBound(), 0d);
	}

	@Test
	public void multiwayKernelMatchesCanonicalFullUnionEnumeration() {
		var x = variable("random-x", 2);
		var y = variable("random-y", 3);
		var z = variable("random-z", 2);
		var w = variable("random-w", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, y, z, w);
		int[] domains = {2, 3, 2, 2};
		int[][] scopes = {{0, 1}, {1, 2, 3}, {0, 3}};
		Random random = new Random(0x5eedL);
		for(int trial = 0; trial < 40; trial++) {
			double[][] tables = {
				randomCosts(random, 6), randomCosts(random, 12), randomCosts(random, 4)};
			List<ExactCategoricalSolver.BoundaryMessage> leaves =
				ExactCategoricalSolver.boundaryLeaves(variables, List.of(
					ExactCategoricalSolver.Factor.dense(List.of(x, y), tables[0]),
					ExactCategoricalSolver.Factor.dense(List.of(y, z, w), tables[1]),
					ExactCategoricalSolver.Factor.dense(List.of(x, w), tables[2])), GENEROUS);
			var boundary = ExactCategoricalSolver.mergeBoundary(
				leaves, List.of(y), GENEROUS, 24L);

			double[] expected = {
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
			int[][] winners = new int[3][];
			int[] candidate = new int[4];
			for(int unionCell = 0; unionCell < 24; unionCell++) {
				decodeReference(unionCell, domains, candidate);
				double cost = 0d;
				for(int factor = 0; factor < scopes.length; factor++)
					cost += tables[factor][encodeReference(scopes[factor], domains, candidate)];
				int output = candidate[1];
				if(cost < expected[output]) {
					expected[output] = cost;
					winners[output] = candidate.clone();
				}
			}

			Assert.assertArrayEquals(expected, boundary.minMarginals(y), 0d);
			double expectedMinimum = Math.min(expected[0], Math.min(expected[1], expected[2]));
			Assert.assertEquals(expectedMinimum, boundary.minimum(), 0d);
			Assert.assertEquals(expectedMinimum, boundary.lowerBound(), 0d);
			for(int output = 0; output < 3; output++) {
				int[] decoded = {-1, output, -1, -1};
				boundary.decodeInto(decoded, variables);
				Assert.assertArrayEquals(winners[output], decoded);
			}
		}
	}

	@Test
	public void multiwayKernelHandlesEmptyUnionAndLeafNullLowValues() {
		List<ExactCategoricalSolver.Variable> variables = List.of();
		List<ExactCategoricalSolver.BoundaryMessage> leaves =
			ExactCategoricalSolver.boundaryLeaves(variables, List.of(
				ExactCategoricalSolver.Factor.dense(List.of(), 2d),
				ExactCategoricalSolver.Factor.dense(List.of(), 3d),
				ExactCategoricalSolver.Factor.dense(List.of(), 4d)), GENEROUS);
		var root = ExactCategoricalSolver.mergeBoundary(
			leaves, List.of(), GENEROUS, 1L);
		Assert.assertEquals(9d, root.minimum(), 0d);
		Assert.assertEquals(9d, root.lowerBound(), 0d);
		int[] assignment = {};
		root.decodeInto(assignment, variables);
		Assert.assertArrayEquals(new int[] {}, assignment);
	}

	@Test
	public void singletonProjectionAliasesNumericStateAndRestoresRemovedAxes() {
		var firstSingleton = variable("singleton-first", 1);
		var a = variable("singleton-a", 2);
		var secondSingleton = variable("singleton-second", 1);
		var b = variable("singleton-b", 3);
		List<ExactCategoricalSolver.Variable> variables =
			List.of(firstSingleton, a, secondSingleton, b);
		var leaf = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(
				List.of(firstSingleton, a, secondSingleton, b), 6d, 5d, 4d, 3d, 2d, 1d),
			GENEROUS);

		var projected = ExactCategoricalSolver.projectSingletons(leaf);
		Assert.assertEquals(List.of(a, b), projected.scope());
		Assert.assertEquals(leaf.cells(), projected.cells());
		Assert.assertEquals(leaf.minimum(), projected.minimum(), 0d);
		Assert.assertEquals(leaf.lowerBound(), projected.lowerBound(), 0d);
		Assert.assertArrayEquals(new double[] {4d, 1d}, projected.minMarginals(a), 0d);
		Assert.assertEquals(0L, projected.retainedCells());
		Assert.assertEquals(0L, projected.assignments());

		int[] assignment = {-1, 1, -1, 2};
		projected.decodeInto(assignment, variables);
		Assert.assertArrayEquals(new int[] {0, 1, 0, 2}, assignment);
		Assert.assertSame(projected, ExactCategoricalSolver.projectSingletons(projected));
		Assert.assertThrows(NullPointerException.class,
			() -> ExactCategoricalSolver.projectSingletons(null));
	}

	@Test
	public void singletonProjectionParticipatesInNestedExactBacktrace() {
		var singleton = variable("nested-singleton", 1);
		var value = variable("nested-value", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(singleton, value);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(singleton, value), 10d, 1d),
			ExactCategoricalSolver.Factor.dense(List.of(singleton, value), 0d, 3d),
			ExactCategoricalSolver.Factor.dense(List.of(value), 0d, 7d));
		List<ExactCategoricalSolver.BoundaryMessage> leaves =
			ExactCategoricalSolver.boundaryLeaves(variables, factors, GENEROUS);
		var joined = ExactCategoricalSolver.mergeBoundary(
			leaves.subList(0, 2), List.of(singleton, value), GENEROUS, 2L);
		var projected = ExactCategoricalSolver.projectSingletons(joined);
		var root = ExactCategoricalSolver.mergeBoundary(
			List.of(projected, leaves.get(2)), List.of(), GENEROUS, 2L);

		ExactCategoricalSolver.Result baseline =
			ExactCategoricalSolver.solve(variables, factors, GENEROUS);
		Assert.assertEquals(baseline.objective(), root.minimum(), 0d);
		int[] assignment = {-1, -1};
		root.decodeInto(assignment, variables);
		Assert.assertEquals(baseline.assignmentInVariableOrder(),
			List.of(assignment[0], assignment[1]));
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private static double[] randomCosts(Random random, int cells) {
		double[] values = new double[cells];
		for(int cell = 0; cell < cells; cell++)
			values[cell] = random.nextInt(6);
		return values;
	}

	private static void decodeReference(int cell, int[] domains, int[] assignment) {
		for(int variable = domains.length - 1; variable >= 0; variable--) {
			assignment[variable] = cell % domains[variable];
			cell /= domains[variable];
		}
	}

	private static int encodeReference(int[] scope, int[] domains, int[] assignment) {
		int cell = 0;
		for(int variable : scope)
			cell = cell * domains[variable] + assignment[variable];
		return cell;
	}
}
