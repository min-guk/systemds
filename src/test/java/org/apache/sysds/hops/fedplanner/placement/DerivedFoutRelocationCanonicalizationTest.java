/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class DerivedFoutRelocationCanonicalizationTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"derived-relocation", "main", List.of("root"), "root", "compiled");
	private static final CompiledHopKey OWNER = new CompiledHopKey(
		"derived-relocation", "main", "root", "compiled", REGION, "owner", "owner");
	private static final CompiledHopKey SOURCE = new CompiledHopKey(
		"derived-relocation", "main", "root", "compiled", REGION, "source", "source");
	private static final ValueVersionKey OWNER_VERSION = version("owner", 0);
	private static final ValueVersionKey SOURCE_VERSION = version("source", 1);
	private static final PlacementState TARGET = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementState SOURCE_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementEmissionState DERIVED = new PlacementEmissionState(TARGET, true);
	private static final PlacementEmissionState SOURCE_EMISSION =
		new PlacementEmissionState(TARGET, false);
	private static final CandidateRuleKey OWNER_RULE = new CandidateRuleKey(
		OWNER, List.of(CandidateInputState.present(FType.ROW)));
	private static final CandidateRuleKey SOURCE_RULE = new CandidateRuleKey(SOURCE, List.of());

	@Test
	public void historicalProofsDoNotCrossProductWithCurrentAssignments() throws Exception {
		DerivedFoutMaterializationActionKey derivedAction = derivedAction();
		CandidateEmissionRealization historicalOutput = historicalOutput("output-a");
		CandidateEmissionFact historical = new CandidateEmissionFact(
			DERIVED, FType.ROW, derivedAction, List.of(historicalOutput));
		List<CandidateRealizationInputBinding> first = List.of(relocationBinding("pool-a"));
		List<CandidateRealizationInputBinding> second = List.of(relocationBinding("pool-b"));

		CandidateEmissionFact canonical = merge(historical, first, second);

		Assert.assertEquals(1, canonical.realizations().size());
		Assert.assertEquals("two histories times two assignments collapse to the two current assignments",
			2, canonical.realizations().get(0).supportClauses().size());
		Assert.assertEquals("rebinding an already canonical result is idempotent",
			canonical, merge(canonical, first, second));
		Assert.assertNotEquals("distinct relocation actions remain distinct support",
			canonical.realizations().get(0).supportClauses().get(0).inputBindings(),
			canonical.realizations().get(0).supportClauses().get(1).inputBindings());
	}

	@Test
	public void outputLayoutsAndRetainedClauseWitnessSemanticsRemainDistinct() throws Exception {
		DerivedFoutMaterializationActionKey derivedAction = derivedAction();
		CandidateEmissionFact layouts = new CandidateEmissionFact(DERIVED, FType.ROW, derivedAction,
			List.of(historicalOutput("output-a"), historicalOutput("output-b")));
		List<CandidateRealizationInputBinding> assignment = List.of(relocationBinding("pool-a"));

		CandidateEmissionFact canonical = merge(layouts, assignment);

		Assert.assertEquals("distinct durable output keys remain separate", 2, canonical.realizations().size());
		DurableAnchorKey witness = anchor("dynamic-witness");
		CandidateRealizationSupportClause first = new CandidateRealizationSupportClause(
			List.of(historyProof("first"), nativeProof("first")), assignment, witness, false);
		CandidateRealizationSupportClause second = new CandidateRealizationSupportClause(
			List.of(historyProof("second"), nativeProof("second")), assignment, witness, false);
		List<CandidateRealizationSupportClause> normalized = canonicalClauses(
			derivedAction, List.of(first, second));
		Assert.assertEquals(1, normalized.size());
		Assert.assertEquals(witness, normalized.get(0).nativeWorkerPoolWitness());
		Assert.assertFalse(normalized.get(0).nativeWorkerPoolLayoutExact());
	}

	private static CandidateEmissionFact merge(CandidateEmissionFact emission,
		List<CandidateRealizationInputBinding>... assignments) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"canonicalDerivedFoutRealizations", CompiledHopKey.class,
			CandidateEmissionFact.class, List.class);
		method.setAccessible(true);
		List<CandidateEmissionRealization> result = new ArrayList<>();
		for(List<CandidateRealizationInputBinding> assignment : assignments) {
			@SuppressWarnings("unchecked")
			List<CandidateEmissionRealization> bound =
				(List<CandidateEmissionRealization>)method.invoke(null, OWNER, emission, assignment);
			result.addAll(bound);
		}
		return new CandidateEmissionFact(DERIVED, FType.ROW, emission.derivedFoutAction(), result);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationSupportClause> canonicalClauses(
		DerivedFoutMaterializationActionKey action,
		List<CandidateRealizationSupportClause> clauses) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"canonicalDerivedFoutClauses", CompiledHopKey.class,
			DerivedFoutMaterializationActionKey.class, List.class);
		method.setAccessible(true);
		return (List<CandidateRealizationSupportClause>)method.invoke(null, OWNER, action, clauses);
	}

	private static CandidateEmissionRealization historicalOutput(String id) {
		return new CandidateEmissionRealization(
			PlacementIdentity.PlacementRealizationKey.durable(DERIVED, anchor(id)),
			List.of(new CandidateRealizationSupportClause(List.of(historyProof("first")), List.of()),
				new CandidateRealizationSupportClause(List.of(historyProof("second")), List.of())));
	}

	private static CandidateRealizationInputBinding relocationBinding(String poolId) {
		CandidateEmissionRealization source = CandidateEmissionRealization.durable(
			SOURCE_EMISSION, anchor("source"), List.of(), List.of());
		CandidateRealizationReference reference = CandidateRealizationReference.of(SOURCE_RULE, source);
		RelocationActionKey action = new RelocationActionKey(SOURCE_VERSION, TARGET,
			FType.ROW, anchor(poolId), REGION.normalizedSignature(), List.of(OWNER));
		return CandidateRealizationInputBinding.relocation(0, reference, action);
	}

	private static DerivedFoutMaterializationActionKey derivedAction() {
		return new DerivedFoutMaterializationActionKey(OWNER, OWNER_VERSION, OWNER_RULE,
			SOURCE_STATE, TARGET, anchor("derived-target"), OWNER, FType.ROW,
			FType.ROW, REGION.normalizedSignature());
	}

	private static PlacementProofKey historyProof(String id) {
		return new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR, OWNER, "history:" + id);
	}

	private static PlacementProofKey nativeProof(String id) {
		return new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, OWNER, "native:" + id);
	}

	private static ValueVersionKey version(String name, int ordinal) {
		return new ValueVersionKey("derived-relocation", name, REGION,
			ordinal, VersionKind.ORDINARY, List.of());
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(4L, 2L))));
	}
}
