/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedHeuristic;

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
import org.apache.sysds.utils.Statistics;
import org.apache.sysds.utils.stats.InfrastructureAnalyzer;
import org.junit.Assert;
import org.junit.Test;

/** Single-worker runtime regression for Heuristic KMeans REFED authority across function recompilation. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014HeuristicKMeansRuntimeRecompileRefedAuthorityRedTest {
	private static final int ROWS = 50;
	private static final int COLS = 20;

	@Test
	public void heuristicKMeansPreservesSelectedRefedAuthorityDuringRuntimeRecompile() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		long oldLocalMaxMemory = InfrastructureAnalyzer.getLocalMaxMemory();
		int oldLocalParallelism = InfrastructureAnalyzer.getLocalParallelism();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		int port = AutomatedTestBase.getRandomAvailablePort();
		Thread worker = null;
		Path root = Files.createTempDirectory(Path.of("target"), "g014-heuristic-kmeans-runtime-refed-");
		Path features = root.resolve("features.data");
		Path output = root.resolve("result.csv");
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			writeMatrix(features);
			Path script = root.resolve("kmeans.dml");
			Path config = root.resolve("SystemDS-config.xml");
			Files.writeString(script, script(port, features, output));
			Files.writeString(config, config(root));
			worker = AutomatedTestBase.startLocalFedWorkerThread(port, 1000);
			InfrastructureAnalyzer.setLocalMaxMemory(8L * 1024 * 1024 * 1024);
			InfrastructureAnalyzer.setLocalPar(8);
			Statistics.reset();
			boolean success = DMLScript.executeScript(new String[] {
				"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
				"-stats", "100", "-config", config.toString()
			});
			Assert.assertTrue("Heuristic KMeans must execute its selected REFED materialization", success);
			Assert.assertFalse("KMeans must publish a normalized placement receipt",
				PlacementEmissionTransaction.receiptSnapshotForTesting().isEmpty());
			long outputActions = PlacementEmissionTransaction.receiptSnapshotForTesting().keySet().stream()
				.map(PlacementEmissionTransaction::currentNormalizedResult)
				.filter(result -> result != null)
				.mapToLong(result -> result.analysis().graph().derivedFoutMaterializationActions().size())
				.sum();
			Assert.assertTrue("The runtime fixture must exercise REFED output-materialization authority",
				outputActions > 0);
			Assert.assertTrue("The runtime fixture must execute the selected REFED instructions",
				Statistics.getCPHeavyHitterCount("fed_fed_refed") > 0);
			PlacementEmissionTransaction.ObservabilitySnapshot observability =
				PlacementEmissionTransaction.observabilitySnapshot();
			Assert.assertEquals("Runtime fallback is forbidden", 0, observability.runtimeFallbackCount());
			Assert.assertEquals("Runtime repair is forbidden", 0, observability.runtimeRepairCount());
		}
		finally {
			TestUtils.shutdownThreads(worker);
			InfrastructureAnalyzer.setLocalMaxMemory(oldLocalMaxMemory);
			InfrastructureAnalyzer.setLocalPar(oldLocalParallelism);
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
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
			delete(features);
			HDFSTool.deleteFileIfExistOnHDFS(output.toString());
			HDFSTool.deleteFileIfExistOnHDFS(output + ".mtd");
			HDFSTool.deleteFileIfExistOnHDFS(root.resolve("scratch").toString());
			HDFSTool.deleteFileIfExistOnHDFS(root.resolve("localtmp").toString());
			Files.deleteIfExists(root.resolve("kmeans.dml"));
			Files.deleteIfExists(root.resolve("SystemDS-config.xml"));
			Files.deleteIfExists(root);
		}
	}

	private static String script(int port, Path features, Path output) {
		return "X=federated(addresses=list(\"" + TestUtils.federatedAddress(port, features.toString())
			+ "\"),ranges=list(list(0,0),list(" + ROWS + "," + COLS + ")));\n"
			+ "[C_n,Y_n]=kmeans(X=X,k=5,is_verbose=FALSE,runs=1,eps=1e-9,max_iter=2,"
			+ "avg_sample_size_per_centroid=5,seed=133815928);\n"
			+ "write(Y_n,\"" + output + "\",format=\"csv\");\n";
	}

	private static String config(Path root) {
		return "<root>\n"
			+ "  <sysds.native.blas>none</sysds.native.blas>\n"
			+ "  <sysds.local.spark>true</sysds.local.spark>\n"
			+ "  <sysds.federated.planner>compile_fed_heuristic</sysds.federated.planner>\n"
			+ "  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>\n"
			+ "  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>\n"
			+ "</root>\n";
	}

	private static void writeMatrix(Path path) throws Exception {
		MatrixBlock block = new MatrixBlock(ROWS, COLS, true);
		block.allocateSparseRowsBlock();
		for(int row = 0; row < ROWS; row++) {
			block.set(row, row % COLS, 1.0 + row * 0.001);
			block.set(row, (row * 7 + 3) % COLS, 0.5);
			block.set(row, (row * 13 + 5) % COLS, 0.25);
		}
		block.recomputeNonZeros();
		MatrixCharacteristics characteristics = new MatrixCharacteristics(ROWS, COLS, 1000,
			block.getNonZeros());
		MatrixWriterFactory.createMatrixWriter(FileFormat.BINARY).writeMatrixToHDFS(block, path.toString(),
			ROWS, COLS, 1000, block.getNonZeros());
		HDFSTool.writeMetaDataFile(path + ".mtd", ValueType.FP64, null, DataType.MATRIX,
			characteristics, FileFormat.BINARY, null, "private-aggregate");
	}

	private static void delete(Path data) throws Exception {
		HDFSTool.deleteFileIfExistOnHDFS(data.toString());
		HDFSTool.deleteFileIfExistOnHDFS(data + ".mtd");
	}
}
