/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Regression for the repeated 50.4 MB CP-to-FOUT upload selected by WAN-light KMeans. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014ExactKMeansWanRepeatedUploadRedTest {
	@Test
	public void cliWanLightKMeansDoesNotEmitTheRepeatedRefedPlan() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		Map<String,String> old = installWanLightCostProperties();
		Path root = Files.createTempDirectory(Path.of("target"), "g014-exact-kmeans-wan-cli-");
		Path data = root.resolve("features.data");
		Path script = root.resolve("kmeans.dml");
		Path config = root.resolve("SystemDS-config.xml");
		try {
			writePrivateAggregateMetadata(data);
			Files.writeString(script, kmeansScript(data, root.resolve("result.csv")));
			Files.writeString(config, String.join("\n",
				"<root>",
				"  <sysds.native.blas>none</sysds.native.blas>",
				"  <sysds.local.spark>true</sysds.local.spark>",
				"  <sysds.federated.planner>compile_exact</sysds.federated.planner>",
				"  <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>",
				"  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>",
				"  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>",
				"</root>", ""));
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			Assert.assertTrue(DMLScript.executeScript(new String[] {
				"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
				"-stats", "100", "-config", config.toString()
			}));
			NormalizedPlannerResult result = committedResult();
			List<CompiledInputEdgeFact> candidates = repeatedSampleUploadEdges(result.analysis());
			Assert.assertEquals("G014_EXACT_CLI_KMEANS_REPEATED_UPLOAD_EDGE_UNPROVEN|edges="
				+ describeEdges(result.analysis(), candidates), 1, candidates.size());
			CompiledInputEdgeFact edge = candidates.get(0);
			var producerState = result.selectedStates().get(edge.producer());
			var consumerState = result.selectedStates().get(edge.consumer());
			boolean selectedAbsentLocal = result.selectedCandidateSelections().stream().anyMatch(receipt ->
				receipt.rule().parentOccurrence() == edge.consumer()
					&& edge.inputPosition() < receipt.rule().orderedInputs().size()
					&& !receipt.rule().orderedInputs().get(edge.inputPosition()).present());
			boolean selectedRepeatedUpload = consumerState.execType() == ExecType.FED
				&& (selectedAbsentLocal || producerState.execType() != ExecType.FED
					|| producerState.output() != FederatedOutput.FOUT);
			Assert.assertFalse("G014_EXACT_CLI_SELECTED_50X_50MB_NATIVE_LOCAL_BROADCAST|edge="
				+ describeEdge(result.analysis(), edge) + "|producer=" + producerState
				+ "|consumer=" + consumerState + "|absentLocal=" + selectedAbsentLocal,
				selectedRepeatedUpload);
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			DMLScript.STATISTICS = oldStatistics;
			DMLScript.STATISTICS_COUNT = oldStatisticsCount;
			DMLScript.SEED = oldSeed;
			DMLScript.USE_LOCAL_SPARK_CONFIG = oldLocalSpark;
			DMLScript.DML_FILE_PATH_ANTLR_PARSER = oldParserPath;
			restoreProperties(old);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
			deleteTree(root);
		}
	}

	@Test
	public void wanLightKMeansPricesFiftyLargeUploadsAndAvoidsTheRepeatedRefedPlan() throws Exception {
		Map<String,String> old = installWanLightCostProperties();
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			DMLProgram program = kmeansSingleWorker();
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
			ExactPhysicalCostModel.PhysicalCostSurface surface =
				ExactPhysicalCostModel.physicalCostSurface(analysis, model);
			ExactPhysicalOptimizer.Result optimized = ExactPhysicalOptimizer.optimize(
				model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
			ExactPhysicalSelection selection = ExactPhysicalSelection.create(model, optimized);

			List<CompiledInputEdgeFact> candidates = repeatedSampleUploadEdges(analysis);
			Assert.assertEquals("G014_EXACT_KMEANS_REPEATED_UPLOAD_EDGE_UNPROVEN|candidates="
				+ describeEdges(analysis, candidates) + "|shapeUniverse="
				+ describeEdges(analysis, analysis.compiledInputEdgesInCanonicalOrder().stream()
					.filter(edge -> analysis.shapeFact(edge.producer()).map(shape ->
						shape.rows() == 3000 && shape.cols() == 2100).orElse(false))
					.toList()), 1, candidates.size());
			CompiledInputEdgeFact edge = candidates.get(0);
			double weight = forwardingWeight(analysis, edge);
			Assert.assertEquals("G014_EXACT_KMEANS_K50_UPLOAD_WEIGHT_DRIFT|edge="
				+ describeEdge(analysis, edge), 50.0, weight, 0.0);

			var producerState = selection.selectedStates().get(edge.producer());
			var consumerState = selection.selectedStates().get(edge.consumer());
			boolean selectedAbsentLocal = selection.candidateReceipts().stream().anyMatch(receipt ->
				receipt.rule().parentOccurrence() == edge.consumer()
					&& edge.inputPosition() < receipt.rule().orderedInputs().size()
					&& !receipt.rule().orderedInputs().get(edge.inputPosition()).present());
			boolean selectedRepeatedUpload = consumerState.execType() == ExecType.FED
				&& (selectedAbsentLocal || producerState.execType() != ExecType.FED
					|| producerState.output() != FederatedOutput.FOUT);
			Assert.assertFalse("G014_EXACT_KMEANS_SELECTED_50X_50MB_RUNTIME_UPLOAD|edge="
				+ describeEdge(analysis, edge) + "|producer=" + producerState
				+ "|consumer=" + consumerState + "|absentLocal=" + selectedAbsentLocal
				+ "|objective=" + Double.longBitsToDouble(optimized.canonicalObjectiveBits()),
				selectedRepeatedUpload);
		}
		finally {
			restoreProperties(old);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	private static List<CompiledInputEdgeFact> repeatedSampleUploadEdges(PlacementAnalysis analysis) {
		List<CompiledInputEdgeFact> result = new ArrayList<>();
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			var producer = analysis.hop(edge.producer()).orElseThrow();
			var consumer = analysis.hop(edge.consumer()).orElseThrow();
			var shape = analysis.shapeFact(edge.producer()).orElse(null);
			if(shape != null && shape.rows() == 3000 && shape.cols() == 2100
				&& "ba(+*)".equals(producer.getOpString()) && "b(*)".equals(consumer.getOpString())
				&& edge.consumer().controlRegion().regionPath().stream()
					.anyMatch(path -> path.contains("loop-body")))
				result.add(edge);
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static double forwardingWeight(PlacementAnalysis analysis,
		CompiledInputEdgeFact edge) throws Exception {
		Method profilesMethod = ExactPhysicalCostModel.class
			.getDeclaredMethod("occurrenceProfiles", PlacementAnalysis.class);
		profilesMethod.setAccessible(true);
		Map<String,?> profiles = (Map<String,?>)profilesMethod.invoke(null, analysis);
		Method weightMethod = ExactPhysicalCostModel.class.getDeclaredMethod(
			"forwardingWeight", Map.class, CompiledHopKey.class, CompiledHopKey.class);
		weightMethod.setAccessible(true);
		return (double)weightMethod.invoke(null, profiles, edge.consumer(), edge.producer());
	}

	private static String describeEdges(PlacementAnalysis analysis,
		List<CompiledInputEdgeFact> edges) {
		return edges.stream().map(edge -> describeEdge(analysis, edge)).toList().toString();
	}

	private static String describeEdge(PlacementAnalysis analysis, CompiledInputEdgeFact edge) {
		var producer = analysis.hop(edge.producer()).orElseThrow();
		var consumer = analysis.hop(edge.consumer()).orElseThrow();
		return producer.getHopID() + ":" + producer.getName() + ':' + producer.getOpString()
			+ analysis.shapeFact(edge.producer()).map(shape -> "[" + shape.rows() + 'x'
				+ shape.cols() + "]").orElse("[unknown]")
			+ "->" + consumer.getHopID() + ":" + consumer.getName() + ':'
			+ consumer.getOpString() + "@" + edge.inputPosition() + '|'
			+ edge.consumer().controlRegion().regionPath();
	}

	private static DMLProgram kmeansSingleWorker() throws Exception {
		Path data = Files.createTempFile("g014-exact-kmeans-wan-w1-", ".data");
		writePrivateAggregateMetadata(data);
		Path metadata = Path.of(data + ".mtd");
		data.toFile().deleteOnExit();
		metadata.toFile().deleteOnExit();
		String script = kmeansScript(data, Path.of("out"));
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static void writePrivateAggregateMetadata(Path data) throws Exception {
		Files.writeString(data, "");
		Files.writeString(Path.of(data + ".mtd"), "{\"data_type\":\"matrix\","
			+ "\"value_type\":\"double\",\"format\":\"binary\","
			+ "\"rows\":50000,\"cols\":2100,\"rows_in_block\":1000,"
			+ "\"cols_in_block\":1000,\"nnz\":105000000,"
			+ "\"privacy\":\"private-aggregate\"}");
	}

	private static String kmeansScript(Path data, Path output) {
		String path = data.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		String result = output.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/" + path
				+ "\"),ranges=list(list(0,0),list(50000,2100)));",
			"[C,Y]=kmeans(X=X,k=50,is_verbose=FALSE,runs=1,eps=1e-9,max_iter=60,"
				+ "avg_sample_size_per_centroid=50,seed=133815928);",
			"write(Y,\"" + result + "\",format=\"csv\");", "");
	}

	private static NormalizedPlannerResult committedResult() {
		var committed = PlacementEmissionTransaction.receiptSnapshotForTesting();
		Assert.assertEquals("Expected one committed Exact program", 1, committed.size());
		return PlacementEmissionTransaction.currentNormalizedResult(committed.keySet().iterator().next());
	}

	private static void deleteTree(Path root) throws Exception {
		if(root == null || !Files.exists(root))
			return;
		try(var paths = Files.walk(root)) {
			for(Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}

	private static Map<String,String> installWanLightCostProperties() {
		Map<String,String> values = Map.ofEntries(
			Map.entry("SYSDS_FED_COST_MEM_BW", "25000"),
			Map.entry("SYSDS_FED_COST_NET_BW", "125"),
			Map.entry("SYSDS_FED_COST_NET_BW_C2W", "125"),
			Map.entry("SYSDS_FED_COST_NET_BW_W2C", "125"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_C2W", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7"),
			Map.entry("SYSDS_FED_COST_NET_LATENCY", "0.020"),
			Map.entry("SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "0"),
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
