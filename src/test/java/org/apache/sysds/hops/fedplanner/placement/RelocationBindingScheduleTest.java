/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Regression for dependency-scheduled exact relocation receipt binding. */
public class RelocationBindingScheduleTest {
	private static final String FINGERPRINT = "relocation-binding-schedule";
	private static final ControlRegionKey REGION = new ControlRegionKey(
		FINGERPRINT, "main", List.of("root"), "root", "compiled");
	private static final PlacementState STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(STATE, false);
	private static final DurableAnchorKey SOURCE_POOL = new DurableAnchorKey("source-pool", FType.FULL,
		List.of(new AnchorPartition("localhost:19101", List.of(0L, 0L), List.of(4L, 2L))));
	private static final DurableAnchorKey PRODUCER_POOL = new DurableAnchorKey("producer-pool", FType.FULL,
		List.of(new AnchorPartition("localhost:19102", List.of(0L, 0L), List.of(4L, 2L))));
	private static final DurableAnchorKey CONSUMER_POOL = new DurableAnchorKey("consumer-pool", FType.FULL,
		List.of(new AnchorPartition("localhost:19103", List.of(0L, 0L), List.of(4L, 2L))));

	@Test
	public void acyclicScheduleMatchesRepeatedSimultaneousClosureAndIgnoresFactOrder()
		throws Exception {
		Fixture fixture = Fixture.acyclic();
		assertWellFormed(fixture);
		Assert.assertEquals(0, inputBindingCount(fixture.facts));
		List<CandidateRuleFact> scheduled = bind(new NeutralPlacementGraphBuilder(),
			fixture.facts, fixture.nodes, fixture.edges, fixture.actions, fixture.origins, fixture.shapes);
		List<CandidateRuleFact> legacy = settleSimultaneously(fixture);
		Assert.assertEquals("one producer-before-consumer transfer must equal legacy closure",
			canonical(legacy), canonical(scheduled));
		Assert.assertTrue("the intermediate must publish an exact source receipt",
			hasInputBinding(scheduled, fixture.producer, fixture.source,
				CandidateInputBindingKind.RELOCATION));
		Assert.assertTrue("the downstream consumer must see the same-transfer intermediate receipt",
			hasInputBinding(scheduled, fixture.consumer, fixture.producer,
				CandidateInputBindingKind.RELOCATION));

		List<CandidateRuleFact> reversed = new ArrayList<>(fixture.facts);
		Collections.reverse(reversed);
		List<CandidateRuleFact> reordered = bind(new NeutralPlacementGraphBuilder(),
			reversed, fixture.nodes, fixture.edges, fixture.actions, fixture.origins, fixture.shapes);
		Assert.assertEquals("binding output must not depend on candidate-fact storage order",
			canonical(scheduled), canonical(reordered));
	}

	@Test
	public void cyclicComponentRequiresAnotherTransferBeforeItIsConverged() throws Exception {
		Fixture fixture = Fixture.cyclic();
		assertWellFormed(fixture);
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();
		List<CandidateRuleFact> first = bind(builder, fixture.facts, fixture.nodes,
			fixture.edges, fixture.actions, fixture.origins, fixture.shapes);
		List<CandidateRuleFact> second = bind(builder, first, fixture.nodes,
			fixture.edges, fixture.actions, fixture.origins, fixture.shapes);
		Assert.assertTrue("the seeded producer must reach its peer in the first simultaneous transfer",
			hasInputBinding(first, fixture.consumer, fixture.producer,
				CandidateInputBindingKind.RELOCATION));
		Assert.assertFalse("one SCC transfer must not consume a receipt created in that transfer",
			hasInputBinding(first, fixture.producer, fixture.consumer,
				CandidateInputBindingKind.RELOCATION));
		Assert.assertTrue("the next transfer must consume the now-committed peer receipt",
			hasInputBinding(second, fixture.producer, fixture.consumer,
				CandidateInputBindingKind.RELOCATION));
		Assert.assertNotEquals("a cyclic SCC must not be certified converged after one transfer",
			canonical(first), canonical(second));
		settle(fixture, second, 6);
	}

	private static List<CandidateRuleFact> settleSimultaneously(Fixture fixture) throws Exception {
		List<CompiledInputEdgeFact> oneComponent = new ArrayList<>(fixture.edges);
		oneComponent.add(new CompiledInputEdgeFact(fixture.consumer, fixture.source, 0));
		return settle(fixture, fixture.facts, oneComponent, 6);
	}

