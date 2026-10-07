/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.BranchPlacementNormalization;
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

	@Test
	public void pcaBranchCarrierPreservesFoutForFollowingFunctionBinding() throws Exception {
		DMLProgram program = compile("""
			X = federated(addresses=list("localhost:8001/X"),
				ranges=list(list(0,0), list(100000,68)));
			X1H = X;
			X1H = replace(target=X1H, pattern=NaN, replacement=0);
			P = cor(X1H);
			[XReduced, components, center, scale] = pca(X=X1H, K=2);
			[C, Y] = kmeans(X=XReduced, k=2, runs=1, seed=7);
			print(sum(P) + sum(C) + sum(Y));
			""");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		DMLConfig prior = ConfigurationManager.getDMLConfig();
		DMLConfig exact = new DMLConfig(prior);
		exact.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_fed_heuristic_single_pass");
		try {
			ConfigurationManager.setGlobalConfig(exact);
			ConfigurationManager.setLocalConfig(exact);
			new DMLTranslator(program).prepareSearchSpaceOnly(program, false);
		}
		finally {
			ConfigurationManager.setGlobalConfig(prior);
			ConfigurationManager.setLocalConfig(prior);
		}
		PlacementAnalysis analysis = program.requirePlacementAnalysisAuthority();
		List<NeutralPlacementGraph.Node> reads = analysis.graph().decisionNodes().stream()
			.filter(node -> "main".equals(node.key().functionNamespace()))
			.filter(node -> analysis.hop(node.key()).orElse(null) instanceof DataOp data
				&& "XReduced".equals(data.getName()))
			.toList();
		Assert.assertFalse("Fixture must retain the PCA result read", reads.isEmpty());
		Assert.assertTrue("Branch-normalized derived PCA output must retain its one-worker FOUT provenance",
			reads.stream().anyMatch(node -> node.legalAlternatives().stream().anyMatch(state ->
				state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
					&& state.fType() == FType.FULL)));
	}

	@Test
	public void pcaAndFederatedKmeansCallsKeepExactBindingProvenance() throws Exception {
		DMLProgram program = compile("""
			X = federated(addresses=list("localhost:8001/X"),
				ranges=list(list(0,0), list(100,8)));
			[XReduced, components, center, scale] = pca(X=X, K=2);
			[C1, Y1] = kmeans(X=XReduced, k=2, runs=1, seed=7);
			[C2, Y2] = kmeans(X=X, k=2, runs=1, seed=7);
			print(sum(C1) + sum(Y1) + sum(C2) + sum(Y2));
			""");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		BranchPlacementNormalization.prepare(program);

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		List<PlacementAnalysis.LogicalFunctionInputFact> inputs =
			analysis.logicalFunctionInputsInCanonicalOrder().stream()
				.filter(fact -> fact.targetRead().functionNamespace().contains("m_kmeans"))
				.filter(fact -> analysis.hop(fact.targetRead()).orElse(null) instanceof DataOp data
					&& "X".equals(data.getName()))
				.toList();
		Assert.assertTrue("Both exact k-means call arguments must remain represented", inputs.stream()
			.map(fact -> analysis.hop(fact.sourceArgument()).orElseThrow().getName())
			.collect(java.util.stream.Collectors.toSet()).containsAll(Set.of("X", "XReduced")));
	}

	@Test
	public void postPrivacyProducerReplacementRebindsTransientWriteBeforeFunctionReplay() throws Exception {
		DMLProgram program = compile("""
			X = federated(addresses=list("localhost:8001/X"),
				ranges=list(list(0,0), list(100,8)));
			[R, A, B, D] = pca(X=X, K=2);
			[C, L] = kmeans(X=R, k=2, runs=1, seed=7);
			Y = scale(X=X, center=TRUE, scale=TRUE);
			Z = scale(X=Y, center=TRUE, scale=TRUE);
			print(sum(C) + sum(Z));
			""");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		DMLConfig prior = ConfigurationManager.getDMLConfig();
		DMLConfig costBased = new DMLConfig(prior);
		costBased.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
		try {
			ConfigurationManager.setGlobalConfig(costBased);
			ConfigurationManager.setLocalConfig(costBased);
			new DMLTranslator(program).prepareSearchSpaceOnly(program, false);
		}
		finally {
			ConfigurationManager.setGlobalConfig(prior);
			ConfigurationManager.setLocalConfig(prior);
		}

		PlacementAnalysis analysis = program.requirePlacementAnalysisAuthority();
		List<NeutralPlacementGraph.Node> pcaResults = analysis.graph().decisionNodes().stream()
			.filter(node -> "main".equals(node.key().functionNamespace()))
			.filter(node -> analysis.hop(node.key()).orElse(null) instanceof DataOp data
				&& "R".equals(data.getName()))
			.toList();
		Assert.assertFalse("Fixture must retain the PCA result read", pcaResults.isEmpty());
		Assert.assertTrue("The rebuilt PCA result must retain an executable FULL/FOUT proof",
			pcaResults.stream().anyMatch(node -> node.legalAlternatives().stream().anyMatch(state ->
				state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
					&& state.fType() == FType.FULL)));
		List<PlacementAnalysis.LogicalFunctionInputFact> secondScaleInputs =
			analysis.logicalFunctionInputsInCanonicalOrder().stream()
				.filter(fact -> analysis.hop(fact.sourceArgument()).orElse(null) instanceof DataOp data
					&& "Y".equals(data.getName()))
				.filter(fact -> fact.targetRead().functionNamespace().contains("m_scale"))
				.toList();
		Assert.assertFalse("The second scale call must retain its exact Y binding", secondScaleInputs.isEmpty());
		Assert.assertTrue("The rebound Y source must remain available at the scale formal",
			secondScaleInputs.stream().allMatch(fact -> analysis.graph().node(fact.sourceArgument()).orElseThrow()
				.legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT && state.fType() == FType.FULL)));
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
