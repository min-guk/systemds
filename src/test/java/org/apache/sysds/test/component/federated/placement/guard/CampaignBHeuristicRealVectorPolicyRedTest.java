/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Executable S4 RED for production-derived Heuristic vector policy facts and selector filtering. */
public class CampaignBHeuristicRealVectorPolicyRedTest {
	private record VectorCase(String name, String script, long rows, long cols, FType fType) { }

	private static final List<VectorCase> KNOWN_VECTOR_CASES = List.of(
		new VectorCase("ROW_4x1", script(
			"list(list(0,0),list(2,2),list(2,0),list(4,2))", "matrix(1,2,1)", "X%*%v"),
			4, 1, FType.ROW),
		new VectorCase("COL_1x4", script(
			"list(list(0,0),list(1,2),list(0,2),list(1,4))", "matrix(1,4,4)", "X%*%v"),
			1, 4, FType.COL),
		new VectorCase("ROW_1x1", script(
			"list(list(0,0),list(2,1),list(2,0),list(4,1))", "matrix(1,1,4)", "v%*%X"),
			1, 1, FType.ROW),
		new VectorCase("COL_1x1", script(
			"list(list(0,0),list(1,2),list(0,2),list(1,4))", "matrix(1,4,1)", "X%*%v"),
			1, 1, FType.COL));

	@Test
	public void knownShapeVectorsProduceTypedPolicyAndExactLocalSelection() throws Exception {
		for(VectorCase fixture : KNOWN_VECTOR_CASES) {
			DMLProgram program = compile(fixture.script());
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			var before = R4Heuristic2Probe.snapshot(program, analysis);
			var projection = aggregateVector(analysis, fixture);
			if(fixture.name().equals("COL_1x4"))
				assertRuntimeSupportedLeftColSource(analysis, projection);
			Node vectorNode = analysis.graph().node(projection.key()).orElseThrow();
			var facts = analysis.heuristicPolicyFacts().demotions().stream()
				.filter(fact -> fact.producer().equals(projection.key())).toList();
			Assert.assertEquals(fixture.name() + " must have one producer-scoped typed demotion", 1, facts.size());
			Assert.assertEquals(fixture.name() + " typed value identity", vectorNode.valueVersion(),
				facts.get(0).valueVersion());

			Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey> markers =
				Set.of(facts.get(0).valueVersion());
			NeutralPlacementGraph selectorGraph = selectorGraph(selectRaw(analysis, markers));
			var selection = R4Heuristic2AdapterBridge.select(analysis, markers);
			List<PlacementState> vectorStates = selectorGraph.node(projection.key()).orElseThrow().legalAlternatives();
			Assert.assertEquals(fixture.name() + " policy view must contain exactly one state", 1,
				vectorStates.size());
			PlacementState state = vectorStates.get(0);
			Assert.assertEquals(fixture.name() + " execution", ExecType.FED, state.execType());
			Assert.assertEquals(fixture.name() + " result remains local", FederatedOutput.LOUT, state.output());
			Assert.assertEquals(fixture.name() + " preserves concrete input FType", fixture.fType(), state.fType());
			Assert.assertTrue(fixture.name() + " legality is known-shape dependent", state.shapeDependent());
			Assert.assertEquals(fixture.name() + " exact selection", state.normalizedSignature(),
				selection.assignments().get(projection.key().normalizedSignature()));
			Assert.assertFalse(fixture.name() + " rejects CP/LOUT", vectorStates.stream().anyMatch(candidate ->
				candidate.execType() == ExecType.CP && candidate.output() == FederatedOutput.LOUT));
			Assert.assertFalse(fixture.name() + " rejects FED/FOUT", vectorStates.stream().anyMatch(candidate ->
				candidate.execType() == ExecType.FED && candidate.output() == FederatedOutput.FOUT));
			R4Heuristic2Probe.unchanged(before, R4Heuristic2Probe.snapshot(program, analysis));
		}
		provenRefedCandidateIsRemovedFromActualEncodedSelectorUniverse();
	}

