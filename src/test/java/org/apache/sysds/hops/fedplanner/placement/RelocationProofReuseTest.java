/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
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

public class RelocationProofReuseTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"relocation-proof-reuse", "main", List.of("root"), "root", "compiled");
	private static final CompiledHopKey OWNER = key("owner");
	private static final PlacementState LOCAL_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementState FOUT_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState LOCAL_EMISSION =
		new PlacementEmissionState(LOCAL_STATE, false);
	private static final PlacementEmissionState FOUT_EMISSION =
		new PlacementEmissionState(FOUT_STATE, false);

	@Test
	public void equalActionsReuseOneProofWithinAnInvocationWithoutChangingTheEagerRelation()
		throws Exception {
		RelocationActionKey firstAction = action("shared");
		RelocationActionKey equalAction = action("shared");
		RelocationActionKey otherAction = action("other");
		Assert.assertEquals(firstAction, equalAction);
		Assert.assertNotSame(firstAction, equalAction);
		CandidateRealizationInputBinding first = binding("source-a", firstAction);
		CandidateRealizationInputBinding equal = binding("source-b", equalAction);
		CandidateRealizationInputBinding other = binding("source-c", otherAction);
		CandidateEmissionFact emission = new CandidateEmissionFact(LOCAL_EMISSION, FType.ROW);

		List<CandidateEmissionRealization> actual = generate(OWNER, emission,
			List.of(List.of(first, equal, other)), null, false, null);
		List<CandidateEmissionRealization> eager = eagerLocal(
			OWNER, emission, List.of(first, equal, other));

		Assert.assertEquals("proof sharing must not change any candidate value", eager, actual);
		Assert.assertEquals(signatures(eager), signatures(actual));
		Assert.assertEquals(eager.hashCode(), actual.hashCode());
		Assert.assertEquals(eager.get(0).supportClauses().stream()
			.map(clause -> clause.inputBindings().get(0)).toList(),
			actual.get(0).supportClauses().stream()
				.map(clause -> clause.inputBindings().get(0)).toList());
		PlacementProofKey firstProof = proofFor(actual, first);
		PlacementProofKey equalProof = proofFor(actual, equal);
		PlacementProofKey otherProof = proofFor(actual, other);
		Assert.assertSame("equal action values in different leaves should share one immutable proof",
			firstProof, equalProof);
		Assert.assertNotSame("different relocation actions retain different proof authority",
			firstProof, otherProof);
	}

	@Test
	public void proofReuseIsIsolatedByOwnerAndInvocation() throws Exception {
		RelocationActionKey action = action("isolated");
		CandidateRealizationInputBinding binding = binding("source", action);
		CandidateEmissionFact emission = new CandidateEmissionFact(LOCAL_EMISSION, FType.ROW);
		PlacementProofKey first = proofFor(generate(OWNER, emission,
			List.of(List.of(binding)), null, false, null), binding);
		PlacementProofKey second = proofFor(generate(OWNER, emission,
			List.of(List.of(binding)), null, false, null), binding);
		CompiledHopKey otherOwner = key("other-owner");
		PlacementProofKey other = proofFor(generate(otherOwner, emission,
			List.of(List.of(binding)), null, false, null), binding);

		Assert.assertEquals(first, second);
		Assert.assertNotSame("the proof memo must not survive one product invocation", first, second);
		Assert.assertSame(OWNER, first.owner());
		Assert.assertSame(otherOwner, other.owner());
		Assert.assertNotEquals(first, other);
	}

	@Test
	public void everyOutputBranchRetainsItsAcceptedOrRejectedProduct() throws Exception {
		RelocationActionKey action = action("branches");
		CandidateRealizationInputBinding binding = binding("source", action);
		List<List<CandidateRealizationInputBinding>> choices = List.of(List.of(binding));
		PlacementProofKey proof = proof(OWNER, action);

		CandidateEmissionFact local = new CandidateEmissionFact(LOCAL_EMISSION, FType.ROW);
		Assert.assertEquals(List.of(CandidateEmissionRealization.local(
			LOCAL_EMISSION, List.of(proof), List.of(binding))),
			generate(OWNER, local, choices, null, false, null));

		DurableAnchorKey output = anchor("output");
		CandidateEmissionFact fout = new CandidateEmissionFact(FOUT_EMISSION, FType.ROW);
		Assert.assertEquals(List.of(CandidateEmissionRealization.durable(
			FOUT_EMISSION, output, List.of(proof), List.of(binding))),
			generate(OWNER, fout, choices, output, false, null));

		DurableAnchorKey pool = anchor("dynamic-pool");
		String lineage = "relocation-native:" + OWNER.normalizedSignature()
			+ "|pool=" + pool.normalizedSignature();
		Assert.assertEquals(List.of(CandidateEmissionRealization.nativeLineageDynamicLayout(
			FOUT_EMISSION, lineage, pool, List.of(proof), List.of(binding))),
			generate(OWNER, fout, choices, null, true, pool));

		Assert.assertTrue(generate(OWNER, fout, choices, null, false, null).isEmpty());
		Assert.assertTrue(generate(OWNER, fout, List.of(List.of()), output, false, null).isEmpty());

		CandidateEmissionFact derived = derivedEmission(output);
		List<PlacementProofKey> derivedProofs = new ArrayList<>();
		derivedProofs.add(new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR, OWNER,
			"derived-fout:" + derived.derivedFoutAction().normalizedSignature()));
		derivedProofs.add(proof);
		derivedProofs = derivedProofs.stream().distinct()
			.sorted(PlacementAnalysis.<PlacementProofKey>canonicalComparator()).toList();
		Assert.assertEquals(List.of(new CandidateEmissionRealization(
			derived.realizations().get(0).key(), derivedProofs, List.of(binding))),
			generate(OWNER, derived, choices, null, false, null));
	}

	private static List<CandidateEmissionRealization> eagerLocal(CompiledHopKey owner,
		CandidateEmissionFact emission, List<CandidateRealizationInputBinding> assignments) {
		List<CandidateEmissionRealization> leaves = assignments.stream()
			.map(binding -> CandidateEmissionRealization.local(emission.emissionState(),
				List.of(proof(owner, binding.relocationAction())), List.of(binding)))
			.toList();
		return new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
			emission.derivedFoutAction(), leaves).realizations();
	}

	private static PlacementProofKey proofFor(List<CandidateEmissionRealization> product,
		CandidateRealizationInputBinding binding) {
		CandidateRealizationSupportClause clause = product.stream()
			.flatMap(realization -> realization.supportClauses().stream())
			.filter(candidate -> candidate.inputBindings().equals(List.of(binding)))
			.findFirst().orElseThrow();
		return clause.proofDependencies().stream()
			.filter(candidate -> candidate.kind() == PlacementProofKind.NATIVE_CONTINUITY)
			.findFirst().orElseThrow();
	}

	private static PlacementProofKey proof(CompiledHopKey owner, RelocationActionKey action) {
		return new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
			owner, "relocation:" + action.normalizedSignature());
	}

	private static List<String> signatures(List<CandidateEmissionRealization> realizations) {
		return realizations.stream().map(CandidateEmissionRealization::normalizedSignature).toList();
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> generate(CompiledHopKey owner,
		CandidateEmissionFact emission, List<List<CandidateRealizationInputBinding>> choices,
		DurableAnchorKey output, boolean recomputes, DurableAnchorKey dynamic) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("generateRelocationBindingProduct",
			CompiledHopKey.class, CandidateEmissionFact.class, List.class, DurableAnchorKey.class,
			boolean.class, DurableAnchorKey.class, SearchSpaceMetrics.class);
		method.setAccessible(true);
		return (List<CandidateEmissionRealization>)method.invoke(null, owner, emission,
			choices, output, recomputes, dynamic, null);
	}

	private static CandidateEmissionFact derivedEmission(DurableAnchorKey output) {
		PlacementEmissionState state = new PlacementEmissionState(FOUT_STATE, true);
		CandidateRuleKey rule = new CandidateRuleKey(OWNER,
			List.of(CandidateInputState.present(FType.ROW)));
		DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
			OWNER, version("owner", 1), rule, LOCAL_STATE, FOUT_STATE, output,
			OWNER, FType.ROW, FType.ROW, REGION.normalizedSignature());
		return new CandidateEmissionFact(state, FType.ROW, action,
			List.of(CandidateEmissionRealization.durable(state, output, List.of(), List.of())));
	}

	private static CandidateRealizationInputBinding binding(String sourceId,
		RelocationActionKey action) {
		CandidateRuleKey rule = new CandidateRuleKey(key(sourceId), List.of());
		CandidateRealizationReference source = CandidateRealizationReference.of(rule,
			CandidateEmissionRealization.local(LOCAL_EMISSION));
		return CandidateRealizationInputBinding.relocation(0, source, action);
	}

	private static RelocationActionKey action(String id) {
		return new RelocationActionKey(version("source", 0), FOUT_STATE, FType.ROW,
			anchor(id), REGION.normalizedSignature(), List.of(OWNER));
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(4L, 2L)),
			new AnchorPartition("localhost:1235", List.of(4L, 0L), List.of(8L, 2L))));
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("relocation-proof-reuse", "main", "root", "compiled",
			REGION, id, id);
	}

	private static ValueVersionKey version(String id, int ordinal) {
		return new ValueVersionKey("relocation-proof-reuse", id, REGION, ordinal,
			VersionKind.ORDINARY, List.of());
	}
}
