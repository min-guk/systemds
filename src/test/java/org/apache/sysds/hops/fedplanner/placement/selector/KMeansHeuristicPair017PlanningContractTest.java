/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Planning-only authority for the historical KMeans PAIR-017 reversal. */
public class KMeansHeuristicPair017PlanningContractTest {
	@Test
	public void privateAggregateWorkerOneClassifiesRepeatedInputAsCrossRankPolicyProjection() throws Exception {
		Map<String,String> old = installWanHeavyCostProperties();
		Path data = Files.createTempFile("pair017-kmeans-w1-", ".data");
		Path metadata = Path.of(data + ".mtd");
		try {
			writePrivateAggregateMetadata(data);
			DMLProgram program = compile(kmeansScript(data));
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			Set<ValueVersionKey> markers = new LinkedHashSet<>(analysis.heuristicPolicyFacts()
				.demotions().stream().map(fact -> fact.valueVersion()).toList());
			HeuristicPlacementAdapter.Result selected = new HeuristicPlacementAdapter(
				new PolicyFirstFeasiblePlacementSelector()).select(analysis, markers);

			List<CompiledInputEdgeFact> sampled = repeatedSampleMultiplyEdges(analysis);
			Assert.assertEquals("PAIR017_REPEATED_SAMPLE_EDGE_UNPROVEN|edges="
				+ describeEdges(analysis, sampled), 1, sampled.size());
			CompiledInputEdgeFact edge = sampled.get(0);
			Assert.assertEquals("PAIR017_FREQUENCY_WEIGHT_DRIFT|edge=" + describeEdge(analysis, edge),
				50.0, analysis.executionFrequencyFacts().forwardingWeight(
					edge.consumer(), edge.producer()), 0.0);

			List<PlacementState> baseDomain = analysis.graph().node(edge.producer())
				.orElseThrow().legalAlternatives();
			PlacementState directFull = requireState(baseDomain, ExecType.FED,
				FederatedOutput.FOUT, org.apache.sysds.hops.fedplanner.FTypes.FType.FULL,
				describeEdge(analysis, edge));
			PlacementState broadcast = requireState(baseDomain, ExecType.FED,
				FederatedOutput.FOUT, org.apache.sysds.hops.fedplanner.FTypes.FType.BROADCAST,
				describeEdge(analysis, edge));
			Assert.assertNotSame("FULL and BROADCAST must remain distinct same-policy states",
				directFull, broadcast);

			List<PlacementState> policyDomain = selected.selectorGraph().node(edge.producer())
				.orElseThrow().legalAlternatives();
			Assert.assertTrue("PAIR017_HEURISTIC_PREFIX_DID_NOT_PROJECT_TO_LOCAL|edge="
				+ describeEdge(analysis, edge) + "|base=" + baseDomain + "|policy=" + policyDomain,
				policyDomain.stream().allMatch(state -> state.execType() == ExecType.CP
					&& state.output() == FederatedOutput.LOUT));
			Assert.assertEquals("The direct FULL/FOUT candidate is present in the shared space but"
				+ " intentionally removed by Heuristic's local-prefix policy", 0,
				policyDomain.stream().filter(state -> state == directFull).count());
			PlacementState selectedProducer = selected.assignment().get(edge.producer());
			Assert.assertEquals(ExecType.CP, selectedProducer.execType());
			Assert.assertEquals(FederatedOutput.LOUT, selectedProducer.output());

			CandidateSelectionReceipt receipt = selected.selectedCandidateSelections().stream()
				.filter(candidate -> candidate.rule().parentOccurrence() == edge.consumer())
				.findFirst().orElseThrow(() -> new AssertionError(
					"PAIR017_SELECTED_CONSUMER_ROW_MISSING|edge=" + describeEdge(analysis, edge)));
			Assert.assertFalse("The projected CP/LOUT producer cannot be represented as an already"
				+ " present federated input; this is a cross-rank policy choice, not a FULL/BROADCAST tie",
				receipt.rule().orderedInputs().get(edge.inputPosition()).present());

			List<CompiledInputEdgeFact> fullInputs = repeatedFullInputEdges(analysis);
			Assert.assertFalse("PAIR017_FULL_INPUT_EDGE_UNPROVEN", fullInputs.isEmpty());
			for(CompiledInputEdgeFact fullInput : fullInputs) {
				PlacementState source = selected.assignment().get(fullInput.producer());
				Assert.assertEquals("PAIR017_PROTECTED_FULL_INPUT_LEFT_ORIGIN|edge="
					+ describeEdge(analysis, fullInput) + "|selected=" + source,
					ExecType.FED, source.execType());
				Assert.assertEquals(FederatedOutput.FOUT, source.output());
				Assert.assertEquals(org.apache.sysds.hops.fedplanner.FTypes.FType.FULL,
					source.fType());
			}
			Assert.assertTrue("PRIVATE_AGGREGATE must exclude active raw FULL-to-BROADCAST movement",
				selected.selectedRelocations().stream().noneMatch(action ->
					action.materializationFType()
						== org.apache.sysds.hops.fedplanner.FTypes.FType.BROADCAST
						&& fullInputs.stream().anyMatch(input -> analysis.graph().node(input.producer())
							.orElseThrow().valueVersion().equals(action.sourceValueVersion()))));
		}
		finally {
			restoreProperties(old);
			Files.deleteIfExists(metadata);
			Files.deleteIfExists(data);
		}
	}

