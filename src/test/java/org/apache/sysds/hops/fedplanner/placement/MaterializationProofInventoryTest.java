/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact immutable proof indexes may outlive an owner query, never its mutable query state. */
public class MaterializationProofInventoryTest {
	private static final PlacementState FULL = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
	private static final PlacementState LOCAL = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.FULL, false);

	@Test
	public void sharedInventoryMatchesColdOwnersInBothOrdersWithoutQueryHistory() throws Exception {
		Fixture f = new Fixture();
		for(List<Integer> order : List.of(List.of(1, 2), List.of(2, 1))) {
			Object inventory = f.inventory(f.nodes, f.edges);
			Object previousResolver = null, previousNative = null, context = null;
			for(int index : order) {
				Object expected = f.cold(f.nodes.get(index), f.facts.get(index), f.nodes, f.edges);
				Object actual = shared(f.nodes.get(index), f.facts.get(index), inventory);
				Assert.assertEquals("complete nodes/facts/action/source order must equal a cold query", expected, actual);
				CandidateEmissionFact derived = outputFacts(actual).stream()
					.flatMap(fact -> fact.allowedEmissionFacts().stream())
					.filter(emission -> emission.emissionState().derivedFedFout()).findFirst().orElseThrow();
				Assert.assertSame(f.nodes.get(index).key(), derived.derivedFoutAction().producer());
				Assert.assertSame(f.nodes.get(0).key(), derived.derivedFoutAction().durableAnchorOwner());
				Object resolver = field(inventory, "resolver");
				Object nativeQuery = field(resolver, "nativeContinuity");
				Assert.assertTrue(((Map<?,?>)field(resolver, "active")).isEmpty());
				Assert.assertFalse(((Map<?,?>)field(resolver, "memo")).isEmpty());
				if(previousResolver != null) {
					Assert.assertSame(previousResolver, resolver);
					Assert.assertNotSame(previousNative, nativeQuery);
					Assert.assertSame(context, field(nativeQuery, "structuralContext"));
				}
				previousResolver = resolver;
				previousNative = nativeQuery;
				context = field(nativeQuery, "structuralContext");
			}
		}
	}

	@Test
	public void lazyInventoryRetainsImmutableConstructorTimeNodesAndFacts() throws Exception {
		Fixture f = new Fixture();
		Node raw = f.nodes.get(1);
		CandidateRuleFact fact = f.facts.get(1);
		List<Node> expectedNodes = List.copyOf(f.nodes);
		List<CandidateRuleFact> expectedFacts = List.copyOf(f.facts);
		Object expected = f.cold(raw, fact, f.nodes, f.edges);
		Object inventory = f.inventory(f.nodes, f.edges);
		Assert.assertNull(field(inventory, "resolver"));
		f.nodes.clear();
		f.facts.clear();
		Assert.assertEquals(expectedNodes, field(inventory, "nodes"));
		Assert.assertEquals(expectedFacts, field(inventory, "facts"));
		Assert.assertEquals(expected, shared(raw, fact, inventory));
		@SuppressWarnings("unchecked")
		List<Node> retainedNodes = (List<Node>)field(inventory, "nodes");
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> retainedFacts = (List<CandidateRuleFact>)field(inventory, "facts");
		for(int index = 0; index < expectedNodes.size(); index++) {
			Assert.assertSame(expectedNodes.get(index), retainedNodes.get(index));
			Assert.assertSame(expectedFacts.get(index), retainedFacts.get(index));
		}
		Assert.assertThrows(UnsupportedOperationException.class, () -> retainedNodes.add(raw));
		Assert.assertThrows(UnsupportedOperationException.class, () -> retainedFacts.add(fact));
	}

	@Test
	public void firstEligibleOwnerRetainsDuplicateEdgeValidationBoundary() throws Exception {
		Fixture f = new Fixture();
		f.add("scalar", new LiteralOp(1.0), new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false),
			List.of(), List.of(), new NodeShapeFact(DataType.SCALAR, 0, 0));
		List<CompiledInputEdgeFact> duplicates = new ArrayList<>(f.edges);
		duplicates.add(f.edges.get(0));
		Object inventory = f.inventory(f.nodes, duplicates);
		Node source = f.nodes.get(0);
		Node transientSource = new Node(source.key(), NodeKind.TRANSIENT_READ, source.valueVersion(),
			true, source.legalAlternatives(), List.of(), source.anchors());
		shared(transientSource, f.facts.get(0), inventory);
		shared(f.nodes.get(3), f.facts.get(3), inventory);
		CandidateRuleFact available = f.facts.get(1);
		CandidateRuleFact excluded = new CandidateRuleFact(available.key(),
			CandidateEvaluationStatus.PRIVACY_EXCLUDED, available.capability(), available.shapeProof(),
			available.profile(), List.of(), "PRIVATE_AGGREGATE");
		shared(f.nodes.get(1), excluded, inventory);
		Assert.assertNull("ineligible owners must not eagerly construct/validate the proof graph",
			field(inventory, "resolver"));
		InvocationTargetException error = Assert.assertThrows(InvocationTargetException.class,
			() -> shared(f.nodes.get(2), f.facts.get(2), inventory));
		Assert.assertTrue(error.getCause() instanceof IllegalStateException);
		Assert.assertTrue(error.getCause().getMessage().contains("Duplicate compiled matrix edge"));
		Assert.assertNull("a partially constructed resolver must not be published", field(inventory, "resolver"));
		Object separateValidInventory = f.inventory(f.nodes, f.edges);
		Assert.assertEquals(f.cold(f.nodes.get(2), f.facts.get(2), f.nodes, f.edges),
			shared(f.nodes.get(2), f.facts.get(2), separateValidInventory));
	}

	@Test
	public void outputOnlyAnchorCannotGroundItsOwnCfgInput() throws Exception {
		Fixture f = new Fixture();
		Node source = f.nodes.get(0), owner = f.nodes.get(1);
		ValueVersionKey circularValue = new ValueVersionKey(source.valueVersion().programFingerprint(),
			source.valueVersion().lexicalVariable(), source.valueVersion().definingControlRegion(),
			source.valueVersion().definitionOrdinal(), source.valueVersion().versionKind(),
			List.of("cfg-definition:" + owner.valueVersion().cfgReferenceSignature()));
		Node ungroundedRead = new Node(source.key(), NodeKind.TRANSIENT_READ, circularValue,
			true, source.legalAlternatives(), List.of(), List.of());
		List<Node> committed = List.of(ungroundedRead, owner, f.nodes.get(2));
		Node rawAnchoredOutput = new Node(owner.key(), owner.kind(), owner.valueVersion(),
			true, owner.legalAlternatives(), List.of(), List.of(f.anchor));
		List<Node> improperlyInjected = List.of(ungroundedRead, rawAnchoredOutput, f.nodes.get(2));
		Assert.assertTrue("fixture must detect raw output injection into the proof inventory",
			outputFacts(f.cold(rawAnchoredOutput, f.facts.get(1), improperlyInjected, f.edges)).stream()
				.flatMap(fact -> fact.allowedEmissionFacts().stream())
				.anyMatch(emission -> emission.emissionState().derivedFedFout()));
		Object actual = shared(rawAnchoredOutput, f.facts.get(1), f.inventory(committed, f.edges));
		Assert.assertEquals(f.cold(rawAnchoredOutput, f.facts.get(1), committed, f.edges), actual);
		Assert.assertFalse("raw output metadata is not committed proof authority", outputFacts(actual).stream()
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.anyMatch(emission -> emission.emissionState().derivedFedFout()));
	}

	private static final class Fixture {
		private final String fingerprint = "materialization-proof-inventory";
		private final ControlRegionKey region = new ControlRegionKey(fingerprint, "main",
			List.of("body"), "main", "recompile");
		private final DurableAnchorKey anchor = new DurableAnchorKey("source-pool", FType.FULL,
			List.of(new AnchorPartition("worker-a", List.of(0L, 0L), List.of(4L, 2L))));
		private final List<Node> nodes = new ArrayList<>();
		private final List<CandidateRuleFact> facts = new ArrayList<>();
		private final List<CompiledInputEdgeFact> edges = new ArrayList<>();
		private final Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		private final Map<Hop,NodeShapeFact> shapes = new IdentityHashMap<>();

		private Fixture() {
			DataOp source = new DataOp("source", DataType.MATRIX, ValueType.FP64,
				OpOpData.FEDERATED, "source", 4, 2, 8, 1000);
			FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(source, Privacy.PRIVATE_AGGREGATE);
			add("source", source, FULL, List.of(anchor), List.of(), new NodeShapeFact(DataType.MATRIX, 4, 2));
			for(String name : List.of("first", "second")) {
				Hop owner = new AggUnaryOp(name, DataType.MATRIX, ValueType.FP64, AggOp.SUM, Direction.Col, source);
				add(name, owner, LOCAL, List.of(), List.of(CandidateInputState.present(FType.FULL)),
					new NodeShapeFact(DataType.MATRIX, 1, 2));
				edges.add(new CompiledInputEdgeFact(nodes.get(0).key(), nodes.get(nodes.size() - 1).key(), 0));
			}
		}

		private void add(String name, Hop hop, PlacementState state, List<DurableAnchorKey> anchors,
			List<CandidateInputState> inputs, NodeShapeFact shape) {
			CompiledHopKey key = new CompiledHopKey(fingerprint, "main", "root", "recompile", region, name, name);
			ValueVersionKey version = new ValueVersionKey(fingerprint, name, region, nodes.size(),
				VersionKind.ORDINARY, List.of());
			nodes.add(new Node(key, NodeKind.OPERATION, version, true, List.of(state), List.of(), anchors));
			facts.add(new CandidateRuleFact(new CandidateRuleKey(key, inputs), CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.OTHER, "fixture", state.execType(), state.output(),
					state.fType(), ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(FType.FULL), ""),
				List.of(new CandidateEmissionFact(new PlacementEmissionState(state, false),
					state.execType() == ExecType.FED ? FType.FULL : null)), ""));
			origins.put(key, hop);
			shapes.put(hop, shape);
		}

		private Object inventory(List<Node> proofNodes, List<CompiledInputEdgeFact> proofEdges) throws Exception {
			Constructor<?> constructor = inventoryType().getDeclaredConstructor(List.class, List.class,
				List.class, List.class, Collection.class, Map.class, Map.class);
			constructor.setAccessible(true);
			return constructor.newInstance(proofNodes, facts, proofEdges, List.of(), List.of(), origins, shapes);
		}

		private Object cold(Node raw, CandidateRuleFact fact, List<Node> proofNodes,
			List<CompiledInputEdgeFact> proofEdges) throws Exception {
			Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
				"closeDerivedWorkerPoolMaterializationCandidates", List.class, List.class, List.class,
				List.class, List.class, List.class, Collection.class, Map.class, Map.class);
			method.setAccessible(true);
			return method.invoke(null, List.of(raw), List.of(fact), proofNodes, facts,
				proofEdges, List.of(), List.of(), origins, shapes);
		}
	}

	private static Object shared(Node raw, CandidateRuleFact fact, Object inventory) throws Exception {
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"closeDerivedWorkerPoolMaterializationCandidates", List.class, List.class, inventoryType());
		method.setAccessible(true);
		return method.invoke(null, List.of(raw), List.of(fact), inventory);
	}

	private static Class<?> inventoryType() throws ClassNotFoundException {
		return Class.forName(NeutralPlacementGraphBuilder.class.getName() + "$MaterializationProofInventory");
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> outputFacts(Object result) throws Exception {
		Method method = result.getClass().getDeclaredMethod("candidateRuleFacts");
		method.setAccessible(true);
		return (List<CandidateRuleFact>)method.invoke(result);
	}
}
