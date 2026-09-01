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

/** Docker-equivalent StepLM regression for a FED/FOUT transient write/read chain. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014FedAllStepLmTransientReadRefedRedTest {
	private static final int ROWS = 50;
	private static final int COLS = 1;

	@Test
	public void fedAllRecompileDoesNotUploadReadOfFederatedTransientWrite() throws Exception {
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
		Path root = Files.createTempDirectory(Path.of("target"), "g014-fedall-steplm-refed-");
		Path features = root.resolve("features.data");
		Path labels = root.resolve("labels.data");
		try {
			writeSparseMatrix(features, ROWS, COLS, true);
			writeSparseMatrix(labels, ROWS, 1, false);
			Path script = root.resolve("steplm.dml");
			Path config = root.resolve("SystemDS-config.xml");
			Files.writeString(script, stepLmScript(port, features, labels, root.resolve("result.csv")));
			Files.writeString(config, config(root));
			worker = AutomatedTestBase.startLocalFedWorkerThread(port, 1000);
			boolean success = DMLScript.executeScript(new String[] {
				"-exec", "singlenode",
				"-seed", "2026072701",
				"-f", script.toString(),
				"-stats", "100",
				"-config", config.toString()
			});
			Assert.assertTrue("FedAll StepLM must recompile without a REFED upload on a transient read", success);
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
			HDFSTool.deleteFileIfExistOnHDFS(features.toString());
			HDFSTool.deleteFileIfExistOnHDFS(features + ".mtd");
			HDFSTool.deleteFileIfExistOnHDFS(labels.toString());
			HDFSTool.deleteFileIfExistOnHDFS(labels + ".mtd");
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
		return "X = federated(addresses=list(\"" + TestUtils.federatedAddress(port, features.toString()) + "\"), " +
				"ranges=list(list(0, 0), list(" + ROWS + ", " + COLS + ")))\n" +
			"Y = federated(addresses=list(\"" + TestUtils.federatedAddress(port, labels.toString()) + "\"), " +
				"ranges=list(list(0, 0), list(" + ROWS + ", 1)))\n\n" +
			"[B, S] = steplm(X=X, y=Y, icpt=0, reg=1e-7, tol=1e-7, maxi=20, verbose=FALSE)\n\n" +
			"write(B, \"" + output + "\", format=\"csv\")\n";
	}

	private static String config(Path root) {
		return "<root>\n" +
			"  <sysds.native.blas>none</sysds.native.blas>\n" +
			"  <sysds.local.spark>true</sysds.local.spark>\n" +
			"  <sysds.federated.planner>compile_fed_all</sysds.federated.planner>\n" +
			"  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>\n" +
			"  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>\n" +
			"</root>\n";
	}

	private static void writeSparseMatrix(Path path, int rows, int cols, boolean features) throws Exception {
		MatrixBlock block = new MatrixBlock(rows, cols, true);
		block.allocateSparseRowsBlock();
		for(int row = 0; row < rows; row++) {
			if(features) {
				block.set(row, row % cols, 1.0);
				if(cols > 1)
					block.set(row, (row * 31 + 7) % cols, 0.25);
			}
			else
				block.set(row, 0, row % 2);
		}
		block.recomputeNonZeros();
		MatrixCharacteristics characteristics = new MatrixCharacteristics(rows, cols, 1000,
			block.getNonZeros());
		MatrixWriterFactory.createMatrixWriter(FileFormat.BINARY).writeMatrixToHDFS(block, path.toString(),
			rows, cols, 1000, block.getNonZeros());
		HDFSTool.writeMetaDataFile(path + ".mtd", ValueType.FP64, null, DataType.MATRIX,
			characteristics, FileFormat.BINARY, null, "private-aggregate");
	}
}
