/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalComparisonRow;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.junit.Assert;
import org.junit.Test;

/** Independent current P/E builders must produce identical source-coordinate physical sets. */
public class CurrentPePhysicalSetCorrespondenceTest {
	private static final ObjectMapper JSON = new ObjectMapper()
		.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

	@Test public void allViableProtectedFixturesHaveEqualPhysicalSets() throws Exception {
		for(String fixture : ProductionShadowFixtureFactory.ids()) {
			if(fixture.equals("B-13")) continue;
			try { compare(fixture); }
			catch(Exception error) { throw new IllegalStateException("Physical set mismatch at " + fixture, error); }
		}
	}

	@Test public void b13ProtectedSourceHasNoModelOnEitherSide() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-13");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		DMLRuntimeException pError = Assert.assertThrows(DMLRuntimeException.class,
			() -> new NeutralPlacementGraphBuilder().buildAnalysis(program));
		Assert.assertTrue(pError.getMessage().contains("No privacy-safe physical placement"));
		DMLRuntimeException eError = Assert.assertThrows(DMLRuntimeException.class,
			() -> ExactPhysicalComparisonRow.streamFixture("B-13", ignored -> { }));
		Assert.assertTrue(eError.getMessage().contains("No privacy-safe physical placement"));
	}

	private static void compare(String fixture) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				for(Hop root : block.getHops()) register(root, fixture, sources);
		PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, sources);
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var catalog = PlanSpaceComparisonIdentity.from(analysis, snapshot);
		var projector = new CurrentPPhysicalPlanRows(analysis, catalog, fixture);
		var relation = new ClosedPlanRelationEnumerator(analysis);
		Set<String> p = new HashSet<>();
		Map<String,Integer> multiplicity = new HashMap<>();
		Map<String,List<FullProductionJointPlanExport.Audit>> proofsByPhysical = new HashMap<>();
		var summary = relation.enumerateStates(BigInteger.ZERO, relation.stateCount(), proof -> {
			String key = canonical(projector.physicalPlan(proof));
			p.add(key);
			multiplicity.merge(key, 1, Integer::sum);
			if(fixture.equals("B-21"))
				proofsByPhysical.computeIfAbsent(key, ignored -> new ArrayList<>()).add(proof);
		});
		Assert.assertEquals(fixture + " P UNKNOWN", BigInteger.ZERO, summary.unknown());
		Set<String> e = new HashSet<>();
		Map<String,Integer> eMultiplicity = new HashMap<>();
		long eAccepted = ExactPhysicalComparisonRow.streamFixture(fixture, row -> {
			String key = canonical(row);
			e.add(key);
			eMultiplicity.merge(key, 1, Integer::sum);
		});
		Set<String> pOnly = new HashSet<>(p);
		pOnly.removeAll(e);
		Set<String> eOnly = new HashSet<>(e);
		eOnly.removeAll(p);
		Assert.assertTrue(fixture + " P-only physical plans=" + pOnly.size(), pOnly.isEmpty());
		Assert.assertTrue(fixture + " E-only physical plans=" + eOnly.size(), eOnly.isEmpty());
		Assert.assertEquals(fixture + " accepted proof count", summary.accepted().longValueExact(), eAccepted);
		Assert.assertEquals(fixture + " projected proof multiplicity", multiplicity, eMultiplicity);
		if(fixture.equals("B-01")) {
			Assert.assertEquals(1L, eAccepted);
			Assert.assertEquals(1, p.size());
		}
		if(fixture.equals("B-21")) {
			Assert.assertEquals(192L, eAccepted);
			Assert.assertEquals(36, p.size());
			Assert.assertTrue("B-21 must audit raw-proof multiplicity",
				multiplicity.values().stream().anyMatch(count -> count > 1));
			assertKnownNoEmissionProofCollapse(analysis, proofsByPhysical);
		}
	}

	private static void assertKnownNoEmissionProofCollapse(
		org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis analysis,
		Map<String,List<FullProductionJointPlanExport.Audit>> groups) {
		boolean found = false;
		for(List<FullProductionJointPlanExport.Audit> proofs : groups.values())
			for(var left : proofs)
				for(var right : proofs) {
					if(left.relocations().size() != 0 || right.relocations().size() != 1)
						continue;
					if(left.candidates().size() != right.candidates().size()) continue;
					boolean directSupportDifference = false;
					for(int i = 0; i < left.candidates().size(); i++) {
						var l = left.candidates().get(i).supportClause().inputBindings();
						var r = right.candidates().get(i).supportClause().inputBindings();
						if(l.size() == 1 && r.isEmpty()
							&& l.get(0).kind().name().equals("DIRECT")) directSupportDifference = true;
					}
					if(!directSupportDifference) continue;
					Assert.assertTrue("Direct FOUT proof has no physical action",
						RelocationSelections.emittedActions(analysis, analysis.graph().relocationActions(),
							left.assignment(), left.candidates(), left.relocations()).isEmpty());
					Assert.assertTrue("No-emission relocation proof has no physical action",
						RelocationSelections.emittedActions(analysis, analysis.graph().relocationActions(),
							right.assignment(), right.candidates(), right.relocations()).isEmpty());
					found = true;
				}
		Assert.assertTrue("Expected B-21 direct-FOUT/no-op relocation proof pair", found);
	}

	private static String canonical(Map<String,Object> row) {
		try {
			Map<String,Object> normalized = new HashMap<>(row);
			for(String name : List.of("nodes", "actions", "bindings", "geometry", "authority",
				"logicalInputs")) {
				@SuppressWarnings("unchecked")
				List<Map<String,Object>> entries = (List<Map<String,Object>>) row.get(name);
				List<String> encoded = new ArrayList<>();
				for(Map<String,Object> entry : entries) encoded.add(JSON.writeValueAsString(entry));
				encoded.sort(String::compareTo);
				normalized.put(name, encoded);
			}
			return JSON.writeValueAsString(normalized);
		}
		catch(Exception error) { throw new IllegalStateException("Cannot canonicalize physical row", error); }
	}

	private static void register(Hop hop, String fixture,
		Map<Long,PrebuilderSnapshot.ExternalSource> sources) {
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			sources.put(hop.getHopID(), new PrebuilderSnapshot.ExternalSource(
				"fixture:" + fixture, "PRIVATE_AGGREGATE", "ROW"));
		for(Hop child : hop.getInput()) register(child, fixture, sources);
	}
}
