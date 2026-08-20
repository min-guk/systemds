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
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.junit.Assert;
import org.junit.Test;

/** Regression for Exact selecting a derived-FOUT producer without its exact durable anchor owner. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014ExactDerivedFoutAnchorSelectionRedTest {
	@Test
	public void lanStepLmSelectsEveryDerivedFoutAnchorOwner() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		Map<String,String> oldProperties = installLanCostProperties();
		Path root = Path.of("target/g014-exact-derived-fout-anchor");
		Path script = root.resolve("steplm.dml");
		Path config = root.resolve("SystemDS-config.xml");
		Path x1 = root.resolve("x-1.data");
		Path x2 = root.resolve("x-2.data");
		Path y1 = root.resolve("y-1.data");
		Path y2 = root.resolve("y-2.data");
		try {
			Files.createDirectories(root);
			String x1Address = ExactCliMetadataFixture.privateAggregateAddress(
				"worker1", 8001, x1, 25000, 2100, 25000L * 2100);
			String x2Address = ExactCliMetadataFixture.privateAggregateAddress(
				"worker2", 8002, x2, 25000, 2100, 25000L * 2100);
			String y1Address = ExactCliMetadataFixture.privateAggregateAddress(
				"worker1", 8001, y1, 25000, 1, 25000);
			String y2Address = ExactCliMetadataFixture.privateAggregateAddress(
				"worker2", 8002, y2, 25000, 1, 25000);
			Files.writeString(script, String.join("\n",
				"X = federated(addresses=list(\"" + x1Address + "\", \"" + x2Address
					+ "\"), ranges=list(list(0, 0), "
					+ "list(25000, 2100), list(25000, 0), list(50000, 2100)))",
				"Y = federated(addresses=list(\"" + y1Address + "\", \"" + y2Address
					+ "\"), ranges=list(list(0, 0), "
					+ "list(25000, 1), list(25000, 0), list(50000, 1)))",
				"",
				"[B, S] = steplm(X=X, y=Y, icpt=0, reg=1e-7, tol=1e-7, maxi=20, verbose=FALSE)",
				"",
				"write(B, \"" + root.resolve("result.csv") + "\", format=\"csv\")", ""));
			Files.writeString(config, String.join("\n",
				"<root>",
				"  <sysds.local.spark>true</sysds.local.spark>",
				"  <sysds.federated.planner>compile_exact</sysds.federated.planner>",
				"  <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>",
				"  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>",
				"  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>",
				"</root>", ""));
			Assert.assertTrue("Exact must select the exact FOUT owner required by every derived-FOUT action",
				DMLScript.executeScript(new String[] {
					"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
					"-stats", "100", "-config", config.toString()
				}));
		}
		finally {
			ExactCliMetadataFixture.delete(x1, x2, y1, y2);
			restoreProperties(oldProperties);
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			DMLScript.STATISTICS = oldStatistics;
			DMLScript.STATISTICS_COUNT = oldStatisticsCount;
			DMLScript.SEED = oldSeed;
			DMLScript.USE_LOCAL_SPARK_CONFIG = oldLocalSpark;
			DMLScript.DML_FILE_PATH_ANTLR_PARSER = oldParserPath;
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
		}
	}

	private static Map<String,String> installLanCostProperties() {
		Map<String,String> values = Map.ofEntries(
			Map.entry("DOCKER_NUM_WORKERS", "2"),
			Map.entry("SYSDS_FED_COST_MEM_BW", "25000"),
			Map.entry("SYSDS_FED_COST_NET_BW", "1250"),
			Map.entry("SYSDS_FED_COST_NET_BW_C2W", "1250"),
			Map.entry("SYSDS_FED_COST_NET_BW_W2C", "1250"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_C2W", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7"),
			Map.entry("SYSDS_FED_COST_NET_LATENCY", "0.001"),
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
}
