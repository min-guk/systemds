/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.BoundaryMergeCounters;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.BoundaryMessage;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

public class ExactPrimitiveBoundaryMergeTest {
	private static final Limits LIMITS = new Limits(1_000_000,10_000_000);
	private static final Field VALUES = field("values");
	private static final Field LOW_VALUES = field("lowValues");
	private static final Field LOWER_VALUES = field("lowerValues");
	private static final Field CHOICES = field("unionChoices");

	@After
	public void clearAblation() {
		System.clearProperty(PruningAblation.PROPERTY);
	}

	@Test
	public void randomizedDenseMergeMatchesLegacyArithmeticWithAndWithoutPruning() throws Exception {
		StringBuilder transcript = new StringBuilder();
		for(String variant : List.of("baseline","local_only")) {
			System.setProperty(PruningAblation.PROPERTY,variant);
			Random random = new Random(0x42C057L);
			for(int trial=0; trial<80; trial++) {
				Variable boundary = new Variable("b-"+trial,3);
				Variable internal = new Variable("x-"+trial,4);
				List<Variable> variables = List.of(boundary,internal);
				double[][] tables = new double[2+random.nextInt(4)][12];
				for(double[] table : tables)
					for(int cell=0; cell<table.length; cell++)
						table[cell] = random.nextInt(33)*0.125d;
				BoundaryMergeCounters counters = new BoundaryMergeCounters();
				BoundaryMessage merged = ExactCategoricalSolver.mergeBoundary(
					leaves(variables,List.of(boundary,internal),tables),List.of(boundary),
					LIMITS,12L,counters);
				Snapshot actual = snapshot(merged);
				for(int b=0; b<3; b++) {
					LegacyCost expected = LegacyCost.infinity();
					int winner = -1;
					for(int x=0; x<4; x++) {
						LegacyCost candidate = LegacyCost.zero();
						for(double[] table : tables)
							candidate = candidate.plus(table[b*4+x],0d);
						if(candidate.compareTo(expected)<0) {
							expected=candidate;
							winner=x;
						}
					}
					assertRaw(expected.high,actual.high[b]);
					assertRaw(expected.low,actual.low[b]);
					Assert.assertEquals(b*4+winner,actual.choices[b]);
					int[] decoded={b,-1};
					merged.decodeInto(decoded,variables);
					Assert.assertArrayEquals(new int[]{b,winner},decoded);
				}
				append(transcript,variant,trial,actual,counters);
			}
		}
		System.out.println("PRIMITIVE_MERGE_TRANSCRIPT="+Integer.toHexString(transcript.toString().hashCode())
			+":"+transcript.length());
	}

	@Test
	public void roundedResidueTieKeepsCanonicalFirstAssignment() throws Exception {
		Variable x = new Variable("residue-tie",2);
		List<Variable> variables=List.of(x);
		BoundaryMessage merged=merge(variables,List.of(
			Factor.dense(List.of(x),1.0e16,1.0e16),
			Factor.dense(List.of(x),0.5d,1d)),List.of());
		Snapshot actual=snapshot(merged);
		Assert.assertEquals(0,actual.choices[0]);
		int[] decoded={-1};
		merged.decodeInto(decoded,variables);
		Assert.assertArrayEquals(new int[]{0},decoded);
		assertRaw(1.0e16,actual.high[0]);
		assertRaw(0.5d,actual.low[0]);
	}

	@Test
	public void earlierOverflowStillPrecedesLaterHardInfinity() {
		Variable x = new Variable("late-infinity",2);
		List<Variable> variables=List.of(x);
		IllegalArgumentException failure=Assert.assertThrows(IllegalArgumentException.class,() -> merge(
			variables,List.of(
				Factor.dense(List.of(x),Double.MAX_VALUE,1d),
				Factor.dense(List.of(x),Double.MAX_VALUE,2d),
				Factor.dense(List.of(x),Double.POSITIVE_INFINITY,0d)),List.of()));
		Assert.assertEquals("EXACT_VE_OBJECTIVE_OVERFLOW",failure.getMessage());
	}

