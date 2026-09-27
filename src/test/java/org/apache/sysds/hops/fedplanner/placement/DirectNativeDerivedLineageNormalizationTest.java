/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.List;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

public class DirectNativeDerivedLineageNormalizationTest {
	@Test
	public void derivedRootUsesOwnerAndCompleteLayoutButNotSeedPlacementId() throws Exception {
		CompiledHopKey owner = owner("owner-a");
		List<AnchorPartition> rowPartitions = List.of(
			new AnchorPartition("worker:8001", List.of(0L, 0L), List.of(4L, 8L)),
			new AnchorPartition("worker:8002", List.of(4L, 0L), List.of(8L, 8L)));
		DurableAnchorKey literalSeed = new DurableAnchorKey("fed-init:X", FType.ROW, rowPartitions);
		DurableAnchorKey derivedSeed = new DurableAnchorKey("native-output:producer", FType.ROW, rowPartitions);

		String literalRoot = lineage(owner, literalSeed);
		String derivedRoot = lineage(owner, derivedSeed);

		Assert.assertEquals("equal complete layouts share one derived output root",
			literalRoot, derivedRoot);
		Assert.assertEquals("normalization must not replace the literal seed identity",
			"fed-init:X", literalSeed.placementId());
		Assert.assertEquals("normalization must not replace the derived seed identity",
			"native-output:producer", derivedSeed.placementId());
		Assert.assertNotEquals("the owner remains part of the derived root",
			literalRoot, lineage(owner("owner-b"), literalSeed));
		Assert.assertNotEquals("the complete partition geometry remains part of the derived root",
			literalRoot, lineage(owner, new DurableAnchorKey("fed-init:X", FType.ROW,
				List.of(new AnchorPartition("worker:8001", List.of(0L, 0L), List.of(8L, 8L))))));
		Assert.assertNotEquals("the exact FType remains part of the derived root",
			literalRoot, lineage(owner, new DurableAnchorKey("fed-init:X", FType.COL, rowPartitions)));
	}

	private static CompiledHopKey owner(String occurrence) {
		ControlRegionKey region = new ControlRegionKey("direct-native-lineage", "main",
			List.of("main"), "main", "compiled");
		return new CompiledHopKey("direct-native-lineage", "main", "main",
			"compiled", region, occurrence, occurrence);
	}

	private static String lineage(CompiledHopKey owner, DurableAnchorKey seed) throws Exception {
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"directNativeDerivedLineage", CompiledHopKey.class, DurableAnchorKey.class);
		method.setAccessible(true);
		return (String)method.invoke(null, owner, seed);
	}
}
