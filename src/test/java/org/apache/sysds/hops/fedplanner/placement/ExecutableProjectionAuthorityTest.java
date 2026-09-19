/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Candidate diagnostics cannot substitute for executable FED/LOUT authority. */
public class ExecutableProjectionAuthorityTest {
	private static final PlacementState FED_LOCAL = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementState CP_LOCAL = new PlacementState(
		ExecType.CP, FederatedOutput.LOUT, null, false);

	@Test
	public void unavailableAllLocalRowCannotKeepFederatedLocalState() throws Exception {
		Node node = node("unavailable", List.of(FED_LOCAL));
		CandidateRuleFact diagnostic = fact(node.key(), CandidateEvaluationStatus.PROFILE_ERROR,
			List.of(CandidateInputState.absentLocal()), "PROFILE_ERROR:fixture");

		Node projected = project(node, List.of(diagnostic));
		Assert.assertTrue("unavailable all-local row is diagnostic evidence, not FED/LOUT authority",
			projected.legalAlternatives().isEmpty());
		Assert.assertFalse("a node with no executable placement must not remain emitted",
			projected.emittedWork());
	}

	@Test
	public void availableAllLocalRowKeepsExistingNativeFederatedLocalState() throws Exception {
		Node node = node("available", List.of(FED_LOCAL));
		CandidateRuleFact available = fact(node.key(), CandidateEvaluationStatus.AVAILABLE,
			List.of(CandidateInputState.absentLocal()), "");

		Node projected = project(node, List.of(available));
		Assert.assertEquals("available native-local row remains the explicit base-authority exception",
			List.of(FED_LOCAL), projected.legalAlternatives());
	}

	@Test
	public void coordinatorLocalStateDoesNotDependOnCandidateRealizationAuthority() throws Exception {
		Node node = node("cp", List.of(CP_LOCAL));
		CandidateRuleFact diagnostic = fact(node.key(), CandidateEvaluationStatus.PROFILE_ERROR,
			List.of(CandidateInputState.absentLocal()), "PROFILE_ERROR:fixture");

		Assert.assertEquals(List.of(CP_LOCAL), project(node, List.of(diagnostic)).legalAlternatives());
	}

	private static CandidateRuleFact fact(CompiledHopKey parent, CandidateEvaluationStatus status,
		List<CandidateInputState> inputs, String failure) {
		CandidateRuleKey key = new CandidateRuleKey(parent, inputs);
		boolean available = status == CandidateEvaluationStatus.AVAILABLE;
		PlacementState witnessState = new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		List<CandidateEmissionFact> emissions = available
			? List.of(new CandidateEmissionFact(new PlacementEmissionState(witnessState, false), FType.ROW))
			: List.of();
		return new CandidateRuleFact(key, status,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.LOUT, FType.ROW, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(available ? List.of(FType.ROW) : List.of(), failure), emissions, failure);
	}

	private static Node node(String name, List<PlacementState> alternatives) {
		ControlRegionKey region = new ControlRegionKey("projection", "main", List.of("root"),
			"root", "compiled");
		CompiledHopKey key = new CompiledHopKey("projection", "main", "root", "compiled",
			region, name, name);
		ValueVersionKey value = new ValueVersionKey("projection", name, region,
			0, VersionKind.ORDINARY, List.of());
		return new Node(key, NodeKind.OPERATION, value, !alternatives.isEmpty(), alternatives, List.of(), List.of());
	}

	@SuppressWarnings("unchecked")
	private static Node project(Node node, List<CandidateRuleFact> facts) throws Exception {
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"projectCandidateNodesToExecutableStates", List.class, List.class, int.class);
		method.setAccessible(true);
		Object projection = method.invoke(null, List.of(node), facts, 1);
		Method nodes = projection.getClass().getDeclaredMethod("nodes");
		nodes.setAccessible(true);
		return ((List<Node>) nodes.invoke(projection)).get(0);
	}
}
