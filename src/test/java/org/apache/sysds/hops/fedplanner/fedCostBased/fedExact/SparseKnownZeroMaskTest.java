/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.EliminationSnapshot;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Result;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

public class SparseKnownZeroMaskTest {
	private static final Limits LIMITS = new Limits(1_000_000, 10_000_000);
	private static final double INF = Double.POSITIVE_INFINITY;

	@Test
	public void zeroConstraintAfterRetainedResidueMatchesDenseArithmeticOrder() throws Exception {
		Variable x = new Variable("residue-x", 2);
		Variable y = new Variable("residue-y", 2);
		assertDenseSparseParity(List.of(x, y), List.of(
			Factor.lazy(List.of(x, y), values -> 1e16),
			Factor.lazy(List.of(x, y), values -> values[0] == 0 ? 0.5d : 0.25d),
			Factor.lazy(List.of(x, y), values -> values[0] == values[1] ? 0d : INF),
			Factor.dense(List.of(x), -1e16, -1e16)));
	}

	@Test
	public void majorityFiniteZeroConstraintOmittedFromJoinStillRejectsInfinity() throws Exception {
		Variable x = new Variable("majority-x", 5);
		Variable y = new Variable("majority-y", 5);
		List<Factor> factors = List.of(
			Factor.lazy(List.of(x, y), values -> values[0] == values[1] ? 0d : INF),
			Factor.dense(List.of(x), 0d, 0d, 0d, 0d, INF),
			Factor.dense(List.of(x), 5d, 4d, 3d, 2d, -100d));
		Result actual = assertDenseSparseParity(List.of(x, y), factors);
		Assert.assertEquals(2d, actual.objective(), 0d);
		Assert.assertEquals(List.of(3, 3), actual.assignmentInVariableOrder());
	}

	@Test
	public void packedMajorityFiniteConstraintComposesWithKnownZeroRun() {
		Variable x = new Variable("packed-majority-x", 2);
		Variable y = new Variable("packed-majority-y", 2);
		Factor denseMajority = Factor.dense(List.of(x, y), 0d, 0d, 0d, INF);
		Factor packedMajority = Factor.lazy(List.of(x, y), values ->
			values[0] == 1 && values[1] == 1 ? INF : 0d);
		Factor denseSelective = Factor.dense(List.of(x), 0d, INF);
		Factor packedSelective = Factor.lazy(List.of(x), values -> values[0] == 0 ? 0d : INF);
		Factor denseZero = Factor.dense(List.of(x), 0d, 0d);
		Factor packedZero = Factor.lazy(List.of(x), values -> 0d);
		Factor numeric = Factor.dense(List.of(y), 7d, 3d);

		Result expected = ExactCategoricalSolver.solve(ExactCategoricalSolver.compilePreferred(
			List.of(x, y), List.of(denseMajority, denseSelective, denseZero, numeric), LIMITS,
			List.of("packed-majority-x", "packed-majority-y")));
		Result actual = ExactCategoricalSolver.solve(ExactCategoricalSolver.compilePreferred(
			List.of(x, y), List.of(packedMajority, packedSelective, packedZero, numeric), LIMITS,
			List.of("packed-majority-x", "packed-majority-y")));

		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
		Assert.assertEquals(3d, actual.objective(), 0d);
		Assert.assertEquals(List.of(0, 1), actual.assignmentInVariableOrder());
	}

	@Test
	public void projectedAndValueMappedSparseFactorsMatchDenseMessages() throws Exception {
		Variable u = new Variable("mapped-u", 2);
		Variable x = new Variable("mapped-x", 4);
		Variable y = new Variable("mapped-y", 2);
		Variable z = new Variable("mapped-z", 2);
		List<EliminationSnapshot> sparse = assertDenseSparseSteps(List.of(u, x, y, z), List.of(
			Factor.lazy(List.of(x, u), values -> values[0] / 2 == values[1] ? 0d : INF),
			Factor.lazy(List.of(u, y), values -> values[0] == values[1] ? 0d : INF),
			Factor.lazy(List.of(x, z), values -> values[0] % 2 == values[1] ? 0d : INF),
			Factor.dense(List.of(y), 1d, 0d),
			Factor.dense(List.of(z), 1d, 0d)));
		Assert.assertTrue(sparse.stream().anyMatch(EliminationSnapshot::sparse));
		Assert.assertTrue(sparse.stream().anyMatch(step -> step.storedCells() < step.high().length));
	}

