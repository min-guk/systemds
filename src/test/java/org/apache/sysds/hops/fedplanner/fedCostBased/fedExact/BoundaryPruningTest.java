/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

public class BoundaryPruningTest {
	private static final ExactCategoricalSolver.Limits GENEROUS =
		new ExactCategoricalSolver.Limits(1_000_000L, 4_000_000L);

	@Test
	public void certifiedLocalCutMatchesFullEnumerationAndReducesChildReads() {
		var x = variable("certified-cut-x", 5);
		List<ExactCategoricalSolver.Variable> variables = List.of(x);
		double[][] tables = {
			{1d, 2d, 3d, 4d, 5d},
			{0d, 0d, 0d, 0d, 0d},
			{0d, 0d, 0d, 0d, 0d}
		};
		var counters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var root = ExactCategoricalSolver.mergeBoundary(
			leaves(variables, x, tables), List.of(), GENEROUS, 5L, counters);

		Reference reference = enumerate(tables);
		Assert.assertEquals(reference.minimum(), root.minimum(), 0d);
		int[] assignment = {-1};
		root.decodeInto(assignment, variables);
		Assert.assertEquals(reference.winner(), assignment[0]);
		Assert.assertEquals(7L, counters.childEvaluations());
		Assert.assertEquals(15L, counters.fullChildEvaluations());
		Assert.assertEquals(4L, counters.costCuts());
		Assert.assertEquals(0L, counters.infeasibleCuts());
		Assert.assertTrue(counters.childEvaluations() < 15L);
	}

