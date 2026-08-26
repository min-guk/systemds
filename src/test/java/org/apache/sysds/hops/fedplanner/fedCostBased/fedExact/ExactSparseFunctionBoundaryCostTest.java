/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.junit.Assert;
import org.junit.Test;

/** Exact cost surface must preserve expected sparse payloads at DML function boundaries. */
public class ExactSparseFunctionBoundaryCostTest {
	@Test
	public void sparseAssignmentActualUsesExpectedWireBytes() throws Exception {
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
			.bindAtFinalHopBoundary(compile());
		var inputFacts = analysis.logicalFunctionInputsInCanonicalOrder();
		Assert.assertEquals("Expected one P actual/formal boundary: "
			+ inputFacts.stream().map(fact -> "source="
				+ analysis.hop(fact.sourceArgument()).map(hop -> hop.getName() + "/"
					+ hop.getOpString()).orElse("-") + ",target="
				+ analysis.hop(fact.targetRead()).map(hop -> hop.getName() + "/"
					+ hop.getOpString()).orElse("-")).toList(), 1, inputFacts.size());
		var input = inputFacts.get(0);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var source = model.domains().stream()
			.filter(domain -> domain.node().key() == input.sourceArgument())
			.findFirst().orElseThrow();
		var formal = model.domains().stream()
			.filter(domain -> domain.node().key() == input.targetRead())
			.findFirst().orElseThrow();
		int sourceFout = firstAlternative(source, ExecType.FED, FederatedOutput.FOUT);
		int formalCp = firstAlternative(formal, ExecType.CP, FederatedOutput.LOUT);

		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		double actual = surface.contributions().stream()
			.map(ExactPhysicalCostModel.PhysicalContribution::factor)
			.filter(factor -> factor.scope().equals(List.of(source.variable(), formal.variable())))
			.mapToDouble(factor -> factor.cost(new int[] {sourceFout, formalCp})).sum();
		double expectedBytes = MatrixBlock.estimateSizeOnDisk(6, 3, 6);
		double expected = analysis.executionFrequencyFacts().logicalFunctionCallWeight(input)
			* FederatedCostModel.computeDownloadNetworkCost(expectedBytes);
		Assert.assertTrue("Expected a nonzero FOUT-to-CP function-boundary transfer", actual > 0.0);
		Assert.assertEquals("The function boundary must use the source occurrence's expected"
			+ " assignment payload instead of the dense formal-read estimate",
			expected, actual, 1e-9);
	}

	private static int firstAlternative(ExactPhysicalModel.DecisionDomain domain,
			ExecType execType, FederatedOutput output) {
		for(int value = 0; value < domain.alternatives().size(); value++) {
			var state = domain.alternatives().get(value).state();
			if(state.execType() == execType && state.output() == output)
				return value;
		}
		throw new AssertionError("Missing " + execType + "/" + output + " in "
			+ domain.node().key().normalizedSignature() + ": " + domain.alternatives());
	}

	private static DMLProgram compile() throws Exception {
		String script = String.join("\n",
			"pass = function(matrix[double] A) return (matrix[double] B) {",
			"  B=A;",
			"  i=1;",
			"  while(i<2) { B=B+0; i=i+1; }",
			"}",
			"X_LOCAL=rand(rows=6,cols=3,seed=7);",
			"X=federated(local_matrix=X_LOCAL,",
			"  addresses=list(\"localhost:1234\",\"localhost:1235\"),",
			"  ranges=list(list(0,0),list(3,3),list(3,0),list(6,3)));",
			"m=rowMins(X);",
			"P=X<=m;",
			"Q=pass(P);",
			"print(sum(Q));");
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}
}
