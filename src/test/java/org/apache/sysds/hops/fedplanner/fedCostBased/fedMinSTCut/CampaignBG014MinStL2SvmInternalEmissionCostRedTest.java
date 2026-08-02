/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

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

/** Docker-shape regression for cost-equivalent internal FED/LOUT arms in two-worker L2SVM. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014MinStL2SvmInternalEmissionCostRedTest {
	@Test
	public void l2SvmMinStCompilesWithMultipleExactInternalEmissions() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		Map<String,String> oldCostProperties = installDockerLanCostProperties();
		Path script = Path.of("target/g014-minst-l2svm-cli.dml");
		Path config = Path.of("target/g014-minst-l2svm-cli.xml");
		Files.createDirectories(script.getParent());
		Files.writeString(script, String.join("\n",
			"X = federated(addresses=list(\"worker1:8001/data/P2P2D_features_2_1.data\", "
				+ "\"worker2:8002/data/P2P2D_features_2_2.data\"), "
				+ "ranges=list(list(0, 0), list(25000, 2100), list(25000, 0), list(50000, 2100)))",
			"Y = federated(addresses=list(\"worker1:8001/data/P2P2D_labels_2_1.data\", "
				+ "\"worker2:8002/data/P2P2D_labels_2_2.data\"), "
				+ "ranges=list(list(0, 0), list(25000, 1), list(25000, 0), list(50000, 1)))",
			"",
			"m = l2svm(X=X, Y=Y, verbose=FALSE, epsilon=1e-22, maxIterations=30)",
			"write(m, \"target/g014-minst-l2svm-cli.csv\", format=\"csv\")", ""));
		Files.writeString(config, String.join("\n",
			"<root>",
			"    <sysds.local.spark>true</sysds.local.spark>",
			"    <sysds.federated.planner>compile_min_st_cut</sysds.federated.planner>",
			"    <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>",
			"</root>", ""));
		try {
			Assert.assertTrue(DMLScript.executeScript(new String[] {
				"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
				"-stats", "100", "-debug", "-config", config.toString()
			}));
		}
		finally {
			Files.deleteIfExists(script);
			Files.deleteIfExists(config);
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			DMLScript.STATISTICS = oldStatistics;
			DMLScript.STATISTICS_COUNT = oldStatisticsCount;
			DMLScript.SEED = oldSeed;
			DMLScript.USE_LOCAL_SPARK_CONFIG = oldLocalSpark;
			DMLScript.DML_FILE_PATH_ANTLR_PARSER = oldParserPath;
			restoreProperties(oldCostProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
		}
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