	@Test
	public void infinitePrefixStopsSuffixEvenWhenCostCertificateIsUnsafe() {
		var x = variable("infinite-prefix-x", 4);
		List<ExactCategoricalSolver.Variable> variables = List.of(x);
		double unsafe = 0x1p53;
		double[][] tables = {
			{0d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
			{unsafe, 0d, 0d, 0d},
			{1d, 1d, 1d, 1d}
		};
		var counters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var root = ExactCategoricalSolver.mergeBoundary(
			leaves(variables, x, tables), List.of(), GENEROUS, 4L, counters);

		Assert.assertEquals(unsafe, root.minimum(), 0d);
		Assert.assertEquals(6L, counters.childEvaluations());
		Assert.assertEquals(3L, counters.infeasibleCuts());
		Assert.assertEquals(0L, counters.costCuts());
		Assert.assertTrue(counters.childEvaluations() < 12L);
	}

	@Test
	public void exactTieCutRetainsTheFirstCanonicalAssignment() {
		var x = variable("tie-cut-x", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x);
		double[][] tables = {{1d, 1d}, {0d, 0d}};
		var counters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var root = ExactCategoricalSolver.mergeBoundary(
			leaves(variables, x, tables), List.of(), GENEROUS, 2L, counters);

		int[] assignment = {-1};
		root.decodeInto(assignment, variables);
		Assert.assertArrayEquals(new int[] {0}, assignment);
		Assert.assertEquals(1d, root.minimum(), 0d);
		Assert.assertEquals(3L, counters.childEvaluations());
		Assert.assertEquals(1L, counters.costCuts());
	}

	@Test
	public void residueAndWideLatticeDisableTheLocalCostCut() {
		var x = variable("unsafe-cut-x", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x);
		var residueInputs = ExactCategoricalSolver.boundaryLeaves(variables, List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x), 1.0e16, 1.0e16),
			ExactCategoricalSolver.Factor.dense(List.of(x), 1d, 2d)), GENEROUS);
		var residue = ExactCategoricalSolver.mergeBoundary(
			residueInputs, List.of(x), GENEROUS, 2L);
		var zero = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(x), 0d, 0d), GENEROUS);
		var residueCounters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var residueRoot = ExactCategoricalSolver.mergeBoundary(
			List.of(residue, zero), List.of(), GENEROUS, 2L, residueCounters);
		Assert.assertEquals(1.0e16, residueRoot.minimum(), 0d);
		Assert.assertEquals(4L, residueCounters.childEvaluations());
		Assert.assertEquals(0L, residueCounters.costCuts());

		double wide = 0x1p53;
		double[][] wideTables = {{wide, wide + 2d}, {1d, 1d}};
		var wideCertificate = ExactDyadicCosts.certifyTables(
			List.of(wideTables[0], wideTables[1]));
		Assert.assertTrue(wideCertificate.supported());
		Assert.assertTrue(wideCertificate.maximumSumBits() > 53);
		var wideCounters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var wideRoot = ExactCategoricalSolver.mergeBoundary(
			leaves(variables, x, wideTables), List.of(), GENEROUS, 2L, wideCounters);
		Reference reference = enumerate(wideTables);
		Assert.assertEquals(reference.minimum(), wideRoot.minimum(), 0d);
		Assert.assertEquals(4L, wideCounters.childEvaluations());
		Assert.assertEquals(0L, wideCounters.costCuts());
	}

	@Test
	public void conditionalBoundaryMatchesFullEnumerationAndSkipsMiddleInfinity() {
		var boundaryVariable = variable("conditional-boundary", 3);
		var internal = variable("conditional-internal", 4);
		List<ExactCategoricalSolver.Variable> variables = List.of(boundaryVariable, internal);
		double inf = Double.POSITIVE_INFINITY;
		double[][] tables = {
			{.125, .25, .375, inf, .75, .5, .125, .25, .125, .25, .375, .5},
			{.125, .125, .125, .125, .125, .125, .125, .125, inf, inf, inf, inf},
			{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
		};
		var counters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var boundary = ExactCategoricalSolver.mergeBoundary(
			boundaryLeaves(variables, boundaryVariable, internal, tables),
			List.of(boundaryVariable), GENEROUS, 12L, counters);
		BoundaryReference reference = enumerateBoundary(tables, 3, 4);

		Assert.assertArrayEquals(reference.minima(), boundary.minMarginals(boundaryVariable), 0d);
		Assert.assertArrayEquals(reference.minima(), boundary.lowerMinMarginals(boundaryVariable), 0d);
		Assert.assertEquals(.25, boundary.minimum(), 0d);
		Assert.assertEquals(.25, boundary.lowerBound(), 0d);
		for(int value = 0; value < 2; value++) {
			int[] assignment = {value, -1};
			boundary.decodeInto(assignment, variables);
			Assert.assertEquals(reference.winners()[value], assignment[1]);
		}
		int[] infeasible = {2, -1};
		Assert.assertThrows(IllegalArgumentException.class,
			() -> boundary.decodeInto(infeasible, variables));
		Assert.assertTrue(counters.costCuts() > 0);
		Assert.assertTrue(counters.infeasibleCuts() >= 5);
		Assert.assertTrue(counters.childEvaluations() < 36L);
	}

	@Test
	public void randomizedExactLatticeBoundariesMatchEnumerationAndBacktrace() {
		Random random = new Random(61006L);
		for(int trial = 0; trial < 40; trial++) {
			var boundaryVariable = variable("random-boundary-" + trial, 3);
			var internal = variable("random-internal-" + trial, 4);
			List<ExactCategoricalSolver.Variable> variables = List.of(boundaryVariable, internal);
			double[][] tables = new double[3][12];
			for(int child = 0; child < tables.length; child++)
				for(int boundary = 0; boundary < 3; boundary++)
					for(int value = 0; value < 4; value++) {
						int cell = boundary * 4 + value;
						tables[child][cell] = value != 0 && random.nextInt(8) == 0
							? Double.POSITIVE_INFINITY : random.nextInt(9) * .125;
					}
			BoundaryReference reference = enumerateBoundary(tables, 3, 4);
			var boundary = ExactCategoricalSolver.mergeBoundary(
				boundaryLeaves(variables, boundaryVariable, internal, tables),
				List.of(boundaryVariable), GENEROUS, 12L,
				new ExactCategoricalSolver.BoundaryMergeCounters());

			Assert.assertArrayEquals(reference.minima(), boundary.minMarginals(boundaryVariable), 0d);
			Assert.assertArrayEquals(reference.minima(),
				boundary.lowerMinMarginals(boundaryVariable), 0d);
			double global = Math.min(reference.minima()[0],
				Math.min(reference.minima()[1], reference.minima()[2]));
			Assert.assertEquals(global, boundary.minimum(), 0d);
			Assert.assertEquals(global, boundary.lowerBound(), 0d);
			for(int value = 0; value < 3; value++) {
				int[] assignment = {value, -1};
				boundary.decodeInto(assignment, variables);
				Assert.assertEquals(reference.winners()[value], assignment[1]);
			}
		}
	}

	private static List<ExactCategoricalSolver.BoundaryMessage> leaves(
		List<ExactCategoricalSolver.Variable> variables,
		ExactCategoricalSolver.Variable variable, double[][] tables) {
		return ExactCategoricalSolver.boundaryLeaves(variables,
			java.util.Arrays.stream(tables)
				.map(table -> ExactCategoricalSolver.Factor.dense(List.of(variable), table))
				.toList(), GENEROUS);
	}

	private static List<ExactCategoricalSolver.BoundaryMessage> boundaryLeaves(
		List<ExactCategoricalSolver.Variable> variables,
		ExactCategoricalSolver.Variable boundary, ExactCategoricalSolver.Variable internal,
		double[][] tables) {
		return ExactCategoricalSolver.boundaryLeaves(variables,
			java.util.Arrays.stream(tables)
				.map(table -> ExactCategoricalSolver.Factor.dense(List.of(boundary, internal), table))
				.toList(), GENEROUS);
	}

	private static Reference enumerate(double[][] tables) {
		double minimum = Double.POSITIVE_INFINITY;
		int winner = -1;
		for(int value = 0; value < tables[0].length; value++) {
			double candidate = 0d;
			for(double[] table : tables)
				candidate += table[value];
			if(candidate < minimum) {
				minimum = candidate;
				winner = value;
			}
		}
		return new Reference(minimum, winner);
	}

	private static BoundaryReference enumerateBoundary(double[][] tables,
		int boundarySize, int internalSize) {
		double[] minima = new double[boundarySize];
		java.util.Arrays.fill(minima, Double.POSITIVE_INFINITY);
		int[] winners = new int[boundarySize];
		java.util.Arrays.fill(winners, -1);
		for(int boundary = 0; boundary < boundarySize; boundary++)
			for(int value = 0; value < internalSize; value++) {
				double candidate = 0d;
				for(double[] table : tables)
					candidate += table[boundary * internalSize + value];
				if(candidate < minima[boundary]) {
					minima[boundary] = candidate;
					winners[boundary] = value;
				}
			}
		return new BoundaryReference(minima, winners);
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private record Reference(double minimum, int winner) { }
	private record BoundaryReference(double[] minima, int[] winners) { }
}
