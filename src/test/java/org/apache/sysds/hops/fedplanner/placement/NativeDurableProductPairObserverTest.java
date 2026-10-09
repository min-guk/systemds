/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeDurableProductPairObserverTest {
	@Test
	public void classifiesSameAndDistinctFullSeedsWithoutMaterializingMembers() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativeDurableProductPairObserver observer = new NativeDurableProductPairObserver(metrics);
		CompiledHopKey owner = key("owner");
		CompiledHopKey sourceOwner = key("source");
		CandidateRealizationInputBinding a = binding(0, "a", sourceOwner);
		CandidateRealizationInputBinding b = binding(0, "b", sourceOwner);
		DurableAnchorKey outputKey = rowPool("durable-output", "localhost:8801");
		DurableAnchorKey seedA = fullPool("seed-a", "localhost:8802");
		NativeContinuitySupportClauses first = relation(owner,
			seedA, rowPool("proof-output", "localhost:8803"),
			true, null, true, List.of(List.of(a, b)));
		NativeContinuitySupportClauses sameSeed = relation(owner,
			fullPool("seed-a", "localhost:8802"), rowPool("proof-output", "localhost:8803"),
			true, null, true, List.of(List.of(a, b)));
		NativeContinuitySupportClauses distinctSeed = relation(owner,
			fullPool("seed-b", "localhost:8802"), rowPool("proof-output", "localhost:8803"),
			true, null, true, List.of(List.of(a, b)));

		observer.beginEmission();
		observer.observe(owner, durable(outputKey, first));
		observer.observe(owner, durable(outputKey, sameSeed));
		observer.observe(owner, durable(outputKey, distinctSeed));
		observer.observe(owner, durable(outputKey, distinctSeed));

		Assert.assertEquals(4, work(metrics, "NATIVE_PAIR_OBSERVED"));
		Assert.assertEquals(1, work(metrics, "NATIVE_PAIR_FIRST"));
		Assert.assertEquals(1, work(metrics, "NATIVE_PAIR_SAME_SEED"));
		Assert.assertEquals("the first A exemplar remains immutable after observing B",
			2, work(metrics, "NATIVE_PAIR_DISTINCT_SEED"));
		Assert.assertEquals(8, work(metrics, "NATIVE_PAIR_LOGICAL_MEMBERS"));
		Assert.assertEquals(4, work(metrics, "NATIVE_PAIR_DISTINCT_SEED_LOGICAL_MEMBERS"));
		for(NativeContinuitySupportClauses clauses : List.of(first, sameSeed, distinctSeed))
			Assert.assertEquals("observation must retain descriptors without member handles",
				0, clauses.materializedHandleCount());
	}

	@Test
	public void identityAxesPrecedeSeedComparisonAndMetadataPrecedesAxes() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativeDurableProductPairObserver observer = new NativeDurableProductPairObserver(metrics);
		CompiledHopKey owner = key("owner");
		CandidateRealizationInputBinding a = binding(0, "a", key("source"));
		CandidateRealizationInputBinding equalDistinctA = directLike(a);
		Assert.assertEquals(a, equalDistinctA);
		Assert.assertNotSame(a, equalDistinctA);
		DurableAnchorKey seed = fullPool("seed", "localhost:8811");
		DurableAnchorKey proofOutput = rowPool("proof", "localhost:8812");
		DurableAnchorKey outputKey = rowPool("durable", "localhost:8814");
		NativeContinuitySupportClauses first = relation(owner, seed, proofOutput,
			true, null, true, List.of(List.of(a)));
		NativeContinuitySupportClauses changedAxes = relation(owner,
			fullPool("other-seed", "localhost:8815"), proofOutput,
			true, null, true, List.of(List.of(equalDistinctA)));
		CompiledHopKey foreignOwner = cloneKey(owner);
		NativeContinuitySupportClauses changedOwner = relation(foreignOwner, seed, proofOutput,
			true, null, true, List.of(List.of(a)));

		observer.beginEmission();
		observer.observe(owner, durable(outputKey, first));
		observer.observe(owner, durable(outputKey, changedAxes));
		observer.observe(foreignOwner, durable(outputKey, changedOwner));
		Assert.assertEquals(1, work(metrics, "NATIVE_PAIR_AXES_CHANGED"));
		Assert.assertEquals(1, work(metrics, "NATIVE_PAIR_METADATA_CHANGED"));
		Assert.assertEquals("axes identity differs before the distinct seed can classify",
			0, work(metrics, "NATIVE_PAIR_DISTINCT_SEED"));
		Assert.assertEquals(0, first.materializedHandleCount());
		Assert.assertEquals(0, changedAxes.materializedHandleCount());
		Assert.assertEquals(0, changedOwner.materializedHandleCount());
	}

	@Test
	public void everyValidDurableHeaderFieldParticipatesInPairClassification() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativeDurableProductPairObserver observer = new NativeDurableProductPairObserver(metrics);
		CompiledHopKey owner = key("metadata-owner");
		CandidateRealizationInputBinding binding = binding(0, "metadata", key("metadata-source"));
		DurableAnchorKey seed = fullPool("metadata-seed", "localhost:8821");
		DurableAnchorKey proof = rowPool("metadata-proof", "localhost:8822");
		DurableAnchorKey outputKey = rowPool("metadata-output", "localhost:8824");
		NativeContinuitySupportClauses baseline = relation(owner, seed, proof,
			true, null, true, List.of(List.of(binding)));
		observer.beginEmission();
		observer.observe(owner, durable(outputKey, baseline));
		observer.observe(owner, durable(outputKey, relation(owner, seed,
			rowPool("other-proof", "localhost:8825"), true, null, true, List.of(List.of(binding)))));
		observer.observe(owner, durable(outputKey, relation(owner, seed, proof,
			false, null, true, List.of(List.of(binding)))));
		CompiledHopKey foreignOwner = cloneKey(owner);
		observer.observe(foreignOwner, durable(outputKey, relation(foreignOwner, seed, proof,
			true, null, true, List.of(List.of(binding)))));
		Assert.assertEquals(3, work(metrics, "NATIVE_PAIR_METADATA_CHANGED"));
		Assert.assertEquals(4, work(metrics, "NATIVE_PAIR_OBSERVED"));
	}

	@Test
	public void impossibleDurableClauseMetadataIsRejectedBeforeObservation() {
		CompiledHopKey owner = key("invalid-clause-owner");
		CandidateRealizationInputBinding binding =
			binding(0, "invalid-clause", key("invalid-clause-source"));
		DurableAnchorKey seed = fullPool("invalid-clause-seed", "localhost:8827");
		DurableAnchorKey proof = rowPool("invalid-clause-proof", "localhost:8828");
		DurableAnchorKey clause = rowPool("invalid-clause-witness", "localhost:8829");
		DurableAnchorKey output = rowPool("invalid-clause-output", "localhost:8830");
		Assert.assertThrows(IllegalArgumentException.class, () -> durable(output,
			relation(owner, seed, proof, true, clause, true, List.of(List.of(binding)))));
		Assert.assertThrows(IllegalArgumentException.class, () -> durable(output,
			relation(owner, seed, proof, true, clause, false, List.of(List.of(binding)))));
		Assert.assertThrows(IllegalArgumentException.class, () ->
			relation(owner, seed, proof, true, null, false, List.of(List.of(binding))));
	}

	@Test
	public void emissionResetClearsExemplarsButObservationBudgetRemainsCumulative() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativeDurableProductPairObserver observer =
			new NativeDurableProductPairObserver(metrics, 4, 2, 16, 16);
		CompiledHopKey owner = key("reset-owner");
		CandidateEmissionRealization publication = durable(rowPool("reset-output", "localhost:8831"),
			relation(owner, fullPool("reset-seed", "localhost:8832"),
				rowPool("reset-proof", "localhost:8833"), true,
				null, true,
				List.of(List.of(binding(0, "reset", key("reset-source"))))));

		observer.beginEmission();
		observer.observe(owner, publication);
		observer.beginEmission();
		observer.observe(owner, publication);
		observer.beginEmission();
		observer.observe(owner, publication);
		Assert.assertEquals("each new emission starts without a cross-emission exemplar",
			2, work(metrics, "NATIVE_PAIR_FIRST"));
		Assert.assertEquals(1, work(metrics, "NATIVE_PAIR_BUDGET_UNKNOWN"));
		Assert.assertEquals(3, work(metrics, "NATIVE_PAIR_OBSERVED"));
	}

	@Test
	public void emissionResetDoesNotRestoreKeyOptionOrAxisComparisonBudgets() {
		CompiledHopKey owner = key("reset-budgets-owner");
		CandidateEmissionRealization publication = durable(
			rowPool("reset-budgets-output", "localhost:8835"),
			relation(owner, fullPool("reset-budgets-seed", "localhost:8836"),
				rowPool("reset-budgets-proof", "localhost:8837"), true,
				null, true,
				List.of(List.of(binding(0, "reset-budgets", key("reset-budgets-source"))))));

		SearchSpaceMetrics keyMetrics = new SearchSpaceMetrics();
		NativeDurableProductPairObserver keyBound =
			new NativeDurableProductPairObserver(keyMetrics, 1, 8, 8, 8);
		keyBound.beginEmission();
		keyBound.observe(owner, publication);
		keyBound.beginEmission();
		keyBound.observe(owner, publication);
		Assert.assertEquals(1, work(keyMetrics, "NATIVE_PAIR_FIRST"));
		Assert.assertEquals(1, work(keyMetrics, "NATIVE_PAIR_BUDGET_UNKNOWN"));

		SearchSpaceMetrics optionMetrics = new SearchSpaceMetrics();
		NativeDurableProductPairObserver optionBound =
			new NativeDurableProductPairObserver(optionMetrics, 8, 8, 1, 8);
		optionBound.beginEmission();
		optionBound.observe(owner, publication);
		optionBound.beginEmission();
		optionBound.observe(owner, publication);
		Assert.assertEquals(1, work(optionMetrics, "NATIVE_PAIR_FIRST"));
		Assert.assertEquals(1, work(optionMetrics, "NATIVE_PAIR_BUDGET_UNKNOWN"));

		SearchSpaceMetrics axisMetrics = new SearchSpaceMetrics();
		NativeDurableProductPairObserver axisBound =
			new NativeDurableProductPairObserver(axisMetrics, 8, 8, 8, 3);
		axisBound.beginEmission();
		axisBound.observe(owner, publication);
		axisBound.observe(owner, publication);
		axisBound.beginEmission();
		axisBound.observe(owner, publication);
		axisBound.observe(owner, publication);
		Assert.assertEquals(2, work(axisMetrics, "NATIVE_PAIR_FIRST"));
		Assert.assertEquals(1, work(axisMetrics, "NATIVE_PAIR_SAME_SEED"));
		Assert.assertEquals(1, work(axisMetrics, "NATIVE_PAIR_BUDGET_UNKNOWN"));
	}

	@Test
	public void everyBoundFailsClosedAndPartialAxisComparisonIsUnknown() {
		SearchSpaceMetrics keyMetrics = new SearchSpaceMetrics();
		assertBudgetUnknown(keyMetrics, new NativeDurableProductPairObserver(keyMetrics,
			0, 8, 8, 8), "key-cap");
		SearchSpaceMetrics observationMetrics = new SearchSpaceMetrics();
		assertBudgetUnknown(observationMetrics, new NativeDurableProductPairObserver(observationMetrics,
			8, 0, 8, 8), "observation-cap");
		SearchSpaceMetrics optionMetrics = new SearchSpaceMetrics();
		assertBudgetUnknown(optionMetrics, new NativeDurableProductPairObserver(optionMetrics,
			8, 8, 0, 8), "option-cap");

		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativeDurableProductPairObserver observer =
			new NativeDurableProductPairObserver(metrics, 8, 8, 8, 1);
		CompiledHopKey owner = key("comparison-owner");
		CandidateRealizationInputBinding first = binding(0, "first", key("comparison-first"));
		CandidateRealizationInputBinding second = binding(1, "second", key("comparison-second"));
		DurableAnchorKey outputKey = rowPool("comparison-output", "localhost:8841");
		NativeContinuitySupportClauses clauses = relation(owner,
			fullPool("comparison-seed", "localhost:8842"),
			rowPool("comparison-proof", "localhost:8843"), true,
			null, true,
			List.of(List.of(first), List.of(second)));
		observer.beginEmission();
		observer.observe(owner, durable(outputKey, clauses));
		observer.observe(owner, durable(outputKey, clauses));
		Assert.assertEquals("a partial axes comparison cannot claim same authority",
			1, work(metrics, "NATIVE_PAIR_BUDGET_UNKNOWN"));
		Assert.assertEquals(0, work(metrics, "NATIVE_PAIR_SAME_SEED"));
	}

	@Test
	public void ignoresNonDurableAndNonNativeRealizations() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativeDurableProductPairObserver observer = new NativeDurableProductPairObserver(metrics);
		CompiledHopKey owner = key("ignored-owner");
		NativeContinuitySupportClauses relation = relation(owner,
			fullPool("ignored-seed", "localhost:8851"),
			rowPool("ignored-proof", "localhost:8852"), true,
			null, true,
			List.of(List.of(binding(0, "ignored", key("ignored-source")))));
		CandidateEmissionRealization nativeOutput = new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission(), "not-durable"), relation);
		CandidateEmissionRealization ordinaryDurable = CandidateEmissionRealization.durable(
			emission(), rowPool("ordinary", "localhost:8854"), List.of(), List.of());
		observer.beginEmission();
		observer.observe(owner, nativeOutput);
		observer.observe(owner, ordinaryDurable);
		Assert.assertEquals(0, work(metrics, "NATIVE_PAIR_OBSERVED"));
		Assert.assertEquals(0, relation.materializedHandleCount());
	}

	private static void assertBudgetUnknown(SearchSpaceMetrics metrics,
		NativeDurableProductPairObserver observer, String id) {
		CompiledHopKey owner = key(id + "-owner");
		NativeContinuitySupportClauses relation = relation(owner,
			fullPool(id + "-seed", "localhost:8861"),
			rowPool(id + "-proof", "localhost:8862"), true,
			null, true,
			List.of(List.of(binding(0, id, key(id + "-source")))));
		observer.beginEmission();
		observer.observe(owner, durable(rowPool(id + "-output", "localhost:8864"), relation));
		Assert.assertEquals(1, work(metrics, "NATIVE_PAIR_OBSERVED"));
		Assert.assertEquals(1, work(metrics, "NATIVE_PAIR_BUDGET_UNKNOWN"));
		Assert.assertEquals(0, relation.materializedHandleCount());
	}

	private static NativeContinuitySupportClauses relation(CompiledHopKey owner,
		DurableAnchorKey seed, DurableAnchorKey proofOutput, boolean proofExact,
		DurableAnchorKey clauseOutput, boolean clauseExact,
		List<List<CandidateRealizationInputBinding>> axes) {
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, proofOutput, proofExact, axes);
		Assert.assertNotNull(product);
		return new NativeContinuitySupportClauses(owner, product, clauseOutput, clauseExact);
	}

	private static CandidateEmissionRealization durable(DurableAnchorKey output,
		NativeContinuitySupportClauses clauses) {
		return new CandidateEmissionRealization(
			PlacementRealizationKey.durable(emission(), output), clauses);
	}

	private static CandidateRealizationInputBinding binding(int position, String name,
		CompiledHopKey owner) {
		CandidateRuleKey rule = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW)));
		return CandidateRealizationInputBinding.direct(position,
			new CandidateRealizationReference(rule,
				PlacementRealizationKey.nativeLineage(emission(), "source-" + name)));
	}

	private static CandidateRealizationInputBinding directLike(
		CandidateRealizationInputBinding binding) {
		return CandidateRealizationInputBinding.direct(binding.inputPosition(),
			new CandidateRealizationReference(binding.source().rule(), binding.source().realization()));
	}

	private static long work(SearchSpaceMetrics metrics, String name) {
		return metrics.directWorkCount(Enum.valueOf(SearchSpaceMetrics.DirectWork.class, name));
	}

	private static PlacementEmissionState emission() {
		return new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
	}

	private static DurableAnchorKey rowPool(String id, String worker) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition(worker, List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static DurableAnchorKey fullPool(String id, String worker) {
		return new DurableAnchorKey(id, FType.FULL, List.of(
			new AnchorPartition(worker, List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"native-pair-observer", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("native-pair-observer", "main", name, "compiled",
			region, name, name);
	}

	private static CompiledHopKey cloneKey(CompiledHopKey key) {
		return new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
			key.callSitePath(), key.recompileContext(), key.controlRegion(),
			key.emittedHopInstance(), key.canonicalSourceOrigin());
	}
}
