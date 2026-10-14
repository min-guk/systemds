/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Limits;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Result;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Status;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

public class MemoOptimizerCostStateTest {
	@Test public void nineteenOrdinalsCollapseToFourExactBoundStates() throws Exception {
		int[] classes=new int[19];
		for(int ordinal=0;ordinal<classes.length;ordinal++) classes[ordinal]=ordinal%4;
		CostTerm term=new CostTerm(new int[] {0,1},r -> r[0]%4+0.25*r[1],-1,
			new int[][] {classes,{0,1}});
		MemoOptimizer optimizer=optimizer(new int[] {19,2},List.of(),List.of(term),8,4096);
		Assert.assertEquals(BigDecimal.ZERO,conditionalBound(optimizer,0));
		Assert.assertEquals("four by two class representatives",8,longField(optimizer,"costEvaluations"));
		Assert.assertEquals(0,longField(optimizer,"wideBoundRelaxations"));
	}

	@Test public void combinedSourceConsumerAndMultiReturnRolesRemainDistinctStates() throws Exception {
		AtomicInteger calls=new AtomicInteger();
		int[][] metadata={{0,1,2},{0,0}};
		CostTerm term=new CostTerm(new int[] {0,1},r -> { calls.incrementAndGet(); return r[0]*0.5; },0,
			metadata);
		metadata[0][1]=0; // constructor owns a defensive copy
		MemoOptimizer optimizer=optimizer(new int[] {3,2},List.of(),List.of(term),16,4096);
		Assert.assertEquals(new BigDecimal(0.5),evaluateCost(optimizer,0,1,0));
		Assert.assertEquals(BigDecimal.ONE,evaluateCost(optimizer,0,2,1));
		Assert.assertEquals(2,calls.get());
		Assert.assertThrows(IllegalArgumentException.class,() -> optimizer(new int[] {3,2},List.of(),
			List.of(new CostTerm(new int[] {0,1},r -> 0,0,new int[][] {{0,2,2},{0,0}})),16,4096));
	}

	@Test public void sameScopeGroupingRefinesEveryMemberPartition() throws Exception {
		CostTerm byFirst=new CostTerm(new int[] {0,1},r -> (r[0]<2 ? 0 : 1)+0.25*(r[1]%2),0,
			new int[][] {{0,0,1,1},{0,1,0,1}});
		CostTerm bySecond=new CostTerm(new int[] {0,1},r -> (r[0]%2)+0.25*(r[1]<2 ? 0 : 1),0,
			new int[][] {{0,1,0,1},{0,0,1,1}});
		MemoOptimizer optimizer=optimizer(new int[] {4,4},List.of(),List.of(byFirst,bySecond),16,4096);
		Result result=optimizer.optimize();
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(new int[] {0,0},result.selectedRules());
		Object factor=((List<?>)field(optimizer,"terms").get(optimizer)).get(0);
		int[][] refined=(int[][])field(factor,"costClasses").get(factor);
		Assert.assertArrayEquals(new int[] {0,1,2,3},refined[0]);
		Assert.assertArrayEquals(new int[] {0,1,2,3},refined[1]);
	}

	@Test public void removedStaticRepresentativeUsesALiveOrdinalFromTheSameClass() throws Exception {
		AtomicInteger actual=new AtomicInteger(-1);
		CostTerm term=new CostTerm(new int[] {0,1},r -> {
			actual.compareAndSet(-1,r[0]); return r[0]<2 ? 0.5 : 1;
		},0,
			new int[][] {{0,0,1},{0,0}});
		MemoOptimizer optimizer=optimizer(new int[] {3,2},List.of(),List.of(term),8,4096);
		invoke(optimizer,"remove",new Class<?>[] {int.class,int.class},0,0);
		Assert.assertEquals(new BigDecimal(0.5),conditionalBound(optimizer,0));
		Assert.assertEquals("ordinal zero was removed; ordinal one represents class zero",1,actual.get());
	}

