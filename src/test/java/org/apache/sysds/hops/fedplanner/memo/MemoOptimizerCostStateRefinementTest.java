/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Limits;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Result;
import org.junit.Assert;
import org.junit.Test;

public class MemoOptimizerCostStateRefinementTest {
	@Test public void groupedMappingsUseTheIntersectionOfEveryMemberPartition() throws Exception {
		CostTerm first=new CostTerm(new int[] {0,1},r -> (r[0]<2 ? 0 : 1)+0.25*(r[1]%2),0,
			new int[][] {{0,0,1,1},{0,1,0,1}});
		CostTerm second=new CostTerm(new int[] {0,1},r -> (r[0]%2)+0.125*(r[1]<2 ? 0 : 1),0,
			new int[][] {{0,1,0,1},{0,0,1,1}});
		MemoOptimizer optimizer=optimizer(new double[2][4],List.of(first,second),4096);
		optimizer.optimize();
		Assert.assertArrayEquals(new int[] {0,1,2,3},factorClasses(optimizer)[0]);
		Assert.assertArrayEquals(new int[] {0,1,2,3},factorClasses(optimizer)[1]);
	}

	@Test public void anyUnannotatedMemberKeepsTheSafeFallback() throws Exception {
		CostTerm annotated=new CostTerm(new int[] {0,1},r -> r[0]%2+0.25*(r[1]%2),0,
			new int[][] {{0,1,0},{0,1,0}});
		CostTerm unannotated=new CostTerm(new int[] {0,1},r -> 0.125*(r[0]+r[1]),0);
		MemoOptimizer optimizer=optimizer(new double[2][3],List.of(annotated,unannotated),4096);
		optimizer.optimize();
		Assert.assertNull(factorClasses(optimizer));
	}

	@Test public void refinedGroupedFactorsMatchUnannotatedExactSearch() {
		Random random=new Random(8841);
		for(int fixture=0;fixture<20;fixture++) {
			int[][] firstClasses={{0,1,random.nextInt(2)},{0,1,random.nextInt(2)}};
			int[][] secondClasses={{0,1,random.nextInt(2)},{0,1,random.nextInt(2)}};
			ToDoubleFunction<int[]> firstEval=r -> -0.5+0.25*firstClasses[0][r[0]]
				+0.125*firstClasses[1][r[1]];
			ToDoubleFunction<int[]> secondEval=r -> 0.0625*secondClasses[0][r[0]]
				+0.03125*secondClasses[1][r[1]];
			List<CostTerm> annotated=List.of(
				new CostTerm(new int[] {0,1},firstEval,-0.5,firstClasses),
				new CostTerm(new int[] {0,1},secondEval,0,secondClasses));
			List<CostTerm> reference=List.of(new CostTerm(new int[] {0,1},firstEval,-0.5),
				new CostTerm(new int[] {0,1},secondEval,0));
			double[][] nativeCosts=new double[2][3];
			for(double[] row:nativeCosts) for(int ordinal=0;ordinal<row.length;ordinal++)
				row[ordinal]=random.nextInt(7)*0.125;
			for(int cache:new int[] {0,4096}) {
				Result expected=optimizer(nativeCosts,reference,cache).optimize();
				Result actual=optimizer(nativeCosts,annotated,cache).optimize();
				Assert.assertEquals(expected.status(),actual.status());
				Assert.assertEquals(0,expected.cost().compareTo(actual.cost()));
				Assert.assertArrayEquals(expected.selectedRules(),actual.selectedRules());
			}
		}
	}

	private static MemoOptimizer optimizer(double[][] nativeCosts,List<CostTerm> terms,int cache) {
		int[] sizes=Arrays.stream(nativeCosts).mapToInt(row -> row.length).toArray();
		boolean[] coverage=new boolean[sizes.length]; Arrays.fill(coverage,true);
		return new MemoOptimizer(sizes,coverage,nativeCosts,List.of(),terms,
			new Limits(Long.MAX_VALUE,256,cache));
	}
	private static int[][] factorClasses(MemoOptimizer optimizer) throws Exception {
		Field terms=MemoOptimizer.class.getDeclaredField("terms"); terms.setAccessible(true);
		Object factor=((List<?>)terms.get(optimizer)).get(0);
		Field classes=factor.getClass().getDeclaredField("costClasses"); classes.setAccessible(true);
		return (int[][])classes.get(factor);
	}
}
