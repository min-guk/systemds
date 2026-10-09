/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.junit.Assert;
import org.junit.Test;

public class NativeDerivedProofKindShortcutTest {
	@Test
	public void durableProofMismatchSkipsEveryNativeRelationShape() throws Exception {
		for(CandidateEmissionRealization prior : List.of(
			ordinary("proof-kind-ordinary", 16, 16),
			conditional("proof-kind-conditional", 8, 8),
			multiHeader("proof-kind-multi-header", 8, 8))) {
			NativeContinuitySupportClauses relation =
				(NativeContinuitySupportClauses)prior.supportClauses();
			PlacementProofKey durable = new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR,
				NativeContinuitySupportFixtureBridge.key("proof-kind-owner"), "derived-fout:exact");
			CandidateEmissionRealization template = explicit(prior, durable);

			Assert.assertEquals(0, relation.materializedHandleCount());
			Assert.assertEquals(List.of(template), retain(template, List.of(prior), durable));
			Assert.assertEquals("proof-kind mismatch must not construct a native member",
				0, relation.materializedHandleCount());
		}
	}

	@Test
	public void explicitDurableAndGenericNativeProofSemanticsStayExact() throws Exception {
		CandidateEmissionRealization nativePrior = ordinary("proof-kind-generic", 3, 2);
		NativeContinuitySupportClauses relation =
			(NativeContinuitySupportClauses)nativePrior.supportClauses();
		PlacementProofKey durable = new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR,
			NativeContinuitySupportFixtureBridge.key("proof-kind-explicit-owner"), "derived-fout:retain");
		CandidateEmissionRealization template = explicit(nativePrior, durable);
		CandidateEmissionRealization explicitPrior = explicit(nativePrior, durable);

		List<CandidateEmissionRealization> durableResult =
			retain(template, List.of(explicitPrior), durable);
		Assert.assertEquals(2, durableResult.size());
		Assert.assertEquals(List.of(durable),
			durableResult.get(1).supportClauses().get(0).proofDependencies());

		PlacementProofKey nativeProof = relation.get(0).proofDependencies().get(0);
		Assert.assertEquals(1, relation.materializedHandleCount());
		List<CandidateEmissionRealization> nativeResult =
			retain(template, List.of(nativePrior), nativeProof);
		Assert.assertEquals(2, nativeResult.size());
		Assert.assertEquals(List.of(nativeProof),
			nativeResult.get(1).supportClauses().get(0).proofDependencies());
		Assert.assertEquals("non-durable generic lookup retains its existing exact scan semantics",
			relation.size(), relation.materializedHandleCount());
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> retain(CandidateEmissionRealization template,
		List<CandidateEmissionRealization> previous, PlacementProofKey proof) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("retainDerivedOutputSupport",
			CandidateEmissionRealization.class, List.class, PlacementProofKey.class);
		method.setAccessible(true);
		return (List<CandidateEmissionRealization>)method.invoke(null, template, previous, proof);
	}

	private static CandidateEmissionRealization explicit(CandidateEmissionRealization authority,
		PlacementProofKey proof) {
		return new CandidateEmissionRealization(authority.key(),
			List.of(new CandidateRealizationSupportClause(List.of(proof), List.of())));
	}

	private static CandidateEmissionRealization ordinary(String id, int left, int right) {
		return NativeContinuitySupportFixtureBridge.realization(id, left, right);
	}

	private static CandidateEmissionRealization conditional(String id, int left, int right) {
		CandidateEmissionRealization base = ordinary(id, left, right);
		NativeContinuitySupportClauses relation =
			(NativeContinuitySupportClauses)base.supportClauses();
		List<List<PlacementIdentity.CandidateRealizationInputBinding>> excluded = new ArrayList<>();
		for(List<PlacementIdentity.CandidateRealizationInputBinding> axis : relation.commonAxes())
			excluded.add(List.of(axis.get(0)));
		NativeContinuitySupportClauses complement = NativeContinuitySupportClauses.exactComplement(
			NativeContinuitySupportFixtureBridge.key(id + "-consumer"), relation.product(), excluded,
			relation.clauseWitness(), relation.clauseLayoutExact());
		Assert.assertNotNull(complement);
		return new CandidateEmissionRealization(base.key(), complement);
	}

	private static CandidateEmissionRealization multiHeader(String id, int left, int right) {
		CandidateEmissionRealization base = ordinary(id, left, right);
		NativeContinuitySupportClauses relation =
			(NativeContinuitySupportClauses)base.supportClauses();
		CompiledHopKey owner = NativeContinuitySupportFixtureBridge.key(id + "-consumer");
		NativeContinuitySupportClauses first = new NativeContinuitySupportClauses(owner,
			relation.product(), relation.clauseWitness(), relation.clauseLayoutExact());
		DurableAnchorKey seed = pool(id + "-second-seed");
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(seed,
			relation.product().outputWorkerPoolWitness(), relation.product().exactPartitionRanges(),
			relation.commonAxes());
		Assert.assertNotNull(product);
		NativeContinuitySupportClauses second = new NativeContinuitySupportClauses(owner,
			product, relation.clauseWitness(), relation.clauseLayoutExact());
		return new CandidateEmissionRealization(base.key(),
			first.multiHeaderUnion(second).orElseThrow());
	}

	private static DurableAnchorKey pool(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(8L, 2L))));
	}
}
