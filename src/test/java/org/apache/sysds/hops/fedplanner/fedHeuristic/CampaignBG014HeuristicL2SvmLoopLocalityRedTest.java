/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedHeuristic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPathEdgeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.selector.PolicyFirstFeasiblePlacementSelector;
import org.apache.sysds.hops.fedplanner.fedHeuristic.FederatedPlannerFedHeuristic.HeuristicInvocationReceipt;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression for preserving a demoted vector across L2SVM's nested loop CFG. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014HeuristicL2SvmLoopLocalityRedTest {
	@Test
	public void demotedXdRemainsLocalAcrossNestedLoopTransientReads() throws Exception {
		try {
			for(int workers : List.of(1, 3, 5, 7)) {
				FederatedPlannerUtils.resetFederatedPlannerRunState();
				assertDemotedXdRemainsLocal(workers);
			}
		}
		finally {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	@Test
	public void publicFederatedWorkerYRetainsXdLocalContinuation() throws Exception {
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			DMLProgram program = compile(l2svmScript(3));
			setFederatedSourcePrivacy(program, "X", Privacy.PRIVATE_AGGREGATE);
			setFederatedSourcePrivacy(program, "Y", Privacy.PUBLIC);
			assertDemotedXdRemainsLocal(3, program, true);
		}
		finally {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}

	private static void assertDemotedXdRemainsLocal(int workers) throws Exception {
		assertDemotedXdRemainsLocal(workers, compile(l2svmScript(workers)), false);
	}

	private static void assertDemotedXdRemainsLocal(int workers, DMLProgram program,
		boolean publicFederatedY) throws Exception {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig heuristicConfig = new DMLConfig(oldConfig);
		heuristicConfig.setTextValue(DMLConfig.FEDERATED_PLANNER,
			"compile_fed_heuristic_single_pass");
		AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
		try {
			ConfigurationManager.setGlobalConfig(heuristicConfig);
			ConfigurationManager.setLocalConfig(heuristicConfig);
			new DMLTranslator(program).constructLops(program, captured::set);
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
		}
		Assert.assertTrue("workers=" + workers + " must invoke the AggLocal production planner",
			captured.get() instanceof HeuristicInvocationReceipt);
		HeuristicInvocationReceipt receipt = (HeuristicInvocationReceipt) captured.get();
		var analysis = receipt.analysis();
		if(publicFederatedY) {
			Assert.assertEquals(Privacy.PRIVATE_AGGREGATE,
				analysis.requirePrivacy(uniqueFederatedSource(analysis, "X").key()));
			Assert.assertEquals(Privacy.PUBLIC,
				analysis.requirePrivacy(uniqueFederatedSource(analysis, "Y").key()));
		}
		var xdPath = analysis.heuristicPolicyFacts().paths().stream()
			.filter(path -> {
				var hop = analysis.hop(path.demotion().producer()).orElseThrow();
				return "Xd".equals(hop.getName()) && hop.getBeginLine() == 99;
			})
			.findFirst().orElseThrow(() -> new AssertionError(
				"L2SVM Xd demotion marker is missing for workers=" + workers));
		var nativeRowLout = publicFederatedY
			? analysis.heuristicPolicyFacts().paths().stream()
				.flatMap(path -> path.nativeContinuations().stream())
				.filter(fact -> analysis.hop(fact.consumer()).orElseThrow().getBeginLine() == 124)
				.filter(fact -> fact.consumerState().execType()
					== org.apache.sysds.common.Types.ExecType.FED
					&& fact.consumerState().output()
						== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT
					&& fact.consumerState().fType() == FType.ROW
					&& !fact.consumerState().shapeDependent())
				.findFirst().orElseThrow(() -> new AssertionError(
					"line-124 must retain its exact native FED/LOUT/ROW continuation"))
			: null;
		if(nativeRowLout != null)
			Assert.assertTrue("The exact candidate must publish the native ROW LOUT state",
				nativeRowLout.runtimeCandidate().allowedEmissionFacts().stream().anyMatch(emission ->
					emission.emissionState().placementState().equals(nativeRowLout.consumerState())
						&& emission.executionFType() == FType.ROW));

		Set<CompiledHopKey> xdReads = new LinkedHashSet<>();
		for(CompiledHopKey key : xdPath.localPrefix()) {
			var node = analysis.graph().node(key).orElseThrow();
			var hop = analysis.hop(key).orElseThrow();
			if(node.kind() == NodeKind.TRANSIENT_READ && hop instanceof DataOp
				&& "Xd".equals(hop.getName()))
				xdReads.add(key);
		}
		Set<Integer> xdReadLines = xdReads.stream()
			.map(key -> analysis.hop(key).orElseThrow().getBeginLine())
			.collect(java.util.stream.Collectors.toSet());
		Assert.assertTrue("workers=" + workers
			+ " line-99 Xd demotion must reach both nested-loop and update-block TReads: "
			+ xdReadLines, xdReadLines.containsAll(Set.of(110, 118)));
		Assert.assertTrue("The exact local path must cross both transient CFG boundaries",
			xdPath.edges().stream().filter(edge -> edge.kind() == HeuristicPathEdgeKind.CFG_TRANSIENT_FORWARD)
				.count() >= 2);

		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey> markers =
			analysis.heuristicPolicyFacts().demotions().stream().map(fact -> fact.valueVersion())
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		var selected = receipt.result();
		if(nativeRowLout != null)
			Assert.assertEquals("Policy projection must retain line-124's certified native LOUT state",
				nativeRowLout.consumerState(), selected.assignment().get(nativeRowLout.consumer()));
		if(publicFederatedY)
			for(CompiledHopKey key : xdPath.localPrefix())
				if(selected.assignment().containsKey(key)) {
					Assert.assertEquals(org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT,
						selected.assignment().get(key).output());
					if(key != xdPath.demotion().producer())
						Assert.assertEquals("The public-Y loop continuation remains coordinator-local: " + key,
							org.apache.sysds.common.Types.ExecType.CP, selected.assignment().get(key).execType());
				}
		Assert.assertEquals("workers=" + workers + " must consume every analysis-owned marker",
			markers, receipt.markers());
		Assert.assertEquals("workers=" + workers + " must use first-feasible policy search",
			"FIRST_FEASIBLE", selected.plannerFacts().get("search"));
		Assert.assertEquals("workers=" + workers + " must publish the AggLocal comparator",
			"FEDERATED_FIRST", selected.plannerFacts().get("stateOrdering"));
		Assert.assertEquals("workers=" + workers + " must retain FedFirst placement priority under the local-vector policy",
			"MAX_FED", selected.orderedTieBreaks().get(0));
		Assert.assertEquals("workers=" + workers + " must terminate with a certified policy plan",
			"POLICY_FEASIBLE", selected.certificate().terminationReason());
		Assert.assertEquals("workers=" + workers + " must use the shared FedFirst certificate",
			"deterministic-component-first-feasible-with-localized-arc-consistency",
			selected.certificate().boundDerivation());
		Assert.assertFalse("workers=" + workers + " must not use planner fallback",
			selected.certificate().fallbackUsed());
		Assert.assertEquals("workers=" + workers + " must emit exactly once without repair",
			new FederatedPlannerFedHeuristic.InvocationCounters(1, 0, 0, 0, 0, 0, 1, 0),
			receipt.counters());
		Assert.assertEquals("workers=" + workers + " must retain a complete assignment",
			selected.selectorGraph().decisionNodes().size(), selected.assignment().size());
		Assert.assertTrue("workers=" + workers + " must retain candidate reachability",
			CandidateSelections.canStillBeReachable(analysis, selected.selectorGraph(),
				selected.selectorGraph().relocationActions(), selected.assignment()));
		var canonical = CandidateSelections.selectMaterializationMaximal(analysis,
			selected.selectorGraph(), selected.selectorGraph().relocationActions(), selected.assignment());
		Assert.assertEquals("workers=" + workers + " must retain exact candidate receipts",
			canonical.candidates().stream().map(candidate -> candidate.normalizedSignature())
				.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)),
			selected.selectedCandidateSelections().stream().map(candidate -> candidate.normalizedSignature())
				.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)));
		var xdState = selected.assignment().get(xdPath.demotion().producer());
		Assert.assertTrue("workers=" + workers + " Xd must use a legal local-result execution",
			xdState.execType() == org.apache.sysds.common.Types.ExecType.CP
				|| xdState.execType() == org.apache.sysds.common.Types.ExecType.FED);
		if(workers > 1)
			Assert.assertEquals("multi-partition Xd must execute at the federated workers",
				org.apache.sysds.common.Types.ExecType.FED, xdState.execType());
		Assert.assertEquals("workers=" + workers + " Xd must return its aggregate vector locally",
			org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT,
			xdState.output());
		var xdHop = analysis.hop(xdPath.demotion().producer()).orElseThrow();
		Assert.assertEquals("workers=" + workers + " selected Xd HOP must match the certified state",
			xdState.execType(), xdHop.getExecType());
		Assert.assertEquals("workers=" + workers + " must retain LOUT on the selected Xd HOP",
			org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT,
			xdHop.getFederatedOutput());
		Assert.assertNotNull("workers=" + workers + " must construct the selected Xd Lop",
			xdHop.getLops());
		Assert.assertEquals("workers=" + workers + " selected Xd Lop must match the certified state",
			xdState.execType(), xdHop.getLops().getExecType());
		Assert.assertEquals("workers=" + workers + " must lower the selected Xd Lop with LOUT",
			org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT,
			xdHop.getLops().getFederatedOutput());
		Assert.assertTrue("A demoted Xd must not be uploaded again inside either repeated loop: "
			+ selected.selectedRelocations(),
			selected.selectedRelocations().stream().noneMatch(action ->
				"Xd".equals(action.sourceValueVersion().lexicalVariable())));
	}

	@Test
	public void singlePassComponentsRetainFunctionFormalCandidateDependencies() throws Exception {
		var analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(
			compile(l2svmScript(2)));
		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey> markers =
			analysis.heuristicPolicyFacts().demotions().stream().map(fact -> fact.valueVersion())
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

		var selected = new FederatedPlannerFedHeuristicSinglePass().select(analysis, markers);
		var movementFirst = new HeuristicPlacementAdapter(new PolicyFirstFeasiblePlacementSelector(
			PolicyFirstFeasiblePlacementSelector.StateOrdering.MOVEMENT_FIRST))
			.select(analysis, markers);

		Assert.assertEquals("single-pass L2SVM must return a complete policy assignment",
			selected.selectorGraph().decisionNodes().size(), selected.assignment().size());
		Assert.assertTrue("merged single-pass components must remain exact-candidate reachable",
			CandidateSelections.canStillBeReachable(analysis, selected.selectorGraph(),
				selected.selectorGraph().relocationActions(), selected.assignment()));
		var canonical = CandidateSelections.selectMaterializationMaximal(analysis,
			selected.selectorGraph(), selected.selectorGraph().relocationActions(), selected.assignment());
		Assert.assertEquals("adapter receipts must equal the exact candidate-row projection",
			canonical.candidates().stream().map(candidate -> candidate.normalizedSignature())
				.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)),
			selected.selectedCandidateSelections().stream().map(candidate -> candidate.normalizedSignature())
				.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)));
		Assert.assertNotEquals("selector ordering is part of the immutable policy-view identity",
			movementFirst.certificate().policyViewFingerprint(),
			selected.certificate().policyViewFingerprint());
		Assert.assertTrue("A demoted Xd must not be uploaded again inside either repeated loop: "
			+ selected.selectedRelocations(),
			selected.selectedRelocations().stream().noneMatch(action ->
				"Xd".equals(action.sourceValueVersion().lexicalVariable())));
	}

	@Test
	public void candidateComponentDependenciesExposeRecursiveFunctionSourceClosure() throws Exception {
		var analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(
			compile(l2svmScript(2)));
		var reachability = CandidateSelections.partialReachabilityIndex(analysis,
			analysis.graph(), analysis.graph().relocationActions());
		Set<CandidateSelections.ComponentDependency> dependencies =
			new LinkedHashSet<>(reachability.componentDependencies());
		Map<CompiledHopKey,List<CompiledHopKey>> sourcesByFormal = new HashMap<>();
		for(var fact : analysis.logicalFunctionInputsInCanonicalOrder())
			sourcesByFormal.computeIfAbsent(fact.targetRead(), ignored -> new ArrayList<>())
				.add(fact.sourceArgument());
		Map<CompiledHopKey,Set<Integer>> candidateInputs = new HashMap<>();
		for(var fact : analysis.candidateRuleFacts().orderedFacts()) {
			if(fact.status() != PlacementAnalysis.CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(int position = 0; position < fact.key().orderedInputs().size(); position++)
				if(fact.key().orderedInputs().get(position).present())
					candidateInputs.computeIfAbsent(fact.key().parentOccurrence(),
						ignored -> new LinkedHashSet<>()).add(position);
		}
		Set<CompiledHopKey> decisions = analysis.graph().decisionNodes().stream()
			.map(node -> node.key()).collect(java.util.stream.Collectors.toSet());
		boolean witnessedFunctionClosure = false;
		boolean witnessedDecisionCoupling = false;
		for(var edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(!candidateInputs.getOrDefault(edge.consumer(), Set.of()).contains(edge.inputPosition())
				|| !sourcesByFormal.containsKey(edge.producer()))
				continue;
			Assert.assertTrue("candidate dependency closure must retain the direct formal producer",
				dependencies.contains(new CandidateSelections.ComponentDependency(
					edge.producer(), edge.consumer())));
			Set<CompiledHopKey> visited = new LinkedHashSet<>();
			ArrayDeque<CompiledHopKey> pending = new ArrayDeque<>();
			pending.add(edge.producer());
			while(!pending.isEmpty()) {
				CompiledHopKey formal = pending.removeFirst();
				if(!visited.add(formal))
					continue;
				for(CompiledHopKey source : sourcesByFormal.getOrDefault(formal, List.of())) {
					witnessedFunctionClosure = true;
					Assert.assertTrue("recursive function/transient source must couple to its candidate consumer",
						dependencies.contains(new CandidateSelections.ComponentDependency(
							source, edge.consumer())));
					witnessedDecisionCoupling |= decisions.contains(source)
						&& decisions.contains(edge.consumer());
					pending.addLast(source);
				}
			}
		}
		Assert.assertTrue("L2SVM w2 must expose a function/transient candidate dependency",
			witnessedFunctionClosure);
		Assert.assertTrue("the closure must connect independently selectable L2SVM occurrences",
			witnessedDecisionCoupling);
	}

	private static String l2svmScript(int workers) throws Exception {
		return federated("X", 50000, 2100, workers) + "\n"
			+ federated("Y", 50000, 1, workers) + "\n"
			+ "B=l2svm(X=X,Y=Y,verbose=FALSE,epsilon=1e-22,maxIterations=30);\n"
			+ "write(B,\"out\",format=\"csv\");\n";
	}

	private static String federated(String name, long rows, long cols, int workers) throws Exception {
		List<String> addresses = new ArrayList<>();
		List<String> ranges = new ArrayList<>();
		for(int worker = 0; worker < workers; worker++) {
			long begin = rows * worker / workers;
			long end = rows * (worker + 1L) / workers;
			Path data = Files.createTempFile("g014-heuristic-l2svm-" + name.toLowerCase()
				+ "-w" + workers + "-p" + (worker + 1) + '-', ".data");
			Path metadata = Path.of(data + ".mtd");
			Files.writeString(data, "");
			Files.writeString(metadata, "{\"data_type\":\"matrix\",\"value_type\":\"double\","
				+ "\"format\":\"binary\",\"rows\":" + (end - begin) + ",\"cols\":" + cols + ','
				+ "\"rows_in_block\":1000,\"cols_in_block\":1000,\"nnz\":" + ((end - begin) * cols)
				+ ",\"privacy\":\"private-aggregate\"}");
			data.toFile().deleteOnExit();
			metadata.toFile().deleteOnExit();
			addresses.add("\"localhost:" + (1234 + worker) + "//" + data + "\"");
			ranges.add("list(" + begin + ",0)");
			ranges.add("list(" + end + ',' + cols + ")");
		}
		return name + "=federated(addresses=list(" + String.join(",", addresses)
			+ "),ranges=list(" + String.join(",", ranges) + "));";
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}

	private static void setFederatedSourcePrivacy(DMLProgram program, String sourceName,
		Privacy privacy) {
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		program.getStatementBlocks().stream().filter(block -> block.getHops() != null)
			.forEach(block -> pending.addAll(block.getHops()));
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		int matches = 0;
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(!visited.add(hop))
				continue;
			if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED
				&& sourceName.equals(data.getName())) {
				FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(data, privacy);
				matches++;
			}
			pending.addAll(hop.getInput());
		}
		Assert.assertEquals("fixture requires one federated source " + sourceName, 1, matches);
	}

	private static Node uniqueFederatedSource(PlacementAnalysis analysis, String sourceName) {
		var matches = analysis.graph().nodes().stream().filter(node -> analysis.hop(node.key())
			.map(hop -> hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED
				&& sourceName.equals(data.getName())).orElse(false)).toList();
		Assert.assertEquals("analysis requires one federated source " + sourceName, 1, matches.size());
		return matches.get(0);
	}
}
