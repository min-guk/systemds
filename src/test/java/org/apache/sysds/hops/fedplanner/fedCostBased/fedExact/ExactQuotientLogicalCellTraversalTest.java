/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.FrozenInputs;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.PartialHardCostFunction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.PartialTruth;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

/** Differential oracle for exact quotient observation order, bits, and equivalence classes. */
public class ExactQuotientLogicalCellTraversalTest {
	@Test
	public void multiAxisFilteredNumericAndPartialHardMatchLegacy() throws Exception {
		List<Variable> variables = List.of(variable("a", 4), variable("b", 3),
			variable("c", 5), variable("d", 2));
		Factor numeric = densePattern(List.of(variables.get(2), variables.get(0), variables.get(1)), 7);
		Factor repeated = densePattern(List.of(variables.get(0), variables.get(3), variables.get(2)), 11);
		Factor partial = Factor.lazy(List.of(variables.get(1), variables.get(0), variables.get(3)),
			new PrefixHard());
		List<Factor> factors = List.of(numeric, repeated, partial, repeated);
		FrozenInputs frozen = freeze(variables, factors);
		boolean[][] active = active(variables);
		active[0][1] = false;
		active[1][2] = false;
		active[2][0] = false;
		long[][] ties = {{0, 0, 3, 0}, {2, 2, 2}, {0, 1, 0, 1, 0}, {0, 0}};
		assertAllVariables(frozen, active, ties, false);
		assertAllVariables(frozen, active, ties, true);
	}

	@Test
	public void randomizedUnequalDomainsPreserveRawHashesClassesAndFirstRepresentatives() throws Exception {
		for(int seed = 0; seed < 40; seed++) {
			Random random = new Random(0x5eedL + seed);
			List<Variable> variables = List.of(variable("x" + seed, 2 + random.nextInt(4)),
				variable("y" + seed, 2 + random.nextInt(5)),
				variable("z" + seed, 2 + random.nextInt(3)),
				variable("w" + seed, 2 + random.nextInt(4)));
			List<Factor> factors = new ArrayList<>();
			for(int factor = 0; factor < 5; factor++) {
				List<Variable> scope = randomScope(variables, random, 2 + random.nextInt(3));
				factors.add(factor == 4 ? hardPattern(scope, seed) : densePattern(scope, seed * 17 + factor));
			}
			factors.add(factors.get(1));
			FrozenInputs frozen = freeze(variables, factors);
			boolean[][] active = active(variables);
			long[][] ties = new long[variables.size()][];
			for(int variable = 0; variable < variables.size(); variable++) {
				ties[variable] = new long[variables.get(variable).domainSize()];
				for(int value = 0; value < ties[variable].length; value++) {
					active[variable][value] = random.nextInt(4) != 0;
					ties[variable][value] = random.nextInt(3);
				}
				active[variable][random.nextInt(active[variable].length)] = true;
			}
			assertAllVariables(frozen, active, ties, false);
			assertAllVariables(frozen, active, ties, true);
		}
	}

	private static void assertAllVariables(FrozenInputs frozen, boolean[][] active,
		long[][] ties, boolean constantHash) throws Exception {
		for(int variable = 0; variable < active.length; variable++) {
			List<Integer> incident = incident(frozen, variable);
			int[][] expected = legacyClasses(frozen, variable, active, incident, ties[variable], constantHash);
			int[][] actual = optimizedClasses(frozen, variable, active, incident, ties[variable], constantHash);
			Assert.assertArrayEquals("classes variable=" + variable + " constant=" + constantHash,
				box(expected), box(actual));
			// Closed hard tables may use a sparse private bucket key. Their exact class
			// partition above, followed by full equality, remains authoritative.
			boolean rawHashStable = incident.stream()
				.noneMatch(factor -> frozen.factor(factor).isHardTable());
			if(!constantHash && rawHashStable)
				for(int value = 0; value < active[variable].length; value++)
					if(active[variable][value])
						Assert.assertArrayEquals("hash variable=" + variable + " value=" + value,
							legacyHash(frozen, variable, value, active, incident, ties[variable][value]),
							optimizedHash(frozen, variable, value, active, incident, ties[variable][value]));
		}
	}

