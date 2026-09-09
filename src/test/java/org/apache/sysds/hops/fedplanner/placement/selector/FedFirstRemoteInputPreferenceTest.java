/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Conditional exact-row coverage for FedFirst's Apache-style remote-input tie hint. */
@net.jcip.annotations.NotThreadSafe
public class FedFirstRemoteInputPreferenceTest {
	@Test
	public void givenCompatibleProducerDomainsStepLmRanksBothRemoteInputsFirst() throws Exception {
		List<Path> temporaryFiles = new ArrayList<>();
		try {
			DMLProgram program = compile(stepLmScript(temporaryFiles, 2));
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			var targets = analysis.compiledHopOccurrences().stream().filter(occurrence -> {
				var hop = occurrence.hop();
				return "ba(+*)".equals(hop.getOpString()) && hop.getBeginLine() == 94
					&& hop.getBeginColumn() == 15 && occurrence.key().normalizedSignature()
						.contains(".builtinNS::m_lm/body/0/branch-if/0");
			}).toList();
			Assert.assertEquals("fixture must identify the exact lmDS.dml:94 t(X) %*% y occurrence",
				1, targets.size());
			var target = targets.get(0);

			List<PlacementState> fedLout = analysis.graph().node(target.key()).orElseThrow()
				.legalAlternatives().stream().filter(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.LOUT).toList();
			Assert.assertTrue("fixture must expose at least two equal-rank FED/LOUT choices: " + fedLout,
				fedLout.size() >= 2);
			Map<PlacementState,Integer> candidateInputCounts = new LinkedHashMap<>();
			for(PlacementState state : fedLout) {
				int maximum = analysis.candidateRuleFacts().orderedFactsForParent(target.key()).stream()
					.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
					.filter(fact -> fact.allowedEmissionFacts().stream().anyMatch(emission ->
						emission.emissionState().placementState().equals(state)))
					.mapToInt(fact -> (int) fact.key().orderedInputs().stream()
						.filter(PlacementAnalysis.CandidateInputState::present).count())
					.max().orElse(0);
				candidateInputCounts.put(state, maximum);
			}
			Assert.assertTrue("equal-rank fixture states must expose distinct exact PRESENT-input counts: "
				+ candidateInputCounts, candidateInputCounts.values().stream().distinct().count() >= 2);

			analysis.candidateRuleFacts().orderedFactsForParent(target.key()).stream()
				.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
				.filter(fact -> fact.key().orderedInputs().size() == 2)
				.filter(fact -> fact.key().orderedInputs().get(0).present()
					&& fact.key().orderedInputs().get(0).fType() == FType.COL)
				.filter(fact -> fact.key().orderedInputs().get(1).present()
					&& fact.key().orderedInputs().get(1).fType() == FType.ROW)
				.filter(fact -> fact.allowedEmissionFacts().stream().anyMatch(emission -> {
					PlacementState state = emission.emissionState().placementState();
					return state.execType() == ExecType.FED && state.output() == FederatedOutput.LOUT;
				}))
				.findFirst().orElseThrow(() -> new AssertionError(
					"fixture has no exact FED/LOUT candidate with COL,ROW PRESENT inputs"));

			var reachability = CandidateSelections.partialReachabilityIndex(analysis,
				analysis.graph(), analysis.graph().relocationActions());
			Map<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey,
				List<PlacementState>> domains = new java.util.IdentityHashMap<>();
			for(var node : analysis.graph().decisionNodes())
				domains.put(node.key(), node.legalAlternatives());
			Assert.assertEquals("the tie-hint probe must retain the original privacy-filtered target domain",
				analysis.graph().node(target.key()).orElseThrow().legalAlternatives(), domains.get(target.key()));
			Map<PlacementState,Integer> reachableInputCounts = new LinkedHashMap<>();
			for(PlacementState state : fedLout)
				reachableInputCounts.put(state, reachability.maximumReachablePresentInputs(
					target.key(), state, Map.of(), domains));
			Assert.assertEquals("the remote COL,ROW row must expose both existing inputs",
				Integer.valueOf(2), reachableInputCounts.values().stream().max(Integer::compareTo).orElseThrow());
			Assert.assertTrue("the reachability-aware tie hint must distinguish remote inputs from"
				+ " a legal local-input FED/LOUT row: " + reachableInputCounts,
				reachableInputCounts.values().stream().distinct().count() >= 2);
		}
		finally {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			for(Path path : temporaryFiles)
				Files.deleteIfExists(path);
		}
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

	private static String stepLmScript(List<Path> temporaryFiles, int workers) throws Exception {
		return federatedInput("X", 50000, 2100, workers, "private-aggregate", temporaryFiles)
			+ federatedInput("Y", 50000, 1, workers, "public", temporaryFiles)
			+ "[B,S]=steplm(X=X,y=Y,icpt=0,reg=1e-7,tol=1e-7,maxi=20,verbose=FALSE);\n"
			+ "write(B,\"/tmp/fedfirst-steplm-planning-only.res\",format=\"csv\");\n";
	}

	private static String federatedInput(String name, long rows, long columns, int workers,
		String privacy, List<Path> temporaryFiles) throws Exception {
		List<String> addresses = new ArrayList<>();
		List<String> ranges = new ArrayList<>();
		for(int worker = 0; worker < workers; worker++) {
			long begin = rows * worker / workers;
			long end = rows * (worker + 1L) / workers;
			Path data = Files.createTempFile("fedfirst-steplm-" + name.toLowerCase()
				+ "-part-" + worker + '-', ".data");
			Path metadata = Path.of(data + ".mtd");
			Files.writeString(data, "");
			Files.writeString(metadata, "{\"data_type\":\"matrix\",\"value_type\":\"double\","
				+ "\"format\":\"binary\",\"rows\":" + (end - begin) + ",\"cols\":" + columns
				+ ",\"rows_in_block\":1000,\"cols_in_block\":1000,\"nnz\":"
				+ ((end - begin) * columns) + ",\"privacy\":\"" + privacy + "\"}");
			temporaryFiles.add(metadata);
			temporaryFiles.add(data);
			String path = data.toString().replace("\\", "\\\\").replace("\"", "\\\"");
			addresses.add("\"localhost:" + (1234 + worker) + "/" + path + "\"");
			ranges.add("list(" + begin + ",0)");
			ranges.add("list(" + end + ',' + columns + ")");
		}
		return name + "=federated(addresses=list(" + String.join(",", addresses)
			+ "),ranges=list(" + String.join(",", ranges) + "));\n";
	}
}
