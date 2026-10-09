/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/** Compare SCC rank gates with their original full-table truth and canonical choices. */
public class DerivedFoutRankRelationTest {
	private record Fixture(List<Variable> variables, List<Factor> factors, BigInteger cells) { }

	@Test public void rankGatesPreserveEveryCellWithoutGenericCartesianFreeze() throws Exception {
		Fixture fixture = circuit(3);
		int sparse = 0;
		int functional = 0;
		long logical = 0;
		for(Factor factor : fixture.factors()) {
			int size = factor.scope().stream().mapToInt(Variable::domainSize).reduce(1, Math::multiplyExact);
			logical += size;
			AtomicInteger legacyCalls = new AtomicInteger();
			Factor legacy = Factor.lazy(factor.scope(), values -> {
				legacyCalls.incrementAndGet();
				return reference(factor, values);
			});
			ExactCategoricalSolver.freezeValidatedFactor(legacy);
			Assert.assertEquals(size, legacyCalls.get());
			Factor frozen = ExactCategoricalSolver.freezeValidatedFactor(factor);
			int[] values = new int[factor.scope().size()];
			for(int cell = 0; cell < size; cell++) {
				long expected = Double.doubleToRawLongBits(reference(factor, values));
				Assert.assertEquals(expected, Double.doubleToRawLongBits(factor.cost(values)));
				Assert.assertEquals(expected, Double.doubleToRawLongBits(frozen.cost(values)));
				increment(values, factor.scope());
			}
			if(factor.isFiniteSupport()) {
				sparse++;
				Assert.assertTrue(frozen.isFiniteSupport());
			}
			if(factor.functionalMapping() != null) {
				functional++;
				Assert.assertNotNull(frozen.functionalMapping());
			}
		}
		Assert.assertEquals(BigInteger.valueOf(logical), fixture.cells());
		Assert.assertEquals("pair and continuing comparator gates must stay sparse", 5, sparse);
		Assert.assertEquals("first comparator is a four-row function", 1, functional);
	}

	@Test public void sparseAndLegacyGatesChooseIdenticalCanonicalAssignments() throws Exception {
		Fixture fixture = circuit(2);
		Random random = new Random(20261009L);
		for(int sample = 0; sample < 20; sample++) {
			List<Factor> expectedFactors = new ArrayList<>();
			for(Factor factor : fixture.factors())
				expectedFactors.add(Factor.lazy(factor.scope(), values -> reference(factor, values)));
			List<Factor> actualFactors = new ArrayList<>(fixture.factors());
			Factor enabled = Factor.dense(List.of(fixture.variables().get(0)), Double.POSITIVE_INFINITY, 0d);
			expectedFactors.add(enabled);
			actualFactors.add(enabled);
			for(int variable = 1; variable <= 4; variable++) {
				Factor price = Factor.dense(List.of(fixture.variables().get(variable)),
					sample == 0 ? 0d : random.nextInt(3) / 10d,
					sample == 0 ? 0d : random.nextInt(3) / 10d);
				expectedFactors.add(price);
				actualFactors.add(price);
			}
			var limits = new ExactCategoricalSolver.Limits(1_000_000, 10_000_000);
			var expected = ExactCategoricalSolver.solve(fixture.variables(), expectedFactors, limits);
			var actual = ExactCategoricalSolver.solve(fixture.variables(), actualFactors, limits);
			Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
			Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
				Double.doubleToRawLongBits(actual.objective()));
		}
	}

	private static double reference(Factor factor, int[] values) {
		List<Variable> scope = factor.scope();
		boolean allowed;
		if(scope.size() == 3 && scope.get(2).domainSize() == 4)
			allowed = values[2] == 2 * values[0] + values[1];
		else if(scope.size() == 2 && scope.get(0).domainSize() == 4)
			allowed = values[1] == relation(values[0]);
		else if(scope.size() == 3)
			allowed = values[2] == (values[0] == 0 ? relation(values[1]) : values[0]);
		else
			allowed = values[0] == 0 || values[1] == 1;
		return allowed ? 0d : Double.POSITIVE_INFINITY;
	}

	private static int relation(int pair) {
		int comparison = Integer.compare(pair / 2, pair % 2);
		return comparison == 0 ? 0 : comparison > 0 ? 1 : 2;
	}

	@SuppressWarnings("unchecked")
	private static Fixture circuit(int bits) throws Exception {
		Class<?> circuitType = Class.forName(ExactDerivedFoutAnchorEncoding.class.getName() + "$Circuit");
		Constructor<?> constructor = circuitType.getDeclaredConstructor(String.class);
		constructor.setAccessible(true);
		Object circuit = constructor.newInstance("rank-relation-test");
		Variable enabled = new Variable("enabled", 2);
		List<Variable> left = new ArrayList<>();
		List<Variable> right = new ArrayList<>();
		for(int bit = 0; bit < bits; bit++) {
			left.add(new Variable("left-" + bit, 2));
			right.add(new Variable("right-" + bit, 2));
		}
		Method greater = ExactDerivedFoutAnchorEncoding.class.getDeclaredMethod("strictGreater",
			circuitType, String.class, Variable.class, List.class, List.class);
		greater.setAccessible(true);
		greater.invoke(null, circuit, "compare", enabled, left, right);
		Field variables = circuitType.getDeclaredField("variables");
		Field factors = circuitType.getDeclaredField("factors");
		Field cells = circuitType.getDeclaredField("cells");
		variables.setAccessible(true); factors.setAccessible(true); cells.setAccessible(true);
		List<Variable> all = new ArrayList<>(List.of(enabled));
		all.addAll(left); all.addAll(right); all.addAll((List<Variable>)variables.get(circuit));
		return new Fixture(List.copyOf(all), List.copyOf((List<Factor>)factors.get(circuit)),
			(BigInteger)cells.get(circuit));
	}

	private static void increment(int[] values, List<Variable> scope) {
		for(int axis = values.length - 1; axis >= 0; axis--) {
			if(++values[axis] < scope.get(axis).domainSize()) return;
			values[axis] = 0;
		}
	}
}
