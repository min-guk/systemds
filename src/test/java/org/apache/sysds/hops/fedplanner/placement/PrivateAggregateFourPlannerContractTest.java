/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.FTypes.FederatedPlanner;
import org.apache.sysds.hops.ipa.FederatedPlannerFactory;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAll.FedAllInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAllMaxFedFoutSinglePass;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.FederatedPlanExact;
import org.apache.sysds.hops.fedplanner.fedHeuristic.FederatedPlannerFedHeuristic.HeuristicInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedHeuristic.FederatedPlannerFedHeuristicSinglePass;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Verifies that all production selectors consume one pre-filtered private-aggregate domain. */
public class PrivateAggregateFourPlannerContractTest {
	private static final String FEDERATED_SOURCE =
		"A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n";
	private static final String CONTROL_FLOW_PROGRAM =
		"f=function(matrix[double] X) return (matrix[double] Y){Y=X+1;}\n"
			+ FEDERATED_SOURCE
			+ "B=A+1;i=1;while(i<=2){if(i>0){B=f(B);}else{B=B+2;}i=i+1;}"
			+ "print(sum(B));\n";
	private static final String NON_REPLAYABLE_MATRIX_CFG_PROGRAM = FEDERATED_SOURCE
		+ "if(sum(A)>0){B=colSums(A);}else{B=colSums(cbind(A,A));}C=B+1;print(sum(C));\n";

