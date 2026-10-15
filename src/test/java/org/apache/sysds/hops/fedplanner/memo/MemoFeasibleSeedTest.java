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
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Status;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

public class MemoFeasibleSeedTest {
	@Test public void greedyDeadEndRepairsWithoutPruningTheOriginalFeasiblePlan() {
		int[] sizes={2,2};
		Check onlyGlobalChoice=new Check(0,new int[] {0,1},rules -> incomplete(rules) ? Truth.UNKNOWN
			: rules[0]==1 && rules[1]==0 ? Truth.YES : Truth.NO);
		var result=find(sizes,new double[][] {{0,5},{0,1}},List.of(onlyGlobalChoice),List.of(),domains(sizes),100);
		Assert.assertArrayEquals(new int[] {1,0},result.selectedRules());
		Assert.assertEquals(0,BigDecimal.valueOf(5).compareTo(result.cost()));
		Assert.assertTrue(result.repairs()>0);
		Assert.assertNotNull(result.fullProof());
		Assert.assertEquals(Status.OPTIMAL,result.fullProof().status());
		Assert.assertArrayEquals(new int[] {1,0},result.fullProof().selectedRules());
		Assert.assertTrue("small repair must reuse a shared exact structural kernel",
			result.fullProof().statistics().nodes()>0);
	}

	@Test public void zeroAndSmallSeedCapsDoNotChangeProofDomainsOrClaimFailure() {
		int[] sizes={2,2}; BitSet[] proof=domains(sizes); BitSet[] before=copy(proof);
		Check onlyGlobalChoice=new Check(0,new int[] {0,1},rules -> incomplete(rules) ? Truth.UNKNOWN
			: rules[0]==1 && rules[1]==0 ? Truth.YES : Truth.NO);
		for(long cap:new long[] {0,1}) {
			var result=find(sizes,new double[2][2],List.of(onlyGlobalChoice),List.of(),proof,cap);
			Assert.assertNull(result.selectedRules());
			Assert.assertNull(result.fullProof());
			Assert.assertEquals("WORK_LIMIT",result.reason());
			Assert.assertArrayEquals(before,proof);
		}
	}

	@Test public void repairEvaluatesCrossingLegalityAndSharedMovementInTheFullObjective() {
		int[] sizes={2,2,2};
		Check crossing=new Check(4,new int[] {0,2},rules -> incomplete(rules) ? Truth.UNKNOWN
			: rules[0]==1 && rules[1]==1 ? Truth.YES : Truth.NO);
		Check middle=new Check(5,new int[] {1,2},rules -> incomplete(rules) ? Truth.UNKNOWN
			: rules[0]==0 && rules[1]==1 ? Truth.YES : Truth.NO);
		CostTerm sharedMovement=new CostTerm(new int[] {0,1,2},rules ->
			rules[0]==1 && (rules[1]==0 || rules[2]==0) ? 7 : 0,0);
		var result=find(sizes,new double[][] {{0,2},{0,3},{0,1}},List.of(crossing,middle),
			List.of(sharedMovement),domains(sizes),500);
		Assert.assertArrayEquals(new int[] {1,0,1},result.selectedRules());
		Assert.assertEquals("shared factor is owned and added once",0,
			BigDecimal.valueOf(10).compareTo(result.cost()));
		Assert.assertTrue(result.repairs()>0);
	}

	@Test public void unknownCompleteChecksNeverProduceAnUpperBound() {
		int[] sizes={2};
		Check unknown=new Check(0,new int[] {0},rules -> Truth.UNKNOWN);
		var result=find(sizes,new double[][] {{0,1}},List.of(unknown),List.of(),domains(sizes),100);
		Assert.assertNull(result.selectedRules());
		Assert.assertNull(result.cost());
		Assert.assertNull("UNKNOWN traversal is not reusable as an exact proof",result.fullProof());
		Assert.assertEquals("UNRESOLVED_UNKNOWN",result.reason());
	}

	@Test public void fullProofRequiresCompleteEnumerationOfTheIncomingDomains() {
		int[] sizes={2,2}; BitSet[] proof=domains(sizes);
		Check onlyGlobalChoice=new Check(0,new int[] {0,1},rules -> incomplete(rules) ? Truth.UNKNOWN
			: rules[0]==1 && rules[1]==1 ? Truth.YES : Truth.NO);
		CostTerm preference=new CostTerm(new int[] {0,1},rules -> rules[0]==1 && rules[1]==1 ? -5 : 0,-5);
		var exact=find(sizes,new double[][] {{0,1},{0,1}},List.of(onlyGlobalChoice),List.of(preference),proof,100);
		Assert.assertArrayEquals(new int[] {1,1},exact.selectedRules());
		Assert.assertNotNull(exact.fullProof());
		Assert.assertEquals(Status.OPTIMAL,exact.fullProof().status());
		Assert.assertEquals(0,BigDecimal.valueOf(-3).compareTo(exact.fullProof().cost()));

		var bounded=find(sizes,new double[][] {{0,1},{0,1}},List.of(onlyGlobalChoice),List.of(preference),proof,5);
		Assert.assertNull("an incomplete traversal cannot be reused as exact proof",bounded.fullProof());
	}

	@Test public void inputsAndReturnedAssignmentsAreDefensiveCopies() {
		int[] sizes={2}; double[][] nativeCosts={{0,1}}; BitSet[] proof=domains(sizes);
		var result=find(sizes,nativeCosts,List.of(),List.of(),proof,20);
		nativeCosts[0][0]=100; proof[0].clear();
		int[] selected=result.selectedRules(); selected[0]=1;
		Assert.assertArrayEquals(new int[] {0},result.selectedRules());
		Assert.assertEquals(0,BigDecimal.ZERO.compareTo(result.cost()));
	}

	private static MemoFeasibleSeed.Result find(int[] sizes,double[][] nativeCosts,List<Check> checks,
		List<CostTerm> costs,BitSet[] domains,long workLimit) {
		return MemoFeasibleSeed.find(sizes,nativeCosts,checks,costs,domains,workLimit,
			new MemoResourcePolicy(Long.MAX_VALUE));
	}

	private static BitSet[] domains(int[] sizes) {
		BitSet[] result=new BitSet[sizes.length];
		for(int owner=0;owner<sizes.length;owner++) {
			result[owner]=new BitSet(sizes[owner]); result[owner].set(0,sizes[owner]);
		}
		return result;
	}

	private static BitSet[] copy(BitSet[] source) {
		return java.util.Arrays.stream(source).map(bits -> (BitSet)bits.clone()).toArray(BitSet[]::new);
	}

	private static boolean incomplete(int[] values) {
		for(int value:values) if(value<0) return true;
		return false;
	}
}