	@Test public void negativeDyadicAndTheExact256ThresholdRemainExact() throws Exception {
		int[] sixteen=range(16),twoHundredFiftySeven=range(257);
		CostTerm exact=new CostTerm(new int[] {0,1},r -> -0.5+0.0625*r[0]+0.03125*r[1],-0.5,
			new int[][] {sixteen,sixteen});
		MemoOptimizer atLimit=optimizer(new int[] {16,16},List.of(),List.of(exact),256,4096);
		Assert.assertEquals(new BigDecimal(-0.5),conditionalBound(atLimit,0));
		Assert.assertEquals(0,longField(atLimit,"wideBoundRelaxations"));

		CostTerm wide=new CostTerm(new int[] {0,1},r -> r[0]+r[1],-7,
			new int[][] {twoHundredFiftySeven,{0}});
		MemoOptimizer overLimit=optimizer(new int[] {257,1},List.of(),List.of(wide),256,4096);
		Assert.assertEquals(new BigDecimal(-7),conditionalBound(overLimit,0));
		Assert.assertEquals(1,longField(overLimit,"wideBoundRelaxations"));
		Assert.assertEquals(0,longField(overLimit,"costEvaluations"));
	}

	@Test public void absentMetadataKeepsTheOriginalWideFallback() throws Exception {
		CostTerm incompleteProjection=new CostTerm(new int[] {0,1},r -> r[0]+r[1],-3);
		MemoOptimizer optimizer=optimizer(new int[] {19,19},List.of(),List.of(incompleteProjection),256,4096);
		Assert.assertEquals(new BigDecimal(-3),conditionalBound(optimizer,0));
		Assert.assertEquals(1,longField(optimizer,"wideBoundRelaxations"));
		Assert.assertEquals(0,longField(optimizer,"costEvaluations"));
	}

	@Test public void singletonProjectionKeepsRemainingOwnerMetadataAligned() {
		Check fix=new Check(0,new int[] {0},r -> r[0]<0 ? Truth.UNKNOWN : r[0]==1 ? Truth.YES : Truth.NO);
		CostTerm term=new CostTerm(new int[] {2,0,1},r -> r[0]%2+0.5*(r[2]<2 ? 0 : 1),0,
			new int[][] {{0,1,0},{0,1},{0,0,1}});
		MemoOptimizer optimizer=optimizer(new int[] {2,3,3},List.of(fix),List.of(term),16,4096);
		Result result=optimizer.optimize();
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(new int[] {1,0,0},result.selectedRules());
		Assert.assertEquals(BigDecimal.ZERO,result.cost());
	}

	@Test public void seededSmallClassMapsMatchUnannotatedExactSearch() {
		Random random=new Random(7129);
		for(int fixture=0;fixture<12;fixture++) {
			int[][] classes=new int[3][3];
			for(int owner=0;owner<3;owner++) {
				classes[owner][0]=0; classes[owner][1]=1; classes[owner][2]=random.nextInt(2);
			}
			double[][] costs=new double[3][3];
			for(double[] row:costs) for(int ordinal=0;ordinal<row.length;ordinal++)
				row[ordinal]=random.nextInt(9)*0.125;
			java.util.function.ToDoubleFunction<int[]> evaluator=r -> -0.5
				+0.25*classes[0][r[0]]+0.125*classes[1][r[1]]+0.0625*classes[2][r[2]];
			CostTerm annotated=new CostTerm(new int[] {0,1,2},evaluator,-0.5,classes);
			CostTerm reference=new CostTerm(new int[] {0,1,2},evaluator,-0.5);
			for(int cache:new int[] {0,4096}) {
				Result expected=optimizer(costs,List.of(edge(0,1),edge(1,2),edge(2,0)),
					List.of(reference),64,cache).optimize();
				Result actual=optimizer(costs,List.of(edge(0,1),edge(1,2),edge(2,0)),
					List.of(annotated),64,cache).optimize();
				Assert.assertEquals("fixture="+fixture,expected.status(),actual.status());
				Assert.assertEquals("fixture="+fixture,expected.cost(),actual.cost());
				Assert.assertArrayEquals("fixture="+fixture,expected.selectedRules(),actual.selectedRules());
			}
		}
	}