	@Test
	public void fourPlannersKeepPrivateSingleWorkerOneHotExpansionRemote() throws Exception {
		String source = "A=federated(addresses=list(\"localhost:1234/labels\"),"
			+ "ranges=list(list(0,0),list(4,1)));\n";
		String domain = null;
		try {
			for(PlannerKind planner : PlannerKind.values()) {
				FederatedPlannerUtils.resetFederatedPlannerRunState();
				// Exercise the static one-hot rewrite without requiring remote shape
				// acquisition before shared analysis, and retain per-category counts.
				DMLProgram program = compile(source
					+ "B=outer(A,t(seq(1,3)),\"==\");C=colSums(B);print(sum(C*C));\n");
				new DMLTranslator(program).rewriteHopsDAG(program);
				PlannedProgram plan = planFresh(planner, program, false);
				if(domain == null)
					domain = plan.analysis().analysisFingerprint();
				Assert.assertEquals(planner.name(), domain, plan.analysis().analysisFingerprint());
				var expansions = plan.analysis().compiledHopOccurrences().stream()
					.filter(occurrence -> occurrence.hop() instanceof ParameterizedBuiltinOp builtin
						&& builtin.getOp() == org.apache.sysds.common.Types.ParamBuiltinOp.REXPAND).toList();
				Assert.assertFalse("Fixture must exercise the production one-hot-to-REXPAND rewrite", expansions.isEmpty());
				for(var expansion : expansions) {
					Assert.assertEquals(planner.name(), Privacy.PRIVATE_AGGREGATE,
						plan.analysis().requirePrivacy(expansion.key()));
					var selected = plan.result().selectedStates().get(expansion.key());
					assertProtectedRemote(planner, selected);
					Assert.assertEquals(planner.name(), org.apache.sysds.hops.fedplanner.FTypes.FType.FULL,
						selected.fType());
					Assert.assertTrue("One-hot encoding must not release row-level labels",
						plan.analysis().graph().node(expansion.key()).orElseThrow().legalAlternatives().stream()
							.allMatch(state -> state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT));
				}
				Assert.assertTrue("A protected expansion needs neither collection nor relocation",
					plan.result().selectedRelocations().isEmpty());
				Assert.assertTrue("A protected expansion must not have a local materialization",
					plan.result().selectedLocalMaterializations().isEmpty());
			}
		}
		finally {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	@Test
	public void fourPlannersConsumeTheSameFilteredPrivateAggregateDomain() throws Exception {
		List<PlannedProgram> plans = new ArrayList<>();
		for(PlannerKind planner : PlannerKind.values())
			plans.add(planFresh(planner));

		String expectedDomain = plans.get(0).analysis().analysisFingerprint();
		for(PlannedProgram plan : plans) {
			PlacementAnalysis analysis = plan.analysis();
			NormalizedPlannerResult result = plan.result();
			Assert.assertEquals(plan.planner().name(), expectedDomain, analysis.analysisFingerprint());
			Assert.assertSame(plan.planner().name(), analysis, result.analysis());
			Assert.assertEquals(plan.planner().name(), analysis.analysisFingerprint(), result.analysisFingerprint());
			Assert.assertTrue(plan.planner().name() + " lost protected loop-body placement",
				analysis.graph().decisionNodes().stream().anyMatch(node ->
					node.key().controlRegion().regionPath().stream().anyMatch(path -> path.contains("loop-body"))
						&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE));
			Assert.assertTrue(plan.planner().name() + " lost protected branch placement",
				analysis.graph().decisionNodes().stream().anyMatch(node ->
					node.key().controlRegion().regionPath().stream().anyMatch(path -> path.contains("branch-"))
						&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE));

			var sum = analysis.compiledHopOccurrences().stream()
				.filter(occurrence -> occurrence.hop() instanceof AggUnaryOp aggregate
					&& aggregate.getOp() == AggOp.SUM)
				.findFirst().orElseThrow();
			Assert.assertEquals(plan.planner().name(), Privacy.PUBLIC, analysis.requirePrivacy(sum.key()));
			PlacementState released = result.selectedStates().get(sum.key());
			Assert.assertNotNull(plan.planner().name() + " omitted the safe aggregate", released);
			Assert.assertEquals(plan.planner().name(), ExecType.FED, released.execType());
			Assert.assertEquals(plan.planner().name(), FederatedOutput.LOUT, released.output());

			long protectedFormals = analysis.graph().nodes().stream()
				.filter(node -> node.kind() == NodeKind.FUNCTION_INPUT || node.kind() == NodeKind.FUNCTION_OUTPUT)
				.filter(node -> analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE)
				.peek(node -> assertProtectedFunctionBoundary(plan.planner(), node))
				.count();
			Assert.assertTrue(plan.planner().name() + " lost protected function boundaries",
				protectedFormals >= 2);

			analysis.graph().decisionNodes().stream()
				.filter(node -> analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE)
				.filter(node -> node.kind() != NodeKind.FUNCTION_CALL)
				.forEach(node -> assertProtectedRemote(plan.planner(), result.selectedStates().get(node.key())));
			Assert.assertTrue(plan.planner().name() + " selected an active relocation of protected data",
				result.selectedRelocations().stream().noneMatch(action -> analysis.occurrences().stream()
					.anyMatch(occurrence -> analysis.graph().node(occurrence.key()).orElseThrow().valueVersion()
						.equals(action.sourceValueVersion())
						&& analysis.requirePrivacy(occurrence.key()) == Privacy.PRIVATE_AGGREGATE)));
		}
	}

	@Test
	public void legacyDpPreservesLogicalValueAuthorityForBranchPhiReads() throws Exception {
		PlannedProgram plan = planFresh(PlannerKind.DP, FEDERATED_SOURCE
			+ "if(sum(A)>0){B=A+1;}else{B=cbind(A,A);}C=B+1;print(sum(C));\n", true);
		var analysis = plan.analysis();
		var read = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD
				&& "B".equals(data.getName()))
			.filter(occurrence -> analysis.cfgDefinitionSourcesInCanonicalOrder(occurrence.key()).size() == 2)
			.findFirst().orElseThrow();
		Assert.assertNotEquals("Fixture must distinguish semantic phi kind from the compiled TRead operation",
			NodeKind.TRANSIENT_READ, analysis.graph().node(read.key()).orElseThrow().kind());
		var sources = analysis.logicalTransientInputsInCanonicalOrder().stream()
			.filter(fact -> fact.targetRead() == read.key()).toList();
		Assert.assertEquals("Both native reaching definitions must retain their logical authority", 2, sources.size());
		DpInvocationReceipt receipt = (DpInvocationReceipt) plan.receipt();
		var snapshot = receipt.semanticConsumption().semanticBlock().candidateSnapshots().stream()
			.filter(candidate -> candidate.parentOccurrence() == read.key() && candidate.logicalEntries().size() == 2)
			.findFirst().orElseThrow();
		Assert.assertTrue(snapshot.transientForwardDependencies().isEmpty());
		Assert.assertTrue(snapshot.cfgTransientDependencies().isEmpty());
		for(var source : sources)
			Assert.assertTrue("The receipt must consume the exact analysis-owned logical fact",
				snapshot.logicalEntries().stream().anyMatch(entry -> entry.fact() == source));
		assertProtectedRemote(PlannerKind.DP, plan.result().selectedStates().get(read.key()));
	}

	@Test
	public void legacyDpConsumesExactCfgDefinitionsWhenLogicalReplayIsUnavailable() throws Exception {
		PlannedProgram plan = planFresh(PlannerKind.DP, NON_REPLAYABLE_MATRIX_CFG_PROGRAM, true);
		PlacementAnalysis analysis = plan.analysis();
		var read = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getDataType().isMatrix()
				&& "B".equals(data.getName())
				&& data.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD)
			.filter(occurrence -> !analysis.cfgDefinitionSourcesInCanonicalOrder(occurrence.key()).isEmpty())
			.filter(occurrence -> analysis.logicalTransientInputsInCanonicalOrder().stream()
				.noneMatch(fact -> fact.targetRead() == occurrence.key()))
			.findFirst().orElseThrow(() -> new AssertionError(
				"fixture lost the non-replayable CFG transient boundary"));
		List<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey> sources =
			analysis.cfgDefinitionSourcesInCanonicalOrder(read.key());
		Assert.assertFalse(sources.isEmpty());
		PlacementState readState = plan.result().selectedStates().get(read.key());
		Assert.assertNotNull("DP omitted the CFG-backed transient read", readState);
		DpInvocationReceipt dp = (DpInvocationReceipt) plan.receipt();
		var snapshot = dp.semanticConsumption().semanticBlock().candidateSnapshots().stream()
			.filter(candidate -> candidate.parentOccurrence() == read.key())
			.filter(candidate -> !candidate.cfgTransientDependencies().isEmpty())
			.findFirst().orElseThrow(() -> new AssertionError("DP omitted the typed CFG dependency receipt"));
		Assert.assertTrue("CFG alternatives leaked into physical kernel oracle arity",
			snapshot.orderedOracleInputs().isEmpty());
		Assert.assertEquals(sources, snapshot.cfgTransientDependencies().stream()
			.map(dependency -> dependency.sourceOccurrence()).toList());
		Assert.assertTrue("fixture must exercise an all-definitions CFG join", sources.size() > 1);
		Assert.assertThrows("A partial CFG receipt must fail closed", IllegalArgumentException.class,
			() -> new DpPlacementAdapter.CandidateOccurrenceSnapshot(snapshot.context(),
				snapshot.parentOccurrence(), snapshot.rawEntries(), snapshot.promotedEntries(),
				snapshot.logicalEntries(), snapshot.transientForwardDependencies(),
				snapshot.cfgTransientDependencies().subList(0, 1), snapshot.functionOutputDependencies(),
				snapshot.orderedOracleInputs(), snapshot.disposition(), snapshot.reasonCode()));
		var firstDependency = snapshot.cfgTransientDependencies().get(0);
		var copiedConstraint = new NeutralPlacementGraph.Constraint(firstDependency.constraint().kind(),
			firstDependency.constraint().left(), firstDependency.constraint().right(),
			firstDependency.constraint().inputPosition(), firstDependency.constraint().evidence());
		var copiedDependency = new DpPlacementAdapter.CfgTransientDependencyEntry(copiedConstraint,
			firstDependency.sourceOccurrence(), firstDependency.collectedPosition(),
			firstDependency.selectedSourceState());
		List<DpPlacementAdapter.CfgTransientDependencyEntry> foreign =
			new ArrayList<>(snapshot.cfgTransientDependencies());
		foreign.set(0, copiedDependency);
		Assert.assertThrows("A value-equal but foreign CFG constraint must fail closed",
			IllegalArgumentException.class,
			() -> new DpPlacementAdapter.CandidateOccurrenceSnapshot(snapshot.context(),
				snapshot.parentOccurrence(), snapshot.rawEntries(), snapshot.promotedEntries(),
				snapshot.logicalEntries(), snapshot.transientForwardDependencies(), foreign,
				snapshot.functionOutputDependencies(), snapshot.orderedOracleInputs(),
				snapshot.disposition(), snapshot.reasonCode()));
		for(var source : sources) {
			Assert.assertTrue("CFG source is not an analysis-owned compiled TWrite",
				analysis.hop(source).orElseThrow() instanceof DataOp data
					&& data.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTWRITE);
			PlacementState sourceState = plan.result().selectedStates().get(source);
			Assert.assertNotNull("DP omitted a reaching CFG definition", sourceState);
			var constraints = analysis.graph().constraints().stream()
				.filter(constraint -> constraint.left() == source && constraint.right() == read.key())
				.filter(constraint -> constraint.evidence().startsWith("cfg-transient-value:"))
				.toList();
			Assert.assertEquals("CFG dependency must have one exact value constraint", 1, constraints.size());
			Assert.assertTrue("DP selected incompatible CFG source/read layouts",
				NeutralPlacementGraph.constraintSatisfied(constraints.get(0), sourceState, readState));
		}
	}