	private static PlacementState requireState(List<PlacementState> states, ExecType exec,
		FederatedOutput output, org.apache.sysds.hops.fedplanner.FTypes.FType fType, String edge) {
		return states.stream().filter(state -> state.execType() == exec && state.output() == output
			&& state.fType() == fType).findFirst().orElseThrow(() -> new AssertionError(
				"PAIR017_EXPECTED_SAME_RANK_STATE_MISSING|state=" + exec + '/' + output + '/' + fType
					+ "|domain=" + states + "|edge=" + edge));
	}

	private static List<CompiledInputEdgeFact> repeatedSampleMultiplyEdges(PlacementAnalysis analysis) {
		return analysis.compiledInputEdgesInCanonicalOrder().stream().filter(edge -> {
			var producer = analysis.hop(edge.producer()).orElseThrow();
			var consumer = analysis.hop(edge.consumer()).orElseThrow();
			var shape = analysis.shapeFact(edge.producer()).orElse(null);
			return shape != null && shape.rows() == 3000 && shape.cols() == 2100
				&& "ba(+*)".equals(producer.getOpString()) && "b(*)".equals(consumer.getOpString())
				&& edge.consumer().controlRegion().regionPath().stream()
					.anyMatch(path -> path.contains("loop-body"));
		}).toList();
	}

	private static List<CompiledInputEdgeFact> repeatedFullInputEdges(PlacementAnalysis analysis) {
		return analysis.compiledInputEdgesInCanonicalOrder().stream().filter(edge -> {
			var producer = analysis.hop(edge.producer()).orElseThrow();
			var consumer = analysis.hop(edge.consumer()).orElseThrow();
			var shape = analysis.shapeFact(edge.producer()).orElse(null);
			return shape != null && shape.rows() == 50000 && shape.cols() == 2100
				&& "TRead X".equals(producer.getOpString()) && "ba(+*)".equals(consumer.getOpString())
				&& edge.consumer().controlRegion().regionPath().stream()
					.anyMatch(path -> path.contains("loop-body"));
		}).toList();
	}

	private static String describeEdges(PlacementAnalysis analysis,
		List<CompiledInputEdgeFact> edges) {
		return edges.stream().map(edge -> describeEdge(analysis, edge)).toList().toString();
	}

	private static String describeEdge(PlacementAnalysis analysis, CompiledInputEdgeFact edge) {
		var producer = analysis.hop(edge.producer()).orElseThrow();
		var consumer = analysis.hop(edge.consumer()).orElseThrow();
		return producer.getHopID() + ":" + producer.getOpString() + "->" + consumer.getHopID()
			+ ':' + consumer.getOpString() + '@' + edge.inputPosition() + '|'
			+ edge.consumer().controlRegion().regionPath();
	}

	private static DMLProgram compile(String script) throws Exception {
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

	private static String kmeansScript(Path data) {
		String path = data.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/" + path
				+ "\"),ranges=list(list(0,0),list(50000,2100)));",
			"[C,Y]=kmeans(X=X,k=50,is_verbose=FALSE,runs=1,eps=1e-9,max_iter=60,"
				+ "avg_sample_size_per_centroid=50,seed=133815928);",
			"print(sum(Y));", "");
	}

	private static Map<String,String> installWanHeavyCostProperties() {
		Map<String,String> values = Map.ofEntries(
			Map.entry("SYSDS_FED_COST_MEM_BW", "25000"),
			Map.entry("SYSDS_FED_COST_NET_BW", "12.5"),
			Map.entry("SYSDS_FED_COST_NET_BW_C2W", "12.5"),
			Map.entry("SYSDS_FED_COST_NET_BW_W2C", "12.5"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW", "14.7"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_C2W", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7"),
			Map.entry("SYSDS_FED_COST_NET_LATENCY", "0.200"),
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