	@Test public void canonicalCostKeysNeverCanonicalizeTheSelectedRuleOrFiniteBudget() {
		CostTerm term=new CostTerm(new int[] {0,1},r -> r[0]<2 ? 0.25 : 1,0.25,
			new int[][] {{0,0,1},{0,0}});
		for(int cache:new int[] {0,4096}) {
			MemoOptimizer exact=optimizer(new int[] {3,2},List.of(edge(0,1)),List.of(term),64,cache);
			Result result=exact.optimize();
			Assert.assertEquals(Status.OPTIMAL,result.status());
			Assert.assertArrayEquals(new int[] {0,0},result.selectedRules());
			Assert.assertEquals(new BigDecimal(0.25),result.cost());
			Assert.assertEquals(Status.RESOURCE_EXHAUSTED,
				optimizer(new int[] {3,2},List.of(edge(0,1)),List.of(term),64,cache,1).optimize().status());
		}
	}

	private static Check edge(int left,int right) {
		return new Check(left,new int[] {left,right},r -> Truth.YES);
	}
	private static int[] range(int size) {
		int[] result=new int[size];
		for(int i=0;i<size;i++) result[i]=i;
		return result;
	}
	private static MemoOptimizer optimizer(int[] sizes,List<Check> checks,List<CostTerm> terms,
		int supportBudget,int cache) {
		return optimizer(sizes,checks,terms,supportBudget,cache,Long.MAX_VALUE);
	}
	private static MemoOptimizer optimizer(int[] sizes,List<Check> checks,List<CostTerm> terms,
		int supportBudget,int cache,long nodes) {
		double[][] costs=new double[sizes.length][];
		for(int owner=0;owner<sizes.length;owner++) costs[owner]=new double[sizes[owner]];
		return optimizer(costs,checks,terms,supportBudget,cache,nodes);
	}
	private static MemoOptimizer optimizer(double[][] costs,List<Check> checks,List<CostTerm> terms,
		int supportBudget,int cache) {
		return optimizer(costs,checks,terms,supportBudget,cache,Long.MAX_VALUE);
	}
	private static MemoOptimizer optimizer(double[][] costs,List<Check> checks,List<CostTerm> terms,
		int supportBudget,int cache,long nodes) {
		int[] sizes=Arrays.stream(costs).mapToInt(row -> row.length).toArray();
		boolean[] coverage=new boolean[sizes.length]; Arrays.fill(coverage,true);
		return new MemoOptimizer(sizes,coverage,costs,checks,terms,new Limits(nodes,supportBudget,cache));
	}
	private static BigDecimal conditionalBound(MemoOptimizer optimizer,int id) throws Exception {
		return (BigDecimal)invoke(optimizer,"computeConditionalCostBound",new Class<?>[] {int.class},id);
	}
	private static BigDecimal evaluateCost(MemoOptimizer optimizer,int id,int... values) throws Exception {
		return (BigDecimal)invoke(optimizer,"evaluateCost",new Class<?>[] {int.class,int[].class},id,values);
	}
	private static long longField(Object receiver,String name) throws Exception {
		return field(receiver,name).getLong(receiver);
	}
	private static Field field(Object receiver,String name) throws Exception {
		Field result=receiver.getClass().getDeclaredField(name); result.setAccessible(true); return result;
	}
	private static Object invoke(Object receiver,String name,Class<?>[] types,Object... arguments) throws Exception {
		Method method=receiver.getClass().getDeclaredMethod(name,types);
		method.setAccessible(true); return method.invoke(receiver,arguments);
	}
}
