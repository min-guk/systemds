/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalFunctionInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.utils.stats.InfrastructureAnalyzer;
import org.junit.Assert;
import org.junit.Test;

/** Docker StepLM regression: function formals retain every caller-supplyable boundary placement. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014DpStepLmFunctionInputCandidateRedTest {
	@Test
	public void stepLmDpRetainsExactFunctionInputCandidates() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		long oldLocalMaxMemory = InfrastructureAnalyzer.getLocalMaxMemory();
		DMLConfig config = new DMLConfig(oldGlobal);
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
		CompilerConfig compiler = OptimizerUtils.constructCompilerConfig(config);
		ConfigurationManager.setGlobalConfig(config);
		ConfigurationManager.setLocalConfig(config);
		ConfigurationManager.setGlobalConfig(compiler);
		ConfigurationManager.setLocalConfig(compiler);
		InfrastructureAnalyzer.setLocalMaxMemory(8L * 1024 * 1024 * 1024);
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		PlacementEmissionTransaction.resetForTesting();
		int port = AutomatedTestBase.getRandomAvailablePort();
		Thread worker = null;
		try {
			Path features = matrixMetadata("target/g014-steplm-worker-features.data", 50000, 2100, 100050000);
			Path labels = matrixMetadata("target/g014-steplm-worker-labels.data", 50000, 1, 50000);
			worker = AutomatedTestBase.startLocalFedWorkerThread(port, 1000);
			ConfigurationManager.setGlobalConfig(config);
			ConfigurationManager.setLocalConfig(config);
			ConfigurationManager.setGlobalConfig(compiler);
			ConfigurationManager.setLocalConfig(compiler);
			DMLProgram program = compile(stepLmScript(port, features, labels));
			AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
			DMLTranslator translator = new DMLTranslator(program);
				translator.constructLops(program, captured::set);
				Assert.assertTrue("StepLM must use the DP planner, receipt=" + captured.get(),
					captured.get() instanceof DpInvocationReceipt);
				assertLinearRegressionFunctionInputClosure((DpInvocationReceipt) captured.get());
				translator.getRuntimeProgram(program, config);
				assertFinalRecompileStatesMatchExecutableHops((DpInvocationReceipt) captured.get());
		}
		finally {
			TestUtils.shutdownThreads(worker);
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			InfrastructureAnalyzer.setLocalMaxMemory(oldLocalMaxMemory);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
		}
	}

	private static void assertFinalRecompileStatesMatchExecutableHops(DpInvocationReceipt receipt) {
		PlacementAnalysis analysis = receipt.analysis();
		Set<Integer> callSiteLines = Set.of(98, 133, 172);
		for(var occurrence : analysis.occurrences()) {
			var hop = occurrence.hop();
			if(!callSiteLines.contains(hop.getBeginLine()) || !"y".equals(hop.getName())
				|| !"TRead y".equals(hop.getOpString()))
				continue;
			var state = FederatedPlannerUtils.getPlannerRecompileState(hop);
			Assert.assertNotNull("StepLM executable y read must retain a recompile state at line "
				+ hop.getBeginLine(), state);
			ExecType executableExec = hop.getForcedExecType() != null
				? hop.getForcedExecType() : hop.getExecType();
			Assert.assertEquals("Recompile registry must match the final executable y-read exec state at line "
				+ hop.getBeginLine(), executableExec, state.getExecType());
			Assert.assertEquals("Recompile registry must match the final executable y-read output state at line "
				+ hop.getBeginLine(), hop.getFederatedOutput(), state.getFederatedOutput());
		}
	}

	private static void assertLinearRegressionFunctionInputClosure(DpInvocationReceipt receipt) {
		PlacementAnalysis analysis = receipt.analysis();
		List<LogicalFunctionInputFact> facts = analysis.logicalFunctionInputsInCanonicalOrder().stream()
			.filter(fact -> ".builtinNS::linear_regression".equals(fact.targetRead().functionNamespace()))
			.filter(fact -> Set.of("X", "y").contains(analysis.hop(fact.targetRead()).orElseThrow().getName()))
			.toList();
		Assert.assertFalse("StepLM must expose exact linear_regression caller bindings", facts.isEmpty());
		assertSharedFormalPlansCoverEveryCaller(receipt, facts);
		Set<String> formals = new LinkedHashSet<>();
		for(LogicalFunctionInputFact fact : facts) {
			String formal = analysis.hop(fact.targetRead()).orElseThrow().getName();
			formals.add(formal);
			Node source = analysis.graph().node(fact.sourceArgument()).orElseThrow();
			Node boundary = analysis.graph().node(fact.boundary()).orElseThrow();
			Node target = analysis.graph().node(fact.targetRead()).orElseThrow();
			Set<CandidateInputState> expectedInputs = new LinkedHashSet<>();
			facts.stream().filter(candidate -> candidate.targetRead() == fact.targetRead())
				.map(candidate -> analysis.graph().node(candidate.sourceArgument()).orElseThrow())
				.flatMap(node -> node.legalAlternatives().stream())
				.map(CampaignBG014DpStepLmFunctionInputCandidateRedTest::candidateInput)
				.filter(java.util.Objects::nonNull).forEach(expectedInputs::add);
			for(PlacementState sourceState : source.legalAlternatives()) {
				CandidateInputState input = candidateInput(sourceState);
				if(input == null)
					continue;
				CandidateRuleFact exact = analysis.candidateRuleFacts().requireExact(fact.targetRead(), List.of(input));
				Assert.assertEquals("Formal TRead candidate must remain executable: " + formal,
					CandidateEvaluationStatus.AVAILABLE, exact.status());
				Assert.assertTrue("Formal TRead emissions must obey CP/LOUT or FED/FOUT: " + formal,
					exact.allowedEmissionStates().stream().allMatch(emission -> {
						PlacementState state = emission.placementState();
						return state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT
							|| state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT;
					}));
			}
			for(PlacementState boundaryState : boundary.legalAlternatives()) {
				Assert.assertTrue("Synthetic input boundary must obey transient placement legality: " + formal,
					isLegalTransient(boundaryState));
				Assert.assertTrue("Synthetic input boundary must retain an exact formal placement tuple: " + formal,
					target.legalAlternatives().contains(boundaryState));
				Assert.assertTrue("Every formal-owned boundary state must be supplyable by its caller: " + formal,
					sourceCanSupply(source, boundaryState));
			}
			for(PlacementState targetState : target.legalAlternatives())
				if(isLegalTransient(targetState) && sourceCanSupply(source, targetState))
					Assert.assertTrue("Boundary must retain every caller-supplyable exact formal tuple: " + formal,
						boundary.legalAlternatives().contains(targetState));
			Set<CandidateInputState> actualInputs = new LinkedHashSet<>();
			analysis.candidateRuleDomain().orderedRuleKeys().stream()
				.filter(key -> key.parentOccurrence() == fact.targetRead())
				.forEach(key -> {
					Assert.assertEquals("Formal TRead candidates have one logical input", 1, key.orderedInputs().size());
					actualInputs.add(key.orderedInputs().get(0));
				});
			Assert.assertEquals("Formal TRead domain must equal the final caller-state union: " + formal,
				expectedInputs, actualInputs);
			Assert.assertTrue("Formal TRead node itself must obey transient placement legality: " + formal,
				target.legalAlternatives().stream().allMatch(CampaignBG014DpStepLmFunctionInputCandidateRedTest::isLegalTransient));
		}
		Assert.assertEquals("StepLM must close both linear_regression matrix formals", Set.of("X", "y"), formals);
		Assert.assertTrue("StepLM linear_regression formals must retain a federated caller candidate",
			facts.stream().map(fact -> analysis.graph().node(fact.sourceArgument()).orElseThrow())
				.flatMap(node -> node.legalAlternatives().stream())
				.anyMatch(state -> state.output() == FederatedOutput.FOUT && state.fType() != null));
	}

	private static void assertSharedFormalPlansCoverEveryCaller(DpInvocationReceipt receipt,
		List<LogicalFunctionInputFact> facts) {
		PlacementAnalysis analysis = receipt.analysis();
		Set<CompiledHopKey> checked = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for(LogicalFunctionInputFact fact : facts) {
			if(!checked.add(fact.targetRead()))
				continue;
			List<Long> expectedSources = facts.stream()
				.filter(candidate -> candidate.targetRead() == fact.targetRead())
				.map(candidate -> analysis.hop(candidate.sourceArgument()).orElseThrow().getHopID()).toList();
			var occurrence = analysis.occurrences().stream()
				.filter(candidate -> candidate.key() == fact.targetRead()).findFirst().orElseThrow();
			var arms = receipt.memo().getExactPlanArmsForOccurrence(occurrence);
			Assert.assertFalse("Shared function formal must retain at least one DP arm", arms.isEmpty());
			for(var arm : arms)
				Assert.assertEquals("Every shared-formal DP arm must account for every exact caller source: "
					+ analysis.hop(fact.targetRead()).orElseThrow().getName(), expectedSources,
					arm.plan().getChildFedPlans().stream().map(edge -> edge.getLeft()).toList());
		}
	}

	private static boolean isLegalTransient(PlacementState state) {
		return state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT
			|| state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT;
	}

	private static boolean sourceCanSupply(Node source, PlacementState targetState) {
		return source.legalAlternatives().stream().anyMatch(sourceState ->
			targetState.output() == FederatedOutput.LOUT
				? sourceState.output() == FederatedOutput.LOUT || sourceState.output() == FederatedOutput.FOUT
				: sourceState.output() == FederatedOutput.FOUT
					&& sourceState.fType() != null && sourceState.fType() == targetState.fType());
	}

	private static CandidateInputState candidateInput(PlacementState state) {
		return state.output() == FederatedOutput.FOUT && state.fType() != null
			? CandidateInputState.present(state.fType())
			: state.output() == FederatedOutput.LOUT ? CandidateInputState.absentLocal() : null;
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

	private static String stepLmScript(int port, Path features, Path labels) {
		return "X = federated(addresses=list(\"" + TestUtils.federatedAddress(port, features.toString()) + "\"), " +
				"ranges=list(list(0, 0), list(50000, 2100)))\n" +
			"Y = federated(addresses=list(\"" + TestUtils.federatedAddress(port, labels.toString()) + "\"), " +
				"ranges=list(list(0, 0), list(50000, 1)))\n\n" +
			"[B, S] = steplm(X=X, y=Y, icpt=0, reg=1e-7, tol=1e-7, maxi=20, verbose=FALSE)\n\n" +
			"write(B, \"target/g014-dp-steplm-function-input.csv\", format=\"csv\")\n";
	}

	private static Path matrixMetadata(String path, long rows, long cols, long nnz) throws Exception {
		Path data = Path.of(path);
		Files.createDirectories(data.getParent());
		Files.writeString(data, "");
		Path mtd = Path.of(path + ".mtd");
		Files.writeString(mtd, "{\"data_type\":\"matrix\",\"value_type\":\"double\"," +
			"\"format\":\"binary\",\"rows\":" + rows + ",\"cols\":" + cols
			+ ",\"rows_in_block\":1000,\"cols_in_block\":1000,\"nnz\":" + nnz + "," +
			"\"privacy\":\"private-aggregate\"}");
		data.toFile().deleteOnExit();
		mtd.toFile().deleteOnExit();
		return data;
	}
}
