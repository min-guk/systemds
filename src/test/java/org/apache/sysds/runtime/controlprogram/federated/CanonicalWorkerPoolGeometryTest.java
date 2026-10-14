/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.runtime.controlprogram.federated;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.junit.Test;

public class CanonicalWorkerPoolGeometryTest {
	@Test
	public void p2W3AndW7CeilTailRangesRoundTripThroughV2Descriptor() {
		assertGeometry(new long[] {0,33334,66668,100000});
		assertGeometry(new long[] {0,14286,28572,42858,57144,71430,85716,100000});
	}

	@Test
	public void v1AndUnprofiledLengthsRetainOrderedRemainderGeometry() {
		CanonicalWorkerPool v1=CanonicalWorkerPool.of(workers(5));
		assertEquals(true,v1.encode().startsWith("POOLv1:"));
		assertArrayEquals(new long[] {0,20000,40000,60000,80000,100000},v1.axisCuts(100000));
		CanonicalWorkerPool parsed=CanonicalWorkerPool.parse(v1.encode());
		assertEquals(v1.encode(),parsed.encode());

		CanonicalWorkerPool profiled=CanonicalWorkerPool.of(workers(3),
			Map.of(10L,new long[] {0,4,8,10}));
		assertArrayEquals(new long[] {0,3,6,8},profiled.axisCuts(8));
	}

	@Test
	public void geometryIsImmutableAndMalformedProfilesAreRejected() {
		long[] supplied={0,4,8,10};
		CanonicalWorkerPool pool=CanonicalWorkerPool.of(workers(3),Map.of(10L,supplied));
		supplied[1]=1;
		assertArrayEquals(new long[] {0,4,8,10},pool.axisCuts(10));
		long[] returned=pool.axisCuts(10); returned[1]=2;
		assertArrayEquals(new long[] {0,4,8,10},pool.axisCuts(10));

		String endpoints=CanonicalWorkerPool.of(workers(3)).encode().substring("POOLv1:".length());
		for(String malformed : List.of("POOLv2:"+endpoints,
			"POOLv2:"+endpoints+"|10:0.4.10", "POOLv2:"+endpoints+"|10:0.4.4.10",
			"POOLv2:"+endpoints+"|10:0.4.8.9", "POOLv2:"+endpoints+"|10:0.4.x.10",
			"POOLv2:"+endpoints+"|10:0.4.8.10,10:0.3.7.10"))
			assertRejected(malformed,()->CanonicalWorkerPool.parse(malformed));
	}

	private static void assertGeometry(long[] cuts) {
		CanonicalWorkerPool pool=CanonicalWorkerPool.of(workers(cuts.length-1),Map.of(100000L,cuts));
		CanonicalWorkerPool parsed=CanonicalWorkerPool.parse(pool.encode());
		assertEquals(pool.encode(),parsed.encode());
		assertArrayEquals(cuts,parsed.axisCuts(100000));
		FederatedRange[] rows=parsed.canonicalRanges(FType.ROW,100000,7);
		for(int i=0;i<rows.length;i++) {
			assertArrayEquals(new long[] {cuts[i],0},rows[i].getBeginDims());
			assertArrayEquals(new long[] {cuts[i+1],7},rows[i].getEndDims());
		}
	}

	private static List<InetSocketAddress> workers(int count) {
		List<InetSocketAddress> result=new ArrayList<>();
		for(int i=0;i<count;i++) result.add(InetSocketAddress.createUnresolved("worker-"+i,19000+i));
		return result;
	}

	private static void assertRejected(String message,Runnable action) {
		try { action.run(); fail(message); }
		catch(DMLRuntimeException expected) { /* expected */ }
	}
}
