/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedHeuristic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPathEdgeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.selector.PolicyFirstFeasiblePlacementSelector;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression for preserving a demoted vector across L2SVM's nested loop CFG. */
public class CampaignBG014HeuristicL2SvmLoopLocalityRedTest {
	@Test
	public void demotedXdRemainsLocalAcrossNestedLoopTransientReads() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(l2svmScript(2)));
		var xdPath = analysis.heuristicPolicyFacts().paths().stream()
			.filter(path -> {
				var hop = analysis.hop(path.demotion().producer()).orElseThrow();
				return "Xd".equals(hop.getName()) && hop.getBeginLine() == 99;
			})
			.findFirst().orElseThrow(() -> new AssertionError("L2SVM Xd demotion marker is missing"));

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
		Assert.assertTrue("The line-99 Xd demotion must reach both nested-loop and update-block TReads: "
			+ xdReadLines, xdReadLines.containsAll(Set.of(110, 118)));
		Assert.assertTrue("The exact local path must cross both transient CFG boundaries",
			xdPath.edges().stream().filter(edge -> edge.kind() == HeuristicPathEdgeKind.CFG_TRANSIENT_FORWARD)
				.count() >= 2);

		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey> markers =
			analysis.heuristicPolicyFacts().demotions().stream().map(fact -> fact.valueVersion())
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		var selected = new HeuristicPlacementAdapter().select(analysis, markers);
		Assert.assertTrue("A demoted Xd must not be uploaded again inside either repeated loop",
			selected.selectedRelocations().stream().noneMatch(action ->
				"Xd".equals(action.sourceValueVersion().lexicalVariable())));
	}

	@Test
	public void singlePassComponentsRetainFunctionFormalCandidateDependencies() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(l2svmScript(2)));
		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey> markers =
			analysis.heuristicPolicyFacts().demotions().stream().map(fact -> fact.valueVersion())
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

		var selected = new HeuristicPlacementAdapter(new PolicyFirstFeasiblePlacementSelector())
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
		Assert.assertTrue("A demoted Xd must not be uploaded again inside either repeated loop",
			selected.selectedRelocations().stream().noneMatch(action ->
				"Xd".equals(action.sourceValueVersion().lexicalVariable())));
	}

	@Test
	public void candidateComponentDependenciesExposeRecursiveFunctionSourceClosure() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(l2svmScript(2)));
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
}
