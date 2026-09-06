/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAll.FedAllInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAllMaxFedFoutSinglePass;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.FederatedPlanExact;
import org.apache.sysds.hops.fedplanner.fedHeuristic.FederatedPlannerFedHeuristic.HeuristicInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedHeuristic.FederatedPlannerFedHeuristicSinglePass;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/** Actual campaign shape/privacy/options in a local metadata-backed compile; not an authenticated Docker run. */
public class GlmPrivateAggregatePlanningContractTest {
	private static final String SELECTOR_PROPERTY =
		"sysds.test.glm.private.aggregate.selector";
	private static final String WORKERS_PROPERTY =
		"sysds.test.glm.private.aggregate.workers";

	@Test
	public void workerOneBinomialGlmRetainsProtectedAppendCandidates() throws Exception {
		Path features = Files.createTempFile("glm-pa-features-", ".data");
		Path labels = Files.createTempFile("glm-pa-labels-", ".data");
		try {
			writeMetadata(features, 2100);
			writeMetadata(labels, 1);
			String script = federatedInput("X", features, 2100)
				+ federatedInput("Y", labels, 1)
				+ "threshold=mean(Y);\nY=(Y>threshold)*1;\n"
				+ "beta=glm(X=X,Y=Y,dfam=2,vpow=0.0,link=2,lpow=1.0,yneg=0.0,"
				+ "icpt=0,disp=0.0,reg=0.0,tol=1e-6,moi=20,mii=5,verbose=FALSE);\n"
				+ "write(beta,\"/tmp/glm-pa-planning-only.res\",format=\"csv\");\n";
			DMLProgram program = ParserFactory.createParser().parse(
				DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
			DMLTranslator translator = new DMLTranslator(program);
			translator.liveVariableAnalysis(program);
			translator.validateParseTree(program);
			translator.constructHops(program);
			translator.rewriteHopsDAG(program);
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);

			var protectedAppends = analysis.graph().nodes().stream().filter(node -> {
				var hop = analysis.hop(node.key()).orElse(null);
				return hop != null && "b(cbind)".equals(hop.getOpString())
					&& node.key().normalizedSignature().contains("binomial_probability_two_column")
					&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE;
			}).toList();
			Assert.assertFalse("The real private-aggregate binomial append domain must be tested",
				protectedAppends.isEmpty());
			for(var node : protectedAppends) {
				Assert.assertFalse("Protected append must retain a native remote candidate: " + node.key(),
					node.legalAlternatives().isEmpty());
				Assert.assertTrue("Neither collection nor CP/re-upload can implement protected append: "
					+ node.key() + ' ' + node.legalAlternatives(),
					node.legalAlternatives().stream().allMatch(state -> state.execType() == ExecType.FED
						&& state.output() == FederatedOutput.FOUT));
			}
		}
		finally {
			Files.deleteIfExists(Path.of(features + ".mtd"));
			Files.deleteIfExists(Path.of(labels + ".mtd"));
			Files.deleteIfExists(features);
			Files.deleteIfExists(labels);
		}
	}

	@Test
	public void optInWorkerOneBinomialGlmPlansWithSelectedAdditionalSelector() throws Exception {
		planWithSelectedAdditionalSelectors(1);
	}

	@Test
	public void optInPartitionedBinomialGlmPlansWithSelectedAdditionalSelector() throws Exception {
		int workers = Integer.getInteger(WORKERS_PROPERTY, 0);
		Assume.assumeTrue("enable the partitioned canary with -D" + WORKERS_PROPERTY
			+ "=3 or 5", workers == 3 || workers == 5);
		planWithSelectedAdditionalSelectors(workers);
	}

