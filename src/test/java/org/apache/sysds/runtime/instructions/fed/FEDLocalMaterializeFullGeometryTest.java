/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.runtime.instructions.fed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.junit.Test;

public class FEDLocalMaterializeFullGeometryTest {
	@Test
	public void singletonFullUsesEncodedRuntimeDimensionsInsteadOfStaleAnchorWidth() {
		FederationMap staleAnchor = federationMap(FType.FULL,
			new long[][][] {{{0, 0}, {10, 2}}});
		FederationMap dummycodeExpanded = federationMap(FType.FULL,
			new long[][][] {{{0, 0}, {10, 5}}});

		assertTrue("FULL planning must derive the expected range from the encoded value's runtime dimensions",
			FEDLocalMaterializeUtil.matchesPlannedLayout(dummycodeExpanded, staleAnchor,
				FType.FULL, FType.FULL, 10, 5));
		assertFalse("The stale pre-dummycode width must not describe the encoded FULL value",
			FEDLocalMaterializeUtil.matchesPlannedLayout(dummycodeExpanded, staleAnchor,
				FType.FULL, FType.FULL, 10, 2));
	}

	@Test
	public void multiEndpointFullIsNotASinglePartitionProof() {
		FederationMap malformedFull = federationMap(FType.FULL,
			new long[][][] {{{0, 0}, {10, 5}}, {{0, 0}, {10, 5}}});

		assertThrows(DMLRuntimeException.class,
			() -> FEDLocalMaterializeUtil.declaredAnchorType(malformedFull));
		assertFalse(FEDLocalMaterializeUtil.matchesPlannedLayout(malformedFull, malformedFull,
			FType.FULL, FType.FULL, 10, 5));
	}

	private static FederationMap federationMap(FType type, long[][][] ranges) {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		for(int index = 0; index < ranges.length; index++) {
			FederatedRange range = new FederatedRange(ranges[index][0], ranges[index][1]);
			FederatedData data = new FederatedData(DataType.MATRIX,
				new InetSocketAddress("localhost", 15000 + index), null);
			entries.add(Pair.of(range, data));
		}
		return new FederationMap(1, entries, type);
	}
}
