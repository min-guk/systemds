/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.math.BigDecimal;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

public class MemoLocalMessagesTest {
	@Test public void invariantWideCheckAxesDoNotCreateArtificialJoin() {
		int[] sizes={2,4,4,2,2};
		double[][] nativeValues={{0,1},{0,0,0,0},{0,0,0,0},{0,1},{0,1}};
		int[][] truthClasses={{0,1},{0,0,0,0},{0,0,0,0}};
		Check projected=new Check(1,new int[] {0,1,2},r -> r[0]==0 ? Truth.YES:Truth.NO,truthClasses);
		List<CostTerm> costs=List.of(
			new CostTerm(new int[] {1,3},r -> r[0]==r[1] ? 0 : 2,0),
			new CostTerm(new int[] {2,4},r -> r[0]%2==r[1] ? 0 : 3,0));
		MemoLocalMessages messages=new MemoLocalMessages(sizes,nativeValues,List.of(projected),costs,domains(sizes),
			new MemoResourcePolicy(Long.MAX_VALUE));
		MemoLocalMessages.Step first=messages.refine(64);

		Assert.assertTrue(first.changed()); Assert.assertEquals(0,first.owner());
		Assert.assertArrayEquals("certified-invariant axes must not survive as message boundaries",
			new int[0],first.scope());
		Assert.assertArrayEquals(new int[] {0,1,2},first.originalOwners());
		while(!messages.complete()) Assert.assertTrue(messages.refine(256).changed());
		BigDecimal expected=exhaustive(sizes,nativeValues,List.of(projected),costs);
		Assert.assertEquals(0,expected.compareTo(messages.lowerBound(domains(sizes))));
		int[] plan=messages.reconstruct(new int[] {0,0,0,0,0});
		Assert.assertNotNull(plan); Assert.assertTrue(legal(plan,List.of(projected)));
		Assert.assertEquals(0,expected.compareTo(cost(plan,nativeValues,costs)));
	}

	@Test public void singletonUnannotatedAxisUsesItsOriginalOrdinalForEvaluation() {
		int[] sizes={2,2}; BitSet[] root=domains(sizes); root[1].clear(); root[1].set(1);
		AtomicInteger wrongOrdinal=new AtomicInteger();
		CostTerm cost=new CostTerm(new int[] {0,1},r -> {
			if(r.length!=2 || r[1]!=1) wrongOrdinal.incrementAndGet();
			return r[0]+r[1];
		},0);
		MemoLocalMessages messages=new MemoLocalMessages(sizes,new double[][] {{0,1},{0,0}},List.of(),List.of(cost),root,
			new MemoResourcePolicy(Long.MAX_VALUE));
		MemoLocalMessages.Step step;
		do { step=messages.refine(8); Assert.assertTrue(step.changed()); } while(step.owner()!=0);
		Assert.assertEquals(0,wrongOrdinal.get());
		Assert.assertArrayEquals(new int[0],step.scope());
	}

	@Test public void materializesOnlySelectedSmallConflictUnderTightMemory() {
		int[] sizes={2,2,64,64}; double[][] nativeValues=new double[4][];
		for(int owner=0;owner<sizes.length;owner++) nativeValues[owner]=new double[sizes[owner]];
		AtomicInteger largeEvaluations=new AtomicInteger();
		CostTerm small=new CostTerm(new int[] {0,1},r -> r[0]==r[1] ? 0 : 3,0);
		CostTerm large=new CostTerm(new int[] {2,3},r -> { largeEvaluations.incrementAndGet(); return r[0]+r[1]; },0);
		MemoLocalMessages messages=new MemoLocalMessages(sizes,nativeValues,List.of(),List.of(small,large),domains(sizes),
			new MemoResourcePolicy(4096));
		MemoLocalMessages.Step step=messages.refine(8);

		Assert.assertTrue(step.changed()); Assert.assertEquals(0,step.owner());
		Assert.assertEquals(0,largeEvaluations.get());
		Assert.assertEquals(1,messages.statistics().candidatePreparations());
		Assert.assertEquals("constant native cost is outside the selected bucket",1,messages.statistics().touchedOriginalFactors());
		Assert.assertEquals(2,messages.statistics().touchedVariables());
		Assert.assertTrue(messages.retainedBytes()<4096);
	}

