/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.AbstractShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.junit.Assert;
import org.junit.Test;

public class CandidateRouteMetricsTest {
	private static final PlacementCandidateGenerator.GenerationPrivacy FED_REQUIRED =
		new PlacementCandidateGenerator.GenerationPrivacy(Privacy.PRIVATE, Set.of(0));

	@Test
	public void countersIdentifyTheActualCandidateGenerationRoutes() {
		QuaternaryOp relationHop = new QuaternaryOp("wsigmoid-route", DataType.MATRIX,
			ValueType.FP64, OpOp4.WSIGMOID, matrix("XR"), matrix("UR"), matrix("VR"),
			false, false);
		assertRouteWithEquivalentFacts(relationHop, weightedDomains(relationHop), FED_REQUIRED,
			SearchSpaceMetrics.CandidateRoute.EXECUTION_RELATION, false);

		QuaternaryOp familyHop = new QuaternaryOp("wsloss-route", DataType.SCALAR,
			ValueType.FP64, OpOp4.WSLOSS, matrix("XF"), matrix("UF"), matrix("VF"),
			matrix("WF"), false);
		BuildResult family = assertRouteWithEquivalentFacts(familyHop, weightedDomains(familyHop),
			new PlacementCandidateGenerator.GenerationPrivacy(Privacy.PUBLIC, Set.of()),
			SearchSpaceMetrics.CandidateRoute.EXECUTION_RELATION, false);
		Assert.assertFalse("fixture must publish at least one compact CP family",
			family.familySignatures().isEmpty());
		Assert.assertEquals("CP_FAMILY counts each successfully published family header",
			family.familySignatures().size(), routeCalls(family.metrics(), familyHop,
				SearchSpaceMetrics.CandidateRoute.CP_FAMILY));

		DataOp frame = new DataOp("F", DataType.FRAME, ValueType.STRING,
			OpOpData.TRANSIENTREAD, "F", 8, 4, 32, 1000);
		IndexingOp rightIndex = new IndexingOp("slice-route", DataType.FRAME, ValueType.STRING,
			frame, new LiteralOp(1L), new LiteralOp(4L), new LiteralOp(1L),
			new LiteralOp(2L), false, false);
		assertRouteWithEquivalentFacts(rightIndex, List.of(
			Arrays.asList(null, FType.ROW, FType.FULL, FType.BROADCAST),
			Arrays.asList((FType) null), Arrays.asList((FType) null),
			Arrays.asList((FType) null), Arrays.asList((FType) null)), FED_REQUIRED,
			SearchSpaceMetrics.CandidateRoute.MRV, true);

		FunctionOp function = new FunctionOp(FunctionType.DML, "main", "routeFunction",
			new String[] {"X", "Y"}, List.of(matrix("XC"), matrix("YC")),
			new String[] {"Z"}, true);
		BuildResult residual = assertRouteWithEquivalentFacts(function, List.of(
			Arrays.asList(null, FType.ROW, FType.FULL),
			Arrays.asList(null, FType.COL)), FED_REQUIRED,
			SearchSpaceMetrics.CandidateRoute.CARTESIAN, true);
		assertExactResidualConstructionMetrics(residual);
		assertImmutableCandidateEvidenceIsShared(residual.ruleFacts());
	}

	private static BuildResult assertRouteWithEquivalentFacts(Hop hop,
		List<List<FType>> domains, PlacementCandidateGenerator.GenerationPrivacy privacy,
		SearchSpaceMetrics.CandidateRoute expectedRoute, boolean exactResidual) {
		List<List<FType>> originalDomains = new ArrayList<>();
		for(List<FType> domain : domains)
			originalDomains.add(new ArrayList<>(domain));
		BuildResult disabled = build(hop, domains, privacy, null);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		BuildResult enabled = build(hop, domains, privacy, metrics);

		Assert.assertEquals("diagnostics must not mutate candidate input domains",
			originalDomains, domains);
		Assert.assertEquals(disabled.node(), enabled.node());
		Assert.assertEquals(disabled.ruleKeys(), enabled.ruleKeys());
		Assert.assertEquals(disabled.ruleFacts(), enabled.ruleFacts());
		Assert.assertEquals(disabled.familySignatures(), enabled.familySignatures());
		Assert.assertEquals("selected fallback/relation routes count once per build",
			1, routeCalls(metrics, hop, expectedRoute));
		for(SearchSpaceMetrics.CandidateRoute route : List.of(
			SearchSpaceMetrics.CandidateRoute.EXECUTION_RELATION,
			SearchSpaceMetrics.CandidateRoute.MRV,
			SearchSpaceMetrics.CandidateRoute.CARTESIAN))
			if(route != expectedRoute)
				Assert.assertEquals("unselected route must remain absent: " + route,
					0, routeCalls(metrics, hop, route));
		Assert.assertEquals("exact residual route must match relation availability", exactResidual ? 1 : 0,
			routeCalls(metrics, hop, SearchSpaceMetrics.CandidateRoute.EXACT_RULE_RESIDUAL));
		Assert.assertEquals("small fixtures do not overflow the bounded per-op breakdown",
			metrics.candidateRouteTotalsSnapshot().values().stream().mapToLong(Long::longValue).sum(),
			metrics.candidateRouteSnapshot().stream()
				.mapToLong(SearchSpaceMetrics.CandidateRouteCount::calls).sum());
		return enabled;
	}

