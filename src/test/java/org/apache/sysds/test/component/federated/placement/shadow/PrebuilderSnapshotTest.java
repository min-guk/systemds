/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.util.HashMap;
import java.util.Map;

import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

public class PrebuilderSnapshotTest {
	@Test public void nestedControlAndFunctionAreReconciledWithRawAst() throws Exception {
		for(String id : new String[] {"B-04", "B-07", "B-09", "B-18"}) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(id);
			PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, Map.of());
			PrebuilderSnapshotVerifier.assertMatches(program, snapshot);
			Assert.assertFalse(id, snapshot.blocks().isEmpty());
			Assert.assertFalse(id, snapshot.nodes().isEmpty());
			if(id.equals("B-07")) {
				Assert.assertFalse(snapshot.functions().isEmpty());
				Assert.assertTrue("Function call must survive as a HOP or inlining boundary",
					!snapshot.calls().isEmpty() || !snapshot.inlinedCalls().isEmpty());
			}
			if(id.equals("B-09"))
				Assert.assertTrue(snapshot.nodes().values().stream().anyMatch(PrebuilderSnapshot.Node::requiresRecompile));
			if(id.equals("B-18"))
				Assert.assertTrue(snapshot.blocks().stream().anyMatch(b -> b.path().contains("/while/")
					&& b.kind().equals("IfStatementBlock")));
		}
	}

	@Test public void externalMetadataIsExplicitAndMissingSourceFailsClosed() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-11");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		try {
			PrebuilderSnapshot.capture(program, Map.of());
			Assert.fail("A federated source without external metadata must fail");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("Missing explicit federated source"));
		}
		Map<Long, PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
		for(Hop root : program.getStatementBlocks().stream().flatMap(b -> b.getHops() == null
			? java.util.stream.Stream.<Hop>empty() : b.getHops().stream()).toList())
			registerFedSources(root, sources);
		Assert.assertFalse(sources.isEmpty());
		PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, sources);
		PrebuilderSnapshotVerifier.assertMatches(program, snapshot);
		for(long id : sources.keySet())
			Assert.assertEquals("PRIVATE_AGGREGATE", snapshot.nodes().get(id).externalSource().privacy());
	}

	@Test public void rawGraphMutationInvalidatesSnapshot() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-01");
		PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, Map.of());
		PrebuilderSnapshotVerifier.assertMatches(program, snapshot);
		program.getStatementBlocks().remove(program.getStatementBlocks().size() - 1);
		try {
			PrebuilderSnapshotVerifier.assertMatches(program, snapshot);
			Assert.fail("Changed raw AST must invalidate the snapshot");
		}
		catch(AssertionError expected) {
			Assert.assertTrue(expected.getMessage().contains("block paths"));
		}
	}

	private static void registerFedSources(Hop hop, Map<Long, PrebuilderSnapshot.ExternalSource> sources) {
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			sources.put(hop.getHopID(), new PrebuilderSnapshot.ExternalSource(
				"frozen-fixture:B-11", "PRIVATE_AGGREGATE", "ROW"));
		for(Hop child : hop.getInput()) registerFedSources(child, sources);
	}
}
