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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.Arrays;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

public class MemoOptimizerScopeValidationTest {
	private static final String DUPLICATE="A shared owner appears once in a condition scope";

	@Test public void preservesAllIntegerValuesOrderAndDefensiveCopies() {
		int[] input={Integer.MAX_VALUE,-1,0,Integer.MIN_VALUE,7};
		Check check=new Check(3,input,values -> Truth.YES);
		input[0]=42;
		Assert.assertArrayEquals(new int[] {Integer.MAX_VALUE,-1,0,Integer.MIN_VALUE,7},check.owners());
		int[] exported=check.owners();
		exported[1]=42;
		Assert.assertArrayEquals(new int[] {Integer.MAX_VALUE,-1,0,Integer.MIN_VALUE,7},check.owners());

		int[] costInput={7,Integer.MIN_VALUE,0,-1,Integer.MAX_VALUE};
		CostTerm term=new CostTerm(costInput,values -> 0,0);
		costInput[0]=42;
		Assert.assertArrayEquals(new int[] {7,Integer.MIN_VALUE,0,-1,Integer.MAX_VALUE},term.owners());
	}

	@Test public void preservesNullAndDuplicateValidationPrecedence() {
		Assert.assertThrows(NullPointerException.class,() -> new Check(0,null,null));
		Assert.assertThrows(NullPointerException.class,() -> new CostTerm(null,null,0));
		assertDuplicate(() -> new Check(0,new int[] {4,1,4},null));
		assertDuplicate(() -> new Check(0,new int[] {4,1,4},values -> Truth.YES,new int[0][]));
		assertDuplicate(() -> new CostTerm(new int[] {4,1,4},null,Double.NaN));
		assertDuplicate(() -> new CostTerm(new int[] {4,1,4},values -> 0,0,new int[0][]));
	}

	@Test public void literalLegacyAndPrimitiveValidationAgreeAcrossRepresentativeWidths() {
		int[][] inputs={
			null, {}, {9}, {9,-1}, {2,0,1}, {Integer.MIN_VALUE,0,Integer.MAX_VALUE,-1},
			sequence(32), sequence(128), {1,1}, {3,1,3}, {0,1,2,0},
			{Integer.MIN_VALUE,0,Integer.MIN_VALUE}, {Integer.MAX_VALUE,-1,Integer.MAX_VALUE}
		};
		for(int[] input:inputs) {
			Outcome legacy=legacy(input);
			Outcome primitive=primitive(input);
			Assert.assertEquals(Arrays.toString(input),legacy.error(),primitive.error());
			if(legacy.error()==null) Assert.assertArrayEquals(Arrays.toString(input),legacy.scope(),primitive.scope());
		}
	}

	private static Outcome legacy(int[] owners) {
		try {
			int[] copy=owners.clone();
			if(Arrays.stream(copy).distinct().count()!=copy.length)
				throw new IllegalArgumentException(DUPLICATE);
			return new Outcome(copy,null);
		}
		catch(RuntimeException exception) { return new Outcome(null,signature(exception)); }
	}

	private static Outcome primitive(int[] owners) {
		try { return new Outcome(new Check(0,owners,values -> Truth.YES).owners(),null); }
		catch(RuntimeException exception) { return new Outcome(null,signature(exception)); }
	}

	private static int[] sequence(int length) {
		int[] result=new int[length];
		for(int index=0;index<length;index++) result[index]=length-index;
		return result;
	}

	private static String signature(RuntimeException exception) {
		return exception.getClass().getName()+":"+exception.getMessage();
	}

	private static void assertDuplicate(Runnable action) {
		IllegalArgumentException exception=Assert.assertThrows(IllegalArgumentException.class,action::run);
		Assert.assertEquals(DUPLICATE,exception.getMessage());
	}

	private record Outcome(int[] scope,String error) { }
}
