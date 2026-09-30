/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Result;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

public class ExactSparseMessageParityTest {
	private static final Limits LIMITS = new Limits(1_000_000, 10_000_000);
	private static final double INF = Double.POSITIVE_INFINITY;

	@Test
	public void selectiveFiveMillionValueAxisUsesBoundedInverseStorage() throws Exception {
		Process process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
			"-Xmx192m", "-cp", System.getProperty("java.class.path"), SelectiveMemoryProbe.class.getName())
			.redirectErrorStream(true).start();
		if(!process.waitFor(30, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			Assert.fail("selective inverse-fibre memory probe timed out");
		}
		String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		Assert.assertEquals(output, 0, process.exitValue());
		Assert.assertTrue(output, output.contains("SELECTIVE_MEMORY_OK"));
	}

	public static final class SelectiveMemoryProbe {
		public static void main(String[] args) {
			int domain = 5_000_000;
			Variable variable = new Variable("large-selective", domain);
			double[] values = new double[domain];
			for(int value = 1; value < domain; value += 2)
				values[value] = INF;
			Result result = ExactCategoricalSolver.solve(List.of(variable),
				List.of(Factor.dense(List.of(variable), values)),
				new Limits(Integer.MAX_VALUE, Long.MAX_VALUE));
			Assert.assertEquals(0d, result.objective(), 0d);
			Assert.assertEquals(List.of(0), result.assignmentInVariableOrder());
			System.out.println("SELECTIVE_MEMORY_OK");
		}
	}

	@Test
	public void binaryLinkLiftingStoresProfilesInsteadOfTheLogicalCartesianProduct() throws Exception {
		Variable e = new Variable("profile-e", 2);
		Variable a = new Variable("profile-a", 20);
		Variable b = new Variable("profile-b", 30);
		Variable c = new Variable("profile-c", 7);
		var steps = assertParity(List.of(e, a, b, c), List.of(
			Factor.lazy(List.of(a, e), v -> v[0] % 2 == v[1] ? 0d : INF),
			Factor.lazy(List.of(b, c, e), v -> (v[0] % 3) * 0.5d + (v[1] % 2) * 0.25d + v[2] * 4d),
			Factor.lazy(List.of(a), v -> v[0] == 13 ? -100d : 0d),
			Factor.lazy(List.of(b), v -> v[0] == 19 ? -30d : 0d),
			Factor.lazy(List.of(c), v -> v[0] == 6 ? -20d : 0d)));
		Assert.assertEquals(4200, steps.get(0).high().length);
		Assert.assertEquals("All logical values remain, with only 12 distinct profile coordinates",
			12, steps.get(0).storedCells());
		for(int cell = 0; cell < 4200; cell++)
			Assert.assertEquals(cell / (30 * 7) % 2, steps.get(0).choices()[cell]);
	}

	@Test
	public void commonRefinementSplitsStoredSupportClassesWithoutLosingTuples() throws Exception {
		Variable u = new Variable("refine-u", 2);
		Variable x = new Variable("refine-x", 4);
		Variable y = new Variable("refine-y", 2);
		Variable z = new Variable("refine-z", 2);
		var steps = assertParity(List.of(u, x, y, z), List.of(
			Factor.lazy(List.of(x, u), v -> v[0] / 2 == v[1] ? 0d : INF),
			Factor.lazy(List.of(u, y), v -> v[0] == v[1] ? 0d : INF),
			Factor.lazy(List.of(x, z), v -> v[0] % 2 == v[1] ? 0d : INF),
			Factor.dense(List.of(y), 1d, 0d), Factor.dense(List.of(z), 1d, 0d)));
		Assert.assertEquals(2, steps.get(0).storedCells());
		Assert.assertArrayEquals(new int[] {0, 1, 2, 3}, steps.get(1).choices());
	}

	@Test
	public void constantMessageCostsCanHaveNonconstantBackpointers() throws Exception {
		Variable x = new Variable("choice-x", 3);
		Variable a = new Variable("choice-a", 3);
		Variable b = new Variable("choice-b", 2);
		var steps = assertParity(List.of(x, a, b), List.of(
			Factor.lazy(List.of(x, a, b), v -> v[0] == v[1] ? 0d : INF),
			Factor.dense(List.of(a), 3d, 1d, 2d), Factor.dense(List.of(b), 2d, 0d)));
		Assert.assertArrayEquals(new int[] {0, 0, 1, 1, 2, 2}, steps.get(0).choices());
		for(double value : steps.get(0).high())
			Assert.assertEquals(Double.doubleToRawLongBits(0d), Double.doubleToRawLongBits(value));
	}

