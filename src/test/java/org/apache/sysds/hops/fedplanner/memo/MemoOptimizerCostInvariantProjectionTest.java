/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Limits;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Result;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Status;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

public class MemoOptimizerCostInvariantProjectionTest {
	@Test public void singleClassCostOwnerKeepsItsNativeAndLegalityChoice() throws Exception {
		double[][] nativeCosts={{4,3,1},{0,2},{0,1}};
		Check legal=new Check(0,new int[] {0},r -> r[0]<0 ? Truth.UNKNOWN
			: r[0]==0 ? Truth.NO : Truth.YES);
		ToDoubleFunction<int[]> evaluator=r -> 0.25*r[1]+0.125*r[2];
		CostTerm annotated=new CostTerm(new int[] {0,1,2},evaluator,0,
			new int[][] {{0,0,0},{0,1},{0,1}});
		CostTerm reference=new CostTerm(new int[] {0,1,2},evaluator,0);
		Result expected=optimizer(nativeCosts,List.of(legal),List.of(reference),64,4096,Long.MAX_VALUE)
			.optimize();
		MemoOptimizer projected=optimizer(nativeCosts,List.of(legal),List.of(annotated),64,4096,
			Long.MAX_VALUE);
		Result actual=projected.optimize();
		assertSame(expected,actual);
		Assert.assertArrayEquals(new int[] {2,0,0},actual.selectedRules());
		Assert.assertEquals(new BigDecimal(1),actual.cost());
		Assert.assertArrayEquals(new int[] {1,2},remainingTermOwners(projected));
	}

	@Test public void initialPruningCanExposeOneClassAndUsesALiveRepresentative() throws Exception {
		double[][] nativeCosts={{0,2,1},{0,0},{0,0}};
		Check removeClassZero=new Check(0,new int[] {0},r -> r[0]<0 ? Truth.UNKNOWN
			: r[0]==0 ? Truth.NO : Truth.YES);
		ToDoubleFunction<int[]> evaluator=r -> (r[0]==0 ? 8 : 0)+0.5*r[1]+0.25*r[2];
		CostTerm annotated=new CostTerm(new int[] {0,1,2},evaluator,0,
			new int[][] {{0,1,1},{0,1},{0,1}});
		CostTerm reference=new CostTerm(new int[] {0,1,2},evaluator,0);
		MemoOptimizer projected=optimizer(nativeCosts,List.of(removeClassZero),List.of(annotated),64,
			4096,Long.MAX_VALUE);
		Result actual=projected.optimize();
		Result expected=optimizer(nativeCosts,List.of(removeClassZero),List.of(reference),64,4096,
			Long.MAX_VALUE).optimize();
		assertSame(expected,actual);
		Assert.assertArrayEquals(new int[] {2,0,0},actual.selectedRules());
		Assert.assertArrayEquals(new int[] {1,2},remainingTermOwners(projected));
	}

	@Test public void costProjectionNeverDischargesUnknownLegality() {
		Check unresolved=new Check(4,new int[] {0},r -> r[0]<0 ? Truth.UNKNOWN
			: r[0]==0 ? Truth.YES : Truth.UNKNOWN);
		CostTerm annotated=new CostTerm(new int[] {0,1,2},r -> 0.25*r[1],0,
			new int[][] {{0,0},{0,1},{0,0}});
		MemoOptimizer optimizer=optimizer(new double[][] {{1,0},{0,0},{0,0}},List.of(unresolved),
			List.of(annotated),64,4096,Long.MAX_VALUE);
		Assert.assertEquals(Status.UNRESOLVED,optimizer.optimize().status());
	}

	@Test public void projectedCostGraphStillSharesTheFiniteNodeBudget() {
		CostTerm annotated=new CostTerm(new int[] {0,1,2},r -> 0.25*r[1]+0.125*r[2],0,
			new int[][] {{0,0},{0,1},{0,1}});
		List<Check> cycle=List.of(edge(0,1),edge(1,2),edge(2,0));
		MemoOptimizer exact=optimizer(new double[3][2],cycle,List.of(annotated),64,4096,
			Long.MAX_VALUE);
		Result optimum=exact.optimize();
		Assert.assertEquals(Status.OPTIMAL,optimum.status());
		Assert.assertEquals(Status.RESOURCE_EXHAUSTED,optimizer(new double[3][2],cycle,
			List.of(annotated),64,4096,1).optimize().status());
	}

	@Test public void seededInvariantProjectionMatchesFullOrdinalExhaustiveSearch() {
		Random random=new Random(1987);
		for(int fixture=0;fixture<16;fixture++) {
			double[][] nativeCosts=new double[4][3];
			for(double[] row:nativeCosts) for(int ordinal=0;ordinal<row.length;ordinal++)
				row[ordinal]=random.nextInt(9)*0.125;
			int[][] classes={{0,0,0},{0,1,random.nextInt(2)},
				{0,1,random.nextInt(2)},{0,1,random.nextInt(2)}};
			ToDoubleFunction<int[]> evaluator=r -> -0.5+0.25*classes[1][r[1]]
				+0.125*classes[2][r[2]]+0.0625*classes[3][r[3]];
			CostTerm annotated=new CostTerm(new int[] {0,1,2,3},evaluator,-0.5,classes);
			CostTerm reference=new CostTerm(new int[] {0,1,2,3},evaluator,-0.5);
			List<Check> checks=List.of(edge(0,1),edge(1,2),edge(2,3),edge(3,0));
			for(int cache:new int[] {0,4096}) {
				Result expected=optimizer(nativeCosts,checks,List.of(reference),64,cache,Long.MAX_VALUE)
					.optimize();
				Result actual=optimizer(nativeCosts,checks,List.of(annotated),64,cache,Long.MAX_VALUE)
					.optimize();
				assertSame(expected,actual);
			}
		}
	}

	private static Check edge(int left,int right) {
		return new Check(left,new int[] {left,right},r -> Truth.YES);
	}
	private static MemoOptimizer optimizer(double[][] costs,List<Check> checks,List<CostTerm> terms,
		int supportBudget,int cache,long nodes) {
		int[] sizes=Arrays.stream(costs).mapToInt(row -> row.length).toArray();
		boolean[] coverage=new boolean[sizes.length]; Arrays.fill(coverage,true);
		return new MemoOptimizer(sizes,coverage,costs,checks,terms,new Limits(nodes,supportBudget,cache));
	}
	private static int[] remainingTermOwners(MemoOptimizer optimizer) throws Exception {
		Field terms=MemoOptimizer.class.getDeclaredField("terms"); terms.setAccessible(true);
		Object factor=((List<?>)terms.get(optimizer)).get(0);
		Field owners=factor.getClass().getDeclaredField("owners"); owners.setAccessible(true);
		return ((int[])owners.get(factor)).clone();
	}
	private static void assertSame(Result expected,Result actual) {
		Assert.assertEquals(expected.status(),actual.status());
		Assert.assertEquals(0,expected.cost().compareTo(actual.cost()));
		Assert.assertArrayEquals(expected.selectedRules(),actual.selectedRules());
	}
}
