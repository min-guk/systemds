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

package org.apache.sysds.hops.fedplanner.placement;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.junit.Test;

public class MaterializedOutputLayoutTest {
	@Test
	public void samePhysicalUploadDoesNotDependOnSeedFilesOrProducerName() {
		DurableAnchorKey a = new DurableAnchorKey("source-A", FType.ROW, List.of(
			partition("localhost:19001/A1", 0, 0, 4, 2),
			partition("localhost:19002/A2", 4, 0, 8, 2)));
		DurableAnchorKey b = new DurableAnchorKey("source-B", FType.ROW, List.of(
			partition("localhost:19001/B1", 0, 0, 4, 2),
			partition("localhost:19002/B2", 4, 0, 8, 2)));
		CompiledHopKey otherOwner = new CompiledHopKey("program", "main", "call", "rc",
			owner().controlRegion(), "other", "other");
		DurableAnchorKey first = output(a, FType.BROADCAST, 1, 2);
		DurableAnchorKey second = PlacementCostSemantics.materializedOutputAnchor(
			b, FType.BROADCAST, shape(1, 2), otherOwner);
		assertEquals("target layout must not inherit source file or owner provenance", first, second);
		assertRanges(first, new long[][][] {{{0, 0}, {1, 2}}, {{0, 0}, {1, 2}}});
	}

	@Test
	public void physicalUploadIdentityPreservesPartitionBoundariesAndWorkerAssignment() {
		DurableAnchorKey splitThree = anchor(FType.ROW,
			partition("localhost:19001", 0, 0, 3, 2),
			partition("localhost:19002", 3, 0, 8, 2));
		DurableAnchorKey splitFour = anchor(FType.ROW,
			partition("localhost:19001", 0, 0, 4, 2),
			partition("localhost:19002", 4, 0, 8, 2));
		DurableAnchorKey reversed = anchor(FType.ROW,
			partition("localhost:19002", 0, 0, 3, 2),
			partition("localhost:19001", 3, 0, 8, 2));
		assertNotEquals(output(splitThree, FType.ROW, 8, 2), output(splitFour, FType.ROW, 8, 2));
		assertNotEquals(output(splitThree, FType.ROW, 8, 2), output(reversed, FType.ROW, 8, 2));
		assertNotEquals(output(splitFour, FType.ROW, 8, 2), output(splitFour, FType.COL, 8, 2));
	}

	@Test
	public void runtimeAnchorKeyRoundTripsCompleteUnevenRowAndColRanges() {
		assertRuntimeRanges(anchor(FType.ROW,
			partition("localhost:19001", 0, 0, 3, 5),
			partition("localhost:19002", 3, 0, 10, 5)));
		assertRuntimeRanges(anchor(FType.COL,
			partition("localhost:19001", 0, 0, 10, 2),
			partition("localhost:19002", 0, 2, 10, 5)));
	}

	@Test
	public void materializedLayoutMatchesRuntimeRangeConstruction() {
		DurableAnchorKey unevenRow = anchor(FType.ROW,
			partition("localhost:19001", 0, 0, 3, 5),
			partition("localhost:19002", 3, 0, 10, 5));

		assertRanges(output(unevenRow, FType.ROW, 10, 5),
			new long[][][] {{{0, 0}, {3, 5}}, {{3, 0}, {10, 5}}});
		assertRanges(output(unevenRow, FType.ROW, 12, 5),
			new long[][][] {{{0, 0}, {6, 5}}, {{6, 0}, {12, 5}}});
		assertRanges(output(unevenRow, FType.COL, 10, 5),
			new long[][][] {{{0, 0}, {10, 3}}, {{0, 3}, {10, 5}}});
		assertRanges(output(unevenRow, FType.BROADCAST, 4, 2),
			new long[][][] {{{0, 0}, {4, 2}}, {{0, 0}, {4, 2}}});
		assertRanges(output(anchor(FType.FULL,
			partition("localhost:19001", 0, 0, 20, 8)), FType.FULL, 4, 2),
			new long[][][] {{{0, 0}, {4, 2}}});
	}

