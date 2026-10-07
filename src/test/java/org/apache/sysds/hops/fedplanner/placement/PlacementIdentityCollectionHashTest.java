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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.junit.Assert;
import org.junit.Test;

public class PlacementIdentityCollectionHashTest {
	private record RegionOracle(String programFingerprint, String functionNamespace,
		List<String> regionPath, String callSitePath, String recompileContext) { }
	private record VersionOracle(String programFingerprint, String lexicalVariable,
		ControlRegionKey definingControlRegion, int definitionOrdinal, VersionKind versionKind,
		List<String> predecessorVersions) { }
	private record PartitionOracle(String workerId, List<Long> begin, List<Long> end) { }
	private record AnchorOracle(String placementId, FType fType, List<AnchorPartition> partitions) { }

	@Test
	public void cachedListsPreserveGeneratedRecordHashesAndStructuralEquality() {
		Random random = new Random(51919);
		for(int trial = 0; trial < 400; trial++) {
			List<String> path = List.of("root", "region" + random.nextInt(100));
			ControlRegionKey region = new ControlRegionKey("program", "main", path, "call", "r");
			ControlRegionKey equalRegion = new ControlRegionKey("program", "main", new ArrayList<>(path), "call", "r");
			Assert.assertTrue(region.getClass().isRecord());
			Assert.assertEquals(new RegionOracle("program", "main", path, "call", "r").hashCode(), region.hashCode());
			Assert.assertEquals(region, equalRegion);
			List<String> predecessors = List.of("a" + trial, "b" + trial);
			int ordinal = random.nextInt(100);
			VersionKind kind = VersionKind.values()[random.nextInt(VersionKind.values().length)];
			ValueVersionKey value = new ValueVersionKey("program", "v", region, ordinal, kind, predecessors);
			ValueVersionKey equalValue = new ValueVersionKey("program", "v", equalRegion, ordinal, kind,
				List.of(predecessors.get(1), predecessors.get(0)));
			Assert.assertEquals(new VersionOracle("program", "v", region, ordinal, kind, predecessors).hashCode(), value.hashCode());
			Assert.assertEquals(value, equalValue);
			Assert.assertEquals(equalValue, value);
			Assert.assertNotEquals(value, new ValueVersionKey("program", "v", region, ordinal + 1, kind, predecessors));
			Assert.assertNotEquals(value, new ValueVersionKey("program", "other", region, ordinal, kind, predecessors));
			Assert.assertNotEquals(value, new ValueVersionKey("program", "v", region, ordinal, kind, List.of("changed")));
			List<Long> begin = List.of(0L, (long)trial);
			List<Long> end = List.of(100L, (long)trial + 1);
			AnchorPartition partition = new AnchorPartition("worker", begin, end);
			Assert.assertEquals(new PartitionOracle("worker", begin, end).hashCode(), partition.hashCode());
			DurableAnchorKey anchor = new DurableAnchorKey("anchor", FType.ROW, List.of(partition));
			Assert.assertEquals(new AnchorOracle("anchor", FType.ROW, List.of(partition)).hashCode(), anchor.hashCode());
			Assert.assertEquals(anchor, new DurableAnchorKey("anchor", FType.ROW,
				List.of(new AnchorPartition("worker", new ArrayList<>(begin), new ArrayList<>(end)))));
		}
	}

	@Test
	public void validatedSortedListsReuseOnlyTheirOwnImmutableSnapshot() {
		AnchorPartition partition = new AnchorPartition("worker", List.of(0L, 0L), List.of(4L, 2L));
		DurableAnchorKey first = new DurableAnchorKey("first", FType.ROW, List.of(partition));
		DurableAnchorKey second = new DurableAnchorKey("second", FType.ROW, first.partitions());
		Assert.assertSame("validated immutable ordering does not need another copy and sort",
			first.partitions(), second.partitions());
		Assert.assertSame(partition, second.partitions().get(0));
		Assert.assertThrows(IllegalArgumentException.class, () ->
			new DurableAnchorKey("duplicate", FType.ROW, List.of(partition, partition)));
		ControlRegionKey aa = new ControlRegionKey("program", "main", List.of("Aa"), "call", "r");
		ControlRegionKey bb = new ControlRegionKey("program", "main", List.of("BB"), "call", "r");
		Assert.assertEquals(aa.regionPath().hashCode(), bb.regionPath().hashCode());
		Assert.assertNotEquals(aa.regionPath(), bb.regionPath());
		Assert.assertEquals(aa.regionPath(), new ArrayList<>(List.of("Aa")));
		Assert.assertEquals(new ArrayList<>(List.of("Aa")), aa.regionPath());
		Assert.assertEquals(List.of("Aa"), aa.regionPath());
		ControlRegionKey unsorted = new ControlRegionKey("program", "main", List.of("z", "a"), "call", "r");
		ValueVersionKey sorted = new ValueVersionKey("program", "v", unsorted, 0,
			VersionKind.values()[0], unsorted.regionPath());
		Assert.assertEquals(List.of("a", "z"), sorted.predecessorVersions());
		Assert.assertNotSame(unsorted.regionPath(), sorted.predecessorVersions());
		ControlRegionKey duplicates = new ControlRegionKey("program", "main", List.of("a", "a"), "call", "r");
		Assert.assertThrows(IllegalArgumentException.class, () -> new ValueVersionKey("program", "v",
			duplicates, 0, VersionKind.values()[0], duplicates.regionPath()));
	}

	@Test
	public void collectionSnapshotsStayImmutableAcrossAllMutationSurfaces() {
		List<String> input = new ArrayList<>(List.of("root", "branch"));
		ControlRegionKey region = new ControlRegionKey("program", "main", input, "call", "r");
		int hash = region.hashCode();
		String signature = region.normalizedSignature();
		input.set(0, "mutated");
		List<String> path = region.regionPath();
		Assert.assertEquals(List.of("root", "branch"), path);
		Assert.assertEquals(hash, region.hashCode());
		Assert.assertEquals(signature, region.normalizedSignature());
		Assert.assertThrows(UnsupportedOperationException.class, () -> path.set(0, "x"));
		Assert.assertThrows(UnsupportedOperationException.class, () -> path.addAll(List.of()));
		Assert.assertThrows(UnsupportedOperationException.class, () -> path.addAll(-1, List.of()));
		Assert.assertThrows(UnsupportedOperationException.class, () -> path.remove("absent"));
		Assert.assertThrows(UnsupportedOperationException.class, () -> path.removeAll(List.of()));
		Assert.assertThrows(UnsupportedOperationException.class, () -> path.retainAll(path));
		Assert.assertThrows(UnsupportedOperationException.class, path::clear);
		Assert.assertThrows(UnsupportedOperationException.class, () -> path.removeIf(x -> false));
		Assert.assertThrows(UnsupportedOperationException.class, () -> path.replaceAll(x -> x));
		Assert.assertThrows(UnsupportedOperationException.class, () -> path.sort(null));
		Assert.assertThrows(UnsupportedOperationException.class, () -> path.subList(0, 0).clear());
		var iterator = path.listIterator();
		iterator.next();
		Assert.assertThrows(UnsupportedOperationException.class, iterator::remove);
		Assert.assertThrows(NullPointerException.class, () -> path.contains(null));
		Assert.assertThrows(NullPointerException.class, () -> path.subList(0, 1).contains(null));
		Assert.assertArrayEquals(new Object[] {"root", "branch"}, path.toArray());
		Assert.assertEquals(List.of("root", "branch"), path.stream().toList());
	}
}
