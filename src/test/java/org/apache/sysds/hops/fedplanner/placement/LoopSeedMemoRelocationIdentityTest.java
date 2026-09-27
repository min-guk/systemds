/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class LoopSeedMemoRelocationIdentityTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"memo-action", "main", List.of("root"), "root", "compiled");
	private static final CompiledHopKey SOURCE = key("source");
	private static final CompiledHopKey OWNER = key("owner");
	private static final ValueVersionKey SOURCE_VALUE = new ValueVersionKey(
		"memo-action", "source", REGION, 0, VersionKind.ORDINARY, List.of());
	private static final CandidateRuleKey SOURCE_RULE = new CandidateRuleKey(SOURCE, List.of());
	private static final CandidateRuleKey OWNER_RULE = new CandidateRuleKey(OWNER,
		List.of(PlacementAnalysis.CandidateInputState.present(FType.ROW)));
	private static final PlacementState SOURCE_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementState TARGET_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);

	@Test
	public void memoHitChangesOnlyEqualRelocationKeyIdentityAndIsIdempotent() {
		RelocationAction memoAction = action(anchor("pool"));
		RelocationAction currentAction = action(anchor("pool"));
		Assert.assertEquals(memoAction.key(), currentAction.key());
		Assert.assertNotSame(memoAction.key(), currentAction.key());
		List<CandidateRuleFact> freshExit = List.of(fact(memoAction.key()));

		List<CandidateRuleFact> first = PlacementRelationClosure
			.normalizeMemoizedRelocationActionIdentity(freshExit, List.of(currentAction));
		Assert.assertEquals("memo replay must preserve the fresh completed-exit value", freshExit, first);
		Assert.assertSame("candidate rule ownership identity is unchanged",
			freshExit.get(0).key(), first.get(0).key());
		Assert.assertSame("equal relocation authority must use the current graph-owned key",
			currentAction.key(), relocationKey(first));

		List<CandidateRuleFact> second = PlacementRelationClosure
			.normalizeMemoizedRelocationActionIdentity(first, List.of(currentAction));
		Assert.assertSame("repeated identity normalization must be allocation-free and idempotent",
			first, second);
		Assert.assertSame(currentAction.key(), relocationKey(second));
	}

	@Test
	public void missingOrChangedCurrentAuthorityPreservesCompletedReference() {
		RelocationAction memoAction = action(anchor("memo"));
		RelocationAction changedAction = action(anchor("changed"));
		List<CandidateRuleFact> completed = List.of(fact(memoAction.key()));

		List<CandidateRuleFact> missing = PlacementRelationClosure
			.normalizeMemoizedRelocationActionIdentity(completed, List.of());
		List<CandidateRuleFact> changed = PlacementRelationClosure
			.normalizeMemoizedRelocationActionIdentity(completed, List.of(changedAction));
		Assert.assertSame("missing authority remains for ordinary final pruning", completed, missing);
		Assert.assertSame("changed authority must not be invented as an equal replacement", completed, changed);
		Assert.assertSame(memoAction.key(), relocationKey(missing));
		Assert.assertSame(memoAction.key(), relocationKey(changed));
	}

	private static CandidateRuleFact fact(RelocationActionKey action) {
		PlacementEmissionState sourceEmission = new PlacementEmissionState(SOURCE_STATE, false);
		CandidateEmissionRealization sourceRealization = CandidateEmissionRealization.local(sourceEmission);
		CandidateRealizationReference source = CandidateRealizationReference.of(SOURCE_RULE, sourceRealization);
		CandidateRealizationInputBinding binding = CandidateRealizationInputBinding.relocation(0, source, action);
		PlacementEmissionState targetEmission = new PlacementEmissionState(TARGET_STATE, false);
		CandidateEmissionRealization realization = CandidateEmissionRealization.nativeLineage(
			targetEmission, "memo-target", List.of(), List.of(binding));
		CandidateEmissionFact emission = new CandidateEmissionFact(
			targetEmission, FType.ROW, null, List.of(realization));
		return new CandidateRuleFact(OWNER_RULE, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.AGG_UNARY, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "memo", List.of()),
			new CandidateShapeProofFact(java.util.Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), List.of(emission), "");
	}

	private static RelocationAction action(DurableAnchorKey anchor) {
		RelocationActionKey key = new RelocationActionKey(SOURCE_VALUE, TARGET_STATE, FType.ROW,
			anchor, "scope", List.of(OWNER));
		return new RelocationAction(key, List.of(new ObligationKey(
			OWNER, 0, SOURCE_VALUE, TARGET_STATE, key, "scope")));
	}

	private static RelocationActionKey relocationKey(List<CandidateRuleFact> facts) {
		CandidateRealizationSupportClause clause = facts.get(0).allowedEmissionFacts().get(0)
			.realizations().get(0).supportClauses().get(0);
		return clause.inputBindings().get(0).relocationAction();
	}

	private static CompiledHopKey key(String name) {
		return new CompiledHopKey("memo-action", "main", "root", "compiled", REGION, name, name);
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(4L, 2L))));
	}
}
