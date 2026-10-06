/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.*;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Refining shape evidence may add choices, but cannot silently remove native execution. */
public class PhysicalCandidateRefinementTest {
	@Test
	public void strongerShapeEvidenceWithNativeGrowthIsMonotone() throws Exception {
		var local = emission(ExecType.CP, null);
		var prior = fact(List.of(local), "UNKNOWN");
		var replacement = fact(List.of(local, emission(ExecType.FED, FType.FULL)), "true");
		Assert.assertEquals(List.of(), removed(prior, replacement));
	}

	@Test
	public void strongerShapeEvidenceDoesNotAuthorizeNativeLoss() throws Exception {
		var local = emission(ExecType.CP, null);
		var prior = fact(List.of(local, emission(ExecType.FED, FType.FULL)), "UNKNOWN");
		Assert.assertNull(removed(prior, fact(List.of(local), "true")));
	}

	@Test
	public void executionLayoutChangeNeedsSeparateRuntimeProof() throws Exception {
		var prior = fact(List.of(emission(ExecType.FED, FType.BROADCAST)), "UNKNOWN");
		Assert.assertNull(removed(prior, fact(List.of(emission(ExecType.FED, FType.ROW)), "true")));
	}

	private static Object removed(CandidateRuleFact prior, CandidateRuleFact replacement) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("removedProvisionalMaterializations",
			CandidateRuleFact.class, CandidateRuleFact.class);
		method.setAccessible(true);
		return method.invoke(null, prior, replacement);
	}

	private static CandidateEmissionFact emission(ExecType execution, FType type) {
		var state = new PlacementEmissionState(new PlacementState(execution, FederatedOutput.LOUT, type, false), false);
		return new CandidateEmissionFact(state, type, null, List.of(CandidateEmissionRealization.local(state)));
	}

	private static CandidateRuleFact fact(List<CandidateEmissionFact> emissions, String singlePartition) {
		var region = new ControlRegionKey("refinement", "main", List.of("root"), "call", "compiled");
		var owner = new CompiledHopKey("refinement", "main", "call", "compiled", region, "owner", "owner");
		return new CandidateRuleFact(new CandidateRuleKey(owner, List.of()), CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.BINARY_EWISE, "fixture", ExecType.CP,
				FederatedOutput.LOUT, null, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of("fullSinglePartition", singlePartition), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""), emissions, "");
	}
}
