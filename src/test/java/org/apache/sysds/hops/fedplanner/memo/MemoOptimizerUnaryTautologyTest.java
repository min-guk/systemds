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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Limits;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Result;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Status;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

public class MemoOptimizerUnaryTautologyTest {
	@Test public void nonzeroFixedOrdinalIsExpandedInOriginalOwnerOrderAndRemoved() throws Exception {
		for(int cache:new int[] {0,4096}) {
			MemoOptimizer optimizer=cycleFixture(cache,Long.MAX_VALUE);
			Result result=optimizer.optimize();
			Assert.assertEquals(Status.OPTIMAL,result.status());
			Assert.assertEquals(new BigDecimal("0.5"),result.cost());
			Assert.assertArrayEquals(new int[] {2,0,0,0},result.selectedRules());
			Assert.assertEquals("only the three cycle checks remain",3,checks(optimizer).size());
			BitSet fixed=domains(optimizer)[0];
			Assert.assertEquals(1,fixed.cardinality());
			Assert.assertTrue(fixed.get(2));
		}
	}

	@Test public void unknownUnaryIsRetainedAndNoUnaryOnlyRemovesItsRejectedOrdinal() throws Exception {
		Check mixed=new Check(11,new int[] {0},r -> r[0]<0 ? Truth.UNKNOWN
			: r[0]==0 ? Truth.YES : Truth.UNKNOWN);
		MemoOptimizer unresolved=optimizer(new int[] {2},new double[][] {{1,0}},List.of(mixed),
			Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.UNRESOLVED,unresolved.optimize().status());
		Assert.assertEquals(1,checks(unresolved).size());

		Check rejectOne=new Check(12,new int[] {0},r -> r[0]<0 ? Truth.UNKNOWN
			: r[0]==1 ? Truth.NO : Truth.YES);
		MemoOptimizer reduced=optimizer(new int[] {3},new double[][] {{3,2,1}},List.of(rejectOne),
			Long.MAX_VALUE,4096);
		Result result=reduced.optimize();
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(new int[] {2},result.selectedRules());
		Assert.assertEquals(0,checks(reduced).size());
		Assert.assertFalse("the initial rejection must survive every search undo",domains(reduced)[0].get(1));
	}

	@Test public void emptyYesUnknownAndNoKeepTheirDistinctProofStatus() throws Exception {
		MemoOptimizer yes=optimizer(new int[] {2},new double[][] {{0,1}},
			List.of(new Check(20,new int[0],r -> Truth.YES)),Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.OPTIMAL,yes.optimize().status());
		Assert.assertEquals(0,checks(yes).size());

		MemoOptimizer unknown=optimizer(new int[] {2},new double[][] {{0,1}},
			List.of(new Check(21,new int[0],r -> Truth.UNKNOWN)),Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.UNRESOLVED,unknown.optimize().status());
		Assert.assertEquals(1,checks(unknown).size());

		MemoOptimizer no=optimizer(new int[] {2},new double[][] {{0,1}},
			List.of(new Check(22,new int[0],r -> Truth.NO)),Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.INFEASIBLE,no.optimize().status());
		Assert.assertEquals("normalization must not erase an impossible obligation",1,checks(no).size());
	}

	@Test public void filteringOccursBeforeSameScopeGrouping() throws Exception {
		Check tautology=new Check(30,new int[] {0},r -> Truth.YES);
		Check obligation=new Check(31,new int[] {0},r -> r[0]<0 ? Truth.UNKNOWN
			: r[0]==0 ? Truth.YES : Truth.UNKNOWN);
		MemoOptimizer optimizer=optimizer(new int[] {2},new double[][] {{1,0}},
			List.of(tautology,obligation),Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.UNRESOLVED,optimizer.optimize().status());
		List<Check> retained=checks(optimizer);
		Assert.assertEquals(1,retained.size());
		Assert.assertEquals("the original UNKNOWN provenance remains",31,retained.get(0).site());
	}

	@Test public void finiteBudgetNeverTurnsTheSimplificationIntoAnExactProof() {
		for(int cache:new int[] {0,4096}) {
			Result limited=cycleFixture(cache,1).optimize();
			Assert.assertEquals(Status.RESOURCE_EXHAUSTED,limited.status());
		}
	}

	private static MemoOptimizer cycleFixture(int cache,long nodes) {
		Check fixNonzero=new Check(0,new int[] {0},r -> r[0]<0 ? Truth.UNKNOWN
			: r[0]==2 ? Truth.YES : Truth.NO);
		// The remaining variable precedes the fixed owner in the original scope.
		Check projectedTautology=new Check(1,new int[] {1,0},r -> r[1]>=0 && r[1]!=2
			? Truth.NO : r[0]<0 || r[1]<0 ? Truth.UNKNOWN : Truth.YES);
		List<Check> checks=List.of(fixNonzero,projectedTautology,edge(1,2),edge(2,3),edge(3,1));
		return optimizer(new int[] {3,2,2,2},new double[][] {{9,8,0.5},{0,0},{0,0},{0,0}},
			checks,nodes,cache);
	}

	private static Check edge(int left,int right) {
		return new Check(left,new int[] {left,right},r -> Truth.YES);
	}

	private static MemoOptimizer optimizer(int[] sizes,double[][] costs,List<Check> checks,
		long nodes,int cache) {
		boolean[] coverage=new boolean[sizes.length]; Arrays.fill(coverage,true);
		return new MemoOptimizer(sizes,coverage,costs,checks,List.of(),new Limits(nodes,64,cache));
	}

	@SuppressWarnings("unchecked")
	private static List<Check> checks(MemoOptimizer optimizer) throws Exception {
		return (List<Check>)field("checks").get(optimizer);
	}

	private static BitSet[] domains(MemoOptimizer optimizer) throws Exception {
		return (BitSet[])field("domains").get(optimizer);
	}

	private static Field field(String name) throws Exception {
		Field result=MemoOptimizer.class.getDeclaredField(name);
		result.setAccessible(true);
		return result;
	}
}
