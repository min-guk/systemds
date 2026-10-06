/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;
import java.util.ArrayDeque;
import java.util.HashSet;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Repeated calls may share one immutable actual without duplicating formal input authority. */
public class ExactRepeatedFunctionInputFeasibilityTest {
	@Test
	public void sameActualAcrossTwoCallOccurrencesRetainsAFeasiblePhysicalModel() throws Exception {
		DMLProgram program = compile("""
			guarded=function(matrix[double] M, boolean flag) return (double out) {
				if(flag) { B=M+1; } else { B=M-1; }
				i=1; while(i<=2) { B=B+1; i=i+1; }
				out=sum(B);
			}
			P=federated(addresses=list("localhost:13000/P1","localhost:13001/P2"),
				ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));
			X=federated(addresses=list("localhost:13000/X1","localhost:13001/X2"),
				ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));
			out=sum(X)+guarded(P,TRUE)+guarded(P,FALSE);
			print(out);
			""");
		PlacementAnalysis analysis =
			CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		Assert.assertEquals("two calls and two branch reads retain four matrix occurrence facts", 4,
			analysis.logicalFunctionInputsInCanonicalOrder().stream().filter(fact ->
				analysis.hop(fact.sourceArgument()).orElseThrow().getDataType().isMatrix()).count());

		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactCategoricalSolver.Result feasible = ExactPhysicalReducedSolver.solve(
			model.variables().size(), model.variables(), model.hardFactors(),
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Assert.assertEquals(model.variables().size(), feasible.assignmentInVariableOrder().size());

		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		Assert.assertNotNull(LocalPhysicalOptimizer.optimize(model, surface));
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		var pending = new ArrayDeque<Hop>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				pending.addAll(block.getHops());
		var visited = new HashSet<Hop>();
		int privateSources = 0;
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(!visited.add(hop))
				continue;
			if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED
				&& "X".equals(data.getName())) {
				FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(data, Privacy.PRIVATE_AGGREGATE);
				privateSources++;
			}
			pending.addAll(hop.getInput());
		}
		Assert.assertEquals("Mixed-privacy fixture retains its private source", 1, privateSources);
		return program;
	}
}
