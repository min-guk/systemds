/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.Arrays;
import java.util.List;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Limits;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Result;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Status;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

public class MemoOptimizerSupportWitnessTest {
	@Test
	public void positiveWitnessesPreserveTheExactCyclicSolveAcrossCacheLimits() {
		List<Check> checks=equalCycle();
		Result uncached=solve(new int[] {3,3,3},checks,List.of(),64,0,Long.MAX_VALUE);
		Result singleEntry=solve(new int[] {3,3,3},checks,List.of(),64,1,Long.MAX_VALUE);
		Result cached=solve(new int[] {3,3,3},checks,List.of(),64,4096,Long.MAX_VALUE);
		assertSameSolve(uncached,singleEntry);
		assertSameSolve(uncached,cached);
		Assert.assertArrayEquals(new int[] {0,0,0},cached.selectedRules());
		Assert.assertEquals(0,uncached.statistics().supportWitnessHits());
		Assert.assertTrue("restored and narrowed domains must reuse positive witnesses",
			cached.statistics().supportWitnessHits()>0);
	}

	@Test
	public void removedWitnessOrdinalIsRejectedAndUndoMakesItReusable() throws Exception {
		MemoOptimizer optimizer=optimizer(new int[] {2,2},List.of(equal(0,0,1)),List.of(),16,4096,
			Long.MAX_VALUE);
		Assert.assertEquals(Truth.YES,support(optimizer,0,0,0)); // stores [0,0]
		remove(optimizer,1,0);
		Assert.assertEquals("the stored tuple no longer exists in the narrowed domain",
			Truth.NO,support(optimizer,0,0,0));
		undo(optimizer,0);
		Assert.assertEquals(Truth.YES,support(optimizer,0,0,0));
		Assert.assertEquals("undo restores the exact stored witness",1,supportWitnessHits(optimizer));
	}

	@Test
	public void prefixYesStoresAConcreteCompletionOfItsUnassignedSuffix() throws Exception {
		Check prefixYes=new Check(0,new int[] {0,1},rules -> rules[0]==1 ? Truth.YES
			: partial(rules) ? Truth.UNKNOWN : Truth.NO);
		MemoOptimizer optimizer=optimizer(new int[] {2,2},List.of(prefixYes),List.of(),16,4096,
			Long.MAX_VALUE);
		Assert.assertEquals(Truth.YES,support(optimizer,0,0,1)); // prefix [1,-1] is sufficient
		remove(optimizer,1,0); // invalidates the concrete [1,0] completion
		Assert.assertEquals("prefix proof must produce another concrete suffix completion",
			Truth.YES,support(optimizer,0,0,1));
		undo(optimizer,0);
		Assert.assertEquals(Truth.YES,support(optimizer,0,0,1));
		Assert.assertEquals(1,supportWitnessHits(optimizer));
	}

	@Test
	public void wideOrdinalWitnessesRemainExactAcrossBacktracking() {
		Check mapHigh=new Check(0,new int[] {0,1},rules -> partial(rules) ? Truth.UNKNOWN
			: rules[1]==(rules[0]==64 ? 1 : 0) ? Truth.YES : Truth.NO);
		Check cycleA=new Check(1,new int[] {1,2},rules -> partial(rules) ? Truth.UNKNOWN : Truth.YES);
		Check cycleB=new Check(2,new int[] {2,0},rules -> partial(rules) ? Truth.UNKNOWN : Truth.YES);
		CostTerm preferHigh=new CostTerm(new int[] {0},rules -> rules[0]==64 ? 0 : 100+rules[0],0);
		List<Check> checks=List.of(mapHigh,cycleA,cycleB);
		Result uncached=solve(new int[] {65,2,2},checks,List.of(preferHigh),128,0,Long.MAX_VALUE);
		Result cached=solve(new int[] {65,2,2},checks,List.of(preferHigh),128,4096,Long.MAX_VALUE);
		assertSameSolve(uncached,cached);
		Assert.assertArrayEquals(new int[] {64,1,0},cached.selectedRules());
		Assert.assertTrue(cached.statistics().supportWitnessHits()>0);
	}

	@Test
	public void collidingWitnessHashesKeepPositionAndValueIdentity() {
		// (id=0,position=0,value=31) and (id=0,position=1,value=0) have the same hash.
		Check onlyPair=new Check(0,new int[] {0,1},rules -> partial(rules) ? Truth.UNKNOWN
			: rules[0]==31 && rules[1]==1 ? Truth.YES : Truth.NO);
		Check cycleA=new Check(1,new int[] {1,2},rules -> partial(rules) ? Truth.UNKNOWN : Truth.YES);
		Check cycleB=new Check(2,new int[] {2,0},rules -> partial(rules) ? Truth.UNKNOWN : Truth.YES);
		Result uncached=solve(new int[] {32,2,2},List.of(onlyPair,cycleA,cycleB),List.of(),64,0,
			Long.MAX_VALUE);
		Result cached=solve(new int[] {32,2,2},List.of(onlyPair,cycleA,cycleB),List.of(),64,4096,
			Long.MAX_VALUE);
		assertSameSolve(uncached,cached);
		Assert.assertArrayEquals(new int[] {31,1,0},cached.selectedRules());
	}