	@Test
	public void rawLowResiduesRemainDependenciesEvenWhenRoundedCostsMatch() throws Exception {
		Variable x = new Variable("residue-x", 2);
		Variable y = new Variable("residue-y", 3);
		var steps = assertParity(List.of(x, y), List.of(
			Factor.lazy(List.of(x, y), v -> 1e16),
			Factor.lazy(List.of(x, y), v -> Math.scalb(1d, v[1] - 3)),
			Factor.dense(List.of(y), -1e16, -1e16, -1e16)));
		Assert.assertArrayEquals(new double[] {1e16, 1e16, 1e16}, steps.get(0).high(), 0d);
		Assert.assertArrayEquals(new double[] {0.125d, 0.25d, 0.5d}, steps.get(0).low(), 0d);
	}

	@Test
	public void constantFactorsKeepTheirOriginalArithmeticGrouping() throws Exception {
		Variable x = new Variable("group-x", 3);
		Variable y = new Variable("group-y", 2);
		assertParity(List.of(x, y), List.of(
			Factor.lazy(List.of(x, y), v -> 1e16),
			Factor.dense(List.of(x), 0.5d, 0.25d, 0.125d),
			Factor.lazy(List.of(x, y), v -> -1e16),
			Factor.dense(List.of(y), Double.MIN_VALUE, 0d),
			Factor.dense(List.of(), -0.25d)));
	}

	@Test
	public void equalityStarPreservesEveryMessageAndOriginalValueTie() throws Exception {
		List<Variable> variables = List.of(new Variable("center", 5), new Variable("a", 5),
			new Variable("b", 5), new Variable("c", 5));
		List<Factor> factors = new ArrayList<>();
		for(int index = 1; index < variables.size(); index++)
			factors.add(Factor.lazy(List.of(variables.get(index), variables.get(0)),
				v -> v[0] == v[1] ? 0d : INF));
		factors.add(Factor.dense(List.of(variables.get(0)), 5d, -1d, 2d, -1d, 4d));
		factors.add(Factor.dense(List.of(variables.get(1)), Double.MIN_VALUE, 0d, 1d, 0d, 2d));
		var steps = assertParity(variables, factors);
		Assert.assertTrue("The equality star must exercise sparse message storage",
			steps.stream().anyMatch(ExactCategoricalSolver.EliminationSnapshot::sparse));
		Assert.assertEquals(125, steps.get(0).high().length);
		Assert.assertEquals(5, java.util.Arrays.stream(steps.get(0).high()).filter(Double::isFinite).count());
	}

	@Test
	public void nearlyDenseLogicalMessagesKeepBoundedStoredMinima() throws Exception {
		Variable x = new Variable("dense-x", 2);
		Variable y = new Variable("dense-y", 2048);
		List<Factor> factors = List.of(Factor.lazy(List.of(x, y),
			v -> v[1] % 5 == 0 || v[0] != (v[1] & 1) ? INF : 0d),
			Factor.dense(List.of(x), 1e16, 1e16),
			Factor.dense(List.of(x), 0.5d, 0d));
		var steps = assertParity(List.of(x, y), factors);
		// Profile compression changes physical density, not any logical minimum.
		Assert.assertTrue(steps.get(0).storedCells() <= 3);
		Assert.assertTrue(java.util.Arrays.stream(steps.get(0).high()).anyMatch(v -> v == INF));
	}

	@Test
	public void signedCancellationRoundedResiduesAndDuplicateOccurrencesMatch() throws Exception {
		Variable x = new Variable("x", 3);
		Variable y = new Variable("y", 2);
		Factor repeated = Factor.dense(List.of(x), 1e16, 1e16, 1e16);
		List<Factor> factors = List.of(repeated, repeated,
			Factor.dense(List.of(y, x), INF, 0d, 0d, 0d, 0d, INF),
			Factor.dense(List.of(x), 1d, 0.5d, 0.25d),
			Factor.dense(List.of(x), -1e16, -1e16, -1e16),
			Factor.dense(List.of(y), Double.MIN_VALUE, -Double.MIN_VALUE));
		assertParity(List.of(x, y), factors);
	}

	@Test
	public void randomOverlappingSupportsAndUnconstrainedAxesMatch() throws Exception {
		Random random = new Random(0x5A125EL);
		for(int trial = 0; trial < 80; trial++) {
			List<Variable> variables = new ArrayList<>();
			for(int index = 0; index < 4; index++)
				variables.add(new Variable("v" + index, 1 + random.nextInt(4)));
			List<Factor> factors = new ArrayList<>();
			for(int index = 1; index < 3; index++) {
				List<Variable> scope = List.of(variables.get(index), variables.get(0));
				double[] values = new double[scope.get(0).domainSize() * scope.get(1).domainSize()];
				for(int cell = 0; cell < values.length; cell++)
					values[cell] = cell == 0 || random.nextBoolean() ? random.nextInt(7) - 3d : INF;
				factors.add(Factor.dense(scope, values));
			}
			factors.add(Factor.lazy(List.of(variables.get(3), variables.get(0)),
				v -> (v[0] - v[1]) * 0.125d));
			factors.add(Factor.dense(List.of(), trial % 2 == 0 ? 1e16 : -0.125d));
			assertParity(variables, factors);
		}
	}