	@Test
	public void impossibleMaterializedLayoutsHaveNoAuthority() {
		DurableAnchorKey twoWorkers = anchor(FType.ROW,
			partition("localhost:19001", 0, 0, 3, 5),
			partition("localhost:19002", 3, 0, 10, 5));
		assertNull(PlacementCostSemantics.materializedOutputAnchor(null, FType.ROW,
			shape(10, 5), owner()));
		assertNull(output(twoWorkers, FType.ROW, 1, 5));
		assertNull(output(twoWorkers, FType.COL, 5, 1));
		assertNull(output(twoWorkers, FType.FULL, 5, 5));
		assertNull(output(twoWorkers, FType.ROW, 5, (long) Integer.MAX_VALUE + 1));
		assertNull(PlacementCostSemantics.materializedOutputAnchor(twoWorkers, FType.ROW,
			new NodeShapeFact(DataType.MATRIX, -1, 5), owner()));
	}

	@Test
	public void relocationDiscoveryDistinguishesUnknownFromKnownImpossibleLayouts() throws Exception {
		DurableAnchorKey twoWorkers = anchor(FType.ROW,
			partition("localhost:19001", 0, 0, 3, 5),
			partition("localhost:19002", 3, 0, 10, 5));
		CompiledHopKey producer = owner();
		CompiledHopKey consumer = new CompiledHopKey("program", "main", "call", "rc",
			producer.controlRegion(), "consumer", "consumer");
		CompiledInputEdgeFact edge = new CompiledInputEdgeFact(producer, consumer, 0);
		Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		Map<Hop,NodeShapeFact> shapes = new IdentityHashMap<>();
		origins.put(producer, null);

		shapes.put(null, shape(-1, 5));
		assertSame("unknown output geometry must retain symbolic relocation evidence", twoWorkers,
			relocationTargetLayout(twoWorkers, FType.ROW, edge, origins, shapes));

		shapes.put(null, shape(1, 5));
		assertNull("one known row cannot be materialized across two ROW workers",
			relocationTargetLayout(twoWorkers, FType.ROW, edge, origins, shapes));
		assertNull("FULL cannot be materialized across a multi-worker target",
			relocationTargetLayout(twoWorkers, FType.FULL, edge, origins, shapes));

		DurableAnchorKey malformed = anchor(FType.ROW,
			partition("localhost:19001", 0, 0, 3, 5),
			partition("localhost:19002", 4, 0, 10, 5));
		shapes.put(null, shape(10, 5));
		assertNull("known target-sized gaps cannot become graph relocation authority",
			relocationTargetLayout(malformed, FType.ROW, edge, origins, shapes));
	}

	@Test
	public void materializationPreservesWorkerRangePairsWhenWorkerNamesSortBackwards() {
		DurableAnchorKey reversed = anchor(FType.ROW,
			partition("localhost:19002", 0, 0, 3, 5),
			partition("localhost:19001", 3, 0, 10, 5));
		assertRuntimeRanges(reversed);
		FederationMap runtime = FederationUtils.buildAnchorMapFromKey(
			ExactPlacementRegistration.runtimeAnchorKey(reversed));
		assertEquals(19002, runtime.getMap().get(0).getValue().getAddress().getPort());
		// Durable identity remains worker-sorted, while upload iteration is range-sorted.
		assertRanges(output(reversed, FType.ROW, 10, 5),
			new long[][][] {{{3, 0}, {10, 5}}, {{0, 0}, {3, 5}}});
		assertRanges(output(reversed, FType.ROW, 12, 5),
			new long[][][] {{{6, 0}, {12, 5}}, {{0, 0}, {6, 5}}});
		assertRanges(output(reversed, FType.COL, 10, 5),
			new long[][][] {{{0, 3}, {10, 5}}, {{0, 0}, {10, 3}}});
	}

