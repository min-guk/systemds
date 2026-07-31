/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.utils.stats.InfrastructureAnalyzer;
import org.junit.Assert;
import org.junit.Test;

/** Docker PCA regression: a planned refed must never be lowered over an already-federated source. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014DpPcaRefedLoweringRedTest {
	@Test
	public void pcaDpFourRowPartitionsKeepOneExactSelectionPerOccurrence() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		long oldLocalMaxMemory = InfrastructureAnalyzer.getLocalMaxMemory();
		Map<String, String> oldCostProperties = installDockerLanCostProperties();
		DMLConfig config = new DMLConfig(oldGlobal);
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
		config.setTextValue(DMLConfig.NATIVE_BLAS, "mkl");
		ConfigurationManager.setGlobalConfig(config);
		ConfigurationManager.setLocalConfig(config);
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		PlacementEmissionTransaction.resetForTesting();
		int[] ports = uniquePorts(4);
		Thread[] workers = new Thread[4];
		try {
			InfrastructureAnalyzer.setLocalMaxMemory(8L * 1024 * 1024 * 1024);
			Path root = Path.of("target/g014-dp-pca-four-row-partitions");
			Files.createDirectories(root);
			Path[] inputs = new Path[4];
			for(int i = 0; i < inputs.length; i++) {
				inputs[i] = matrixMetadata(root.resolve("features-" + (i + 1) + ".data"));
				workers[i] = AutomatedTestBase.startLocalFedWorkerThread(ports[i], 1000);
			}
			ConfigurationManager.setGlobalConfig(config);
			ConfigurationManager.setLocalConfig(config);
			DMLProgram program = compile(pcaFourRowPartitionsScript(ports, inputs));
			AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
			DMLTranslator translator = new DMLTranslator(program);
			translator.constructLops(program, captured::set);
			Assert.assertTrue("PCA must use the DP planner", captured.get() instanceof DpInvocationReceipt);
			DpInvocationReceipt receipt = (DpInvocationReceipt) captured.get();
			Assert.assertEquals("fixture must retain four-worker cost geometry", 4, receipt.memo().getNumWorkers());
			long centeredXOccurrences = receipt.analysis().occurrences().stream()
				.filter(occurrence -> occurrence.key().functionNamespace().endsWith("::m_pca"))
				.filter(occurrence -> occurrence.hop().getOpString().equals("b(-)"))
				.filter(occurrence -> occurrence.hop().getName().equals("X"))
				.peek(occurrence -> Assert.assertNotNull("centered X must have one normalized exact state",
					receipt.normalizedResult().selectedEmissionStates().get(occurrence.key())))
				.count();
			Assert.assertEquals("fixture must expose the Docker centered-X occurrence", 1, centeredXOccurrences);
			translator.getRuntimeProgram(program, config);
		}
		finally {
			TestUtils.shutdownThreads(workers);
			InfrastructureAnalyzer.setLocalMaxMemory(oldLocalMaxMemory);
			restoreProperties(oldCostProperties);
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
	public void pcaDpDoesNotPublishRefedForAlreadyFederatedLop() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		DMLConfig config = new DMLConfig(oldGlobal);
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
		ConfigurationManager.setGlobalConfig(config);
		ConfigurationManager.setLocalConfig(config);
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		PlacementEmissionTransaction.resetForTesting();
		try {
			DMLProgram program = compile(pcaScript());
			AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
			DMLTranslator translator = new DMLTranslator(program);
			translator.constructLops(program, captured::set);
			Assert.assertTrue("PCA must use the DP planner", captured.get() instanceof DpInvocationReceipt);
			DpInvocationReceipt receipt = (DpInvocationReceipt) captured.get();

			StringBuilder conflicts = new StringBuilder();
			for(Map.Entry<Long, Map<Long, FederatedRefedRegistry.AnchorSpec>> scope :
				FederatedRefedRegistry.snapshotAll().scopes().entrySet()) {
				for(Map.Entry<Long, FederatedRefedRegistry.AnchorSpec> entry : scope.getValue().entrySet()) {
					for(HopOccurrenceProjection occurrence : receipt.analysis().occurrences()) {
						Hop hop = occurrence.hop();
						if(occurrence.scopeId() != scope.getKey() || hop.getHopID() != entry.getKey())
							continue;
						Lop lop = hop.getLops();
						if(lop != null && lop.getFederatedOutput().isForcedFederated())
							conflicts.append("scope=").append(scope.getKey())
								.append(" occurrence=").append(occurrence.key().normalizedSignature())
								.append(" hop=").append(hop.getHopID()).append(':').append(hop.getOpString())
								.append(" hopState=").append(hop.getForcedExecType()).append('/')
								.append(hop.getFederatedOutput())
								.append(" lopState=").append(lop.getExecType()).append('/')
								.append(lop.getFederatedOutput())
								.append(" selected=").append(receipt.normalizedResult().selectedEmissionStates()
									.get(occurrence.key()))
								.append(" anchors=").append(receipt.analysis().graph().node(occurrence.key())
									.orElseThrow().anchors())
								.append(" relocations=").append(receipt.normalizedResult().selectedRelocations().stream()
									.filter(action -> action.sourceValueVersion().equals(receipt.analysis().graph()
										.node(occurrence.key()).orElseThrow().valueVersion())).toList())
								.append(" spec=").append(entry.getValue().getAnchorKey())
								.append(" consumers=").append(entry.getValue().getConsumerHopIds()).append('\n');
					}
				}
			}
			Assert.assertEquals("A refed registry entry targets an already-federated lop:\n" + conflicts,
				"", conflicts.toString());
			// Exercise the same Dag.getJobs lowering boundary that failed in the Docker canary.
			translator.getRuntimeProgram(program, config);
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
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

	private static String pcaScript() throws Exception {
		Path data = Files.createTempFile("g014-pca-refed-", ".data");
		Path mtd = Path.of(data + ".mtd");
		Files.writeString(data, "");
		Files.writeString(mtd, "{\"data_type\":\"matrix\",\"value_type\":\"double\"," +
			"\"format\":\"text\",\"rows\":50000,\"cols\":2100,\"nnz\":100050000," +
			"\"privacy\":\"private-aggregate\"}");
		data.toFile().deleteOnExit();
		mtd.toFile().deleteOnExit();
		String path = data.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		return "X_LOCAL=read(\"" + path + "\");\n" +
			"X=federated(local_matrix=X_LOCAL,addresses=list(\"localhost:1234\")," +
			"ranges=list(list(0,0),list(50000,2100)));\n" +
			"[Xout,Mout]=pca(X=X,K=10);\n" +
			"write(Mout,\"target/g014-pca-refed-lowering.csv\",format=\"csv\");\n";
	}

	private static String pcaFourRowPartitionsScript(int[] ports, Path[] inputs) {
		return "X=federated(addresses=list(\"" + TestUtils.federatedAddress(ports[0], inputs[0].toString())
			+ "\",\"" + TestUtils.federatedAddress(ports[1], inputs[1].toString())
			+ "\",\"" + TestUtils.federatedAddress(ports[2], inputs[2].toString())
			+ "\",\"" + TestUtils.federatedAddress(ports[3], inputs[3].toString())
			+ "\"),ranges=list(list(0,0),list(12500,2100)," +
			"list(12500,0),list(25000,2100),list(25000,0),list(37500,2100)," +
			"list(37500,0),list(50000,2100)));\n" +
			"[Xout,Mout]=pca(X=X,K=10);\n" +
			"write(Mout,\"target/g014-pca-four-row-partitions.csv\",format=\"csv\");\n";
	}

	private static Path matrixMetadata(Path data) throws Exception {
		Files.createDirectories(data.getParent());
		Files.writeString(data, "");
		Files.writeString(Path.of(data + ".mtd"), "{\"data_type\":\"matrix\"," +
			"\"value_type\":\"double\",\"format\":\"binary\",\"rows\":12500," +
			"\"cols\":2100,\"rows_in_block\":1000,\"cols_in_block\":1000," +
			"\"nnz\":25012500,\"privacy\":\"private-aggregate\"}");
		return data;
	}

	private static int[] uniquePorts(int count) {
		int[] ports = new int[count];
		for(int i = 0; i < count; i++) {
			boolean duplicate;
			do {
				ports[i] = AutomatedTestBase.getRandomAvailablePort();
				duplicate = false;
				for(int j = 0; j < i; j++)
					duplicate |= ports[i] == ports[j];
			}
			while(duplicate);
		}
		return ports;
	}

	private static Map<String, String> installDockerLanCostProperties() {
		Map<String, String> values = Map.of(
			"SYSDS_FED_COST_MEM_BW", "25000",
			"SYSDS_FED_COST_NET_BW", "1250",
			"SYSDS_FED_COST_NET_BW_C2W", "1250",
			"SYSDS_FED_COST_NET_BW_W2C", "1250",
			"SYSDS_FED_COST_NET_SERDES_BW", "210",
			"SYSDS_FED_COST_NET_SERDES_BW_C2W", "210",
			"SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7",
			"SYSDS_FED_COST_NET_LATENCY", "0.001",
			"SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "0",
			"SYSDS_FED_COST_FLOPS", "2147483648");
		Map<String, String> previous = new HashMap<>();
		values.forEach((key, value) -> {
			previous.put(key, System.getProperty(key));
			System.setProperty(key, value);
		});
		return previous;
	}

	private static void restoreProperties(Map<String, String> previous) {
		previous.forEach((key, value) -> {
			if(value == null)
				System.clearProperty(key);
			else
				System.setProperty(key, value);
		});
	}
}