	private static void assertExactResidualConstructionMetrics(BuildResult result) {
		var construction = result.metrics().candidateConstructionSnapshot();
		Assert.assertTrue("residual route must evaluate exact oracle tuples", construction.exactRuleCalls() > 0);
		Assert.assertTrue("equal exact results must reuse immutable decision evidence",
			construction.evidenceReuses() > 0);
		Assert.assertEquals("small residual fixture must not overflow evidence retention",
			0, construction.evidenceOverflow());
		Assert.assertTrue("candidate facts must request immutable headers", construction.headerRequests() > 0);
		Assert.assertTrue("equivalent candidate facts must reuse immutable headers", construction.headerReuses() > 0);
		Assert.assertTrue("candidate facts must request immutable profiles", construction.profileRequests() > 0);
		Assert.assertTrue("equivalent candidate facts must reuse immutable profiles", construction.profileReuses() > 0);
	}

	private static void assertImmutableCandidateEvidenceIsShared(List<CandidateRuleFact> facts) {
		boolean sharedHeader = false;
		boolean sharedProfile = false;
		for(int left = 0; left < facts.size(); left++)
			for(int right = left + 1; right < facts.size(); right++) {
				CandidateRuleFact first = facts.get(left);
				CandidateRuleFact second = facts.get(right);
				if(first.capability() != null && first.capability().equals(second.capability())
					&& first.shapeProof().equals(second.shapeProof())) {
					Assert.assertSame("equal immutable capabilities must share one build-local value",
						first.capability(), second.capability());
					Assert.assertSame("equal immutable proofs must share one build-local value",
						first.shapeProof(), second.shapeProof());
					sharedHeader = true;
				}
				if(first.profile().equals(second.profile())) {
					Assert.assertSame("equal immutable profiles must share one build-local value",
						first.profile(), second.profile());
					sharedProfile = true;
				}
			}
		Assert.assertTrue("fixture must exercise build-local immutable header sharing", sharedHeader);
		Assert.assertTrue("fixture must exercise build-local immutable profile sharing", sharedProfile);
	}

	private static BuildResult build(Hop hop, List<List<FType>> domains,
		PlacementCandidateGenerator.GenerationPrivacy privacy, SearchSpaceMetrics metrics) {
		String id = "candidate-route-" + hop.getHopID();
		ControlRegionKey region = new ControlRegionKey(id, "main", List.of("root"),
			"root", "compiled");
		CompiledHopKey key = new CompiledHopKey(id, "main", "root", "compiled",
			region, hop.getOpString(), hop.getOpString());
		ValueVersionKey value = new ValueVersionKey(id, "value", region, 0,
			VersionKind.ORDINARY, List.of());
		NodeShapeFact shape = new NodeShapeFact(hop.getDataType(), hop.getDim1(), hop.getDim2());
		List<NodeShapeFact> inputShapes = hop.getInput().stream()
			.map(input -> new NodeShapeFact(input.getDataType(), input.getDim1(), input.getDim2()))
			.toList();
		SinglePartitionFacts partitions = new SinglePartitionFacts(hop.getInput(), Map.of(), Set.of());
		List<PlacementIdentity.DurableAnchorKey> inputAnchors = new ArrayList<>(
			Collections.nCopies(hop.getInput().size(), null));
		List<CompiledHopKey> inputOwners = new ArrayList<>(
			Collections.nCopies(hop.getInput().size(), null));
		List<CandidateRuleKey> keys = new ArrayList<>();
		List<CandidateRuleFact> facts = new ArrayList<>();
		PlacementCandidateGenerator generator = new PlacementCandidateGenerator(facade(), metrics);
		Node node = generator.buildNode(hop, key, value, List.of(), inputAnchors, inputOwners,
			shape, AbstractShapeFact.fromConcrete(shape), partitions, inputShapes, domains,
			keys, facts, privacy);
		return new BuildResult(node, List.copyOf(keys), List.copyOf(facts),
			generator.cpRuleFamilies().stream().map(CpRuleFamily::normalizedSignature).toList(), metrics);
	}

	private static long routeCalls(SearchSpaceMetrics metrics, Hop hop,
		SearchSpaceMetrics.CandidateRoute route) {
		return metrics.candidateRouteSnapshot().stream()
			.filter(count -> count.opcode().equals(normalizedOpcode(hop)) && count.route() == route)
			.mapToLong(SearchSpaceMetrics.CandidateRouteCount::calls).sum();
	}

	private static String normalizedOpcode(Hop hop) {
		String opcode = hop.getOpString();
		int separator = opcode.indexOf(' ');
		return separator < 0 ? opcode : opcode.substring(0, separator);
	}

	private static List<List<FType>> weightedDomains(Hop hop) {
		List<FType> allRuntimeTypes = new ArrayList<>();
		allRuntimeTypes.add(null);
		allRuntimeTypes.addAll(Arrays.asList(FType.values()));
		List<List<FType>> domains = new ArrayList<>();
		for(Hop input : hop.getInput())
			domains.add(input.getDataType().isMatrix()
				? new ArrayList<>(allRuntimeTypes) : Arrays.asList((FType) null));
		return domains;
	}

	private static OracleFacade facade() {
		return new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
	}

	private static DataOp matrix(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, name, 8, 4, 32, 1000);
	}

	private record BuildResult(Node node, List<CandidateRuleKey> ruleKeys,
		List<CandidateRuleFact> ruleFacts, List<String> familySignatures,
		SearchSpaceMetrics metrics) { }
}
