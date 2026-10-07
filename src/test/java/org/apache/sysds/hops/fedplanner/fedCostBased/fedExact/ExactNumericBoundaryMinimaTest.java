/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.BoundaryMessage;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class ExactNumericBoundaryMinimaTest {
	private static final Limits LIMITS = new Limits(1_000_000,10_000_000);
	private static final Field VALUES = field("values");
	private static final Field LOW = field("lowValues");
	private static final Field LOWER = field("lowerValues");
	private static final Field CHOICES = field("unionChoices");

	@After
	public void clearAblation() {
		System.clearProperty(PruningAblation.PROPERTY);
	}

	@Test
	public void randomizedNumericAndSparseMessagesMatchLegacyObjectMinima() throws Exception {
		Random random = new Random(0x4d494e494d41L);
		for(String pruning : List.of("baseline","local_only")) {
			System.setProperty(PruningAblation.PROPERTY,pruning);
			for(int trial=0; trial<60; trial++) {
				Variable b = new Variable("b-"+trial,3);
				Variable x = new Variable("x-"+trial,4);
				List<Variable> variables = List.of(b,x);
				double[] first = numericTable(random,trial,0);
				double[] second = numericTable(random,trial,1);
				double[] hard = {0d,0d,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
				BoundaryMessage merged = ExactCategoricalSolver.mergeBoundary(
					ExactCategoricalSolver.boundaryLeaves(variables,List.of(
						Factor.dense(List.of(b,x),first),Factor.dense(List.of(b,x),second),
						Factor.dense(List.of(x),hard)),LIMITS),List.of(b),LIMITS);
				double[] high = values(merged,VALUES);
				double[] low = values(merged,LOW);
				double[] lower = values(merged,LOWER);
				double expectedMinimum = legacyMinimum(high,low);
				assertRaw(expectedMinimum,merged.minimum());
				assertRaw(Arrays.stream(lower).min().orElseThrow(),merged.lowerBound());
				double[] bulk = merged.minMarginals(b);
				double[] lowerBulk = merged.lowerMinMarginals(b);
				for(int value=0; value<b.domainSize(); value++) {
					double expected = legacyMinimum(new double[]{high[value]},new double[]{low[value]});
					assertRaw(expected,merged.minMarginal(b,value));
					assertRaw(expected,bulk[value]);
					assertRaw(lower[value],lowerBulk[value]);
					int[] assignment = {value,-1};
					assertRaw(round(high[value],low[value]),merged.valueForAssignment(assignment,variables));
					merged.decodeInto(assignment,variables);
					Assert.assertEquals(value,assignment[0]);
					Assert.assertTrue(assignment[1]>=0 && assignment[1]<x.domainSize());
					Assert.assertFalse(merged.improvesBacktrace(assignment,variables));
				}
				Assert.assertEquals(List.of(b),merged.scope());
				Assert.assertEquals(3L,merged.cells());
				Assert.assertEquals(12L,merged.retainedCells());
				Assert.assertEquals(12L,merged.assignments());
				Assert.assertNotNull(CHOICES.get(merged));
			}
		}
	}

	@Test
	public void sameRoundedResiduesKeepCanonicalFirstChoiceAndRawMinimum() throws Exception {
		Variable x = new Variable("close",2);
		List<Variable> variables = List.of(x);
		BoundaryMessage merged = ExactCategoricalSolver.mergeBoundary(
			ExactCategoricalSolver.boundaryLeaves(variables,List.of(
				Factor.dense(List.of(x),1.0e16,1.0e16),
				Factor.dense(List.of(x),0.5d,1d)),LIMITS),List.of(),LIMITS);
		assertRaw(1.0e16,merged.minimum());
		Assert.assertEquals(0,((int[])CHOICES.get(merged))[0]);
		int[] selected = {-1};
		merged.decodeInto(selected,variables);
		Assert.assertArrayEquals(new int[]{0},selected);
	}

	@Test
	public void overflowStillPrecedesLaterSparseInfinity() {
		Variable x = new Variable("overflow",2);
		List<Variable> variables = List.of(x);
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,() ->
			ExactCategoricalSolver.mergeBoundary(ExactCategoricalSolver.boundaryLeaves(variables,List.of(
				Factor.dense(List.of(x),Double.MAX_VALUE,1d),
				Factor.dense(List.of(x),Double.MAX_VALUE,2d),
				Factor.dense(List.of(x),Double.POSITIVE_INFINITY,0d)),LIMITS),List.of(),LIMITS));
		Assert.assertEquals("EXACT_VE_OBJECTIVE_OVERFLOW",failure.getMessage());
	}

	@Test
	public void negativeZeroRetainsValidationFailureAndInfinityRetainsGetterResults() {
		Variable x = new Variable("raw",3);
		IllegalArgumentException invalid = Assert.assertThrows(IllegalArgumentException.class,() ->
			ExactCategoricalSolver.boundaryLeaf(List.of(x),
				Factor.dense(List.of(x),-0d,0d,Double.POSITIVE_INFINITY),LIMITS));
		Assert.assertEquals("EXACT_VE_FACTOR_COST_INVALID|value=-0.0",invalid.getMessage());
		BoundaryMessage leaf = ExactCategoricalSolver.boundaryLeaf(List.of(x),
			Factor.dense(List.of(x),0d,0d,Double.POSITIVE_INFINITY),LIMITS);
		assertRaw(0d,leaf.minimum());
		assertRaw(0d,leaf.minMarginal(x,0));
		assertRaw(0d,leaf.minMarginal(x,1));
		Assert.assertEquals(Double.POSITIVE_INFINITY,leaf.minMarginal(x,2),0d);
		assertRaw(leaf.minMarginal(x,0),leaf.minMarginals(x)[0]);
	}

	private static double[] numericTable(Random random,int trial,int factor) {
		double[] result = new double[12];
		for(int b=0; b<3; b++)
			for(int x=0; x<4; x++) {
				int cell=b*4+x;
				if(x>=2 && random.nextBoolean())
					result[cell]=Double.POSITIVE_INFINITY;
				else if((trial+factor+cell)%11==0)
					result[cell]=0d;
				else if((trial+factor+cell)%7==0)
					result[cell]=1.0e16;
				else
					result[cell]=random.nextInt(17)*0.125d;
			}
		return result;
	}

	private static double legacyMinimum(double[] high,double[] low) {
		double bestHigh=Double.POSITIVE_INFINITY,bestLow=0d;
		for(int cell=0; cell<high.length; cell++)
			if(Double.compare(round(high[cell],low[cell]),round(bestHigh,bestLow))<0) {
				bestHigh=high[cell];
				bestLow=low[cell];
			}
		return round(bestHigh,bestLow);
	}

	private static double round(double high,double low) {
		if(high==Double.POSITIVE_INFINITY)
			return high;
		double rounded=high+low;
		if(!Double.isFinite(rounded))
			throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
		return rounded;
	}

	private static double[] values(BoundaryMessage message,Field field) throws Exception {
		return ((double[])field.get(message)).clone();
	}

	private static Field field(String name) {
		try {
			Field result=BoundaryMessage.class.getDeclaredField(name);
			result.setAccessible(true);
			return result;
		}
		catch(ReflectiveOperationException failure) {
			throw new ExceptionInInitializerError(failure);
		}
	}

	private static void assertRaw(double expected,double actual) {
		Assert.assertEquals(Long.toHexString(Double.doubleToRawLongBits(expected)),
			Long.toHexString(Double.doubleToRawLongBits(actual)));
	}
}
