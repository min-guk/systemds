/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.AppliedPlanReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

/** Regression for Docker LAN KMeans: legality repair may replace one superseded aggregate root. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014DpKMeansSupersededRootReplacementRedTest {
	@Test
	public void threeWorkerLanKMeansClassifiesSupersededRootReplacement() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		Map<String,String> oldCostProperties = installDockerLanCostProperties();
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		PlacementEmissionTransaction.resetForTesting();
		List<Integer> ports = distinctPorts(3);
		Thread worker1 = null;
		Thread worker2 = null;
		Thread worker3 = null;
		Path root = Path.of("target/g014-dp-kmeans-additional-root-dedup");
		try {
			DMLConfig config = new DMLConfig(oldGlobal);
			config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
			ConfigurationManager.setGlobalConfig(config);
			ConfigurationManager.setLocalConfig(config);
			List<Path> inputs = List.of(
				matrixMetadata(root.resolve("features-1.data"), 16667, 2100, 33350667),
				matrixMetadata(root.resolve("features-2.data"), 16667, 2100, 33350667),
				matrixMetadata(root.resolve("features-3.data"), 16666, 2100, 33348666));
			worker1 = AutomatedTestBase.startLocalFedWorkerThread(ports.get(0), 1000);
			worker2 = AutomatedTestBase.startLocalFedWorkerThread(ports.get(1), 1000);
			worker3 = AutomatedTestBase.startLocalFedWorkerThread(ports.get(2), 1000);
			ConfigurationManager.setGlobalConfig(config);
			ConfigurationManager.setLocalConfig(config);

			DMLProgram program = compile(kmeansScript(ports, inputs, root.resolve("result.csv")));
			AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
			new DMLTranslator(program).constructLops(program, captured::set);
			Assert.assertTrue("KMeans must use the DP planner", captured.get() instanceof DpInvocationReceipt);
			DpInvocationReceipt receipt = (DpInvocationReceipt) captured.get();
			Assert.assertEquals("fixture must preserve the three-worker LAN cost geometry", 3,
				receipt.memo().getNumWorkers());

			Map<Long,List<AppliedPlanReceipt>> appliedById = new LinkedHashMap<>();
			receipt.appliedPlans().forEach(applied ->
				appliedById.computeIfAbsent(applied.planningHopId(), ignored -> new java.util.ArrayList<>())
					.add(applied));
			List<List<AppliedPlanReceipt>> replacements = appliedById.values().stream()
				.filter(group -> group.size() > 1).toList();
			Assert.assertFalse("The Docker failure requires a superseded-root replacement",
				replacements.isEmpty());
			int preCompletionCount = receipt.appliedPlans().size()
				- receipt.disconnectedCompletionReceipts().size();
			for(List<AppliedPlanReceipt> replacement : replacements) {
				Assert.assertEquals("Only one final replacement is legal", 2, replacement.size());
				AppliedPlanReceipt prior = replacement.get(0);
				AppliedPlanReceipt finalPlan = replacement.get(1);
				Assert.assertTrue(prior.ordinal() < preCompletionCount);
				Assert.assertTrue(finalPlan.ordinal() >= preCompletionCount);
				Assert.assertNotSame("Legality repair must replace, not reapply, the same plan",
					prior.plan(), finalPlan.plan());
				Assert.assertSame(prior.planningHop(), finalPlan.planningHop());
				var occurrence = receipt.memo().requirePlanCarrierOccurrence(finalPlan.planningHop());
				Assert.assertTrue("The pre-completion authority must be explicitly superseded",
					receipt.supersededPreCompletionKeys().stream().anyMatch(key -> key == occurrence.key()));
				Assert.assertTrue("The replacement must be the final disconnected-component sink",
					receipt.disconnectedCompletionReceipts().stream().anyMatch(completion ->
						completion.appliedPlan() == finalPlan && completion.sinkRoot() == occurrence.key()));
			}
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2, worker3);
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
			restoreProperties(oldCostProperties);
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

	private static String kmeansScript(List<Integer> ports, List<Path> inputs, Path output) {
		return "N=50000; D=2100;\n"
			+ "X=federated(addresses=list(\"" + TestUtils.federatedAddress(ports.get(0), inputs.get(0).toString())
			+ "\",\"" + TestUtils.federatedAddress(ports.get(1), inputs.get(1).toString())
			+ "\",\"" + TestUtils.federatedAddress(ports.get(2), inputs.get(2).toString()) + "\"),"
			+ "ranges=list(list(0,0),list(16667,D),list(16667,0),list(33334,D),"
			+ "list(33334,0),list(N,D)));\n"
			+ "[C,Y]=kmeans(X=X,k=50,is_verbose=FALSE,runs=1,eps=1e-9,max_iter=60,"
			+ "avg_sample_size_per_centroid=50,seed=133815928);\n"
			+ "write(Y,\"" + output + "\",format=\"csv\");\n";
	}

	private static Path matrixMetadata(Path data, long rows, long cols, long nnz) throws Exception {
		Files.createDirectories(data.getParent());
		Files.writeString(data, "");
		Files.writeString(Path.of(data + ".mtd"), "{\"data_type\":\"matrix\","
			+ "\"value_type\":\"double\",\"format\":\"binary\",\"rows\":" + rows
			+ ",\"cols\":" + cols + ",\"rows_in_block\":1000,\"cols_in_block\":1000,"
			+ "\"nnz\":" + nnz + ",\"privacy\":\"private-aggregate\"}");
		return data;
	}

	private static List<Integer> distinctPorts(int count) {
		Set<Integer> ports = new java.util.LinkedHashSet<>();
		while(ports.size() < count)
			ports.add(AutomatedTestBase.getRandomAvailablePort());
		return List.copyOf(ports);
	}

	private static Map<String,String> installDockerLanCostProperties() {
		Map<String,String> values = Map.of(
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
		Map<String,String> previous = new HashMap<>();
		values.forEach((key, value) -> {
			previous.put(key, System.getProperty(key));
			System.setProperty(key, value);
		});
		return previous;
	}

	private static void restoreProperties(Map<String,String> previous) {
		previous.forEach((key, value) -> {
			if(value == null)
				System.clearProperty(key);
			else
				System.setProperty(key, value);
		});
	}
}
