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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.junit.Test;

public class FederationMapParallelTest {
	@Test
	public void forEachParallelPropagatesCallbackFailure() {
		FederationMap map = map();
		AtomicInteger callbacks = new AtomicInteger();

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> map.forEachParallel((range, data) -> {
				callbacks.incrementAndGet();
				throw new IllegalStateException("callback failed");
			}));

		assertEquals("callback failed", failure.getMessage());
		assertEquals(1, callbacks.get());
	}

	@Test
	public void mapParallelPropagatesCallbackFailure() {
		FederationMap map = map();
		AtomicInteger callbacks = new AtomicInteger();

		IllegalStateException failure = assertThrows(IllegalStateException.class,
			() -> map.mapParallel(9, (range, data) -> {
				callbacks.incrementAndGet();
				throw new IllegalStateException("mapping failed");
			}));

		assertEquals("mapping failed", failure.getMessage());
		assertEquals(1, callbacks.get());
		assertEquals("a failed map must not publish the requested output id", 1, map.getID());
	}

	private static FederationMap map() {
		FederatedRange range = new FederatedRange(new long[] {0, 0}, new long[] {2, 3});
		FederatedData data = new FederatedData(DataType.MATRIX,
			new InetSocketAddress("localhost", 15000), "unused");
		return new FederationMap(1, List.of(Pair.of(range, data)), FType.ROW);
	}
}
