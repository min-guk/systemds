/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
		runCompileOnlyL2Svm(2);
	}

	@Test
	public void l2SvmSingleWorkerReclosesDerivedFoutCandidates() throws Exception {
		runCompileOnlyL2Svm(1);
	}

	private static void runCompileOnlyL2Svm(int workerCount) throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		long oldLocalMaxMemory = InfrastructureAnalyzer.getLocalMaxMemory();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		List<Integer> ports = new ArrayList<>();
		List<Thread> workers = new ArrayList<>();
		Path root = Path.of("target/g014-dp-l2svm-refed-source-lowering-w" + workerCount);
		try {
			Files.createDirectories(root);
			List<Path> features = new ArrayList<>();
			List<Path> labels = new ArrayList<>();
			for(int worker = 0; worker < workerCount; worker++) {
				int port;
				do port = AutomatedTestBase.getRandomAvailablePort();
				while(ports.contains(port));
				ports.add(port);
				long begin = 50000L * worker / workerCount;
				long end = 50000L * (worker + 1L) / workerCount;
				features.add(matrixMetadata(root.resolve("features-" + (worker + 1) + ".data"),
					end - begin, 2100, (end - begin) * 2100));
				labels.add(matrixMetadata(root.resolve("labels-" + (worker + 1) + ".data"),
					end - begin, 1, end - begin));
			}
			Path script = root.resolve("l2svm.dml");
			Path config = root.resolve("SystemDS-config.xml");
			Files.writeString(script, l2SvmScript(ports, features, labels,
				root.resolve("result.csv")));
			Files.writeString(config, config(root));

			for(int port : ports)
				workers.add(AutomatedTestBase.startLocalFedWorkerThread(port, 1000));
			InfrastructureAnalyzer.setLocalMaxMemory(8L * 1024 * 1024 * 1024);
			PrintStream originalOut = System.out;
			ByteArrayOutputStream captured = new ByteArrayOutputStream();
			boolean success;
			try(PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
				System.setOut(capture);
				success = DMLScript.executeScript(new String[] {
					"-exec", "singlenode",
					"-seed", "2026072701",
					"-f", script.toString(),
					"-stats", "100",
					"-explain", "runtime",
					"-config", config.toString()
				});
			}
			finally {
				System.setOut(originalOut);
			}
			Assert.assertTrue("The Docker-equivalent L2SVM compile must complete", success);
			String explain = captured.toString(StandardCharsets.UTF_8);
			int materializedTransposes = count(explain, "FED r' X.MATRIX");
			Assert.assertTrue("DP must not rematerialize the loop-invariant X transpose; runtime explain:\n"
				+ explain, materializedTransposes <= 1);
			if(materializedTransposes == 0)
				Assert.assertTrue("Without an explicit X transpose boundary, DP must use the legal native "
					+ "local-vector x federated-X LOUT plan; runtime explain:\n" + explain,
					countLines(explain, "FED ba+*", " X.MATRIX", " LOUT") >= 2);
		}
		finally {
			TestUtils.shutdownThreads(workers.toArray(Thread[]::new));
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

	private static int count(String value, String needle) {
		int result = 0;
		for(int offset = 0; (offset = value.indexOf(needle, offset)) >= 0; offset += needle.length())
			result++;
		return result;
	}

	private static int countLines(String value, String... needles) {
		int result = 0;
		for(String line : value.split("\\R")) {
			boolean matches = true;
			for(String needle : needles)
				matches &= line.contains(needle);
			if(matches)
				result++;
		}
		return result;
	}

	private static String l2SvmScript(List<Integer> ports, List<Path> features,
		List<Path> labels, Path output) {
		return federatedInput("X", ports, features, 2100)
			+ federatedInput("Y", ports, labels, 1) + "\n"
			+ "m = l2svm(X=X, Y=Y, verbose=FALSE, epsilon = 1e-22, maxIterations = 30)\n"
			+ "write(m, \"" + output + "\", format=\"csv\")\n";
	}

	private static String federatedInput(String name, List<Integer> ports, List<Path> inputs, long cols) {
		List<String> addresses = new ArrayList<>();
		List<String> ranges = new ArrayList<>();
		for(int worker = 0; worker < ports.size(); worker++) {
			long begin = 50000L * worker / ports.size();
			long end = 50000L * (worker + 1L) / ports.size();
			addresses.add("\"" + TestUtils.federatedAddress(ports.get(worker), inputs.get(worker).toString()) + "\"");
			ranges.add("list(" + begin + ", 0)");
			ranges.add("list(" + end + ", " + cols + ")");
		}
		return name + " = federated(addresses=list(" + String.join(", ", addresses)
			+ "), ranges=list(" + String.join(", ", ranges) + "))\n";
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
