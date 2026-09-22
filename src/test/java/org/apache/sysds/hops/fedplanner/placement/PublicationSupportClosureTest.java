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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Publication may discard expired proofs, but cannot borrow or remap their authority. */
public class PublicationSupportClosureTest {
	private static final PlacementEmissionState LOCAL = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.LOUT, null, false), false);
	private static final PlacementEmissionState ROW = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
	private static final CandidateRuleKey SOURCE = rule("source"), CONSUMER = rule("consumer");

	@Test
	public void privacyRowReuseRequiresTheSameExactEmissionObjects() {
		CandidateRuleFact original = fact(SOURCE, ROW, List.of(source("current")));
		Assert.assertSame(original, NeutralPlacementGraphBuilder.retainUnchangedPrivacyFact(
			original, original.allowedEmissionFacts()));
		CandidateEmissionFact replacement = new CandidateEmissionFact(ROW, FType.ROW, null,
			List.of(source("another-worker-map")));
		CandidateRuleFact replaced = NeutralPlacementGraphBuilder.retainUnchangedPrivacyFact(
			original, List.of(replacement));
		Assert.assertNotSame("a different realization authority must not reuse the old row", original, replaced);
		Assert.assertEquals(List.of(replacement), replaced.allowedEmissionFacts());
	}

	@Test
	public void expiredClauseDoesNotRemoveTheValidAlternative() throws Exception {
		CandidateEmissionRealization source = source("current"), expired = source("expired");
		CandidateEmissionRealization validClause = local(source, 0), expiredClause = local(expired, 0);
		List<CandidateRuleFact> result = publish(List.of(fact(SOURCE, ROW, List.of(source)),
			fact(CONSUMER, LOCAL, List.of(validClause, expiredClause))));
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, result.get(1).status());
		Assert.assertEquals(List.of(validClause), result.get(1).allowedEmissionFacts().get(0).realizations());
		Assert.assertEquals(result, publish(result));
	}

	@Test
	public void oneLiveInputCannotAuthorizeAnExpiredConjunct() throws Exception {
		CandidateEmissionRealization source = source("current"), expired = source("expired");
		CandidateEmissionRealization conjunction = CandidateEmissionRealization.local(LOCAL, List.of(), List.of(
			CandidateRealizationInputBinding.direct(0, CandidateRealizationReference.of(SOURCE, source)),
			CandidateRealizationInputBinding.direct(1, CandidateRealizationReference.of(SOURCE, expired))));
		List<CandidateRuleFact> result = publish(List.of(fact(SOURCE, ROW, List.of(source)),
			fact(CONSUMER, LOCAL, List.of(conjunction))));
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, result.get(0).status());
		Assert.assertEquals(CandidateEvaluationStatus.PROFILE_ERROR, result.get(1).status());
		Assert.assertTrue(result.get(1).allowedEmissionFacts().isEmpty());
	}

	private static CandidateEmissionRealization local(CandidateEmissionRealization source, int position) {
		return CandidateEmissionRealization.local(LOCAL, List.of(),
			List.of(CandidateRealizationInputBinding.direct(position, CandidateRealizationReference.of(SOURCE, source))));
	}

	private static CandidateEmissionRealization source(String id) {
		return CandidateEmissionRealization.durable(ROW,
			new DurableAnchorKey(id, FType.ROW, List.of(new AnchorPartition("localhost:1234",
				List.of(0L, 0L), List.of(4L, 2L)))), List.of(), List.of());
	}

	private static CandidateRuleKey rule(String name) {
		ControlRegionKey region = new ControlRegionKey("publication", "main", List.of("root"), "root", "compiled");
		return new CandidateRuleKey(new CompiledHopKey("publication", "main", "root", "compiled", region, name, name),
			List.of(CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.ROW)));
	}

	private static CandidateRuleFact fact(CandidateRuleKey key, PlacementEmissionState emission,
		List<CandidateEmissionRealization> realizations) {
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.BINARY_EWISE, "fixture", ExecType.FED,
				emission.placementState().output(), emission.placementState().fType(), ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()), new CandidateProfileFact(List.of(FType.ROW), ""),
			List.of(new CandidateEmissionFact(emission, FType.ROW, null, realizations)), "");
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> publish(List<CandidateRuleFact> facts) throws Exception {
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod("removeUngroundedStagingRealizations", List.class);
		method.setAccessible(true);
		return (List<CandidateRuleFact>)method.invoke(null, facts);
	}
}
