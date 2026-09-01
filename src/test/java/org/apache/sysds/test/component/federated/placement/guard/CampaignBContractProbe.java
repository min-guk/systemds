/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementGraphFingerprint;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;

/** Minimal immutable snapshot and frozen-authority utilities for the Cost4 RED. */
final class CampaignBContractProbe {
	private static final String RESOURCE_ROOT="/org/apache/sysds/test/component/federated/placement/characterization/";
	record Fixture(String id,DMLProgram program,PlacementAnalysis analysis) { }
	record Snapshot(PlacementAnalysis analysis,NeutralPlacementGraph graph,String fingerprint,String graphSignature,
		String programSignature,List<String> projections,List<Hop> hops,List<String> candidates,List<String> identities,
		List<String> provenance,List<String> anchors,List<String> constraints,List<String> exclusions,
		List<String> relocations,List<String> obligations) { }

	static Snapshot snapshot(Fixture fixture) {
		List<String> projections=new ArrayList<>();List<Hop> hops=new ArrayList<>();
		for(var occurrence:fixture.analysis().occurrences()){
			projections.add(occurrence.key().normalizedSignature()+'|'+occurrence.normalizedOrdinal()+'|'+occurrence.normalizedSignature());
			hops.add(occurrence.hop());
		}
		return new Snapshot(fixture.analysis(),fixture.analysis().graph(),fixture.analysis().analysisFingerprint(),
			fixture.analysis().graph().normalizedSignature(),PlacementGraphFingerprint.capture(fixture.program()),
			List.copyOf(projections),List.copyOf(hops),fixture.analysis().graph().normalizedCandidateUniverse(),
			fixture.analysis().graph().normalizedIdentities(),fixture.analysis().graph().normalizedProvenance(),fixture.analysis().graph().normalizedAnchors(),
			fixture.analysis().graph().normalizedConstraints(),fixture.analysis().graph().normalizedExclusions(),
			fixture.analysis().graph().normalizedRelocationActions(),fixture.analysis().graph().normalizedObligations());
	}
	static void assertUnchanged(Snapshot before,Snapshot after) {
		Assert.assertSame(before.analysis(),after.analysis());Assert.assertSame(before.graph(),after.graph());
		Assert.assertEquals(before.fingerprint(),after.fingerprint());Assert.assertEquals(before.graphSignature(),after.graphSignature());
		Assert.assertEquals(before.programSignature(),after.programSignature());Assert.assertEquals(before.projections(),after.projections());
		Assert.assertEquals(before.candidates(),after.candidates());Assert.assertEquals(before.identities(),after.identities());
		Assert.assertEquals(before.provenance(),after.provenance());
		Assert.assertEquals(before.anchors(),after.anchors());Assert.assertEquals(before.constraints(),after.constraints());
		Assert.assertEquals(before.exclusions(),after.exclusions());Assert.assertEquals(before.relocations(),after.relocations());
		Assert.assertEquals(before.obligations(),after.obligations());Assert.assertEquals(before.hops().size(),after.hops().size());
		for(int i=0;i<before.hops().size();i++)Assert.assertSame(before.hops().get(i),after.hops().get(i));
	}
	static String resource(String name)throws Exception {
		try(InputStream in=CampaignBContractProbe.class.getResourceAsStream(RESOURCE_ROOT+name)){
			if(in==null)throw new IllegalStateException("Missing committed authority "+name);
			return new String(in.readAllBytes(),StandardCharsets.UTF_8);
		}
	}
	static String sha256(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
	private CampaignBContractProbe() { }
}
