/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeRelocationAuthorityValidationTest {
	private static final String FINGERPRINT = "native-relocation-authority";
	private static final ControlRegionKey REGION = new ControlRegionKey(
		FINGERPRINT, "main", List.of("root"), "root", "compiled");
	private static final PlacementState STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(STATE, false);

	@Test
	public void actualNoActionBindKeepsOrdinaryRelationLazy() throws Exception {
		assertNoActionBindStaysLazy(RelationKind.ORDINARY);
	}

	@Test
	public void actualNoActionBindKeepsConditionalRelationLazy() throws Exception {
		assertNoActionBindStaysLazy(RelationKind.CONDITIONAL);
	}

	@Test
	public void actualNoActionBindKeepsMultiHeaderRelationLazy() throws Exception {
		assertNoActionBindStaysLazy(RelationKind.MULTI_HEADER);
	}

	private static void assertNoActionBindStaysLazy(RelationKind kind) throws Exception {
		Fixture fixture = fixture(kind);
		List<CandidateRuleFact> rebound = bind(fixture);
		CandidateEmissionRealization selected = rebound.get(rebound.size() - 1)
			.allowedEmissionFacts().get(0).realizations().get(0);
		Assert.assertSame(kind.name(), fixture.relation, selected.supportClauses());
		Assert.assertEquals(kind.name(), 0, fixture.relation.materializedHandleCount());
		Assert.assertEquals(fixture.facts, rebound);
	}

	@Test
	public void structurallyEqualForeignSourceOwnerStillUsesExactFallback() throws Exception {
		CompiledHopKey owner = key("foreign-consumer");
		CompiledHopKey source = key("foreign-source");
		CompiledHopKey foreignSource = key("foreign-source");
		Assert.assertEquals(source, foreignSource);
		Assert.assertNotSame(source, foreignSource);
		DurableAnchorKey output = pool("foreign-output");
		NativeContinuitySupportClauses left = relation(owner, pool("foreign-seed"), output,
			List.of(axis(0, source, "foreign", 2)));
		NativeContinuitySupportClauses right = relation(owner, pool("foreign-seed"), output,
			List.of(axis(0, foreignSource, "foreign", 2)));
		CandidateEmissionRealization current = realization("foreign-output", left);
		CandidateEmissionRealization rebound = realization("foreign-output", right);

		Assert.assertEquals(current, rebound);
		Assert.assertFalse(sameAuthority(current, rebound, Set.of()));
		Assert.assertTrue(left.materializedHandleCount() > 0);
		Assert.assertTrue(right.materializedHandleCount() > 0);
	}

	@Test
	public void staleRelocationBindingStillRequiresCurrentActionIdentity() throws Exception {
		CompiledHopKey owner = key("stale-owner");
		RelocationActionKey action = new RelocationActionKey(version("stale", 1), STATE,
			FType.ROW, pool("stale-pool"), REGION.normalizedSignature(), List.of(owner));
		CandidateRuleKey sourceRule = new CandidateRuleKey(owner, List.of());
		CandidateEmissionRealization source = CandidateEmissionRealization.durable(
			EMISSION, pool("stale-source"), List.of(), List.of());
		CandidateRealizationInputBinding binding = CandidateRealizationInputBinding.relocation(
			0, CandidateRealizationReference.of(sourceRule, source), action);
		CandidateEmissionRealization realization = new CandidateEmissionRealization(
			PlacementRealizationKey.durable(EMISSION, pool("stale-output")),
			List.of(new CandidateRealizationSupportClause(List.of(), List.of(binding))));

		Assert.assertTrue(sameAuthority(realization, realization, Set.of(action)));
		Assert.assertFalse(sameAuthority(realization, realization, Set.of()));
	}

	private static Fixture fixture(RelationKind kind) {
		CompiledHopKey sourceOwner = key(kind + "-source");
		CompiledHopKey consumerOwner = key(kind + "-consumer");
		DurableAnchorKey output = pool(kind + "-output");
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			axis(0, sourceOwner, kind.name().toLowerCase(), 4));
		NativeContinuitySupportClauses first = relation(
			consumerOwner, pool(kind + "-seed-a"), output, axes);
		NativeContinuitySupportClauses relation = switch(kind) {
			case ORDINARY -> first;
			case CONDITIONAL -> NativeContinuitySupportClauses.exactComplement(consumerOwner,
				first.product(), List.of(axes.get(0).subList(0, 2)), null, true);
			case MULTI_HEADER -> first.multiHeaderUnion(relation(
				consumerOwner, pool(kind + "-seed-b"), output, axes)).orElseThrow();
		};
		CandidateEmissionRealization source = CandidateEmissionRealization.durable(
			EMISSION, pool(kind + "-source-pool"), List.of(), List.of());
		CandidateRuleKey sourceRule = axes.get(0).get(0).source().rule();
		CandidateRuleKey consumerRule = new CandidateRuleKey(consumerOwner,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateEmissionRealization consumer = new CandidateEmissionRealization(
			PlacementRealizationKey.durable(EMISSION, output), relation);
		List<CandidateRuleFact> facts = List.of(fact(sourceRule, source), fact(consumerRule, consumer));
		List<Node> nodes = List.of(node(sourceOwner, version(kind + "-source", 0)),
			node(consumerOwner, version(kind + "-consumer", 1)));
		List<CompiledInputEdgeFact> edges = List.of(
			new CompiledInputEdgeFact(sourceOwner, consumerOwner, 0));
		Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		Map<Hop,NodeShapeFact> shapes = new IdentityHashMap<>();
		for(Node node : nodes) {
			DataOp hop = new DataOp(node.key().canonicalSourceOrigin(), DataType.MATRIX,
				ValueType.FP64, OpOpData.TRANSIENTREAD, node.key().canonicalSourceOrigin(), 8, 2, 8, 1000);
			origins.put(node.key(), hop);
			shapes.put(hop, new NodeShapeFact(DataType.MATRIX, 8, 2));
		}
		return new Fixture(relation, facts, nodes, edges, origins, shapes);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> bind(Fixture fixture) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"bindRelocationCandidateRealizationsMeasured", List.class, List.class,
			List.class, List.class, Map.class, Map.class);
		method.setAccessible(true);
		return (List<CandidateRuleFact>)method.invoke(
			PlacementBuilderTestAccess.relationClosure(new NeutralPlacementGraphBuilder()),
			fixture.facts, fixture.nodes, fixture.edges, List.of(), fixture.origins, fixture.shapes);
	}

	private static boolean sameAuthority(CandidateEmissionRealization current,
		CandidateEmissionRealization rebound, Set<RelocationActionKey> actions) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"sameRelocationBoundEmissionAuthority", List.class, List.class, Set.class);
		method.setAccessible(true);
		return (boolean)method.invoke(null,
			List.of(new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(current))),
			List.of(new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(rebound))), actions);
	}

	private static CandidateEmissionRealization realization(String id,
		NativeContinuitySupportClauses relation) {
		return new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(EMISSION, id), relation);
	}

	private static NativeContinuitySupportClauses relation(CompiledHopKey owner,
		DurableAnchorKey seed, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes) {
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true, axes);
		Assert.assertNotNull(product);
		return new NativeContinuitySupportClauses(owner, product, null, true);
	}

	private static List<CandidateRealizationInputBinding> axis(int position,
		CompiledHopKey owner, String id, int width) {
		List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		for(int option = 0; option < width; option++)
			bindings.add(CandidateRealizationInputBinding.direct(position,
				new CandidateRealizationReference(rule,
					PlacementRealizationKey.nativeLineage(EMISSION, id + '-' + option))));
		bindings.sort(PlacementAnalysis.canonicalComparator());
		return List.copyOf(bindings);
	}

	private static CandidateRuleFact fact(CandidateRuleKey key,
		CandidateEmissionRealization realization) {
		CandidateEmissionFact emission = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, List.of(realization));
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), List.of(emission), "");
	}

	private static Node node(CompiledHopKey key, ValueVersionKey value) {
		return new Node(key, NodeKind.OPERATION, value, true,
			List.of(STATE), List.of(), List.of());
	}

	private static DurableAnchorKey pool(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:19121", List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey(FINGERPRINT, "main", "root", "compiled", REGION, id, id);
	}

	private static ValueVersionKey version(String id, int ordinal) {
		return new ValueVersionKey(FINGERPRINT, id, REGION, ordinal,
			VersionKind.ORDINARY, List.of());
	}

	private enum RelationKind { ORDINARY, CONDITIONAL, MULTI_HEADER }

	private record Fixture(NativeContinuitySupportClauses relation,
		List<CandidateRuleFact> facts, List<Node> nodes, List<CompiledInputEdgeFact> edges,
		Map<CompiledHopKey,Hop> origins, Map<Hop,NodeShapeFact> shapes) { }
}