	@Test
	public void fourPlannersPreserveNativeSamePoolPrivateAggregateInputs() throws Exception {
		String script = FEDERATED_SOURCE
			+ "B=federated(addresses=list(\"localhost:1234/Y1\",\"localhost:1235/Y2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"
			+ "C=A+B;print(sum(C));\n";
		String domain = null;
		for(PlannerKind planner : PlannerKind.values()) {
			PlannedProgram plan = planFresh(planner, script);
			PlacementAnalysis analysis = plan.analysis();
			if(domain == null)
				domain = analysis.analysisFingerprint();
			Assert.assertEquals(planner.name(), domain, analysis.analysisFingerprint());
			analysis.compiledHopOccurrences().stream()
				.filter(occurrence -> occurrence.hop() instanceof DataOp data
					&& data.getOp() == org.apache.sysds.common.Types.OpOpData.FEDERATED)
				.forEach(occurrence -> {
					var shape = analysis.shapeFact(occurrence.key()).orElseThrow();
					Assert.assertEquals(planner.name(), 4, shape.rows());
					Assert.assertEquals(planner.name(), 2, shape.cols());
				});
			long protectedValues = analysis.graph().decisionNodes().stream()
				.filter(node -> analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE)
				.peek(node -> assertProtectedRemote(planner, plan.result().selectedStates().get(node.key())))
				.count();
			Assert.assertTrue(planner + " lost the raw inputs or elementwise result", protectedValues >= 3);
			Assert.assertTrue(planner + " collected or redistributed a protected same-pool input",
				plan.result().selectedRelocations().stream().noneMatch(action ->
					analysis.graph().nodes().stream().anyMatch(node ->
						node.valueVersion().equals(action.sourceValueVersion())
							&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE)));
		}
	}

