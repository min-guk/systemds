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

package org.apache.sysds.test.component.federated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FederationUtilsRefedReuseLayoutTest {
	@Before
	public void setUp() {
		FederationUtils.clearRefedReuseCache();
	}

	@After
	public void tearDown() {
		FederationUtils.clearRefedReuseCache();
	}

	@Test
	public void testRefedReuseSameLayoutDifferentMapIdHits() {
		FederationMap left = rowMap(1L, 0, 5, 5, 10);
		FederationMap right = rowMap(999L, 0, 5, 5, 10);

		String leftLayout = FederationUtils.deriveFedLayoutSignature(left);
		String rightLayout = FederationUtils.deriveFedLayoutSignature(right);
		assertEquals(leftLayout, rightLayout);
		assertNotEquals(left.getID(), right.getID());

		FederationUtils.putRefedReuseMap("_mVar311", 77L, 1L,
			10, 50, 500, leftLayout, FType.ROW, left);

		FederationMap cached = FederationUtils.getRefedReuseMap("_mVar311", 88L, 1L,
			10, 50, 500, rightLayout, FType.ROW);
		assertNotNull("same semantic layout should hit even if anchor map ids differ", cached);
		assertEquals(FType.ROW, cached.getType());
		assertEquals(2, cached.getSize());
	}

	@Test
	public void testRefedReuseDifferentLayoutMisses() {
		FederationMap left = rowMap(1L, 0, 5, 5, 10);
		FederationMap right = rowMap(2L, 0, 4, 4, 10);

		FederationUtils.putRefedReuseMap("_mVar311", 77L, 1L,
			10, 50, 500, FederationUtils.deriveFedLayoutSignature(left), FType.ROW, left);

		FederationMap cached = FederationUtils.getRefedReuseMap("_mVar311", 88L, 1L,
			10, 50, 500, FederationUtils.deriveFedLayoutSignature(right), FType.ROW);
		assertNull("different row ranges must miss", cached);
	}

	@Test
	public void testRefedReuseMutationMisses() {
		FederationMap layout = rowMap(1L, 0, 5, 5, 10);
		String layoutSig = FederationUtils.deriveFedLayoutSignature(layout);

		FederationUtils.putRefedReuseMap("_mVar311", 77L, 1L,
			10, 50, 500, layoutSig, FType.ROW, layout);

		FederationMap cached = FederationUtils.getRefedReuseMap("_mVar311", 88L, 2L,
			10, 50, 500, layoutSig, FType.ROW);
		assertNull("mutation changes must still invalidate reuse", cached);
	}

	@Test
	public void testBuildAnchorMapFromKeyPreservesRowRanges() {
		FederationMap original = rowMap(1L, 0, 5, 5, 10);
		String anchorKey = FederationUtils.deriveFedLayoutSignature(original);

		FederationMap rebuilt = FederationUtils.buildAnchorMapFromKey(anchorKey);
		assertNotNull(rebuilt);
		assertEquals(FType.ROW, rebuilt.getType());
		assertEquals(original.getSize(), rebuilt.getSize());

		FederatedRange[] origRanges = original.getFederatedRanges();
		FederatedRange[] rebuiltRanges = rebuilt.getFederatedRanges();
		for (int i = 0; i < origRanges.length; i++) {
			assertEquals(origRanges[i].getBeginDims()[0], rebuiltRanges[i].getBeginDims()[0]);
			assertEquals(origRanges[i].getEndDims()[0], rebuiltRanges[i].getEndDims()[0]);
			assertEquals(original.getFederatedData()[i].getAddress(), rebuilt.getFederatedData()[i].getAddress());
		}
	}

	private static FederationMap rowMap(long mapId, long r0, long r1, long r2, long r3) {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		entries.add(new ImmutablePair<>(
			new FederatedRange(new long[] {r0, 0}, new long[] {r1, 50}),
			new FederatedData(Types.DataType.MATRIX, new InetSocketAddress("localhost", 14000), "left")));
		entries.add(new ImmutablePair<>(
			new FederatedRange(new long[] {r2, 0}, new long[] {r3, 50}),
			new FederatedData(Types.DataType.MATRIX, new InetSocketAddress("localhost", 14001), "right")));
		return new FederationMap(mapId, entries, FType.ROW);
	}
}