	@Test
	public void changedLazyValuesAreNotRetainedByCompiledSupportIndexes() throws Exception {
		Variable x = new Variable("live-x", 3);
		Variable y = new Variable("live-y", 3);
		AtomicInteger revision = new AtomicInteger();
		List<Factor> factors = List.of(Factor.lazy(List.of(y, x),
			v -> v[0] == (v[1] + revision.get()) % 3 ? 0d : INF),
			Factor.dense(List.of(x), 2d, 0d, 1d));
		var compiled = ExactCategoricalSolver.compilePreferred(List.of(x, y), factors, LIMITS,
			List.of(x.key(), y.key()));
		for(int version = 0; version < 3; version++) {
			revision.set(version);
			Result expected = dense(compiled, factors);
			Result actual = ExactCategoricalSolver.solve(compiled);
			assertResult(expected, actual);
		}
	}

	@Test
	public void eligibilityBoundaryAndLargeOrderedErrorsMatchDense() throws Exception {
		Variable x = new Variable("range-x", 2);
		for(double value : new double[] {Math.scalb(1d, 400), Math.nextUp(Math.scalb(1d, 400)),
			-Math.scalb(1d, 400), -Math.nextUp(Math.scalb(1d, 400)), Double.MAX_VALUE}) {
			List<Factor> factors = List.of(Factor.dense(List.of(x), value, 0d),
				Factor.dense(List.of(x), value, 0d), Factor.dense(List.of(x), INF, 1d));
			assertParity(List.of(x), factors);
		}
		assertParity(List.of(x), List.of(Factor.dense(List.of(x), INF, INF)));
		assertParity(List.of(x), List.of(Factor.dense(List.of(), INF)));
		assertParity(List.of(x), List.of());
	}

	private static List<ExactCategoricalSolver.EliminationSnapshot> assertParity(
		List<Variable> variables, List<Factor> factors) throws Exception {
		var compiled = ExactCategoricalSolver.compilePreferred(variables, factors, LIMITS,
			variables.stream().map(Variable::key).toList());
		Result expected;
		try { expected = dense(compiled, factors); }
		catch(IllegalArgumentException failure) {
			IllegalArgumentException actual = Assert.assertThrows(IllegalArgumentException.class,
				() -> ExactCategoricalSolver.solve(compiled));
			Assert.assertEquals(failure.getMessage(), actual.getMessage());
			return List.of();
		}
		assertResult(expected, ExactCategoricalSolver.solve(compiled));
		List<ExactCategoricalSolver.EliminationSnapshot> dense = new ArrayList<>();
		List<ExactCategoricalSolver.EliminationSnapshot> sparse = new ArrayList<>();
		assertResult(expected, ExactCategoricalSolver.solveWithStepsForTest(compiled,
			(variable, value) -> 0L, dense::add));
		assertResult(expected, ExactCategoricalSolver.solveWithStepsForTest(compiled, null, sparse::add));
		Assert.assertEquals(dense.size(), sparse.size());
		for(int index = 0; index < dense.size(); index++) {
			var reference = dense.get(index);
			var actual = sparse.get(index);
			Assert.assertEquals(reference.variable(), actual.variable());
			Assert.assertArrayEquals(reference.scope(), actual.scope());
			Assert.assertArrayEquals(reference.choices(), actual.choices());
			for(int cell = 0; cell < reference.high().length; cell++) {
				Assert.assertEquals("high step=" + index + " cell=" + cell,
					Double.doubleToRawLongBits(reference.high()[cell]),
					Double.doubleToRawLongBits(actual.high()[cell]));
				Assert.assertEquals("low step=" + index + " cell=" + cell,
					Double.doubleToRawLongBits(reference.low()[cell]),
					Double.doubleToRawLongBits(actual.low()[cell]));
			}
		}
		return sparse;
	}

	private static Result dense(ExactCategoricalSolver.CompiledProblem compiled, List<Factor> factors)
		throws Exception {
		Field field = compiled.getClass().getDeclaredField("prepared");
		field.setAccessible(true);
		Object prepared = field.get(compiled);
		Method method = ExactCategoricalSolver.class.getDeclaredMethod("solve", prepared.getClass(),
			List.class, ExactCategoricalSolver.TieCostFunction.class);
		method.setAccessible(true);
		try {
			return (Result)method.invoke(null, prepared, factors,
				(ExactCategoricalSolver.TieCostFunction)(variable, value) -> 0L);
		}
		catch(InvocationTargetException error) { throw (RuntimeException)error.getCause(); }
	}

	private static void assertResult(Result expected, Result actual) {
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
		Assert.assertEquals(expected.statistics(), actual.statistics());
	}
}
