/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact invalidation boundary for the physical single-partition proof cache. */
public class PhysicalSinglePartitionProjectionTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"single-partition-projection", "main", List.of("main"), "main", "compiled");
	private static final PlacementState FULL_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
	private static final PlacementEmissionState FULL = new PlacementEmissionState(FULL_STATE, false);

	@Test
	public void physicalStateRetainsProofsOnlyForAnEqualOwnerProjection() throws Exception {
		CandidateRuleKey owner = rule("state-owner");
		CandidateEmissionRealization base = nativeRealization(
			owner, "state-native", anchor("state-pool", false));
		CandidateRealizationReference reference = ref(owner, base);
		CandidateRuleFact first = fact(owner, base, "metadata-a");
		Object state = physicalState(first);
		Map<?,?> initialProofs = stateProofs(state);
		Map<?,?> savedInitialProofs = Map.copyOf(initialProofs);
		Assert.assertEquals("EXACT", initialProofs.get(reference).toString());

		commit(state, owner, fact(owner, base, "metadata-b"));
		Assert.assertSame("metadata-only owner replacement keeps the exact proof map",
			initialProofs, stateProofs(state));

		CandidateEmissionRealization changed = nativeRealization(
			owner, "state-native", anchor("state-pool", true));
		Assert.assertEquals("native witness changes retain the realization key",
			reference, ref(owner, changed));
		commit(state, owner, fact(owner, changed, "metadata-b"));
		Map<?,?> updatedProofs = stateProofs(state);
		Assert.assertEquals("NON_SINGLE", updatedProofs.get(reference).toString());
		Assert.assertNotEquals("the old proof values must not survive a changed native witness",
			savedInitialProofs, Map.copyOf(updatedProofs));
		Assert.assertEquals("incremental proof repair must equal a cold exact fixed point",
			fixedPoint(List.of(List.of(fact(owner, changed, "metadata-b")))), updatedProofs);
	}

	@Test
	public void projectionIgnoresRuleMetadataButRetainsEveryProofInputWitnessAndStatus() throws Exception {
		CandidateRuleKey owner = rule("owner");
		CandidateRuleKey sourceA = rule("source-a"), sourceB = rule("source-b");
		CandidateEmissionRealization source = durable("source", false);
		CandidateRealizationReference referenceA = ref(sourceA, source);
		CandidateRealizationReference referenceB = ref(sourceB, source);
		CandidateEmissionRealization base = valueMap("value", clause(proof(owner, "proof-a"),
			CandidateRealizationInputBinding.direct(0, referenceA)));

		Map<?,?> first = projection(List.of(fact(owner, base, "metadata-a")));
		Assert.assertEquals("non-proof rule metadata must not invalidate the proof cache", first,
			projection(List.of(fact(owner, base, "metadata-b"))));

		CandidateEmissionRealization changedProof = valueMap("value", clause(proof(owner, "proof-b"),
			CandidateRealizationInputBinding.direct(0, referenceA)));
		Assert.assertNotEquals(first, projection(List.of(fact(owner, changedProof, "metadata-a"))));

		CandidateEmissionRealization changedBinding = valueMap("value", clause(proof(owner, "proof-a"),
			CandidateRealizationInputBinding.direct(0, referenceB)));
		Assert.assertNotEquals("a changed missing exact reference must invalidate the projection",
			first, projection(List.of(fact(owner, changedBinding, "metadata-a"))));

		CandidateEmissionRealization witnessA = nativeRealization(owner, "native", anchor("pool-a", false));
		CandidateEmissionRealization witnessB = nativeRealization(owner, "native", anchor("pool-b", false));
		Assert.assertNotEquals(projection(List.of(fact(owner, witnessA, "metadata-a"))),
			projection(List.of(fact(owner, witnessB, "metadata-a"))));
		Assert.assertNotEquals(first, projection(List.of(failed(owner))));
	}

	@Test
	public void ownerProjectionPreservesLegacyLastKeyWins() throws Exception {
		CandidateRuleKey owner = rule("last-owner");
		CandidateEmissionRealization first = valueMap("same-key", clause(proof(owner, "first")));
		CandidateEmissionRealization last = valueMap("same-key", clause(proof(owner, "last")));
		CandidateEmissionRealization shadowedChange = valueMap("same-key", clause(proof(owner, "changed")));

		Map<?,?> expected = projection(List.of(fact(owner, last, "last")));
		Assert.assertEquals(expected, projection(List.of(
			fact(owner, first, "first"), fact(owner, last, "last"))));
		Assert.assertEquals("a changed shadowed row does not change the effective owner projection", expected,
			projection(List.of(fact(owner, shadowedChange, "changed"), fact(owner, last, "last"))));
		Assert.assertNotEquals(expected, projection(List.of(
			fact(owner, last, "last"), fact(owner, shadowedChange, "changed"))));
	}

	@Test
	public void sourceFreeClausesCollapseToTheirExactThreeBitOr() throws Exception {
		CandidateRuleKey owner = rule("static-owner"), sourceRule = rule("static-source");
		CandidateEmissionRealization source = durable("static-source", false);
		CandidateRealizationReference reference = ref(sourceRule, source);
		CandidateRealizationSupportClause unknown = clause(proof(owner, "unknown"));
		CandidateRealizationSupportClause exact = relocationClause(reference, owner, "exact", false);
		CandidateRealizationSupportClause nonSingle = relocationClause(reference, owner, "non-single", true);
		CandidateEmissionRealization realization = CandidateEmissionRealization.valueMap(
			FULL, "static-value", List.of(unknown, exact, nonSingle));

		Assert.assertEquals("all source-free alternatives compile to one OR clause", 1,
			compiledClauses(realization, Map.of()).size());
		Assert.assertEquals("NON_SINGLE", fixedPoint(List.of(List.of(
			fact(owner, realization, "static")))).get(ref(owner, realization)).toString());
	}

	@SuppressWarnings("unchecked")
	private static Map<CandidateRealizationReference,CandidateEmissionRealization> projection(
		List<CandidateRuleFact> facts) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"singlePartitionOwnerRealizations", List.class);
		method.setAccessible(true);
		return (Map<CandidateRealizationReference,CandidateEmissionRealization>)method.invoke(null, facts);
	}

	private static Object physicalState(CandidateRuleFact fact) throws Exception {
		Class<?> type = java.util.Arrays.stream(PlacementRelationClosure.class.getDeclaredClasses())
			.filter(candidate -> candidate.getSimpleName().equals("PhysicalCandidateState"))
			.findFirst().orElseThrow();
		Constructor<?> constructor = type.getDeclaredConstructor(List.class, List.class, List.class);
		constructor.setAccessible(true);
		List<Object> nodes = new ArrayList<>();
		nodes.add(null);
		return constructor.newInstance(nodes,
			new ArrayList<>(List.of(new ArrayList<>(List.of(fact.key())))),
			new ArrayList<>(List.of(new ArrayList<>(List.of(fact)))));
	}

	private static void commit(Object state, CandidateRuleKey key, CandidateRuleFact fact) throws Exception {
		Method commit = java.util.Arrays.stream(state.getClass().getDeclaredMethods())
			.filter(method -> method.getName().equals("commit")).findFirst().orElseThrow();
		commit.setAccessible(true);
		commit.invoke(state, 0, null, null, List.of(key), List.of(fact), new HashMap<>());
	}

	@SuppressWarnings("unchecked")
	private static Map<?,?> stateProofs(Object state) throws Exception {
		Method method = state.getClass().getDeclaredMethod("singlePartitionProofs");
		method.setAccessible(true);
		return (Map<?,?>)method.invoke(state);
	}

	@SuppressWarnings("unchecked")
	private static List<?> compiledClauses(CandidateEmissionRealization realization,
		Map<CandidateRealizationReference,Integer> ordinals) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"singlePartitionClauses", CandidateEmissionRealization.class, Map.class);
		method.setAccessible(true);
		return (List<?>)method.invoke(null, realization, ordinals);
	}

	@SuppressWarnings("unchecked")
	private static Map<CandidateRealizationReference,?> fixedPoint(
		List<List<CandidateRuleFact>> inventory) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"exactSinglePartitionRealizationProofs", List.class);
		method.setAccessible(true);
		return (Map<CandidateRealizationReference,?>)method.invoke(null, inventory);
	}

	private static CandidateRealizationSupportClause relocationClause(
		CandidateRealizationReference source, CandidateRuleKey owner, String id, boolean multiPartition) {
		RelocationActionKey action = new RelocationActionKey(
			new ValueVersionKey(REGION.programFingerprint(), id, REGION, 0, VersionKind.ORDINARY, List.of()),
			FULL_STATE, FType.FULL, anchor(id, multiPartition), REGION.normalizedSignature(),
			List.of(owner.parentOccurrence()));
		return clause(proof(owner, id), CandidateRealizationInputBinding.relocation(0, source, action));
	}

	private static CandidateEmissionRealization nativeRealization(CandidateRuleKey owner,
		String lineage, DurableAnchorKey witness) {
		return CandidateEmissionRealization.nativeLineage(FULL, lineage, witness,
			List.of(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
				owner.parentOccurrence(), "native:" + lineage)), List.of());
	}

	private static CandidateEmissionRealization valueMap(String id,
		CandidateRealizationSupportClause clause) {
		return CandidateEmissionRealization.valueMap(FULL, id, List.of(clause));
	}

	private static CandidateRealizationSupportClause clause(PlacementProofKey proof,
		CandidateRealizationInputBinding... bindings) {
		return new CandidateRealizationSupportClause(List.of(proof), List.of(bindings));
	}

	private static PlacementProofKey proof(CandidateRuleKey owner, String id) {
		return new PlacementProofKey(PlacementProofKind.SHAPE, owner.parentOccurrence(), id);
	}

	private static CandidateRealizationReference ref(CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		return CandidateRealizationReference.of(rule, realization);
	}

	private static CandidateEmissionRealization durable(String id, boolean multiPartition) {
		return CandidateEmissionRealization.durable(FULL, anchor(id, multiPartition), List.of(), List.of());
	}

	private static DurableAnchorKey anchor(String id, boolean multiPartition) {
		List<AnchorPartition> partitions = multiPartition
			? List.of(partition(id + "-a", 0, 4), partition(id + "-b", 4, 8))
			: List.of(partition(id + "-a", 0, 8));
		return new DurableAnchorKey(id, FType.FULL, partitions);
	}

	private static AnchorPartition partition(String worker, long begin, long end) {
		return new AnchorPartition(worker, List.of(begin, 0L), List.of(end, 3L));
	}

	private static CandidateRuleKey rule(String name) {
		return new CandidateRuleKey(new CompiledHopKey(REGION.programFingerprint(), "main", "main",
			"compiled", REGION, name, name), List.of(CandidateInputState.present(FType.FULL)));
	}

	private static CandidateRuleFact fact(CandidateRuleKey key,
		CandidateEmissionRealization realization, String metadata) {
		CandidateEmissionFact emission = new CandidateEmissionFact(
			FULL, FType.FULL, null, List.of(realization));
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, metadata, ExecType.FED,
				FederatedOutput.FOUT, FType.FULL, ReasonCode.OK, metadata, List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.FULL), ""), List.of(emission), "");
	}

	private static CandidateRuleFact failed(CandidateRuleKey key) {
		return new CandidateRuleFact(key, CandidateEvaluationStatus.RULE_ERROR, null,
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), "failed"), List.of(), "failed");
	}
}
