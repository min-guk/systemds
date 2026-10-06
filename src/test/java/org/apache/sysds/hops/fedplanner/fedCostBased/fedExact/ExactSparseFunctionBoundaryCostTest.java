/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Exact planning accepts LOCAL function input delivery only through an explicit selected transfer. */
public class ExactSparseFunctionBoundaryCostTest {
	@Test
	public void protectedFoutActualRejectsLocalFunctionInputTransfer() throws Exception {
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
			.bindAtFinalHopBoundary(compile());
		var input = analysis.logicalFunctionInputsInCanonicalOrder().stream()
			.filter(fact -> analysis.hop(fact.sourceArgument())
				.map(hop -> hop.getOpString().contains("exp")).orElse(false))
			.findFirst().orElseThrow();
		var source = analysis.graph().node(input.sourceArgument()).orElseThrow();
		var boundary = analysis.graph().node(input.boundary()).orElseThrow();
		var formal = analysis.graph().node(input.targetRead()).orElseThrow();
		var transfer = analysis.graph().constraints().stream()
			.filter(constraint -> constraint.kind() == ConstraintKind.FUNCTION_INPUT_TRANSFER)
			.filter(constraint -> constraint.left() == source.key()
				&& constraint.right() == boundary.key())
			.findFirst().orElseThrow();
		Assert.assertNotNull(transfer);
		Assert.assertTrue("fixture must retain the federated actual",
			source.legalAlternatives().stream().anyMatch(state -> state.output() == FederatedOutput.FOUT));
		Assert.assertTrue("privacy closure must remove the explicit LOCAL transfer candidate",
			boundary.legalAlternatives().stream().noneMatch(state -> state.output() == FederatedOutput.LOUT));
		Assert.assertTrue("the protected formal read must retain only FOUT identity delivery",
			formal.legalAlternatives().stream().noneMatch(state -> state.output() == FederatedOutput.LOUT));
	}

	@Test
	public void exactModelStillBuildsForAProtectedFunctionArgument() throws Exception {
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
			.bindAtFinalHopBoundary(compile());
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		Assert.assertFalse(model.domains().isEmpty());
		Assert.assertFalse("the protected fixture must retain an exact function input fact",
			analysis.logicalFunctionInputsInCanonicalOrder().isEmpty());
	}

	private static DMLProgram compile() throws Exception {
		String script = """
			pass=function(matrix[double] A) return(matrix[double] B) {
			  B=A; i=1; while(i<2) { B=B+0; i=i+1; }
			}
			X_LOCAL=rand(rows=6,cols=3,seed=7);
			X=federated(local_matrix=X_LOCAL,
			  addresses=list("localhost:1234","localhost:1235"),
			  ranges=list(list(0,0),list(3,3),list(3,0),list(6,3)));
			E=exp(X); Q=pass(E); print(sum(E)+sum(Q));
			""";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return program;
	}
}