	@Test
	public void sparseJoinPreservesCanonicalChoiceRawCostsAndMetrics() throws Exception {
		Variable x=new Variable("sparse-x",3);
		Variable y=new Variable("sparse-y",3);
		List<Variable> variables=List.of(x,y);
		double[] hard=new double[9];
		Arrays.fill(hard,Double.POSITIVE_INFINITY);
		hard[2]=0d;
		hard[3]=0d;
		List<BoundaryMessage> leaves=ExactCategoricalSolver.boundaryLeaves(variables,List.of(
			Factor.dense(List.of(x),0d,0d,0d),
			Factor.dense(List.of(y),0d,0d,0d),
			Factor.dense(List.of(y,x),hard)),LIMITS);
		BoundaryMergeCounters counters=new BoundaryMergeCounters();
		BoundaryMessage merged=ExactCategoricalSolver.mergeBoundary(leaves,List.of(),LIMITS,counters);
		Snapshot actual=snapshot(merged);
		assertRaw(0d,actual.high[0]);
		assertRaw(0d,actual.low[0]);
		Assert.assertEquals(1,actual.choices[0]);
		Assert.assertEquals(27L,counters.fullChildEvaluations());
		Assert.assertEquals(6L,counters.childEvaluations());
		int[] decoded={-1,-1};
		merged.decodeInto(decoded,variables);
		Assert.assertArrayEquals(new int[]{0,1},decoded);
	}

	private static List<BoundaryMessage> leaves(List<Variable> variables,List<Variable> scope,
		double[][] tables) {
		List<Factor> factors=new ArrayList<>();
		for(double[] table:tables)
			factors.add(Factor.dense(scope,table));
		return ExactCategoricalSolver.boundaryLeaves(variables,factors,LIMITS);
	}

	private static BoundaryMessage merge(List<Variable> variables,List<Factor> factors,
		List<Variable> boundary) {
		return ExactCategoricalSolver.mergeBoundary(
			ExactCategoricalSolver.boundaryLeaves(variables,factors,LIMITS),boundary,LIMITS);
	}

	private static Snapshot snapshot(BoundaryMessage message) throws Exception {
		return new Snapshot(((double[])VALUES.get(message)).clone(),
			((double[])LOW_VALUES.get(message)).clone(),
			((double[])LOWER_VALUES.get(message)).clone(),
			((int[])CHOICES.get(message)).clone());
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

	private static void append(StringBuilder out,String variant,int trial,Snapshot snapshot,
		BoundaryMergeCounters counters) {
		out.append(variant).append(':').append(trial).append(':');
		for(double value:snapshot.high) out.append(Long.toHexString(Double.doubleToRawLongBits(value))).append(',');
		for(double value:snapshot.low) out.append(Long.toHexString(Double.doubleToRawLongBits(value))).append(',');
		for(double value:snapshot.lower) out.append(Long.toHexString(Double.doubleToRawLongBits(value))).append(',');
		out.append(Arrays.toString(snapshot.choices)).append(':')
			.append(counters.childEvaluations()).append(':')
			.append(counters.fullChildEvaluations()).append(':')
			.append(counters.infeasibleCuts()).append(':').append(counters.costCuts()).append(';');
	}

	private static void assertRaw(double expected,double actual) {
		Assert.assertEquals(Long.toHexString(Double.doubleToRawLongBits(expected)),
			Long.toHexString(Double.doubleToRawLongBits(actual)));
	}

	private record Snapshot(double[] high,double[] low,double[] lower,int[] choices) {}

	private record LegacyCost(double high,double low) implements Comparable<LegacyCost> {
		static LegacyCost zero() { return new LegacyCost(0d,0d); }
		static LegacyCost infinity() { return new LegacyCost(Double.POSITIVE_INFINITY,0d); }
		LegacyCost plus(double thatHigh,double thatLow) {
			if(high==Double.POSITIVE_INFINITY || thatHigh==Double.POSITIVE_INFINITY)
				return infinity();
			double sum=high+thatHigh;
			if(!Double.isFinite(sum)) throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double virtual=sum-high;
			double error=(high-(sum-virtual))+(thatHigh-virtual);
			error+=low+thatLow;
			if(!Double.isFinite(error)) throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double normalizedHigh=sum+error;
			if(!Double.isFinite(normalizedHigh))
				throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			return new LegacyCost(normalizedHigh,error-(normalizedHigh-sum));
		}
		double rounded() {
			if(high==Double.POSITIVE_INFINITY) return high;
			double result=high+low;
			if(!Double.isFinite(result)) throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			return result;
		}
		@Override public int compareTo(LegacyCost that) {
			return Double.compare(rounded(),that.rounded());
		}
	}
}
