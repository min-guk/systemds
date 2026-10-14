/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Limits;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Result;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Status;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

public class MemoOptimizerBinarySupportTest {
	@Test public void retainedPartnerMaskTracksMultiplePartnersAcrossRemoveAndUndo() throws Exception {
		Check relation=new Check(0,new int[] {0,1},values -> partial(values) ? Truth.UNKNOWN
			: values[0]==0 && values[1]<=1 ? Truth.YES : Truth.NO);
		MemoOptimizer optimizer=optimizer(new int[] {2,3},List.of(relation),32,4096,Long.MAX_VALUE);
		Assert.assertEquals(Truth.YES,support(optimizer,0,0,0)); // learns partner 0
		remove(optimizer,1,0);
		Assert.assertEquals(Truth.YES,support(optimizer,0,0,0)); // learns partner 1
		undo(optimizer,0);
		long[][][] retained=binarySupports(optimizer);
		Assert.assertEquals("both certified partners must be retained",3L,retained[0][0][0]);

		int mark=trailSize(optimizer);
		remove(optimizer,1,0);
		long hits=supportWitnessHits(optimizer);
		Assert.assertEquals(Truth.YES,support(optimizer,0,0,0));
		Assert.assertEquals("the second live partner must satisfy the mask",hits+1,supportWitnessHits(optimizer));
		remove(optimizer,1,1);
		Assert.assertEquals("a mask with no live retained partner must not certify YES",
			Truth.NO,support(optimizer,0,0,0));
		undo(optimizer,mark);
		Assert.assertEquals(Truth.YES,support(optimizer,0,0,0));
	}

	@Test public void bit63UsesTheCompactMaskAndWidth65UsesTheWitnessFallback() throws Exception {
		for(int width:new int[] {64,65}) {
			Check high=new Check(0,new int[] {0,1},values -> partial(values) ? Truth.UNKNOWN
				: values[0]==width-1 && values[1]==1 ? Truth.YES : Truth.NO);
			MemoOptimizer optimizer=optimizer(new int[] {width,2},List.of(high),256,4096,Long.MAX_VALUE);
			Assert.assertEquals(Truth.YES,support(optimizer,0,0,width-1));
			long hits=supportWitnessHits(optimizer);
			Assert.assertEquals(Truth.YES,support(optimizer,0,0,width-1));
			Assert.assertEquals(hits+1,supportWitnessHits(optimizer));
			if(width==64) {
				Assert.assertNotNull(binarySupports(optimizer)[0]);
				Assert.assertEquals(1L<<63,binarySupports(optimizer)[0][1][1]);
			}
			else Assert.assertNull("a wide owner must retain the full tuple witness",binarySupports(optimizer)[0]);
		}
	}

	@Test public void ternaryCorrelationNeverBecomesABinaryPartnerProof() throws Exception {
		Check parity=new Check(0,new int[] {0,1,2},values -> partial(values) ? Truth.UNKNOWN
			: (values[0]^values[1]^values[2])==0 ? Truth.YES : Truth.NO);
		MemoOptimizer optimizer=optimizer(new int[] {2,2,2},List.of(parity),32,4096,Long.MAX_VALUE);
		Assert.assertEquals(Truth.YES,support(optimizer,0,0,0));
		Assert.assertNull(binarySupports(optimizer)[0]);
		remove(optimizer,2,0); // invalidates the first completed tuple [0,0,0]
		Assert.assertEquals("the remaining correlated tuple must be searched, not inferred from one partner",
			Truth.YES,support(optimizer,0,0,0));
	}

	@Test public void binaryRowsRespectDisabledTinyAndBoundedCaches() throws Exception {
		Check relation=new Check(0,new int[] {0,1},values -> partial(values) ? Truth.UNKNOWN : Truth.YES);
		for(int cache:new int[] {0,1,4,5,4096}) {
			MemoOptimizer optimizer=optimizer(new int[] {2,3},List.of(relation),16,cache,Long.MAX_VALUE);
			Assert.assertEquals(Truth.YES,support(optimizer,0,0,0));
			Assert.assertEquals("two plus three retained rows require capacity five",cache>=5,
				binarySupports(optimizer)[0]!=null);
			Assert.assertEquals(cache>=5 ? 5 : 0,binarySupportRows(optimizer));
		}
	}