	private static void planWithSelectedAdditionalSelectors(int workers) throws Exception {
		String requestedSelector = System.getProperty(SELECTOR_PROPERTY, "").trim();
		Assume.assumeTrue("enable the local compile-only canary with -D" + SELECTOR_PROPERTY
			+ "=FED_ALL, HEURISTIC, EXACT, or ALL", !requestedSelector.isEmpty());
		List<SelectorKind> selectors = requestedSelector.equalsIgnoreCase("ALL")
			? List.of(SelectorKind.values())
			: List.of(parseSelector(requestedSelector));
		List<Path> features = new ArrayList<>();
		List<Path> labels = new ArrayList<>();
		try {
			int blockRows = (50000 + workers - 1) / workers;
			for(int worker = 0; worker < workers; worker++) {
				Path x = Files.createTempFile("glm-pa-selector-features-", ".data");
				features.add(x);
				Path y = Files.createTempFile("glm-pa-selector-labels-", ".data");
				labels.add(y);
				int rows = Math.min(blockRows, 50000 - worker * blockRows);
				writeMetadata(x, rows, 2100);
				writeMetadata(y, rows, 1);
			}
			List<PlannedGlm> plans = new ArrayList<>();
			for(SelectorKind selector : selectors) {
				long started = System.nanoTime();
				PlannedGlm plan = planFresh(selector, features, labels);
				plans.add(plan);
				System.out.println("GLM private-aggregate selector profile: selector=" + selector
					+ " workers=" + workers
					+ " elapsedMs=" + ((System.nanoTime() - started) / 1_000_000L)
					+ " decisions=" + plan.analysis().graph().decisionNodes().size()
					+ " certificateChars=" + plan.result().objectiveCertificate().length()
					+ " plan=" + plan.result().normalizedPlanFingerprint());
			}

			String expectedAnalysis = plans.get(0).analysis().analysisFingerprint();
			for(PlannedGlm plan : plans) {
				PlacementAnalysis analysis = plan.analysis();
				NormalizedPlannerResult result = plan.result();
				Assert.assertEquals(plan.selector().name(), expectedAnalysis,
					analysis.analysisFingerprint());
				Assert.assertSame(plan.selector().name(), analysis, result.analysis());
				Assert.assertEquals(plan.selector().name(), analysis.analysisFingerprint(),
					result.analysisFingerprint());
				Assert.assertEquals(plan.selector().name(), analysis.graph().decisionNodes().size(),
					result.selectedStates().size());
				Assert.assertFalse(plan.selector() + " omitted its objective certificate",
					result.objectiveCertificate().isBlank());
				Assert.assertFalse(plan.selector() + " omitted its normalized plan fingerprint",
					result.normalizedPlanFingerprint().isBlank());
				analysis.graph().decisionNodes().forEach(node -> Assert.assertTrue(
					plan.selector() + " selected a state outside the common legal domain: " + node.key(),
					node.legalAlternatives().stream().anyMatch(
						state -> state == result.selectedStates().get(node.key()))));
				List<PlacementAnalysis.HopOccurrenceProjection> protectedAppends =
					protectedAppends(analysis);
				Assert.assertFalse(plan.selector() + " lost the protected binomial append",
					protectedAppends.isEmpty());
				// This campaign uses link=2 (logit). The two cloglog left-index updates
				// belong to the unreachable link=4 branch, not to this protected path.
				// Native PA left indexing has separate graph and worker-kernel regressions.
				protectedAppends.forEach(occurrence -> assertProtectedRemote(plan.selector(),
					result.selectedStates().get(occurrence.key())));
				analysis.graph().decisionNodes().stream()
					.filter(node -> node.kind() != NodeKind.FUNCTION_CALL)
					.filter(node -> analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE)
					.forEach(node -> assertProtectedRemote(plan.selector(),
						result.selectedStates().get(node.key())));
				Assert.assertTrue(plan.selector() + " selected a relocation of protected data",
					result.selectedRelocations().stream().noneMatch(action -> analysis.graph().nodes().stream()
						.anyMatch(node -> node.valueVersion().equals(action.sourceValueVersion())
							&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE)));
			}
		}
		finally {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			for(Path data : java.util.stream.Stream.concat(features.stream(), labels.stream()).toList()) {
				Files.deleteIfExists(Path.of(data + ".mtd"));
				Files.deleteIfExists(data);
			}
		}
	}

