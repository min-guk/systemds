/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

/**
 * Arithmetic and externally observable golden parity for the dense exact solver kernel.
 * This deliberately does not call DenseFactor.value: optimized kernels may remove it.
 */
public class ExactCategoricalSolverArithmeticParityTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(1_000_000L, 10_000_000L);
	private static final String MODEL_GOLDEN =
		"15479d2899d00720e0e59dca1b695167458c1f631b04a4acb2f532252e448513";
	private static final String ERROR_GOLDEN =
		"48ffeda9ce34f97e03aa8a8c74c44fa9f1a1924108ff41793720316fa1b5150e";

	private static final Class<?> DENSE;
	private static final Constructor<?> DENSE_CONSTRUCTOR;
	private static final Method PRECISE_SUM;
	private static final Method HIGH;
	private static final Method LOW;
	private static final Method TIE;
	private static final Constructor<?> PRECISE_CONSTRUCTOR;
	private static final Method PRECISE_PLUS_OBJECT;
	private static final Method PRECISE_PLUS_COMPONENTS;

	static {
		try {
			DENSE = Class.forName(ExactCategoricalSolver.class.getName() + "$DenseFactor");
			DENSE_CONSTRUCTOR = DENSE.getDeclaredConstructor(int[].class, int[].class,
				double[].class, double[].class, long[].class);
			DENSE_CONSTRUCTOR.setAccessible(true);
			PRECISE_SUM = ExactCategoricalSolver.class.getDeclaredMethod(
				"preciseSum", List.class, int[].class);
			PRECISE_SUM.setAccessible(true);
			Class<?> precise = Class.forName(ExactCategoricalSolver.class.getName() + "$PreciseCost");
			PRECISE_CONSTRUCTOR = precise.getDeclaredConstructor(
				double.class, double.class, long.class);
			PRECISE_PLUS_OBJECT = precise.getDeclaredMethod("plus", precise);
			PRECISE_PLUS_COMPONENTS = precise.getDeclaredMethod(
				"plus", double.class, double.class, long.class);
			HIGH = precise.getDeclaredMethod("high");
			LOW = precise.getDeclaredMethod("low");
			TIE = precise.getDeclaredMethod("tieCost");
			HIGH.setAccessible(true);
			LOW.setAccessible(true);
			TIE.setAccessible(true);
			PRECISE_CONSTRUCTOR.setAccessible(true);
			PRECISE_PLUS_OBJECT.setAccessible(true);
			PRECISE_PLUS_COMPONENTS.setAccessible(true);
		}
		catch(ReflectiveOperationException error) {
			throw new ExceptionInInitializerError(error);
		}
	}

	@Test
	public void primitivePreciseAdditionMatchesLegacyObjectArithmetic() throws Exception {
		Random random = new Random(0xA110CA7EL);
		for(int trial = 0; trial < 2_000; trial++) {
			LegacyCost left = new LegacyCost(randomFinite(random),
				randomFinite(random) * 0x1.0p-48, random.nextInt(1_000_000));
			LegacyCost right = new LegacyCost(randomFinite(random),
				randomFinite(random) * 0x1.0p-48, random.nextInt(1_000_000));
			assertRawEquals(left.plus(right), primitivePlus(left, right));
		}
		for(LegacyCost left : List.of(
			new LegacyCost(+0d, -0d, 7L),
			new LegacyCost(-0d, +0d, 11L),
			new LegacyCost(1.0e16, 1d, 13L),
			new LegacyCost(-1.0e16, -1d, 17L),
			new LegacyCost(Double.POSITIVE_INFINITY, -19d, 23L)))
			for(LegacyCost right : List.of(
				new LegacyCost(+0d, -0d, 29L),
				new LegacyCost(-0d, +0d, 31L),
				new LegacyCost(1d, Math.scalb(1d, -54), 37L),
				new LegacyCost(Double.POSITIVE_INFINITY, 41d, 43L)))
				assertRawEquals(left.plus(right), primitivePlus(left, right));

		Object infinity = PRECISE_CONSTRUCTOR.newInstance(Double.POSITIVE_INFINITY, -7d, 5L);
		Object absorbed = PRECISE_PLUS_OBJECT.invoke(infinity, new Object[] {null});
		assertRawEquals(new LegacyCost(Double.POSITIVE_INFINITY, 0d, 0L), reflectedCost(absorbed));
		assertPrimitivePlusError(new LegacyCost(Double.MAX_VALUE, 0d, Long.MAX_VALUE),
			new LegacyCost(Double.MAX_VALUE, 0d, 1L), "EXACT_VE_OBJECTIVE_OVERFLOW");
		assertPrimitivePlusError(new LegacyCost(1d, 0d, Long.MAX_VALUE),
			new LegacyCost(1d, 0d, 1L), "EXACT_VE_TIE_COST_OVERFLOW");
	}

	@Test
	public void preciseSumMatchesLegacyExpressionOrderForRandomFiniteRows() throws Exception {
		Random random = new Random(0x5EEDC0DEL);
		for(int trial = 0; trial < 500; trial++) {
			int[] domains = {2 + random.nextInt(3), 2 + random.nextInt(3), 2};
			int[] assignment = {random.nextInt(domains[0]), random.nextInt(domains[1]),
				random.nextInt(domains[2])};
			List<LegacyFactor> legacy = new ArrayList<>();
			List<Object> reflected = new ArrayList<>();
			int factorCount = 1 + random.nextInt(8);
			for(int factor = 0; factor < factorCount; factor++) {
				int[][] scopes = {{}, {0}, {1}, {2}, {0, 1}, {1, 0}, {2, 0}, {1, 2, 0}};
				int[] scope = scopes[random.nextInt(scopes.length)].clone();
				int cells = cells(scope, domains);
				double[] high = new double[cells];
				double[] low = random.nextBoolean() ? new double[cells] : null;
				long[] tie = random.nextBoolean() ? new long[cells] : null;
				for(int cell = 0; cell < cells; cell++) {
					high[cell] = randomFinite(random);
					if(low != null)
						low[cell] = randomFinite(random) * 0x1.0p-48;
					if(tie != null)
						tie[cell] = random.nextInt(1_000_001);
				}
				legacy.add(new LegacyFactor(scope, domains, high, low, tie));
				reflected.add(dense(scope, domains, high, low, tie));
			}
			assertRawEquals(legacySum(legacy, assignment), preciseSum(reflected, assignment));
		}
	}

	@Test
	public void preciseSumLocksCancellationSignedZeroAndInfinityEarlyReturn() throws Exception {
		int[] domains = {1};
		int[] assignment = {0};
		List<LegacyFactor> cancellation = List.of(
			factor(domains, 1.0e16, 0d, 7L),
			factor(domains, 1d, Math.scalb(1d, -54), 11L),
			factor(domains, -1.0e16, -0d, 13L),
			factor(domains, Double.MIN_VALUE, -Double.MIN_VALUE, 17L));
		assertReflectionParity(cancellation, assignment);

		for(double high : new double[] {0d, -0d})
			for(double low : new double[] {0d, -0d})
				assertReflectionParity(List.of(new LegacyFactor(new int[] {0}, domains,
					new double[] {high}, new double[] {low}, new long[] {3L})), assignment);

		List<LegacyFactor> infinity = List.of(
			factor(domains, 9d, Math.scalb(1d, -50), 19L),
			factor(domains, Double.POSITIVE_INFINITY, -17d, 23L),
			factor(domains, 1d, 2d, 29L));
		LegacyCost actual = reflectionSum(infinity, assignment);
		assertRawEquals(new LegacyCost(Double.POSITIVE_INFINITY, -17d, 23L), actual);
	}

	@Test
	public void objectiveOverflowPrecedesTieOverflowAndTieOverflowRemainsExact() throws Exception {
		int[] domains = {1};
		List<LegacyFactor> objectiveFirst = List.of(
			factor(domains, Double.MAX_VALUE, 0d, Long.MAX_VALUE),
			factor(domains, Double.MAX_VALUE, 0d, 1L));
		assertPreciseSumError(objectiveFirst, "EXACT_VE_OBJECTIVE_OVERFLOW");

		List<LegacyFactor> tieOnly = List.of(
			factor(domains, 1d, 0d, Long.MAX_VALUE), factor(domains, 1d, 0d, 1L));
		assertPreciseSumError(tieOnly, "EXACT_VE_TIE_COST_OVERFLOW");
	}

	@Test
	public void zeroContributionsPreserveRawResiduesAndSecondaryErrors() throws Exception {
		int[] domains = {1};
		Random random = new Random(0x20C057L);
		for(int trial = 0; trial < 500; trial++) {
			List<LegacyFactor> factors = new ArrayList<>();
			for(int index = 0; index < 12; index++) {
				double high = randomFinite(random);
				double low = index % 3 == 0 ? 0d : randomFinite(random) * 0x1.0p-48;
				factors.add(factor(domains, high, low, random.nextInt(10)));
				// Zero factors before/after non-normalized input residues must retain
				// the legacy normalization, signed-zero and additive tie behavior.
				for(double zeroHigh : new double[] {0d, -0d})
					for(double zeroLow : new double[] {0d, -0d})
						factors.add(factor(domains, zeroHigh, zeroLow, random.nextInt(10)));
			}
			assertReflectionParity(factors, new int[] {0});
		}
		assertPreciseSumError(List.of(factor(domains, 9d, 0d, Long.MAX_VALUE),
			factor(domains, -0d, -0d, 1L)), "EXACT_VE_TIE_COST_OVERFLOW");
	}

	@Test
	public void solveInvokesTieCallbackOnceForEveryEliminationCellValue() {
		var a = new ExactCategoricalSolver.Variable("callback-a", 2);
		var b = new ExactCategoricalSolver.Variable("callback-b", 3);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(b, a),
				0d, 1d, 0d, Double.POSITIVE_INFINITY, 2d, 0d));
		Map<String,Integer> calls = new LinkedHashMap<>();
		List<String> sequence = new ArrayList<>();
		ExactCategoricalSolver.solve(List.of(a, b), factors, LIMITS, (variable, value) -> {
			sequence.add(variable.key() + '=' + value);
			calls.merge(variable.key() + '=' + value, 1, Integer::sum);
			return 0L;
		});
		Assert.assertEquals(Map.of("callback-a=0", 1, "callback-a=1", 1,
			"callback-b=0", 2, "callback-b=1", 2, "callback-b=2", 2), calls);
		Assert.assertEquals(List.of("callback-b=0", "callback-b=1", "callback-b=2",
			"callback-b=0", "callback-b=1", "callback-b=2",
			"callback-a=0", "callback-a=1"), sequence);
	}

	@Test
	public void negativeTieCostFailsAtItsFirstEliminationCell() {
		var value = new ExactCategoricalSolver.Variable("negative-tie", 2);
		List<String> calls = new ArrayList<>();
		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.solve(List.of(value), List.of(
				ExactCategoricalSolver.Factor.dense(List.of(value), 0d, 1d)), LIMITS,
				(variable, selected) -> {
					calls.add(variable.key() + '=' + selected);
					return -1L;
				}));
		Assert.assertEquals("EXACT_VE_TIE_COST_INVALID", error.getMessage());
		Assert.assertEquals(List.of("negative-tie=0"), calls);
	}

	@Test
	public void finiteNegativePrimaryCostsRemainSupported() {
		var value = new ExactCategoricalSolver.Variable("negative-primary", 2);
		ExactCategoricalSolver.Result result = ExactCategoricalSolver.solve(List.of(value),
			List.of(ExactCategoricalSolver.Factor.dense(List.of(value), -7d, -3d)), LIMITS);
		Assert.assertEquals(Long.toHexString(Double.doubleToRawLongBits(-7d)),
			Long.toHexString(Double.doubleToRawLongBits(result.objective())));
		Assert.assertEquals(List.of(0), result.assignmentInVariableOrder());
	}

	@Test
	public void fixedSeedHeterogeneousSolveGoldenLocksObjectiveAssignmentAndStatistics() {
		StringBuilder transcript = new StringBuilder();
		Random random = new Random(0x1357A11L);
		int nonZeroAssignments = 0;
		java.util.Set<List<Integer>> distinctAssignments = new java.util.HashSet<>();
		for(int trial = 0; trial < 64; trial++) {
			int count = 1 + random.nextInt(4);
			List<ExactCategoricalSolver.Variable> variables = new ArrayList<>();
			for(int variable = 0; variable < count; variable++)
				variables.add(new ExactCategoricalSolver.Variable(
					"t" + trial + "-v" + variable, 1 + random.nextInt(3)));
			int[] witness = new int[count];
			for(int variable = 0; variable < count; variable++)
				witness[variable] = random.nextInt(variables.get(variable).domainSize());
			List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
			factors.add(ExactCategoricalSolver.Factor.dense(List.of(),
				trial % 5 == 0 ? 1.0e16 : random.nextInt(4)));
			for(int variable = 0; variable < count; variable++) {
				double[] values = new double[variables.get(variable).domainSize()];
				for(int value = 0; value < values.length; value++)
					values[value] = value == witness[variable]
						? -50d + Math.scalb(variable + 1d, -45)
						: random.nextInt(9) + Math.scalb(value + 1d, -48);
				factors.add(ExactCategoricalSolver.Factor.dense(
					List.of(variables.get(variable)), values));
			}
			for(int right = 1; right < count; right++) {
				var scope = List.of(variables.get(right), variables.get(right - 1));
				double[] values = new double[scope.get(0).domainSize() * scope.get(1).domainSize()];
				int witnessCell = witness[right] * scope.get(1).domainSize() + witness[right - 1];
				for(int cell = 0; cell < values.length; cell++) {
					if(cell == witnessCell)
						values[cell] = -5d + Math.scalb(right, -50);
					else
						values[cell] = random.nextInt(13) == 0 ? Double.POSITIVE_INFINITY
							: random.nextInt(7) - 3d + Math.scalb(cell + 1d, -52);
				}
				factors.add(ExactCategoricalSolver.Factor.dense(scope, values));
			}
			ExactCategoricalSolver.Result result = ExactCategoricalSolver.solve(
				variables, factors, LIMITS,
				(variable, value) -> (variable.key().hashCode() & 3) + value);
			if(result.assignmentInVariableOrder().stream().anyMatch(value -> value != 0))
				nonZeroAssignments++;
			distinctAssignments.add(result.assignmentInVariableOrder());
			transcript.append(trial).append('|')
				.append(Long.toHexString(Double.doubleToRawLongBits(result.objective()))).append('|')
				.append(result.assignmentInVariableOrder()).append('|')
				.append(result.statistics()).append('\n');
		}
		Assert.assertTrue("golden corpus must exercise selected non-zero values",
			nonZeroAssignments >= 20);
		Assert.assertTrue("golden corpus must exercise heterogeneous assignments",
			distinctAssignments.size() >= 12);
		var left = new ExactCategoricalSolver.Variable("scope-left", 2);
		var right = new ExactCategoricalSolver.Variable("scope-right", 3);
		List<ExactCategoricalSolver.Variable> variables = List.of(left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(right, left),
				9d, 1d, 4d, 7d, 2d, 8d),
			ExactCategoricalSolver.Factor.dense(List.of(), 3d));
		for(ExactCategoricalSolver.Result result : List.of(
			ExactCategoricalSolver.solve(variables, factors, LIMITS),
			ExactCategoricalSolver.solve(ExactCategoricalSolver.compilePreferred(
				variables, factors, LIMITS, List.of(left.key(), right.key()))),
			ExactCategoricalSolver.solve(ExactCategoricalSolver.compilePreferred(
				variables, factors, LIMITS, List.of(right.key(), left.key())))))
			transcript.append("scope-order|")
				.append(Long.toHexString(Double.doubleToRawLongBits(result.objective()))).append('|')
				.append(result.assignmentInVariableOrder()).append('|')
				.append(result.statistics()).append('\n');
		Assert.assertEquals(MODEL_GOLDEN, sha256(transcript.toString()));
	}

	@Test
	public void fixedErrorOrderingGoldenLocksMessagesAndCauses() {
		StringBuilder errors = new StringBuilder();
		var negative = new ExactCategoricalSolver.Variable("negative", 1);
		capture(errors, () -> ExactCategoricalSolver.solve(List.of(negative),
			List.of(ExactCategoricalSolver.Factor.dense(List.of(negative), -1d)), LIMITS));
		var signedZero = new ExactCategoricalSolver.Variable("signed-zero", 1);
		capture(errors, () -> ExactCategoricalSolver.solve(List.of(signedZero),
			List.of(ExactCategoricalSolver.Factor.dense(List.of(signedZero), -0d)), LIMITS));
		for(double invalid : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, -0d}) {
			var dense = new ExactCategoricalSolver.Variable("dense-" + invalid, 1);
			capture(errors, () -> ExactCategoricalSolver.solve(List.of(dense),
				List.of(ExactCategoricalSolver.Factor.dense(List.of(dense), invalid)), LIMITS));
			var lazy = new ExactCategoricalSolver.Variable("lazy-" + invalid, 1);
			capture(errors, () -> ExactCategoricalSolver.solve(List.of(lazy),
				List.of(ExactCategoricalSolver.Factor.lazy(List.of(lazy), values -> invalid)), LIMITS));
		}
		var negativeTie = new ExactCategoricalSolver.Variable("negative-tie-error", 1);
		capture(errors, () -> ExactCategoricalSolver.solve(List.of(negativeTie),
			List.of(ExactCategoricalSolver.Factor.dense(List.of(negativeTie), 0d)), LIMITS,
			(variable, value) -> -1L));
		var overflowA = new ExactCategoricalSolver.Variable("overflow-a", 1);
		var overflowB = new ExactCategoricalSolver.Variable("overflow-b", 1);
		capture(errors, () -> ExactCategoricalSolver.solve(List.of(overflowA, overflowB), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(overflowA), Double.MAX_VALUE),
			ExactCategoricalSolver.Factor.dense(List.of(overflowB), Double.MAX_VALUE)), LIMITS,
			(variable, value) -> Long.MAX_VALUE));
		capture(errors, () -> ExactCategoricalSolver.solve(List.of(overflowA, overflowB), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(overflowA), 0d),
			ExactCategoricalSolver.Factor.dense(List.of(overflowB), 0d)), LIMITS,
			(variable, value) -> Long.MAX_VALUE));
		Assert.assertEquals(ERROR_GOLDEN, sha256(errors.toString()));
	}

	private static void capture(StringBuilder transcript, ThrowingRunnable runnable) {
		try {
			runnable.run();
			transcript.append("NO_ERROR\n");
		}
		catch(Exception error) {
			transcript.append(error.getClass().getName()).append('|').append(error.getMessage())
				.append('|').append(error.getCause() == null ? "null" : error.getCause().getClass().getName())
				.append('\n');
		}
	}

	private static void assertReflectionParity(List<LegacyFactor> factors, int[] assignment)
		throws Exception {
		assertRawEquals(legacySum(factors, assignment), reflectionSum(factors, assignment));
	}

	private static LegacyCost reflectionSum(List<LegacyFactor> factors, int[] assignment)
		throws Exception {
		List<Object> reflected = new ArrayList<>();
		for(LegacyFactor factor : factors)
			reflected.add(dense(factor.scope, factor.domains, factor.high, factor.low, factor.tie));
		return preciseSum(reflected, assignment);
	}

	private static void assertPreciseSumError(List<LegacyFactor> factors, String expected)
		throws Exception {
		InvocationTargetException error = Assert.assertThrows(InvocationTargetException.class,
			() -> reflectionSum(factors, new int[] {0}));
		Assert.assertEquals(IllegalArgumentException.class, error.getCause().getClass());
		Assert.assertEquals(expected, error.getCause().getMessage());
	}

	private static Object dense(int[] scope, int[] domains, double[] high, double[] low,
		long[] tie) throws ReflectiveOperationException {
		return DENSE_CONSTRUCTOR.newInstance(scope, domains, high, low, tie);
	}

	private static LegacyCost preciseSum(List<Object> factors, int[] assignment) throws Exception {
		Object precise;
		try {
			precise = PRECISE_SUM.invoke(null, factors, assignment);
		}
		catch(InvocationTargetException error) {
			throw error;
		}
		return reflectedCost(precise);
	}

	private static LegacyCost primitivePlus(LegacyCost left, LegacyCost right) throws Exception {
		Object precise = PRECISE_CONSTRUCTOR.newInstance(left.high, left.low, left.tie);
		return reflectedCost(PRECISE_PLUS_COMPONENTS.invoke(
			precise, right.high, right.low, right.tie));
	}

	private static LegacyCost reflectedCost(Object precise) throws Exception {
		return new LegacyCost((double) HIGH.invoke(precise), (double) LOW.invoke(precise),
			(long) TIE.invoke(precise));
	}

	private static void assertPrimitivePlusError(LegacyCost left, LegacyCost right,
		String expected) throws Exception {
		Object precise = PRECISE_CONSTRUCTOR.newInstance(left.high, left.low, left.tie);
		InvocationTargetException error = Assert.assertThrows(InvocationTargetException.class,
			() -> PRECISE_PLUS_COMPONENTS.invoke(precise, right.high, right.low, right.tie));
		Assert.assertEquals(IllegalArgumentException.class, error.getCause().getClass());
		Assert.assertEquals(expected, error.getCause().getMessage());
	}

	private static LegacyCost legacySum(List<LegacyFactor> factors, int[] assignment) {
		LegacyCost total = new LegacyCost(0d, 0d, 0L);
		for(LegacyFactor factor : factors) {
			LegacyCost value = factor.at(assignment);
			if(value.high == Double.POSITIVE_INFINITY)
				return value;
			total = total.plus(value);
		}
		return total;
	}

	private static void assertRawEquals(LegacyCost expected, LegacyCost actual) {
		Assert.assertEquals(Long.toHexString(Double.doubleToRawLongBits(expected.high)),
			Long.toHexString(Double.doubleToRawLongBits(actual.high)));
		Assert.assertEquals(Long.toHexString(Double.doubleToRawLongBits(expected.low)),
			Long.toHexString(Double.doubleToRawLongBits(actual.low)));
		Assert.assertEquals(expected.tie, actual.tie);
	}

	private static LegacyFactor factor(int[] domains, double high, double low, long tie) {
		return new LegacyFactor(new int[] {0}, domains, new double[] {high},
			new double[] {low}, new long[] {tie});
	}

	private static int cells(int[] scope, int[] domains) {
		int cells = 1;
		for(int variable : scope)
			cells *= domains[variable];
		return cells;
	}

	private static double randomFinite(Random random) {
		return switch(random.nextInt(10)) {
			case 0 -> 0d;
			case 1 -> -0d;
			case 2 -> 1.0e16;
			case 3 -> -1.0e16;
			case 4 -> Double.MIN_VALUE;
			default -> Math.scalb(random.nextInt(2_000_001) - 1_000_000,
				random.nextInt(81) - 40);
		};
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(64);
			for(byte item : digest)
				result.append(String.format("%02x", item & 0xff));
			return result.toString();
		}
		catch(Exception error) {
			throw new AssertionError(error);
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable { void run() throws Exception; }

	private record LegacyFactor(int[] scope, int[] domains, double[] high, double[] low,
		long[] tie) {
		private LegacyCost at(int[] assignment) {
			int cell = 0;
			for(int index = 0; index < scope.length; index++) {
				int stride = 1;
				for(int right = index + 1; right < scope.length; right++)
					stride *= domains[scope[right]];
				cell += assignment[scope[index]] * stride;
			}
			return new LegacyCost(high[cell], low == null ? 0d : low[cell],
				tie == null ? 0L : tie[cell]);
		}
	}

	/** Test-only copy of the legacy arithmetic, kept expression-for-expression. */
	private record LegacyCost(double high, double low, long tie) {
		private LegacyCost plus(LegacyCost that) {
			if(high == Double.POSITIVE_INFINITY || that.high == Double.POSITIVE_INFINITY)
				return new LegacyCost(Double.POSITIVE_INFINITY, 0d, 0L);
			double sum = high + that.high;
			if(!Double.isFinite(sum))
				throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double virtual = sum - high;
			double error = (high - (sum - virtual)) + (that.high - virtual);
			error += low + that.low;
			if(!Double.isFinite(error))
				throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double normalizedHigh = sum + error;
			if(!Double.isFinite(normalizedHigh))
				throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double normalizedLow = error - (normalizedHigh - sum);
			long combinedTie;
			try {
				combinedTie = Math.addExact(tie, that.tie);
			}
			catch(ArithmeticException ex) {
				throw new IllegalArgumentException("EXACT_VE_TIE_COST_OVERFLOW", ex);
			}
			return new LegacyCost(normalizedHigh, normalizedLow, combinedTie);
		}
	}
}