	@Test(expected=IllegalArgumentException.class)
	public void rejectsCostClassAxisWithWrongOriginalDomainLength() {
		CostTerm malformed=new CostTerm(new int[] {0},r -> 0,0,new int[][] {{0}});
		new MemoLocalMessages(new int[] {2},new double[][] {{0,0}},List.of(),List.of(malformed),domains(new int[] {2}),
			new MemoResourcePolicy(Long.MAX_VALUE));
	}

	@Test(expected=IllegalArgumentException.class)
	public void rejectsTruthClassAxisWithWrongOriginalDomainLength() {
		Check malformed=new Check(1,new int[] {0},r -> Truth.YES,new int[][] {{0}});
		new MemoLocalMessages(new int[] {2},new double[][] {{0,0}},List.of(malformed),List.of(),domains(new int[] {2}),
			new MemoResourcePolicy(Long.MAX_VALUE));
	}

	@Test public void zeroScopeFactorsAreEvaluatedExactlyWithoutCartesianWork() {
		int[] sizes={2}; double[][] nativeValues={{0,1}};
		AtomicInteger evaluations=new AtomicInteger();
		CostTerm constant=new CostTerm(new int[0],r -> { evaluations.incrementAndGet(); return 7; },0);
		MemoLocalMessages feasible=new MemoLocalMessages(sizes,nativeValues,List.of(),List.of(constant),domains(sizes),
			new MemoResourcePolicy(Long.MAX_VALUE));
		Assert.assertEquals(0,new BigDecimal("7").compareTo(feasible.initialBound()));
		Assert.assertEquals(0,new BigDecimal("7").compareTo(feasible.lowerBound(domains(sizes))));
		Assert.assertEquals("projected constants are evaluated once",1,evaluations.get());

		Check impossible=new Check(4,new int[0],r -> Truth.NO);
		MemoLocalMessages infeasible=new MemoLocalMessages(sizes,nativeValues,List.of(impossible),List.of(),domains(sizes),
			new MemoResourcePolicy(Long.MAX_VALUE));
		Assert.assertNull(infeasible.initialBound());
	}

	@Test public void eliminatedInteriorRestrictionsRemainAConservativeRelaxation() {
		int[] sizes={2,2}; double[][] nativeValues={{0,0},{0,0}};
		CostTerm cost=new CostTerm(new int[] {0,1},r -> r[0]==0 ? 0 : 9,0);
		MemoLocalMessages messages=new MemoLocalMessages(sizes,nativeValues,List.of(),List.of(cost),domains(sizes),
			new MemoResourcePolicy(Long.MAX_VALUE));
		MemoLocalMessages.Step step=messages.refine(8);
		Assert.assertTrue(step.changed()); Assert.assertEquals(0,step.owner());
		BitSet[] restricted=domains(sizes); restricted[0].clear(); restricted[0].set(1);
		BigDecimal lower=messages.lowerBound(restricted);
		Assert.assertTrue(lower.compareTo(new BigDecimal("9"))<=0);
	}

	@Test(expected=IllegalArgumentException.class)
	public void residualQueryCannotWidenTheConstructionDomain() {
		int[] sizes={2}; BitSet[] root=domains(sizes); root[0].clear(1);
		MemoLocalMessages messages=new MemoLocalMessages(sizes,new double[][] {{0,1}},List.of(),List.of(),root,
			new MemoResourcePolicy(Long.MAX_VALUE));
		messages.lowerBound(domains(sizes));
	}

	@Test public void nativeCostsPreserveTheOriginalBinary64Objective() {
		int[] sizes={2}; double[][] nativeValues={{0.3,0.1}};
		MemoLocalMessages messages=new MemoLocalMessages(sizes,nativeValues,List.of(),List.of(),domains(sizes),
			new MemoResourcePolicy(Long.MAX_VALUE));
		BigDecimal expected=new BigDecimal(0.1);
		Assert.assertEquals(0,expected.compareTo(messages.initialBound()));
		Assert.assertTrue(messages.refine(2).changed());
		Assert.assertEquals(0,expected.compareTo(messages.lowerBound(domains(sizes))));
		Assert.assertArrayEquals(new int[] {1},messages.reconstruct(new int[] {0}));
	}

