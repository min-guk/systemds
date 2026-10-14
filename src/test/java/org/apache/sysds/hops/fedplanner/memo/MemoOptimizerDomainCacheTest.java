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
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

public class MemoOptimizerDomainCacheTest {
	@Test
	public void restoredScopedDomainsPreserveTheExactSolve() {
		List<Check> checks=List.of(
			new Check(0,new int[] {0,1},values -> partial(values) ? Truth.UNKNOWN
				: values[1]==0 ? Truth.YES : Truth.NO),
			new Check(1,new int[] {1,2},values -> partial(values) ? Truth.UNKNOWN : Truth.YES),
			new Check(2,new int[] {2,0},values -> partial(values) ? Truth.UNKNOWN : Truth.YES));
		// Equal cyclic cost incidence keeps owner 0 first in the stable branch order;
		// both owner-0 alternatives recreate the same scoped state for term {1,2}.
		List<CostTerm> terms=List.of(
			new CostTerm(new int[] {0,1},values -> 0,0),
			new CostTerm(new int[] {1,2},values -> 0,0),
			new CostTerm(new int[] {2,0},values -> 0,0));
		Result uncached=solve(new int[] {2,2,2},checks,terms,3,0);
		Result singleEntry=solve(new int[] {2,2,2},checks,terms,3,1);
		Result cached=solve(new int[] {2,2,2},checks,terms,3,4096);
		assertSameSolve(uncached,singleEntry);
		assertSameSolve(uncached,cached);
		Assert.assertArrayEquals(new int[] {0,0,0},cached.selectedRules());
		Assert.assertTrue("restored check-local domains must reuse exact support",
			cached.statistics().supportCacheHits()+cached.statistics().supportWitnessHits()>0);
		Assert.assertTrue("restored cost domains must reuse the exact lower bound",
			cached.statistics().conditionalBoundCacheHits()>0);
		Assert.assertEquals("exact cache hits must not add wide relaxations",
			uncached.statistics().wideBoundRelaxations(),cached.statistics().wideBoundRelaxations());
	}

	@Test
	public void finiteSupportBudgetUnknownIsPreserved() {
		Check exactEquality=new Check(0,new int[] {0,1},values -> partial(values) ? Truth.UNKNOWN
			: values[0]==values[1] ? Truth.YES : Truth.NO);
		Result uncached=solve(new int[] {3,3},List.of(exactEquality),List.of(),1,0);
		Result cached=solve(new int[] {3,3},List.of(exactEquality),List.of(),1,4096);
		assertSameSolve(uncached,cached);
	}

	@Test
	public void collidingBitSetHashesKeepDifferentDomainContents() {
		// BitSet words 1 and 1L<<32 have the same folded Java hash.
		Assert.assertEquals(java.util.BitSet.valueOf(new long[] {1}).hashCode(),
			java.util.BitSet.valueOf(new long[] {1L<<32}).hashCode());
		Check cycleA=new Check(0,new int[] {0,1},values -> partial(values) ? Truth.UNKNOWN : Truth.YES);
		Check cycleB=new Check(1,new int[] {1,2},values -> partial(values) ? Truth.UNKNOWN : Truth.YES);
		Check cycleC=new Check(2,new int[] {2,0},values -> partial(values) ? Truth.UNKNOWN : Truth.YES);
		CostTerm distinguish=new CostTerm(new int[] {0,1},values -> values[0]==32 ? 0 : 100+values[0],0);
		Result uncached=solve(new int[] {65,2,2},List.of(cycleA,cycleB,cycleC),List.of(distinguish),64,0);
		Result cached=solve(new int[] {65,2,2},List.of(cycleA,cycleB,cycleC),List.of(distinguish),64,4096);
		assertSameSolve(uncached,cached);
		Assert.assertEquals(32,cached.selectedRules()[0]);
	}

	@Test
	public void compactAndWideDomainsPreserveBoundaryOrdinals() {
		for(int width:new int[] {63,64,65}) {
			Check onlyHighest=new Check(0,new int[] {0},values -> partial(values) ? Truth.UNKNOWN
				: values[0]==width-1 ? Truth.YES : Truth.NO);
			Result uncached=solve(new int[] {width},List.of(onlyHighest),List.of(),128,0);
			Result singleEntry=solve(new int[] {width},List.of(onlyHighest),List.of(),128,1);
			Result cached=solve(new int[] {width},List.of(onlyHighest),List.of(),128,4096);
			assertSameSolve(uncached,singleEntry);
			assertSameSolve(uncached,cached);
			Assert.assertEquals(width-1,cached.selectedRules()[0]);
		}
	}

	@Test
	public void compactAndWideDomainsRestoreHighestOrdinalAfterBacktracking() {
		for(int width:new int[] {63,64,65}) {
			Check cycleA=new Check(0,new int[] {0,1},values -> partial(values) ? Truth.UNKNOWN : Truth.YES);
			Check cycleB=new Check(1,new int[] {1,2},values -> partial(values) ? Truth.UNKNOWN : Truth.YES);
			Check cycleC=new Check(2,new int[] {2,0},values -> partial(values) ? Truth.UNKNOWN : Truth.YES);
			CostTerm preferHighest=new CostTerm(new int[] {0,1},
				values -> values[0]==width-1 ? 0 : 100+values[0],0);
			List<Check> checks=List.of(cycleA,cycleB,cycleC);
			Result uncached=solve(new int[] {width,2,2},checks,List.of(preferHighest),128,0);
			Result singleEntry=solve(new int[] {width,2,2},checks,List.of(preferHighest),128,1);
			Result cached=solve(new int[] {width,2,2},checks,List.of(preferHighest),128,4096);
			assertSameSolve(uncached,singleEntry);
			assertSameSolve(uncached,cached);
			Assert.assertEquals(width-1,cached.selectedRules()[0]);
		}
	}

	private static boolean partial(int[] values) {
		return Arrays.stream(values).anyMatch(value -> value<0);
	}

	private static Result solve(int[] sizes,List<Check> checks,List<CostTerm> terms,int supportBudget,int cache) {
		double[][] costs=new double[sizes.length][];
		for(int owner=0;owner<sizes.length;owner++) costs[owner]=new double[sizes[owner]];
		boolean[] coverage=new boolean[sizes.length]; Arrays.fill(coverage,true);
		return new MemoOptimizer(sizes,coverage,costs,checks,terms,
			new Limits(Long.MAX_VALUE,supportBudget,cache)).optimize();
	}

	private static void assertSameSolve(Result expected,Result actual) {
		Assert.assertEquals(expected.status(),actual.status());
		Assert.assertEquals(expected.cost(),actual.cost());
		Assert.assertArrayEquals(expected.selectedRules(),actual.selectedRules());
	}
}
