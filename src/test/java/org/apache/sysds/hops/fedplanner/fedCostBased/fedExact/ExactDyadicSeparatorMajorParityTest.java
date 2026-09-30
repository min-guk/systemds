package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.Assert;
import org.junit.Test;

public class ExactDyadicSeparatorMajorParityTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(1_000_000, 10_000_000);
	private static final double INF = Double.POSITIVE_INFINITY;
	private static final BigInteger BASE = BigInteger.ONE.shiftLeft(53);

	@Test
	public void separatorMajorMatchesIndependentReferenceAcrossAxisOrders() throws Exception {
		var a = new ExactCategoricalSolver.Variable("a", 4);
		var b = new ExactCategoricalSolver.Variable("b", 3);
		var c = new ExactCategoricalSolver.Variable("c", 5);
		var d = new ExactCategoricalSolver.Variable("d", 2);
		var free = new ExactCategoricalSolver.Variable("free", 3);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c, d, free);
		int[] ap = {1, 0, 1, 2};
		int[] bp = {2, 0, 1};
		int[] cp = {0, 1, 0, 2, 1};
		var repeated = ExactCategoricalSolver.Factor.lazy(List.of(c, a, b), v ->
			v[0] == 3 && v[1] == 3 && v[2] == 2 ? INF
				: 0x1p53 + (ap[v[1]] * 7 + bp[v[2]] * 3 + cp[v[0]]) * .25);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			repeated, repeated,
			ExactCategoricalSolver.Factor.lazy(List.of(d, b), v ->
				v[0] == 1 && v[1] == 1 ? INF : (v[0] * 5 + bp[v[1]]) * .25),
			ExactCategoricalSolver.Factor.lazy(List.of(a, d), v ->
				(ap[v[0]] * 2 + v[1]) * .5),
			ExactCategoricalSolver.Factor.dense(List.of(a), .75, .25, .75, .5),
			ExactCategoricalSolver.Factor.dense(List.of(), .25));
		boolean quotient = false;
		for(List<String> order : List.of(
			List.of("a", "b", "c", "d", "free"),
			List.of("c", "a", "d", "b", "free"),
			List.of("free", "d", "c", "b", "a")))
			quotient |= assertParity(variables, factors, order, -2, 80);
		Assert.assertTrue("fixture must exercise quotient message storage", quotient);
	}

	@Test
	public void subnormalSingletonScalarAndAllZeroLowWordsMatch() throws Exception {
		var x = new ExactCategoricalSolver.Variable("x", 3);
		var singleton = new ExactCategoricalSolver.Variable("singleton", 1);
		var free = new ExactCategoricalSolver.Variable("free-subnormal", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, singleton, free);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x),
				2 * Double.MIN_VALUE, Double.MIN_VALUE, Double.MIN_VALUE),
			ExactCategoricalSolver.Factor.dense(List.of(singleton, x),
				0d, Double.MIN_VALUE, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(), Double.MIN_VALUE));
		assertParity(variables, factors,
			List.of("singleton", "x", "free-subnormal"), -1074, 16);
		assertParity(variables, factors,
			List.of("free-subnormal", "x", "singleton"), -1074, 16);
	}

	@Test
	public void majorityFiniteMissingCellsAndOriginalOrdinalTiesMatch() throws Exception {
		var x = new ExactCategoricalSolver.Variable("x", 4);
		var y = new ExactCategoricalSolver.Variable("y", 3);
		var z = new ExactCategoricalSolver.Variable("z", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, y, z);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.lazy(List.of(y, x), v ->
				v[0] == 2 && v[1] == 3 ? INF : (v[1] == 0 || v[1] == 2 ? 1 : 2)),
			ExactCategoricalSolver.Factor.lazy(List.of(x, z), v ->
				v[0] == 1 && v[1] == 1 ? INF : (v[0] == 0 || v[0] == 2 ? .25 : .5)),
			ExactCategoricalSolver.Factor.dense(List.of(y), .5, 0, .5));
		assertParity(variables, factors, List.of("x", "y", "z"), -2, 32);
		assertParity(variables, factors, List.of("z", "x", "y"), -2, 32);
	}

	@Test
	public void supportsEmptyCoversSparseMissesAndDenseAccumulatorCrossover() throws Exception {
		var x = new ExactCategoricalSolver.Variable("cross-x", 2);
		var y = new ExactCategoricalSolver.Variable("cross-y", 100);
		List<ExactCategoricalSolver.Factor> missing = List.of(
			ExactCategoricalSolver.Factor.lazy(List.of(y, x), v ->
				v[0] % 10 == 0 && v[1] == 0 ? INF : 0d),
			ExactCategoricalSolver.Factor.lazy(List.of(x, y), v ->
				v[1] % 10 == 0 && v[0] == 1 ? INF : .25));
		Assert.assertTrue(assertParity(List.of(x, y), missing,
			List.of("cross-x", "cross-y"), -2, 16));

		var denseX = new ExactCategoricalSolver.Variable("dense-cross-x", 2);
		var denseY = new ExactCategoricalSolver.Variable("dense-cross-y", 10);
		var denseZ = new ExactCategoricalSolver.Variable("dense-cross-z", 10);
		List<ExactCategoricalSolver.Factor> dense = List.of(
			ExactCategoricalSolver.Factor.lazy(List.of(denseY, denseX, denseZ), v ->
				(v[0] * 10 + v[2]) * .25));
		assertParity(List.of(denseX, denseY, denseZ), dense,
			List.of("dense-cross-x", "dense-cross-y", "dense-cross-z"), -2, 16);

		var carryX = new ExactCategoricalSolver.Variable("carry-x", 3);
		var carryY = new ExactCategoricalSolver.Variable("carry-y", 2);
		var carryFactor = ExactCategoricalSolver.Factor.lazy(List.of(carryY, carryX), v ->
			0x1p52 + v[0]);
		assertParity(List.of(carryX, carryY), List.of(carryFactor, carryFactor),
			List.of("carry-x", "carry-y"), 0, 60);
	}

	@Test
	public void preflightLazyCallbackOrderAndLegacyTieCallbackRemainUnchanged() {
		var x = new ExactCategoricalSolver.Variable("callback-x", 2);
		AtomicInteger rejectedCalls = new AtomicInteger();
		var rejected = ExactCategoricalSolver.Factor.lazy(List.of(x), values -> {
			rejectedCalls.incrementAndGet();
			return 0d;
		});
		Assert.assertThrows(IllegalArgumentException.class, () -> ExactCategoricalSolver.solveDyadic(
			ExactCategoricalSolver.compile(List.of(x), List.of(rejected),
				new ExactCategoricalSolver.Limits(1, 1)),
			ExactDyadicCosts.certify(0, 1, 1, true)));
		Assert.assertEquals(0, rejectedCalls.get());

		var y = new ExactCategoricalSolver.Variable("callback-y", 3);
		List<Integer> visits = new ArrayList<>();
		var ordered = ExactCategoricalSolver.Factor.lazy(List.of(y, x), values -> {
			visits.add(values[0] * 10 + values[1]);
			return values[0] + values[1];
		});
		var compiled = ExactCategoricalSolver.compilePreferred(List.of(x, y), List.of(ordered),
			LIMITS, List.of("callback-x", "callback-y"));
		ExactCategoricalSolver.solveDyadic(compiled,
			ExactDyadicCosts.certify(0, 8, 1, true));
		Assert.assertEquals(List.of(0, 1, 10, 11, 20, 21), visits);

		AtomicInteger tieCalls = new AtomicInteger();
		var tieResult = ExactCategoricalSolver.solve(List.of(x),
			List.of(ExactCategoricalSolver.Factor.dense(List.of(x), 0d, 0d)), LIMITS,
			(variable, value) -> { tieCalls.incrementAndGet(); return value == 0 ? 1L : 0L; });
		Assert.assertEquals(List.of(1), tieResult.assignmentInVariableOrder());
		Assert.assertEquals(2, tieCalls.get());
	}

	private static boolean assertParity(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, List<String> order, int q, int bits)
		throws Exception {
		var certificate = ExactDyadicCosts.certify(q, bits, factors.size(), true);
		Assert.assertTrue(certificate.reason(), certificate.supported());
		var compiled = ExactCategoricalSolver.compilePreferred(variables, factors, LIMITS, order);
		List<ExactCategoricalSolver.EliminationSnapshot> actual = dyadicSteps(compiled, certificate);
		Reference reference = reference(variables, factors, order, q);
		Assert.assertEquals(reference.steps.size(), actual.size());
		boolean quotient = false;
		for(int step = 0; step < actual.size(); step++) {
			var expected = reference.steps.get(step);
			var observed = actual.get(step);
			Assert.assertEquals(expected.variable, observed.variable());
			Assert.assertArrayEquals(expected.scope, observed.scope());
			Assert.assertArrayEquals("choices step=" + step, expected.choices, observed.choices());
			assertRaw("high step=" + step, expected.high, observed.high());
			assertRaw("low step=" + step, expected.low, observed.low());
			quotient |= observed.sparse() || observed.storedCells() < observed.high().length;
		}
		var result = ExactCategoricalSolver.solveDyadic(compiled, certificate);
		Assert.assertEquals(reference.assignment, result.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(reference.objective),
			Double.doubleToRawLongBits(result.objective()));
		return quotient;
	}

	@SuppressWarnings("unchecked")
	private static List<ExactCategoricalSolver.EliminationSnapshot> dyadicSteps(
		ExactCategoricalSolver.CompiledProblem compiled, ExactDyadicCosts.Certificate certificate)
		throws Exception {
		Field preparedField = compiled.getClass().getDeclaredField("prepared");
		Field factorsField = compiled.getClass().getDeclaredField("factors");
		preparedField.setAccessible(true);
		factorsField.setAccessible(true);
		Object prepared = preparedField.get(compiled);
		Class<?> preparedType = prepared.getClass();
		Method solve = ExactCategoricalSolver.class.getDeclaredMethod("solve", preparedType,
			List.class, ExactCategoricalSolver.TieCostFunction.class, Consumer.class,
			ExactDyadicCosts.Certificate.class);
		solve.setAccessible(true);
		List<ExactCategoricalSolver.EliminationSnapshot> steps = new ArrayList<>();
		solve.invoke(null, prepared, factorsField.get(compiled), null,
			(Consumer<ExactCategoricalSolver.EliminationSnapshot>)steps::add, certificate);
		return steps;
	}

	private static Reference reference(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, List<String> order, int q) {
		int[] domains = variables.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize).toArray();
		List<RefFactor> active = new ArrayList<>();
		for(var factor : factors) {
			int[] scope = factor.scope().stream().mapToInt(variables::indexOf).toArray();
			int cells = cells(scope, domains);
			BigInteger[] values = new BigInteger[cells];
			int[] local = new int[scope.length];
			for(int cell = 0; cell < cells; cell++) {
				decodeLocal(cell, scope, domains, local);
				double value = factor.cost(local);
				values[cell] = value == INF ? null : units(value, q);
			}
			active.add(new RefFactor(scope, values));
		}
		List<RefStep> steps = new ArrayList<>();
		for(String key : order) {
			int variable = variables.indexOf(variables.stream()
				.filter(candidate -> candidate.key().equals(key)).findFirst().orElseThrow());
			List<RefFactor> bucket = active.stream().filter(factor -> factor.contains(variable)).toList();
			active.removeAll(bucket);
			int[] separator = bucket.stream().flatMapToInt(factor -> Arrays.stream(factor.scope))
				.filter(axis -> axis != variable).distinct().sorted().toArray();
			int outputCells = cells(separator, domains);
			BigInteger[] output = new BigInteger[outputCells];
			int[] choices = new int[outputCells];
			int[] global = new int[domains.length];
			int[] separatorLocal = new int[separator.length];
			for(int cell = 0; cell < outputCells; cell++) {
				decode(cell, separator, domains, separatorLocal, global);
				BigInteger best = null;
				for(int value = 0; value < domains[variable]; value++) {
					global[variable] = value;
					BigInteger candidate = BigInteger.ZERO;
					for(RefFactor factor : bucket) {
						BigInteger contribution = factor.value(global, domains);
						if(contribution == null) { candidate = null; break; }
						candidate = candidate.add(contribution);
					}
					if(candidate != null && (best == null || candidate.compareTo(best) < 0)) {
						best = candidate;
						choices[cell] = value;
					}
				}
				output[cell] = best;
			}
			active.add(new RefFactor(separator, output));
			steps.add(snapshot(variable, separator, output, choices));
		}
		BigInteger total = BigInteger.ZERO;
		for(RefFactor factor : active) {
			BigInteger value = factor.values[0];
			if(value == null) throw new IllegalArgumentException("reference infeasible");
			total = total.add(value);
		}
		int[] assignment = new int[variables.size()];
		for(int index = steps.size() - 1; index >= 0; index--) {
			RefStep step = steps.get(index);
			int cell = encode(step.scope, domains, assignment);
			assignment[step.variable] = step.choices[cell];
		}
		double objective = Double.longBitsToDouble(words(total).toDoubleBits(q));
		return new Reference(steps, Arrays.stream(assignment).boxed().toList(), objective);
	}

	private static RefStep snapshot(int variable, int[] scope, BigInteger[] values, int[] choices) {
		double[] high = new double[values.length], low = new double[values.length];
		for(int cell = 0; cell < values.length; cell++) {
			if(values[cell] == null) high[cell] = INF;
			else {
				ExactDyadicCosts words = words(values[cell]);
				high[cell] = words.highDigit();
				low[cell] = words.lowDigit();
			}
		}
		return new RefStep(variable, scope.clone(), high, low, choices.clone());
	}

	private static BigInteger units(double value, int q) {
		ExactDyadicCosts words = ExactDyadicCosts.fromRawBits(Double.doubleToRawLongBits(value), q);
		return BigInteger.valueOf(words.highWord()).shiftLeft(53)
			.add(BigInteger.valueOf(words.lowWord()));
	}

	private static ExactDyadicCosts words(BigInteger value) {
		BigInteger[] division = value.divideAndRemainder(BASE);
		return ExactDyadicCosts.ofWords(division[0].longValueExact(), division[1].longValueExact());
	}

	private static int cells(int[] scope, int[] domains) {
		int cells = 1;
		for(int variable : scope) cells = Math.multiplyExact(cells, domains[variable]);
		return cells;
	}

	private static void decodeLocal(int cell, int[] scope, int[] domains, int[] local) {
		for(int axis = scope.length - 1; axis >= 0; axis--) {
			local[axis] = cell % domains[scope[axis]];
			cell /= domains[scope[axis]];
		}
	}

	private static void decode(int cell, int[] scope, int[] domains, int[] local, int[] global) {
		decodeLocal(cell, scope, domains, local);
		for(int axis = 0; axis < scope.length; axis++) global[scope[axis]] = local[axis];
	}

	private static int encode(int[] scope, int[] domains, int[] global) {
		int cell = 0;
		for(int variable : scope) cell = cell * domains[variable] + global[variable];
		return cell;
	}

	private static void assertRaw(String label, double[] expected, double[] actual) {
		Assert.assertEquals(label, expected.length, actual.length);
		for(int cell = 0; cell < expected.length; cell++)
			Assert.assertEquals(label + " cell=" + cell,
				Double.doubleToRawLongBits(expected[cell]), Double.doubleToRawLongBits(actual[cell]));
	}

	private record RefFactor(int[] scope, BigInteger[] values) {
		private boolean contains(int variable) {
			return Arrays.stream(scope).anyMatch(axis -> axis == variable);
		}
		private BigInteger value(int[] global, int[] domains) {
			return values[encode(scope, domains, global)];
		}
	}
	private record RefStep(int variable, int[] scope, double[] high, double[] low, int[] choices) { }
	private record Reference(List<RefStep> steps, List<Integer> assignment, double objective) { }
}