	private static FrozenInputs freeze(List<Variable> variables, List<Factor> factors) {
		return ExactCategoricalSolver.freezeInputs(variables, factors,
			new ExactCategoricalSolver.Limits(10_000_000, 100_000_000));
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
		Method hash = ExactPhysicalReducedSolver.class.getDeclaredMethod("observationHash",
			FrozenInputs.class, int.class, boolean[][].class, observations.getClass(), long.class);
		hash.setAccessible(true);
		Object result = hash.invoke(null, frozen, value, active, observations, tie);
		Method first = result.getClass().getDeclaredMethod("first");
		Method second = result.getClass().getDeclaredMethod("second");
		first.setAccessible(true);
		second.setAccessible(true);
		return new long[] {(long)first.invoke(result), (long)second.invoke(result)};
	}

	private static int[][] legacyClasses(FrozenInputs frozen, int variable, boolean[][] active,
		List<Integer> incident, long[] tieCosts, boolean constantHash) {
		Map<LegacyHash,List<List<Integer>>> buckets = new LinkedHashMap<>();
		for(int value = 0; value < active[variable].length; value++) {
			if(!active[variable][value])
				continue;
			LegacyHash hash = constantHash ? new LegacyHash(0, 0)
				: legacyObservationHash(frozen, variable, value, active, incident, tieCosts[value]);
			List<List<Integer>> candidates = buckets.computeIfAbsent(hash, ignored -> new ArrayList<>());
			List<Integer> equivalent = null;
			for(List<Integer> candidate : candidates)
				if(legacyEqual(frozen, variable, candidate.get(0), value, active, incident, tieCosts)) {
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

	private static LegacyHash legacyObservationHash(FrozenInputs frozen, int variable, int value,
		boolean[][] active, List<Integer> incident, long tie) {
		long first = mix(0x9e3779b97f4a7c15L, tie);
		long second = mix(0xc2b2ae3d27d4eb4fL, tie);
		for(int factor : incident) {
			first = mix(first, factor);
			second = mix(second, ~factor);
			long[] state = {first, second};
			legacyVisit(frozen, factor, variable, value, active, new int[frozen.scope(factor).length], 0,
				bits -> {
					state[0] = mix(state[0], bits);
					state[1] = mix(state[1], Long.rotateLeft(bits, 23));
				});
			first = state[0];
			second = state[1];
		}
		return new LegacyHash(first, second);
	}

	private static long[] legacyHash(FrozenInputs frozen, int variable, int value,
		boolean[][] active, List<Integer> incident, long tie) {
		LegacyHash hash = legacyObservationHash(frozen, variable, value, active, incident, tie);
		return new long[] {hash.first, hash.second};
	}

	private static boolean legacyEqual(FrozenInputs frozen, int variable, int left, int right,
		boolean[][] active, List<Integer> incident, long[] ties) {
		if(ties[left] != ties[right])
			return false;
		for(int factor : incident) {
			int[] scope = frozen.scope(factor);
			int variablePosition = 0;
			while(scope[variablePosition] != variable)
				variablePosition++;
			if(!legacyEqual(frozen, factor, scope, variable, variablePosition, left, right,
				active, new int[scope.length], 0))
				return false;
		}
		return true;
	}

	private static boolean legacyEqual(FrozenInputs frozen, int factor, int[] scope, int variable,
		int variablePosition, int left, int right, boolean[][] active, int[] local, int position) {
		if(position == scope.length) {
			local[variablePosition] = left;
			long leftBits = Double.doubleToRawLongBits(frozen.costAt(factor, encode(local, scope, frozen)));
			local[variablePosition] = right;
			return leftBits == Double.doubleToRawLongBits(frozen.costAt(factor, encode(local, scope, frozen)));
		}
		int scoped = scope[position];
		if(scoped == variable)
			return legacyEqual(frozen, factor, scope, variable, variablePosition, left, right,
				active, local, position + 1);
		for(int value = 0; value < active[scoped].length; value++)
			if(active[scoped][value]) {
				local[position] = value;
				if(!legacyEqual(frozen, factor, scope, variable, variablePosition, left, right,
					active, local, position + 1))
					return false;
			}
		return true;
	}

	private static void legacyVisit(FrozenInputs frozen, int factor, int variable, int fixed,
		boolean[][] active, int[] local, int position, BitsVisitor visitor) {
		int[] scope = frozen.scope(factor);
		if(position == scope.length) {
			visitor.accept(Double.doubleToRawLongBits(frozen.costAt(factor, encode(local, scope, frozen))));
			return;
		}
		int scoped = scope[position];
		if(scoped == variable) {
			local[position] = fixed;
			legacyVisit(frozen, factor, variable, fixed, active, local, position + 1, visitor);
			return;
		}
		for(int value = 0; value < active[scoped].length; value++)
			if(active[scoped][value]) {
				local[position] = value;
				legacyVisit(frozen, factor, variable, fixed, active, local, position + 1, visitor);
			}
	}

	private static int encode(int[] local, int[] scope, FrozenInputs frozen) {
		int cell = 0;
		for(int position = 0; position < scope.length; position++)
			cell = Math.addExact(Math.multiplyExact(cell, frozen.domainSize(scope[position])), local[position]);
		return cell;
	}

	private static List<Integer> incident(FrozenInputs frozen, int variable) {
		List<Integer> result = new ArrayList<>();
		for(int factor = 0; factor < frozen.factorCount(); factor++)
			for(int scoped : frozen.scope(factor))
				if(scoped == variable) {
					result.add(factor);
					break;
				}
		return result;
	}

	private static Factor densePattern(List<Variable> scope, int salt) {
		int cells = scope.stream().mapToInt(Variable::domainSize).reduce(1, Math::multiplyExact);
		double[] values = new double[cells];
		for(int cell = 0; cell < cells; cell++) {
			int selector = Math.floorMod(cell * 31 + salt, 13);
			values[cell] = selector == 0 ? Double.POSITIVE_INFINITY
				: selector == 1 ? Double.MIN_VALUE
				: selector == 2 ? Math.nextUp(0.125d) : selector % 4 * 0.125d;
		}
		return Factor.dense(scope, values);
	}

	private static Factor hardPattern(List<Variable> scope, int salt) {
		return Factor.lazy(scope, values -> {
			int code = salt;
			for(int value : values)
				code = code * 31 + value;
			return Math.floorMod(code, 5) == 0 ? Double.POSITIVE_INFINITY : 0d;
		});
	}

	private static List<Variable> randomScope(List<Variable> variables, Random random, int size) {
		List<Variable> shuffled = new ArrayList<>(variables);
		java.util.Collections.shuffle(shuffled, random);
		return List.copyOf(shuffled.subList(0, size));
	}

	private static boolean[][] active(List<Variable> variables) {
		boolean[][] active = new boolean[variables.size()][];
		for(int variable = 0; variable < variables.size(); variable++) {
			active[variable] = new boolean[variables.get(variable).domainSize()];
			Arrays.fill(active[variable], true);
		}
		return active;
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
	private record LegacyHash(long first, long second) { }
	@FunctionalInterface private interface BitsVisitor { void accept(long bits); }

	private static final class PrefixHard implements PartialHardCostFunction {
		@Override public PartialTruth partialTruth(int[] values) {
			if(values[0] < 0)
				return PartialTruth.UNKNOWN;
			if(values[0] == 2)
				return PartialTruth.ALL_FORBIDDEN;
			if(values[1] < 0 || values[2] < 0)
				return PartialTruth.UNKNOWN;
			return (values[0] + values[1] + values[2]) % 3 == 0
				? PartialTruth.ALL_FORBIDDEN : PartialTruth.ALL_ZERO;
		}
		@Override public double cost(int[] values) {
			return partialTruth(values) == PartialTruth.ALL_FORBIDDEN ? Double.POSITIVE_INFINITY : 0d;
		}
	}
}
