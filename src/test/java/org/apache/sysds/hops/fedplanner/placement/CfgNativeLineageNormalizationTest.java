/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.junit.Assert;
import org.junit.Test;

public class CfgNativeLineageNormalizationTest {
	@Test
	public void compatibilityLayoutMemoMatchesColdLegacySerialization() throws Exception {
		PlacementIdentity.beginAnalysisScope(null);
		try {
			List<DurableAnchorKey> witnesses = List.of(
				anchor("canonical", FType.ROW, "worker1:8001", 0, 4),
				anchor("path", FType.ROW, "worker1:8001/X", 0, 4),
				anchor("rendered", FType.ROW, "worker1/10.0.0.1:8001", 0, 4),
				anchor("invalid", FType.ROW, "worker1:not-a-port", 0, 4),
				anchor("col", FType.COL, "worker1:8001", 0, 4),
				anchor("range", FType.ROW, "worker1:8001", 4, 9));
			for(DurableAnchorKey witness : witnesses) {
				String first = compatibilityLayout(witness);
				Assert.assertEquals("memoized layout must retain the legacy bytes for " + witness.placementId(),
					coldCompatibilityLayout(witness), first);
				Assert.assertSame("the immutable wrapper must reuse the cached String", first,
					compatibilityLayout(witness));
			}
			DurableAnchorKey renamed = anchor("renamed", FType.ROW, "worker1:8001", 0, 4);
			Assert.assertEquals("placement ids are outside compatibility layout identity",
				compatibilityLayout(witnesses.get(0)), compatibilityLayout(renamed));
			Assert.assertNotEquals(compatibilityLayout(witnesses.get(0)), compatibilityLayout(witnesses.get(4)));
			Assert.assertNotEquals(compatibilityLayout(witnesses.get(0)), compatibilityLayout(witnesses.get(5)));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}

	}

	@Test
	public void normalizedLayoutReusesCanonicalPartitionsAndStillRejectsDuplicates() throws Exception {
		AnchorPartition canonical = new AnchorPartition(
			"worker1:8001", List.of(0L, 0L), List.of(4L, 8L));
		DurableAnchorKey normalized = normalizedLayout("normalized",
			new DurableAnchorKey("source", FType.ROW, List.of(canonical)));
		Assert.assertSame("an unchanged endpoint must retain its immutable partition", canonical,
			normalized.partitions().get(0));

		DurableAnchorKey aliases = new DurableAnchorKey("aliases", FType.ROW, List.of(
			new AnchorPartition("worker1:8001/X", List.of(0L, 0L), List.of(4L, 8L)),
			new AnchorPartition("worker1:8001", List.of(0L, 0L), List.of(4L, 8L))));
		InvocationTargetException error = Assert.assertThrows(InvocationTargetException.class,
			() -> normalizedLayout("duplicates", aliases));
		Assert.assertTrue("normalization must retain DurableAnchorKey duplicate validation",
			error.getCause() instanceof IllegalArgumentException);
	}

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
		DurableAnchorKey pathAddresses = new DurableAnchorKey("fed-init:path", FType.ROW, List.of(
			new AnchorPartition("localhost:8001/X1", List.of(0L, 0L), List.of(4L, 8L)),
			new AnchorPartition("localhost:8002/X2", List.of(4L, 0L), List.of(8L, 8L))));
		DurableAnchorKey endpointAddresses = new DurableAnchorKey("native-proof:endpoint", FType.ROW, List.of(
			new AnchorPartition("localhost:8001", List.of(0L, 0L), List.of(4L, 8L)),
			new AnchorPartition("localhost:8002", List.of(4L, 0L), List.of(8L, 8L))));
		Assert.assertEquals("generated reader layouts normalize equivalent worker URL and endpoint witnesses",
			lineage(owner, pathAddresses, true), lineage(owner, endpointAddresses, true));
		Assert.assertEquals("dynamic layouts normalize equivalent worker URL and endpoint witnesses",
			lineage(owner, pathAddresses, false), lineage(owner, endpointAddresses, false));
		Assert.assertEquals("lineage normalization must retain the path-bearing source authority",
			"localhost:8001/X1", pathAddresses.partitions().get(0).workerId());
		Assert.assertEquals("lineage normalization must retain the endpoint source authority",
			"localhost:8001", endpointAddresses.partitions().get(0).workerId());

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

