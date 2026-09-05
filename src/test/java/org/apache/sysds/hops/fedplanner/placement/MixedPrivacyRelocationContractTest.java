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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAll.FedAllInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAllMaxFedFoutSinglePass;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.FederatedPlanExact;
import org.apache.sysds.hops.fedplanner.fedHeuristic.FederatedPlannerFedHeuristic.HeuristicInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedHeuristic.FederatedPlannerFedHeuristicSinglePass;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Privacy-sensitive relocation contract for mixed federated binary inputs. */
public class MixedPrivacyRelocationContractTest {
	private static final String SCRIPT =
		"A=federated(addresses=list(\"localhost:1234/A1\",\"localhost:1235/A2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n"
			+ "B=federated(addresses=list(\"localhost:2234/B1\",\"localhost:2235/B2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n"
			+ "C=A+B;print(sum(C));\n";

	@Test
	public void fourPlannersMoveOnlyThePublicOperandToTheProtectedPool() throws Exception {
		List<PlannedProgram> plans = new ArrayList<>();
		for(PlannerKind planner : PlannerKind.values())
			plans.add(planFresh(planner, false));

		String domain = plans.get(0).analysis().analysisFingerprint();
		for(PlannedProgram plan : plans) {
			PlacementAnalysis analysis = plan.analysis();
			NormalizedPlannerResult result = plan.result();
			Assert.assertEquals(plan.planner().name(), domain, analysis.analysisFingerprint());
			Node protectedSource = source(analysis, "A");
			Node publicSource = source(analysis, "B");
			Node consumer = uniqueNode(analysis, "C", "b(+)");
			Assert.assertEquals(Privacy.PRIVATE_AGGREGATE,
				analysis.requirePrivacy(protectedSource.key()));
			Assert.assertEquals(Privacy.PUBLIC, analysis.requirePrivacy(publicSource.key()));
			Assert.assertEquals("fixture requires one durable protected-source pool", 1,
				protectedSource.anchors().size());

			List<RelocationSelections.ResolvedChoice> inputs = RelocationSelections.resolveAndValidate(
				analysis, analysis.graph().relocationActions(), result.selectedStates(),
				result.selectedCandidateSelections(), result.selectedRelocationChoices()).stream()
				.filter(choice -> choice.obligation().consumer() == consumer.key()).toList();
			Assert.assertEquals(plan.planner() + " must bind both matrix inputs", 2, inputs.size());
			Assert.assertTrue(plan.planner() + " moved the protected input",
				inputs.stream().filter(choice -> choice.action().key().sourceValueVersion()
					.equals(protectedSource.valueVersion()))
					.allMatch(choice -> !choice.requiresEmission()));
			Assert.assertEquals(plan.planner() + " must retain one direct protected receipt", 1,
				inputs.stream().filter(choice -> !choice.requiresEmission()
					&& choice.action().key().sourceValueVersion().equals(protectedSource.valueVersion())).count());
			Assert.assertEquals(plan.planner() + " must actively relocate the public input", 1,
				inputs.stream().filter(RelocationSelections.ResolvedChoice::requiresEmission)
					.filter(choice -> choice.action().key().sourceValueVersion()
						.equals(publicSource.valueVersion())).count());
			Assert.assertTrue(plan.planner() + " did not converge on the protected worker pool",
				inputs.stream().allMatch(choice -> PlacementIdentity.samePhysicalWorkerPool(
					protectedSource.anchors().get(0), choice.action().key().durableAnchor())));
			Assert.assertTrue(plan.planner() + " emitted a protected relocation",
				result.selectedRelocations().stream().noneMatch(action ->
					action.sourceValueVersion().equals(protectedSource.valueVersion())));

			var sum = analysis.compiledHopOccurrences().stream()
				.filter(occurrence -> occurrence.hop() instanceof AggUnaryOp aggregate
					&& aggregate.getOp() == AggOp.SUM)
				.findFirst().orElseThrow();
			Assert.assertEquals(plan.planner().name(), Privacy.PUBLIC,
				analysis.requirePrivacy(sum.key()));
			PlacementState released = result.selectedStates().get(sum.key());
			Assert.assertNotNull(plan.planner() + " omitted the aggregate release", released);
			Assert.assertEquals(plan.planner().name(), ExecType.FED, released.execType());
			Assert.assertEquals(plan.planner().name(), FederatedOutput.LOUT, released.output());
		}
	}

