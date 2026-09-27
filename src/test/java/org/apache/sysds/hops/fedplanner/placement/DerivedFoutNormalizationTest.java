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

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class DerivedFoutNormalizationTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"derived-normalization", "main", List.of("root"), "root", "compiled");
	private static final CompiledHopKey OWNER = key("owner");
	private static final CompiledHopKey SOURCE = key("source");
	private static final CompiledHopKey OTHER_SOURCE = key("other-source");
	private static final ValueVersionKey OWNER_VERSION = version("owner", 0);
	private static final PlacementState TARGET = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementState LOCAL_SOURCE = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementEmissionState DERIVED = new PlacementEmissionState(TARGET, true);
	private static final PlacementEmissionState SOURCE_EMISSION =
		new PlacementEmissionState(TARGET, false);
	private static final CandidateRuleKey OWNER_RULE = new CandidateRuleKey(
		OWNER, List.of(CandidateInputState.present(FType.ROW)));
	private static final CandidateRuleKey SOURCE_RULE = new CandidateRuleKey(SOURCE, List.of());
	private static final CandidateRuleKey OTHER_SOURCE_RULE =
		new CandidateRuleKey(OTHER_SOURCE, List.of());

	@Test
	public void matchingDerivedOutputRetainsTentativeBindingsButChangedAuthorityDoesNot() throws Exception {
		DataOp input = new DataOp("input", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "input", 4, 2, 8, 1000);
		Hop owner = new UnaryOp("owner", DataType.MATRIX, ValueType.FP64, OpOp1.LOG, input);
		Map<CompiledHopKey,Hop> origins = Map.of(OWNER, owner);
		Map<Hop,NodeShapeFact> shapes = Map.of(owner, new NodeShapeFact(DataType.MATRIX, 4, 2));
		DerivedFoutMaterializationActionKey action = action("scope-a");
		CandidateRuleFact raw = fact(new CandidateEmissionFact(DERIVED, FType.ROW, action));
		CandidateRuleFact normalizedRaw = bind(List.of(raw), origins, shapes).get(0);
		CandidateEmissionRealization expected = normalizedRaw.allowedEmissionFacts().get(0)
			.realizations().get(0);
		CandidateEmissionRealization exactSourceRealization = sourceRealization("source");
		CandidateRealizationReference exactSource = CandidateRealizationReference.of(
			SOURCE_RULE, exactSourceRealization);
		CandidateRealizationInputBinding binding = CandidateRealizationInputBinding.direct(0, exactSource);
		CandidateRealizationSupportClause grounded = new CandidateRealizationSupportClause(
			expected.supportClauses().get(0).proofDependencies(), List.of(binding));
		CandidateEmissionRealization supported = new CandidateEmissionRealization(
			expected.key(), List.of(grounded));
		CandidateRuleFact supportedFact = fact(new CandidateEmissionFact(
			DERIVED, FType.ROW, action, List.of(supported)));

		CandidateRuleFact retained = bind(List.of(supportedFact), origins, shapes).get(0);
		CandidateEmissionRealization retainedRealization = retained.allowedEmissionFacts().get(0)
			.realizations().get(0);
		Assert.assertEquals(expected.key(), retainedRealization.key());
		Assert.assertEquals("matching action and durable output retain grounded tentative support",
			1, retainedRealization.supportClauses().stream()
				.filter(clause -> clause.inputBindings().equals(List.of(binding))).count());
		Assert.assertTrue("fresh normalized template authority remains available independently",
			retainedRealization.supportClauses().stream()
				.anyMatch(clause -> clause.inputBindings().isEmpty()));
		Assert.assertEquals("normalizing an already matching result is idempotent",
			retained, bind(List.of(retained), origins, shapes).get(0));
		Assert.assertEquals("normalization must not mutate its input fact", supportedFact,
			fact(new CandidateEmissionFact(DERIVED, FType.ROW, action, List.of(supported))));

		CandidateEmissionRealization wrongLayout = CandidateEmissionRealization.durable(
			DERIVED, anchor("wrong-output", "worker:9001"), grounded.proofDependencies(), List.of(binding));
		CandidateRuleFact layoutReset = bind(List.of(fact(new CandidateEmissionFact(
			DERIVED, FType.ROW, action, List.of(wrongLayout)))), origins, shapes).get(0);
		CandidateEmissionRealization resetLayout = layoutReset.allowedEmissionFacts().get(0)
			.realizations().get(0);
		Assert.assertEquals(expected.key(), resetLayout.key());
		Assert.assertTrue("a changed durable output cannot carry old support clauses",
			resetLayout.supportClauses().get(0).inputBindings().isEmpty());

		DerivedFoutMaterializationActionKey changedAction = action("scope-b");
		CandidateRuleFact actionReset = bind(List.of(fact(new CandidateEmissionFact(
			DERIVED, FType.ROW, changedAction, List.of(supported)))), origins, shapes).get(0);
		CandidateRealizationSupportClause resetAction = actionReset.allowedEmissionFacts().get(0)
			.realizations().get(0).supportClauses().get(0);
		Assert.assertTrue("support proved for a different exact action cannot be retained",
			resetAction.inputBindings().isEmpty());
		Assert.assertTrue("the old action proof must not cross into the changed action",
			resetAction.proofDependencies().stream().noneMatch(
				grounded.proofDependencies()::contains));

		CandidateRuleFact exactSourceFact = sourceFact(SOURCE_RULE, exactSourceRealization);
		CandidateRuleFact prunedWithSource = publish(List.of(exactSourceFact, retained)).get(1);
		Assert.assertTrue("the retained binding remains live with its exact source reference",
			hasBinding(prunedWithSource, binding));
		Assert.assertTrue("source pruning preserves the independent fresh template",
			hasEmptyTemplate(prunedWithSource));

		CandidateRuleFact withdrawn = publish(List.of(retained)).get(0);
		Assert.assertFalse("withdrawing the exact source removes only its dangling binding clause",
			hasBinding(withdrawn, binding));
		Assert.assertTrue("withdrawal keeps the fresh derived template",
			hasEmptyTemplate(withdrawn));

		CandidateEmissionRealization equalLayoutOtherSource = sourceRealization("equal-layout-other");
		CandidateRuleFact unrelated = sourceFact(OTHER_SOURCE_RULE, equalLayoutOtherSource);
		CandidateRuleFact notRescued = publish(List.of(unrelated, retained)).get(1);
		Assert.assertFalse("an equal-layout but different exact source reference cannot rescue support",
			hasBinding(notRescued, binding));
		Assert.assertTrue(hasEmptyTemplate(notRescued));

		CandidateRuleFact renormalized = bind(List.of(supportedFact), origins, shapes).get(0);
		CandidateRuleFact restored = publish(List.of(exactSourceFact, renormalized)).get(1);
		Assert.assertTrue("regenerating the supported fact after source restoration restores the binding",
			hasBinding(restored, binding));
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> bind(List<CandidateRuleFact> facts,
		Map<CompiledHopKey,Hop> origins, Map<Hop,NodeShapeFact> shapes) throws Exception {
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"bindDerivedFoutRealizations", List.class, Map.class, Map.class);
		method.setAccessible(true);
		return (List<CandidateRuleFact>)method.invoke(null, facts, origins, shapes);
	}

	private static CandidateRuleFact fact(CandidateEmissionFact emission) {
		return new CandidateRuleFact(OWNER_RULE, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), List.of(emission), "");
	}

	private static CandidateEmissionRealization sourceRealization(String id) {
		return CandidateEmissionRealization.durable(
			SOURCE_EMISSION, anchor(id, "worker:8001"), List.of(), List.of());
	}

	private static CandidateRuleFact sourceFact(CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "source", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""),
			List.of(new CandidateEmissionFact(
				SOURCE_EMISSION, FType.ROW, null, List.of(realization))), "");
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> publish(List<CandidateRuleFact> facts) throws Exception {
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"removeUngroundedStagingRealizations", List.class);
		method.setAccessible(true);
		return (List<CandidateRuleFact>)method.invoke(null, facts);
	}

	private static boolean hasBinding(CandidateRuleFact fact,
		CandidateRealizationInputBinding binding) {
		return fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
			.flatMap(realization -> realization.supportClauses().stream())
			.anyMatch(clause -> clause.inputBindings().contains(binding));
	}

	private static boolean hasEmptyTemplate(CandidateRuleFact fact) {
		return fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
			.flatMap(realization -> realization.supportClauses().stream())
			.anyMatch(clause -> clause.inputBindings().isEmpty());
	}

	private static DerivedFoutMaterializationActionKey action(String scope) {
		return new DerivedFoutMaterializationActionKey(OWNER, OWNER_VERSION, OWNER_RULE,
			LOCAL_SOURCE, TARGET, anchor("materialization-seed", "worker:8001"),
			SOURCE, FType.ROW, FType.ROW, scope);
	}

	private static DurableAnchorKey anchor(String id, String worker) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition(worker, List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static CompiledHopKey key(String name) {
		return new CompiledHopKey("derived-normalization", "main", "root",
			"compiled", REGION, name, name);
	}

	private static ValueVersionKey version(String name, int ordinal) {
		return new ValueVersionKey("derived-normalization", name, REGION,
			ordinal, VersionKind.ORDINARY, List.of());
	}
}
