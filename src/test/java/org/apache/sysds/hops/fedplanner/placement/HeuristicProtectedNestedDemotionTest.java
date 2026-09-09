/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.stream.Collectors;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.selector.PolicyFirstFeasiblePlacementSelector;
import org.apache.sysds.hops.fedplanner.fedHeuristic.FederatedPlannerFedHeuristicSinglePass;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class HeuristicProtectedNestedDemotionTest {
	@Test
	public void protectedSiblingRequiresRemoteSecondDemotionNotIllegalCp() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X\"),"
			+ "ranges=list(list(0,0),list(4,3)));\n"
			+ "v=matrix(1,rows=3,cols=1);z=X%*%v;q=t(X)%*%z;print(sum(q));\n";
		PlacementAnalysis analysis = analyzeProtected(script);
		var markers = analysis.heuristicPolicyFacts().demotions().stream()
			.map(fact -> fact.valueVersion()).collect(Collectors.toSet());
		Assert.assertEquals("Two reductions over protected X exercise nested demotions", 2, markers.size());
		var continuations = analysis.heuristicPolicyFacts().paths().stream()
			.flatMap(path -> path.nativeContinuations().stream())
			.filter(fact -> fact.consumerState().output() == FederatedOutput.LOUT).toList();
		Assert.assertFalse("Nested protected reduction needs a certified native LOUT continuation",
			continuations.isEmpty());
		for(var fact : continuations) {
			var candidate = fact.runtimeCandidate();
			Assert.assertEquals(CandidateInputState.absentLocal(),
				candidate.key().orderedInputs().get(fact.localInputPosition()));
			Assert.assertEquals(1, candidate.key().orderedInputs().stream()
				.filter(CandidateInputState::present).count());
			Assert.assertTrue(candidate.allowedEmissionFacts().stream().anyMatch(emission ->
				emission.emissionState().placementState().equals(fact.consumerState())
					&& emission.executionFType() == fact.siblingFoutState().fType()));
			Assert.assertFalse("Native LOUT exception applies only when coordinator reduction is illegal",
				analysis.graph().node(fact.consumer()).orElseThrow().legalAlternatives().stream()
					.anyMatch(state -> state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT));
		}
		var result = new FederatedPlannerFedHeuristicSinglePass()
			.select(analysis, markers);
		for(var occurrence : analysis.compiledHopOccurrences())
			if(occurrence.hop() instanceof AggBinaryOp) {
				var state = result.assignment().get(occurrence.key());
				Assert.assertEquals("Protected X cannot be collected for coordinator matmul", ExecType.FED, state.execType());
				Assert.assertEquals("Safe aggregate result still follows the demotion policy", FederatedOutput.LOUT, state.output());
			}
		Assert.assertTrue("No explicit re-upload is needed for a native local operand",
			result.selectedRelocations().isEmpty());
		Assert.assertTrue(CandidateSelections.canStillBeReachable(analysis, result.selectorGraph(),
			result.selectorGraph().relocationActions(), result.assignment()));
	}

	@Test
	public void sharedProtectedFunctionFormalPreventsInfeasibleActualDemotion() throws Exception {
		PlacementAnalysis analysis = analyzeProtected(
			"f=function(Matrix[double] A) return(Matrix[double] R) {"
				+ "if(sum(A)>0){R=exp(A);}else{R=abs(A);}}\n"
			+ "X=federated(addresses=list(\"localhost:1234/X\"),ranges=list(list(0,0),list(4,3)));\n"
			+ "Y=federated(addresses=list(\"localhost:1234/Y\"),ranges=list(list(0,0),list(4,1)));\n"
			+ "v=matrix(1,rows=3,cols=1);z=X%*%v;a=f(z);b=f(Y);print(sum(a)+sum(b));\n");
		var markers = analysis.heuristicPolicyFacts().demotions().stream()
			.map(fact -> fact.valueVersion()).collect(Collectors.toSet());
		var result = new HeuristicPlacementAdapter(new PolicyFirstFeasiblePlacementSelector())
			.select(analysis, markers);
		for(var occurrence : analysis.compiledHopOccurrences())
			if(occurrence.hop() instanceof AggBinaryOp) {
				Assert.assertTrue("Necessary policy support does not delete any base candidate",
					analysis.graph().node(occurrence.key()).orElseThrow().legalAlternatives().stream()
						.anyMatch(state -> state.execType() == ExecType.FED && state.output() == FederatedOutput.LOUT));
				Assert.assertEquals("A shared protected formal requires the actual value resident remotely",
					FederatedOutput.FOUT, result.assignment().get(occurrence.key()).output());
				Assert.assertFalse("The policy cannot require a hard-constraint-incompatible demotion",
					markers.contains(analysis.graph().node(occurrence.key()).orElseThrow().valueVersion()));
			}
		Assert.assertTrue(CandidateSelections.canStillBeReachable(analysis, result.selectorGraph(),
			result.selectorGraph().relocationActions(), result.assignment()));
	}

	@Test
	public void noVectorDemotionRetainsFedFirstAssignment() throws Exception {
		PlacementAnalysis analysis = analyzeProtected(
			"X=federated(addresses=list(\"localhost:1234/X\"),ranges=list(list(0,0),list(4,3)));\n"
				+ "z=exp(X);print(sum(z));\n");
		var markers = analysis.heuristicPolicyFacts().demotions().stream()
			.map(fact -> fact.valueVersion()).collect(Collectors.toSet());
		Assert.assertTrue("Elementwise input must not fabricate an aggregate-vector marker", markers.isEmpty());
		var heuristic = new FederatedPlannerFedHeuristicSinglePass().select(analysis, markers);
		var fedFirst = new PolicyFirstFeasiblePlacementSelector().select(analysis);
		Assert.assertEquals("Without a demotion, AggLocal retains FedFirst choices",
			fedFirst.assignment(), heuristic.assignment());
		Assert.assertEquals("FEDERATED_FIRST", heuristic.plannerFacts().get("stateOrdering"));
		Assert.assertEquals("FIRST_FEASIBLE", heuristic.plannerFacts().get("search"));
	}

	private static PlacementAnalysis analyzeProtected(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}
