/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Storage-native dynamic worker-layout detection contract. */
public class NativeDynamicLayoutRelationViewTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"native-dynamic-layout", "main", List.of("main"), "main", "compiled");
	private static final CompiledHopKey OWNER = new CompiledHopKey(
		"native-dynamic-layout", "main", "main", "compiled", REGION, "owner", "owner");
	private static final PlacementState STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(STATE, false);
	private static final DurableAnchorKey POOL = new DurableAnchorKey("pool", FType.ROW,
		List.of(new AnchorPartition("worker-0", List.of(0L, 0L), List.of(4L, 4L))));
	private static final PlacementProofKey CONTINUITY = new PlacementProofKey(
		PlacementProofKind.NATIVE_CONTINUITY, OWNER, "pool-continuity");
	private static final CandidateRealizationInputBinding BINDING = binding();

	@Test
	public void explicitFactorizedAndIndexedMetadataHaveExactParityWithoutMaterialization() {
		for(Metadata metadata : Metadata.values()) {
			CandidateRealizationSupportClause clause = clause(metadata);
			CandidateEmissionRealization explicit = explicit(List.of(clause), "explicit-" + metadata);
			CandidateEmissionRealization factorized = factorized(metadata);
			CandidateEmissionRealization indexed = explicit.withIndexedSupport();

			boolean expected = metadata == Metadata.DYNAMIC;
			Assert.assertEquals(expected,
				NativePlacementContinuity.hasDynamicNativeLayout(explicit.supportClauses()));
			Assert.assertEquals(expected,
				NativePlacementContinuity.hasDynamicNativeLayout(factorized.supportClauses()));
			Assert.assertEquals(expected,
				NativePlacementContinuity.hasDynamicNativeLayout(indexed.supportClauses()));
			Assert.assertEquals(0,
				((FactorizedSupportClauses)factorized.supportClauses()).materializedClauseCount());
			Assert.assertEquals(0,
				((IndexedSupportClauses)indexed.supportClauses()).materializedHandleCount());
		}
	}

	@Test
	public void sparseMixedIndexedMetadataScansRowsWithoutCreatingHandles() {
		List<CandidateRealizationSupportClause> explicit = List.of(
			clause(Metadata.NONE), clause(Metadata.EXACT), clause(Metadata.DYNAMIC))
			.stream().sorted().toList();
		IndexedSupportClauses indexed = IndexedSupportClauses.fromCanonical(explicit);
		Assert.assertTrue(NativePlacementContinuity.hasDynamicNativeLayout(explicit));
		Assert.assertTrue(NativePlacementContinuity.hasDynamicNativeLayout(indexed));
		Assert.assertEquals(0, indexed.materializedHandleCount());
	}

	@Test
	public void thousandMemberProductMatchesSeparateExplicitReferenceWithoutMaterialization() {
		List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>();
		for(int axis = 0; axis < 3; axis++) {
			List<CandidateRealizationInputBinding> options = new ArrayList<>();
			for(int option = 0; option < 10; option++)
				options.add(binding(axis, "axis-" + axis + "-option-" + option));
			axes.add(List.copyOf(options));
		}
		CandidateEmissionRealization product = CandidateEmissionRealization.factorized(
			PlacementRealizationKey.nativeLineage(EMISSION, "thousand-product"),
			List.of(CONTINUITY), axes, POOL, false);
		FactorizedSupportClauses stored = (FactorizedSupportClauses)product.supportClauses();
		Assert.assertEquals(1_000, stored.size());
		Assert.assertEquals(0, stored.materializedClauseCount());

		List<CandidateRealizationSupportClause> explicit = new ArrayList<>(1_000);
		for(var left : axes.get(0))
			for(var middle : axes.get(1))
				for(var right : axes.get(2))
					explicit.add(new CandidateRealizationSupportClause(List.of(CONTINUITY),
						List.of(left, middle, right), POOL, false));
		explicit = explicit.stream().sorted().toList();
		Assert.assertEquals(
			NativePlacementContinuity.hasDynamicNativeLayout(explicit),
			NativePlacementContinuity.hasDynamicNativeLayout(product.supportClauses()));
		Assert.assertTrue(NativePlacementContinuity.hasDynamicNativeLayout(product.supportClauses()));
		Assert.assertEquals(0, stored.materializedClauseCount());
		Assert.assertFalse(NativePlacementContinuity.hasDynamicNativeLayout(List.of()));
	}

	@Test
	public void tenThousandMemberNativeProductReadsUniformLayoutMetadataWithoutHandles() {
		CandidateEmissionRealization template =
			NativeContinuitySupportFixtureBridge.realization("native-layout-metadata", 100, 100);
		var axes = template.nativeContinuitySupportProduct().orElseThrow().axes();
		for(boolean exact : List.of(false, true)) {
			CandidateEmissionRealization relation =
				NativeContinuitySupportFixtureBridge.nativeRelation(template.key(), OWNER,
					POOL, POOL, exact, axes);
			Assert.assertEquals(!exact,
				NativePlacementContinuity.hasDynamicNativeLayout(relation.supportClauses()));
			Assert.assertEquals(10_000, relation.supportClauses().size());
			Assert.assertEquals(0,
				NativeContinuitySupportFixtureBridge.materialized(relation));
		}
	}

	private static CandidateEmissionRealization explicit(
		List<CandidateRealizationSupportClause> clauses, String id) {
		return new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(EMISSION, id), clauses);
	}

	private static CandidateEmissionRealization factorized(Metadata metadata) {
		DurableAnchorKey witness = metadata == Metadata.NONE ? null : POOL;
		boolean exact = metadata != Metadata.DYNAMIC;
		return CandidateEmissionRealization.factorized(
			PlacementRealizationKey.nativeLineage(EMISSION, "factorized-" + metadata),
			witness == null ? List.of() : List.of(CONTINUITY),
			List.of(List.of(BINDING)), witness, exact);
	}

	private static CandidateRealizationSupportClause clause(Metadata metadata) {
		DurableAnchorKey witness = metadata == Metadata.NONE ? null : POOL;
		return new CandidateRealizationSupportClause(
			witness == null ? List.of() : List.of(CONTINUITY), List.of(BINDING), witness,
			metadata != Metadata.DYNAMIC);
	}

	private static CandidateRealizationInputBinding binding() {
		return binding(0, "shared", OWNER);
	}

	private static CandidateRealizationInputBinding binding(int position, String id) {
		return binding(position, id, new CompiledHopKey(
			"native-dynamic-layout", "main", "main", "compiled", REGION,
			"axis-owner-" + position, "axis-owner-" + position));
	}

	private static CandidateRealizationInputBinding binding(
		int position, String id, CompiledHopKey sourceOwner) {
		CandidateEmissionRealization source = CandidateEmissionRealization.durable(
			EMISSION, new DurableAnchorKey("source-" + id, FType.ROW, POOL.partitions()),
			List.of(), List.of());
		CandidateRuleKey rule = new CandidateRuleKey(sourceOwner,
			List.of(CandidateInputState.present(FType.ROW)));
		return CandidateRealizationInputBinding.direct(position,
			CandidateRealizationReference.of(rule, source));
	}

	private enum Metadata { NONE, EXACT, DYNAMIC }
}