	@Test
	public void fourPlannersRejectTwoProtectedOperandsFromIncompatiblePoolsAfterAnalysis() throws Exception {
		for(PlannerKind planner : PlannerKind.values()) {
			DMLProgram program = compile();
			registerSourcePrivacy(program, true);
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			Assert.assertEquals(planner.name(), Privacy.PRIVATE_AGGREGATE,
				analysis.requirePrivacy(source(analysis, "A").key()));
			Assert.assertEquals(planner.name(), Privacy.PRIVATE_AGGREGATE,
				analysis.requirePrivacy(source(analysis, "B").key()));
			Assert.assertThrows(planner + " must reject the infeasible full relocation selection",
				RuntimeException.class, () -> invoke(planner, program, analysis));
		}
	}

	private static PlannedProgram planFresh(PlannerKind planner, boolean protectBoth) throws Exception {
		DMLProgram program = compile();
		registerSourcePrivacy(program, protectBoth);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		return new PlannedProgram(planner, analysis, normalized(invoke(planner, program, analysis)));
	}

	private static PlannerInvocationReceipt invoke(PlannerKind planner, DMLProgram program,
		PlacementAnalysis analysis) {
		return switch(planner) {
			case FED_ALL -> new FederatedPlannerFedAllMaxFedFoutSinglePass()
				.rewriteProgram(program, null, null, analysis);
			case HEURISTIC -> new FederatedPlannerFedHeuristicSinglePass()
				.rewriteProgram(program, null, null, analysis);
			case DP -> new FederatedPlannerDpFedCostBased().rewriteProgram(program, null, null, analysis);
			case EXACT -> new FederatedPlanExact().rewriteProgram(program, null, null, analysis);
		};
	}

	private static NormalizedPlannerResult normalized(PlannerInvocationReceipt receipt) {
		if(receipt instanceof FedAllInvocationReceipt fedAll)
			return fedAll.normalizedResult();
		if(receipt instanceof HeuristicInvocationReceipt heuristic)
			return heuristic.normalizedResult();
		if(receipt instanceof DpInvocationReceipt dp)
			return dp.normalizedResult();
		if(receipt instanceof ExactPlacementInput exact)
			return exact.normalizedResult();
		throw new AssertionError("Unexpected planner receipt " + receipt.getClass());
	}

	private static void registerSourcePrivacy(DMLProgram program, boolean protectBoth) {
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		List<DataOp> sources = federatedSources(program);
		Assert.assertEquals("fixture requires exactly two federated sources", 2, sources.size());
		for(DataOp source : sources)
			if("A".equals(source.getName()) || protectBoth)
				FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(
					source, Privacy.PRIVATE_AGGREGATE);
	}

	private static List<DataOp> federatedSources(DMLProgram program) {
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		program.getStatementBlocks().stream().filter(block -> block.getHops() != null)
			.forEach(block -> pending.addAll(block.getHops()));
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		List<DataOp> sources = new ArrayList<>();
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(!visited.add(hop))
				continue;
			if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
				sources.add(data);
			pending.addAll(hop.getInput());
		}
		return sources;
	}

	private static Node source(PlacementAnalysis analysis, String name) {
		List<Node> matches = analysis.graph().nodes().stream().filter(node -> analysis.hop(node.key())
			.map(hop -> hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED
				&& name.equals(data.getName())).orElse(false)).toList();
		Assert.assertEquals("fixture requires one federated source " + name, 1, matches.size());
		return matches.get(0);
	}

	private static Node uniqueNode(PlacementAnalysis analysis, String name, String opcode) {
		List<Node> matches = analysis.graph().nodes().stream().filter(node -> analysis.hop(node.key())
			.map(hop -> name.equals(hop.getName()) && opcode.equals(hop.getOpString())).orElse(false)).toList();
		Assert.assertEquals("fixture requires one " + name + '/' + opcode, 1, matches.size());
		return matches.get(0);
	}

	private static DMLProgram compile() throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, SCRIPT, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}

	private enum PlannerKind { FED_ALL, HEURISTIC, DP, EXACT }
	private record PlannedProgram(PlannerKind planner, PlacementAnalysis analysis,
		NormalizedPlannerResult result) { }
}