	@Test
	public void onlyRawPositiveZeroWithZeroSecondaryWordsIsClassified() throws Exception {
		Assert.assertTrue(classifiesAsExactFiniteZero(new double[] {0d, INF},
			new double[] {0d, 7d}, new long[] {0L, 9L}));
		Assert.assertFalse(classifiesAsExactFiniteZero(new double[] {-0d}, null, null));
		Assert.assertFalse(classifiesAsExactFiniteZero(new double[] {0d},
			new double[] {Double.MIN_VALUE}, null));
		Assert.assertFalse(classifiesAsExactFiniteZero(new double[] {0d}, null, new long[] {1L}));
		Assert.assertFalse(classifiesAsExactFiniteZero(new double[] {1d}, null, null));
	}

	@Test
	public void valuesOutsideSparseRangeRetainDenseOverflowBehavior() throws Exception {
		Variable x = new Variable("overflow-x", 2);
		List<Factor> factors = List.of(
			Factor.lazy(List.of(x), values -> values[0] == 0 ? 0d : INF),
			Factor.dense(List.of(x), Double.MAX_VALUE, Double.MAX_VALUE),
			Factor.dense(List.of(x), Double.MAX_VALUE, Double.MAX_VALUE));
		var compiled = compile(List.of(x), factors);
		IllegalArgumentException expected = Assert.assertThrows(IllegalArgumentException.class,
			() -> dense(compiled, factors, null));
		IllegalArgumentException actual = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.solve(compiled));
		Assert.assertEquals(expected.getMessage(), actual.getMessage());
	}

	@Test
	public void consecutiveKnownZeroRunsPreserveDenseRawArithmeticAndChoices() throws Exception {
		Variable x = new Variable("zero-run-x", 3);
		Variable y = new Variable("zero-run-y", 3);
		List<Factor> factors = new ArrayList<>();
		factors.add(Factor.lazy(List.of(x, y), values -> 1e16));
		for(int index = 0; index < 9; index++)
			factors.add(Factor.lazy(List.of(x, y), values ->
				values[0] == values[1] ? 0d : INF));
		factors.add(Factor.lazy(List.of(x, y), values -> values[0] * 0.25d + 0.5d));
		for(int index = 0; index < 7; index++)
			factors.add(Factor.dense(List.of(x), 0d, 0d, 0d));
		factors.add(Factor.dense(List.of(x), -1e16, -1e16, -1e16));
		assertDenseSparseParity(List.of(x, y), factors);
	}

	@Test
	public void knownZeroTokensPreserveFactorPositionsAndRunLengths() throws Exception {
		Method method = ExactCategoricalSolver.class.getDeclaredMethod(
			"knownZeroFactorTokens", boolean[].class);
		method.setAccessible(true);
		Assert.assertArrayEquals(new int[] {-2, 2, -3, 6}, (int[])method.invoke(null,
			(Object)new boolean[] {true, true, false, true, true, true, false}));
		Assert.assertArrayEquals(new int[] {0, 1}, (int[])method.invoke(null,
			(Object)new boolean[] {false, false}));
	}

	@Test
	public void zeroRunUsesRawFixedPointAndFallsBackForUnnormalizedState() throws Exception {
		long high = 0x44a0274290aa8444L;
		long low = 0x44d7de2a509cd3edL;
		double initialHigh = Double.longBitsToDouble(high);
		double initialLow = Double.longBitsToDouble(low);
		double[] once = repeatExactZero(initialHigh, initialLow, 1);
		double[] twice = repeatExactZero(initialHigh, initialLow, 2);
		Assert.assertNotEquals("the supplied first transition is deliberately not a fixed point",
			Double.doubleToRawLongBits(once[0]), Double.doubleToRawLongBits(twice[0]));
		for(int length : List.of(1, 2, 8)) {
			double[] expected = repeatExactZero(initialHigh, initialLow, length);
			Object actual = addKnownZeroRun(initialHigh, initialLow, 17L, length);
			Assert.assertEquals(Double.doubleToRawLongBits(expected[0]), rawComponent(actual, "high"));
			Assert.assertEquals(Double.doubleToRawLongBits(expected[1]), rawComponent(actual, "low"));
			Assert.assertEquals(17L, longComponent(actual, "tieCost"));
		}
	}

	@Test
	public void zeroRunPreservesSignedZeroInfinityNanAndOverflowBehavior() throws Exception {
		Object signed = addKnownZeroRun(-0d, -0d, 29L, 5);
		Assert.assertEquals(Double.doubleToRawLongBits(+0d), rawComponent(signed, "high"));
		Assert.assertEquals(Double.doubleToRawLongBits(+0d), rawComponent(signed, "low"));
		Assert.assertEquals(29L, longComponent(signed, "tieCost"));

		Object infinity = addKnownZeroRun(INF, 0d, 3L, 2);
		Assert.assertEquals(Double.doubleToRawLongBits(INF), rawComponent(infinity, "high"));
		long nanBits = 0x7ff8000000000042L;
		Object nan = addKnownZeroRun(Double.longBitsToDouble(nanBits), 0d, 5L, 2);
		double[] expectedNan = repeatExactZero(Double.longBitsToDouble(nanBits), 0d, 2);
		Assert.assertEquals(Double.doubleToRawLongBits(expectedNan[0]), rawComponent(nan, "high"));

		InvocationTargetException overflow = Assert.assertThrows(InvocationTargetException.class,
			() -> addKnownZeroRun(INF, Double.MIN_VALUE, 0L, 1));
		Assert.assertEquals("EXACT_VE_OBJECTIVE_OVERFLOW", overflow.getCause().getMessage());
	}

	private static Result assertDenseSparseParity(List<Variable> variables, List<Factor> factors)
		throws Exception {
		var compiled = compile(variables, factors);
		Result expected = dense(compiled, factors, null);
		Result actual = ExactCategoricalSolver.solve(compiled);
		assertResult(expected, actual);
		assertDenseSparseSteps(compiled, factors);
		return actual;
	}

	private static List<EliminationSnapshot> assertDenseSparseSteps(List<Variable> variables,
		List<Factor> factors) throws Exception {
		var compiled = compile(variables, factors);
		assertResult(dense(compiled, factors, null), ExactCategoricalSolver.solve(compiled));
		return assertDenseSparseSteps(compiled, factors);
	}

	private static List<EliminationSnapshot> assertDenseSparseSteps(
		ExactCategoricalSolver.CompiledProblem compiled, List<Factor> factors) throws Exception {
		List<EliminationSnapshot> dense = new ArrayList<>();
		List<EliminationSnapshot> sparse = new ArrayList<>();
		Result expected = dense(compiled, factors, dense);
		Result actual = ExactCategoricalSolver.solveWithStepsForTest(compiled, null, sparse::add);
		assertResult(expected, actual);
		Assert.assertEquals(dense.size(), sparse.size());
		for(int step = 0; step < dense.size(); step++) {
			EliminationSnapshot expectedStep = dense.get(step);
			EliminationSnapshot actualStep = sparse.get(step);
			Assert.assertEquals(expectedStep.variable(), actualStep.variable());
			Assert.assertArrayEquals(expectedStep.scope(), actualStep.scope());
			Assert.assertArrayEquals(expectedStep.choices(), actualStep.choices());
			Assert.assertEquals(expectedStep.high().length, actualStep.high().length);
			for(int cell = 0; cell < expectedStep.high().length; cell++) {
				Assert.assertEquals("high step=" + step + " cell=" + cell,
					Double.doubleToRawLongBits(expectedStep.high()[cell]),
					Double.doubleToRawLongBits(actualStep.high()[cell]));
				Assert.assertEquals("low step=" + step + " cell=" + cell,
					Double.doubleToRawLongBits(expectedStep.low()[cell]),
					Double.doubleToRawLongBits(actualStep.low()[cell]));
			}
		}
		return sparse;
	}

	private static ExactCategoricalSolver.CompiledProblem compile(List<Variable> variables,
		List<Factor> factors) {
		return ExactCategoricalSolver.compilePreferred(variables, factors, LIMITS,
			variables.stream().map(Variable::key).toList());
	}

	private static Result dense(ExactCategoricalSolver.CompiledProblem compiled,
		List<Factor> factors, List<EliminationSnapshot> snapshots) throws Exception {
		Field field = compiled.getClass().getDeclaredField("prepared");
		field.setAccessible(true);
		Object prepared = field.get(compiled);
		Method method = ExactCategoricalSolver.class.getDeclaredMethod("solve", prepared.getClass(),
			List.class, ExactCategoricalSolver.TieCostFunction.class, java.util.function.Consumer.class);
		method.setAccessible(true);
		try {
			return (Result)method.invoke(null, prepared, factors,
				(ExactCategoricalSolver.TieCostFunction)(variable, value) -> 0L,
				snapshots == null ? null : (java.util.function.Consumer<EliminationSnapshot>)snapshots::add);
		}
		catch(InvocationTargetException error) {
			throw (RuntimeException)error.getCause();
		}
	}

	private static boolean classifiesAsExactFiniteZero(double[] high, double[] low, long[] tie)
		throws Exception {
		Class<?> denseFactor = Class.forName(ExactCategoricalSolver.class.getName() + "$DenseFactor");
		Constructor<?> constructor = denseFactor.getDeclaredConstructor(int[].class, int[].class,
			double[].class, double[].class, long[].class);
		constructor.setAccessible(true);
		Object factor = constructor.newInstance(new int[] {0}, new int[] {high.length}, high, low, tie);
		Method classifier = denseFactor.getDeclaredMethod("finiteValuesAreExactPositiveZero");
		classifier.setAccessible(true);
		return (boolean)classifier.invoke(factor);
	}

	private static Object addKnownZeroRun(double high, double low, long tie, int length)
		throws Exception {
		Method method = ExactCategoricalSolver.class.getDeclaredMethod(
			"addKnownZeroRun", double.class, double.class, long.class, int.class);
		method.setAccessible(true);
		return method.invoke(null, high, low, tie, length);
	}

	private static long rawComponent(Object cost, String name) throws Exception {
		Method component = cost.getClass().getDeclaredMethod(name);
		component.setAccessible(true);
		return Double.doubleToRawLongBits((double)component.invoke(cost));
	}

	private static long longComponent(Object cost, String name) throws Exception {
		Method component = cost.getClass().getDeclaredMethod(name);
		component.setAccessible(true);
		return (long)component.invoke(cost);
	}

	private static double[] repeatExactZero(double high, double low, int length) {
		for(int remaining = length; remaining > 0; remaining--) {
			if(low == 0d) {
				high += 0d;
				low = 0d;
			}
			else {
				double sum = high + 0d;
				if(!Double.isFinite(sum))
					throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
				double virtual = sum - high;
				double error = (high - (sum - virtual)) + (0d - virtual);
				error += low;
				if(!Double.isFinite(error))
					throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
				double normalizedHigh = sum + error;
				if(!Double.isFinite(normalizedHigh))
					throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
				low = error - (normalizedHigh - sum);
				high = normalizedHigh;
			}
		}
		return new double[] {high, low};
	}

	private static void assertResult(Result expected, Result actual) {
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
		Assert.assertEquals(expected.statistics(), actual.statistics());
	}
}
