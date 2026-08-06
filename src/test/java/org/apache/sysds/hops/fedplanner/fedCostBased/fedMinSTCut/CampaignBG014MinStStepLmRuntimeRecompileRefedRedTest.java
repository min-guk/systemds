/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

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

/** Regression for runtime-recompiled StepLM blocks re-uploading a local formal without a FED consumer. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014MinStStepLmRuntimeRecompileRefedRedTest {
	private static final int ROWS = 50;
	private static final int COLS = 1;

	@Test
	public void minStRecompileDoesNotUploadLocalYWithoutPhysicalFedConsumer() throws Exception {
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
		Path root = Files.createTempDirectory(Path.of("target"), "g014-minst-steplm-refed-");
		Path features = root.resolve("features.data");
		Path labels = root.resolve("labels.data");
		try {
			writeMatrix(features, true);
			writeMatrix(labels, false);
			Path script = root.resolve("steplm.dml");
			Path config = root.resolve("SystemDS-config.xml");
			Files.writeString(script, stepLmScript(port, features, labels, root.resolve("result.csv")));
			Files.writeString(config, config(root));
			worker = AutomatedTestBase.startLocalFedWorkerThread(port, 1_000);
			InfrastructureAnalyzer.setLocalMaxMemory(8L * 1024 * 1024 * 1024);
			Statistics.reset();
			Assert.assertTrue(DMLScript.executeScript(new String[] {
				"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
				"-stats", "100", "-config", config.toString()
			}));
			long refed = Statistics.getCPHeavyHitterCount("fed_fed_refed");
			Assert.assertTrue("Only a statically selected physical upload may remain; runtime-recompiled "
				+ "CP blocks must not add REFED for coordinator-only work. actual=" + refed, refed <= 1);
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
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
			delete(features);
			delete(labels);
			HDFSTool.deleteFileIfExistOnHDFS(root.resolve("result.csv").toString());
			HDFSTool.deleteFileIfExistOnHDFS(root.resolve("result.csv.mtd").toString());
			HDFSTool.deleteFileIfExistOnHDFS(root.resolve("scratch").toString());
			HDFSTool.deleteFileIfExistOnHDFS(root.resolve("localtmp").toString());
			Files.deleteIfExists(root.resolve("steplm.dml"));
			Files.deleteIfExists(root.resolve("SystemDS-config.xml"));
			Files.deleteIfExists(root);
		}
	}

	private static String stepLmScript(int port, Path features, Path labels, Path output) {
		return "X = federated(addresses=list(\"" + TestUtils.federatedAddress(port, features.toString())
			+ "\"), ranges=list(list(0, 0), list(" + ROWS + ", " + COLS + ")))\n"
			+ "Y = federated(addresses=list(\"" + TestUtils.federatedAddress(port, labels.toString())
			+ "\"), ranges=list(list(0, 0), list(" + ROWS + ", 1)))\n"
			+ "[B, S] = steplm(X=X, y=Y, icpt=0, reg=1e-7, tol=1e-7, maxi=2, verbose=FALSE)\n"
			+ "write(B, \"" + output + "\", format=\"csv\")\n";
	}

	private static String config(Path root) {
		return "<root>\n"
			+ "  <sysds.native.blas>none</sysds.native.blas>\n"
			+ "  <sysds.local.spark>true</sysds.local.spark>\n"
			+ "  <sysds.federated.planner>compile_min_st_cut</sysds.federated.planner>\n"
			+ "  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>\n"
			+ "  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>\n"
			+ "</root>\n";
	}

	private static void writeMatrix(Path path, boolean features) throws Exception {
		MatrixBlock block = new MatrixBlock(ROWS, features ? COLS : 1, false);
		for(int row = 0; row < ROWS; row++)
			block.set(row, 0, features ? row + 1 : row % 2);
		block.recomputeNonZeros();
		MatrixCharacteristics mc = new MatrixCharacteristics(block.getNumRows(), block.getNumColumns(),
			1_000, block.getNonZeros());
		MatrixWriterFactory.createMatrixWriter(FileFormat.BINARY).writeMatrixToHDFS(block,
			path.toString(), block.getNumRows(), block.getNumColumns(), 1_000, block.getNonZeros());
		HDFSTool.writeMetaDataFile(path + ".mtd", ValueType.FP64, null, DataType.MATRIX,
			mc, FileFormat.BINARY, null, "private-aggregate");
	}

	private static void delete(Path path) throws Exception {
		HDFSTool.deleteFileIfExistOnHDFS(path.toString());
		HDFSTool.deleteFileIfExistOnHDFS(path + ".mtd");
	}
}
