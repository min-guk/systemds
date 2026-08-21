/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.junit.Assert;
import org.junit.Test;

/** Regression for a legal but loop-amplified Xd upload selected by the local planner. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014LocalL2SvmLoopRelocationRedTest {
	@Test
	public void wanLightLocalPlannerDoesNotUploadXdInsideNestedLoop() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		Map<String,String> oldProperties = installWanLightCostProperties();
		Path root = Files.createTempDirectory(Path.of("target"), "g014-local-l2svm-wan-cli-");
		Path script = root.resolve("l2svm.dml");
		Path config = root.resolve("SystemDS-config.xml");
		Path x1 = root.resolve("x-1.data");
		Path x2 = root.resolve("x-2.data");
		Path y1 = root.resolve("y-1.data");
		Path y2 = root.resolve("y-2.data");
		try {
			String x1Address = ExactCliMetadataFixture.privateAggregateAddress(
				"worker1", 8001, x1, 25000, 2100, 25000L * 2100);
			String x2Address = ExactCliMetadataFixture.privateAggregateAddress(
				"worker2", 8002, x2, 25000, 2100, 25000L * 2100);
			String y1Address = ExactCliMetadataFixture.privateAggregateAddress(
				"worker1", 8001, y1, 25000, 1, 25000);
			String y2Address = ExactCliMetadataFixture.privateAggregateAddress(
				"worker2", 8002, y2, 25000, 1, 25000);
			Files.writeString(script, String.join("\n",
				"X = federated(addresses=list(\"" + x1Address + "\", \"" + x2Address + "\"), "
					+ "ranges=list(list(0, 0), list(25000, 2100), list(25000, 0), list(50000, 2100)))",
				"Y = federated(addresses=list(\"" + y1Address + "\", \"" + y2Address + "\"), "
					+ "ranges=list(list(0, 0), list(25000, 1), list(25000, 0), list(50000, 1)))",
				"m = l2svm(X=X, Y=Y, verbose=FALSE, epsilon=1e-22, maxIterations=30)",
				"write(m, \"" + root.resolve("result.csv") + "\", format=\"csv\")", ""));
			Files.writeString(config, String.join("\n",
				"<root>",
				"  <sysds.native.blas>none</sysds.native.blas>",
				"  <sysds.local.spark>true</sysds.local.spark>",
				"  <sysds.federated.planner>compile_cost_based</sysds.federated.planner>",
				"  <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>",
				"  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>",
				"  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>",
				"</root>", ""));
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			Assert.assertTrue(DMLScript.executeScript(new String[] {
				"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
				"-stats", "100", "-config", config.toString()
			}));

			NormalizedPlannerResult result = committedResult();
			Assert.assertTrue("the 30x20 nested loop must not select an Xd relocation; relocations="
				+ result.selectedRelocations(), result.selectedRelocations().stream().noneMatch(action ->
					result.analysis().graph().nodes().stream().anyMatch(node ->
						node.valueVersion().equals(action.sourceValueVersion())
							&& result.analysis().hop(node.key()).map(hop -> "Xd".equals(hop.getName())
								&& hop.getBeginLine() == 110).orElse(false))));
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			DMLScript.STATISTICS = oldStatistics;
			DMLScript.STATISTICS_COUNT = oldStatisticsCount;
			DMLScript.SEED = oldSeed;
			DMLScript.USE_LOCAL_SPARK_CONFIG = oldLocalSpark;
			DMLScript.DML_FILE_PATH_ANTLR_PARSER = oldParserPath;
			restoreProperties(oldProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
			deleteTree(root);
		}
	}

	private static NormalizedPlannerResult committedResult() {
		var committed = PlacementEmissionTransaction.receiptSnapshotForTesting();
		Assert.assertEquals("Expected one committed program", 1, committed.size());
		return PlacementEmissionTransaction.currentNormalizedResult(
			committed.keySet().iterator().next());
	}

	private static Map<String,String> installWanLightCostProperties() {
		Map<String,String> values = Map.ofEntries(
			Map.entry("SYSDS_FED_COST_MEM_BW", "25000"),
			Map.entry("SYSDS_FED_COST_NET_BW", "125"),
			Map.entry("SYSDS_FED_COST_NET_BW_C2W", "125"),
			Map.entry("SYSDS_FED_COST_NET_BW_W2C", "125"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_C2W", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7"),
			Map.entry("SYSDS_FED_COST_NET_LATENCY", "0.020"),
			Map.entry("SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "0"),
			Map.entry("SYSDS_FED_COST_FLOPS", "2147483648"));
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

	private static void deleteTree(Path root) throws Exception {
		if(!Files.exists(root))
			return;
		try(var paths = Files.walk(root)) {
			for(Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}
}
