/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
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

public class CfgNativeLineageNormalizationTest {
	@Test
	public void readerLineageUsesOwnerLayoutAndPrecisionButNotCarrierId() throws Exception {
		CompiledHopKey owner = owner("reader-a");
		List<AnchorPartition> rowLayout = List.of(
			new AnchorPartition("worker:8001", List.of(0L, 0L), List.of(4L, 8L)),
			new AnchorPartition("worker:8002", List.of(4L, 0L), List.of(8L, 8L)));
		DurableAnchorKey literal = new DurableAnchorKey("fed-init:X", FType.ROW, rowLayout);
		DurableAnchorKey carried = new DurableAnchorKey("native-output:loop-carrier", FType.ROW, rowLayout);

		String exact = lineage(owner, literal, true);
		Assert.assertEquals("same-layout carrier renames must not rename the CFG reader",
			exact, lineage(owner, carried, true));
		Assert.assertEquals("canonical partition order must name the same complete layout",
			exact, lineage(owner, new DurableAnchorKey("reordered", FType.ROW,
				List.of(rowLayout.get(1), rowLayout.get(0))), true));
		Assert.assertEquals("normalization must not replace the literal source identity",
			"fed-init:X", literal.placementId());
		Assert.assertEquals("normalization must not replace the carried source identity",
			"native-output:loop-carrier", carried.placementId());

		Assert.assertNotEquals("reader owner remains part of the derived identity",
			exact, lineage(owner("reader-b"), literal, true));
		Assert.assertNotEquals("FType remains part of the complete layout",
			exact, lineage(owner,
				new DurableAnchorKey("fed-init:X", FType.COL, rowLayout), true));
		Assert.assertNotEquals("partition ranges remain part of the complete layout",
			exact, lineage(owner, new DurableAnchorKey("fed-init:X", FType.ROW, List.of(
				new AnchorPartition("worker:8001", List.of(0L, 0L), List.of(8L, 8L)))), true));
		Assert.assertNotEquals("partition count remains part of the complete layout",
			exact, lineage(owner, new DurableAnchorKey("fed-init:X", FType.ROW,
				List.of(rowLayout.get(0))), true));
		Assert.assertNotEquals("partition multiplicity remains part of the complete layout",
			exact, lineage(owner, new DurableAnchorKey("fed-init:X", FType.ROW,
				List.of(
					new AnchorPartition("worker:8001", List.of(0L, 0L), List.of(2L, 8L)),
					new AnchorPartition("worker:8001", List.of(2L, 0L), List.of(4L, 8L)),
					rowLayout.get(1))), true));
		Assert.assertNotEquals("the non-partitioned axis range remains part of the complete layout",
			exact, lineage(owner, new DurableAnchorKey("fed-init:X", FType.ROW, List.of(
				new AnchorPartition("worker:8001", List.of(0L, 1L), List.of(4L, 9L)),
				new AnchorPartition("worker:8002", List.of(4L, 1L), List.of(8L, 9L)))), true));
		Assert.assertNotEquals("worker placement remains part of the complete layout",
			exact, lineage(owner, new DurableAnchorKey("fed-init:X", FType.ROW, List.of(
				new AnchorPartition("worker:9001", List.of(0L, 0L), List.of(4L, 8L)),
				new AnchorPartition("worker:8002", List.of(4L, 0L), List.of(8L, 8L)))), true));
		Assert.assertNotEquals("exact and dynamic reader layouts remain distinct",
			exact, lineage(owner, literal, false));
	}

	private static CompiledHopKey owner(String occurrence) {
		ControlRegionKey region = new ControlRegionKey("cfg-native-lineage", "main",
			List.of("main"), "main", "compiled");
		return new CompiledHopKey("cfg-native-lineage", "main", "main",
			"compiled", region, occurrence, occurrence);
	}

	private static String lineage(CompiledHopKey owner, DurableAnchorKey seed,
		boolean exactLayout) throws Exception {
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"cfgNativeDerivedLineage", CompiledHopKey.class, DurableAnchorKey.class, boolean.class);
		method.setAccessible(true);
		return (String)method.invoke(null, owner, seed, exactLayout);
	}
}
