/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

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
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.utils.stats.InfrastructureAnalyzer;
import org.junit.Assert;
import org.junit.Test;

/** Docker StepLM regression: the final DP output decision map must select one exact state per occurrence. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014DpStepLmDecisionMapClosureRedTest {
	@Test
	public void stepLmFullCompileClosesEveryOutputDecision() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		long oldLocalMaxMemory = InfrastructureAnalyzer.getLocalMaxMemory();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		int port = AutomatedTestBase.getRandomAvailablePort();
		Thread worker = null;
		Path root = Path.of("target/g014-dp-steplm-decision-map-closure");
		try {
			Files.createDirectories(root);
			Path features = matrixMetadata(root.resolve("features.data"), 50000, 2100, 100050000);
			Path labels = matrixMetadata(root.resolve("labels.data"), 50000, 1, 50000);
			Path script = root.resolve("steplm.dml");
			Path config = root.resolve("SystemDS-config.xml");
			Files.writeString(script, stepLmScript(port, features, labels, root.resolve("result.csv")));
			Files.writeString(config, config(root));

			worker = AutomatedTestBase.startLocalFedWorkerThread(port, 1000);
			InfrastructureAnalyzer.setLocalMaxMemory(8L * 1024 * 1024 * 1024);
			boolean success = DMLScript.executeScript(new String[] {
				"-exec", "singlenode",
				"-seed", "2026072701",
				"-f", script.toString(),
				"-stats", "100",
				"-config", config.toString()
			});
			Assert.assertTrue("The Docker-equivalent StepLM compile must complete", success);
		}
		finally {
			TestUtils.shutdownThreads(worker);
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
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
		}
	}

	private static String stepLmScript(int port, Path features, Path labels, Path output) {
		return "X = federated(addresses=list(\"" + TestUtils.federatedAddress(port, features.toString()) + "\"), " +
				"ranges=list(list(0, 0), list(50000, 2100)))\n" +
			"Y = federated(addresses=list(\"" + TestUtils.federatedAddress(port, labels.toString()) + "\"), " +
				"ranges=list(list(0, 0), list(50000, 1)))\n\n" +
			"[B, S] = steplm(X=X, y=Y, icpt=0, reg=1e-7, tol=1e-7, maxi=20, verbose=FALSE)\n\n" +
			"write(B, \"" + output + "\", format=\"csv\")\n";
	}

	private static String config(Path root) {
		return "<root>\n" +
			"  <sysds.native.blas>none</sysds.native.blas>\n" +
			"  <sysds.local.spark>true</sysds.local.spark>\n" +
			"  <sysds.federated.planner>compile_cost_based</sysds.federated.planner>\n" +
			"  <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>\n" +
			"  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>\n" +
			"  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>\n" +
			"</root>\n";
	}

	private static Path matrixMetadata(Path data, long rows, long cols, long nnz) throws Exception {
		Files.createDirectories(data.getParent());
		Files.writeString(data, "");
		Path mtd = Path.of(data + ".mtd");
		Files.writeString(mtd, "{\"data_type\":\"matrix\",\"value_type\":\"double\"," +
			"\"format\":\"binary\",\"rows\":" + rows + ",\"cols\":" + cols +
			",\"rows_in_block\":1000,\"cols_in_block\":1000,\"nnz\":" + nnz + "," +
			"\"privacy\":\"private-aggregate\"}");
		return data;
	}
}
