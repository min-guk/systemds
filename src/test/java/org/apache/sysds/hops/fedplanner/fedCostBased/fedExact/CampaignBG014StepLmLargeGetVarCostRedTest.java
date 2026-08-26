/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.junit.Assert;
import org.junit.Test;

/** One-worker STEP-LM regression for a large planner-selected GET_VAR boundary. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014StepLmLargeGetVarCostRedTest {
	@Test
	public void costBasedSelectorsDoNotCollectTheFullFeatureMatrix() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		Map<String,String> oldCostProperties = installDockerLanCostProperties();
		Path root = Path.of("target/g014-steplm-large-get-var");
		Path script = root.resolve("steplm.dml");
		Path config = root.resolve("SystemDS-config.xml");
		Path x = root.resolve("x.data");
		Path y = root.resolve("y.data");
		try {
			Files.createDirectories(root);
			String xAddress = ExactCliMetadataFixture.privateAggregateAddress(
				"worker1", 8001, x, 50000, 2100, 50000L * 2100);
			String yAddress = ExactCliMetadataFixture.privateAggregateAddress(
				"worker1", 8001, y, 50000, 1, 50000);
			Files.writeString(script, String.join("\n",
				"X = federated(addresses=list(\"" + xAddress
					+ "\"), ranges=list(list(0, 0), list(50000, 2100)))",
				"Y = federated(addresses=list(\"" + yAddress
					+ "\"), ranges=list(list(0, 0), list(50000, 1)))",
				"",
				"[B, S] = steplm(X=X, y=Y, icpt=0, reg=1e-7, tol=1e-7, maxi=20, verbose=FALSE)",
				"write(B, \"" + root.resolve("result.csv") + "\", format=\"csv\")", ""));

			for(String planner : new String[] {"compile_cost_based", "compile_exact"}) {
				resetPlannerState();
				Files.writeString(config, String.join("\n",
					"<root>",
					"  <sysds.local.spark>true</sysds.local.spark>",
					"  <sysds.federated.planner>" + planner + "</sysds.federated.planner>",
					"  <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>",
					"  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>",
					"  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>",
					"</root>", ""));

				PrintStream originalOut = System.out;
				ByteArrayOutputStream captured = new ByteArrayOutputStream();
				boolean success;
				try(PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
					System.setOut(capture);
					success = DMLScript.executeScript(new String[] {
						"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
						"-stats", "100", "-explain", "runtime", "-config", config.toString()
					});
				}
				finally {
					System.setOut(originalOut);
				}
				Assert.assertTrue(planner + " must compile STEP-LM", success);
				var committed = PlacementEmissionTransaction.receiptSnapshotForTesting();
				Assert.assertEquals(planner + " must commit one whole-program selection", 1, committed.size());
				var normalized = PlacementEmissionTransaction.currentNormalizedResult(
					committed.keySet().iterator().next());
				long fullXCollections = ((java.util.List<?>) normalized.selectedLocalMaterializations()).stream()
					.map(action -> (LocalMaterializationActionKey) action)
					.filter(action -> normalized.analysis().hop(action.sourceOccurrence()).map(hop ->
						hop.isFederatedDataOp() && "X".equals(hop.getName())).orElse(false))
					.count();
				String runtimeProgram = captured.toString(StandardCharsets.UTF_8);
				Assert.assertEquals(planner + " must not replace repeated remote STEP-LM kernels with one"
					+ " 840 MB source collection; local=" + normalized.selectedLocalMaterializations(),
					0L, fullXCollections);
				Assert.assertFalse(planner + " must not emit a synthetic prefetch for the full X source",
					runtimeProgram.contains("CP prefetch X.MATRIX"));
			}
		}
		finally {
			ExactCliMetadataFixture.delete(x, y);
			Files.deleteIfExists(script);
			Files.deleteIfExists(config);
			restoreProperties(oldCostProperties);
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			DMLScript.STATISTICS = oldStatistics;
			DMLScript.STATISTICS_COUNT = oldStatisticsCount;
			DMLScript.SEED = oldSeed;
			DMLScript.USE_LOCAL_SPARK_CONFIG = oldLocalSpark;
			DMLScript.DML_FILE_PATH_ANTLR_PARSER = oldParserPath;
			resetPlannerState();
		}
	}

	private static void resetPlannerState() {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		PlacementEmissionTransaction.resetForTesting();
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
	}

	private static Map<String,String> installDockerLanCostProperties() {
		Map<String,String> values = Map.ofEntries(
			Map.entry("DOCKER_NUM_WORKERS", "1"),
			Map.entry("SYSDS_FED_COST_MEM_BW", "25000"),
			Map.entry("SYSDS_FED_COST_NET_BW", "1250"),
			Map.entry("SYSDS_FED_COST_NET_BW_C2W", "1250"),
			Map.entry("SYSDS_FED_COST_NET_BW_W2C", "1250"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_C2W", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7"),
			Map.entry("SYSDS_FED_COST_NET_LATENCY", "0.001"),
			Map.entry("SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "1"),
			Map.entry("SYSDS_FED_COST_FLOPS", "2147483648"));
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
