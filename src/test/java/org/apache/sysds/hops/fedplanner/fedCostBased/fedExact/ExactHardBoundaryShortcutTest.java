/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class ExactHardBoundaryShortcutTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(1_000_000,20_000_000);

	@Test
	public void sparseHardSupportMatchesCanonicalCellScanAcrossWords() {
		for(int size : new int[] {1,63,64,65}) {
			boolean[] finite = new boolean[size];
			finite[0] = true;
			if(size > 2)
				finite[size / 2] = true;
			if(size > 1)
				finite[size - 1] = true;
			assertSupport(size,finite);

			Arrays.fill(finite,false);
			assertSupport(size,finite);
			Arrays.fill(finite,true);
			assertSupport(size,finite);
		}

		boolean[] exactHalf = new boolean[64];
		for(int cell=0; cell<64; cell+=2)
			exactHalf[cell] = true;
		assertSupport(64,exactHalf);
		boolean[] majority = exactHalf.clone();
		majority[1] = true;
		assertSupport(64,majority);
	}

	@Test
	public void hardMinimumAndLowerBoundMatchDenseRawBitsForFiniteAndInfeasibleTables() {
		for(int size : new int[] {1,63,64,65}) {
			for(boolean anyFinite : new boolean[] {false,true}) {
				var value = new ExactCategoricalSolver.Variable("minimum-"+size+"-"+anyFinite,size);
				double[] denseValues = new double[size];
				Arrays.fill(denseValues,Double.POSITIVE_INFINITY);
				if(anyFinite)
					denseValues[size-1] = 0d;
				var dense = ExactCategoricalSolver.boundaryLeaf(List.of(value),
					ExactCategoricalSolver.Factor.dense(List.of(value),denseValues),LIMITS);
				var hard = ExactCategoricalSolver.boundaryLeaf(List.of(value),
					ExactCategoricalSolver.Factor.lazy(List.of(value),v -> denseValues[v[0]]),LIMITS);
				assertRawEquals(dense.minimum(),hard.minimum());
				assertRawEquals(dense.lowerBound(),hard.lowerBound());
			}
		}
	}

	@Test
	public void hardCertificateShortcutMatchesDenseAndKeepsNumericFailures() throws Exception {
		var x = new ExactCategoricalSolver.Variable("certificate-x",65);
		double[] hardValues = new double[65];
		for(int cell=0; cell<hardValues.length; cell++)
			hardValues[cell] = cell == 0 || cell == 64 ? 0d : Double.POSITIVE_INFINITY;
		var denseHard = leaf(x,ExactCategoricalSolver.Factor.dense(List.of(x),hardValues));
		var packedHard = leaf(x,ExactCategoricalSolver.Factor.lazy(List.of(x),v -> hardValues[v[0]]));
		var dyadic = leaf(x,ExactCategoricalSolver.Factor.lazy(List.of(x),v -> v[0] * 0.25d));
		var unit = leaf(x,ExactCategoricalSolver.Factor.dense(List.of(x),constantValues(65,1d)));
		var tiny = leaf(x,ExactCategoricalSolver.Factor.dense(List.of(x),constantValues(65,0x1p-100)));

		Assert.assertEquals(exactNonnegative(List.of(denseHard,dyadic)),
			exactNonnegative(List.of(packedHard,dyadic)));
		Assert.assertTrue(exactNonnegative(List.of(packedHard,dyadic)));
		Assert.assertFalse(exactNonnegative(List.of(packedHard,unit,tiny)));
	}

	@Test
	public void packedHardMergeMatchesDenseCostsChoicesCutsAndCounters() {
		var x = new ExactCategoricalSolver.Variable("merge-x",3);
		var y = new ExactCategoricalSolver.Variable("merge-y",3);
		List<ExactCategoricalSolver.Variable> variables = List.of(x,y);
		double[] hardValues = {
			Double.POSITIVE_INFINITY,0d,Double.POSITIVE_INFINITY,
			0d,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,
			Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,0d};
		var numericX = ExactCategoricalSolver.Factor.dense(List.of(x),0x1p53,4d,2d);
		var numericY = ExactCategoricalSolver.Factor.dense(List.of(y),1d,0x1p-53,3d);
		var denseHard = ExactCategoricalSolver.Factor.dense(List.of(x,y),hardValues);
		var packedHard = ExactCategoricalSolver.Factor.lazy(List.of(x,y),v -> hardValues[v[0]*3+v[1]]);
		var denseCounters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var hardCounters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var dense = merge(variables,List.of(numericX,denseHard,numericY),denseCounters);
		var hard = merge(variables,List.of(numericX,packedHard,numericY),hardCounters);

		assertRawEquals(dense.minimum(),hard.minimum());
		assertRawEquals(dense.lowerBound(),hard.lowerBound());
		int[] denseAssignment = {2,2};
		int[] hardAssignment = {2,2};
		dense.decodeInto(denseAssignment,variables);
		hard.decodeInto(hardAssignment,variables);
		Assert.assertArrayEquals(denseAssignment,hardAssignment);
		Assert.assertEquals(denseCounters.fullChildEvaluations(),hardCounters.fullChildEvaluations());
		Assert.assertEquals(denseCounters.childEvaluations(),hardCounters.childEvaluations());
		Assert.assertEquals(denseCounters.infeasibleCuts(),hardCounters.infeasibleCuts());
		Assert.assertEquals(denseCounters.costCuts(),hardCounters.costCuts());
	}

	@Test
	public void invalidLazyCostStillFailsBeforeBoundaryShortcuts() {
		var x = new ExactCategoricalSolver.Variable("invalid",2);
		try {
			ExactCategoricalSolver.boundaryLeaf(List.of(x),ExactCategoricalSolver.Factor.lazy(
				List.of(x),v -> v[0] == 0 ? 0d : Double.NaN),LIMITS);
			Assert.fail("invalid lazy cost must be rejected");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage(),
				expected.getMessage().startsWith("EXACT_VE_FACTOR_COST_INVALID"));
		}
	}

	private static void assertSupport(int size, boolean[] finite) {
		var value = new ExactCategoricalSolver.Variable("support-"+size+"-"+Arrays.hashCode(finite),size);
		var message = leaf(value,ExactCategoricalSolver.Factor.lazy(List.of(value),v ->
			finite[v[0]] ? 0d : Double.POSITIVE_INFINITY));
		int finiteCount = 0;
		for(boolean allowed : finite)
			if(allowed)
				finiteCount++;
		if(finiteCount == size || (long)finiteCount * 2 > size) {
			Assert.assertNull(message.hardSupport());
			return;
		}
		int[] expected = new int[finiteCount];
		for(int cell=0, output=0; cell<size; cell++)
			if(finite[cell])
				expected[output++] = cell;
		Assert.assertArrayEquals(expected,message.hardSupport().finiteCells());
	}

	private static ExactCategoricalSolver.BoundaryMessage leaf(ExactCategoricalSolver.Variable variable,
		ExactCategoricalSolver.Factor factor) {
		return ExactCategoricalSolver.boundaryLeaf(List.of(variable),factor,LIMITS);
	}

	private static ExactCategoricalSolver.BoundaryMessage merge(
		List<ExactCategoricalSolver.Variable> variables,List<ExactCategoricalSolver.Factor> factors,
		ExactCategoricalSolver.BoundaryMergeCounters counters) {
		return ExactCategoricalSolver.mergeBoundary(
			ExactCategoricalSolver.boundaryLeaves(variables,factors,LIMITS),List.of(),LIMITS,counters);
	}

	private static boolean exactNonnegative(List<ExactCategoricalSolver.BoundaryMessage> messages)
		throws Exception {
		Method method = ExactCategoricalSolver.class.getDeclaredMethod(
			"exactNonnegativeBoundarySum",List.class);
		method.setAccessible(true);
		try { return (boolean)method.invoke(null,messages); }
		catch(InvocationTargetException failure) {
			if(failure.getCause() instanceof RuntimeException runtime)
				throw runtime;
			throw failure;
		}
	}

	private static double[] constantValues(int size, double value) {
		double[] values = new double[size];
		Arrays.fill(values,value);
		return values;
	}

	private static void assertRawEquals(double expected, double actual) {
		Assert.assertEquals(Double.doubleToRawLongBits(expected),Double.doubleToRawLongBits(actual));
	}
}
