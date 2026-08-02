/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.nio.file.Files;
import java.nio.file.Path;

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

/** Docker-shape CLI regression for a local StepLM formal fed by multiple exact FOUT layouts. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014MinStStepLmFunctionSourceLayoutRedTest {
	@Test
	public void stepLmMinStCliCompilationAcceptsMultipleSourceLayouts() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		Path script = Path.of("target/g014-minst-steplm-cli.dml");
		Path config = Path.of("target/g014-minst-steplm-cli.xml");
		Files.createDirectories(script.getParent());
		Files.writeString(script, String.join("\n",
			"X = federated(addresses=list(\"worker1:8001/data/P2P2D_features.data\"), "
				+ "ranges=list(list(0, 0), list(50000, 2100)))",
			"Y = federated(addresses=list(\"worker1:8001/data/P2P2D_labels.data\"), "
				+ "ranges=list(list(0, 0), list(50000, 1)))",
			"",
			"[B, S] = steplm(X=X, y=Y, icpt=0, reg=1e-7, tol=1e-7, maxi=20, verbose=FALSE)",
			"",
			"write(B, \"target/g014-minst-steplm-cli.csv\", format=\"csv\")", ""));
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
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
		}
	}
}
