/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.AnchorForm;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.ObservationResult;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.ObservationState;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.RegistrationFact;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** RED contract for the public anchor-provenance observation bridge. */
public class AnchorProvenanceObserverFactoryContractTest {
	@Test
	public void nullAnalysisIsInvalidWithoutFact() {
		ObservationResult result = observe(null, nonFederatedSource());

		assertEmpty(ObservationState.INVALID_REQUEST, result, "placement analysis is null");
	}

	@Test
	public void nullFederatedSourceIsInvalidWithoutFact() throws Exception {
		PlacementAnalysis analysis = compileAnalysis("B-11");
		AnalysisSnapshot before = AnalysisSnapshot.capture(analysis);

		ObservationResult result = observe(analysis, null);

		assertEmpty(ObservationState.INVALID_REQUEST, result, "federated source DataOp is null");
		before.assertUnchanged(analysis);
	}

	@Test
	public void nonFederatedSourceIsInvalidWithoutFact() throws Exception {
		PlacementAnalysis analysis = compileAnalysis("B-11");
		AnalysisSnapshot before = AnalysisSnapshot.capture(analysis);

		ObservationResult result = observe(analysis, nonFederatedSource());

		assertEmpty(ObservationState.INVALID_REQUEST, result,
			"source DataOp is not a federated data operation");
		before.assertUnchanged(analysis);
	}

	@Test
	public void b11ExactAcceptedFederatedSourceYieldsAvailableRegistrationFact() throws Exception {
		PlacementAnalysis analysis = compileAnalysis("B-11");
		SourceBinding source = soleFederatedSource(analysis);
		DurableAnchorKey anchor = soleAnchor(source.node);
		assertExactLegalSourceState(source.node, anchor.fType(), false);
		Assert.assertEquals("durable source must not duplicate exact FED/FOUT state", 1,
			fedFoutStateCount(source.node));
		AnalysisSnapshot before = AnalysisSnapshot.capture(analysis);

		ObservationResult result = observe(analysis, source.dataOp);

		Assert.assertEquals(ObservationState.AVAILABLE, result.state());
		RegistrationFact fact = result.fact().orElseThrow(AssertionError::new);
		Assert.assertEquals(analysis.analysisFingerprint(), fact.analysisFingerprint());
		Assert.assertEquals(source.key, fact.occurrenceKey());
		Assert.assertEquals(AnchorForm.FEDINIT_LITERAL, fact.anchorForm());
		Assert.assertEquals(anchor, fact.normalizedAnchorIdentity());
		Assert.assertEquals(anchor.fType(), fact.fType());
		Assert.assertNotNull(fact.fType());
		Assert.assertEquals(anchor.partitions(), fact.partitions());
		Assert.assertFalse(fact.partitions().isEmpty());
		Assert.assertEquals("anchor provenance is available", result.detail());
		before.assertUnchanged(analysis);
	}

	@Test
	public void clonedFederatedSourceWithEqualCopiedFieldsHasZeroIdentityMatch() throws Exception {
		PlacementAnalysis analysis = compileAnalysis("B-11");
		SourceBinding source = soleFederatedSource(analysis);
		DataOp clone = (DataOp) source.dataOp.clone();
		assertEquivalentVisibleFederatedSource(source.dataOp, clone);
		AnalysisSnapshot before = AnalysisSnapshot.capture(analysis);

		ObservationResult result = observe(analysis, clone);

		assertEmpty(ObservationState.INVALID_REQUEST, result,
			"source DataOp is not bound by the placement analysis");
		before.assertUnchanged(analysis);
	}

	@Test
	public void independentlyCompiledForeignB11SourceHasZeroIdentityMatch() throws Exception {
		PlacementAnalysis analysis = compileAnalysis("B-11");
		SourceBinding source = soleFederatedSource(analysis);
		PlacementAnalysis foreignAnalysis = compileAnalysis("B-11");
		SourceBinding foreignSource = soleFederatedSource(foreignAnalysis);
		Assert.assertNotSame(source.dataOp, foreignSource.dataOp);
		assertEquivalentVisibleFederatedSource(source.dataOp, foreignSource.dataOp);
		AnalysisSnapshot before = AnalysisSnapshot.capture(analysis);
		AnalysisSnapshot foreignBefore = AnalysisSnapshot.capture(foreignAnalysis);

		ObservationResult result = observe(analysis, foreignSource.dataOp);

		assertEmpty(ObservationState.INVALID_REQUEST, result,
			"source DataOp is not bound by the placement analysis");
		before.assertUnchanged(analysis);
		foreignBefore.assertUnchanged(foreignAnalysis);
	}

