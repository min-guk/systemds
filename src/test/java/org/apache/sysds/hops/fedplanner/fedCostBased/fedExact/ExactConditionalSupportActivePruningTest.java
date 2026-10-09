/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.ConditionalRegion;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.FrozenInputs;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class ExactConditionalSupportActivePruningTest {
	private static final Limits LIMITS = new Limits(1_000_000, 10_000_000);

	@Test
	public void ternaryConditionalPrunesUnsupportedValuesInFrozenAndPartialPaths() throws Exception {
		Variable selector = new Variable("selector", 2), left = new Variable("left", 2),
			right = new Variable("right", 2);
		List<Variable> variables = List.of(selector, left, right);
		Factor support = Factor.conditionalSupport(variables, 0, new int[] {0, 1}, List.of(
			region(0, null, new int[] {0}, new int[] {0}),
			region(1, null, new int[] {1}, new int[] {1})));
		boolean[][] expected = {{true, false}, {true, false}, {true, false}};

		boolean[][] partial = {{true, true}, {true, false}, {true, true}};
		invoke("revisePartialHardFactor", new Class<?>[] {Factor.class, int[].class, boolean[][].class},
			support, new int[] {0, 1, 2}, partial);
		assertDomains(expected, partial);

		FrozenInputs frozen = ExactCategoricalSolver.freezeInputs(variables, List.of(support), LIMITS);
		boolean[][] active = {{true, true}, {true, false}, {true, true}};
		invoke("arcConsistency", new Class<?>[] {FrozenInputs.class, boolean[][].class}, frozen, active);
		assertDomains(expected, active);
	}

	@Test
	public void conditionalRemovalEpochRevisitsEarlierFrozenFactor() throws Exception {
		Variable selector = new Variable("epoch-selector", 2), left = new Variable("epoch-left", 2),
			right = new Variable("epoch-right", 2), tail = new Variable("epoch-tail", 2);
		List<Variable> variables = List.of(selector, left, right, tail);
		Factor equality = Factor.dense(List.of(right, tail),
			0d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0d);
		Factor leftZero = Factor.dense(List.of(left), 0d, Double.POSITIVE_INFINITY);
		Factor conditional = Factor.conditionalSupport(List.of(selector, left, right), 0,
			new int[] {0, 1}, List.of(
				region(0, null, new int[] {0}, new int[] {0}),
				region(1, null, new int[] {1}, new int[] {1})));
		FrozenInputs frozen = ExactCategoricalSolver.freezeInputs(variables,
			List.of(equality, leftZero, conditional), LIMITS);
		boolean[][] active = {{true, true}, {true, true}, {true, true}, {true, true}};
		invoke("arcConsistency", new Class<?>[] {FrozenInputs.class, boolean[][].class}, frozen, active);
		boolean[][] expected = {{true, false}, {true, false}, {true, false}, {true, false}};
		assertDomains(expected, active);
	}

	@Test
	public void wildcardAndOverlappingRegionsMatchExhaustiveActiveTupleSupport() {
		Variable left = new Variable("left", 3), selector = new Variable("selector", 4),
			right = new Variable("right", 3);
		Factor support = Factor.conditionalSupport(List.of(left, selector, right), 1,
			new int[] {0, 2, 3}, List.of(
				region(0, new int[] {0, 1}, null, new int[] {0}),
				region(0, new int[] {1, 2}, null, new int[] {1, 2}),
				region(2, new int[0], null, new int[] {2}),
				region(3, new int[] {2}, null, new int[] {2})));
		boolean[][] active = {{false, true, true}, {true, true, true, true}, {true, false, true}};
		assertDomains(exhaustiveSupport(support, active), support.conditionalSupportedValues(active));
		Assert.assertArrayEquals("selector 1 is an unconstrained wildcard",
			new boolean[] {true, true, false, true}, support.conditionalSupportedValues(active)[1]);
	}

	@Test
	public void selectorOnlyAndEmptyActiveAxisHaveExactMasks() {
		Variable selector = new Variable("selector-only", 4);
		Factor support = Factor.conditionalSupport(List.of(selector), 0, new int[] {1, 2},
			List.of(region(1, (int[])null)));
		Assert.assertArrayEquals(new boolean[] {true, true, false, true},
			support.conditionalSupportedValues(new boolean[][] {{true, true, true, true}})[0]);
		Assert.assertArrayEquals(new boolean[] {false, false, false, false},
			support.conditionalSupportedValues(new boolean[][] {{false, false, false, false}})[0]);
	}

	@Test
	public void fixedSeedRandomActiveDomainsMatchTupleEnumeration() {
		Random random = new Random(0x434f4e44474143L);
		for(int trial = 0; trial < 160; trial++) {
			int selectorAxis = trial % 3;
			List<Variable> variables = List.of(new Variable("a" + trial, 2),
				new Variable("b" + trial, 3), new Variable("c" + trial, 2));
			int selectorDomain = variables.get(selectorAxis).domainSize();
			int[] constrained = java.util.stream.IntStream.range(0, selectorDomain)
				.filter(value -> random.nextBoolean()).toArray();
			List<ConditionalRegion> regions = new ArrayList<>();
			for(int selector : constrained) {
				int copies = random.nextInt(3);
				for(int copy = 0; copy < copies; copy++) {
					int[][] allowed = new int[3][];
					for(int axis = 0; axis < 3; axis++) if(axis != selectorAxis) {
						List<Integer> values = new ArrayList<>();
						for(int value = 0; value < variables.get(axis).domainSize(); value++)
							if(random.nextBoolean()) values.add(value);
						allowed[axis] = values.stream().mapToInt(Integer::intValue).toArray();
					}
					regions.add(new ConditionalRegion(selector, allowed));
				}
			}
			Factor support = Factor.conditionalSupport(variables, selectorAxis, constrained, regions);
			boolean[][] active = new boolean[3][];
			for(int axis = 0; axis < active.length; axis++) {
				active[axis] = new boolean[variables.get(axis).domainSize()];
				for(int value = 0; value < active[axis].length; value++)
					active[axis][value] = random.nextBoolean();
			}
			assertDomains(exhaustiveSupport(support, active), support.conditionalSupportedValues(active));
		}
	}

	@Test(timeout = 5000)
	public void billionTupleRelationPrunesFromStoredRegionsOnly() {
		Variable first = new Variable("large-first", 1_000), selector = new Variable("large-selector", 1_000),
			last = new Variable("large-last", 1_000);
		Factor support = Factor.conditionalSupport(List.of(first, selector, last), 1,
			new int[] {7, 999}, List.of(
				region(7, new int[] {3, 400}, null, new int[] {5, 600}),
				region(999, new int[] {999}, null, new int[] {998})));
		boolean[][] active = new boolean[3][1_000];
		active[0][400] = true; active[1][7] = true; active[1][999] = true; active[2][600] = true;
		boolean[][] supported = support.conditionalSupportedValues(active);
		Assert.assertTrue(supported[0][400]);
		Assert.assertTrue(supported[1][7]);
		Assert.assertFalse(supported[1][999]);
		Assert.assertTrue(supported[2][600]);
		Assert.assertEquals(6L, support.conditionalStoredValues());
	}

	@Test
	public void reducedSolvePreservesDenseObjectiveBitsAndCanonicalTieChoice() {
		Variable selector = new Variable("solve-selector", 3), left = new Variable("solve-left", 3),
			right = new Variable("solve-right", 2);
		List<Variable> variables = List.of(selector, left, right);
		Factor conditional = Factor.conditionalSupport(variables, 0, new int[] {0, 2}, List.of(
			region(0, null, new int[] {1}, new int[] {0}),
			region(2, null, new int[] {0, 2}, new int[] {1})));
		double[] dense = denseCosts(conditional);
		List<Factor> costs = List.of(
			Factor.dense(List.of(selector), 0d, Double.POSITIVE_INFINITY, 0d),
			Factor.dense(List.of(left), 0d, 0d, 0d),
			Factor.dense(List.of(right), 0d, 0d));
		ExactCategoricalSolver.TieCostFunction tie = (variable, value) ->
			variable == selector ? 2L - value : value;
		var expected = ExactCategoricalSolver.solve(variables,
			List.of(Factor.dense(variables, dense), costs.get(0), costs.get(1), costs.get(2)), LIMITS, tie);
		var actual = ExactPhysicalReducedSolver.solve(variables.size(), variables,
			List.of(conditional, costs.get(0), costs.get(1), costs.get(2)), LIMITS, tie);
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
	}

	private static ConditionalRegion region(int selector, int[]... allowed) {
		return new ConditionalRegion(selector, allowed);
	}

	private static boolean[][] exhaustiveSupport(Factor factor, boolean[][] active) {
		boolean[][] supported = new boolean[active.length][];
		for(int axis = 0; axis < active.length; axis++) supported[axis] = new boolean[active[axis].length];
		int cells = factor.scope().stream().mapToInt(Variable::domainSize).reduce(1, Math::multiplyExact);
		int[] values = new int[active.length];
		for(int cell = 0; cell < cells; cell++) {
			int remaining = cell; boolean admitted = true;
			for(int axis = values.length - 1; axis >= 0; axis--) {
				values[axis] = remaining % active[axis].length; remaining /= active[axis].length;
				admitted &= active[axis][values[axis]];
			}
			if(admitted && Double.isFinite(factor.cost(values)))
				for(int axis = 0; axis < values.length; axis++) supported[axis][values[axis]] = true;
		}
		return supported;
	}

	private static double[] denseCosts(Factor factor) {
		int cells = factor.scope().stream().mapToInt(Variable::domainSize).reduce(1, Math::multiplyExact);
		double[] result = new double[cells]; int[] values = new int[factor.scope().size()];
		for(int cell = 0; cell < cells; cell++) {
			int remaining = cell;
			for(int axis = values.length - 1; axis >= 0; axis--) {
				values[axis] = remaining % factor.scope().get(axis).domainSize();
				remaining /= factor.scope().get(axis).domainSize();
			}
			result[cell] = factor.cost(values);
		}
		return result;
	}

	private static void assertDomains(boolean[][] expected, boolean[][] actual) {
		Assert.assertEquals(expected.length, actual.length);
		for(int axis = 0; axis < expected.length; axis++) Assert.assertArrayEquals("axis=" + axis,
			expected[axis], actual[axis]);
	}

	private static Object invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
		Method method = ExactPhysicalReducedSolver.class.getDeclaredMethod(name, types);
		method.setAccessible(true);
		return method.invoke(null, arguments);
	}
}
