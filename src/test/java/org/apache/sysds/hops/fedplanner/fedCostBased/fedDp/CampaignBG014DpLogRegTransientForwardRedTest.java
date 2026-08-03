/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireTransientForwardEdge;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateDecisionReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
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

/** Docker LogReg regression: transient forwards retain one exact CP/LOUT source authority. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014DpLogRegTransientForwardRedTest {
	@Test
	public void logRegDpKeepsExactTransientForwardAuthority() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		DMLConfig config = new DMLConfig(oldGlobal);
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
		ConfigurationManager.setGlobalConfig(config);
		ConfigurationManager.setLocalConfig(config);
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		PlacementEmissionTransaction.resetForTesting();
		int port = AutomatedTestBase.getRandomAvailablePort();
		Thread worker = null;
		try {
			Path features = matrixMetadata("target/g014-logreg-worker-features.data", 50000, 2100, 100050000);
			Path labels = matrixMetadata("target/g014-logreg-worker-labels.data", 50000, 1, 50000);
			worker = AutomatedTestBase.startLocalFedWorkerThread(port, 1000);
			ConfigurationManager.setGlobalConfig(config);
			ConfigurationManager.setLocalConfig(config);
			DMLProgram program = compile(logRegScript(port, features, labels));
			AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
			DMLTranslator translator = new DMLTranslator(program);
			translator.constructLops(program, captured::set);
			Assert.assertTrue("LogReg must use the DP planner, receipt=" + captured.get(),
				captured.get() instanceof DpInvocationReceipt);
			assertBranchJoinForwardUsesOnlyNeutralAuthorizedState((DpInvocationReceipt) captured.get());
			translator.getRuntimeProgram(program, config);
		}
		finally {
			TestUtils.shutdownThreads(worker);
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
		}
	}

	@Test
	public void logRegDpTwoWorkersEmitsExactSortedRelocationRegistryAuthorities() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		long oldLocalMaxMemory = InfrastructureAnalyzer.getLocalMaxMemory();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		int port1 = AutomatedTestBase.getRandomAvailablePort();
		int port2 = AutomatedTestBase.getRandomAvailablePort();
		while(port2 == port1)
			port2 = AutomatedTestBase.getRandomAvailablePort();
		Thread worker1 = null;
		Thread worker2 = null;
		Path root = Path.of("target/g014-dp-logreg-registry-slot");
		try {
			Files.createDirectories(root);
			Path features1 = matrixMetadata(root.resolve("features-1.data").toString(), 25000, 2100, 50025000);
			Path features2 = matrixMetadata(root.resolve("features-2.data").toString(), 25000, 2100, 50025000);
			Path labels1 = matrixMetadata(root.resolve("labels-1.data").toString(), 25000, 1, 25000);
			Path labels2 = matrixMetadata(root.resolve("labels-2.data").toString(), 25000, 1, 25000);
			Path script = root.resolve("logreg.dml");
			Path config = root.resolve("SystemDS-config.xml");
			Files.writeString(script, logRegScript(
				List.of(port1, port2), List.of(features1, features2), List.of(labels1, labels2)));
			Files.writeString(config, dockerCompileConfig(root));
			worker1 = AutomatedTestBase.startLocalFedWorkerThread(port1, 1000);
			worker2 = AutomatedTestBase.startLocalFedWorkerThread(port2, 1000);
			InfrastructureAnalyzer.setLocalMaxMemory(8L * 1024 * 1024 * 1024);
			boolean success = DMLScript.executeScript(new String[] {
				"-exec", "singlenode",
				"-seed", "2026072701",
				"-f", script.toString(),
				"-stats", "100",
				"-config", config.toString()
			});
			Assert.assertTrue("The Docker-equivalent two-worker LogReg compile must complete", success);
			Assert.assertEquals("The DP CLI fixture must publish one exact placement authority", 1,
				PlacementEmissionTransaction.receiptSnapshotForTesting().size());
			NormalizedPlannerResult result = PlacementEmissionTransaction.currentNormalizedResult(
				PlacementEmissionTransaction.receiptSnapshotForTesting().keySet().iterator().next());
			Assert.assertEquals("DP", result.plannerId());
			int checkedPresentInputs = 0;
			Map<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey,
				PlacementState> assignment = new LinkedHashMap<>();
			result.selectedEmissionStates().forEach((key, state) ->
				assignment.put(key, state.placementState()));
			for(var selection : result.selectedCandidateSelections()) {
				for(int inputPosition = 0; inputPosition < selection.rule().orderedInputs().size(); inputPosition++) {
					var input = selection.rule().orderedInputs().get(inputPosition);
					if(!input.present())
						continue;
					final int exactInputPosition = inputPosition;
					var edges = result.analysis().compiledInputEdgesInCanonicalOrder().stream()
						.filter(edge -> edge.consumer() == selection.rule().parentOccurrence()
							&& edge.inputPosition() == exactInputPosition).toList();
					if(edges.isEmpty())
						continue;
					Assert.assertEquals("A physical LogReg candidate input must have one exact producer",
						1, edges.size());
					PlacementState source = assignment.get(edges.get(0).producer());
					Assert.assertNotNull("A physical LogReg candidate input must have an emitted source state",
						source);
					boolean direct = source.output()
						== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
						&& source.fType() == input.fType();
					boolean relocated = result.selectedRelocationChoices().stream()
						.anyMatch(choice -> choice.demand().consumer() == selection.rule().parentOccurrence()
							&& choice.demand().inputPosition() == exactInputPosition
							&& choice.action().materializationFType() == input.fType());
					Assert.assertTrue("A PRESENT LogReg input must be direct or have one exact relocation",
						direct || relocated);
					checkedPresentInputs++;
				}
			}
			Assert.assertTrue("The two-worker LogReg fixture must exercise a physical PRESENT input",
				checkedPresentInputs > 0);
			List<FederatedRefedRegistry.AnchorSpec> authorities =
				FederatedRefedRegistry.snapshotAll().scopes().values().stream()
					.flatMap(scope -> scope.values().stream())
					.toList();
			Assert.assertEquals("Only planner-selected physical relocations may publish REFED owners",
				result.selectedRelocations().isEmpty(), authorities.isEmpty());
			for(FederatedRefedRegistry.AnchorSpec spec : authorities) {
				Assert.assertNotNull("Exact REFED authority must retain a durable anchor key", spec.getAnchorKey());
				Assert.assertNotNull("Exact REFED authority must retain its materialization FType",
					spec.getMaterializationFType());
				Assert.assertEquals("REFED consumers must remain sorted and deduplicated",
					spec.getConsumerHopIds().stream().distinct().sorted().toList(),
					spec.getConsumerHopIds());
			}
			Assert.assertEquals("Runtime fallback remains forbidden", 0L,
				PlacementEmissionTransaction.observabilitySnapshot().runtimeFallbackCount());
			Assert.assertEquals("Runtime repair remains forbidden", 0L,
				PlacementEmissionTransaction.observabilitySnapshot().runtimeRepairCount());
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			InfrastructureAnalyzer.setLocalMaxMemory(oldLocalMaxMemory);
			DMLScript.STATISTICS = oldStatistics;
			DMLScript.STATISTICS_COUNT = oldStatisticsCount;
			DMLScript.SEED = oldSeed;
			DMLScript.USE_LOCAL_SPARK_CONFIG = oldLocalSpark;
			DMLScript.DML_FILE_PATH_ANTLR_PARSER = oldParserPath;
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
		}
	}

	private static void assertBranchJoinForwardUsesOnlyNeutralAuthorizedState(DpInvocationReceipt receipt) {
		List<RewireTransientForwardEdge> schedulingOnly = receipt.semanticConsumption().rewireSnapshot()
			.transientForwardEdges().stream().filter(edge -> {
				String writeName = receipt.analysis().hop(edge.writeOccurrence()).orElseThrow().getName();
				String readName = receipt.analysis().hop(edge.readOccurrence()).orElseThrow().getName();
				boolean hasPhysicalOwner = receipt.analysis().compiledInputEdgesInCanonicalOrder().stream()
					.anyMatch(fact -> fact.producer() == edge.writeOccurrence()
						&& fact.consumer() == edge.readOccurrence());
				boolean hasLogicalOwner = receipt.analysis().logicalTransientInputsInCanonicalOrder().stream()
					.anyMatch(fact -> fact.sourceWrite() == edge.writeOccurrence()
						&& fact.targetRead() == edge.readOccurrence());
				return "rowSums_X_sq".equals(writeName) && "rowSums_X_sq".equals(readName)
					&& !hasPhysicalOwner && !hasLogicalOwner;
			}).toList();
		Assert.assertFalse("LogReg must retain the branch-join scheduling dependency", schedulingOnly.isEmpty());
		for(RewireTransientForwardEdge edge : schedulingOnly) {
			Assert.assertTrue("branch-join read must be neutral-authorized only as CP/LOUT",
				receipt.analysis().graph().node(edge.readOccurrence()).orElseThrow().legalAlternatives().stream()
					.allMatch(state -> state.execType() == ExecType.CP
						&& state.output() == FederatedOutput.LOUT && state.fType() == null));
			List<CandidateDecisionReceipt> decisions = receipt.semanticConsumption().semanticBlock()
				.candidateDecisionReceipts().stream()
					.filter(decision -> decision.candidateSnapshot().parentOccurrence() == edge.readOccurrence())
					.toList();
			Assert.assertFalse("branch-join read must retain a local candidate", decisions.isEmpty());
			Assert.assertTrue("scheduling-only edge must not manufacture FED/FOUT authority",
				decisions.stream().noneMatch(decision -> decision.allowFEDFOUT()));
		}
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

	private static String logRegScript(int port, Path features, Path labels) {
		return "N=50000;\n" +
			"D=2100;\n" +
			"X=federated(addresses=list(\"" + TestUtils.federatedAddress(port, features.toString()) + "\")," +
				"ranges=list(list(0,0),list(N,D)));\n" +
			"Y=federated(addresses=list(\"" + TestUtils.federatedAddress(port, labels.toString()) + "\")," +
				"ranges=list(list(0,0),list(N,1)));\n" +
			"Y=(Y<0)+1;\n" +
			"m=multiLogReg(X=X,Y=Y,verbose=FALSE,maxi=30,maxii=5,tol=1e-9,icpt=0," +
				"numclasses=2,numrows=N,numcols=D);\n" +
			"write(m,\"target/g014-dp-logreg-transient-forward.csv\",format=\"csv\");\n";
	}

	private static String logRegScript(List<Integer> ports, List<Path> features, List<Path> labels) {
		return "N=50000;\n" +
			"D=2100;\n" +
			"X=federated(addresses=list(\"" + TestUtils.federatedAddress(ports.get(0), features.get(0).toString())
				+ "\",\"" + TestUtils.federatedAddress(ports.get(1), features.get(1).toString()) + "\"),"
				+ "ranges=list(list(0,0),list(25000,D),list(25000,0),list(N,D)));\n" +
			"Y=federated(addresses=list(\"" + TestUtils.federatedAddress(ports.get(0), labels.get(0).toString())
				+ "\",\"" + TestUtils.federatedAddress(ports.get(1), labels.get(1).toString()) + "\"),"
				+ "ranges=list(list(0,0),list(25000,1),list(25000,0),list(N,1)));\n" +
			"Y=(Y<0)+1;\n" +
			"m=multiLogReg(X=X,Y=Y,verbose=FALSE,maxi=30,maxii=5,tol=1e-9,icpt=0," +
				"numclasses=2,numrows=N,numcols=D);\n" +
			"write(m,\"target/g014-dp-logreg-transient-forward-two-workers.csv\",format=\"csv\");\n";
	}

	private static String dockerCompileConfig(Path root) {
		return "<root>\n"
			+ "  <sysds.native.blas>none</sysds.native.blas>\n"
			+ "  <sysds.local.spark>true</sysds.local.spark>\n"
			+ "  <sysds.federated.planner>compile_cost_based</sysds.federated.planner>\n"
			+ "  <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>\n"
			+ "  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>\n"
			+ "  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>\n"
			+ "</root>\n";
	}

	private static Path matrixMetadata(String path, long rows, long cols, long nnz) throws Exception {
		Path data = Path.of(path);
		Files.createDirectories(data.getParent());
		Files.writeString(data, "");
		Path mtd = Path.of(path + ".mtd");
		Files.writeString(mtd, "{\"data_type\":\"matrix\",\"value_type\":\"double\"," +
			"\"format\":\"text\",\"rows\":" + rows + ",\"cols\":" + cols + ",\"nnz\":" + nnz + "," +
			"\"privacy\":\"private-aggregate\"}");
		data.toFile().deleteOnExit();
		mtd.toFile().deleteOnExit();
		return data;
	}
}