	@Test
	public void missingOccurrenceKeyFromGraphFailsClosedDuringAnalysisConstructionWithoutMutation() throws Exception {
		PlacementAnalysis analysis = compileAnalysis("B-11");
		SourceBinding source = soleFederatedSource(analysis);
		AnalysisSnapshot before = AnalysisSnapshot.capture(analysis);

		try {
			CampaignBPlacementAnalysisFixtureBridge.missingHopProjectionTrap(analysis, source.key);
			Assert.fail("foreign occurrence key trap unexpectedly constructed");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals("Occurrence has a foreign graph key", expected.getMessage());
		}

		before.assertUnchanged(analysis);
	}

	@Test
	public void sameFederatedSourceInTwoDistinctOccurrenceKeysIsInvalid() throws Exception {
		PlacementAnalysis analysis = compileAnalysis("B-11");
		SourceBinding source = soleFederatedSource(analysis);
		PlacementAnalysis ambiguous = duplicateOccurrenceAnalysis(analysis, source);
		Assert.assertEquals(2, ambiguous.occurrences().stream()
			.filter(occurrence -> occurrence.hop() == source.dataOp).count());
		Assert.assertEquals(2, ambiguous.occurrences().stream()
			.filter(occurrence -> occurrence.hop() == source.dataOp)
			.map(HopOccurrenceProjection::key).distinct().count());
		AnalysisSnapshot before = AnalysisSnapshot.capture(ambiguous);

		ObservationResult result = observe(ambiguous, source.dataOp);

		assertEmpty(ObservationState.INVALID_REQUEST, result,
			"source DataOp is bound by multiple placement occurrences");
		before.assertUnchanged(ambiguous);
	}

	@Test
	public void b13ExactOtherSourceIsLegalButStillUnavailableAsDurableAnchor() throws Exception {
		PlacementAnalysis analysis = compileAnalysis("B-13");
		SourceBinding source = soleFederatedSource(analysis);
		Assert.assertTrue("B13 diagonal fed-init must not expose a durable relocation anchor",
			source.node.anchors().isEmpty());
		assertExactLegalSourceState(source.node, FType.OTHER, false);
		Assert.assertEquals("B13 exact source must publish exactly one FED/FOUT legal state", 1,
			fedFoutStateCount(source.node));
		Assert.assertFalse("B13 exact source must not retain stale source-state UNSUPPORTED_ANCHOR exclusion",
			source.node.exclusions().stream().anyMatch(exclusion ->
				exclusion.reasonCode() == NeutralPlacementGraph.ReasonCode.UNSUPPORTED_ANCHOR
					&& exclusion.state().execType() == ExecType.FED
					&& exclusion.state().output() == FederatedOutput.FOUT));
		Assert.assertFalse("B13 source must not authorize relocation actions without a durable anchor",
			analysis.graph().relocationActions().stream().anyMatch(action ->
				action.key().sourceValueVersion().equals(source.node.valueVersion())
					|| action.obligations().stream().anyMatch(obligation ->
						obligation.sourceValueVersion().equals(source.node.valueVersion()))));
		AnalysisSnapshot before = AnalysisSnapshot.capture(analysis);

		ObservationResult result = observe(analysis, source.dataOp);

		assertEmpty(ObservationState.UNAVAILABLE, result,
			"matched source has no supported durable anchor");
		before.assertUnchanged(analysis);
	}

	@Test
	public void exactSourceWithTwoDistinctDurableAnchorsIsInvalid() throws Exception {
		PlacementAnalysis analysis = compileAnalysis("B-11");
		SourceBinding source = soleFederatedSource(analysis);
		DurableAnchorKey first = soleAnchor(source.node);
		DurableAnchorKey second = new DurableAnchorKey(first.placementId() + "|distinct",
			first.fType(), first.partitions());
		Assert.assertNotEquals(first, second);
		PlacementAnalysis ambiguous = replaceNode(analysis, new Node(source.key, source.node.kind(),
			source.node.valueVersion(), source.node.emittedWork(), source.node.legalAlternatives(),
			source.node.exclusions(), List.of(first, second)));
		AnalysisSnapshot before = AnalysisSnapshot.capture(ambiguous);

		ObservationResult result = observe(ambiguous, source.dataOp);

		assertEmpty(ObservationState.INVALID_REQUEST, result,
			"matched source has multiple durable anchors");
		before.assertUnchanged(ambiguous);
	}