	@Test
	public void normalizationDoesNotReuseAFormerCheckUnderItsGroupedIdentity() {
		Check permissive=new Check(0,new int[] {0,1},rules -> partial(rules) ? Truth.UNKNOWN : Truth.YES);
		Check different=new Check(1,new int[] {0,1},rules -> partial(rules) ? Truth.UNKNOWN
			: rules[0]!=rules[1] ? Truth.YES : Truth.NO);
		// A unary term triggers normalization and groups the two equal-scope checks.
		CostTerm normalize=new CostTerm(new int[] {0},rules -> 0,0);
		Result uncached=solve(new int[] {2,2},List.of(permissive,different),List.of(normalize),16,0,
			Long.MAX_VALUE);
		Result cached=solve(new int[] {2,2},List.of(permissive,different),List.of(normalize),16,4096,
			Long.MAX_VALUE);
		assertSameSolve(uncached,cached);
		Assert.assertArrayEquals(new int[] {0,1},cached.selectedRules());
	}

	@Test
	public void unknownAndFiniteBudgetsAreNeverCertifiedByWitnesses() {
		Check unknown=new Check(0,new int[] {0,1},rules -> Truth.UNKNOWN);
		Result unresolved=solve(new int[] {2,2},List.of(unknown),List.of(),2,4096,Long.MAX_VALUE);
		Assert.assertEquals(Status.UNRESOLVED,unresolved.status());
		Assert.assertEquals(0,unresolved.statistics().supportWitnessHits());

		Result uncached=solve(new int[] {3,3,3},equalCycle(),List.of(),8,0,1);
		Result cached=solve(new int[] {3,3,3},equalCycle(),List.of(),8,4096,1);
		assertSameSolve(uncached,cached);
		Assert.assertEquals(Status.RESOURCE_EXHAUSTED,cached.status());
	}

	private static List<Check> equalCycle() {
		return List.of(equal(0,0,1),equal(1,1,2),equal(2,2,0));
	}
	private static Check equal(int id,int first,int second) {
		return new Check(id,new int[] {first,second},rules -> partial(rules) ? Truth.UNKNOWN
			: rules[0]==rules[1] ? Truth.YES : Truth.NO);
	}
	private static boolean partial(int[] rules) {
		return Arrays.stream(rules).anyMatch(rule -> rule<0);
	}
	private static Result solve(int[] sizes,List<Check> checks,List<CostTerm> terms,
		int supportBudget,int cache,long nodes) {
		return optimizer(sizes,checks,terms,supportBudget,cache,nodes).optimize();
	}
	private static MemoOptimizer optimizer(int[] sizes,List<Check> checks,List<CostTerm> terms,
		int supportBudget,int cache,long nodes) {
		double[][] costs=new double[sizes.length][];
		for(int owner=0;owner<sizes.length;owner++) costs[owner]=new double[sizes[owner]];
		boolean[] coverage=new boolean[sizes.length]; Arrays.fill(coverage,true);
		return new MemoOptimizer(sizes,coverage,costs,checks,terms,new Limits(nodes,supportBudget,cache));
	}
	private static Truth support(MemoOptimizer optimizer,int check,int owner,int value) throws Exception {
		var method=MemoOptimizer.class.getDeclaredMethod("support",int.class,int.class,int.class);
		method.setAccessible(true);
		return (Truth)method.invoke(optimizer,check,owner,value);
	}
	private static void remove(MemoOptimizer optimizer,int owner,int value) throws Exception {
		var method=MemoOptimizer.class.getDeclaredMethod("remove",int.class,int.class);
		method.setAccessible(true);
		method.invoke(optimizer,owner,value);
	}
	private static void undo(MemoOptimizer optimizer,int mark) throws Exception {
		var method=MemoOptimizer.class.getDeclaredMethod("undo",int.class);
		method.setAccessible(true);
		method.invoke(optimizer,mark);
	}
	private static long supportWitnessHits(MemoOptimizer optimizer) throws Exception {
		var field=MemoOptimizer.class.getDeclaredField("supportWitnessHits");
		field.setAccessible(true);
		return field.getLong(optimizer);
	}
	private static void assertSameSolve(Result expected,Result actual) {
		Assert.assertEquals(expected.status(),actual.status());
		Assert.assertEquals(expected.cost(),actual.cost());
		Assert.assertArrayEquals(expected.selectedRules(),actual.selectedRules());
	}
}
