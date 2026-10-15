/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.BitSet;
import java.util.List;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.DirectResult;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Limits;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Status;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

public class MemoDirectKernelTest {
	@Test public void directEliminationMatchesExistingExactAndPreservesCanonicalTie() {
		double[][] costs={{0,0},{0,0},{0,0}};
		List<Check> cycle=List.of(edge(0,1),edge(1,2),edge(2,0));
		MemoOptimizer expected=optimizer(costs,cycle,List.of());
		MemoOptimizer direct=optimizer(costs,cycle,List.of());
		DirectResult result=direct.optimizeDirect(null,new MemoResourcePolicy(Long.MAX_VALUE));
		Assert.assertTrue(result.completed());
		Assert.assertEquals("elimination",result.kernel());
		Assert.assertEquals(Status.OPTIMAL,result.result().status());
		Assert.assertArrayEquals(expected.optimize().selectedRules(),result.result().selectedRules());
		Assert.assertArrayEquals(new int[] {0,0,0},result.result().selectedRules());
	}

	@Test public void directTreeHonorsSparseOriginalOrdinalRestrictions() {
		double[][] costs={{5,4,0},{0,1}};
		BitSet[] restrictions={bits(2),bits(0,1)};
		MemoOptimizer optimizer=optimizer(costs,List.of(edge(0,1)),List.of());
		DirectResult result=optimizer.optimizeDirect(restrictions,new MemoResourcePolicy(Long.MAX_VALUE));
		restrictions[0].clear();
		Assert.assertTrue(result.completed());
		Assert.assertEquals("tree",result.kernel());
		Assert.assertArrayEquals(new int[] {2,0},result.result().selectedRules());
		Assert.assertEquals(bits(2),result.domains()[0]);
	}

	@Test public void admissionRejectionReturnsAnUnchangedProofDomainForHandoff() {
		double[][] costs={{0,1},{0,1},{0,1}};
		CostTerm ternary=new CostTerm(new int[] {0,1,2},r -> r[0]+r[1]+r[2],0);
		MemoResourcePolicy resources=new MemoResourcePolicy(0);
		DirectResult result=optimizer(costs,List.of(),List.of(ternary)).optimizeDirect(null,resources);
		Assert.assertFalse(result.completed());
		Assert.assertEquals(Status.UNRESOLVED,result.result().status());
		for(BitSet domain:result.domains()) Assert.assertEquals(bits(0,1),domain);
		Assert.assertTrue(resources.stats().rejections()>0);
	}

	@Test public void workSelectorDeclinesWideKernelWithoutChangingTheProblem() {
		double[][] costs={{0,1},{0,1},{0,1}};
		List<Check> cycle=List.of(edge(0,1),edge(1,2),edge(2,0));
		DirectResult result=optimizer(costs,cycle,List.of()).optimizeDirect(null,
			new MemoResourcePolicy(Long.MAX_VALUE),0);
		Assert.assertFalse(result.completed());
		Assert.assertEquals(Status.UNRESOLVED,result.result().status());
		for(BitSet domain:result.domains()) Assert.assertEquals(bits(0,1),domain);
		Assert.assertEquals(0,result.result().statistics().eliminationVariables());
	}

	@Test public void resourceCeilingCountsResidentAndAdditionalSeparately() {
		MemoResourcePolicy resources=new MemoResourcePolicy(1000);
		Assert.assertTrue(resources.admit(400,500));
		Assert.assertFalse(resources.admit(600,500));
		MemoResourcePolicy scoped=resources.withResidentBytes(700);
		Assert.assertTrue(scoped.admit(300));
		Assert.assertFalse(scoped.admit(301));
		Assert.assertEquals(1100,resources.peakEstimatedBytes());
		Assert.assertEquals(resources.stats(),scoped.stats());
		Assert.assertEquals(2,resources.stats().admissions());
		Assert.assertEquals(2,resources.stats().rejections());
	}

	private static MemoOptimizer optimizer(double[][] costs,List<Check> checks,List<CostTerm> terms) {
		int[] sizes=new int[costs.length];
		boolean[] coverage=new boolean[costs.length];
		for(int owner=0;owner<costs.length;owner++) { sizes[owner]=costs[owner].length; coverage[owner]=true; }
		return new MemoOptimizer(sizes,coverage,costs,checks,terms,Limits.exactDefaults());
	}

	private static Check edge(int left,int right) {
		return new Check(left,new int[] {left,right},r -> {
			for(int value:r) if(value<0) return Truth.UNKNOWN;
			return Truth.YES;
		});
	}

	private static BitSet bits(int... values) {
		BitSet result=new BitSet();
		for(int value:values) result.set(value);
		return result;
	}
}
