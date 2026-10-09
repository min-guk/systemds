/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

public class ExactNaryFiniteSupportReductionTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(2_000_000, 8_000_000);

	@Test
	public void ternarySparseSupportCascadesAcrossThreeFixedPointRounds() {
		var a = variable("cascade-a", 3);
		var b = variable("cascade-b", 3);
		var c = variable("cascade-c", 3);
		var d = variable("cascade-d", 3);
		var e = variable("cascade-e", 3);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c, d, e);
		int[] diagonal3 = {0, 13, 26};
		int[] diagonalWithFreeTail = {0, 1, 2, 12, 13, 14, 24, 25, 26};
		int[] firstTwoPlanes = new int[18];
		for(int cell = 0; cell < firstTwoPlanes.length; cell++)
			firstTwoPlanes[cell] = cell;
		List<ExactCategoricalSolver.Factor> sparse = List.of(
			ExactCategoricalSolver.Factor.finiteSupport(List.of(b, d, e), diagonalWithFreeTail),
			ExactCategoricalSolver.Factor.finiteSupport(List.of(a, b, c), diagonal3),
			ExactCategoricalSolver.Factor.finiteSupport(List.of(c, d, e), firstTwoPlanes),
			ExactCategoricalSolver.Factor.dense(List.of(a), 5d, 1d, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(d), 5d, 2d, 0d));
		List<ExactCategoricalSolver.Factor> explicit = List.of(
			denseSupport(List.of(b, d, e), diagonalWithFreeTail),
			denseSupport(List.of(a, b, c), diagonal3),
			denseSupport(List.of(c, d, e), firstTwoPlanes),
			sparse.get(3), sparse.get(4));

		ExactCategoricalSolver.Result expected =
			ExactCategoricalSolver.solve(variables, explicit, LIMITS);
		ExactCategoricalSolver.Result actual =
			ExactPhysicalReducedSolver.solve(variables.size(), variables, sparse, LIMITS);
		assertSameResult(expected, actual);
		Assert.assertEquals(List.of(1, 1, 1, 1, 0), actual.assignmentInVariableOrder());

		ExactPhysicalReducedSolver.CompactModel compact =
			ExactPhysicalReducedSolver.reducedModel(variables.size(), variables, sparse, LIMITS);
		Assert.assertEquals("factor order forces c, then a/b, then d to be removed in successive rounds",
			List.of(2, 2, 2, 2, 1), compact.variables().stream()
				.map(ExactCategoricalSolver.Variable::domainSize).toList());
		ExactCategoricalSolver.Factor ternary = sparse.get(1);
		int[] detached = ternary.finiteSupportCells();
		detached[0] = 25;
		Assert.assertEquals("the reducer reads only the three immutable stored rows",
			3, ternary.finiteSupportCellCount());
		Assert.assertEquals(0, ternary.finiteSupportCellAt(0));
		Assert.assertEquals(13, ternary.finiteSupportCellAt(1));
		Assert.assertEquals(26, ternary.finiteSupportCellAt(2));
	}

	@Test
	public void randomizedNarySparseReductionMatchesDenseReference() {
		Random random = new Random(0x6e6172795f66696eL);
		for(int trial = 0; trial < 80; trial++) {
			int arity = 3 + random.nextInt(2);
			List<ExactCategoricalSolver.Variable> variables = new ArrayList<>(arity);
			for(int axis = 0; axis < arity; axis++)
				variables.add(variable("random-" + trial + '-' + axis, 2 + random.nextInt(4)));
			int cells = variables.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
				.reduce(1, Math::multiplyExact);
			int[] support = new int[cells];
			int count = 1;
			support[0] = 0;
			for(int cell = 1; cell < cells; cell++)
				if(random.nextInt(5) == 0)
					support[count++] = cell;
			support = Arrays.copyOf(support, count);
			int lastDomain = variables.get(arity - 1).domainSize();
			int keep = 1 + random.nextInt(lastDomain);
			int[] allowed = new int[keep];
			for(int value = 0; value < keep; value++)
				allowed[value] = value;

			List<ExactCategoricalSolver.Factor> sparse = new ArrayList<>();
			List<ExactCategoricalSolver.Factor> explicit = new ArrayList<>();
			sparse.add(ExactCategoricalSolver.Factor.finiteSupport(variables, support));
			explicit.add(denseSupport(variables, support));
			List<ExactCategoricalSolver.Variable> last = List.of(variables.get(arity - 1));
			sparse.add(ExactCategoricalSolver.Factor.finiteSupport(last, allowed));
			explicit.add(denseSupport(last, allowed));
			for(int axis = 0; axis < arity; axis++) {
				double[] costs = new double[variables.get(axis).domainSize()];
				for(int value = 0; value < costs.length; value++)
					costs[value] = random.nextInt(9) + value * 0.125d;
				ExactCategoricalSolver.Factor cost =
					ExactCategoricalSolver.Factor.dense(List.of(variables.get(axis)), costs);
				sparse.add(cost);
				explicit.add(cost);
			}

			ExactCategoricalSolver.Result expected =
				ExactCategoricalSolver.solve(variables, explicit, LIMITS);
			ExactCategoricalSolver.Result actual = ExactPhysicalReducedSolver.solve(
				variables.size(), variables, sparse, LIMITS);
			assertSameResult(expected, actual);
		}
	}

	private static ExactCategoricalSolver.Factor denseSupport(
		List<ExactCategoricalSolver.Variable> scope, int[] finite) {
		int cells = scope.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		double[] values = new double[cells];
		Arrays.fill(values, Double.POSITIVE_INFINITY);
		for(int cell : finite)
			values[cell] = 0d;
		return ExactCategoricalSolver.Factor.dense(scope, values);
	}

	private static void assertSameResult(ExactCategoricalSolver.Result expected,
		ExactCategoricalSolver.Result actual) {
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}
}
