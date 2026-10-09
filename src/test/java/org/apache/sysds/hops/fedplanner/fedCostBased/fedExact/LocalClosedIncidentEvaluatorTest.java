/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class LocalClosedIncidentEvaluatorTest {
	private record Choice(int value, int hardViolations, double cost) { }

	@Test
	public void reusesOneProjectedArrayPerClosedFactorAcrossALargeDomain() {
		Variable prefix = new Variable("prefix", 2);
		Variable choice = new Variable("choice", 512);
		List<LinkedHashSet<Integer>> seedArrayIdentities = new ArrayList<>();
		List<Integer> calls = new ArrayList<>();
		List<Factor> costs = new ArrayList<>();
		for(int factor = 0; factor < 12; factor++) {
			LinkedHashSet<Integer> identities = new LinkedHashSet<>();
			seedArrayIdentities.add(identities);
			calls.add(0);
			int ordinal = factor;
			costs.add(Factor.lazy(List.of(prefix, choice), values -> {
				// Observe identity without retaining the solver-owned reusable buffer.
				if(calls.get(ordinal) < choice.domainSize())
					identities.add(System.identityHashCode(values));
				calls.set(ordinal, calls.get(ordinal) + 1);
				return values[0] + Math.abs(values[1] - 317) + ordinal;
			}));
		}

		LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
			List.of(prefix, choice), List.of(), costs, List.of(prefix, choice), List.of(),
			(variable, value) -> value);

		Assert.assertEquals(List.of(0, 317), result.assignmentInVariableOrder());
		for(int factor = 0; factor < costs.size(); factor++) {
			Assert.assertEquals("every domain value plus final objective", 513,
				calls.get(factor).intValue());
			Assert.assertEquals("seed projection must be reused", 1,
				seedArrayIdentities.get(factor).size());
		}
	}

	@Test
	public void fixedSeedModelsMatchFreshArrayReferenceExactly() {
		for(int seed = 0; seed < 40; seed++) {
			Random random = new Random(0x5eedL + seed);
			List<Variable> variables = List.of(
				new Variable("a-" + seed, 3), new Variable("b-" + seed, 4),
				new Variable("c-" + seed, 3), new Variable("d-" + seed, 5));
			List<Variable> order = List.of(variables.get(1), variables.get(0),
				variables.get(3), variables.get(2));
			List<Factor> hard = new ArrayList<>();
			List<Factor> cost = new ArrayList<>();
			// Includes factors that remain open at the first endpoint and close later.
			for(List<Variable> scope : List.of(
				List.of(variables.get(1)), List.of(variables.get(1), variables.get(0)),
				List.of(variables.get(0), variables.get(2)),
				List.of(variables.get(1), variables.get(3), variables.get(2)))) {
				int cells = scope.stream().mapToInt(Variable::domainSize).reduce(1, Math::multiplyExact);
				double[] values = new double[cells];
				for(int cell = 0; cell < cells; cell++)
					values[cell] = random.nextInt(7) == 0 ? 0d
						: Double.longBitsToDouble(Double.doubleToRawLongBits(random.nextDouble() * 8d));
				cost.add(Factor.dense(scope, values));
			}
			// These constraints exercise +INF and hard-factor encounter order but always
			// leave value zero legal, so no post-seed repair obscures the comparison.
			hard.add(Factor.lazy(List.of(variables.get(1)), v -> v[0] == 3
				? Double.POSITIVE_INFINITY : 0d));
			hard.add(Factor.lazy(List.of(variables.get(2)), v -> v[0] == 2
				? Double.POSITIVE_INFINITY : 0d));
			LocalCategoricalOptimizer.StateKeyProvider keys =
				(variable, value) -> value % 2;

			List<Integer> expected = freshArraySeed(variables, hard, cost, order, keys);
			LocalCategoricalOptimizer.Result actual = LocalCategoricalOptimizer.optimize(
				variables, hard, cost, order, List.of(), keys);

			Assert.assertEquals("seed=" + seed, expected, actual.assignmentInVariableOrder());
			Assert.assertEquals("seed=" + seed, objectiveBits(variables, cost, expected),
				Double.doubleToRawLongBits(actual.objective()));
		}
	}

	@Test
	public void preservesInvalidCostAndInfinityBehavior() {
		Variable x = new Variable("x", 3);
		LocalCategoricalOptimizer.Result finite = LocalCategoricalOptimizer.optimize(
			List.of(x), List.of(), List.of(Factor.lazy(List.of(x), v -> v[0] == 0
				? Double.POSITIVE_INFINITY : v[0] - 1d)), List.of(x), List.of(),
			(variable, value) -> value);
		Assert.assertEquals(List.of(1), finite.assignmentInVariableOrder());
		assertFailure(x, Double.NaN, "LOCAL_COST_FACTOR_INVALID");
		assertFailure(x, -0d, "LOCAL_COST_FACTOR_INVALID");
	}

	private static void assertFailure(Variable variable, double value, String reason) {
		try {
			LocalCategoricalOptimizer.optimize(List.of(variable), List.of(),
				List.of(Factor.lazy(List.of(variable), ignored -> value)), List.of(variable),
				List.of(), (v, selected) -> selected);
			Assert.fail("expected " + reason);
		}
		catch(IllegalArgumentException failure) {
			Assert.assertTrue(failure.getMessage(), failure.getMessage().startsWith(reason));
		}
	}

	private static List<Integer> freshArraySeed(List<Variable> variables, List<Factor> hard,
		List<Factor> cost, List<Variable> order, LocalCategoricalOptimizer.StateKeyProvider keys) {
		IdentityHashMap<Variable,Integer> positions = new IdentityHashMap<>();
		for(int index = 0; index < variables.size(); index++)
			positions.put(variables.get(index), index);
		int[] assignment = new int[variables.size()];
		java.util.Arrays.fill(assignment, -1);
		for(Variable variable : order) {
			int global = positions.get(variable);
			Map<Object,Choice> representatives = new LinkedHashMap<>();
			for(int value = 0; value < variable.domainSize(); value++) {
				assignment[global] = value;
				int violations = 0;
				for(Factor factor : hard)
					if(factor.scope().contains(variable) && closed(factor, assignment, positions)
						&& factorCost(factor, assignment, positions) == Double.POSITIVE_INFINITY)
						violations++;
				ExactCompensatedCostSum total = new ExactCompensatedCostSum();
				double sum = 0d;
				for(Factor factor : cost) {
					if(!factor.scope().contains(variable) || !closed(factor, assignment, positions))
						continue;
					double current = factorCost(factor, assignment, positions);
					if(current == Double.POSITIVE_INFINITY) {
						sum = current;
						break;
					}
					total.addBits(Double.doubleToRawLongBits(current), "reference-cost", "reference-total");
					sum = Double.longBitsToDouble(total.totalBits("reference-total"));
				}
				Choice candidate = new Choice(value, violations, sum);
				Object key = keys.stateKey(variable, value);
				Choice prior = representatives.get(key);
				if(prior == null || compare(candidate, prior) < 0)
					representatives.put(key, candidate);
			}
			assignment[global] = representatives.values().stream().min(
				LocalClosedIncidentEvaluatorTest::compare).orElseThrow().value();
		}
		return java.util.Arrays.stream(assignment).boxed().toList();
	}

	private static long objectiveBits(List<Variable> variables, List<Factor> cost,
		List<Integer> assignment) {
		IdentityHashMap<Variable,Integer> positions = new IdentityHashMap<>();
		for(int index = 0; index < variables.size(); index++)
			positions.put(variables.get(index), index);
		ExactCompensatedCostSum total = new ExactCompensatedCostSum();
		for(Factor factor : cost) {
			int[] local = factor.scope().stream().mapToInt(v -> assignment.get(positions.get(v))).toArray();
			total.addBits(Double.doubleToRawLongBits(factor.cost(local)), "reference-cost", "reference-total");
		}
		return total.totalBits("reference-total");
	}

	private static boolean closed(Factor factor, int[] assignment,
		IdentityHashMap<Variable,Integer> positions) {
		for(Variable variable : factor.scope())
			if(assignment[positions.get(variable)] < 0)
				return false;
		return true;
	}

	private static double factorCost(Factor factor, int[] assignment,
		IdentityHashMap<Variable,Integer> positions) {
		int[] local = factor.scope().stream().mapToInt(v -> assignment[positions.get(v)]).toArray();
		return factor.cost(local);
	}

	private static int compare(Choice left, Choice right) {
		int comparison = Integer.compare(left.hardViolations(), right.hardViolations());
		if(comparison != 0)
			return comparison;
		comparison = Double.compare(left.cost(), right.cost());
		return comparison != 0 ? comparison : Integer.compare(left.value(), right.value());
	}
}