	private static List<CandidateRuleFact> settle(Fixture fixture,
		List<CandidateRuleFact> initial, int maxPasses) throws Exception {
		return settle(fixture, initial, fixture.edges, maxPasses);
	}

	private static List<CandidateRuleFact> settle(Fixture fixture,
		List<CandidateRuleFact> initial, List<CompiledInputEdgeFact> edges,
		int maxPasses) throws Exception {
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();
		List<CandidateRuleFact> current = initial;
		for(int pass = 0; pass < maxPasses; pass++) {
			List<CandidateRuleFact> next = bind(builder, current, fixture.nodes,
				edges, fixture.actions, fixture.origins, fixture.shapes);
			if(canonical(next).equals(canonical(current)))
				return next;
			current = next;
		}
		throw new AssertionError("bounded relocation fixture did not converge");
	}

	private static boolean hasInputBinding(List<CandidateRuleFact> facts,
		CompiledHopKey owner, CompiledHopKey source, CandidateInputBindingKind kind) {
		return facts.stream().filter(fact -> fact.key().parentOccurrence() == owner)
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.flatMap(realization -> realization.supportClauses().stream())
			.flatMap(clause -> clause.inputBindings().stream())
			.anyMatch(binding -> binding.source().rule().parentOccurrence() == source
				&& binding.kind() == kind);
	}

	private static long inputBindingCount(List<CandidateRuleFact> facts) {
		return facts.stream().flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.flatMap(realization -> realization.supportClauses().stream())
			.mapToLong(clause -> clause.inputBindings().size()).sum();
	}

	private static void assertWellFormed(Fixture fixture) {
		Assert.assertEquals(3, fixture.nodes.size());
		Assert.assertEquals(2, fixture.edges.size());
		Assert.assertEquals(2, fixture.actions.size());
		for(CompiledInputEdgeFact edge : fixture.edges) {
			Assert.assertSame(edge.consumer(), fixture.nodes.stream()
				.map(Node::key).filter(key -> key == edge.consumer()).findFirst().orElse(null));
			Assert.assertSame(edge.producer(), fixture.nodes.stream()
				.map(Node::key).filter(key -> key == edge.producer()).findFirst().orElse(null));
		}
		for(RelocationAction action : fixture.actions) {
			Assert.assertEquals(1, action.obligations().size());
			ObligationKey obligation = action.obligations().get(0);
			Assert.assertEquals(0, obligation.inputPosition());
			Assert.assertEquals(STATE, obligation.requiredPlacement());
			Assert.assertEquals(action.key(), obligation.relocationAction());
			Node source = fixture.nodes.stream()
				.filter(node -> node.valueVersion().equals(action.key().sourceValueVersion()))
				.findFirst().orElseThrow();
			Assert.assertSame(source.key(), fixture.edges.stream()
				.filter(edge -> edge.consumer() == obligation.consumer())
				.map(CompiledInputEdgeFact::producer).findFirst().orElse(null));
		}
		for(Node node : fixture.nodes) {
			Hop origin = fixture.origins.get(node.key());
			Assert.assertNotNull(origin);
			Assert.assertNotNull(fixture.shapes.get(origin));
		}
	}

