/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Behavior lock for cost pruning.  The optimized implementations may read fewer
 * children, but must publish the same logical messages and canonical witnesses.
 */
public class CertifiedCostPruningParityTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(1_000_000L, 10_000_000L);
	private static final double INF = Double.POSITIVE_INFINITY;

	@After
	public void clearAblation() {
		System.clearProperty(PruningAblation.PROPERTY);
	}

	@Test
	public void oneHundredTwentyLocalMessagesMatchEnumerationWithAndWithoutPruning() {
		Random random = new Random(0xC057_120L);
		for(int trial = 0; trial < 120; trial++) {
			int boundaryDomain = 2 + random.nextInt(3);
			int internalDomain = 2 + random.nextInt(3);
			var boundary = variable("local-boundary-" + trial, boundaryDomain);
			var internal = variable("local-internal-" + trial, internalDomain);
			List<ExactCategoricalSolver.Variable> variables = List.of(boundary, internal);
			double[][] tables = new double[3][boundaryDomain * internalDomain];
			for(int factor = 0; factor < tables.length; factor++)
				for(int boundaryValue = 0; boundaryValue < boundaryDomain; boundaryValue++)
					for(int internalValue = 0; internalValue < internalDomain; internalValue++) {
						int cell = boundaryValue * internalDomain + internalValue;
						// Keep value zero feasible in every row, while retaining sparse rows and ties.
						tables[factor][cell] = internalValue > 0 && random.nextInt(7) == 0
							? INF : random.nextInt(9) * .125d;
					}
			LocalReference reference = enumerateLocal(tables, boundaryDomain, internalDomain);
			List<ExactCategoricalSolver.Factor> factors = factors(boundary, internal, tables);
			var baseline = merge("baseline", variables, factors,
				List.of(boundary), null);
			var optimized = merge("local_only", variables, factors,
				List.of(boundary), null);
			System.clearProperty(PruningAblation.PROPERTY);
			var defaultSuffix = ExactCategoricalSolver.mergeBoundary(
				ExactCategoricalSolver.boundaryLeaves(variables, factors, LIMITS),
				List.of(boundary), LIMITS);
			var explicitSuffix = ExactCategoricalSolver.mergeBoundaryWithPruningForTest(
				ExactCategoricalSolver.boundaryLeaves(variables, factors, LIMITS),
				List.of(boundary), LIMITS, ExactCategoricalSolver.CostPruningMode.SUFFIX,
				new ExactCategoricalSolver.BoundaryMergeCounters());
			assertLocalMessage(reference, baseline, variables, boundary, internal);
			assertLocalMessage(reference, optimized, variables, boundary, internal);
			assertLocalMessage(reference, defaultSuffix, variables, boundary, internal);
			assertLocalMessage(reference, explicitSuffix, variables, boundary, internal);
			assertSameLogicalMessage(baseline, optimized, variables);
			assertSameLogicalMessage(baseline, defaultSuffix, variables);
			assertSameLogicalMessage(defaultSuffix, explicitSuffix, variables);
		}
	}

	@Test
	public void supportAndQuotientMergeRetainsEveryLogicalBoundaryAndWitness() {
		var boundary = variable("quotient-boundary", 12);
		var internal = variable("quotient-internal", 8);
		List<ExactCategoricalSolver.Variable> variables = List.of(boundary, internal);
		double[][] tables = new double[3][96];
		for(int boundaryValue = 0; boundaryValue < 12; boundaryValue++)
			for(int internalValue = 0; internalValue < 8; internalValue++) {
				int cell = boundaryValue * 8 + internalValue;
				tables[0][cell] = (boundaryValue & 1) == (internalValue & 1) ? 0d : INF;
				tables[1][cell] = (boundaryValue % 3) + (internalValue & 1) * .125d;
				tables[2][cell] = (internalValue & 1) * .25d;
			}
		LocalReference reference = enumerateLocal(tables, 12, 8);
		var baselineCounters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var optimizedCounters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var baseline = merge("baseline", variables, factors(boundary, internal, tables),
			List.of(boundary), baselineCounters);
		var optimized = merge("local_only", variables, factors(boundary, internal, tables),
			List.of(boundary), optimizedCounters);
		assertLocalMessage(reference, baseline, variables, boundary, internal);
		assertLocalMessage(reference, optimized, variables, boundary, internal);
		assertSameLogicalMessage(baseline, optimized, variables);
		Assert.assertTrue("fixture must enter the support/quotient merge",
			optimizedCounters.supportCellsExamined() > 0);
		Assert.assertTrue("equivalent boundary profiles should share numeric storage",
			optimized.retainedCells() < baseline.retainedCells());
	}

	@Test
	public void higherLocalBoundaryCostCanStillBeTheGlobalWinner() {
		var shared = variable("shared-choice", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(shared);
		double[][] tables = {{1d, 3d}, {100d, 0d}};
		for(String variant : List.of("baseline", "local_only")) {
			var message = merge(variant, variables, factors(shared, null, tables), List.of(), null);
			Assert.assertEquals(3d, message.minimum(), 0d);
			Assert.assertEquals(3d, message.lowerBound(), 0d);
			int[] assignment = {-1};
			message.decodeInto(assignment, variables);
			Assert.assertArrayEquals(new int[] {1}, assignment);
		}
	}

	@Test
	public void tiesAndRoundedResiduesPreserveLowerCellsAndBacktrace() {
		var x = variable("residue-x", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x);
		List<ExactCategoricalSolver.Factor> childFactors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x), 1.0e16, 1.0e16),
			ExactCategoricalSolver.Factor.dense(List.of(x), .125d, .125d),
			ExactCategoricalSolver.Factor.dense(List.of(x), .125d, .125d));
		var baseline = merge("baseline", variables, childFactors, List.of(x), null);
		var optimized = merge("local_only", variables, childFactors, List.of(x), null);
		assertSameLogicalMessage(baseline, optimized, variables);
		for(var message : List.of(baseline, optimized)) {
			Assert.assertArrayEquals(new double[] {1.0e16, 1.0e16}, message.minMarginals(x), 0d);
			Assert.assertArrayEquals(new double[] {1.0e16, 1.0e16}, message.lowerMinMarginals(x), 0d);
		}
		System.setProperty(PruningAblation.PROPERTY, "baseline");
		var baselineRoot = ExactCategoricalSolver.mergeBoundary(List.of(baseline), List.of(), LIMITS);
		System.setProperty(PruningAblation.PROPERTY, "local_only");
		var optimizedRoot = ExactCategoricalSolver.mergeBoundary(List.of(optimized), List.of(), LIMITS);
		assertSameLogicalMessage(baselineRoot, optimizedRoot, variables);
		int[] assignment = {-1};
		optimizedRoot.decodeInto(assignment, variables);
		Assert.assertArrayEquals(new int[] {0}, assignment);
	}

	@Test
	public void oneHundredTenGlobalModelsMatchBruteForceDenseSparseAndDyadic() throws Exception {
		Random random = new Random(0x610B_A11L);
		for(int trial = 0; trial < 110; trial++) {
			List<ExactCategoricalSolver.Variable> variables = List.of(
				variable("global-x-" + trial, 2 + random.nextInt(2)),
				variable("global-y-" + trial, 2 + random.nextInt(2)),
				variable("global-z-" + trial, 2 + random.nextInt(2)));
			List<TableFactor> definitions = List.of(
				randomFactor(random, variables, 0, 1),
				randomFactor(random, variables, 1, 2),
				randomFactor(random, variables, 0, 2),
				randomFactor(random, variables, trial % 3));
			List<ExactCategoricalSolver.Factor> factors = definitions.stream()
				.map(TableFactor::factor).toList();
			var compiled = ExactCategoricalSolver.compilePreferred(variables, factors, LIMITS,
				variables.stream().map(ExactCategoricalSolver.Variable::key).toList());
			List<ExactCategoricalSolver.EliminationSnapshot> denseSteps = new ArrayList<>();
			List<ExactCategoricalSolver.EliminationSnapshot> sparseSteps = new ArrayList<>();
			var dense = ExactCategoricalSolver.solveWithStepsForTest(compiled,
				(variable, value) -> 0L, denseSteps::add);
			var sparse = ExactCategoricalSolver.solveWithStepsForTest(compiled, null, sparseSteps::add);
			var certificate = ExactDyadicCosts.certify(-3, 24, factors.size(), true);
			Assert.assertTrue(certificate.reason(), certificate.supported());
			List<ExactCategoricalSolver.EliminationSnapshot> dyadicSteps =
				dynamicSteps(compiled, certificate);
			var dyadic = ExactCategoricalSolver.solveDyadic(compiled, certificate);
			GlobalReference reference = enumerateGlobal(variables, definitions);
			assertGlobalResult(reference, dense, definitions, variables);
			assertSameResult(dense, sparse);
			assertSameResult(dense, dyadic);
			assertSameSteps(denseSteps, sparseSteps);
			assertSameRoundedSteps(denseSteps, dyadicSteps, -3);
		}
	}

	@Test
	public void negativeCostsWideResiduesAndCanonicalTiesUseTheExactFallback() {
		var x = variable("global-residue-x", 2);
		var y = variable("global-residue-y", 3);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, y);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x, y),
				1e16, 1e16, 1e16, 1e16, 1e16, 1e16),
			ExactCategoricalSolver.Factor.dense(List.of(x, y),
				.125d, .25d, .5d, .125d, .25d, .5d),
			ExactCategoricalSolver.Factor.dense(List.of(y), -1e16, -1e16, -1e16));
		var compiled = ExactCategoricalSolver.compilePreferred(variables, factors, LIMITS,
			List.of(x.key(), y.key()));
		List<ExactCategoricalSolver.EliminationSnapshot> denseSteps = new ArrayList<>();
		List<ExactCategoricalSolver.EliminationSnapshot> sparseSteps = new ArrayList<>();
		var dense = ExactCategoricalSolver.solveWithStepsForTest(compiled,
			(variable, value) -> 0L, denseSteps::add);
		var sparse = ExactCategoricalSolver.solveWithStepsForTest(compiled, null, sparseSteps::add);
		assertSameResult(dense, sparse);
		assertSameSteps(denseSteps, sparseSteps);
		Assert.assertEquals(Double.doubleToRawLongBits(.125d),
			Double.doubleToRawLongBits(dense.objective()));
		Assert.assertEquals(List.of(0, 0), dense.assignmentInVariableOrder());
	}

	private static List<ExactCategoricalSolver.Factor> factors(
		ExactCategoricalSolver.Variable first, ExactCategoricalSolver.Variable second,
		double[][] tables) {
		List<ExactCategoricalSolver.Variable> scope = second == null
			? List.of(first) : List.of(first, second);
		return Arrays.stream(tables)
			.map(table -> ExactCategoricalSolver.Factor.dense(scope, table)).toList();
	}

	private static ExactCategoricalSolver.BoundaryMessage merge(String variant,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		List<ExactCategoricalSolver.Variable> boundary,
		ExactCategoricalSolver.BoundaryMergeCounters counters) {
		System.setProperty(PruningAblation.PROPERTY, variant);
		return ExactCategoricalSolver.mergeBoundary(
			ExactCategoricalSolver.boundaryLeaves(variables, factors, LIMITS),
			boundary, LIMITS, counters);
	}

	private static LocalReference enumerateLocal(double[][] tables, int boundaryDomain,
		int internalDomain) {
		double[] minima = new double[boundaryDomain];
		int[] winners = new int[boundaryDomain];
		Arrays.fill(minima, INF);
		Arrays.fill(winners, -1);
		for(int boundary = 0; boundary < boundaryDomain; boundary++)
			for(int internal = 0; internal < internalDomain; internal++) {
				double candidate = 0d;
				for(double[] table : tables)
					candidate += table[boundary * internalDomain + internal];
				if(candidate < minima[boundary]) {
					minima[boundary] = candidate;
					winners[boundary] = internal;
				}
			}
		return new LocalReference(minima, winners);
	}

	private static void assertLocalMessage(LocalReference reference,
		ExactCategoricalSolver.BoundaryMessage message,
		List<ExactCategoricalSolver.Variable> variables,
		ExactCategoricalSolver.Variable boundary,
		ExactCategoricalSolver.Variable internal) {
		Assert.assertArrayEquals(reference.minima(), message.minMarginals(boundary), 0d);
		Assert.assertArrayEquals(reference.minima(), message.lowerMinMarginals(boundary), 0d);
		for(int value = 0; value < boundary.domainSize(); value++) {
			int[] assignment = {value, -1};
			Assert.assertEquals(reference.minima()[value],
				message.valueForAssignment(assignment, variables), 0d);
			message.decodeInto(assignment, variables);
			Assert.assertEquals(value, assignment[variables.indexOf(boundary)]);
			Assert.assertEquals(reference.winners()[value], assignment[variables.indexOf(internal)]);
		}
	}

	private static void assertSameLogicalMessage(ExactCategoricalSolver.BoundaryMessage expected,
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
		int[] scopeValues = new int[expected.scope().size()];
		for(int cell = 0; cell < cells; cell++) {
			decode(cell, expected.scope(), scopeValues);
			int[] expectedAssignment = new int[variables.size()];
			Arrays.fill(expectedAssignment, -1);
			for(int axis = 0; axis < scopeValues.length; axis++)
				expectedAssignment[variables.indexOf(expected.scope().get(axis))] = scopeValues[axis];
			int[] actualAssignment = expectedAssignment.clone();
			Assert.assertEquals(Double.doubleToRawLongBits(
				expected.valueForAssignment(expectedAssignment, variables)),
				Double.doubleToRawLongBits(actual.valueForAssignment(actualAssignment, variables)));
			boolean feasible = true;
			try {
				expected.decodeInto(expectedAssignment, variables);
			}
			catch(IllegalArgumentException expectedFailure) {
				feasible = false;
			}
			if(feasible) {
				actual.decodeInto(actualAssignment, variables);
				Assert.assertArrayEquals(expectedAssignment, actualAssignment);
			}
			else
				Assert.assertThrows(IllegalArgumentException.class,
					() -> actual.decodeInto(actualAssignment, variables));
		}
	}

	private static TableFactor randomFactor(Random random,
		List<ExactCategoricalSolver.Variable> variables, int... scopeIndexes) {
		List<ExactCategoricalSolver.Variable> scope = Arrays.stream(scopeIndexes)
			.mapToObj(variables::get).toList();
		int cells = scope.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		double[] values = new double[cells];
		for(int cell = 0; cell < cells; cell++)
			values[cell] = cell != 0 && random.nextInt(8) == 0
				? INF : random.nextInt(17) * .125d;
		return new TableFactor(scopeIndexes.clone(), values,
			ExactCategoricalSolver.Factor.dense(scope, values));
	}

	private static GlobalReference enumerateGlobal(List<ExactCategoricalSolver.Variable> variables,
		List<TableFactor> factors) {
		int cells = variables.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		double minimum = INF;
		for(int cell = 0; cell < cells; cell++) {
			int[] assignment = new int[variables.size()];
			decode(cell, variables, assignment);
			double candidate = evaluate(factors, assignment, variables);
			minimum = Math.min(minimum, candidate);
		}
		return new GlobalReference(minimum);
	}

	private static void assertGlobalResult(GlobalReference reference,
		ExactCategoricalSolver.Result result, List<TableFactor> factors,
		List<ExactCategoricalSolver.Variable> variables) {
		Assert.assertEquals(Double.doubleToRawLongBits(reference.minimum()),
			Double.doubleToRawLongBits(result.objective()));
		int[] assignment = result.assignmentInVariableOrder().stream().mapToInt(Integer::intValue).toArray();
		Assert.assertEquals(Double.doubleToRawLongBits(reference.minimum()),
			Double.doubleToRawLongBits(evaluate(factors, assignment, variables)));
	}

	private static double evaluate(List<TableFactor> factors, int[] assignment,
		List<ExactCategoricalSolver.Variable> variables) {
		double total = 0d;
		for(TableFactor factor : factors) {
			int cell = 0;
			for(int variable : factor.scope())
				cell = cell * variables.get(variable).domainSize() + assignment[variable];
			total += factor.values()[cell];
		}
		return total;
	}

	private static void assertSameResult(ExactCategoricalSolver.Result expected,
		ExactCategoricalSolver.Result actual) {
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
	}

	private static void assertSameSteps(List<ExactCategoricalSolver.EliminationSnapshot> expected,
		List<ExactCategoricalSolver.EliminationSnapshot> actual) {
		Assert.assertEquals(expected.size(), actual.size());
		for(int step = 0; step < expected.size(); step++) {
			var left = expected.get(step);
			var right = actual.get(step);
			Assert.assertEquals(left.variable(), right.variable());
			Assert.assertArrayEquals(left.scope(), right.scope());
			Assert.assertArrayEquals(left.choices(), right.choices());
			assertRaw(left.high(), right.high());
			assertRaw(left.low(), right.low());
		}
	}

	private static void assertSameRoundedSteps(
		List<ExactCategoricalSolver.EliminationSnapshot> expected,
		List<ExactCategoricalSolver.EliminationSnapshot> actual, int dyadicExponent) {
		Assert.assertEquals(expected.size(), actual.size());
		for(int step = 0; step < expected.size(); step++) {
			var left = expected.get(step);
			var right = actual.get(step);
			Assert.assertEquals(left.variable(), right.variable());
			Assert.assertArrayEquals(left.scope(), right.scope());
			Assert.assertArrayEquals(left.choices(), right.choices());
			Assert.assertEquals(left.high().length, right.high().length);
			for(int cell = 0; cell < left.high().length; cell++) {
				double expectedValue = left.high()[cell] + left.low()[cell];
				double actualValue = Math.scalb(
					right.high()[cell] + right.low()[cell], dyadicExponent);
				Assert.assertEquals("step=" + step + " cell=" + cell,
					Double.doubleToRawLongBits(expectedValue),
					Double.doubleToRawLongBits(actualValue));
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static List<ExactCategoricalSolver.EliminationSnapshot> dynamicSteps(
		ExactCategoricalSolver.CompiledProblem compiled,
		ExactDyadicCosts.Certificate certificate) throws Exception {
		Field preparedField = compiled.getClass().getDeclaredField("prepared");
		Field factorsField = compiled.getClass().getDeclaredField("factors");
		preparedField.setAccessible(true);
		factorsField.setAccessible(true);
		Object prepared = preparedField.get(compiled);
		Method solve = ExactCategoricalSolver.class.getDeclaredMethod("solve", prepared.getClass(),
			List.class, ExactCategoricalSolver.TieCostFunction.class, Consumer.class,
			ExactDyadicCosts.Certificate.class);
		solve.setAccessible(true);
		List<ExactCategoricalSolver.EliminationSnapshot> steps = new ArrayList<>();
		try {
			solve.invoke(null, prepared, factorsField.get(compiled), null,
				(Consumer<ExactCategoricalSolver.EliminationSnapshot>)steps::add, certificate);
		}
		catch(InvocationTargetException failure) {
			throw (RuntimeException)failure.getCause();
		}
		return steps;
	}

	private static void decode(int cell, List<ExactCategoricalSolver.Variable> scope, int[] values) {
		for(int axis = scope.size() - 1; axis >= 0; axis--) {
			values[axis] = cell % scope.get(axis).domainSize();
			cell /= scope.get(axis).domainSize();
		}
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

	private record LocalReference(double[] minima, int[] winners) { }
	private record GlobalReference(double minimum) { }
	private record TableFactor(int[] scope, double[] values,
		ExactCategoricalSolver.Factor factor) { }
}