	@Test
	public void targetSizedMalformedRangesCannotBeSilentlyRebalanced() {
		DurableAnchorKey gap = anchor(FType.ROW,
			partition("localhost:19001", 0, 0, 3, 5),
			partition("localhost:19002", 4, 0, 10, 5));
		assertNull(output(gap, FType.ROW, 10, 5));
		assertNotNull(output(gap, FType.ROW, 12, 5));
		assertNotNull(output(gap, FType.COL, 10, 5));
	}

	@Test
	public void legacyTwoDimensionalPartitionTokensRemainReadable() {
		FederationMap row = FederationUtils.buildAnchorMapFromKey(
			"localhost:19001;localhost:19002;|0,3;3,10;|ROW");
		FederationMap col = FederationUtils.buildAnchorMapFromKey(
			"localhost:19001;localhost:19002;|0,2;2,5;|COL");
		assertArrayEquals(new long[] {3, 0}, row.getFederatedRanges()[1].getBeginDims());
		assertArrayEquals(new long[] {0, 5}, col.getFederatedRanges()[1].getEndDims());
	}

	private static DurableAnchorKey output(DurableAnchorKey seed, FType type, long rows, long cols) {
		return PlacementCostSemantics.materializedOutputAnchor(seed, type, shape(rows, cols), owner());
	}

	private static DurableAnchorKey relocationTargetLayout(DurableAnchorKey seed, FType type,
		CompiledInputEdgeFact input, Map<CompiledHopKey,Hop> origins,
		Map<Hop,NodeShapeFact> shapes) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("relocationTargetLayout",
			DurableAnchorKey.class, FType.class, CompiledInputEdgeFact.class, Map.class, Map.class);
		method.setAccessible(true);
		return (DurableAnchorKey) method.invoke(null, seed, type, input, origins, shapes);
	}

	private static NodeShapeFact shape(long rows, long cols) {
		return new NodeShapeFact(DataType.MATRIX, rows, cols);
	}

	private static void assertRuntimeRanges(DurableAnchorKey anchor) {
		FederationMap map = FederationUtils.buildAnchorMapFromKey(
			ExactPlacementRegistration.runtimeAnchorKey(anchor));
		assertEquals(anchor.fType(), map.getType());
		FederatedRange[] ranges = map.getFederatedRanges();
		assertEquals(anchor.partitions().size(), ranges.length);
		for(var entry : map.getMap()) {
			String worker = FederationUtils.canonicalFederatedWorkerAddress(
				entry.getValue().getAddress().getHostString() + ":" + entry.getValue().getAddress().getPort());
			AnchorPartition expected = anchor.partitions().stream().filter(partition -> worker.equals(
				FederationUtils.canonicalFederatedWorkerAddress(partition.workerId()))).findFirst().orElseThrow();
			assertArrayEquals(longs(expected.begin()), entry.getKey().getBeginDims());
			assertArrayEquals(longs(expected.end()), entry.getKey().getEndDims());
		}
	}

	private static void assertRanges(DurableAnchorKey anchor, long[][][] expected) {
		assertNotNull(anchor);
		assertEquals(expected.length, anchor.partitions().size());
		for(int i = 0; i < expected.length; i++) {
			assertArrayEquals(expected[i][0], longs(anchor.partitions().get(i).begin()));
			assertArrayEquals(expected[i][1], longs(anchor.partitions().get(i).end()));
		}
		assertRuntimeRanges(anchor);
	}

	private static long[] longs(List<Long> values) {
		return values.stream().mapToLong(Long::longValue).toArray();
	}

	private static DurableAnchorKey anchor(FType type, AnchorPartition... partitions) {
		return new DurableAnchorKey("seed-" + type, type, List.of(partitions));
	}

	private static AnchorPartition partition(String worker, long rb, long cb, long re, long ce) {
		return new AnchorPartition(worker, List.of(rb, cb), List.of(re, ce));
	}

	private static CompiledHopKey owner() {
		ControlRegionKey region = new ControlRegionKey("program", "main", List.of("root"), "call", "rc");
		return new CompiledHopKey("program", "main", "call", "rc", region, "owner", "owner");
	}
}
