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
import java.util.BitSet;
import java.util.List;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

public class MemoLocalSeedTest {
	@Test public void firstFeasibleReturnsWithoutAnExactProof() {
		int[] sizes={2,2};
		CostTerm coupled=new CostTerm(new int[] {0,1},rules -> {
			if(rules[0]==0 && rules[1]==0) return 10;
			if(rules[0]==0 && rules[1]==1) return 20;
			return 0;
		},0);
		var result=findFirst(sizes,new double[2][2],List.of(),List.of(coupled),domains(sizes),100);

		Assert.assertArrayEquals("the first greedy witness need not be the optimum",new int[] {0,0},
			result.selectedRules());
		Assert.assertEquals(0,BigDecimal.TEN.compareTo(result.cost()));
		Assert.assertNull("Local seed discovery never publishes an exact proof",result.fullProof());
		Assert.assertEquals("GREEDY_FEASIBLE",result.reason());
	}

	@Test public void firstFeasibleRepairStopsAtTheFirstOriginalYesWitness() {
		int[] sizes={2,2};
		Check onlyGlobalChoice=new Check(0,new int[] {0,1},rules -> incomplete(rules) ? Truth.UNKNOWN
			: rules[0]==1 && rules[1]==0 ? Truth.YES : Truth.NO);
		var result=findFirst(sizes,new double[][] {{0,5},{0,1}},List.of(onlyGlobalChoice),List.of(),
			domains(sizes),100);

		Assert.assertArrayEquals(new int[] {1,0},result.selectedRules());
		Assert.assertEquals(0,BigDecimal.valueOf(5).compareTo(result.cost()));
		Assert.assertTrue(result.repairs()>0);
		Assert.assertNull(result.fullProof());
		Assert.assertEquals("FIRST_REPAIRED",result.reason());
	}

	@Test public void firstFeasibleNeverTurnsUnknownIntoAnUpperBoundOrProof() {
		int[] sizes={2};
		Check unknown=new Check(0,new int[] {0},rules -> Truth.UNKNOWN);
		var result=findFirst(sizes,new double[][] {{0,1}},List.of(unknown),List.of(),domains(sizes),100);

		Assert.assertNull(result.selectedRules());
		Assert.assertNull(result.cost());
		Assert.assertNull(result.fullProof());
		Assert.assertEquals("UNRESOLVED_UNKNOWN",result.reason());
	}

	@Test public void boundedFirstFeasibleDoesNotMutateOrPruneProofDomains() {
		int[] sizes={2,2};
		BitSet[] proof=domains(sizes); BitSet[] before=copy(proof);
		Check onlyGlobalChoice=new Check(0,new int[] {0,1},rules -> incomplete(rules) ? Truth.UNKNOWN
			: rules[0]==1 && rules[1]==0 ? Truth.YES : Truth.NO);
		var result=findFirst(sizes,new double[2][2],List.of(onlyGlobalChoice),List.of(),proof,1);

		Assert.assertNull(result.selectedRules());
		Assert.assertNull(result.fullProof());
		Assert.assertEquals("WORK_LIMIT",result.reason());
		Assert.assertArrayEquals(before,proof);
	}

	@Test public void improvementReturnsTheFirstStrictlyCheaperWitness() {
		int[] sizes={2,2};
		var result=improveFirst(sizes,new double[][] {{5,1},{5,0}},List.of(),List.of(),domains(sizes),
			new int[] {0,0},List.of(new int[] {0,1}),100);

		Assert.assertArrayEquals("return before reaching the still cheaper [1,1] witness",new int[] {0,1},
			result.selectedRules());
		Assert.assertEquals(0,BigDecimal.valueOf(5).compareTo(result.cost()));
		Assert.assertNull(result.fullProof());
		Assert.assertEquals("FIRST_IMPROVEMENT",result.reason());
	}

	@Test public void improvementKeepsOutsideBoundaryFixedAndValidatesCrossingChecks() {
		int[] sizes={2,2};
		Check equal=new Check(0,new int[] {0,1},rules -> incomplete(rules) ? Truth.UNKNOWN
			: rules[0]==rules[1] ? Truth.YES : Truth.NO);
		var result=improveFirst(sizes,new double[][] {{0,0},{5,0}},List.of(equal),List.of(),domains(sizes),
			new int[] {0,0},List.of(new int[] {1}),100);

		Assert.assertArrayEquals("the cheaper rule is illegal against the fixed outside boundary",
			new int[] {0,0},result.selectedRules());
		Assert.assertEquals(0,BigDecimal.valueOf(5).compareTo(result.cost()));
		Assert.assertNull(result.fullProof());
		Assert.assertEquals("NO_IMPROVEMENT",result.reason());
	}

	private static MemoFeasibleSeed.Result findFirst(int[] sizes,double[][] nativeCosts,List<Check> checks,
		List<CostTerm> costs,BitSet[] proofDomains,long workLimit) {
		return MemoFeasibleSeed.findFirst(sizes,nativeCosts,checks,costs,proofDomains,workLimit,
			new MemoResourcePolicy(Long.MAX_VALUE));
	}

	private static MemoFeasibleSeed.Result improveFirst(int[] sizes,double[][] nativeCosts,List<Check> checks,
		List<CostTerm> costs,BitSet[] proofDomains,int[] initialSelected,List<int[]> scopes,long workLimit) {
		return MemoFeasibleSeed.improveFirst(sizes,nativeCosts,checks,costs,proofDomains,initialSelected,scopes,
			workLimit,new MemoResourcePolicy(Long.MAX_VALUE));
	}

	private static BitSet[] domains(int[] sizes) {
		BitSet[] result=new BitSet[sizes.length];
		for(int owner=0;owner<sizes.length;owner++) {
			result[owner]=new BitSet(sizes[owner]); result[owner].set(0,sizes[owner]);
		}
		return result;
	}

	private static BitSet[] copy(BitSet[] source) {
		return Arrays.stream(source).map(bits -> (BitSet)bits.clone()).toArray(BitSet[]::new);
	}

	private static boolean incomplete(int[] values) {
		for(int value:values) if(value<0) return true;
		return false;
	}
}