	private static void assertExactLegalSourceState(Node node, FType fType, boolean shapeDependent) {
		Assert.assertTrue("node must expose exact legal source state " + fType + " in "
			+ node.legalAlternatives(), node.legalAlternatives().contains(
				new PlacementState(ExecType.FED, FederatedOutput.FOUT, fType, shapeDependent)));
	}

	private static long fedFoutStateCount(Node node) {
		return node.legalAlternatives().stream().filter(state ->
			state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT).count();
	}

	private static ObservationResult observe(PlacementAnalysis analysis, DataOp dataOp) {
		return AnchorProvenanceObserverFactory.observer().observe(analysis, dataOp);
	}

	private static PlacementAnalysis compileAnalysis(String fixture) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static DataOp nonFederatedSource() {
		return new DataOp("local", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "local", 4, 2, -1, -1);
	}

	private static SourceBinding soleFederatedSource(PlacementAnalysis analysis) {
		List<SourceBinding> matches = new ArrayList<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
			Hop hop = occurrence.hop();
			if(hop instanceof DataOp && ((DataOp) hop).isFederatedDataOp()) {
				Node node = analysis.graph().node(occurrence.key()).orElseThrow(AssertionError::new);
				matches.add(new SourceBinding((DataOp) hop, occurrence.key(), occurrence.scopeId(), node));
			}
		}
		Assert.assertEquals("fixture must have one exact federated DataOp occurrence", 1, matches.size());
		return matches.get(0);
	}

	private static DurableAnchorKey soleAnchor(Node node) {
		Assert.assertEquals("fixture node must expose exactly one durable anchor", 1, node.anchors().size());
		return node.anchors().get(0);
	}

	private static void assertEmpty(ObservationState state, ObservationResult result, String detail) {
		Assert.assertEquals(state, result.state());
		Assert.assertFalse(result.fact().isPresent());
		Assert.assertEquals(detail, result.detail());
	}

	private static void assertEquivalentVisibleFederatedSource(DataOp expected, DataOp actual) {
		Assert.assertNotSame(expected, actual);
		Assert.assertEquals(expected.isFederatedDataOp(), actual.isFederatedDataOp());
		Assert.assertTrue(actual.isFederatedDataOp());
		Assert.assertEquals(expected.getOp(), actual.getOp());
		Assert.assertEquals(expected.getName(), actual.getName());
		Assert.assertEquals(expected.getDataType(), actual.getDataType());
		Assert.assertEquals(expected.getValueType(), actual.getValueType());
		Assert.assertEquals(expected.getDim1(), actual.getDim1());
		Assert.assertEquals(expected.getDim2(), actual.getDim2());
		Assert.assertEquals(expected.getNnz(), actual.getNnz());
		Assert.assertEquals(expected.getBlocksize(), actual.getBlocksize());
		Assert.assertEquals(expected.getFederatedOutput(), actual.getFederatedOutput());
	}

	private static PlacementAnalysis duplicateOccurrenceAnalysis(PlacementAnalysis source,
		SourceBinding binding) {
		CompiledHopKey duplicateKey = new CompiledHopKey(binding.key.programFingerprint(),
			binding.key.functionNamespace(), binding.key.callSitePath(), binding.key.recompileContext(),
			binding.key.controlRegion(), binding.key.emittedHopInstance() + "|duplicate-occurrence",
			binding.key.canonicalSourceOrigin() + "|duplicate-occurrence");
		Node duplicateNode = new Node(duplicateKey, binding.node.kind(), binding.node.valueVersion(),
			binding.node.emittedWork(), binding.node.legalAlternatives(), binding.node.exclusions(),
			binding.node.anchors());
		List<Node> nodes = new ArrayList<>(source.graph().nodes());
		nodes.add(duplicateNode);
		NeutralPlacementGraph graph = new NeutralPlacementGraph(nodes, source.graph().constraints(),
			source.graph().relocationActions());
		List<HopOccurrenceProjection> projections = new ArrayList<>(source.occurrences());
		projections.add(new HopOccurrenceProjection(duplicateKey, binding.dataOp, binding.scopeId, projections.size(),
			duplicateKey.normalizedSignature()));
		return new PlacementAnalysis(graph, projections, null, shapeFacts(source, projections),
			"multiple-occurrence-" + source.analysisFingerprint(), source.heuristicPolicyFacts());
	}

	private static PlacementAnalysis replaceNode(PlacementAnalysis source, Node replacement) {
		List<Node> nodes = new ArrayList<>();
		boolean replaced = false;
		for(Node node : source.graph().nodes()) {
			if(node.key().equals(replacement.key())) {
				nodes.add(replacement);
				replaced = true;
			}
			else
				nodes.add(node);
		}
		Assert.assertTrue("replacement node key must be present", replaced);
		NeutralPlacementGraph graph = new NeutralPlacementGraph(nodes, source.graph().constraints(),
			source.graph().relocationActions());
		return CampaignBPlacementAnalysisFixtureBridge.replaceGraph(source, graph);
	}

	private static PlacementShapeFacts shapeFacts(PlacementAnalysis source,
		List<HopOccurrenceProjection> projections) {
		Map<CompiledHopKey, NodeShapeFact> facts = new LinkedHashMap<>();
		LinkedHashSet<CompiledHopKey> expectedKeys = new LinkedHashSet<>();
		for(HopOccurrenceProjection projection : projections) {
			expectedKeys.add(projection.key());
			NodeShapeFact fact = source.shapeFact(projection.key()).orElse(null);
			if(fact == null)
				fact = source.shapeFact(soleFederatedSource(source).key).orElseThrow(AssertionError::new);
			facts.put(projection.key(), fact);
		}
		return new PlacementShapeFacts(facts, expectedKeys);
	}

	private record SourceBinding(DataOp dataOp, CompiledHopKey key, long scopeId, Node node) { }

	private static final class AnalysisSnapshot {
		private final PlacementAnalysis analysis;
		private final NeutralPlacementGraph graph;
		private final String fingerprint;
		private final String graphSignature;
		private final List<HopOccurrenceProjection> occurrences;
		private final List<Hop> occurrenceHops;
		private final List<Node> nodes;
		private final List<DurableAnchorKey> anchors;
		private final List<String> anchorSignatures;
		private final List<String> fullSnapshot;

		private AnalysisSnapshot(PlacementAnalysis analysis) {
			this.analysis = analysis;
			graph = analysis.graph();
			fingerprint = analysis.analysisFingerprint();
			graphSignature = graph.normalizedSignature();
			occurrences = List.copyOf(analysis.occurrences());
			occurrenceHops = analysis.occurrences().stream().map(HopOccurrenceProjection::hop).toList();
			nodes = List.copyOf(graph.nodes());
			anchors = graph.nodes().stream().flatMap(node -> node.anchors().stream()).toList();
			anchorSignatures = anchors.stream().map(DurableAnchorKey::normalizedSignature).toList();
			fullSnapshot = CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(analysis);
		}

		private static AnalysisSnapshot capture(PlacementAnalysis analysis) {
			return new AnalysisSnapshot(analysis);
		}

		private void assertUnchanged(PlacementAnalysis after) {
			Assert.assertSame(analysis, after);
			Assert.assertSame(graph, after.graph());
			Assert.assertEquals(fingerprint, after.analysisFingerprint());
			Assert.assertEquals(graphSignature, after.graph().normalizedSignature());
			Assert.assertEquals(occurrences.size(), after.occurrences().size());
			for(int i = 0; i < occurrences.size(); i++) {
				Assert.assertSame(occurrences.get(i), after.occurrences().get(i));
				Assert.assertSame(occurrenceHops.get(i), after.occurrences().get(i).hop());
			}
			Assert.assertEquals(nodes.size(), after.graph().nodes().size());
			for(int i = 0; i < nodes.size(); i++)
				Assert.assertSame(nodes.get(i), after.graph().nodes().get(i));
			List<DurableAnchorKey> afterAnchors = after.graph().nodes().stream()
				.flatMap(node -> node.anchors().stream()).toList();
			Assert.assertEquals(anchors.size(), afterAnchors.size());
			for(int i = 0; i < anchors.size(); i++)
				Assert.assertSame(anchors.get(i), afterAnchors.get(i));
			Assert.assertEquals(anchorSignatures, afterAnchors.stream()
				.map(DurableAnchorKey::normalizedSignature).toList());
			Assert.assertEquals(fullSnapshot,
				CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(after));
			Assert.assertTrue("observer must not replace Hop identities", unchangedHopIdentitySet(after));
		}

		private boolean unchangedHopIdentitySet(PlacementAnalysis after) {
			Map<Hop,Boolean> before = new IdentityHashMap<>();
			for(Hop hop : occurrenceHops)
				before.put(hop, Boolean.TRUE);
			for(HopOccurrenceProjection occurrence : after.occurrences())
				if(!before.containsKey(occurrence.hop()))
					return false;
			return true;
		}
	}
}
