/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NativeContinuitySupportFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

/** Cost metadata must not request exact members of an already certified native pool. */
public class ExactNativeSupportCostMetadataTest {
	@Test
	public void inputLayoutPreservesExactAndDynamicRangesWithoutMemberAllocation() {
		for(boolean exact : List.of(false, true)) {
			var relation = nativeRelation("layout-" + exact, 20, 2, exact);
			var reference = nativeRelation("layout-" + exact, 20, 2, exact);
			var explicit = new CandidateEmissionRealization(reference.key(),
				List.copyOf(reference.supportClauses()));
			Assert.assertEquals(ExactPhysicalCostModel.realizationInputLayout(explicit),
				ExactPhysicalCostModel.realizationInputLayout(relation));
			Assert.assertEquals(0, relation.fullyMaterializedSupportClauseCount());
		}
	}

	@Test
	public void singletonCertificateHandlesMillionMemberNativePoolWithoutExpansion() {
		var relation = nativeRelation("million", 1000, 1, false);
		Assert.assertEquals(1_000_000, relation.supportClauses().size());
		Assert.assertTrue(ExactPhysicalCostModel.singletonWorkerProofForTest(
			analysis(), model(alternative(relation, null)), 1));
		Assert.assertEquals(0, relation.fullyMaterializedSupportClauseCount());
	}

	@Test
	public void singletonCertificateRejectsMultipleWorkersWithoutExpansion() {
		var relation = nativeRelation("two-workers", 20, 2, false);
		Assert.assertFalse(ExactPhysicalCostModel.singletonWorkerProofForTest(
			analysis(), model(alternative(relation, null)), 1));
		Assert.assertEquals(0, relation.fullyMaterializedSupportClauseCount());
	}

	@Test
	public void recursiveWorkerCountUsesUniformNativePoolAndPreservesFallbackBehavior() {
		for(int workers : List.of(1, 3)) {
			var source = nativeRelation("recursive-" + workers, 20, workers, false);
			var sourceRule = new CandidateRuleKey(
				NativeContinuitySupportFixtureBridge.key("source-" + workers), List.of());
			var reference = CandidateRealizationReference.of(sourceRule, source);
			var clause = new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, reference)));
			var root = CandidateEmissionRealization.valueMap(source.key().emissionState(),
				"root-" + workers, List.of(clause));
			var analysis = analysis();
			when(analysis.requireExactCandidateRealization(any())).thenReturn(source);
			Assert.assertEquals(workers, ExactPhysicalCostModel.realizationWorkerCount(
				analysis, alternative(root, root.supportClauses().get(0)), 7));
			Assert.assertEquals(0, source.fullyMaterializedSupportClauseCount());
		}
	}

	private static CandidateEmissionRealization nativeRelation(
		String name, int width, int workers, boolean exact) {
		var template = NativeContinuitySupportFixtureBridge.realization(name, width, width);
		List<AnchorPartition> ranges = new ArrayList<>();
		for(int worker = 0; worker < workers; worker++)
			ranges.add(new AnchorPartition("worker-" + worker + ":1234/data",
				List.of((long)worker * 8, 0L), List.of((long)(worker + 1) * 8, 2L)));
		var pool = new DurableAnchorKey(name + "-pool", FType.ROW, ranges);
		return NativeContinuitySupportFixtureBridge.nativeRelation(template.key(),
			NativeContinuitySupportFixtureBridge.key(name + "-owner"), pool, pool, exact,
			template.nativeContinuitySupportProduct().orElseThrow().axes());
	}

	private static PlacementAnalysis analysis() {
		var analysis = mock(PlacementAnalysis.class);
		var graph = mock(NeutralPlacementGraph.class);
		when(analysis.graph()).thenReturn(graph);
		when(graph.nodes()).thenReturn(List.of());
		when(graph.relocationActions()).thenReturn(List.of());
		when(graph.derivedFoutMaterializationActions()).thenReturn(List.of());
		return analysis;
	}

	private static ExactPhysicalModel model(ExactPhysicalModel.Alternative alternative) {
		var model = mock(ExactPhysicalModel.class);
		var domain = mock(ExactPhysicalModel.DecisionDomain.class);
		when(domain.alternatives()).thenReturn(List.of(alternative));
		when(model.domains()).thenReturn(List.of(domain));
		return model;
	}

	private static ExactPhysicalModel.Alternative alternative(CandidateEmissionRealization realization,
		CandidateRealizationSupportClause clause) {
		var alternative = mock(ExactPhysicalModel.Alternative.class);
		when(alternative.realization()).thenReturn(realization);
		when(alternative.supportClause()).thenReturn(clause);
		when(alternative.inputAuthorities()).thenReturn(List.of());
		return alternative;
	}
}