	@Test public void incrementalMessagesRaiseSignedRelaxationAndReconstructOptimum() {
		int[] sizes={2,2,2};
		double[][] nativeCosts={{-2,1},{0,3},{1,-1}};
		List<CostTerm> costs=List.of(
			new CostTerm(new int[] {0,1},r -> r[0]==r[1] ? -3 : 5,-3),
			new CostTerm(new int[] {1,2},r -> r[0]!=r[1] ? -4 : 2,-4));
		List<Check> checks=List.of(new Check(1,new int[] {0,2},r -> r[0]==1 && r[1]==1 ? Truth.NO : Truth.YES));
		MemoLocalMessages messages=new MemoLocalMessages(sizes,nativeCosts,checks,costs,domains(sizes),
			new MemoResourcePolicy(Long.MAX_VALUE));
		BigDecimal initial=messages.initialBound();
		while(!messages.complete()) Assert.assertTrue(messages.refine(64).changed());
		BigDecimal exact=exhaustive(sizes,nativeCosts,checks,costs);

		Assert.assertTrue(messages.lowerBound(domains(sizes)).compareTo(initial)>=0);
		Assert.assertEquals(0,exact.compareTo(messages.lowerBound(domains(sizes))));
		int[] plan=messages.reconstruct(new int[] {0,0,0});
		Assert.assertNotNull(plan);
		Assert.assertEquals(0,exact.compareTo(cost(plan,nativeCosts,costs)));
		Assert.assertTrue(legal(plan,checks));
	}

	@Test public void unknownRowsAreAllowedAndNoRowsAreForbidden() {
		int[] sizes={2,2};
		double[][] nativeValues={{0,0},{0,0}};
		Check check=new Check(1,new int[] {0,1},r -> {
			if(r[0]==0 && r[1]==0) return Truth.UNKNOWN;
			return r[0]==1 ? Truth.NO : Truth.YES;
		});
		CostTerm cost=new CostTerm(new int[] {0,1},r -> r[0]==0 && r[1]==0 ? -5 : 2,-5);
		MemoLocalMessages messages=new MemoLocalMessages(sizes,nativeValues,List.of(check),List.of(cost),domains(sizes),
			new MemoResourcePolicy(Long.MAX_VALUE));
		while(!messages.complete()) Assert.assertTrue(messages.refine(16).changed());
		Assert.assertEquals(0,new BigDecimal("-5").compareTo(messages.lowerBound(domains(sizes))));
		Assert.assertArrayEquals(new int[] {0,0},messages.reconstruct(new int[] {0,0}));
	}

	@Test public void certifiedClassesRetainOriginalOrdinalsInMessages() {
		int[] sizes={3,2};
		double[][] nativeValues={{0,0,0},{0,0}};
		int[][] classes={{0,0,1},{0,1}};
		CostTerm cost=new CostTerm(new int[] {0,1},r -> r[0]<2 && r[1]==0 ? 1 : 7,1,classes);
		Check check=new Check(2,new int[] {0},r -> r[0]==2 ? Truth.NO : Truth.YES,new int[][] {{0,0,1}});
		MemoLocalMessages messages=new MemoLocalMessages(sizes,nativeValues,List.of(check),List.of(cost),domains(sizes),
			new MemoResourcePolicy(Long.MAX_VALUE));
		while(!messages.complete()) Assert.assertTrue(messages.refine(32).changed());
		Assert.assertEquals(0,BigDecimal.ONE.compareTo(messages.lowerBound(domains(sizes))));
		int[] plan=messages.reconstruct(new int[] {1,1});
		Assert.assertNotNull(plan);
		Assert.assertNotEquals(2,plan[0]);
	}