	@Test
	public void inheritedPlacementAnchorDoesNotCertifyDerivedValueDimensions() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE + "B=t(A);print(sum(B));\n");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		var transpose = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof ReorgOp)
			.findFirst().orElseThrow();
		var source = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() == transpose.hop().getInput(0))
			.findFirst().orElseThrow();
		Assert.assertFalse("fixture must expose the input's 4x2 placement anchor",
			analysis.graph().node(source.key()).orElseThrow().anchors().isEmpty());
		Assert.assertEquals(4, analysis.shapeFact(source.key()).orElseThrow().rows());
		Assert.assertEquals(2, analysis.shapeFact(source.key()).orElseThrow().cols());
		var shape = analysis.shapeFact(transpose.key()).orElseThrow();
		Assert.assertFalse("A's 4x2 source ranges are not the shape of t(A)",
			shape.rows() == 4 && shape.cols() == 2);
	}

	@Test
	public void rawPrivateAggregatePrintIsRejectedBeforeAnySelectorRuns() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE + "print(A);\n");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		DMLRuntimeException failure = Assert.assertThrows(DMLRuntimeException.class,
			() -> CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program));
		Assert.assertTrue(failure.getMessage(),
			failure.getMessage().contains("No privacy-safe physical placement"));
	}

	@Test
	public void fourPlannersPreservePrivateRowPlacementAcrossGrowingLoopColumns() throws Exception {
		assertGrowingColumns(FEDERATED_SOURCE);
	}

	@Test
	public void fourPlannersPreservePrivateFullPlacementAcrossGrowingLoopColumns() throws Exception {
		assertGrowingColumns("A=federated(addresses=list(\"localhost:1234/X1\"),"
			+ "ranges=list(list(0,0),list(4,2)));\n");
	}

	@Test
	public void fourPlannersPreservePrivateColPlacementAcrossGrowingLoopRows() throws Exception {
		assertGrowingValue("A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(0,2),list(2,4)));\n", "rbind");
	}

	@Test
	public void growingFullValuesDoNotOwnTheSeedRangeGeometry() throws Exception {
		PlannedProgram plan = planFresh(PlannerKind.FED_ALL,
			"A=federated(addresses=list(\"localhost:1234/X1\"),"
				+ "ranges=list(list(0,0),list(4,2)));\n"
				+ "B=A;i=1;while(i<=2){B=cbind(B,A);i=i+1;}print(sum(B));\n");
		var growing = plan.analysis().compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop().getDataType().isMatrix())
			.filter(occurrence -> !occurrence.key().callSitePath().equals("main/0"))
			.filter(occurrence -> "B".equals(occurrence.hop().getName())
				|| "b(cbind)".equals(occurrence.hop().getOpString())).toList();
		Assert.assertTrue("The append, backedge and carried reads must survive compilation", growing.size() >= 4);
		for(var occurrence : growing) {
			var node = plan.analysis().graph().node(occurrence.key()).orElseThrow();
			assertProtectedRemote(PlannerKind.FED_ALL, plan.result().selectedStates().get(node.key()));
			Assert.assertTrue(occurrence.hop().getOpString()
				+ " cannot claim the seed's old 4x2 range as its evolving exact value geometry",
				node.anchors().isEmpty());
		}
	}

	@Test
	public void fullLoopUsesCurrentOccurrenceEvidenceDespiteStaleNames() throws Exception {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		try {
			FederatedPlannerUtils.registerFedInitVar("B", org.apache.sysds.hops.fedplanner.FTypes.FType.ROW,
				"localhost:1234/old1;localhost:1235/old2|[0,0]-[2,2];[2,0]-[4,2]|ROW");
			growingFullValuesDoNotOwnTheSeedRangeGeometry();
		}
		finally {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	private static void assertGrowingColumns(String source) throws Exception {
		assertGrowingValue(source, "cbind");
	}

	private static void assertGrowingValue(String source, String operation) throws Exception {
		String script = source + "B=A;i=1;while(i<=2){B=" + operation + "(B,A);i=i+1;}print(sum(B));\n";
		for(PlannerKind planner : PlannerKind.values()) {
			PlannedProgram plan = planFresh(planner, script);
			Assert.assertTrue("the growing matrix loop must remain in the planned graph",
				plan.analysis().graph().decisionNodes().stream().anyMatch(node ->
					node.key().controlRegion().regionPath().stream().anyMatch(path -> path.contains("loop-body"))
						&& plan.analysis().requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE));
			plan.analysis().graph().decisionNodes().stream()
				.filter(node -> plan.analysis().requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE)
				.forEach(node -> assertProtectedRemote(planner, plan.result().selectedStates().get(node.key())));
			var growing = plan.analysis().compiledHopOccurrences().stream()
				.filter(occurrence -> occurrence.hop().getDataType().isMatrix())
				.filter(occurrence -> !occurrence.key().callSitePath().equals("main/0"))
				.filter(occurrence -> "B".equals(occurrence.hop().getName())
					|| ("b(" + operation + ")").equals(occurrence.hop().getOpString())).toList();
			Assert.assertTrue(planner + " must retain append, backedge and carried reads", growing.size() >= 4);
			for(var occurrence : growing)
				Assert.assertTrue(planner + " assigned stale seed geometry to " + occurrence.hop().getOpString(),
					plan.analysis().graph().node(occurrence.key()).orElseThrow().anchors().isEmpty());
			Assert.assertTrue(planner + " must retain every reaching definition, not just a seed",
				plan.analysis().logicalTransientInputsInCanonicalOrder().stream().anyMatch(input ->
					plan.analysis().logicalTransientInputsInCanonicalOrder().stream()
						.filter(other -> other.targetRead() == input.targetRead()).count() >= 2));
			plan.analysis().logicalTransientInputsInCanonicalOrder().stream()
				.filter(input -> growing.stream().anyMatch(occurrence -> occurrence.key() == input.targetRead()))
				.forEach(input -> Assert.assertNull(planner + " used seed geometry as a logical value map", input.anchor()));
			Assert.assertTrue(planner + " relocated a protected growing value",
				plan.result().selectedRelocations().stream().noneMatch(action ->
					plan.analysis().graph().nodes().stream().anyMatch(node ->
						node.valueVersion().equals(action.sourceValueVersion())
							&& plan.analysis().requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE)));
		}
	}

	private static PlannedProgram planFresh(PlannerKind planner) throws Exception {
		return planFresh(planner, CONTROL_FLOW_PROGRAM);
	}

	private static PlannedProgram planFresh(PlannerKind planner, String script) throws Exception {
		return planFresh(planner, script, false);
	}

	private static PlannedProgram planFresh(PlannerKind planner, String script, boolean legacyDp) throws Exception {
		return planFresh(planner, compile(script), legacyDp);
	}

	private static PlannedProgram planFresh(PlannerKind planner, DMLProgram program, boolean legacyDp) throws Exception {
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		PlannerInvocationReceipt receipt = switch(planner) {
			case FED_ALL -> new FederatedPlannerFedAllMaxFedFoutSinglePass()
				.rewriteProgram(program, null, null, analysis);
			case HEURISTIC -> new FederatedPlannerFedHeuristicSinglePass()
				.rewriteProgram(program, null, null, analysis);
			case DP -> {
				if(!legacyDp)
					yield FederatedPlannerFactory.create(FederatedPlanner.COMPILE_COST_BASED)
						.rewriteProgram(program, null, null, analysis);
				try {
					yield new FederatedPlannerDpFedCostBased().rewriteProgram(program, null, null, analysis);
				}
				catch(DpPlacementAdapter.DpSemanticConstructionException failure) {
					var parent = failure.parentOccurrence();
					throw new AssertionError(failure.reasonCode() + "|parent=" + parent.normalizedSignature()
						+ "|cfgSources=" + analysis.cfgDefinitionSourcesInCanonicalOrder(parent)
						+ "|logical=" + analysis.logicalTransientInputsInCanonicalOrder().stream()
							.filter(fact -> fact.targetRead() == parent).toList(), failure);
				}
			}
			case EXACT -> new FederatedPlanExact().rewriteProgram(program, null, null, analysis);
		};
		NormalizedPlannerResult result;
		if(receipt instanceof FedAllInvocationReceipt fedAll)
			result = fedAll.normalizedResult();
		else if(receipt instanceof HeuristicInvocationReceipt heuristic)
			result = heuristic.normalizedResult();
		else if(receipt instanceof DpInvocationReceipt dp)
			result = dp.normalizedResult();
		else if(receipt instanceof ExactPlacementInput exact)
			result = exact.normalizedResult();
		else
			throw new AssertionError("Unexpected planner receipt " + receipt.getClass());
		if(planner == PlannerKind.DP && !legacyDp)
			Assert.assertEquals("Four-planner coverage must exercise the production local optimizer",
				"DP-LocalConflict", result.plannerId());
		return new PlannedProgram(planner, analysis, result, receipt);
	}

	private static void assertProtectedRemote(PlannerKind planner, PlacementState state) {
		Assert.assertNotNull(planner + " omitted a protected decision", state);
		Assert.assertEquals(planner.name(), ExecType.FED, state.execType());
		Assert.assertEquals(planner.name(), FederatedOutput.FOUT, state.output());
	}

	private static void assertProtectedFunctionBoundary(PlannerKind planner, NeutralPlacementGraph.Node node) {
		if(node.emittedWork()) {
			Assert.assertFalse(planner + " lost an emitted protected function domain",
				node.legalAlternatives().isEmpty());
			Assert.assertTrue(planner.name(), node.legalAlternatives().stream()
				.allMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT));
		}
		else {
			Assert.assertEquals(planner.name(), NodeKind.FUNCTION_INPUT, node.kind());
			Assert.assertTrue(planner + " gave a non-emitted function placeholder a physical state",
				node.legalAlternatives().isEmpty());
			Assert.assertFalse(planner + " lost the inlined-boundary exclusion evidence",
				node.exclusions().isEmpty());
			Assert.assertTrue(planner + " lost the explicit inlined-boundary exclusion",
				node.exclusions().stream().allMatch(exclusion -> exclusion.reasonCode()
					== NeutralPlacementGraph.ReasonCode.NON_EMITTED_INLINED_FUNCTION_INPUT));
		}
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}

	private enum PlannerKind { FED_ALL, HEURISTIC, DP, EXACT }
	private record PlannedProgram(PlannerKind planner, PlacementAnalysis analysis,
		NormalizedPlannerResult result, PlannerInvocationReceipt receipt) { }
}
