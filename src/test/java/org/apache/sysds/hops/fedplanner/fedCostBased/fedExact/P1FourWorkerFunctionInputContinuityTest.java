/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression coverage for the P1 split-output -> nested function-input placement boundary. */
public class P1FourWorkerFunctionInputContinuityTest {
	private static final String P1_SPLIT_NESTED_INPUT = """
		e_step = function(Matrix[Double] X) return(Double norm) {
			resp = matrix(1, rows=nrow(X), cols=2);
			weight = colSums(resp) + 2.22e-16;
			mean = (t(resp) %*% X) / t(weight);
			sigma = matrix(0, 0, ncol(X));
			for(k in 1:nrow(mean)) {
				diff = X - mean[k,];
				cov = (t(diff * resp[, k]) %*% diff) / as.scalar(weight[1,k]);
				sigma = rbind(sigma, cov);
			}
			norm = sum(sigma);
		}
		run = function(Matrix[Double] Xin, Matrix[Double] I) return(Double norm) {
			Xselected = removeEmpty(target=Xin, margin="cols", select=t(I));
			Xscaled = scale(X=Xselected, center=TRUE, scale=TRUE);
			covAll = t(Xscaled) %*% Xscaled;
			dummyY = seq(1, nrow(Xscaled));
			[Xtrain, Xtest, ytrain, ytest] = split(
				X=Xscaled, Y=dummyY, f=0.7, cont=FALSE, seed=7);
			norm = e_step(Xtrain) + sum(covAll);
		}
		X = federated(
			addresses=list("localhost:8001/X1", "localhost:8002/X2",
				"localhost:8003/X3", "localhost:8004/X4"),
			ranges=list(list(0,0), list(25,8), list(25,0), list(50,8),
				list(50,0), list(75,8), list(75,0), list(100,8)));
		I = matrix(1, rows=1, cols=8);
		print(run(X, I));
		""";

	@Test
	public void privateAggregateFourWorkerSplitOutputRemainsRowAtNestedEStepInput() throws Exception {
		DMLProgram program = compile(P1_SPLIT_NESTED_INPUT);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		List<NeutralPlacementGraph.Node> formalReads = analysis.graph().decisionNodes().stream()
			.filter(node -> "e_step".equals(node.key().functionNamespace()))
			.filter(node -> analysis.hop(node.key()).orElse(null) instanceof DataOp data
				&& "X".equals(data.getName()))
			.filter(node -> analysis.logicalFunctionInputsInCanonicalOrder().stream()
				.anyMatch(fact -> fact.targetRead() == node.key()))
			.toList();
		Assert.assertFalse("Fixture must expose the e_step formal X read", formalReads.isEmpty());
		for(NeutralPlacementGraph.Node formalRead : formalReads) {
			Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(formalRead.key()));
			Assert.assertTrue("Split ROW/FOUT authority must cross the nested function boundary: " + formalRead,
				formalRead.legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT && state.fType() == FType.ROW));
			PlacementAnalysis.LogicalFunctionInputFact input = analysis.logicalFunctionInputsInCanonicalOrder().stream()
				.filter(fact -> fact.targetRead() == formalRead.key()).findFirst().orElseThrow();
			Assert.assertEquals("run", input.sourceArgument().functionNamespace());
			Assert.assertTrue("The e_step argument must be a compiled split output",
				analysis.hop(input.sourceArgument()).orElseThrow() instanceof DataOp data
					&& data.getName().startsWith("_sbcvar"));
		}
		Assert.assertNotNull("The complete four-worker P1 candidate graph must have a heuristic feasible selection",
			new HeuristicPlacementAdapter().select(analysis, Set.of()));

		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		ExactPhysicalOptimizer.Result optimized = ExactPhysicalOptimizer.optimize(
			model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		ExactPhysicalSelection exact = ExactPhysicalSelection.create(model, optimized);
		Assert.assertEquals("The exact P1 certificate must cover every physical decision",
			analysis.graph().decisionNodes().size(), exact.selectedStates().size());
	}

	private static DMLProgram compile(String script) throws Exception {
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