	@Test public void rejectedMemoryPublishesNoPartialMessage() {
		int[] sizes={2,2};
		MemoLocalMessages messages=new MemoLocalMessages(sizes,new double[][] {{0,1},{0,1}},List.of(),
			List.of(new CostTerm(new int[] {0,1},r -> r[0]+r[1],0)),domains(sizes),new MemoResourcePolicy(0));
		BigDecimal before=messages.initialBound();
		MemoLocalMessages.Step step=messages.refine(16);
		Assert.assertFalse(step.changed());
		Assert.assertEquals("MEMORY_REJECTED",step.reason());
		Assert.assertEquals(0,messages.retainedBytes());
		Assert.assertEquals(0,before.compareTo(messages.lowerBound(domains(sizes))));
		Assert.assertEquals(0,messages.statistics().completedBuckets());
	}

	@Test public void randomSignedModelsMatchIndependentExhaustiveOracle() {
		Random random=new Random(20261015);
		for(int trial=0;trial<30;trial++) {
			int[] sizes={2,2,2,2}; double[][] nativeValues=new double[4][2];
			for(double[] row:nativeValues) for(int value=0;value<2;value++) row[value]=random.nextInt(9)-4;
			int a=random.nextInt(4),b=(a+1+random.nextInt(3))%4;
			int c=random.nextInt(4),d=(c+1+random.nextInt(3))%4;
			int x=random.nextInt(9)-4,y=random.nextInt(9)-4;
			List<CostTerm> costs=List.of(
				new CostTerm(new int[] {a,b},r -> r[0]==r[1] ? x : y,Math.min(x,y)),
				new CostTerm(new int[] {c,d},r -> r[0]!=r[1] ? y : x,Math.min(x,y)));
			List<Check> checks=List.of(new Check(3,new int[] {0,3},r -> r[0]==1 && r[1]==1 ? Truth.NO : Truth.YES));
			MemoLocalMessages messages=new MemoLocalMessages(sizes,nativeValues,checks,costs,domains(sizes),
				new MemoResourcePolicy(Long.MAX_VALUE));
			while(!messages.complete()) Assert.assertTrue(messages.refine(256).changed());
			BigDecimal expected=exhaustive(sizes,nativeValues,checks,costs);
			Assert.assertEquals("trial "+trial,0,expected.compareTo(messages.lowerBound(domains(sizes))));
			int[] plan=messages.reconstruct(new int[] {0,0,0,0});
			Assert.assertNotNull(plan); Assert.assertTrue(legal(plan,checks));
			Assert.assertEquals("trial "+trial,0,expected.compareTo(cost(plan,nativeValues,costs)));
		}
	}

	private static BitSet[] domains(int[] sizes) {
		BitSet[] result=new BitSet[sizes.length];
		for(int owner=0;owner<sizes.length;owner++) { result[owner]=new BitSet(); result[owner].set(0,sizes[owner]); }
		return result;
	}
	private static BigDecimal exhaustive(int[] sizes,double[][] nativeValues,List<Check> checks,List<CostTerm> costs) {
		BigDecimal best=null; int combinations=1; for(int size:sizes) combinations*=size;
		for(int row=0;row<combinations;row++) {
			int[] plan=new int[sizes.length]; int value=row;
			for(int owner=sizes.length-1;owner>=0;owner--) { plan[owner]=value%sizes[owner]; value/=sizes[owner]; }
			if(!legal(plan,checks)) continue;
			BigDecimal candidate=cost(plan,nativeValues,costs);
			if(best==null || candidate.compareTo(best)<0) best=candidate;
		}
		return best;
	}
	private static boolean legal(int[] plan,List<Check> checks) {
		for(Check check:checks) {
			int[] tuple=new int[check.owners().length]; int[] owners=check.owners();
			for(int axis=0;axis<owners.length;axis++) tuple[axis]=plan[owners[axis]];
			if(check.evaluate(tuple)==Truth.NO) return false;
		}
		return true;
	}
	private static BigDecimal cost(int[] plan,double[][] nativeValues,List<CostTerm> costs) {
		BigDecimal total=BigDecimal.ZERO;
		for(int owner=0;owner<plan.length;owner++) total=total.add(new BigDecimal(nativeValues[owner][plan[owner]]));
		for(CostTerm term:costs) {
			int[] owners=term.owners(),tuple=new int[owners.length];
			for(int axis=0;axis<owners.length;axis++) tuple[axis]=plan[owners[axis]];
			total=total.add(term.evaluateExact(tuple));
		}
		return total;
	}
}
