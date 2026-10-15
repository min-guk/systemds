/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Limits;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.PropagationResult;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

public class MemoLocalPropagationTest {
	@Test public void propagationNeverInvokesCostEvaluatorOrOptimizationKernel() {
		AtomicInteger costs=new AtomicInteger();
		CostTerm unused=new CostTerm(new int[] {0,1},r -> { costs.incrementAndGet(); return 1; },0);
		Check equal=completeCheck(new int[] {0,1},tuple -> tuple[0]==tuple[1]);
		BitSet[] restrictions=domains(2,2); restrictions[1].clear(); restrictions[1].set(1);
		MemoOptimizer optimizer=new MemoOptimizer(new int[] {2,2},new boolean[] {true,true},
			new double[][] {{0,0},{0,0}},List.of(equal),List.of(unused),new Limits(1,64,64));
		PropagationResult result=optimizer.propagateOnly(restrictions);

		Assert.assertTrue(result.feasible()); Assert.assertTrue(result.completeCoverage());
		Assert.assertEquals(bitset(1),result.domains()[0]);
		Assert.assertEquals(bitset(1),result.domains()[1]);
		Assert.assertEquals(0,costs.get());
		Assert.assertEquals(0,result.statistics().costEvaluations());
		Assert.assertEquals(0,result.statistics().eliminationCells());
		Assert.assertEquals(0,result.statistics().nodes());
	}

	@Test public void unknownSupportRetainsEveryOrdinal() {
		Check unknown=new Check(1,new int[] {0,1},r -> Truth.UNKNOWN);
		MemoOptimizer optimizer=optimizer(new int[] {3,2},List.of(unknown),new Limits(1,8,32));
		PropagationResult result=optimizer.propagateOnly(null);
		Assert.assertTrue(result.feasible());
		Assert.assertArrayEquals(domains(3,2),result.domains());
		Assert.assertEquals(0,result.statistics().removedRules());
	}

	@Test public void exactRestrictionIsPreservedAndReturnedDomainsAreDefensive() {
		Check onlyOne=completeCheck(new int[] {0},tuple -> tuple[0]==1);
		MemoOptimizer optimizer=optimizer(new int[] {3},List.of(onlyOne),new Limits(1,16,16));
		BitSet[] restriction=domains(3); restriction[0].clear(); restriction[0].set(1);
		PropagationResult result=optimizer.propagateOnly(restriction);
		BitSet[] first=result.domains(); first[0].set(2);
		Assert.assertEquals(bitset(1),result.domains()[0]);
	}

	@Test public void randomizedBinaryFixpointMatchesIndependentSupportOracle() {
		Random random=new Random(20261015);
		for(int trial=0;trial<30;trial++) {
			int[] sizes={3,3,3}; List<TableCheck> tables=new ArrayList<>(); List<Check> checks=new ArrayList<>();
			for(int edge=0;edge<3;edge++) {
				int left=edge,right=(edge+1)%3; boolean[][] allowed=new boolean[3][3];
				for(int a=0;a<3;a++) for(int b=0;b<3;b++) allowed[a][b]=random.nextBoolean();
				tables.add(new TableCheck(left,right,allowed));
				checks.add(completeCheck(new int[] {left,right},tuple -> allowed[tuple[0]][tuple[1]]));
			}
			BitSet[] expected=oracle(sizes,tables);
			PropagationResult actual=optimizer(sizes,checks,new Limits(1,128,256)).propagateOnly(null);
			boolean feasible=true; for(BitSet domain:expected) feasible&=!domain.isEmpty();
			Assert.assertEquals("trial "+trial,feasible,actual.feasible());
			if(feasible) Assert.assertArrayEquals("trial "+trial,expected,actual.domains());
		}
	}

	@Test public void wideDomainSnapshotPeakIsIncludedInAdmissionEstimate() {
		int size=4097,cacheEntries=32;
		Check unknown=new Check(1,new int[] {0,1},r -> Truth.UNKNOWN);
		Limits uncached=new Limits(1,8,0),cached=new Limits(1,8,cacheEntries);
		long withoutCache=MemoOptimizer.estimatePropagationBytes(new int[] {size,size},List.of(unknown),uncached);
		long withCache=MemoOptimizer.estimatePropagationBytes(new int[] {size,size},List.of(unknown),cached);
		long scopedSnapshotBytes=2L*((size+63L)/64L)*Long.BYTES;

		Assert.assertTrue("every retained support entry must cover both wide-domain snapshots",
			withCache-withoutCache>=cacheEntries*scopedSnapshotBytes);
		MemoResourcePolicy oneByteShort=new MemoResourcePolicy(withCache-1);
		Assert.assertFalse("the complete propagation peak must be admitted atomically",
			oneByteShort.admit(withCache));
		Assert.assertEquals(withCache,oneByteShort.stats().peakRejectedBytes());
	}

	private interface TuplePredicate { boolean test(int[] tuple); }
	private static Check completeCheck(int[] owners,TuplePredicate predicate) {
		return new Check(1,owners,tuple -> {
			for(int value:tuple) if(value<0) return Truth.UNKNOWN;
			return predicate.test(tuple) ? Truth.YES:Truth.NO;
		});
	}
	private static MemoOptimizer optimizer(int[] sizes,List<Check> checks,Limits limits) {
		double[][] nativeValues=new double[sizes.length][]; boolean[] coverage=new boolean[sizes.length];
		for(int owner=0;owner<sizes.length;owner++) { nativeValues[owner]=new double[sizes[owner]]; coverage[owner]=true; }
		return new MemoOptimizer(sizes,coverage,nativeValues,checks,List.of(),limits);
	}
	private static BitSet[] oracle(int[] sizes,List<TableCheck> checks) {
		BitSet[] domains=domains(sizes); boolean changed;
		do {
			changed=false;
			for(TableCheck check:checks) {
				for(int value=domains[check.left].nextSetBit(0);value>=0;value=domains[check.left].nextSetBit(value+1)) {
					boolean support=false;
					for(int other=domains[check.right].nextSetBit(0);other>=0;other=domains[check.right].nextSetBit(other+1))
						support|=check.allowed[value][other];
					if(!support) { domains[check.left].clear(value); changed=true; }
				}
				for(int value=domains[check.right].nextSetBit(0);value>=0;value=domains[check.right].nextSetBit(value+1)) {
					boolean support=false;
					for(int other=domains[check.left].nextSetBit(0);other>=0;other=domains[check.left].nextSetBit(other+1))
						support|=check.allowed[other][value];
					if(!support) { domains[check.right].clear(value); changed=true; }
				}
			}
		} while(changed);
		return domains;
	}
	private static BitSet[] domains(int... sizes) {
		BitSet[] result=new BitSet[sizes.length];
		for(int owner=0;owner<sizes.length;owner++) { result[owner]=new BitSet(); result[owner].set(0,sizes[owner]); }
		return result;
	}
	private static BitSet bitset(int... values) { BitSet result=new BitSet(); for(int value:values) result.set(value); return result; }
	private record TableCheck(int left,int right,boolean[][] allowed) { }
}