	@Test
	public void unknownVectorShapeDoesNotProveTypedPolicy() throws Exception {
		VectorCase fixture = KNOWN_VECTOR_CASES.get(0);
		DMLProgram program = compile(fixture.script());
		PlacementAnalysis known = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var projection = aggregateVector(known, fixture);
		Assert.assertTrue("unknown-shape negative must start from a real aggregate-binary vector",
			projection.hop() instanceof AggBinaryOp);
		projection.hop().setDim1(-1);
		projection.hop().setDim2(-1);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Assert.assertTrue("unknown shape must not create a vector demotion",
			analysis.heuristicPolicyFacts().demotions().isEmpty());
	}

	private static void provenRefedCandidateIsRemovedFromActualEncodedSelectorUniverse() throws Exception {
		var fixture = CampaignBProvenanceFixtureBridge.fresh("H-09-INDEPENDENT-ANCHOR-RELEASE");
		PlacementAnalysis analysis = withProvenCandidates(fixture);
		Map<String, String> encodedCandidates = new LinkedHashMap<>();
		for(var proof : fixture.candidateProofs().values()) {
			String encoded = candidate(proof.atom().node().normalizedSignature(), proof.state().normalizedSignature());
			encodedCandidates.put(proof.candidate(), encoded);
			Assert.assertTrue("dedicated provenance graph must really contain the otherwise-legal candidate",
				analysis.graph().normalizedCandidateUniverse().contains(encoded));
		}

		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey> markers =
			Set.of(fixture.marker());
		NeutralPlacementGraph selectorGraph = selectorGraph(selectRaw(analysis, markers));
		var selection = R4Heuristic2AdapterBridge.select(analysis, markers);
		for(String encoded : encodedCandidates.values())
			Assert.assertFalse("proven refederated descendant must be absent from actual selector graph",
				selectorGraph.normalizedCandidateUniverse().contains(encoded));
		for(var assigned : selection.assignments().entrySet())
			Assert.assertTrue("assignment must use exact length-prefixed graph encoding: " + assigned,
				selectorGraph.normalizedCandidateUniverse().contains(candidate(assigned.getKey(), assigned.getValue())));
		Assert.assertFalse("exact selection cannot use fallback", selection.certificate().fallback());
		Assert.assertEquals(List.of(), selection.refedRegistry());
		Assert.assertEquals(List.of(), selection.relocations());
		Assert.assertEquals(List.of(), selection.obligations());
	}

	@Test
	public void independentAnchorReleaseSentinelRemainsGreen() throws Exception {
		var fixture = CampaignBProvenanceFixtureBridge.fresh("H-09-INDEPENDENT-ANCHOR-RELEASE");
		var before = R4Heuristic2Probe.snapshot(fixture.program(), fixture.analysis());
		var selection = R4Heuristic2AdapterBridge.select(fixture.analysis(), Set.of(fixture.marker()));
		R4Heuristic2SemanticValidator.heuristic(fixture, selection);
		R4Heuristic2Probe.unchanged(before, R4Heuristic2Probe.snapshot(fixture.program(), fixture.analysis()));
	}

	private static PlacementAnalysis withProvenCandidates(CampaignBProvenanceFixtureBridge.Fixture fixture)
		throws Exception {
		Map<CompiledHopKey, Node> replacements = new HashMap<>();
		for(var proof : fixture.candidateProofs().values()) {
			Node node = fixture.analysis().graph().node(proof.provenNode()).orElseThrow();
			List<PlacementState> legal = new ArrayList<>(node.legalAlternatives());
			if(!legal.contains(proof.state()))
				legal.add(proof.state());
			replacements.put(node.key(), new Node(node.key(), node.kind(), node.valueVersion(), node.emittedWork(),
				legal, node.exclusions().stream().filter(x -> !x.state().equals(proof.state())).toList(), node.anchors()));
		}
		NeutralPlacementGraph graph = new NeutralPlacementGraph(fixture.analysis().graph().nodes().stream()
			.map(node -> replacements.getOrDefault(node.key(), node)).toList(),
			fixture.analysis().graph().constraints(), fixture.analysis().graph().relocationActions());
		return CampaignBPlacementAnalysisFixtureBridge.replaceGraph(fixture.analysis(), graph);
	}

