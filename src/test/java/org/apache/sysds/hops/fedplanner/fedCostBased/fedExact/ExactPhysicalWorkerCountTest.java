/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalWorkerCountTest {
	@Test
	public void countsDifferentFilesOnSameWorkersOnce() {
		NeutralPlacementGraph graph = graph(
			anchor("X", "worker1:8001/data/features", "worker2:8002/data/features", "worker3:8003/data/features"),
			anchor("y", "worker1:8001/data/labels", "worker2:8002/data/labels", "worker3:8003/data/labels"));

		Assert.assertEquals(3, ExactPhysicalCostModel.workerCount(graph));
	}

	@Test
	public void preservesDistinctPortsOnSameHost() {
		NeutralPlacementGraph graph = graph(
			anchor("X", "worker1:8001/data/features"),
			anchor("y", "worker1:8002/data/labels"));

		Assert.assertEquals(2, ExactPhysicalCostModel.workerCount(graph));
	}

	private static NeutralPlacementGraph graph(DurableAnchorKey... anchors) {
		String fingerprint = "exact-worker-count";
		ControlRegionKey region = new ControlRegionKey(fingerprint, "main", List.of("sb-1"),
			"main", "compiled");
		CompiledHopKey key = new CompiledHopKey(fingerprint, "main", "main", "compiled", region,
			"anchor-owner@sb-1", "anchor-owner");
		ValueVersionKey value = new ValueVersionKey(fingerprint, "anchor", region, 0,
			VersionKind.ORDINARY, List.of());
		Node node = new Node(key, NodeKind.OPERATION, value, false, List.of(), List.of(), List.of(anchors));
		return new NeutralPlacementGraph(List.of(node), List.of(), List.of());
	}

	private static DurableAnchorKey anchor(String placementId, String... workers) {
		return new DurableAnchorKey(placementId, FType.ROW,
			java.util.Arrays.stream(workers)
				.map(worker -> new AnchorPartition(worker, List.of(0L, 0L), List.of(1L, 1L)))
				.toList());
	}
}