	private static PlannedGlm planFresh(SelectorKind selector, List<Path> features, List<Path> labels)
		throws Exception {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		DMLProgram program = compile(glmScript(features, labels));
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
			.bindAtFinalHopBoundary(program);
		PlannerInvocationReceipt receipt = switch(selector) {
			case FED_ALL -> new FederatedPlannerFedAllMaxFedFoutSinglePass()
				.rewriteProgram(program, null, null, analysis);
			case HEURISTIC -> new FederatedPlannerFedHeuristicSinglePass()
				.rewriteProgram(program, null, null, analysis);
			case EXACT -> new FederatedPlanExact().rewriteProgram(program, null, null, analysis);
		};
		NormalizedPlannerResult result;
		if(receipt instanceof FedAllInvocationReceipt fedAll)
			result = fedAll.normalizedResult();
		else if(receipt instanceof HeuristicInvocationReceipt heuristic)
			result = heuristic.normalizedResult();
		else if(receipt instanceof ExactPlacementInput exact)
			result = exact.normalizedResult();
		else
			throw new AssertionError("Unexpected GLM planner receipt " + receipt.getClass());
		return new PlannedGlm(selector, analysis, result);
	}

	private static SelectorKind parseSelector(String requested) {
		try {
			return SelectorKind.valueOf(requested.toUpperCase(java.util.Locale.ROOT));
		}
		catch(IllegalArgumentException failure) {
			throw new AssertionError("Unknown " + SELECTOR_PROPERTY + " value: " + requested, failure);
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

	private static String glmScript(List<Path> features, List<Path> labels) {
		return federatedInput("X", features, 2100)
			+ federatedInput("Y", labels, 1)
			+ "threshold=mean(Y);\nY=(Y>threshold)*1;\n"
			+ "beta=glm(X=X,Y=Y,dfam=2,vpow=0.0,link=2,lpow=1.0,yneg=0.0,"
			+ "icpt=0,disp=0.0,reg=0.0,tol=1e-6,moi=20,mii=5,verbose=FALSE);\n"
			+ "write(beta,\"/tmp/glm-pa-planning-only.res\",format=\"csv\");\n";
	}

	private static List<PlacementAnalysis.HopOccurrenceProjection> protectedAppends(
		PlacementAnalysis analysis) {
		return analysis.compiledHopOccurrences().stream().filter(occurrence ->
			"b(cbind)".equals(occurrence.hop().getOpString())
				&& occurrence.key().normalizedSignature().contains("binomial_probability_two_column")
				&& analysis.requirePrivacy(occurrence.key()) == Privacy.PRIVATE_AGGREGATE).toList();
	}

	private static void assertProtectedRemote(SelectorKind selector, PlacementState state) {
		Assert.assertNotNull(selector + " omitted a protected decision", state);
		Assert.assertEquals(selector.name(), ExecType.FED, state.execType());
		Assert.assertEquals(selector.name(), FederatedOutput.FOUT, state.output());
	}

	private static void writeMetadata(Path data, int columns) throws Exception {
		writeMetadata(data, 50000, columns);
	}

	private static void writeMetadata(Path data, int rows, int columns) throws Exception {
		Files.writeString(Path.of(data + ".mtd"), "{\"data_type\":\"matrix\","
			+ "\"value_type\":\"double\",\"format\":\"binary\",\"rows\":" + rows + ",\"cols\":"
			+ columns + ",\"rows_in_block\":1000,\"cols_in_block\":1000,\"nnz\":"
			+ ((long) rows * columns) + ",\"privacy\":\"private-aggregate\"}");
	}

	private static String federatedInput(String name, Path data, int columns) {
		return federatedInput(name, List.of(data), columns);
	}

	private static String federatedInput(String name, List<Path> data, int columns) {
		List<String> addresses = new ArrayList<>();
		List<String> ranges = new ArrayList<>();
		int blockRows = (50000 + data.size() - 1) / data.size();
		for(int worker = 0; worker < data.size(); worker++) {
			String path = data.get(worker).toString().replace("\\", "\\\\").replace("\"", "\\\"");
			addresses.add("\"localhost:" + (1234 + worker) + "/" + path + "\"");
			int begin = worker * blockRows;
			ranges.add("list(" + begin + ",0),list(" + Math.min(50000, begin + blockRows)
				+ "," + columns + ")");
		}
		return name + "=federated(addresses=list(" + String.join(",", addresses)
			+ "),ranges=list(" + String.join(",", ranges) + "));\n";
	}

	private enum SelectorKind { FED_ALL, HEURISTIC, EXACT }

	private record PlannedGlm(SelectorKind selector, PlacementAnalysis analysis,
		NormalizedPlannerResult result) {
	}
}
