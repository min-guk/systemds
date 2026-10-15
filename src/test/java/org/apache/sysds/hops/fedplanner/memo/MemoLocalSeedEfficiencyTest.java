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
import java.util.BitSet;
import java.util.List;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

/** Contracts that let the Local seed implementation index factors without changing its search. */
public class MemoLocalSeedEfficiencyTest {
	@Test public void greedyWitnessAndOrdinalWorkStayDeterministic() {
		int[] sizes={3,2,2};
		CostTerm closesAtLastOwner=new CostTerm(new int[] {0,2},rules -> rules[0]+rules[1],0);
		var result=findFirst(sizes,new double[][] {{4,1,1},{2,0},{3,0}},List.of(),
			List.of(closesAtLastOwner),100);

		Assert.assertArrayEquals("ties retain the lowest ordinal",new int[] {1,1,1},result.selectedRules());
		Assert.assertEquals("one work unit per attempted ordinal",7,result.evaluations());
		Assert.assertEquals(0,BigDecimal.valueOf(3).compareTo(result.cost()));
		Assert.assertEquals("GREEDY_FEASIBLE",result.reason());
	}

	@Test public void workCapStopsAtTheSameOrdinalBoundary() {
		int[] sizes={3,2};
		var result=findFirst(sizes,new double[2][],List.of(),List.of(),4);

		Assert.assertNull(result.selectedRules());
		Assert.assertEquals(4,result.evaluations());
		Assert.assertEquals("WORK_LIMIT",result.reason());
	}

	@Test public void unknownClosedCheckCannotBecomeASeed() {
		int[] sizes={2,2};
		Check unknown=new Check(7,new int[] {0,1},rules -> Truth.UNKNOWN);
		var result=findFirst(sizes,new double[2][2],List.of(unknown),List.of(),100);

		Assert.assertNull(result.selectedRules());
		Assert.assertNull(result.cost());
		Assert.assertEquals("UNRESOLVED_UNKNOWN",result.reason());
	}

	@Test public void greedyDeadEndRepairReturnsFirstLexicalOriginalYesWitness() {
		int[] sizes={2,2,2};
		Check crossing=new Check(1,new int[] {0,2},rules ->
			rules[0]==1 && rules[1]==0 ? Truth.YES : Truth.NO);
		Check adjacent=new Check(2,new int[] {1,2},rules ->
			rules[0]==0 && rules[1]==0 ? Truth.YES : Truth.NO);
		var result=findFirst(sizes,new double[][] {{0,5},{0,1},{0,1}},List.of(crossing,adjacent),
			List.of(),100);

		Assert.assertArrayEquals(new int[] {1,0,0},result.selectedRules());
		Assert.assertTrue(result.repairs()>0);
		Assert.assertEquals("FIRST_REPAIRED",result.reason());
	}

	@Test public void conditionalImprovementRevalidatesCrossingCheckAndFullCost() {
		int[] sizes={2,2,2};
		Check boundary=new Check(3,new int[] {0,2},rules ->
			rules[0]==rules[1] ? Truth.YES : Truth.NO);
		CostTerm crossingCost=new CostTerm(new int[] {1,2},rules -> rules[0]==1 ? -4 : 0,-4);
		var result=improveFirst(sizes,new double[][] {{0,0},{5,0},{0,0}},List.of(boundary),
			List.of(crossingCost),new int[] {0,0,0},List.of(new int[] {1,2}),100);

		Assert.assertArrayEquals("illegal [0,1] at the scoped boundary is skipped",new int[] {0,1,0},
			result.selectedRules());
		Assert.assertEquals(0,BigDecimal.valueOf(-4).compareTo(result.cost()));
		Assert.assertEquals("FIRST_IMPROVEMENT",result.reason());
	}

	@Test public void factorIndexMemoryIsAdmittedBeforeConstruction() {
		int[] sizes={2};
		Check check=new Check(4,new int[] {0},rules -> Truth.YES);
		MemoResourcePolicy resources=new MemoResourcePolicy(400);
		var result=MemoFeasibleSeed.findFirst(sizes,new double[][] {{0,1}},List.of(check),List.of(),
			domains(sizes),10,resources);

		Assert.assertEquals("RESOURCE_REJECTED",result.reason());
		Assert.assertNull(result.selectedRules());
		Assert.assertEquals(1,resources.stats().rejections());
		Assert.assertTrue("the admitted estimate includes closing-owner metadata",
			resources.stats().peakRejectedBytes()>400);
	}

	@Test public void noEnumerationRetainsTheLegacyImproveWorkspaceAdmission() {
		int[] sizes={2};
		for(ImprovementCase test:new ImprovementCase[] {
			new ImprovementCase(0,List.of(new int[] {0}),"WORK_LIMIT"),
			new ImprovementCase(10,List.of(new int[0]),"NO_IMPROVEMENT")}) {
			MemoResourcePolicy resources=new MemoResourcePolicy(450);
			var result=MemoFeasibleSeed.improveFirst(sizes,new double[][] {{0,1}},List.of(),List.of(),
				domains(sizes),new int[] {0},test.scopes(),test.workLimit(),resources);

			Assert.assertArrayEquals(new int[] {0},result.selectedRules());
			Assert.assertEquals(test.reason(),result.reason());
			Assert.assertEquals("the legacy workspace fits this finite ceiling",0,resources.stats().rejections());
		}
	}

	@Test(expected=IllegalArgumentException.class)
	public void noEnumerationStillValidatesMalformedScopeOwners() {
		int[] sizes={2};
		MemoFeasibleSeed.improveFirst(sizes,new double[][] {{0,1}},List.of(),List.of(),domains(sizes),
			new int[] {0},List.of(new int[] {1}),0,new MemoResourcePolicy(Long.MAX_VALUE));
	}

	private record ImprovementCase(long workLimit,List<int[]> scopes,String reason) { }

	private static MemoFeasibleSeed.Result findFirst(int[] sizes,double[][] nativeCosts,List<Check> checks,
		List<CostTerm> costs,long workLimit) {
		for(int owner=0;owner<sizes.length;owner++)
			if(nativeCosts[owner]==null) nativeCosts[owner]=new double[sizes[owner]];
		return MemoFeasibleSeed.findFirst(sizes,nativeCosts,checks,costs,domains(sizes),workLimit,
			new MemoResourcePolicy(Long.MAX_VALUE));
	}

	private static MemoFeasibleSeed.Result improveFirst(int[] sizes,double[][] nativeCosts,List<Check> checks,
		List<CostTerm> costs,int[] incumbent,List<int[]> scopes,long workLimit) {
		return MemoFeasibleSeed.improveFirst(sizes,nativeCosts,checks,costs,domains(sizes),incumbent,scopes,
			workLimit,new MemoResourcePolicy(Long.MAX_VALUE));
	}

	private static BitSet[] domains(int[] sizes) {
		BitSet[] result=new BitSet[sizes.length];
		for(int owner=0;owner<sizes.length;owner++) {
			result[owner]=new BitSet(sizes[owner]);
			result[owner].set(0,sizes[owner]);
		}
		return result;
	}
}
