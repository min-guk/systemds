/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.PlacementPlannerAdapter;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Regression contract: exact ABSENT_LOCAL candidate inputs must survive lowering authority. */
public class CampaignBG014AbsentLocalMaterializationLoweringRedTest {
	@Test
	public void fedAllPricesAndAvoidsOptionalLocalInputPreparation() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compileFixture());
		FedAllPlacementAdapter.Result selection = new FedAllPlacementAdapter().select(analysis);
		NormalizedPlannerResult plan = PlacementPlannerAdapter.normalize(
			analysis, selection);
		Map<CompiledHopKey,CandidateSelectionReceipt> candidates = new IdentityHashMap<>();
		for(CandidateSelectionReceipt candidate : plan.selectedCandidateSelections())
			candidates.put(candidate.rule().parentOccurrence(), candidate);

		List<CompiledInputEdgeFact> expected = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> !analysis.isDmlFunctionCallBoundary(edge.consumer()))
			.filter(edge -> isFedFout(plan.selectedStates().get(edge.producer()))
				&& !plan.selectedEmissionStates().get(edge.producer()).derivedFedFout())
			.filter(edge -> {
				PlacementState consumer = plan.selectedStates().get(edge.consumer());
				CandidateSelectionReceipt candidate = candidates.get(edge.consumer());
				return consumer != null && consumer.execType() == ExecType.FED && candidate != null
					&& edge.inputPosition() < candidate.rule().orderedInputs().size()
					&& !candidate.rule().orderedInputs().get(edge.inputPosition()).present();
			})
			.toList();
		long avoidableLocalRows = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> fact.status()
				== PlacementAnalysis.CandidateEvaluationStatus.AVAILABLE)
			.filter(fact -> fact.allowedEmissionFacts().stream().anyMatch(emission ->
				emission.emissionState().placementState().execType() == ExecType.FED))
			.flatMap(fact -> java.util.stream.IntStream.range(0,
				fact.key().orderedInputs().size()).filter(position ->
					!fact.key().orderedInputs().get(position).present()).mapToObj(position ->
						analysis.compiledInputEdgesInCanonicalOrder().stream().filter(edge ->
							edge.consumer() == fact.key().parentOccurrence()
								&& edge.inputPosition() == position).findFirst().orElse(null)))
			.filter(java.util.Objects::nonNull)
			.filter(edge -> analysis.graph().node(edge.producer()).orElseThrow().legalAlternatives()
				.stream().anyMatch(state -> isFedFout(state))).count();
		Assert.assertTrue("fixture must expose an optional exact ABSENT_LOCAL path",
			avoidableLocalRows > 0);
		Assert.assertTrue("FedAll must avoid optional FOUT-to-local transfers after FED/FOUT ties",
			expected.isEmpty());

		List<LocalMaterializationActionKey> locals = plan.selectedLocalMaterializations();
		Assert.assertTrue("No optional local materialization may remain in the selected lowering",
			locals.isEmpty());
		Assert.assertEquals("FedAll's relocation objective must include every exact physical transfer "
			+ "that canonical lowering will emit",
			RelocationSelections.physicalEmissionCount(selection.selectedRelocations()) + locals.size(),
			selection.score().relocationCount());
	}

	private static boolean isFedFout(PlacementState state) {
		return state != null && state.execType() == ExecType.FED
			&& state.output() == FederatedOutput.FOUT && state.fType() != null;
	}

	private static DMLProgram compileFixture() throws Exception {
		String rangesX = "list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100))";
		String rangesY = "list(list(0,0),list(25000,1),list(25000,0),list(50000,1))";
		String workersX = "list(\"localhost:1234/X1\",\"localhost:1235/X2\")";
		String workersY = "list(\"localhost:1234/Y1\",\"localhost:1235/Y2\")";
		String script = "X=federated(addresses=" + workersX + ",ranges=" + rangesX + ");\n"
			+ "Y=federated(addresses=" + workersY + ",ranges=" + rangesY + ");\n"
			+ "Y=(Y<0)+1;\n"
			+ "Z=multiLogReg(X=X,Y=Y,verbose=FALSE,maxi=30,maxii=5,tol=1e-9,icpt=0,"
			+ "numclasses=2,numrows=50000,numcols=2100);\n"
			+ "write(Z,\"/tmp/g014-absent-local-lowering\",format=\"binary\");\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}
}
