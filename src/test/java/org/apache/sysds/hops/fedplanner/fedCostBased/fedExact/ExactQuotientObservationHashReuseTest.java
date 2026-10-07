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

import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.FrozenInputs;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class ExactQuotientObservationHashReuseTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(2_000_000, 20_000_000);

	@Test
	public void rawHashesAndForcedCollisionClassesMatchLegacy() throws Exception {
		List<Variable> variables = List.of(variable("x", 5), variable("y", 3), variable("z", 4));
		Factor xyz = Factor.dense(List.of(variables.get(2), variables.get(0), variables.get(1)),
			pattern(4 * 5 * 3, 7));
		Factor xy = Factor.dense(List.of(variables.get(0), variables.get(1)), pattern(5 * 3, 19));
		FrozenInputs frozen = freeze(variables, List.of(xyz, xy, xyz));
		boolean[][] active = {{true, false, true, true, true}, {true, true, false},
			{false, true, true, true}};
		long[] ties = {3, 9, 3, 1, 3};
		List<Integer> incident = List.of(0, 1, 2);

		for(int value : new int[] {0, 2, 3, 4})
			Assert.assertArrayEquals(legacyHash(frozen, 0, value, active, incident, ties[value]),
				optimizedHash(frozen, 0, value, active, incident, ties[value]));
		for(boolean constantHash : new boolean[] {false, true})
			Assert.assertArrayEquals(box(legacyClasses(frozen, 0, active, incident, ties, constantHash)),
				box(optimizedClasses(frozen, 0, active, incident, ties, constantHash)));
	}

	@Test
	public void fullReducedSolvePreservesCanonicalAssignmentAndObjectiveBits() {
		Variable a = variable("a", 4);
		Variable b = variable("b", 3);
		Variable c = variable("c", 2);
		List<Variable> variables = List.of(a, b, c);
		List<Factor> factors = List.of(
			Factor.dense(List.of(a, b), 0.0d, 0.0d, 3d, 0.0d, 0.0d, 3d,
				2d, 2d, 4d, 2d, 2d, 4d),
			Factor.dense(List.of(b, c), 0.25d, 2d, Math.nextUp(0.25d), 2d, 3d, 4d),
			Factor.dense(List.of(c), Double.MIN_VALUE, 1d));
		ExactCategoricalSolver.Result expected = ExactCategoricalSolver.solve(variables, factors, LIMITS);
		ExactCategoricalSolver.Result actual = ExactPhysicalReducedSolver.solve(3, variables, factors, LIMITS);
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
	}

	@Test
	public void invalidNegativeZeroStillFailsBeforeQuotienting() {
		Variable value = variable("invalid-negative-zero", 2);
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalReducedSolver.solve(1, List.of(value),
				List.of(Factor.dense(List.of(value), -0.0d, 1d)), LIMITS));
		Assert.assertTrue(failure.getMessage(),
			failure.getMessage().startsWith("EXACT_VE_FACTOR_COST_INVALID"));
	}

	@Test
	public void quotientDoesNotAllocateStatePerObservation() throws Exception {
		java.lang.management.ThreadMXBean platform = ManagementFactory.getThreadMXBean();
		org.junit.Assume.assumeTrue("thread allocation accounting unavailable",
			platform instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean)platform;
		org.junit.Assume.assumeTrue("thread allocation accounting unsupported",
			bean.isThreadAllocatedMemorySupported());
		boolean wasEnabled = bean.isThreadAllocatedMemoryEnabled();
		try {
			if(!wasEnabled)
				bean.setThreadAllocatedMemoryEnabled(true);

			Variable x = variable("allocation-x", 256);
			Variable y = variable("allocation-y", 2);
			List<Variable> variables = List.of(x, y);
			double[] values = new double[512];
			for(int xv = 0; xv < 256; xv++) {
				values[xv * 2] = xv;
				values[xv * 2 + 1] = xv + 0.5d;
			}
			Factor repeated = Factor.dense(variables, values);
			List<Factor> factors = new ArrayList<>();
			for(int factor = 0; factor < 64; factor++)
				factors.add(repeated);
			FrozenInputs frozen = freeze(variables, factors);
			boolean[][] active = {new boolean[256], {true, true}};
			Arrays.fill(active[0], true);
			List<Integer> incident = new ArrayList<>();
			for(int factor = 0; factor < factors.size(); factor++)
				incident.add(factor);
			long[] ties = new long[256];

			for(int warmup = 0; warmup < 20; warmup++)
				optimizedClasses(frozen, 0, active, incident, ties, false);
			long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
			for(int iteration = 0; iteration < 12; iteration++)
				optimizedClasses(frozen, 0, active, incident, ties, false);
			long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
			Assert.assertTrue("per-observation state allocation remains: " + allocated,
				allocated < 3_000_000L);
		}
		finally {
			if(!wasEnabled && bean.isThreadAllocatedMemoryEnabled())
				bean.setThreadAllocatedMemoryEnabled(false);
		}
	}

	private static FrozenInputs freeze(List<Variable> variables, List<Factor> factors) {
		return ExactCategoricalSolver.freezeInputs(variables, factors, LIMITS);
	}

	private static int[][] optimizedClasses(FrozenInputs frozen, int variable, boolean[][] active,
		List<Integer> incident, long[] ties, boolean constantHash) throws Exception {
		Method method = ExactPhysicalReducedSolver.class.getDeclaredMethod("quotientClasses",
			FrozenInputs.class, int.class, boolean[][].class, List.class, long[].class, boolean.class);
		method.setAccessible(true);
		return (int[][])method.invoke(null, frozen, variable, active, incident, ties, constantHash);
	}

	private static long[] optimizedHash(FrozenInputs frozen, int variable, int value,
		boolean[][] active, List<Integer> incident, long tie) throws Exception {
		Class<?> traversal = Class.forName(ExactPhysicalReducedSolver.class.getName() + "$ObservationTraversal");
		Constructor<?> constructor = traversal.getDeclaredConstructor(FrozenInputs.class, int.class, int.class);
		constructor.setAccessible(true);
		Object observations = Array.newInstance(traversal, incident.size());
		for(int index = 0; index < incident.size(); index++)
			Array.set(observations, index, constructor.newInstance(frozen, incident.get(index), variable));
		Method hash = Arrays.stream(ExactPhysicalReducedSolver.class.getDeclaredMethods())
			.filter(method -> method.getName().equals("observationHash")).findFirst().orElseThrow();
		hash.setAccessible(true);
		Object result;
		if(hash.getParameterCount() == 5)
			result = hash.invoke(null, frozen, value, active, observations, tie);
		else {
			Class<?> accumulator = hash.getParameterTypes()[5];
			Constructor<?> accumulatorConstructor = accumulator.getDeclaredConstructor();
			accumulatorConstructor.setAccessible(true);
			result = hash.invoke(null, frozen, value, active, observations, tie,
				accumulatorConstructor.newInstance());
		}
		Method first = result.getClass().getDeclaredMethod("first");
		Method second = result.getClass().getDeclaredMethod("second");
		first.setAccessible(true);
		second.setAccessible(true);
		return new long[] {(long)first.invoke(result), (long)second.invoke(result)};
	}

	private static int[][] legacyClasses(FrozenInputs frozen, int variable, boolean[][] active,
		List<Integer> incident, long[] tieCosts, boolean constantHash) {
		Map<Hash,List<List<Integer>>> buckets = new LinkedHashMap<>();
		for(int value = 0; value < active[variable].length; value++) {
			if(!active[variable][value])
				continue;
			Hash hash = constantHash ? new Hash(0, 0)
				: hash(frozen, variable, value, active, incident, tieCosts[value]);
			List<List<Integer>> candidates = buckets.computeIfAbsent(hash, ignored -> new ArrayList<>());
			List<Integer> equivalent = null;
			for(List<Integer> candidate : candidates)
				if(equal(frozen, variable, candidate.get(0), value, active, incident, tieCosts)) {
					equivalent = candidate;
					break;
				}
			if(equivalent == null) {
				equivalent = new ArrayList<>();
				candidates.add(equivalent);
			}
			equivalent.add(value);
		}
		return buckets.values().stream().flatMap(List::stream)
			.map(values -> values.stream().mapToInt(Integer::intValue).toArray())
			.sorted((left, right) -> Integer.compare(left[0], right[0])).toArray(int[][]::new);
	}

	private static long[] legacyHash(FrozenInputs frozen, int variable, int value,
		boolean[][] active, List<Integer> incident, long tie) {
		Hash hash = hash(frozen, variable, value, active, incident, tie);
		return new long[] {hash.first, hash.second};
	}

	private static Hash hash(FrozenInputs frozen, int variable, int fixed, boolean[][] active,
		List<Integer> incident, long tie) {
		long[] state = {mix(0x9e3779b97f4a7c15L, tie), mix(0xc2b2ae3d27d4eb4fL, tie)};
		for(int factor : incident) {
			state[0] = mix(state[0], factor);
			state[1] = mix(state[1], ~factor);
			visit(frozen, factor, variable, fixed, active, new int[frozen.scope(factor).length], 0, bits -> {
				state[0] = mix(state[0], bits);
				state[1] = mix(state[1], Long.rotateLeft(bits, 23));
			});
		}
		return new Hash(state[0], state[1]);
	}

	private static boolean equal(FrozenInputs frozen, int variable, int left, int right,
		boolean[][] active, List<Integer> incident, long[] ties) {
		if(ties[left] != ties[right])
			return false;
		for(int factor : incident) {
			List<Long> leftBits = bits(frozen, factor, variable, left, active);
			List<Long> rightBits = bits(frozen, factor, variable, right, active);
			if(!leftBits.equals(rightBits))
				return false;
		}
		return true;
	}

	private static List<Long> bits(FrozenInputs frozen, int factor, int variable, int fixed,
		boolean[][] active) {
		List<Long> result = new ArrayList<>();
		visit(frozen, factor, variable, fixed, active, new int[frozen.scope(factor).length], 0,
			result::add);
		return result;
	}

	private static void visit(FrozenInputs frozen, int factor, int variable, int fixed,
		boolean[][] active, int[] local, int position, BitsVisitor visitor) {
		int[] scope = frozen.scope(factor);
		if(position == scope.length) {
			visitor.accept(Double.doubleToRawLongBits(frozen.costAt(factor, encode(local, scope, frozen))));
			return;
		}
		int scoped = scope[position];
		if(scoped == variable) {
			local[position] = fixed;
			visit(frozen, factor, variable, fixed, active, local, position + 1, visitor);
			return;
		}
		for(int value = 0; value < active[scoped].length; value++)
			if(active[scoped][value]) {
				local[position] = value;
				visit(frozen, factor, variable, fixed, active, local, position + 1, visitor);
			}
	}

	private static int encode(int[] local, int[] scope, FrozenInputs frozen) {
		int cell = 0;
		for(int position = 0; position < scope.length; position++)
			cell = Math.addExact(Math.multiplyExact(cell, frozen.domainSize(scope[position])), local[position]);
		return cell;
	}

	private static double[] pattern(int cells, int salt) {
		double[] values = new double[cells];
		for(int cell = 0; cell < cells; cell++) {
			int selector = Math.floorMod(cell * 31 + salt, 11);
			values[cell] = selector == 0 ? Double.POSITIVE_INFINITY : selector == 1 ? 0.0d
				: selector == 2 ? Double.MIN_VALUE : selector * 0.125d;
		}
		return values;
	}

	private static Variable variable(String key, int domain) { return new Variable(key, domain); }
	private static Object[] box(int[][] values) {
		return Arrays.stream(values).map(Arrays::toString).toArray();
	}
	private static long mix(long hash, long value) {
		long mixed = value * 0x9e3779b97f4a7c15L;
		mixed ^= mixed >>> 29;
		return Long.rotateLeft(hash ^ mixed, 27) * 5 + 0x52dce729;
	}
	private record Hash(long first, long second) { }
	@FunctionalInterface private interface BitsVisitor { void accept(long bits); }
}
