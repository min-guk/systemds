/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.*;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class SearchSpaceObjectCreationMetricsTest {
	@Test
	public void countsRealConstructionsRatherThanLogicalMembersAndResetsBetweenAnalyses() {
		var region = new ControlRegionKey("objects", "main", List.of("root"), "root", "compiled");
		var owner = new CompiledHopKey("objects", "main", "root", "compiled", region, "op", "op");
		var row = CandidateInputState.present(FType.ROW);
		var absent = CandidateInputState.absentLocal();
		var header = new CandidateRuleRelation.Header(
			new CandidateCapabilityFact(OpCategory.QUATERNARY, "WSLOSS", ExecType.FED,
				FederatedOutput.LOUT, null, ReasonCode.OK, "", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), List.of(new CandidateEmissionFact(
				new PlacementEmissionState(new PlacementState(ExecType.FED,
					FederatedOutput.LOUT, FType.ROW, false), false), FType.ROW)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			var relation = new CandidateRuleRelation(owner, List.of(
				new CandidateRuleRelation.ConditionalRegion(List.of(List.of(row),
					List.of(absent, row), List.of(absent, row)), header)));
			Assert.assertEquals(4, relation.logicalSize().intValueExact());
			Assert.assertEquals(0, metrics.objectCreationSnapshot().candidateRuleFacts());
			var selected = List.of(row, absent, row);
			Assert.assertSame(relation.requireExact(selected), relation.requireExact(selected));
			Assert.assertEquals(1, metrics.objectCreationSnapshot().candidateRuleKeys());
			Assert.assertEquals(1, metrics.objectCreationSnapshot().candidateRuleFacts());
			new CandidateRealizationSupportClause(List.of(), List.of());
			Assert.assertEquals(1, metrics.objectCreationSnapshot().explicitSupportClauses());
			Assert.assertEquals(0, metrics.objectCreationSnapshot().indexedSupportHandles());
		}
		finally { PlacementIdentity.endAnalysisScope(); }
		metrics.reset();
		Assert.assertEquals(new SearchSpaceMetrics.ObjectCreationSnapshot(0, 0, 0, 0),
			metrics.objectCreationSnapshot());
	}
}
