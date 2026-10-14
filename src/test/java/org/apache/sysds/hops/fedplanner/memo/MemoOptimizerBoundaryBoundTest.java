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

import java.math.BigDecimal;
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

public class MemoOptimizerBoundaryBoundTest {
	@Test
	public void conflictingNativeAndLeafMinimaKeepTheExactOptimum() {
		// A finite limit deliberately exercises the legacy correlated-bound counters.
		Result reference=solve(conflictingTerms(),alwaysYesCycle(),0,Long.MAX_VALUE-1,1);
		Assert.assertEquals(Status.OPTIMAL,reference.status());
		Assert.assertEquals(0,new BigDecimal("0.5").compareTo(reference.cost()));
		Assert.assertArrayEquals(new int[] {0,0,0,0,0},reference.selectedRules());
		for(int capacity:new int[] {1,4096}) assertSameSolve(reference,
			solve(conflictingTerms(),alwaysYesCycle(),capacity,Long.MAX_VALUE-1,1));
		Assert.assertTrue("correlated messages must prune the impossible independent minima",
			reference.statistics().nodes()<23);
		System.out.println("BOUNDARY_CONFLICT_NODES="+reference.statistics().nodes());
		assertSameSolve(reference,solve(conflictingTerms(),alwaysYesCycle(),4096,Long.MAX_VALUE,1));
	}

	@Test
	public void equalCombinedMessagesKeepTheFullLexicographicTie() {
		List<CostTerm> zeroLeaves=List.of(
			new CostTerm(new int[] {0,3},rules -> 0,0),
			new CostTerm(new int[] {0,4},rules -> 0,0));
		for(int capacity:new int[] {0,1,4096}) {
			Result result=solve(zeroLeaves,alwaysYesCycle(),capacity,Long.MAX_VALUE,0);
			Assert.assertEquals(Status.OPTIMAL,result.status());
			Assert.assertArrayEquals(new int[] {0,0,0,0,0},result.selectedRules());
		}
	}

	@Test
	public void unknownBoundaryDoesNotPublishAPartialTreeProof() {
		List<Check> checks=List.of(alwaysYes(0,0,1),alwaysYes(1,1,2),alwaysYes(2,2,0),
			new Check(3,new int[] {0,3},rules -> Truth.UNKNOWN));
		Result result=solve(conflictingTerms(),checks,4096,Long.MAX_VALUE,1);
		Assert.assertEquals(Status.UNRESOLVED,result.status());
		Assert.assertNull(result.selectedRules());
	}

	@Test
	public void finiteNodeBudgetStopsBeforePublishingAProof() {
		for(int capacity:new int[] {0,1,4096}) {
			Result result=solve(conflictingTerms(),alwaysYesCycle(),capacity,3,1);
			Assert.assertEquals(Status.RESOURCE_EXHAUSTED,result.status());
			Assert.assertNull(result.selectedRules());
		}
	}

	private static List<CostTerm> conflictingTerms() {
		// Native owner 0 prefers rule 1. Both exact leaf messages prefer rule 0.
		// Old independent minima form the impossible -0.5 lower bound; the exact
		// correlated owner contribution is min(1-.25-.25, 0+4+4) = 0.5.
		return List.of(
			new CostTerm(new int[] {0,3},rules -> rules[0]==0 ? -0.25 : 4,-0.25),
			new CostTerm(new int[] {0,4},rules -> rules[0]==0 ? -0.25 : 4,-0.25));
	}

	private static List<Check> alwaysYesCycle() {
		return List.of(alwaysYes(0,0,1),alwaysYes(1,1,2),alwaysYes(2,2,0));
	}

	private static Check alwaysYes(int site,int left,int right) {
		return new Check(site,new int[] {left,right},rules -> Truth.YES);
	}

	private static Result solve(List<CostTerm> terms,List<Check> checks,int cache,long nodes,double firstNativeCost) {
		int[] sizes={2,4,4,2,2};
		double[][] nativeCosts=new double[sizes.length][];
		for(int owner=0;owner<sizes.length;owner++) nativeCosts[owner]=new double[sizes[owner]];
		nativeCosts[0][0]=firstNativeCost;
		boolean[] coverage=new boolean[sizes.length]; Arrays.fill(coverage,true);
		return new MemoOptimizer(sizes,coverage,nativeCosts,checks,terms,new Limits(nodes,64,cache)).optimize();
	}

	private static void assertSameSolve(Result expected,Result actual) {
		Assert.assertEquals(expected.status(),actual.status());
		Assert.assertEquals(expected.cost(),actual.cost());
		Assert.assertArrayEquals(expected.selectedRules(),actual.selectedRules());
	}
}
