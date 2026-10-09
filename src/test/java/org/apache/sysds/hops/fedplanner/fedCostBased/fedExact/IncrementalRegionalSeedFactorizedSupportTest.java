/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.ConditionalRegion;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class IncrementalRegionalSeedFactorizedSupportTest {
	private static final Limits LIMITS = new Limits(1_000_000, 10_000_000);

	@Test
	public void conditionalAndSparseSupportsAvoidCartesianVisits() throws Exception {
		List<Variable> variables = List.of(new Variable("a", 64), new Variable("b", 64),
			new Variable("c", 64));
		Factor conditional = Factor.conditionalSupport(variables, 1,
			IntStream.range(0, 64).toArray(), List.of(
				new ConditionalRegion(5, new int[][] {{3, 7}, null, {11, 13}})));
		Factor sparse = Factor.finiteSupport(variables, (3 * 64 + 5) * 64 + 11,
			(7 * 64 + 5) * 64 + 13);
		for(Factor factor : List.of(conditional, sparse)) {
			boolean[][] active = full(variables);
			var stats = new IncrementalRegionalSeed.SupportStatistics();
			propagate(variables, List.of(factor), active, stats);
			Assert.assertArrayEquals(mask(64, 3, 7), active[0]);
			Assert.assertArrayEquals(mask(64, 5), active[1]);
			Assert.assertArrayEquals(mask(64, 11, 13), active[2]);
			Assert.assertEquals("stored hard relation must not enumerate product cells", 0L, stats.visitedCells);
			if(factor.isConditionalSupport()) Assert.assertEquals(2L, stats.conditionalRevisions);
			else Assert.assertEquals(4L, stats.sparseCellsVisited);
		}
	}

	@Test
	public void functionalMapReadsRowsAndPreservesReverseCascade() throws Exception {
		List<Variable> variables = List.of(new Variable("rows", 128), new Variable("columns", 128),
			new Variable("tail", 128));
		int[] identity = IntStream.range(0, 128).toArray();
		Factor first = frozenMap(variables.get(0), variables.get(1), identity);
		Factor second = frozenMap(variables.get(1), variables.get(2), identity);
		boolean[][] active = full(variables);
		active[2] = mask(128, 91);
		var stats = new IncrementalRegionalSeed.SupportStatistics();
		propagate(variables, List.of(first, second), active, stats);
		for(boolean[] axis : active) Assert.assertArrayEquals(mask(128, 91), axis);
		Assert.assertEquals(0L, stats.visitedCells);
		Assert.assertTrue(stats.functionalRowsVisited <= 3L * 128 + 2);
	}

	@Test
	public void fixedSeedRelationsMatchExplicitFixedPointAcrossActiveDomains() throws Exception {
		Random random = new Random(0x73656564474143L);
		for(int trial = 0; trial < 160; trial++) {
			List<Variable> variables = List.of(new Variable("a", 2), new Variable("b", 3),
				new Variable("c", 2));
			int selector = trial % 3;
			int[] constrained = IntStream.range(0, variables.get(selector).domainSize())
				.filter(value -> random.nextBoolean()).toArray();
			List<ConditionalRegion> regions = new ArrayList<>();
			for(int value : constrained)
				for(int copy = random.nextInt(3); copy > 0; copy--) {
					int[][] allowed = new int[3][];
					for(int axis = 0; axis < 3; axis++) if(axis != selector)
						allowed[axis] = randomValues(random, variables.get(axis).domainSize());
					regions.add(new ConditionalRegion(value, allowed));
				}
			Factor conditional = Factor.conditionalSupport(variables, selector, constrained, regions);
			Factor sparse = Factor.finiteSupport(List.of(variables.get(2), variables.get(0)), randomValues(random, 4));
			Factor functional = frozenMap(variables.get(1), variables.get(2),
				new int[] {random.nextInt(3) - 1, random.nextInt(3) - 1, random.nextInt(3) - 1});
			List<Factor> factors = List.of(conditional, sparse, functional,
				Factor.dense(List.of(variables.get(0)), 0.25d, 0.5d));
			// Exhaust every nonempty input-domain mask, including wildcard-only and
			// constrained selectors whose surviving region set is empty.
			for(int ma = 1; ma < 4; ma++) for(int mb = 1; mb < 8; mb++) for(int mc = 1; mc < 4; mc++) {
				boolean[][] active = {bits(2, ma), bits(3, mb), bits(2, mc)};
				assertParity(variables, factors, active);
			}
		}
	}

	@Test
	public void scalarSparseAndSelectorOnlyHolesPreserveFailures() throws Exception {
		for(int[] cells : List.of(new int[0], new int[] {0}))
			assertParity(List.of(), List.of(Factor.finiteSupport(List.of(), cells)), new boolean[0][]);
		Variable selector = new Variable("unary", 4);
		Factor conditional = Factor.conditionalSupport(List.of(selector), 0, new int[] {1, 2},
			List.of(new ConditionalRegion(1, new int[][] {null})));
		for(int bits = 1; bits < 16; bits++)
			assertParity(List.of(selector), List.of(conditional), new boolean[][] {bits(4, bits)});
	}

	@Test
	public void reducedRootLiftKeepsAssignmentAndObjectiveBits() {
		List<Variable> variables = List.of(new Variable("original", 3), new Variable("aux1", 3),
			new Variable("aux2", 2));
		Factor conditional = Factor.conditionalSupport(variables, 0, new int[] {0, 2}, List.of(
			new ConditionalRegion(0, new int[][] {null, {0, 2}, {0}}),
			new ConditionalRegion(2, new int[][] {null, {1}, {1}})));
		Factor functional = frozenMap(variables.get(1), variables.get(2), new int[] {0, 1, 0});
		List<Factor> factors = List.of(conditional, functional,
			Factor.dense(List.of(variables.get(1)), 0.3d, 0.2d, 0.3d),
			Factor.dense(List.of(), 0.1d));
		var root = ExactPhysicalReducedSolver.reducedModel(1, variables, factors, LIMITS);
		Assert.assertTrue(root.factors().stream().anyMatch(Factor::isConditionalSupport));
		var reference = ExactPhysicalReducedSolver.reducedModel(1, variables,
			factors.stream().map(IncrementalRegionalSeedFactorizedSupportTest::dense).toList(), LIMITS);
		for(int repeat = 0; repeat < 4; repeat++) for(int value = 0; value < 3; value++) {
			var stats = new IncrementalRegionalSeed.SupportStatistics();
			int[] actual = IncrementalRegionalSeed.lift(root, List.of(value), LIMITS, stats);
			Assert.assertTrue("reduced root must consume conditional relation directly", stats.conditionalRevisions > 0);
			Assert.assertTrue("numeric factors retain the original scalar evaluation", stats.visitedCells > 0);
			int[] expected = IncrementalRegionalSeed.lift(reference, List.of(value), LIMITS);
			List<Integer> expanded = root.expandAssignment(Arrays.stream(actual).boxed().toList());
			Assert.assertEquals(reference.expandAssignment(Arrays.stream(expected).boxed().toList()), expanded);
			Assert.assertEquals(Double.doubleToRawLongBits(objective(reference, expected)),
				Double.doubleToRawLongBits(objective(root, actual)));
		}
	}

	@Test
	public void tinyActiveProductStaysCheaperThanScanningBroadSparseRelation() throws Exception {
		List<Variable> variables = List.of(new Variable("left", 32), new Variable("right", 32));
		Factor sparse = Factor.finiteSupport(variables, IntStream.range(0, 1024).toArray());
		boolean[][] active = {mask(32, 17), mask(32, 21)};
		var stats = new IncrementalRegionalSeed.SupportStatistics();
		propagate(variables, List.of(sparse), active, stats);
		Assert.assertEquals(1L, stats.visitedCells);
	}

	@Test
	public void duplicateScopeIsRejectedBeforePropagation() {
		Variable variable = new Variable("duplicate", 2);
		try {
			ExactPhysicalReducedSolver.reducedModel(1, List.of(variable),
				List.of(Factor.functionalMap(variable, variable, new int[] {0, 1})), LIMITS);
			Assert.fail("duplicate scope must not reach the propagation kernel");
		}
		catch(IllegalArgumentException failure) {
			Assert.assertEquals("EXACT_VE_FACTOR_VARIABLE_DUPLICATE", failure.getMessage());
		}
	}

	private static void assertParity(List<Variable> variables, List<Factor> factors, boolean[][] input) throws Exception {
		boolean[][] expected = copy(input), actual = copy(input);
		var referenceStats = new IncrementalRegionalSeed.SupportStatistics();
		var actualStats = new IncrementalRegionalSeed.SupportStatistics();
		String referenceFailure = run(variables, factors.stream().map(IncrementalRegionalSeedFactorizedSupportTest::dense)
			.toList(), expected, referenceStats);
		String actualFailure = run(variables, factors, actual, actualStats);
		Assert.assertEquals(referenceFailure, actualFailure);
		if(referenceFailure == null) {
			for(int axis = 0; axis < expected.length; axis++) Assert.assertArrayEquals(expected[axis], actual[axis]);
			Assert.assertEquals(referenceStats.factorRevisions, actualStats.factorRevisions);
		}
	}

	private static String run(List<Variable> variables, List<Factor> factors, boolean[][] active,
		IncrementalRegionalSeed.SupportStatistics stats) throws Exception {
		try { propagate(variables, factors, active, stats); return null; }
		catch(InvocationTargetException failure) {
			if(!(failure.getCause() instanceof IllegalArgumentException invalid)) throw failure;
			return invalid.getMessage();
		}
	}

	private static void propagate(List<Variable> variables, List<Factor> factors, boolean[][] active,
		IncrementalRegionalSeed.SupportStatistics stats) throws Exception {
		Map<Variable,Integer> indices = new IdentityHashMap<>();
		for(int i = 0; i < variables.size(); i++) indices.put(variables.get(i), i);
		Method compile = IncrementalRegionalSeed.class.getDeclaredMethod("compileSupportPlan", List.class, Map.class, int.class);
		compile.setAccessible(true);
		Object plan = compile.invoke(null, factors, indices, variables.size());
		Method propagate = IncrementalRegionalSeed.class.getDeclaredMethod("propagateFiniteSupport", plan.getClass(),
			boolean[][].class, IncrementalRegionalSeed.SupportStatistics.class);
		propagate.setAccessible(true);
		propagate.invoke(null, plan, active, stats);
	}

	private static Factor dense(Factor factor) {
		int cells = factor.scope().stream().mapToInt(Variable::domainSize).reduce(1, Math::multiplyExact);
		double[] costs = new double[cells];
		int[] values = new int[factor.scope().size()];
		for(int cell = 0; cell < cells; cell++) {
			int remaining = cell;
			for(int axis = values.length - 1; axis >= 0; axis--) {
				values[axis] = remaining % factor.scope().get(axis).domainSize();
				remaining /= factor.scope().get(axis).domainSize();
			}
			costs[cell] = factor.cost(values);
		}
		return Factor.dense(factor.scope(), costs);
	}

	private static double objective(ExactPhysicalReducedSolver.CompactModel root, int[] values) {
		Map<Variable,Integer> index = new IdentityHashMap<>();
		for(int i = 0; i < values.length; i++) index.put(root.variables().get(i), i);
		double total = 0d;
		for(Factor factor : root.factors()) total += factor.cost(factor.scope().stream()
			.mapToInt(variable -> values[index.get(variable)]).toArray());
		return total;
	}

	private static Factor frozenMap(Variable source, Variable target, int[] targets) {
		return ExactCategoricalSolver.freezeValidatedFactor(Factor.functionalMap(source, target, targets));
	}

	private static int[] randomValues(Random random, int size) {
		return IntStream.range(0, size).filter(value -> random.nextBoolean()).toArray();
	}
	private static boolean[][] copy(boolean[][] values) { return Arrays.stream(values).map(boolean[]::clone).toArray(boolean[][]::new); }
	private static boolean[][] full(List<Variable> variables) {
		return variables.stream().map(variable -> { boolean[] values = new boolean[variable.domainSize()];
			Arrays.fill(values, true); return values; }).toArray(boolean[][]::new);
	}
	private static boolean[] bits(int size, int bits) {
		boolean[] values = new boolean[size]; for(int i = 0; i < size; i++) values[i] = (bits & 1 << i) != 0; return values;
	}
	private static boolean[] mask(int size, int... active) {
		boolean[] values = new boolean[size]; for(int value : active) values[value] = true; return values;
	}
}
