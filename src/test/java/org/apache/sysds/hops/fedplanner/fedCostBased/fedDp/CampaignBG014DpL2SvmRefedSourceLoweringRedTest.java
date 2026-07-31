/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.utils.stats.InfrastructureAnalyzer;
import org.junit.Assert;
import org.junit.Test;

/** Docker L2SVM regression: every emitted REFED source must lower in its exact statement-block scope. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014DpL2SvmRefedSourceLoweringRedTest {
	@Test
	public void l2SvmTwoWorkersLowersEverySelectedRefedSource() throws Exception {
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
		Path root = Path.of("target/g014-dp-l2svm-refed-source-lowering");
		try {
			Files.createDirectories(root);
			Path features1 = matrixMetadata(root.resolve("features-1.data"), 25000, 2100, 50025000);
			Path features2 = matrixMetadata(root.resolve("features-2.data"), 25000, 2100, 50025000);
			Path labels1 = matrixMetadata(root.resolve("labels-1.data"), 25000, 1, 25000);
			Path labels2 = matrixMetadata(root.resolve("labels-2.data"), 25000, 1, 25000);
			Path script = root.resolve("l2svm.dml");
			Path config = root.resolve("SystemDS-config.xml");
			Files.writeString(script, l2SvmScript(port1, port2, features1, features2, labels1, labels2,
				root.resolve("result.csv")));
			Files.writeString(config, config(root));

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
			Assert.assertTrue("The Docker-equivalent L2SVM compile must complete", success);
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

	private static String l2SvmScript(int port1, int port2, Path features1, Path features2,
		Path labels1, Path labels2, Path output) {
		return "X = federated(addresses=list(\"" + TestUtils.federatedAddress(port1, features1.toString())
			+ "\", \"" + TestUtils.federatedAddress(port2, features2.toString()) + "\"), "
			+ "ranges=list(list(0, 0), list(25000, 2100), list(25000, 0), list(50000, 2100)))\n"
			+ "Y = federated(addresses=list(\"" + TestUtils.federatedAddress(port1, labels1.toString())
			+ "\", \"" + TestUtils.federatedAddress(port2, labels2.toString()) + "\"), "
			+ "ranges=list(list(0, 0), list(25000, 1), list(25000, 0), list(50000, 1)))\n\n"
			+ "m = l2svm(X=X, Y=Y, verbose=FALSE, epsilon = 1e-22, maxIterations = 30)\n"
			+ "write(m, \"" + output + "\", format=\"csv\")\n";
	}

	private static String config(Path root) {
		return "<root>\n"
			+ "  <sysds.native.blas>none</sysds.native.blas>\n"
			+ "  <sysds.local.spark>true</sysds.local.spark>\n"
			+ "  <sysds.federated.planner>compile_cost_based</sysds.federated.planner>\n"
			+ "  <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>\n"
			+ "  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>\n"
			+ "  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>\n"
			+ "</root>\n";
	}

	private static Path matrixMetadata(Path data, long rows, long cols, long nnz) throws Exception {
		Files.createDirectories(data.getParent());
		Files.writeString(data, "");
		Path mtd = Path.of(data + ".mtd");
		Files.writeString(mtd, "{\"data_type\":\"matrix\",\"value_type\":\"double\","
			+ "\"format\":\"binary\",\"rows\":" + rows + ",\"cols\":" + cols
			+ ",\"rows_in_block\":1000,\"cols_in_block\":1000,\"nnz\":" + nnz + ","
			+ "\"privacy\":\"private-aggregate\"}");
		return data;
	}
}
