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

package org.apache.sysds.runtime.controlprogram.federated;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.net.InetSocketAddress;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.junit.Test;

public class CanonicalWorkerPoolTest {
	@Test
	public void descriptorRoundTripPreservesOrderAndRuntimeMapDerivation() {
		CanonicalWorkerPool pool = CanonicalWorkerPool.of(List.of(
			new InetSocketAddress("worker-b", 2345), new InetSocketAddress("worker-a", 1234)));
		CanonicalWorkerPool parsed = CanonicalWorkerPool.parse(pool.encode());
		assertEquals("worker-b", parsed.workers().get(0).getHostString());
		assertEquals(2345, parsed.workers().get(0).getPort());
		assertEquals("worker-a", parsed.workers().get(1).getHostString());

		FederationMap map = parsed.newMap(DataType.MATRIX, 7, FType.ROW, 5, 3);
		assertEquals(parsed.encode(), CanonicalWorkerPool.fromFederationMap(map).encode());
	}

	@Test
	public void duplicateAndMalformedPoolsAreRejected() {
		InetSocketAddress worker = new InetSocketAddress("localhost", 1234);
		assertThrows(DMLRuntimeException.class, () -> CanonicalWorkerPool.of(List.of(worker, worker)));
		assertThrows(DMLRuntimeException.class, () -> CanonicalWorkerPool.parse("POOLv1:"));
		assertThrows(DMLRuntimeException.class, () -> CanonicalWorkerPool.parse("anchor-key"));
	}

	@Test
	public void canonicalRangesUseOrderedRemainderAndForbidEmptySlices() {
		CanonicalWorkerPool pool = CanonicalWorkerPool.of(List.of(
			new InetSocketAddress("worker-a", 1234), new InetSocketAddress("worker-b", 2345)));
		FederatedRange[] rows = pool.canonicalRanges(FType.ROW, 5, 3);
		assertRange(rows[0], new long[] {0, 0}, new long[] {3, 3});
		assertRange(rows[1], new long[] {3, 0}, new long[] {5, 3});
		FederatedRange[] cols = pool.canonicalRanges(FType.COL, 3, 5);
		assertRange(cols[0], new long[] {0, 0}, new long[] {3, 3});
		assertRange(cols[1], new long[] {0, 3}, new long[] {3, 5});
		assertThrows(DMLRuntimeException.class, () -> pool.canonicalRanges(FType.ROW, 1, 3));
		assertThrows(DMLRuntimeException.class, () -> pool.canonicalRanges(FType.COL, 3, 1));
	}

	@Test
	public void singletonNormalizesEveryFedTargetToFullAndMultiworkerRepToBroadcast() {
		CanonicalWorkerPool singleton = CanonicalWorkerPool.of(
			List.of(new InetSocketAddress("worker-a", 1234)));
		assertEquals(FType.FULL, singleton.runtimeType(FType.ROW));
		assertEquals(FType.FULL, singleton.runtimeType(FType.COL));
		assertEquals(FType.FULL, singleton.runtimeType(FType.BROADCAST));

		CanonicalWorkerPool pair = CanonicalWorkerPool.of(List.of(
			new InetSocketAddress("worker-a", 1234), new InetSocketAddress("worker-b", 2345)));
		assertEquals(FType.BROADCAST, pair.runtimeType(FType.FULL));
		assertEquals(FType.BROADCAST, pair.runtimeType(FType.BROADCAST));
	}

	@Test
	public void ingressRequiresExactWorkerOrderTypeAndCanonicalRanges() {
		InetSocketAddress first = new InetSocketAddress("worker-a", 1234);
		InetSocketAddress second = new InetSocketAddress("worker-b", 2345);
		CanonicalWorkerPool pool = CanonicalWorkerPool.of(List.of(first, second));
		FederationMap valid = pool.newMap(DataType.MATRIX, 7, FType.ROW, 5, 3);
		pool.validateIngress(valid, DataType.MATRIX, FType.ROW, 5, 3);

		List<Pair<FederatedRange, FederatedData>> reversed = List.of(
			Pair.of(valid.getMap().get(0).getKey(), initialized(second, 7)),
			Pair.of(valid.getMap().get(1).getKey(), initialized(first, 7)));
		FederationMap invalid = new FederationMap(7, reversed, FType.ROW);
		assertThrows(DMLRuntimeException.class,
			() -> pool.validateIngress(invalid, DataType.MATRIX, FType.ROW, 5, 3));
		FederationMap wrongType = new FederationMap(7, valid.getMap(), FType.COL);
		assertThrows(DMLRuntimeException.class,
			() -> pool.validateIngress(wrongType, DataType.MATRIX, FType.ROW, 5, 3));
		FederationMap wrongRange = new FederationMap(7, List.of(
			Pair.of(new FederatedRange(new long[] {0, 0}, new long[] {2, 3}), initialized(first, 7)),
			Pair.of(new FederatedRange(new long[] {2, 0}, new long[] {5, 3}), initialized(second, 7))), FType.ROW);
		assertThrows(DMLRuntimeException.class,
			() -> pool.validateIngress(wrongRange, DataType.MATRIX, FType.ROW, 5, 3));
		assertThrows(DMLRuntimeException.class,
			() -> pool.validateIngress(valid, DataType.FRAME, FType.ROW, 5, 3));
	}

	private static FederatedData initialized(InetSocketAddress address, long id) {
		return new FederatedData(DataType.MATRIX, address, "test", id);
	}

	private static void assertRange(FederatedRange actual, long[] begin, long[] end) {
		assertArrayEquals(begin, actual.getBeginDims());
		assertArrayEquals(end, actual.getEndDims());
	}
}