	private static PlacementAnalysis.HopOccurrenceProjection aggregateVector(PlacementAnalysis analysis,
		VectorCase fixture) {
		List<PlacementAnalysis.HopOccurrenceProjection> projections = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof AggBinaryOp)
			.filter(occurrence -> occurrence.hop().getDim1() == fixture.rows()
				&& occurrence.hop().getDim2() == fixture.cols()).toList();
		Assert.assertEquals(fixture.name() + " must contain one real aggregate-binary vector", 1,
			projections.size());
		return projections.get(0);
	}

	private static void assertRuntimeSupportedLeftColSource(PlacementAnalysis analysis,
		PlacementAnalysis.HopOccurrenceProjection projection) {
		Assert.assertTrue("COL_1x4 contract requires a real aggregate-binary operation",
			projection.hop() instanceof AggBinaryOp);
		var aggregate = (AggBinaryOp) projection.hop();
		var sourceOccurrences = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() == aggregate.getInput().get(0)).toList();
		Assert.assertEquals("COL_1x4 input0 must resolve to one durable federated source", 1,
			sourceOccurrences.size());
		Node source = analysis.graph().node(sourceOccurrences.get(0).key()).orElseThrow();
		Assert.assertEquals("COL_1x4 input0 must carry one durable anchor", 1, source.anchors().size());
		Assert.assertEquals("COL_1x4 input0 durable anchor FType", FType.COL, source.anchors().get(0).fType());
		Assert.assertTrue("COL_1x4 input0 must expose legal FED/FOUT/COL",
			source.legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT && state.fType() == FType.COL));

		var evidence = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry())
			.decideWithEvidence(aggregate, Arrays.asList(FType.COL, null), null);
		Assert.assertTrue("COL_1x4 direct oracle must have no missing required shape facts",
			evidence.shapeProof().missingRequiredFacts().isEmpty());
		Assert.assertEquals("COL_1x4 direct oracle execution", ExecType.FED, evidence.caps().exec());
		Assert.assertEquals("COL_1x4 direct oracle output", FederatedOutput.LOUT,
			evidence.caps().placement());
	}

	private static String candidate(String key, String state) {
		return token(key) + "|" + token(state);
	}

	private static String token(String value) {
		return value.length() + ":" + value;
	}

	private static Object selectRaw(PlacementAnalysis analysis, Set<?> markers) throws Exception {
		Class<?> adapter = Class.forName(
			"org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter");
		try {
			return adapter.getMethod("select", PlacementAnalysis.class, Set.class)
				.invoke(adapter.getConstructor().newInstance(), analysis, Set.copyOf(markers));
		}
		catch(InvocationTargetException failure) {
			throw new AssertionError("Heuristic policy selection failed", failure.getCause());
		}
	}

	private static NeutralPlacementGraph selectorGraph(Object result) throws Exception {
		try {
			return (NeutralPlacementGraph) result.getClass().getMethod("selectorGraph").invoke(result);
		}
		catch(NoSuchMethodException missing) {
			throw new AssertionError(
				"S4 requires the immutable graph actually supplied to ExactPlacementSelector", missing);
		}
	}

	private static String script(String ranges, String vector, String expression) {
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),ranges=" + ranges + ");",
			"v=" + vector + ";", "z=" + expression + ";", "w=z+1;", "print(sum(w));") + "\n";
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}
}
