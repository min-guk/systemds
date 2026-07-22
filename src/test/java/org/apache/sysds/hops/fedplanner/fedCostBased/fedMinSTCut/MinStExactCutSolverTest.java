/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class MinStExactCutSolverTest {
	private static final long SOURCE = -1L;
	private static final long SINK = -2L;

	@Test
	public void uniqueOptimumReturnsCanonicalSourcePartition() {
		MinStExactCutSolver.Result result = MinStExactCutSolver.solve(SOURCE, SINK,
			List.of(decision(choice(), choice(0L))), List.of(), List.of(
				edge(SOURCE, 0L, 10.0), edge(0L, SINK, 1.0)));
		Assert.assertEquals(bits(1.0), result.objectiveBits());
		Assert.assertTrue(result.unique());
		Assert.assertEquals(List.of(0L), result.minima().get(0).sourceNodeIds());
	}

	@Test
	public void equalCutReportsTieUnspecifiedShapeAndRetainsAllMinima() {
		MinStExactCutSolver.Result result = MinStExactCutSolver.solve(SOURCE, SINK,
			List.of(), List.of(5L, 2L), List.of(edge(SOURCE, SINK, 2.0)));
		Assert.assertEquals(bits(2.0), result.objectiveBits());
		Assert.assertFalse(result.unique());
		Assert.assertEquals(List.of(List.of(), List.of(2L), List.of(2L, 5L), List.of(5L)),
			result.minima().stream().map(MinStExactCutSolver.Minimum::sourceNodeIds).toList());
	}

	@Test
	public void rejectsNonCanonicalCapacitiesBeforeSolving() {
		for(long badBits : List.of(Double.doubleToRawLongBits(Double.NaN),
			Double.doubleToRawLongBits(Double.POSITIVE_INFINITY),
			Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY),
			Double.doubleToRawLongBits(-1.0), Double.doubleToRawLongBits(-0.0)))
			assertRejects(() -> MinStExactCutSolver.solve(SOURCE, SINK, List.of(),
				List.of(), List.of(new MinStExactCutSolver.Edge(SOURCE, SINK, badBits))));
	}

	@Test
	public void canonicalOrderingIsDeterministicForChoicesAndFreeNodes() {
		MinStExactCutSolver.Result result = MinStExactCutSolver.solve(SOURCE, SINK,
			List.of(decision(choice(7L, 3L), choice(4L))), List.of(9L, 1L),
			List.of(edge(SOURCE, SINK, 0.0)));
		Assert.assertEquals(List.of(
			List.of(1L, 3L, 7L), List.of(1L, 3L, 7L, 9L),
			List.of(1L, 4L), List.of(1L, 4L, 9L), List.of(3L, 7L),
			List.of(3L, 7L, 9L), List.of(4L), List.of(4L, 9L)),
			result.minima().stream().map(MinStExactCutSolver.Minimum::sourceNodeIds).toList());
	}

	@Test
	public void cutObjectiveUsesCompensatedCanonicalCapacitySum() {
		List<MinStExactCutSolver.Edge> largeFirst = List.of(
			edge(SOURCE, SINK, 1.0e16), edge(SOURCE, SINK, 1.0), edge(SOURCE, SINK, 1.0));
		List<MinStExactCutSolver.Edge> smallFirst = List.of(
			edge(SOURCE, SINK, 1.0), edge(SOURCE, SINK, 1.0), edge(SOURCE, SINK, 1.0e16));
		long exactBits = bits(1.0000000000000002e16);

		MinStExactCutSolver.Result largeFirstResult = MinStExactCutSolver.solve(SOURCE, SINK,
			List.of(), List.of(), largeFirst);
		MinStExactCutSolver.Result smallFirstResult = MinStExactCutSolver.solve(SOURCE, SINK,
			List.of(), List.of(), smallFirst);

		Assert.assertEquals(exactBits, largeFirstResult.objectiveBits());
		Assert.assertEquals(exactBits, smallFirstResult.objectiveBits());
	}

	@Test
	public void solverSourceContainsNoTimeoutCapOrFallbackMarkers() throws Exception {
		String source = Files.readString(Path.of(
			"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCutSolver.java"));
		Assert.assertFalse(source.contains("timeout"));
		Assert.assertFalse(source.contains("fallback"));
		Assert.assertFalse(source.contains("Runtime"));
		Assert.assertFalse(source.contains("MAX_STATES"));
		Assert.assertFalse(source.contains("STATE_CAP"));
	}

	private static MinStExactCutSolver.Decision decision(MinStExactCutSolver.Choice... choices) {
		return new MinStExactCutSolver.Decision(List.of(choices));
	}

	private static MinStExactCutSolver.Choice choice(Long... sourceNodeIds) {
		return new MinStExactCutSolver.Choice(List.of(sourceNodeIds));
	}

	private static MinStExactCutSolver.Edge edge(long from, long to, double capacity) {
		return new MinStExactCutSolver.Edge(from, to, bits(capacity));
	}

	private static long bits(double value) {
		return Double.doubleToRawLongBits(value);
	}

	private static void assertRejects(Runnable action) {
		try {
			action.run();
			Assert.fail("Expected rejection");
		}
		catch(IllegalArgumentException expected) {
			// expected
		}
	}
}
