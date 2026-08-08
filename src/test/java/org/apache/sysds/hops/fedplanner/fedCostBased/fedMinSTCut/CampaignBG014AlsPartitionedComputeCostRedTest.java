/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression for WAN-light ALS inner-CG partitioned compute being priced as serial work. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014AlsPartitionedComputeCostRedTest {
	@Test
	public void alsWorkerCountChangesThePhysicalInputTopology() throws Exception {
		try {
			Assert.assertEquals("The one-worker campaign input must expose its exact FULL topology",
				FType.FULL, sourceFType(1));
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			Assert.assertEquals("The multi-worker campaign input must expose its exact ROW topology",
				FType.ROW, sourceFType(2));
		}
		finally {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	@Test
	public void wanLightAlsMinStKeepsLargeInnerElementwiseWorkFederated() throws Exception {
		Map<String,String> oldProperties = installWanLightCostProperties();
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			DMLProgram program = als(4);
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			List<CompiledHopKey> innerMaskReads = innerMaskReads(analysis);
			Assert.assertFalse("ALS regression fixture did not expose inner-CG TRead W", innerMaskReads.isEmpty());
			Assert.assertTrue("Every inner-CG TRead W must retain its unique logical TWrite source across"
					+ " nested recompile contexts|reads=" + innerMaskReads.stream()
						.map(key -> key.normalizedSignature()).toList()
					+ "|facts=" + analysis.logicalTransientInputsInCanonicalOrder(),
				innerMaskReads.stream().allMatch(read -> analysis.logicalTransientInputsInCanonicalOrder().stream()
					.anyMatch(fact -> fact.targetRead() == read)));

			MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
			MinStExactCostFactsProducer.PhysicalCostSurface surface =
				MinStExactCostFactsProducer.physicalCostSurface(analysis, model);
			MinStExactPhysicalOptimizer.Result optimized = MinStExactPhysicalOptimizer.optimize(
				model, surface, MinStExactPhysicalOptimizer.PRODUCTION_LIMITS);
			NormalizedPlannerResult minSt = MinStExactPhysicalPlacementProjector.project(
				MinStExactPhysicalSelection.create(model, optimized)).normalizedResult();
			NormalizedPlannerResult dp = new FederatedPlannerDpFedCostBased()
				.selectProgram(program, null, null, analysis).normalizedResult();

			List<CompiledHopKey> targets = largeInnerElementwiseHops(analysis, minSt);
			Assert.assertFalse("ALS regression fixture did not expose the 50000x2100 inner-CG b(*) stage",
				targets.isEmpty());
			assertFederated("MinST", analysis, minSt, targets);
			assertDpSelectionUsesOpenLegalCandidateSpace(analysis, dp, targets);
		}
		finally {
			restoreProperties(oldProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	private static FType sourceFType(int workers) throws Exception {
		DMLProgram program = als(workers);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
			.bindAtFinalHopBoundary(program);
		var source = analysis.graph().decisionNodes().stream()
			.filter(node -> analysis.hop(node.key()).orElse(null) instanceof DataOp data
				&& data.getOp() == OpOpData.FEDERATED)
			.findFirst().orElseThrow();
		List<FType> sourceTypes = source.anchors().stream().map(anchor -> anchor.fType())
			.distinct().toList();
		Assert.assertEquals("ALS source must publish one exact durable layout", 1, sourceTypes.size());
		return sourceTypes.get(0);
	}

	private static List<CompiledHopKey> innerMaskReads(PlacementAnalysis analysis) {
		return analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key)
			.filter(key -> analysis.hop(key).orElse(null) instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && "W".equals(data.getName()))
			.filter(key -> key.controlRegion().regionPath().stream().anyMatch(path -> path.contains("loop-body")))
			.toList();
	}

	private static List<CompiledHopKey> largeInnerElementwiseHops(PlacementAnalysis analysis,
			NormalizedPlannerResult result) {
		List<CompiledHopKey> targets = new ArrayList<>();
		for(CompiledHopKey key : result.selectedStates().keySet()) {
			var hop = analysis.hop(key).orElse(null);
			var shape = analysis.shapeFact(key).orElse(null);
			if(hop instanceof BinaryOp && "b(*)".equals(hop.getOpString())
				&& shape != null && shape.rows() == 50000 && shape.cols() == 2100
				&& key.controlRegion().regionPath().stream().anyMatch(path -> path.contains("loop-body")))
				targets.add(key);
		}
		return targets;
	}

	private static void assertFederated(String planner, PlacementAnalysis analysis,
			NormalizedPlannerResult result, List<CompiledHopKey> targets) {
		List<String> states = targets.stream().map(key -> describe(analysis, key) + '='
			+ result.selectedStates().get(key)).toList();
		Assert.assertTrue(planner + " must price the partition-preserving ALS inner-CG elementwise stage"
			+ " as parallel worker work|states=" + states,
			targets.stream().allMatch(key -> result.selectedStates().get(key) != null
				&& result.selectedStates().get(key).execType() == ExecType.FED));
	}

	private static void assertDpSelectionUsesOpenLegalCandidateSpace(PlacementAnalysis analysis,
			NormalizedPlannerResult dp, List<CompiledHopKey> targets) {
		for(CompiledHopKey key : targets) {
			var alternatives = analysis.graph().node(key).orElseThrow().legalAlternatives();
			Assert.assertTrue("DP candidate space must retain the legal FED alternative for "
				+ describe(analysis, key) + "|alternatives=" + alternatives,
				alternatives.stream().anyMatch(state -> state.execType() == ExecType.FED));
			var selected = dp.selectedStates().get(key);
			Assert.assertNotNull("DP must emit a selection for " + describe(analysis, key), selected);
			Assert.assertTrue("DP selection must be one of the common-analysis alternatives for "
				+ describe(analysis, key) + "|selected=" + selected + "|alternatives=" + alternatives,
				alternatives.contains(selected));
		}
	}

	private static String describe(PlacementAnalysis analysis, CompiledHopKey key) {
		var hop = analysis.hop(key).orElseThrow();
		return hop.getHopID() + ":" + hop.getName() + ':' + hop.getOpString()
			+ analysis.shapeFact(key).map(shape -> "[" + shape.rows() + 'x' + shape.cols() + "]")
				.orElse("[unknown]")
			+ '|' + key.controlRegion().regionPath();
	}

	static DMLProgram als(int workers) throws Exception {
		return als(workers, 2);
	}

	static DMLProgram als(int workers, int maxi) throws Exception {
		String script = federatedFeatures(workers) + String.join("\n",
			"[U,V]=als(X=X,rank=10,regType=\"L2\",reg=0.000001,maxi=" + maxi + ","
				+ "check=FALSE,thr=0.0001,seed=1389632218,verbose=FALSE);",
			"write(V,\"out\",format=\"csv\");") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static String federatedFeatures(int workers) throws Exception {
		List<String> addresses = new ArrayList<>();
		List<String> ranges = new ArrayList<>();
		for(int worker = 0; worker < workers; worker++) {
			long begin = 50000L * worker / workers;
			long end = 50000L * (worker + 1) / workers;
			Path data = Files.createTempFile("g014-als-cost-w" + workers + "-p" + worker + '-', ".data");
			Path metadata = Path.of(data + ".mtd");
			Files.writeString(data, "");
			Files.writeString(metadata, "{\"data_type\":\"matrix\","
				+ "\"value_type\":\"double\",\"format\":\"binary\","
				+ "\"rows\":" + (end - begin) + ",\"cols\":2100,"
				+ "\"rows_in_block\":1000,\"cols_in_block\":1000,"
				+ "\"nnz\":" + ((end - begin) * 2001) + ','
				+ "\"privacy\":\"private-aggregate\"}");
			data.toFile().deleteOnExit();
			metadata.toFile().deleteOnExit();
			String path = data.toString().replace("\\", "\\\\").replace("\"", "\\\"");
			addresses.add("\"localhost:" + (12340 + worker) + '/' + path + "\"");
			ranges.add("list(" + begin + ",0)");
			ranges.add("list(" + end + ",2100)");
		}
		return "X=federated(addresses=list(" + String.join(",", addresses)
			+ "),ranges=list(" + String.join(",", ranges) + "));\n";
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

	static Map<String,String> installWanMidCostProperties() {
		Map<String,String> values = Map.ofEntries(
			Map.entry("SYSDS_FED_COST_MEM_BW", "25000"),
			Map.entry("SYSDS_FED_COST_NET_BW", "25"),
			Map.entry("SYSDS_FED_COST_NET_BW_C2W", "25"),
			Map.entry("SYSDS_FED_COST_NET_BW_W2C", "25"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_C2W", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7"),
			Map.entry("SYSDS_FED_COST_NET_LATENCY", "0.080"),
			Map.entry("SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "0"),
			Map.entry("SYSDS_FED_COST_FLOPS", "2147483648"));
		Map<String,String> previous = new HashMap<>();
		values.forEach((key, value) -> {
			previous.put(key, System.getProperty(key));
			System.setProperty(key, value);
		});
		return previous;
	}

	static void restoreProperties(Map<String,String> previous) {
		previous.forEach((key, value) -> {
			if(value == null)
				System.clearProperty(key);
			else
				System.setProperty(key, value);
		});
	}
}
