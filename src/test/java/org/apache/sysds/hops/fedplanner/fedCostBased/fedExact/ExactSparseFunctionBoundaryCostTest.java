/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.MemoTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
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
	public void logicalFunctionBoundaryPreservesConcreteNnzWithAbstractGeometry() throws Exception {
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
			.bindAtFinalHopBoundary(compileDenseFunctionInput());
		var input = analysis.logicalFunctionInputsInCanonicalOrder().stream()
			.filter(fact -> analysis.hop(fact.sourceArgument())
				.map(hop -> hop.getOpString().contains("exp")).orElse(false))
			.findFirst().orElseThrow();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		Hop sourceHop = analysis.hop(input.sourceArgument()).orElseThrow();
		Hop formalHop = analysis.hop(input.targetRead()).orElseThrow();
		makeRawShapeUnknown(sourceHop);
		makeRawShapeUnknown(formalHop);
		sourceHop.setNnz(2);

		var source = model.domains().stream()
			.filter(domain -> domain.node().key() == input.sourceArgument())
			.findFirst().orElseThrow();
		var formal = model.domains().stream()
			.filter(domain -> domain.node().key() == input.targetRead())
			.findFirst().orElseThrow();
		int sourceFout = firstAlternative(source, ExecType.FED, FederatedOutput.FOUT);
		int formalCp = firstAlternative(formal, ExecType.CP, FederatedOutput.LOUT);
		double actual = ExactPhysicalCostModel.physicalCostSurface(analysis, model)
			.contributions().stream()
			.map(ExactPhysicalCostModel.PhysicalContribution::factor)
			.filter(factor -> factor.scope().equals(List.of(source.variable(), formal.variable())))
			.mapToDouble(factor -> factor.cost(new int[] {sourceFout, formalCp})).sum();
		double expectedBytes = MatrixBlock.estimateSizeOnDisk(6, 3, 2);
		double denseBytes = PlacementCostSemantics.analysisAwareDenseOutputBytes(
			analysis, input.sourceArgument());
		Assert.assertTrue("Fixture must distinguish concrete sparse bytes from dense analysis bytes",
			expectedBytes < denseBytes);
		double expected = analysis.executionFrequencyFacts().logicalFunctionCallWeight(input)
			* FederatedCostModel.computeReusableMaterializationDownloadCost(expectedBytes,
				source.alternatives().get(sourceFout).state().fType(), 2);
		Assert.assertEquals("Exact abstract geometry must retain a concrete raw-HOP nnz rather"
			+ " than replacing it with a dense payload", expected, actual, 1e-9);
	}

	@Test
	public void logicalFunctionBoundaryRetainsFallbackWhenAnalysisShapeIsUnknown() throws Exception {
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
			.bindAtFinalHopBoundary(compileUnknownFunctionInput());
		var input = analysis.logicalFunctionInputsInCanonicalOrder().stream()
			.findFirst().orElseThrow();
		Assert.assertTrue("Fixture requires genuinely unproven common-analysis bytes",
			Double.isNaN(PlacementCostSemantics.analysisAwareDenseOutputBytes(
				analysis, input.sourceArgument())));
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var source = model.domains().stream()
			.filter(domain -> domain.node().key() == input.sourceArgument())
			.findFirst().orElseThrow();
		var formal = model.domains().stream()
			.filter(domain -> domain.node().key() == input.targetRead())
			.findFirst().orElseThrow();
		int sourceFout = firstAlternative(source, ExecType.FED, FederatedOutput.FOUT);
		int formalCp = firstAlternative(formal, ExecType.CP, FederatedOutput.LOUT);
		double actual = ExactPhysicalCostModel.physicalCostSurface(analysis, model)
			.contributions().stream()
			.map(ExactPhysicalCostModel.PhysicalContribution::factor)
			.filter(factor -> factor.scope().equals(List.of(source.variable(), formal.variable())))
			.mapToDouble(factor -> factor.cost(new int[] {sourceFout, formalCp})).sum();
		double fallbackBytes = FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(
			analysis.hop(input.targetRead()).orElseThrow(),
			analysis.hop(input.sourceArgument()).orElseThrow());
		double expected = analysis.executionFrequencyFacts().logicalFunctionCallWeight(input)
			* FederatedCostModel.computeReusableMaterializationDownloadCost(
				fallbackBytes, source.alternatives().get(sourceFout).state().fType(), 2);
		Assert.assertEquals("An unproven analysis shape must retain the established raw-HOP"
			+ " fallback", expected, actual, 1e-9);
	}

	@Test
	public void logicalFunctionBoundaryUsesExactAnalysisShapeForUnknownRawHops() throws Exception {
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
			.bindAtFinalHopBoundary(compileDenseFunctionInput());
		var input = analysis.logicalFunctionInputsInCanonicalOrder().stream()
			.filter(fact -> analysis.hop(fact.sourceArgument())
				.map(hop -> hop.getOpString().contains("exp")).orElse(false))
			.findFirst().orElseThrow();
		var sourceShape = analysis.abstractShapeFact(input.sourceArgument()).orElseThrow();
		Assert.assertTrue(sourceShape.rows().isExact(6));
		Assert.assertTrue(sourceShape.cols().isExact(3));
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		Hop sourceHop = analysis.hop(input.sourceArgument()).orElseThrow();
		Hop formalHop = analysis.hop(input.targetRead()).orElseThrow();
		makeRawShapeUnknown(sourceHop);
		makeRawShapeUnknown(formalHop);
		double rawFallback = FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(
			formalHop, sourceHop);
		double exactBytes = PlacementCostSemantics.analysisAwareDenseOutputBytes(
			analysis, input.sourceArgument());
		Assert.assertTrue("Fixture must distinguish the raw unknown-shape fallback from the"
			+ " exact common-analysis shape", rawFallback > exactBytes);

		var source = model.domains().stream()
			.filter(domain -> domain.node().key() == input.sourceArgument())
			.findFirst().orElseThrow();
		var formal = model.domains().stream()
			.filter(domain -> domain.node().key() == input.targetRead())
			.findFirst().orElseThrow();
		int sourceFout = firstAlternative(source, ExecType.FED, FederatedOutput.FOUT);
		int formalCp = firstAlternative(formal, ExecType.CP, FederatedOutput.LOUT);
		double actual = ExactPhysicalCostModel.physicalCostSurface(analysis, model)
			.contributions().stream()
			.map(ExactPhysicalCostModel.PhysicalContribution::factor)
			.filter(factor -> factor.scope().equals(List.of(source.variable(), formal.variable())))
			.mapToDouble(factor -> factor.cost(new int[] {sourceFout, formalCp})).sum();
		var sourceState = source.alternatives().get(sourceFout).state();
		double expected = analysis.executionFrequencyFacts().logicalFunctionCallWeight(input)
			* FederatedCostModel.computeReusableMaterializationDownloadCost(
				exactBytes, sourceState.fType(), 2);
		Assert.assertEquals("Logical function transfer bytes must come from the exact immutable"
			+ " analysis shape rather than the raw HOP unknown-shape fallback",
			expected, actual, 1e-9);
	}

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
		double denseBytes = MatrixBlock.estimateSizeOnDisk(6, 3, 18);
		Assert.assertTrue("Fixture must distinguish sparse wire bytes from the dense formal shape",
			expectedBytes < denseBytes);
		var sourceState = source.alternatives().get(sourceFout).state();
		Assert.assertNotNull("FED/FOUT source must retain its materialization layout", sourceState.fType());
		double expected = analysis.executionFrequencyFacts().logicalFunctionCallWeight(input)
			* FederatedCostModel.computeReusableMaterializationDownloadCost(
				expectedBytes, sourceState.fType(), 2);
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

	private static DMLProgram compileDenseFunctionInput() throws Exception {
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
			"Q=pass(exp(X));",
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

	private static DMLProgram compileUnknownFunctionInput() throws Exception {
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
			"A=removeEmpty(target=X,margin=\"rows\");",
			"Q=pass(A);",
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

	private static void makeRawShapeUnknown(Hop hop) {
		hop.setDim1(-1);
		hop.setDim2(-1);
		hop.setNnz(-1);
		hop.computeMemEstimate(new MemoTable());
	}
}
