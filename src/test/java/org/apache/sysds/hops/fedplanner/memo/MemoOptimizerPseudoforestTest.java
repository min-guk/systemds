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
import java.util.ArrayList;
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

public class MemoOptimizerPseudoforestTest {
	@Test public void leafHeavyHubDoesNotDelayBreakingTheOnlyCycle() {
		List<Check> checks=List.of(edge(0,1),edge(0,2),edge(0,3),edge(0,4),edge(0,5),
			edge(5,6),edge(6,7),edge(7,5),edge(7,8));
		Result result=solve(new double[9][2],checks,List.of(),Long.MAX_VALUE,64,4096);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(new int[9],result.selectedRules());
		Assert.assertTrue(result.statistics().forestTailCompletions()>0);
	}

	@Test public void twoUnicyclicComponentsAreBrokenSequentiallyAndKeepGlobalLexOrder() {
		// Owner 0 is an earlier-id leaf of the first triangle. It must still participate
		// in the final global tie break after both independent cycles are conditioned.
		List<Check> checks=List.of(edge(0,1),edge(1,3),edge(3,5),edge(5,1),
			edge(2,4),edge(4,6),edge(6,2),edge(6,7));
		Result result=solve(new double[8][2],checks,List.of(),Long.MAX_VALUE,64,4096);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(new int[8],result.selectedRules());
		Assert.assertEquals(BigDecimal.ZERO,result.cost());
		Assert.assertTrue(result.statistics().forestTailCompletions()>0);
	}

	@Test public void projectedTernaryCyclePreservesTupleOrderCostAndLeafChoice() {
		Check ternary=new Check(4,new int[] {3,1,2},r -> partial(r) ? Truth.UNKNOWN
			: (r[0]+r[1]+r[2])%2==0 ? Truth.YES : Truth.NO);
		List<Check> checks=List.of(ternary,equal(0,1),equal(2,4));
		List<CostTerm> terms=List.of(new CostTerm(new int[] {3,1,2},r ->
			0.125+4*r[0]+2*r[1]+r[2],0.125));
		double[][] costs={{0,0},{1,0},{0,1},{0,1},{0,0}};
		Result expected=exhaustiveProjectedFixture(costs,terms);
		for(int cache:new int[] {0,1,4096}) {
			Result actual=solve(costs,checks,terms,Long.MAX_VALUE,64,cache);
			assertSame(expected,actual);
			Assert.assertTrue(actual.statistics().forestTailCompletions()>0);
		}
	}

	@Test public void unknownCycleBranchRemainsUnresolved() {
		Check unknown=new Check(7,new int[] {0,1,2},r -> partial(r) ? Truth.UNKNOWN : Truth.UNKNOWN);
		Result result=solve(new double[4][2],
			List.of(equal(0,1),equal(1,2),equal(2,0),equal(2,3),unknown),List.of(),
			Long.MAX_VALUE,1,4096);
		Assert.assertEquals(Status.UNRESOLVED,result.status());
		Assert.assertNull(result.selectedRules());
	}

	@Test public void cycleConditioningAndTailCellsShareTheFiniteNodeBudget() {
		List<Check> checks=new ArrayList<>();
		for(int owner=0;owner<7;owner++) checks.add(edge(owner,(owner+1)%7));
		checks.add(edge(0,7)); checks.add(edge(7,8));
		Result exact=solve(new double[9][2],checks,List.of(),Long.MAX_VALUE,64,4096);
		Assert.assertEquals(Status.OPTIMAL,exact.status());
		Assert.assertTrue(exact.statistics().forestTailCells()>0);
		assertSame(exact,solve(new double[9][2],checks,List.of(),exact.statistics().nodes(),64,4096));
		Assert.assertEquals(Status.RESOURCE_EXHAUSTED,solve(new double[9][2],checks,List.of(),
			exact.statistics().nodes()-1,64,4096).status());
	}

	private static Check equal(int left,int right) {
		return new Check(left,new int[] {left,right},r -> partial(r) ? Truth.UNKNOWN
			: r[0]==r[1] ? Truth.YES : Truth.NO);
	}

	private static Check edge(int left,int right) {
		return new Check(left,new int[] {left,right},r -> Truth.YES);
	}

	private static boolean partial(int[] rules) {
		return Arrays.stream(rules).anyMatch(rule -> rule<0);
	}

	private static Result solve(double[][] costs,List<Check> checks,List<CostTerm> terms,long nodes,
		int supportBudget,int cache) {
		boolean[] coverage=new boolean[costs.length]; Arrays.fill(coverage,true);
		int[] sizes=Arrays.stream(costs).mapToInt(row -> row.length).toArray();
		return new MemoOptimizer(sizes,coverage,costs,checks,terms,
			new Limits(nodes,supportBudget,cache)).optimize();
	}

	private static Result exhaustiveProjectedFixture(double[][] costs,List<CostTerm> terms) {
		BigDecimal best=null; int[] selected=null;
		int assignments=1<<costs.length;
		for(int code=0;code<assignments;code++) {
			int[] rules=new int[costs.length];
			for(int owner=0;owner<rules.length;owner++) rules[owner]=code>>owner&1;
			if(rules[0]!=rules[1] || rules[2]!=rules[4]
				|| (rules[3]+rules[1]+rules[2])%2!=0) continue;
			BigDecimal value=BigDecimal.ZERO;
			for(int owner=0;owner<rules.length;owner++)
				value=value.add(new BigDecimal(costs[owner][rules[owner]]));
			for(CostTerm term:terms) {
				int[] tuple=Arrays.stream(term.owners()).map(owner -> rules[owner]).toArray();
				value=value.add(term.evaluateExact(tuple));
			}
			if(best==null || value.compareTo(best)<0 || value.compareTo(best)==0 && lexLess(rules,selected)) {
				best=value; selected=rules;
			}
		}
		return new Result(best==null ? Status.INFEASIBLE : Status.OPTIMAL,best,selected,null);
	}

	private static boolean lexLess(int[] left,int[] right) {
		if(right==null) return true;
		for(int i=0;i<left.length;i++) if(left[i]!=right[i]) return left[i]<right[i];
		return false;
	}

	private static void assertSame(Result expected,Result actual) {
		Assert.assertEquals(expected.status(),actual.status());
		Assert.assertEquals(expected.cost(),actual.cost());
		Assert.assertArrayEquals(expected.selectedRules(),actual.selectedRules());
	}
}