	private static Map<String,List<String>> canonical(List<CandidateRuleFact> facts) {
		Map<String,List<String>> result = new java.util.TreeMap<>();
		for(CandidateRuleFact fact : facts)
			result.computeIfAbsent(fact.key().parentOccurrence().normalizedSignature(), ignored -> new ArrayList<>())
				.add(fact.key().normalizedSignature() + "=>" + fact.allowedEmissionFacts().stream()
					.map(emission -> emission.selectionSignature() + ':' + emission.realizations().stream()
						.map(CandidateEmissionRealization::normalizedSignature).toList()).toList());
		result.replaceAll((ignored, values) -> values.stream().sorted().toList());
		return result;
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> bind(NeutralPlacementGraphBuilder builder,
		List<CandidateRuleFact> facts, List<Node> nodes, List<CompiledInputEdgeFact> edges,
		List<RelocationAction> actions, Map<CompiledHopKey,Hop> origins,
		Map<Hop,NodeShapeFact> shapes) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"bindRelocationCandidateRealizationsMeasured", List.class, List.class,
			List.class, List.class, Map.class, Map.class);
		method.setAccessible(true);
		return (List<CandidateRuleFact>)method.invoke(
			PlacementBuilderTestAccess.relationClosure(builder),
			facts, nodes, edges, actions, origins, shapes);
	}

	private static final class Fixture {
		private final CompiledHopKey source = key("source");
		private final CompiledHopKey producer = key("producer");
		private final CompiledHopKey consumer = key("consumer");
		private final ValueVersionKey sourceVersion = version("source", 0);
		private final ValueVersionKey producerVersion = version("producer", 1);
		private final ValueVersionKey consumerVersion = version("consumer", 2);
		private final DataOp sourceHop = new DataOp("source", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "source", 4, 2, 8, 1000);
		private final UnaryOp producerHop = new UnaryOp(
			"producer", DataType.MATRIX, ValueType.FP64, OpOp1.LOG, sourceHop);
		private final UnaryOp consumerHop = new UnaryOp(
			"consumer", DataType.MATRIX, ValueType.FP64, OpOp1.LOG, producerHop);
		private final List<Node> nodes;
		private final List<CandidateRuleFact> facts;
		private final List<CompiledInputEdgeFact> edges;
		private final List<RelocationAction> actions;
		private final Map<CompiledHopKey,Hop> origins;
		private final Map<Hop,NodeShapeFact> shapes;

		private Fixture(boolean cyclic) {
			CandidateRuleKey sourceRule = new CandidateRuleKey(source, List.of());
			CandidateRuleKey producerRule = new CandidateRuleKey(
				producer, List.of(CandidateInputState.present(FType.FULL)));
			CandidateRuleKey consumerRule = new CandidateRuleKey(
				consumer, List.of(CandidateInputState.present(FType.FULL)));
			nodes = List.of(node(source, sourceVersion), node(producer, producerVersion),
				node(consumer, consumerVersion));
			CandidateEmissionRealization seed = CandidateEmissionRealization.durable(
				EMISSION, SOURCE_POOL, List.of(), List.of());
			facts = List.of(fact(sourceRule, List.of(seed)),
				fact(producerRule, cyclic ? List.of(seed) : List.of()),
				fact(consumerRule, List.of()));
			edges = cyclic
				? List.of(new CompiledInputEdgeFact(consumer, producer, 0),
					new CompiledInputEdgeFact(producer, consumer, 0))
				: List.of(new CompiledInputEdgeFact(source, producer, 0),
					new CompiledInputEdgeFact(producer, consumer, 0));
			actions = cyclic
				? List.of(action(consumerVersion, producer, PRODUCER_POOL),
					action(producerVersion, consumer, CONSUMER_POOL))
				: List.of(action(sourceVersion, producer, PRODUCER_POOL),
					action(producerVersion, consumer, CONSUMER_POOL));
			Map<CompiledHopKey,Hop> exactOrigins = new IdentityHashMap<>();
			exactOrigins.put(source, sourceHop);
			exactOrigins.put(producer, producerHop);
			exactOrigins.put(consumer, consumerHop);
			origins = exactOrigins;
			Map<Hop,NodeShapeFact> exactShapes = new IdentityHashMap<>();
			exactShapes.put(sourceHop, new NodeShapeFact(DataType.MATRIX, 4, 2));
			exactShapes.put(producerHop, new NodeShapeFact(DataType.MATRIX, 4, 2));
			exactShapes.put(consumerHop, new NodeShapeFact(DataType.MATRIX, 4, 2));
			shapes = exactShapes;
		}

		private static Fixture acyclic() { return new Fixture(false); }
		private static Fixture cyclic() { return new Fixture(true); }
	}

	private static CandidateRuleFact fact(CandidateRuleKey key,
		List<CandidateEmissionRealization> realizations) {
		CandidateEmissionFact emission = realizations.isEmpty()
			? new CandidateEmissionFact(EMISSION, FType.FULL)
			: new CandidateEmissionFact(EMISSION, FType.FULL, null, realizations);
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.FULL, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.FULL), ""), List.of(emission), "");
	}

	private static Node node(CompiledHopKey key, ValueVersionKey value) {
		return new Node(key, NodeKind.OPERATION, value, true,
			List.of(STATE), List.of(), List.of());
	}

	private static RelocationAction action(ValueVersionKey source, CompiledHopKey consumer,
		DurableAnchorKey targetPool) {
		RelocationActionKey key = new RelocationActionKey(source, STATE, FType.FULL,
			targetPool, REGION.normalizedSignature(), List.of(consumer));
		ObligationKey obligation = new ObligationKey(
			consumer, 0, source, STATE, key, REGION.normalizedSignature());
		return new RelocationAction(key, List.of(obligation));
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey(FINGERPRINT, "main", "root", "compiled", REGION, id, id);
	}

	private static ValueVersionKey version(String id, int ordinal) {
		return new ValueVersionKey(FINGERPRINT, id, REGION, ordinal,
			VersionKind.ORDINARY, List.of());
	}
}
