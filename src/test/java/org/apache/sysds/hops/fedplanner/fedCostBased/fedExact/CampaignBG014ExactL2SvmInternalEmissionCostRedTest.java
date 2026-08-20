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

/** Docker-shape regression for cost-equivalent internal FED/LOUT arms in two-worker L2SVM. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014ExactL2SvmInternalEmissionCostRedTest {
	@Test
	public void l2SvmExactCompilesWithMultipleExactInternalEmissions() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		Map<String,String> oldCostProperties = installDockerLanCostProperties();
		Path script = Path.of("target/g014-exact-l2svm-cli.dml");
		Path config = Path.of("target/g014-exact-l2svm-cli.xml");
		Path x1 = Path.of("target/g014-exact-l2svm-x-1.data");
		Path x2 = Path.of("target/g014-exact-l2svm-x-2.data");
		Path y1 = Path.of("target/g014-exact-l2svm-y-1.data");
		Path y2 = Path.of("target/g014-exact-l2svm-y-2.data");
		Files.createDirectories(script.getParent());
		String x1Address = ExactCliMetadataFixture.privateAggregateAddress(
			"worker1", 8001, x1, 25000, 2100, 25000L * 2100);
		String x2Address = ExactCliMetadataFixture.privateAggregateAddress(
			"worker2", 8002, x2, 25000, 2100, 25000L * 2100);
		String y1Address = ExactCliMetadataFixture.privateAggregateAddress(
			"worker1", 8001, y1, 25000, 1, 25000);
		String y2Address = ExactCliMetadataFixture.privateAggregateAddress(
			"worker2", 8002, y2, 25000, 1, 25000);
		Files.writeString(script, String.join("\n",
			"X = federated(addresses=list(\"" + x1Address + "\", \"" + x2Address + "\"), "
				+ "ranges=list(list(0, 0), list(25000, 2100), list(25000, 0), list(50000, 2100)))",
			"Y = federated(addresses=list(\"" + y1Address + "\", \"" + y2Address + "\"), "
				+ "ranges=list(list(0, 0), list(25000, 1), list(25000, 0), list(50000, 1)))",
			"",
			"m = l2svm(X=X, Y=Y, verbose=FALSE, epsilon=1e-22, maxIterations=30)",
			"write(m, \"target/g014-exact-l2svm-cli.csv\", format=\"csv\")", ""));
		Files.writeString(config, String.join("\n",
			"<root>",
			"    <sysds.local.spark>true</sysds.local.spark>",
			"    <sysds.federated.planner>compile_exact</sysds.federated.planner>",
			"    <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>",
			"</root>", ""));
		try {
			PrintStream originalOut = System.out;
			ByteArrayOutputStream captured = new ByteArrayOutputStream();
			boolean success;
			try(PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
				System.setOut(capture);
				success = DMLScript.executeScript(new String[] {
					"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
					"-stats", "100", "-debug", "-explain", "runtime", "-config", config.toString()
				});
			}
			finally {
				System.setOut(originalOut);
			}
			Assert.assertTrue(success);
			String runtimeProgram = captured.toString(StandardCharsets.UTF_8);
			String outerLoop = between(runtimeProgram, "WHILE (lines 96-140)",
				"GENERIC (lines 141-141)");
			Assert.assertEquals("Exact must retain the cheaper direct local-left/FED-right matmul plan "
				+ "instead of materializing the full X transpose; boundaries="
				+ boundaryRegistrySlots() + "; normalizedLocals=" + normalizedLocalSummary()
				+ "; selectedRelocations=" + normalizedRelocationSummary()
				+ "; variantTrace=" + runtimeProgram.lines()
					.filter(line -> line.contains("Exact-ExactRowVariant")).toList(),
				0, count(runtimeProgram, "FED r' X.MATRIX"));
			Assert.assertFalse("The outer L2SVM loop must not materialize the stable X transpose",
				outerLoop.contains("FED r' X.MATRIX"));
			Assert.assertTrue("The outer loop must keep X-times-s federated",
				outerLoop.contains("FED ba+* X.MATRIX"));
			Assert.assertTrue("The outer loop must keep local-left-times-X on the federated workers",
				outerLoop.lines().anyMatch(line -> line.contains("FED ba+*")
					&& line.contains(" X.MATRIX") && line.contains(" LOUT")));
			var normalized = committedResult();
			var anchorsBySource = normalized.selectedRelocationChoices().stream().collect(
				java.util.stream.Collectors.groupingBy(choice -> choice.action().sourceValueVersion(),
					java.util.stream.Collectors.mapping(choice -> choice.action().durableAnchor().placementId(),
						java.util.stream.Collectors.toSet())));
			Assert.assertTrue("Independent L2SVM consumers must retain their exact worker-pool authority "
				+ "instead of expanding one selected action over every compatible obligation: "
				+ anchorsBySource,
				anchorsBySource.values().stream().anyMatch(anchors -> anchors.size() > 1));
			Assert.assertFalse("Exact must not publish a relocation boundary on the loop-local X transpose",
				normalized.selectedRelocations().stream().anyMatch(action -> {
					var source = normalized.analysis().graph().nodes().stream()
						.filter(node -> node.valueVersion().equals(action.sourceValueVersion()))
						.findFirst().orElseThrow();
					return normalized.analysis().hop(source.key()).orElseThrow().getBeginLine() == 124
						&& "r(r')".equals(normalized.analysis().hop(source.key()).orElseThrow().getOpString());
				}));
		}
		finally {
			Files.deleteIfExists(script);
			Files.deleteIfExists(config);
			ExactCliMetadataFixture.delete(x1, x2, y1, y2);
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			DMLScript.STATISTICS = oldStatistics;
			DMLScript.STATISTICS_COUNT = oldStatisticsCount;
			DMLScript.SEED = oldSeed;
			DMLScript.USE_LOCAL_SPARK_CONFIG = oldLocalSpark;
			DMLScript.DML_FILE_PATH_ANTLR_PARSER = oldParserPath;
			restoreProperties(oldCostProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
		}
	}

	private static String between(String value, String start, String end) {
		int from = value.indexOf(start);
		Assert.assertTrue("Missing runtime-program start marker: " + start, from >= 0);
		int to = value.indexOf(end, from + start.length());
		Assert.assertTrue("Missing runtime-program end marker: " + end, to > from);
		return value.substring(from, to);
	}

	private static int count(String value, String needle) {
		int result = 0;
		for(int offset = 0; (offset = value.indexOf(needle, offset)) >= 0; offset += needle.length())
			result++;
		return result;
	}

	private static String boundaryRegistrySlots() {
		return "refed=" + FederatedRefedRegistry.snapshotAll().scopes().entrySet().stream()
			.flatMap(scope -> scope.getValue().entrySet().stream().map(entry -> scope.getKey() + ":" + entry.getKey()
				+ "->" + entry.getValue().getConsumerHopIds())).sorted().toList()
			+ ",fout=" + FederatedFoutMaterializeRegistry.snapshotAll().scopes().entrySet().stream()
			.flatMap(scope -> scope.getValue().keySet().stream().map(hop -> scope.getKey() + ":" + hop)).sorted().toList()
			+ ",local=" + FederatedLocalMaterializeRegistry.snapshotAll().scopes().entrySet().stream()
			.flatMap(scope -> scope.getValue().entrySet().stream().map(entry -> scope.getKey() + ":" + entry.getKey()
				+ "->" + entry.getValue().getConsumerHopIds()
				+ "[fType=" + entry.getValue().getFTypeHint()
				+ ",reason=" + entry.getValue().getReason() + "]")).sorted().toList();
	}

	private static String normalizedLocalSummary() {
		var result = committedResult();
		return ((java.util.List<?>) result.selectedLocalMaterializations()).stream()
			.map(value -> {
				LocalMaterializationActionKey action = (LocalMaterializationActionKey) value;
				long source = result.analysis().hop(action.sourceOccurrence()).orElseThrow().getHopID();
				return source + "->" + action.obligations().stream().map(obligation -> {
					long consumer = result.analysis().hop(obligation.consumerOccurrence()).orElseThrow().getHopID();
					return consumer + "=" + result.selectedStates().get(obligation.consumerOccurrence());
				}).toList();
			}).toList().toString();
	}

	private static String normalizedRelocationSummary() {
		var result = committedResult();
		return result.selectedRelocations().stream().map(action -> {
			var source = result.analysis().graph().nodes().stream()
				.filter(node -> node.valueVersion().equals(action.sourceValueVersion())).findFirst().orElseThrow();
			long sourceHop = result.analysis().hop(source.key()).orElseThrow().getHopID();
			return sourceHop + "[" + result.selectedStates().get(source.key()) + "]->"
				+ action.compatibleConsumers().stream()
					.map(consumer -> Long.toString(result.analysis().hop(consumer).orElseThrow().getHopID())).toList()
				+ " target=" + action.targetPlacement() + " materialization=" + action.materializationFType()
				+ " anchor=" + action.durableAnchor().fType();
		}).toList().toString();
	}

	private static org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult committedResult() {
		var committed = PlacementEmissionTransaction.receiptSnapshotForTesting();
		Assert.assertEquals("Expected one committed program", 1, committed.size());
		return PlacementEmissionTransaction.currentNormalizedResult(committed.keySet().iterator().next());
	}

	private static Map<String,String> installDockerLanCostProperties() {
		Map<String,String> values = Map.of(
			"SYSDS_FED_COST_MEM_BW", "25000",
			"SYSDS_FED_COST_NET_BW", "1250",
			"SYSDS_FED_COST_NET_BW_C2W", "1250",
			"SYSDS_FED_COST_NET_BW_W2C", "1250",
			"SYSDS_FED_COST_NET_SERDES_BW", "210",
			"SYSDS_FED_COST_NET_SERDES_BW_C2W", "210",
			"SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7",
			"SYSDS_FED_COST_NET_LATENCY", "0.001",
			"SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "0",
			"SYSDS_FED_COST_FLOPS", "2147483648");
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