	@Test
	public void readerLineageUsesActualOutputWitnessInsteadOfCompatibilitySeed() throws Exception {
		CompiledHopKey owner = owner("reader-output");
		DurableAnchorKey seed = new DurableAnchorKey("seed", FType.ROW, List.of(
			new AnchorPartition("worker:8001", List.of(0L, 0L), List.of(4L, 8L)),
			new AnchorPartition("worker:8002", List.of(4L, 0L), List.of(8L, 8L))));
		DurableAnchorKey outputA = new DurableAnchorKey("output-a", FType.ROW, List.of(
			new AnchorPartition("worker:9001", List.of(0L, 0L), List.of(4L, 8L)),
			new AnchorPartition("worker:9002", List.of(4L, 0L), List.of(8L, 8L))));
		DurableAnchorKey outputB = new DurableAnchorKey("output-b", FType.ROW, List.of(
			new AnchorPartition("worker:7001", List.of(0L, 0L), List.of(4L, 8L)),
			new AnchorPartition("worker:7002", List.of(4L, 0L), List.of(8L, 8L))));

		Assert.assertNotEquals("one seed cannot collapse distinct native output pools",
			lineage(owner, seed, outputA, false), lineage(owner, seed, outputB, false));
		DurableAnchorKey renamedSeed = new DurableAnchorKey("other-seed", FType.ROW, List.of(
			new AnchorPartition("worker:6001", List.of(0L, 0L), List.of(4L, 8L)),
			new AnchorPartition("worker:6002", List.of(4L, 0L), List.of(8L, 8L))));
		Assert.assertEquals("different compatible seeds must share an identical output pool identity",
			lineage(owner, seed, outputA, false), lineage(owner, renamedSeed, outputA, false));
	}

	private static CompiledHopKey owner(String occurrence) {
		ControlRegionKey region = new ControlRegionKey("cfg-native-lineage", "main",
			List.of("main"), "main", "compiled");
		return new CompiledHopKey("cfg-native-lineage", "main", "main",
			"compiled", region, occurrence, occurrence);
	}

	private static DurableAnchorKey anchor(String id, FType type, String worker,
		long begin, long end) {
		return new DurableAnchorKey(id, type, List.of(new AnchorPartition(
			worker, List.of(begin, 0L), List.of(end, 8L))));
	}

	private static String compatibilityLayout(DurableAnchorKey witness) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"nativeCompatibilityLayout", DurableAnchorKey.class);
		method.setAccessible(true);
		return (String)method.invoke(null, witness);
	}

	private static String coldCompatibilityLayout(DurableAnchorKey witness) {
		List<AnchorPartition> partitions = witness.partitions().stream().map(partition -> {
			String endpoint = FederationUtils.canonicalFederatedWorkerAddress(partition.workerId());
			return new AnchorPartition(endpoint == null ? partition.workerId() : endpoint,
				partition.begin(), partition.end());
		}).toList();
		return new DurableAnchorKey("transient-native-layout", witness.fType(), partitions)
			.normalizedSignature();
	}

	private static DurableAnchorKey normalizedLayout(String id, DurableAnchorKey witness)
		throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"normalizedNativeLayout", String.class, DurableAnchorKey.class);
		method.setAccessible(true);
		return (DurableAnchorKey)method.invoke(null, id, witness);
	}

	private static String lineage(CompiledHopKey owner, DurableAnchorKey seed,
		boolean exactLayout) throws Exception {
		return lineage(owner, seed, seed, exactLayout);
	}

	private static String lineage(CompiledHopKey owner, DurableAnchorKey seed,
		DurableAnchorKey outputWitness, boolean exactLayout) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"cfgNativeDerivedLineage", CompiledHopKey.class, DurableAnchorKey.class,
			DurableAnchorKey.class, boolean.class);
		method.setAccessible(true);
		return (String)method.invoke(null, owner, seed, outputWitness, exactLayout);
	}
}
