/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.ConditionalRegion;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class ExactConditionalSupportCertifiedValidationTest {
	private static final Limits LIMITS = new Limits(1_000_000, 10_000_000);

	@Test
	public void binaryPreflightReadsEachRegionOnlyForStructuralBudgetValidation()
		throws Exception {
		Variable left = new Variable("certified-left", 4);
		Variable right = new Variable("certified-right", 5);
		Factor selectorFirst = Factor.conditionalSupport(List.of(left, right), 0,
			new int[] {0, 1, 3}, List.of(
				region(0, null, new int[] {0, 2}),
				region(1, null, new int[0]),
				region(3, null, new int[] {1, 4})));
		Factor selectorLast = Factor.conditionalSupport(List.of(left, right), 1,
			new int[] {0, 2, 4}, List.of(
				region(0, new int[] {0, 3}, null),
				region(2, new int[0], null),
				region(4, new int[] {1, 2}, null)));
		CountingList<?> firstRegions = countRegionReads(selectorFirst);
		CountingList<?> lastRegions = countRegionReads(selectorLast);

		invokeBinaryFreeze(List.of(left, right), List.of(selectorFirst, selectorLast));

		Assert.assertEquals("first-axis regions must not be revisited through Cartesian validation", 3,
			firstRegions.reads());
		Assert.assertEquals("last-axis regions must not be revisited through Cartesian validation", 3,
			lastRegions.reads());
	}

	@Test
	public void fixedSeedBinaryRelationsMatchDenseReductionAndSolveExactly() {
		Random random = new Random(0x434f4e4456414cL);
		boolean sawUnconstrainedSelector = false;
		boolean sawEmptyRegion = false;
		for(int trial = 0; trial < 120; trial++) {
			int leftSize = 2 + random.nextInt(3);
			int rightSize = 2 + random.nextInt(3);
			Variable left = new Variable("random-left-" + trial, leftSize);
			Variable right = new Variable("random-right-" + trial, rightSize);
			List<Variable> variables = List.of(left, right);
			int selectorAxis = trial & 1;
			int selectorSize = variables.get(selectorAxis).domainSize();
			int otherAxis = 1 - selectorAxis;
			int otherSize = variables.get(otherAxis).domainSize();
			List<Integer> constrainedValues = new ArrayList<>();
			List<ConditionalRegion> regions = new ArrayList<>();
			for(int selector = 0; selector < selectorSize; selector++) {
				if(!random.nextBoolean())
					continue;
				constrainedValues.add(selector);
				int copies = random.nextInt(3);
				for(int copy = 0; copy < copies; copy++) {
					int[] allowedValues = java.util.stream.IntStream.range(0, otherSize)
						.filter(ignored -> random.nextBoolean()).toArray();
					sawEmptyRegion |= allowedValues.length == 0;
					int[][] allowed = new int[2][];
					allowed[otherAxis] = allowedValues;
					regions.add(new ConditionalRegion(selector, allowed));
				}
			}
			int[] constrained = constrainedValues.stream().mapToInt(Integer::intValue).toArray();
			sawUnconstrainedSelector |= constrained.length < selectorSize;
			Factor conditional = Factor.conditionalSupport(variables, selectorAxis, constrained, regions);
			Factor dense = Factor.dense(variables, denseCosts(conditional));
			List<Factor> numeric = List.of(
				Factor.dense(List.of(left), unaryCosts(leftSize, 0.25d)),
				Factor.dense(List.of(right), unaryCosts(rightSize, 0.125d)));
			List<Factor> compactFactors = List.of(conditional, numeric.get(0), numeric.get(1));
			List<Factor> denseFactors = List.of(dense, numeric.get(0), numeric.get(1));

			ExactPhysicalReducedSolver.CompactModel compact = null;
			ExactPhysicalReducedSolver.CompactModel explicit = null;
			IllegalArgumentException compactFailure = null;
			IllegalArgumentException explicitFailure = null;
			try { compact = ExactPhysicalReducedSolver.reducedModel(2, variables, compactFactors, LIMITS); }
			catch(IllegalArgumentException failure) { compactFailure = failure; }
			try { explicit = ExactPhysicalReducedSolver.reducedModel(2, variables, denseFactors, LIMITS); }
			catch(IllegalArgumentException failure) { explicitFailure = failure; }
			if(compactFailure != null || explicitFailure != null) {
				Assert.assertNotNull("trial=" + trial, compactFailure);
				Assert.assertNotNull("trial=" + trial, explicitFailure);
				Assert.assertEquals("trial=" + trial, explicitFailure.getMessage(), compactFailure.getMessage());
				continue;
			}
			for(int variable = 0; variable < variables.size(); variable++)
				for(int value = 0; value < variables.get(variable).domainSize(); value++)
					Assert.assertEquals("trial=" + trial + " variable=" + variable + " value=" + value,
						explicit.reducedValue(variable, value), compact.reducedValue(variable, value));
			var tie = (ExactCategoricalSolver.TieCostFunction)(variable, value) -> value;
			var compactResult = ExactPhysicalReducedSolver.solve(2, variables, compactFactors, LIMITS, tie);
			var explicitResult = ExactPhysicalReducedSolver.solve(2, variables, denseFactors, LIMITS, tie);
			Assert.assertEquals("trial=" + trial, explicitResult.assignmentInVariableOrder(),
				compactResult.assignmentInVariableOrder());
			Assert.assertEquals("trial=" + trial,
				Double.doubleToRawLongBits(explicitResult.objective()),
				Double.doubleToRawLongBits(compactResult.objective()));
		}
		Assert.assertTrue("fixed seed must cover wildcard selectors", sawUnconstrainedSelector);
		Assert.assertTrue("fixed seed must cover empty stored regions", sawEmptyRegion);
	}

	@Test
	public void allConstrainedEmptyRegionsRetainTheExactInfeasibleFailure() {
		Variable selector = new Variable("empty-selector", 3);
		Variable target = new Variable("empty-target", 4);
		Factor conditional = Factor.conditionalSupport(List.of(selector, target), 0,
			new int[] {0, 1, 2}, List.of(
				region(0, null, new int[0]), region(1, null, new int[0]),
				region(2, null, new int[0])));
		Factor dense = Factor.dense(List.of(selector, target), denseCosts(conditional));
		IllegalArgumentException compact = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalReducedSolver.reducedModel(2, List.of(selector, target),
				List.of(conditional), LIMITS));
		IllegalArgumentException explicit = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalReducedSolver.reducedModel(2, List.of(selector, target),
				List.of(dense), LIMITS));
		Assert.assertEquals(explicit.getMessage(), compact.getMessage());
		Assert.assertEquals("EXACT_VE_NO_FEASIBLE_ASSIGNMENT", compact.getMessage());
	}

	private static double[] unaryCosts(int size, double scale) {
		double[] values = new double[size];
		for(int value = 0; value < size; value++)
			values[value] = value * scale;
		return values;
	}

	private static double[] denseCosts(Factor factor) {
		int[] domains = factor.scope().stream().mapToInt(Variable::domainSize).toArray();
		int cells = Arrays.stream(domains).reduce(1, Math::multiplyExact);
		double[] values = new double[cells];
		int[] coordinates = new int[domains.length];
		for(int cell = 0; cell < cells; cell++) {
			int remaining = cell;
			for(int axis = domains.length - 1; axis >= 0; axis--) {
				coordinates[axis] = remaining % domains[axis];
				remaining /= domains[axis];
			}
			values[cell] = factor.cost(coordinates);
		}
		return values;
	}

	private static ConditionalRegion region(int selector, int[]... allowed) {
		return new ConditionalRegion(selector, allowed);
	}

	private static void invokeBinaryFreeze(List<Variable> variables, List<Factor> factors)
		throws Exception {
		Method method = ExactPhysicalReducedSolver.class.getDeclaredMethod(
			"freezeNonPartialBinaryInputs", List.class, List.class, Limits.class);
		method.setAccessible(true);
		method.invoke(null, variables, factors, LIMITS);
	}

	private static CountingList<?> countRegionReads(Factor factor) throws Exception {
		Field evaluator = Factor.class.getDeclaredField("evaluator");
		evaluator.setAccessible(true);
		Object conditional = evaluator.get(factor);
		Field regions = conditional.getClass().getDeclaredField("regions");
		regions.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<Object> original = (List<Object>)regions.get(conditional);
		CountingList<Object> counted = new CountingList<>(original);
		regions.set(conditional, counted);
		return counted;
	}

	private static final class CountingList<T> extends AbstractList<T> {
		private final List<T> values;
		private int reads;
		private CountingList(List<T> values) { this.values = List.copyOf(values); }
		@Override public T get(int index) { reads++; return values.get(index); }
		@Override public int size() { return values.size(); }
		private int reads() { return reads; }
	}
}
