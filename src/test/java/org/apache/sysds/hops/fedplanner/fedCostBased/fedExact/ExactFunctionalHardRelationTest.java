/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.FrozenInputs;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.HardTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class ExactFunctionalHardRelationTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(1_000_000, 10_000_000);

	@Test
	public void observationRelationRetainsEveryCellWithoutExpandingItsBacking() throws Exception {
		int rows = 4096;
		int categories = 128;
		Variable left = new Variable("functional-left", rows);
		Variable right = new Variable("functional-right", rows);
		Factor canonical = Factor.lazy(List.of(left, right), values ->
			values[0] % categories == 0 ? 0d : Double.POSITIVE_INFINITY);
		List<Integer> keys = new ArrayList<>(rows);
		for(int source = 0; source < rows; source++)
			keys.add(source % categories);
		var encoded = ExactHardFactorObservationDecomposition.create("functional-storage", canonical,
			new List<?>[] {keys, Collections.nCopies(rows, 0)});
		Assert.assertNotNull(encoded);
		List<Variable> variables = new ArrayList<>(List.of(left, right));
		variables.addAll(encoded.auxiliaryVariables());
		var frozen = ExactCategoricalSolver.freezeInputs(variables, encoded.solverFactors(),
			new ExactCategoricalSolver.Limits(1_000_000, 10_000_000));
		for(int source = 0; source < rows; source++)
			for(int target = 0; target < categories; target++) {
				double expected = source % categories == target ? 0d : Double.POSITIVE_INFINITY;
				Assert.assertEquals(Double.doubleToRawLongBits(expected),
					Double.doubleToRawLongBits(frozen.costAt(0, source * categories + target)));
			}
		Field table = Factor.class.getDeclaredField("hardValues");
		table.setAccessible(true);
		long bytes = primitiveBackingBytes(table.get(frozen.factor(0)),
			Collections.newSetFromMap(new IdentityHashMap<>()));
		Assert.assertTrue("Functional relation expanded backing: bytes=" + bytes,
			bytes <= (long)Integer.BYTES * rows + 64);
	}

	@Test
	public void projectedMissingTargetsAndSingletonRebindKeepEveryRawCell() {
		Variable source = new Variable("project-source", 6), target = new Variable("project-target", 4);
		int[] mapping = {3, -1, 0, 3, 1, 2};
		Factor deferred = Factor.functionalMap(source, target, mapping);
		Factor frozen = ExactCategoricalSolver.freezeInputs(List.of(source, target), List.of(deferred), LIMITS)
			.factor(0);
		int[] rows = {5, 1, 3, 0, 2}, columns = {3, 0};
		List<Variable> projectedScope = List.of(new Variable("project-rows", rows.length),
			new Variable("project-columns", columns.length));
		for(Factor input : List.of(deferred, frozen)) {
			Factor projected = input.projectFunctionalMap(projectedScope, rows, columns);
			Assert.assertNotNull(projected.functionalMapping());
			for(int row = 0; row < rows.length; row++)
				for(int column = 0; column < columns.length; column++)
					Assert.assertEquals(Double.doubleToRawLongBits(
						mapping[rows[row]] == columns[column] ? 0d : Double.POSITIVE_INFINITY),
						Double.doubleToRawLongBits(projected.cost(new int[] {row, column})));
			Assert.assertNull(input.projectFunctionalMap(projectedScope, rows, new int[] {1, 1}));
		}
		List<Variable> singletonScope = List.of(source, new Variable("single-target", 1));
		Factor singleton = frozen.projectFunctionalMap(singletonScope, new int[] {0, 1, 2, 3, 4, 5},
			new int[] {3});
		Factor unary = singleton.rebindOwned(List.of(source));
		Assert.assertNull(unary.functionalMapping());
		for(int row = 0; row < source.domainSize(); row++)
			Assert.assertEquals(Double.doubleToRawLongBits(mapping[row] == 3 ? 0d : Double.POSITIVE_INFINITY),
				Double.doubleToRawLongBits(unary.cost(new int[] {row})));
	}

	@Test
	public void functionalAxisClassesAndFiniteOrderMatchPackedTables() throws Exception {
		Random random = new Random(76325L);
		for(int trial = 0; trial < 40; trial++) {
			Variable source = new Variable("axis-source", 1 + random.nextInt(20));
			Variable target = new Variable("axis-target", 1 + random.nextInt(9));
			int[] mapping = new int[source.domainSize()];
			for(int row = 0; row < mapping.length; row++)
				mapping[row] = random.nextInt(target.domainSize() + 1) - 1;
			HardTable functional = table(ExactCategoricalSolver.freezeInputs(List.of(source, target),
				List.of(Factor.functionalMap(source, target, mapping)), LIMITS).factor(0));
			HardTable packed = HardTable.allocate(source.domainSize() * target.domainSize());
			List<Integer> finite = new ArrayList<>();
			for(int cell = 0; cell < packed.cells(); cell++) {
				if(mapping[cell / target.domainSize()] == cell % target.domainSize())
					finite.add(cell);
				else
					packed.forbid(cell);
			}
			Assert.assertEquals(packed.cells(), functional.cells());
			Assert.assertEquals(packed.finiteCount(), functional.finiteCount());
			Assert.assertArrayEquals(packed.axisClasses(source.domainSize(), target.domainSize()),
				functional.axisClasses(source.domainSize(), target.domainSize()));
			Assert.assertArrayEquals(packed.axisClasses(target.domainSize(), 1),
				functional.axisClasses(target.domainSize(), 1));
			Method finiteMethod = ExactCategoricalSolver.FunctionalMap.class
				.getDeclaredMethod("finiteCellAfter", int.class);
			finiteMethod.setAccessible(true);
			Object relation = Factor.functionalMap(source, target, mapping).functionalMapping();
			List<Integer> actual = new ArrayList<>();
			for(int cell = (int)finiteMethod.invoke(relation, -1); cell >= 0;
				cell = (int)finiteMethod.invoke(relation, cell))
				actual.add(cell);
			Assert.assertEquals(finite, actual);
			Assert.assertThrows(IllegalStateException.class, () -> functional.forbid(0));
			Assert.assertThrows(IllegalStateException.class, () -> functional.forbidRange(0, 1));
		}
	}

	@Test
	public void activeProfileBucketsRetainExactPartitionsUnderForcedCollisions() throws Exception {
		Variable source = new Variable("hash-source", 12), target = new Variable("hash-target", 5);
		int[] mapping = {2, 0, -1, 4, 2, 0, 1, -1, 3, 4, 1, 3};
		double[] unary = {0d, 1d, 0.5d, 0.5d, 0d, 1d, 0.5d, 0.5d, 2d, 0.5d, 0.5d, 2d};
		long[] ties = {0, 1, 0, 0, 0, 1, 0, 0, 2, 3, 0, 2};
		FrozenInputs frozen = ExactCategoricalSolver.freezeInputs(List.of(source, target),
			List.of(Factor.functionalMap(source, target, mapping), Factor.dense(List.of(source), unary)), LIMITS);
		Method compile = ExactPhysicalReducedSolver.class.getDeclaredMethod("compileObservationHashes",
			FrozenInputs.class, boolean[][].class, long[][].class, int.class, int[].class);
		compile.setAccessible(true);
		Method quotient = ExactPhysicalReducedSolver.class.getDeclaredMethod("quotientClasses",
			FrozenInputs.class, int.class, boolean[][].class, List.class, long[].class, boolean.class);
		quotient.setAccessible(true);
		for(boolean[] targets : List.of(new boolean[] {true, false, true, false, false}, new boolean[5])) {
			boolean[] rows = new boolean[source.domainSize()];
			Arrays.fill(rows, true);
			rows[4] = false;
			boolean[][] active = {rows, targets};
			Map<List<Long>, List<Integer>> expectedGroups = new LinkedHashMap<>();
			for(int row = 0; row < rows.length; row++) {
				if(!rows[row])
					continue;
				List<Long> observations = new ArrayList<>(List.of(ties[row]));
				for(int column = 0; column < targets.length; column++)
					if(targets[column])
						observations.add(Double.doubleToRawLongBits(mapping[row] == column
							? 0d : Double.POSITIVE_INFINITY));
				observations.add(Double.doubleToRawLongBits(unary[row]));
				expectedGroups.computeIfAbsent(observations, ignored -> new ArrayList<>()).add(row);
			}
			int[][] expected = expectedGroups.values().stream()
				.map(group -> group.stream().mapToInt(Integer::intValue).toArray()).toArray(int[][]::new);
			for(boolean constantHash : new boolean[] {false, true}) {
				int[][] actual = (int[][])quotient.invoke(null, frozen, 0, active, List.of(0, 1), ties, constantHash);
				Assert.assertTrue(Arrays.deepToString(actual), Arrays.deepEquals(expected, actual));
			}
			Object hashes = compile.invoke(null, frozen, active, new long[][] {ties, new long[5]}, 1, null);
			Field reads = hashes.getClass().getDeclaredField("cellReads");
			reads.setAccessible(true);
			Assert.assertEquals("Only the generic unary factor reads cells", 11L, reads.getLong(hashes));
		}
	}

	private static HardTable table(Factor factor) throws Exception {
		Field field = Factor.class.getDeclaredField("hardValues");
		field.setAccessible(true);
		return (HardTable)field.get(factor);
	}

	@Test
	public void boundarySummariesSparseMergeMarginalsAndDecodeMatchLazy() {
		Variable x = new Variable("boundary-x", 12), y = new Variable("boundary-y", 4);
		List<Variable> variables = List.of(x, y);
		int[] mapping = {0, 2, -1, 0, 1, 3, 2, 3, 0, 1, 2, 3};
		Factor functional = Factor.functionalMap(x, y, mapping);
		Factor lazy = Factor.lazy(variables, values ->
			mapping[values[0]] == values[1] ? 0d : Double.POSITIVE_INFINITY);
		var actualLeaf = ExactCategoricalSolver.boundaryLeaf(variables, functional, LIMITS);
		var expectedLeaf = ExactCategoricalSolver.boundaryLeaf(variables, lazy, LIMITS);
		Assert.assertEquals(raw(expectedLeaf.minimum()), raw(actualLeaf.minimum()));
		Assert.assertEquals(raw(expectedLeaf.lowerBound()), raw(actualLeaf.lowerBound()));
		Assert.assertArrayEquals(expectedLeaf.hardSupport().finiteCells(), actualLeaf.hardSupport().finiteCells());
		for(Variable variable : variables) {
			assertRawArray(expectedLeaf.minMarginals(variable), actualLeaf.minMarginals(variable));
			assertRawArray(expectedLeaf.lowerMinMarginals(variable), actualLeaf.lowerMinMarginals(variable));
		}
		Factor numericX = Factor.dense(List.of(x), 0x1p53, 4d, 0d, 2d, 8d, 4d, 6d, 2d, 2d, 1d, 3d, 1d);
		Factor numericY = Factor.dense(List.of(y), 1d, 0x1p-53, 3d, 0.25d);
		for(boolean hardFirst : new boolean[] {false, true}) {
			List<Factor> actualFactors = hardFirst ? List.of(functional, numericX, numericY)
				: List.of(numericX, functional, numericY);
			List<Factor> expectedFactors = hardFirst ? List.of(lazy, numericX, numericY)
				: List.of(numericX, lazy, numericY);
			for(List<Variable> boundary : List.of(List.<Variable>of(), List.of(y))) {
				var actual = ExactCategoricalSolver.mergeBoundary(ExactCategoricalSolver.boundaryLeaves(
					variables, actualFactors, LIMITS), boundary, LIMITS,
					new ExactCategoricalSolver.BoundaryMergeCounters());
				var expected = ExactCategoricalSolver.mergeBoundary(ExactCategoricalSolver.boundaryLeaves(
					variables, expectedFactors, LIMITS), boundary, LIMITS,
					new ExactCategoricalSolver.BoundaryMergeCounters());
				Assert.assertEquals(raw(expected.minimum()), raw(actual.minimum()));
				Assert.assertEquals(raw(expected.lowerBound()), raw(actual.lowerBound()));
				if(!boundary.isEmpty()) {
					assertRawArray(expected.minMarginals(y), actual.minMarginals(y));
					assertRawArray(expected.lowerMinMarginals(y), actual.lowerMinMarginals(y));
				}
				for(int value = 0; value < (boundary.isEmpty() ? 1 : y.domainSize()); value++) {
					int[] actualAssignment = {0, value}, expectedAssignment = {0, value};
					actual.decodeInto(actualAssignment, variables);
					expected.decodeInto(expectedAssignment, variables);
					Assert.assertArrayEquals(expectedAssignment, actualAssignment);
				}
			}
		}
		Variable one = new Variable("boundary-singleton", 1);
		List<Variable> withSingleton = List.of(x, one);
		int[] singletonMap = {0, 0, -1, 0, 0, 0, -1, 0, 0, 0, 0, 0};
		var projected = ExactCategoricalSolver.projectSingletons(ExactCategoricalSolver.boundaryLeaf(
			withSingleton, Factor.functionalMap(x, one, singletonMap), LIMITS));
		var reference = ExactCategoricalSolver.projectSingletons(ExactCategoricalSolver.boundaryLeaf(
			withSingleton, Factor.lazy(withSingleton, v -> singletonMap[v[0]] == v[1]
				? 0d : Double.POSITIVE_INFINITY), LIMITS));
		Assert.assertEquals(List.of(x), projected.scope());
		assertRawArray(reference.minMarginals(x), projected.minMarginals(x));
		int[] selected = {3, 0}, expectedSelected = {3, 0};
		projected.decodeInto(selected, withSingleton);
		reference.decodeInto(expectedSelected, withSingleton);
		Assert.assertArrayEquals(expectedSelected, selected);
	}

	@Test
	public void sparseKnownZeroAndDyadicSolvesPreserveRawObjectiveAndBackpointers() {
		Variable x = new Variable("sparse-x", 12), y = new Variable("sparse-y", 4), z = new Variable("sparse-z", 3);
		List<Variable> variables = List.of(x, y, z);
		int[] toY = {0, 0, 1, 2, 2, -1, 3, 0, 1, 2, 3, 3};
		int[] toZ = {2, 1, 0, 1, 1, 2, 2, 0, 1, 2, 0, 2};
		Factor numericX = Factor.dense(List.of(x), 0x1p53, 4d, 5d, 1d, 1d, 0d, 6d, 3d, 2d, 3d, 0.25d, 2d);
		Factor numericY = Factor.dense(List.of(y), 0.25d, 1d, 0.75d, 0.5d);
		Factor numericZ = Factor.dense(List.of(z), 0.5d, 0.25d, 1d);
		List<Factor> functional = List.of(numericX, Factor.functionalMap(x, y, toY), numericY,
			Factor.functionalMap(x, z, toZ), numericZ);
		List<Factor> ordinary = List.of(numericX,
			Factor.lazy(List.of(x, y), v -> toY[v[0]] == v[1] ? 0d : Double.POSITIVE_INFINITY), numericY,
			Factor.lazy(List.of(x, z), v -> toZ[v[0]] == v[1] ? 0d : Double.POSITIVE_INFINITY), numericZ);
		var certificate = ExactDyadicCosts.certify(-2, 56, 5, true);
		Assert.assertTrue(certificate.supported());
		for(List<String> order : List.of(List.of(x.key(), y.key(), z.key()), List.of(z.key(), y.key(), x.key()))) {
			var actual = ExactCategoricalSolver.compilePreferred(variables, functional, LIMITS, order);
			var expected = ExactCategoricalSolver.compilePreferred(variables, ordinary, LIMITS, order);
			for(boolean dyadic : new boolean[] {false, true}) {
				var result = dyadic ? ExactCategoricalSolver.solveDyadic(actual, certificate)
					: ExactCategoricalSolver.solve(actual);
				var reference = dyadic ? ExactCategoricalSolver.solveDyadic(expected, certificate)
					: ExactCategoricalSolver.solve(expected);
				Assert.assertEquals(raw(reference.objective()), raw(result.objective()));
				Assert.assertEquals(reference.assignmentInVariableOrder(), result.assignmentInVariableOrder());
			}
		}
	}

	private static long raw(double value) {
		return Double.doubleToRawLongBits(value);
	}

	private static void assertRawArray(double[] expected, double[] actual) {
		Assert.assertArrayEquals(Arrays.stream(expected).mapToLong(Double::doubleToRawLongBits).toArray(),
			Arrays.stream(actual).mapToLong(Double::doubleToRawLongBits).toArray());
	}

	private static long primitiveBackingBytes(Object value, Set<Object> visited) throws Exception {
		if(value == null || !visited.add(value))
			return 0L;
		Class<?> type = value.getClass();
		if(type.isArray()) {
			Class<?> component = type.getComponentType();
			if(component == long.class || component == double.class)
				return (long)Long.BYTES * Array.getLength(value);
			if(component == int.class || component == float.class)
				return (long)Integer.BYTES * Array.getLength(value);
			if(component.isPrimitive())
				return (long)(component == char.class || component == short.class ? 2 : 1)
					* Array.getLength(value);
			long bytes = 0;
			for(Object item : (Object[])value)
				bytes += primitiveBackingBytes(item, visited);
			return bytes;
		}
		if(!type.getName().startsWith(ExactCategoricalSolver.class.getName()))
			return 0L;
		long bytes = 0;
		for(Field field : type.getDeclaredFields()) {
			if(Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive())
				continue;
			field.setAccessible(true);
			bytes += primitiveBackingBytes(field.get(value), visited);
		}
		return bytes;
	}
}