	@Test public void normalizationClearsRowsTiedToTheFormerCheckIdentity() throws Exception {
		Check permissive=new Check(0,new int[] {0,1},values -> partial(values) ? Truth.UNKNOWN : Truth.YES);
		Check unequal=new Check(1,new int[] {0,1},values -> partial(values) ? Truth.UNKNOWN
			: values[0]!=values[1] ? Truth.YES : Truth.NO);
		MemoOptimizer optimizer=optimizer(new int[] {2,2},List.of(permissive,unequal),16,4096,Long.MAX_VALUE);
		Assert.assertEquals(Truth.YES,support(optimizer,0,0,0));
		Assert.assertNotNull(binarySupports(optimizer)[0]);
		invoke(optimizer,"normalizeAfterPropagation",new Class<?>[0]);
		Assert.assertEquals(0,binarySupportRows(optimizer));
		for(long[][] rows:binarySupports(optimizer)) Assert.assertNull(rows);
		Assert.assertEquals("the normalized conjunction must be evaluated under its new identity",
			Truth.YES,support(optimizer,0,0,0));
		Assert.assertEquals(Truth.YES,support(optimizer,0,0,1));
	}

	@Test public void unknownAndFiniteSearchBudgetsAreNotPromotedByBinaryRows() throws Exception {
		Check unknown=new Check(0,new int[] {0,1},values -> Truth.UNKNOWN);
		MemoOptimizer direct=optimizer(new int[] {2,2},List.of(unknown),1,4096,Long.MAX_VALUE);
		Assert.assertEquals(Truth.UNKNOWN,support(direct,0,0,0));
		Assert.assertNull(binarySupports(direct)[0]);

		Check equality=new Check(0,new int[] {0,1},values -> partial(values) ? Truth.UNKNOWN
			: values[0]==values[1] ? Truth.YES : Truth.NO);
		Result uncached=optimizer(new int[] {3,3},List.of(equality),16,0,1).optimize();
		Result cached=optimizer(new int[] {3,3},List.of(equality),16,4096,1).optimize();
		Assert.assertEquals(Status.RESOURCE_EXHAUSTED,uncached.status());
		Assert.assertEquals(uncached.status(),cached.status());
		Assert.assertEquals(uncached.cost(),cached.cost());
		Assert.assertArrayEquals(uncached.selectedRules(),cached.selectedRules());
	}

	private static boolean partial(int[] values) {
		return Arrays.stream(values).anyMatch(value -> value<0);
	}

	private static MemoOptimizer optimizer(int[] sizes,List<Check> checks,int supportBudget,int cache,long nodes) {
		double[][] costs=new double[sizes.length][];
		for(int owner=0;owner<sizes.length;owner++) costs[owner]=new double[sizes[owner]];
		boolean[] coverage=new boolean[sizes.length]; Arrays.fill(coverage,true);
		return new MemoOptimizer(sizes,coverage,costs,checks,List.of(),new Limits(nodes,supportBudget,cache));
	}

	private static Truth support(MemoOptimizer optimizer,int check,int owner,int value) throws Exception {
		return (Truth)invoke(optimizer,"support",new Class<?>[] {int.class,int.class,int.class},check,owner,value);
	}

	private static void remove(MemoOptimizer optimizer,int owner,int value) throws Exception {
		invoke(optimizer,"remove",new Class<?>[] {int.class,int.class},owner,value);
	}

	private static void undo(MemoOptimizer optimizer,int mark) throws Exception {
		invoke(optimizer,"undo",new Class<?>[] {int.class},mark);
	}

	private static int trailSize(MemoOptimizer optimizer) throws Exception {
		return ((List<?>)field(MemoOptimizer.class,"trail").get(optimizer)).size();
	}

	private static long[][][] binarySupports(MemoOptimizer optimizer) throws Exception {
		return (long[][][])field(MemoOptimizer.class,"binarySupports").get(optimizer);
	}

	private static int binarySupportRows(MemoOptimizer optimizer) throws Exception {
		return field(MemoOptimizer.class,"binarySupportRows").getInt(optimizer);
	}

	private static long supportWitnessHits(MemoOptimizer optimizer) throws Exception {
		return field(MemoOptimizer.class,"supportWitnessHits").getLong(optimizer);
	}

	private static Object invoke(Object receiver,String name,Class<?>[] parameterTypes,Object...arguments)
		throws Exception {
		Method method=receiver.getClass().getDeclaredMethod(name,parameterTypes);
		method.setAccessible(true);
		return method.invoke(receiver,arguments);
	}

	private static Field field(Class<?> owner,String name) throws Exception {
		Field result=owner.getDeclaredField(name); result.setAccessible(true); return result;
	}
}
