/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the
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

public class MemoOptimizerForestTailTest {
	@Test
	public void projectedTailPreservesOriginalFactorTupleOrder() {
		Check orderedCheck=new Check(4,new int[] {2,0,1},r -> partial(r) ? Truth.UNKNOWN
			: r[0]+2*r[1]+4*r[2]>=0 ? Truth.YES : Truth.NO);
		CostTerm orderedCost=new CostTerm(new int[] {2,0,1},
			r -> 100*r[0]+10*r[1]+r[2],0);
		List<Check> checks=List.of(yes(0,0,1),yes(1,1,2),yes(2,2,0),yes(3,0,3),orderedCheck);
		double[][] nativeCosts=new double[4][2]; nativeCosts[2][1]=-150;
		Result result=solve(nativeCosts,checks,List.of(orderedCost),Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(new int[] {0,0,1,0},result.selectedRules());
		Assert.assertEquals(0,BigDecimal.valueOf(-50).compareTo(result.cost()));
		Assert.assertTrue(result.statistics().forestTailCompletions()>0);
	}

	@Test
	public void conditionalForestAvoidsTheFrozenCartesianResidualSearch() {
		int core=8,owners=core+1;
		double[][] nativeCosts=new double[owners][2];
		java.util.ArrayList<Check> checks=new java.util.ArrayList<>();
		java.util.ArrayList<CostTerm> terms=new java.util.ArrayList<>();
		for(int owner=0;owner<core;owner++) {
			int left=owner,right=(owner+1)%core;
			checks.add(yes(owner,left,right));
			terms.add(new CostTerm(new int[] {left,right},r -> r[0]==1 && r[1]==1 ? 0 : 1,0));
		}
		checks.add(yes(core,0,core));
		Result result=solve(nativeCosts,checks,terms,Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(new int[] {1,1,1,1,1,1,1,1,0},result.selectedRules());
		Assert.assertTrue("frozen candidate02 visits 75 nodes on this exact fixture",
			result.statistics().nodes()<75);
		Assert.assertTrue(result.statistics().forestTailCompletions()>0);
	}

	@Test
	public void projectedHigherArityAndParallelEdgesUseAnExactForestTail() {
		List<Check> checks=List.of(yes(0,0,1),yes(1,1,2),yes(2,2,0),yes(3,0,3));
		List<CostTerm> terms=List.of(
			new CostTerm(new int[0],r -> 0.0625,0.0625),
			new CostTerm(new int[] {0,1,2},r -> r[0]==1 && r[1]==0 ? -0.25 : 1,-0.25),
			new CostTerm(new int[] {1,2},r -> r[0]==r[1] ? 0.5 : 2,0.5),
			new CostTerm(new int[] {1,2},r -> r[0]==0 ? 0.125 : 0.25,0.125));
		double[][] nativeCosts={{1,0},{0,2},{1,0},{0,1}};
		Result expected=exhaustive(nativeCosts,terms);
		for(int cache:new int[] {0,1,4096}) {
			Result result=solve(nativeCosts,checks,terms,Long.MAX_VALUE,cache);
			assertSameSolve(expected,result);
			Assert.assertTrue(result.statistics().forestTailApplicable()>0);
			Assert.assertTrue(result.statistics().forestTailCompletions()>0);
			Assert.assertTrue(result.statistics().forestTailCells()>0);
		}
	}

	@Test
	public void attachedLeafWithEarlierOwnerKeepsGlobalLexicographicOrder() {
		Check inverseLeaf=new Check(0,new int[] {0,1},r -> partial(r) ? Truth.UNKNOWN
			: r[0]!=r[1] ? Truth.YES : Truth.NO);
		List<Check> checks=List.of(inverseLeaf,yes(1,1,2),yes(2,2,3),yes(3,3,1));
		Result result=solve(new double[4][2],checks,List.of(),Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(new int[] {0,1,0,0},result.selectedRules());
		Assert.assertTrue(result.statistics().forestTailCompletions()>0);
	}

	@Test
	public void unknownTailFallsBackWithoutPublishingAProof() {
		Check unknown=new Check(4,new int[] {0,1,2},r -> partial(r) ? Truth.UNKNOWN : Truth.UNKNOWN);
		List<Check> checks=List.of(yes(0,0,1),yes(1,1,2),yes(2,2,0),yes(3,0,3),unknown);
		Result result=solve(new double[4][2],checks,List.of(),Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.UNRESOLVED,result.status());
		Assert.assertNull(result.selectedRules());
		Assert.assertTrue(result.statistics().forestTailApplicable()>0);
		Assert.assertEquals(0,result.statistics().forestTailCompletions());
	}

	@Test
	public void knownInfeasibleForestTailClosesTheBranchExactly() {
		Check noCompleteTuple=new Check(4,new int[] {0,1,2},r -> partial(r) ? Truth.UNKNOWN : Truth.NO);
		Result result=solve(new double[4][2],
			List.of(yes(0,0,1),yes(1,1,2),yes(2,2,0),yes(3,0,3),noCompleteTuple),
			List.of(),Long.MAX_VALUE,1,4096);
		Assert.assertEquals(Status.INFEASIBLE,result.status());
		Assert.assertNull(result.selectedRules());
		Assert.assertTrue(result.statistics().forestTailCompletions()>0);
	}

	@Test
	public void forestCellsShareTheGlobalFiniteNodeBudget() {
		List<Check> checks=List.of(yes(0,0,1),yes(1,1,2),yes(2,2,0),yes(3,0,3));
		Result exact=solve(new double[4][2],checks,List.of(),Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.OPTIMAL,exact.status());
		Assert.assertTrue(exact.statistics().forestTailCells()>0);
		Result exactBudget=solve(new double[4][2],checks,List.of(),exact.statistics().nodes(),4096);
		assertSameSolve(exact,exactBudget);
		Result limited=solve(new double[4][2],checks,List.of(),exact.statistics().nodes()-1,4096);
		Assert.assertEquals(Status.RESOURCE_EXHAUSTED,limited.status());
	}

	private static Check yes(int site,int... owners) {
		return new Check(site,owners,r -> Truth.YES);
	}

	private static boolean partial(int[] rules) {
		return Arrays.stream(rules).anyMatch(rule -> rule<0);
	}

	private static Result solve(double[][] nativeCosts,List<Check> checks,List<CostTerm> terms,long nodes,int cache) {
		return solve(nativeCosts,checks,terms,nodes,64,cache);
	}

	private static Result solve(double[][] nativeCosts,List<Check> checks,List<CostTerm> terms,long nodes,
		int supportBudget,int cache) {
		boolean[] coverage=new boolean[nativeCosts.length]; Arrays.fill(coverage,true);
		int[] sizes=Arrays.stream(nativeCosts).mapToInt(row -> row.length).toArray();
		return new MemoOptimizer(sizes,coverage,nativeCosts,checks,terms,
			new Limits(nodes,supportBudget,cache)).optimize();
	}

	private static Result exhaustive(double[][] nativeCosts,List<CostTerm> terms) {
		BigDecimal best=null; int[] selected=null;
		for(int code=0;code<16;code++) {
			int[] rules={code>>3,code>>2&1,code>>1&1,code&1};
			BigDecimal cost=BigDecimal.ZERO;
			for(int owner=0;owner<rules.length;owner++)
				cost=cost.add(new BigDecimal(nativeCosts[owner][rules[owner]]));
			for(CostTerm term:terms) {
				int[] projected=Arrays.stream(term.owners()).map(owner -> rules[owner]).toArray();
				cost=cost.add(term.evaluateExact(projected));
			}
			if(best==null || cost.compareTo(best)<0) { best=cost; selected=rules; }
		}
		return new Result(Status.OPTIMAL,best,selected,null);
	}

	private static void assertSameSolve(Result expected,Result actual) {
		Assert.assertEquals(expected.status(),actual.status());
		Assert.assertEquals(expected.cost(),actual.cost());
		Assert.assertArrayEquals(expected.selectedRules(),actual.selectedRules());
	}
}
