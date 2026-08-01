/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedAll;

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

/** Docker-equivalent LogReg regression for a selected cross-anchor relocation of transient Y. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014FedAllLogRegTransientReadRelocationRedTest {
	@Test
	public void fedAllLogRegTwoWorkersLowersFederatedTransientReadRelocation() throws Exception {
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
		Path root = Files.createTempDirectory(Path.of("target"), "g014-fedall-logreg-tread-relocation-");
		try {
			Path features1 = matrixMetadata(root.resolve("features-1.data"), 25000, 2100, 50025000);
			Path features2 = matrixMetadata(root.resolve("features-2.data"), 25000, 2100, 50025000);
			Path labels1 = matrixMetadata(root.resolve("labels-1.data"), 25000, 1, 25000);
			Path labels2 = matrixMetadata(root.resolve("labels-2.data"), 25000, 1, 25000);
			Path script = root.resolve("logreg.dml");
			Path config = root.resolve("SystemDS-config.xml");
			Files.writeString(script, logRegScript(port1, port2, features1, features2, labels1, labels2,
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
			Assert.assertTrue("FedAll LogReg must lower the selected transient Y relocation", success);
			PlacementEmissionTransaction.ObservabilitySnapshot observability =
				PlacementEmissionTransaction.observabilitySnapshot();
			Assert.assertEquals("Planner execution must not use runtime fallback", 0,
				observability.runtimeFallbackCount());
			Assert.assertEquals("Planner execution must not use runtime repair", 0,
				observability.runtimeRepairCount());
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

	private static String logRegScript(int port1, int port2, Path features1, Path features2,
		Path labels1, Path labels2, Path output) {
		return "N = 50000\n"
			+ "D = 2100\n"
			+ "X = federated(addresses=list(\"" + TestUtils.federatedAddress(port1, features1.toString())
			+ "\", \"" + TestUtils.federatedAddress(port2, features2.toString()) + "\"), "
			+ "ranges=list(list(0, 0), list(25000, 2100), list(25000, 0), list(50000, 2100)))\n"
			+ "Y = federated(addresses=list(\"" + TestUtils.federatedAddress(port1, labels1.toString())
			+ "\", \"" + TestUtils.federatedAddress(port2, labels2.toString()) + "\"), "
			+ "ranges=list(list(0, 0), list(25000, 1), list(25000, 0), list(50000, 1)))\n"
			+ "Y = (Y < 0) + 1\n"
			+ "m = multiLogReg(X=X, Y=Y, verbose=FALSE, maxi=30, maxii=5, tol=1e-9, icpt=0, "
			+ "numclasses=2, numrows=N, numcols=D)\n"
			+ "write(m, \"" + output + "\", format=\"csv\")\n";
	}

	private static String config(Path root) {
		return "<root>\n"
			+ "  <sysds.native.blas>none</sysds.native.blas>\n"
			+ "  <sysds.local.spark>true</sysds.local.spark>\n"
			+ "  <sysds.federated.planner>compile_fed_all</sysds.federated.planner>\n"
			+ "  <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>\n"
			+ "  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>\n"
			+ "  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>\n"
			+ "</root>\n";
	}

	private static Path matrixMetadata(Path data, long rows, long cols, long nnz) throws Exception {
		Files.writeString(data, "");
		Path mtd = Path.of(data + ".mtd");
		Files.writeString(mtd, "{\"data_type\":\"matrix\",\"value_type\":\"double\","
			+ "\"format\":\"binary\",\"rows\":" + rows + ",\"cols\":" + cols
			+ ",\"rows_in_block\":1000,\"cols_in_block\":1000,\"nnz\":" + nnz + ","
			+ "\"privacy\":\"private-aggregate\"}");
		return data;
	}
}
