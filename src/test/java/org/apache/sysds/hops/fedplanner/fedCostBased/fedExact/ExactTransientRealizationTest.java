/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Physical selection must retain the same exact alias authority as shared analysis. */
public class ExactTransientRealizationTest {
	@Test
	public void crossBlockChoicesRetainWriterReaderRealizations() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(4,3),list(4,0),list(8,3)));\n"
			+ "p=matrix(1,rows=3,cols=1); pred=X%*%p;\n"
			+ "if(sum(p)>0) { print(1); } else { print(2); }\n"
			+ "print(sum(pred));\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Assert.assertFalse(analysis.logicalTransientInputsInCanonicalOrder().isEmpty());
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		for(var domain : model.domains())
			for(var alternative : domain.alternatives())
				if(analysis.candidateRuleFacts().orderedFactsForParent(domain.node().key()).stream()
					.anyMatch(fact -> fact.allowedEmissionFacts().stream().anyMatch(emission ->
						emission.emissionState().placementState().equals(alternative.state()))))
					Assert.assertNotNull("A selected candidate map must not be completed after optimization",
						alternative.realization());
		var predictionRead = analysis.logicalTransientInputsInCanonicalOrder().stream()
			.filter(fact -> analysis.hop(fact.targetRead()).orElseThrow() instanceof DataOp data
				&& "pred".equals(data.getName())).findFirst().orElseThrow().targetRead();
		var readDomain = model.domains().stream().filter(domain -> domain.node().key() == predictionRead)
			.findFirst().orElseThrow();
		// Placement selection happens after shared analysis: both alternatives must
		// admit compatible writer/source assignments, without post-solve map repair.
		for(FederatedOutput output : List.of(FederatedOutput.LOUT, FederatedOutput.FOUT)) {
			Assert.assertTrue("Expected both local and federated prediction continuations",
				readDomain.alternatives().stream().anyMatch(alternative -> alternative.state().output() == output));
			var factors = new ArrayList<>(model.hardFactors());
			factors.add(ExactCategoricalSolver.Factor.lazy(List.of(readDomain.variable()), values ->
				readDomain.alternatives().get(values[0]).state().output() == output
					? 0.0 : Double.POSITIVE_INFINITY));
			var choice = ExactCategoricalSolver.solve(model.variables(), factors,
				new ExactCategoricalSolver.Limits(10_000_000, 50_000_000));
			Assert.assertEquals("Each retained placement must have an executable assignment", 0.0, choice.objective(), 0.0);
		}
		var result = model.solveLegalityOnly(new ExactCategoricalSolver.Limits(10_000_000, 50_000_000));
		var physical = model.physicalSelection(result);
		Map<CompiledHopKey,CandidateSelectionReceipt> receipts = new IdentityHashMap<>();
		for(var candidate : physical.candidates()) {
			CandidateSelectionReceipt receipt = analysis.canonicalCandidateReceipt(
				candidate.rule().key(), candidate.emission(), candidate.realization(), candidate.supportClause());
			Assert.assertSame("The solver-selected proof clause must survive receipt projection",
				candidate.supportClause(), receipt.supportClause());
			receipts.put(candidate.decision(), receipt);
		}
		for(var receipt : receipts.values())
			for(var support : receipt.supportClause().requiredInputSupport())
				Assert.assertTrue("A selected support clause cannot borrow another source layout",
					CandidateSelections.matchesRealization(support, receipts.get(support.rule().parentOccurrence())));
		for(var fact : analysis.logicalTransientInputsInCanonicalOrder()) {
			Assert.assertNotNull(receipts.get(fact.sourceWrite()));
			Assert.assertNotNull(receipts.get(fact.targetRead()));
			Assert.assertTrue("Selected writer/read must share an executable compatibility edge",
				fact.compatibility().stream().anyMatch(edge ->
					CandidateSelections.matchesRealization(edge.sourceRealization(), receipts.get(fact.sourceWrite()))
						&& CandidateSelections.matchesRealization(edge.readerRealization(), receipts.get(fact.targetRead()))));
		}
	}
}
