/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.utils.stats.InfrastructureAnalyzer;
import org.junit.Assert;
import org.junit.Test;

/** Regression for worker-fanout latency overpricing in WAN-light LogReg MinST plans. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014MinStLogRegParallelDispatchCostRedTest {
	@Test
	public void cliWanLightLogRegPlanRemainsStableAcrossWorkerCounts() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		long oldLocalMaxMemory = InfrastructureAnalyzer.getLocalMaxMemory();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		Map<String,String> oldProperties = installWanLightCostProperties();
		try {
			InfrastructureAnalyzer.setLocalMaxMemory(8L * 1024 * 1024 * 1024);
			for(int workers = 1; workers <= 4; workers++) {
				Path root = Files.createTempDirectory(Path.of("target"),
					"g014-minst-logreg-cli-w" + workers + '-');
				try {
					Path script = root.resolve("logreg.dml");
					Path config = root.resolve("SystemDS-config.xml");
					Files.writeString(script, logRegScript(root, workers));
					Files.writeString(config, compileOnlyConfig(root));
					FederatedPlannerUtils.resetFederatedPlannerRunState();
					PlacementEmissionTransaction.resetForTesting();
					Assert.assertTrue("Docker-equivalent MinST LogReg compile failed for workers=" + workers,
						DMLScript.executeScript(new String[] {
							"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
							"-stats", "100", "-config", config.toString()
						}));
					NormalizedPlannerResult result = committedResult();
					List<CompiledInputEdgeFact> featureMatmulEdges = repeatedFeatureMatmulEdges(result.analysis());
					Assert.assertFalse("G014_MINST_LOGREG_REPEATED_FEATURE_MATMUL_UNPROVEN|workers="
						+ workers + "|edges=" + describeEdges(result.analysis(), featureMatmulEdges),
						featureMatmulEdges.isEmpty());
					Set<CompiledHopKey> consumers = new LinkedHashSet<>();
					featureMatmulEdges.forEach(edge -> consumers.add(edge.consumer()));
					List<String> selectedDescriptions = consumers.stream().map(consumer ->
						describeHop(result.analysis(), consumer) + '=' + result.selectedStates().get(consumer))
						.toList();
					Assert.assertTrue("WAN-light LogReg MinST must not download the repeated feature"
						+ " matrix solely because worker fanout duplicates one parallel dispatch stage"
						+ "|workers=" + workers + "|states=" + selectedDescriptions,
						consumers.stream().allMatch(consumer -> result.selectedStates().get(consumer) != null
							&& result.selectedStates().get(consumer).execType() == ExecType.FED));
				}
				finally {
					FederatedPlannerUtils.resetFederatedPlannerRunState();
					PlacementEmissionTransaction.resetForTesting();
					FederatedRefedRegistry.clear();
					FederatedFoutMaterializeRegistry.clear();
					FederatedLocalMaterializeRegistry.clear();
					deleteTree(root);
				}
			}
		}
		finally {
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
			restoreProperties(oldProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
		}
	}

	private static NormalizedPlannerResult committedResult() {
		var committed = PlacementEmissionTransaction.receiptSnapshotForTesting();
		Assert.assertEquals("Expected one committed MinST program", 1, committed.size());
		return PlacementEmissionTransaction.currentNormalizedResult(committed.keySet().iterator().next());
	}

	private static List<CompiledInputEdgeFact> repeatedFeatureMatmulEdges(PlacementAnalysis analysis) {
		List<CompiledInputEdgeFact> result = new ArrayList<>();
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			var producerShape = analysis.shapeFact(edge.producer()).orElse(null);
			var consumer = analysis.hop(edge.consumer()).orElseThrow();
			if(producerShape != null && producerShape.rows() == 50000 && producerShape.cols() == 2100
				&& "ba(+*)".equals(consumer.getOpString())
				&& edge.consumer().controlRegion().regionPath().stream()
					.anyMatch(path -> path.contains("loop-body")))
				result.add(edge);
		}
		return result;
	}

	private static String describeEdges(PlacementAnalysis analysis, List<CompiledInputEdgeFact> edges) {
		return edges.stream().map(edge -> describeHop(analysis, edge.producer()) + "->"
			+ describeHop(analysis, edge.consumer()) + '@' + edge.inputPosition()).toList().toString();
	}

	private static String describeHop(PlacementAnalysis analysis, CompiledHopKey key) {
		var hop = analysis.hop(key).orElseThrow();
		return hop.getHopID() + ":" + hop.getName() + ':' + hop.getOpString()
			+ analysis.shapeFact(key).map(shape -> "[" + shape.rows() + 'x' + shape.cols() + "]")
				.orElse("[unknown]")
			+ '|' + key.controlRegion().regionPath();
	}

	private static String logRegScript(Path root, int workers) throws Exception {
		List<String> xAddresses = new ArrayList<>();
		List<String> yAddresses = new ArrayList<>();
		List<String> xRanges = new ArrayList<>();
		List<String> yRanges = new ArrayList<>();
		long begin = 0;
		for(int worker = 0; worker < workers; worker++) {
			long end = 50000L * (worker + 1) / workers;
			Path x = root.resolve("x-" + worker + ".data");
			Path y = root.resolve("y-" + worker + ".data");
			writePrivateAggregateMetadata(x, end - begin, 2100, (end - begin) * 2001);
			writePrivateAggregateMetadata(y, end - begin, 1, end - begin);
			int port = 12340 + worker;
			xAddresses.add("\"worker" + (worker + 1) + ':' + port + '/' + escape(x) + "\"");
			yAddresses.add("\"worker" + (worker + 1) + ':' + port + '/' + escape(y) + "\"");
			xRanges.add("list(" + begin + ",0)");
			xRanges.add("list(" + end + ",2100)");
			yRanges.add("list(" + begin + ",0)");
			yRanges.add("list(" + end + ",1)");
			begin = end;
		}
		return String.join("\n",
			"N=50000;", "D=2100;",
			"X=federated(addresses=list(" + String.join(",", xAddresses)
				+ "),ranges=list(" + String.join(",", xRanges) + "));",
			"Y=federated(addresses=list(" + String.join(",", yAddresses)
				+ "),ranges=list(" + String.join(",", yRanges) + "));",
			"Y=(Y<0)+1;",
			"m=multiLogReg(X=X,Y=Y,verbose=FALSE,maxi=30,maxii=5,tol=1e-9,icpt=0,"
				+ "numclasses=2,numrows=N,numcols=D);",
			"write(m,\"" + escape(root.resolve("out.csv")) + "\",format=\"csv\");", "");
	}

	private static String compileOnlyConfig(Path root) {
		return String.join("\n", "<root>",
			"  <sysds.native.blas>none</sysds.native.blas>",
			"  <sysds.local.spark>true</sysds.local.spark>",
			"  <sysds.federated.planner>compile_min_st_cut</sysds.federated.planner>",
			"  <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>",
			"  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>",
			"  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>",
			"</root>", "");
	}

	private static void writePrivateAggregateMetadata(Path data, long rows, long cols, long nnz)
		throws Exception {
		Files.writeString(data, "");
		Files.writeString(Path.of(data + ".mtd"), "{\"data_type\":\"matrix\","
			+ "\"value_type\":\"double\",\"format\":\"binary\","
			+ "\"rows\":" + rows + ",\"cols\":" + cols + ",\"rows_in_block\":1000,"
			+ "\"cols_in_block\":1000,\"nnz\":" + nnz + ','
			+ "\"privacy\":\"private-aggregate\"}");
	}

	private static String escape(Path path) {
		return path.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static void deleteTree(Path root) throws Exception {
		if(root == null || !Files.exists(root))
			return;
		try(var paths = Files.walk(root)) {
			for(Path path : paths.sorted(Comparator.reverseOrder()).toList())
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
