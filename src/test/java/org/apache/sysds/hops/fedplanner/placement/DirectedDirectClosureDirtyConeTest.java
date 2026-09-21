/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class DirectedDirectClosureDirtyConeTest {
	private static final String FINGERPRINT = "direct-dirty-cone";
	private static final ControlRegionKey REGION = new ControlRegionKey(FINGERPRINT, "main",
		List.of("main"), "main", "compiled");
	private static final PlacementState LOCAL =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);

	@Test
	public void diamondVisitsOnlyChangedRowAndItsConsumers() throws Exception {
		Node a = node("A"), b = node("B"), c = node("C"), d = node("D");
		List<Node> nodes = List.of(a, b, c, d);
		List<CompiledInputEdgeFact> edges = List.of(edge(a, b), edge(a, c), edge(b, d), edge(c, d));
		assertSameKeys(keys(b, d), affected(keys(b), nodes, edges, Map.of()));
		assertSameKeys(keys(a, b, c, d), affected(keys(a), nodes, edges, Map.of()));
	}

	@Test
	public void cycleAndFunctionReachingFollowProducerToConsumer() throws Exception {
		Node upstream = node("upstream"), argument = node("argument"), body = node("body");
		Node result = node("result"), downstream = node("downstream"), unrelated = node("unrelated");
		List<Node> nodes = List.of(upstream, argument, body, result, downstream, unrelated);
		List<CompiledInputEdgeFact> edges = List.of(edge(argument, body), edge(body, result),
			edge(result, body), edge(result, downstream));
		Map<CompiledHopKey,List<CompiledHopKey>> reaching =
			Map.of(argument.key(), List.of(upstream.key()));
		assertSameKeys(keys(body, result, downstream), affected(keys(body), nodes, edges, reaching));
		assertSameKeys(keys(upstream, argument, body, result, downstream),
			affected(keys(upstream), nodes, edges, reaching));
		assertSameKeys(keys(unrelated), affected(keys(unrelated), nodes, edges, reaching));
	}

	@Test
	public void oneValueVersionExpandsAliasesWithoutReversingDependencies() throws Exception {
		Node producer = node("producer"), alias = node("alias", producer.valueVersion());
		Node consumer = node("consumer"), upstream = node("upstream");
		List<Node> nodes = List.of(producer, alias, consumer, upstream);
		List<CompiledInputEdgeFact> edges = List.of(edge(upstream, producer), edge(alias, consumer));
		assertSameKeys(keys(producer, alias, consumer),
			affected(keys(producer), nodes, edges, Map.of()));
	}

	@Test
	public void removedOrAddedSupportStillInvalidatesItsConsumer() throws Exception {
		Node source = node("support-source"), owner = node("support-owner");
		Node other = node("unrelated");
		List<Node> nodes = List.of(source, owner, other);
		List<CandidateRuleFact> bound = List.of(supportFact(source, owner));
		assertSameKeys(keys(source, owner), affected(keys(source), nodes, List.of(), Map.of(),
			bound, List.of()));
		assertSameKeys(keys(source, owner), affected(keys(source), nodes, List.of(), Map.of(),
			List.of(), bound));
	}

	private static CandidateRuleFact supportFact(Node source, Node owner) {
		PlacementEmissionState emission = new PlacementEmissionState(LOCAL, false);
		CandidateRuleKey sourceRule = new CandidateRuleKey(source.key(), List.of());
		CandidateRealizationReference sourceRef = CandidateRealizationReference.of(sourceRule,
			CandidateEmissionRealization.local(emission));
		CandidateEmissionRealization realization = CandidateEmissionRealization.local(emission,
			List.of(), List.of(CandidateRealizationInputBinding.direct(0, sourceRef)));
		CandidateEmissionFact output = new CandidateEmissionFact(emission, null, null,
			List.of(realization));
		return new CandidateRuleFact(new CandidateRuleKey(owner.key(), List.of()),
			CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.BINARY_EWISE, "fixture", ExecType.CP,
				FederatedOutput.LOUT, null, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""), List.of(output), "");
	}

	private static Node node(String name) {
		return node(name, new ValueVersionKey(FINGERPRINT, name, REGION, 0,
			VersionKind.ORDINARY, List.of()));
	}

	private static Node node(String name, ValueVersionKey version) {
		CompiledHopKey key = new CompiledHopKey(FINGERPRINT, "main", "main", "compiled",
			REGION, name, name);
		return new Node(key, NodeKind.OPERATION, version, true,
			List.of(LOCAL), List.of(), List.of());
	}

	private static CompiledInputEdgeFact edge(Node source, Node consumer) {
		return new CompiledInputEdgeFact(source.key(), consumer.key(), 0);
	}

	private static Set<CompiledHopKey> keys(Node... nodes) {
		Set<CompiledHopKey> keys = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Node node : nodes)
			keys.add(node.key());
		return keys;
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> affected(Set<CompiledHopKey> changed, List<Node> nodes,
		List<CompiledInputEdgeFact> edges, Map<CompiledHopKey,List<CompiledHopKey>> reaching)
		throws Exception {
		return affected(changed, nodes, edges, reaching, List.of(), List.of());
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> affected(Set<CompiledHopKey> changed, List<Node> nodes,
		List<CompiledInputEdgeFact> edges, Map<CompiledHopKey,List<CompiledHopKey>> reaching,
		List<CandidateRuleFact> before, List<CandidateRuleFact> after) throws Exception {
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"affectedDirectClosureOccurrences", Set.class, List.class, List.class, Map.class,
			List.class, List.class);
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(null, changed, nodes, edges, reaching,
			before, after);
	}

	private static void assertSameKeys(Set<CompiledHopKey> expected, Set<CompiledHopKey> actual) {
		Assert.assertEquals(expected, actual);
	}
}
