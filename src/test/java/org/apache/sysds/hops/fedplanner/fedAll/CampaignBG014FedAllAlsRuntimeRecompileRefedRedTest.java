/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedAll;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.io.MatrixWriterFactory;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.util.HDFSTool;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.utils.stats.InfrastructureAnalyzer;
import org.junit.Assert;
import org.junit.Test;

/** Docker-equivalent FedAll ALS regression for the inner-CG runtime recompile boundary. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014FedAllAlsRuntimeRecompileRefedRedTest {
	private static final int ROWS_PER_WORKER = 50;
	private static final int ROWS = 2 * ROWS_PER_WORKER;
	private static final int COLS = 20;

	@Test
	public void fedAllAlsKeepsSelectedRefedAuthorityDuringRuntimeRecompile() throws Exception {
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
		Path root = Files.createTempDirectory(Path.of("target"), "g014-fedall-als-runtime-refed-");
		Path first = root.resolve("features-1.data");
		Path second = root.resolve("features-2.data");
		Path output = root.resolve("result.csv");
		try {
			writeMatrix(first, 0);
			writeMatrix(second, ROWS_PER_WORKER);
			Path script = root.resolve("als.dml");
			Path config = root.resolve("SystemDS-config.xml");
			Files.writeString(script, alsScript(port1, port2, first, second, output));
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
			Assert.assertTrue("FedAll ALS must preserve the selected REFED authority across runtime recompile",
				success);
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
			delete(first);
			delete(second);
			HDFSTool.deleteFileIfExistOnHDFS(output.toString());
			HDFSTool.deleteFileIfExistOnHDFS(output + ".mtd");
			HDFSTool.deleteFileIfExistOnHDFS(root.resolve("scratch").toString());
			HDFSTool.deleteFileIfExistOnHDFS(root.resolve("localtmp").toString());
			Files.deleteIfExists(root.resolve("als.dml"));
			Files.deleteIfExists(root.resolve("SystemDS-config.xml"));
			Files.deleteIfExists(root);
		}
	}

	private static String alsScript(int port1, int port2, Path first, Path second, Path output) {
		return "X = federated(addresses=list(\"" + TestUtils.federatedAddress(port1, first.toString())
			+ "\", \"" + TestUtils.federatedAddress(port2, second.toString()) + "\"), "
			+ "ranges=list(list(0, 0), list(" + ROWS_PER_WORKER + ", " + COLS + "), "
			+ "list(" + ROWS_PER_WORKER + ", 0), list(" + ROWS + ", " + COLS + ")))\n"
			+ "[U, V] = als(X=X, rank=10, regType=\"L2\", reg=0.000001, maxi=2, "
			+ "check=FALSE, thr=0.0001, seed=1389632218, verbose=FALSE)\n"
			+ "write(V, \"" + output + "\", format=\"csv\")\n";
	}

	private static String config(Path root) {
		return "<root>\n"
			+ "  <sysds.native.blas>none</sysds.native.blas>\n"
			+ "  <sysds.local.spark>true</sysds.local.spark>\n"
			+ "  <sysds.federated.planner>compile_fed_all</sysds.federated.planner>\n"
			+ "  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>\n"
			+ "  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>\n"
			+ "</root>\n";
	}

	private static void writeMatrix(Path path, int rowOffset) throws Exception {
		MatrixBlock block = new MatrixBlock(ROWS_PER_WORKER, COLS, true);
		block.allocateSparseRowsBlock();
		for(int row = 0; row < ROWS_PER_WORKER; row++) {
			int globalRow = row + rowOffset;
			block.set(row, globalRow % COLS, 1.0);
			block.set(row, (globalRow * 7 + 3) % COLS, 0.5);
			block.set(row, (globalRow * 13 + 5) % COLS, 0.25);
		}
		block.recomputeNonZeros();
		MatrixCharacteristics characteristics = new MatrixCharacteristics(ROWS_PER_WORKER, COLS, 1000,
			block.getNonZeros());
		MatrixWriterFactory.createMatrixWriter(FileFormat.BINARY).writeMatrixToHDFS(block, path.toString(),
			ROWS_PER_WORKER, COLS, 1000, block.getNonZeros());
		HDFSTool.writeMetaDataFile(path + ".mtd", ValueType.FP64, null, DataType.MATRIX,
			characteristics, FileFormat.BINARY, null, "private-aggregate");
	}

	private static void delete(Path data) throws Exception {
		HDFSTool.deleteFileIfExistOnHDFS(data.toString());
		HDFSTool.deleteFileIfExistOnHDFS(data + ".mtd");
	}
}
