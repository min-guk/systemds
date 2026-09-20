/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.CandidateClosureDependencies.ChangeKind;
import org.apache.sysds.hops.fedplanner.placement.CandidateClosureDependencies.IndexStats;
import org.apache.sysds.hops.fedplanner.placement.CandidateClosureDependencies.Revision;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.junit.Assert;
import org.junit.Test;

public class CandidateClosureDependenciesTest {
	private final CompiledHopKey root = key("root");
	private final CompiledHopKey left = key("left");
	private final CompiledHopKey right = key("right");
	private final CompiledHopKey leaf = key("leaf");
	private final Map<CompiledHopKey,List<CompiledHopKey>> diamond = Map.of(
		root, List.of(left, right), left, List.of(leaf), right, List.of(leaf));

	@Test
	public void zeroOneSparseAndAllChangedOwnersUseExactDirectedCones() {
		Revision zero = schedule(Map.of(root, ChangeKind.NONE));
		Assert.assertTrue(zero.directOwners().isEmpty());

		Revision one = schedule(Map.of(left, ChangeKind.POSITIVE_RELATION));
		Assert.assertEquals(2, one.directOwners().size());
		Assert.assertTrue(one.directOwners().containsAll(List.of(left, leaf)));
		Assert.assertFalse("unrelated sibling must not be scheduled", one.directOwners().contains(right));

		Revision sparse = schedule(Map.of(left, ChangeKind.POSITIVE_RELATION,
			right, ChangeKind.POSITIVE_RELATION));
		Assert.assertEquals(3, sparse.directOwners().size());
		Assert.assertFalse(sparse.directOwners().contains(root));

		Revision all = schedule(Map.of(root, ChangeKind.POSITIVE_RELATION,
			left, ChangeKind.POSITIVE_RELATION, right, ChangeKind.POSITIVE_RELATION,
			leaf, ChangeKind.POSITIVE_RELATION));
		Assert.assertEquals(4, all.directOwners().size());
	}

	@Test
	public void replacementsRemovalAuthorityAndTopologyChangesUseConservativeComponent() {
		for(ChangeKind fallback : List.of(ChangeKind.EXACT_REPLACEMENT, ChangeKind.DOMAIN_CHANGE,
			ChangeKind.AUTHORITY_CHANGE, ChangeKind.TOPOLOGY_CHANGE)) {
			Revision revision = schedule(Map.of(left, fallback));
			Assert.assertFalse(fallback.name(), revision.directed());
			Assert.assertEquals(fallback.name(), 4, revision.directOwners().size());
			Assert.assertEquals(revision.invalidationOwners(), revision.directOwners());
		}
	}

	@Test
	public void sameFTypeSupportReplacementAndCyclesFailClosed() {
		Revision supportReplacement = schedule(Map.of(left, ChangeKind.EXACT_REPLACEMENT));
		Assert.assertFalse("same shallow FType is insufficient for exact support preservation",
			supportReplacement.directed());

		Map<CompiledHopKey,List<CompiledHopKey>> cyclic = Map.of(root, List.of(left), left, List.of(root));
		Revision cycle = CandidateClosureDependencies.scheduleForTesting(
			Map.of(root, ChangeKind.POSITIVE_RELATION), cyclic);
		Assert.assertFalse(cycle.directed());
		Assert.assertEquals(2, cycle.directOwners().size());
	}

	@Test
	public void exactSupportValueRatherThanFTypeCountControlsClassification() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(
				NeutralPlacementFixedPointCompositionTest.ACTIONS));
		CandidateRuleFact oldFact = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> !fact.allowedEmissionFacts().isEmpty()).findFirst().orElseThrow();
		CandidateEmissionFact oldEmission = oldFact.allowedEmissionFacts().get(0);
		CandidateEmissionRealization oldRealization = oldEmission.realizations().get(0);
		PlacementProofKey replacementProof = new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
			oldFact.key().parentOccurrence(), "same-ftype-different-exact-support");
		CandidateEmissionRealization replacement = new CandidateEmissionRealization(
			oldRealization.key(), List.of(replacementProof), List.of());
		CandidateEmissionFact changedEmission = new CandidateEmissionFact(oldEmission.emissionState(),
			oldEmission.executionFType(), oldEmission.derivedFoutAction(), List.of(replacement));
		List<CandidateEmissionFact> emissions = new java.util.ArrayList<>(oldFact.allowedEmissionFacts());
		emissions.set(0, changedEmission);
		CandidateRuleFact changed = new CandidateRuleFact(oldFact.key(), oldFact.status(),
			oldFact.capability(), oldFact.shapeProof(), oldFact.profile(), emissions, oldFact.failureCode());

		Assert.assertEquals(ChangeKind.EXACT_REPLACEMENT,
			CandidateClosureDependencies.classifyForTesting(List.of(oldFact), List.of(changed)));
		Assert.assertEquals(ChangeKind.DOMAIN_CHANGE,
			CandidateClosureDependencies.classifyForTesting(List.of(oldFact), List.of()));
	}

	@Test
	public void realFactsClassifyPositiveAuthorityAndSupportTopologyChanges() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(
				NeutralPlacementFixedPointCompositionTest.ACTIONS));
		List<CandidateRuleFact> facts = analysis.candidateRuleFacts().orderedFacts();
		CandidateRuleFact owner = facts.stream().filter(fact -> !fact.allowedEmissionFacts().isEmpty())
			.findFirst().orElseThrow();
		CandidateRuleFact source = facts.stream()
			.filter(fact -> fact != owner && !fact.allowedEmissionFacts().isEmpty())
			.findFirst().orElseThrow();
		CandidateEmissionFact emission = owner.allowedEmissionFacts().get(0);
		CandidateEmissionRealization realization = emission.realizations().get(0);
		PlacementProofKey addedProof = new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
			owner.key().parentOccurrence(), "positive-exact-support-addition");
		CandidateEmissionRealization addedClause = new CandidateEmissionRealization(realization.key(),
			List.of(addedProof), List.of());
		CandidateRuleFact positive = withEmission(owner, emission,
			new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
				emission.derivedFoutAction(), List.of(realization, addedClause)));
		Assert.assertEquals(ChangeKind.POSITIVE_RELATION,
			CandidateClosureDependencies.classifyForTesting(List.of(owner), List.of(positive)));
		Revision positiveRevision = CandidateClosureDependencies.revision(List.of(owner),
			List.of(positive), List.of(), List.of(), Map.of(), List.of());
		Assert.assertTrue("real positive revision must exercise directed eligibility",
			positiveRevision.directed());

		var capability = owner.capability();
		var changedCapability = new PlacementAnalysis.CandidateCapabilityFact(capability.category(),
			capability.opcode(), capability.nativeExec(), capability.nativeOutput(), capability.nativeFoutFType(),
			capability.reasonCode(), capability.detail() + "-changed", capability.notes());
		CandidateRuleFact authority = new CandidateRuleFact(owner.key(), owner.status(), changedCapability,
			owner.shapeProof(), owner.profile(), owner.allowedEmissionFacts(), owner.failureCode());
		Assert.assertEquals(ChangeKind.AUTHORITY_CHANGE,
			CandidateClosureDependencies.classifyForTesting(List.of(owner), List.of(authority)));

		CandidateEmissionRealization sourceRealization = source.allowedEmissionFacts().get(0).realizations().get(0);
		CandidateRealizationReference reference = CandidateRealizationReference.of(source.key(), sourceRealization);
		CandidateRealizationSupportClause topologyClause = new CandidateRealizationSupportClause(
			List.of(addedProof), List.of(CandidateRealizationInputBinding.direct(0, reference)));
		CandidateEmissionRealization topologyRealization = new CandidateEmissionRealization(
			realization.key(), List.of(topologyClause));
		CandidateRuleFact topology = withEmission(owner, emission,
			new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
				emission.derivedFoutAction(), List.of(realization, topologyRealization)));
		Assert.assertEquals(ChangeKind.TOPOLOGY_CHANGE,
			CandidateClosureDependencies.classifyForTesting(List.of(owner), List.of(topology)));
		Revision topologyRevision = CandidateClosureDependencies.revision(List.of(source, owner),
			List.of(source, topology), List.of(), List.of(), Map.of(), List.of());
		Assert.assertFalse(topologyRevision.directed());
		Assert.assertTrue("old+new support edges must invalidate the support source",
			topologyRevision.invalidationOwners().containsAll(
				List.of(source.key().parentOccurrence(), owner.key().parentOccurrence())));
		Revision removedTopology = CandidateClosureDependencies.revision(List.of(source, topology),
			List.of(source, owner), List.of(), List.of(), Map.of(), List.of());
		Assert.assertFalse(removedTopology.directed());
		Assert.assertTrue("removed old support edges remain in the revision invalidation graph",
			removedTopology.invalidationOwners().contains(source.key().parentOccurrence()));
	}

	@Test
	public void aliasesAndFunctionBoundariesPropagateAndFailClosed() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(
				NeutralPlacementFixedPointCompositionTest.ACTIONS));
		CandidateRuleFact owner = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> !fact.allowedEmissionFacts().isEmpty()).findFirst().orElseThrow();
		CandidateEmissionFact emission = owner.allowedEmissionFacts().get(0);
		CandidateEmissionRealization realization = emission.realizations().get(0);
		PlacementProofKey proof = new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
			owner.key().parentOccurrence(), "alias-positive");
		CandidateRuleFact positive = withEmission(owner, emission,
			new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
				emission.derivedFoutAction(), List.of(realization,
					new CandidateEmissionRealization(realization.key(), List.of(proof), List.of()))));
		CompiledHopKey alias = keyLike(owner.key().parentOccurrence(), "alias");
		ValueVersionKey shared = version(owner.key().parentOccurrence(), 7);
		List<Node> aliasNodes = List.of(node(owner.key().parentOccurrence(), NodeKind.OPERATION, shared),
			node(alias, NodeKind.OPERATION, shared));
		Revision aliasRevision = CandidateClosureDependencies.revision(List.of(owner), List.of(positive),
			aliasNodes, List.of(), Map.of(), List.of());
		Assert.assertFalse(aliasRevision.directed());
		Assert.assertTrue(aliasRevision.invalidationOwners().contains(alias));

		CompiledHopKey functionInput = keyLike(owner.key().parentOccurrence(), "function-input");
		List<Node> boundaryNodes = List.of(
			node(owner.key().parentOccurrence(), NodeKind.OPERATION,
				version(owner.key().parentOccurrence(), 8)),
			node(functionInput, NodeKind.FUNCTION_INPUT, version(functionInput, 9)));
		Revision boundaryRevision = CandidateClosureDependencies.revision(List.of(owner), List.of(positive),
			boundaryNodes, List.of(), Map.of(), List.of(new Constraint(ConstraintKind.SAME_PLACEMENT,
				owner.key().parentOccurrence(), functionInput)));
		Assert.assertFalse(boundaryRevision.directed());
		Assert.assertTrue(boundaryRevision.invalidationOwners().contains(functionInput));
	}

	@Test
	public void largeAliasGroupUsesLinearMembershipIndexWithoutPairwiseEdges() {
		Map<CompiledHopKey,ValueVersionKey> aliases = new java.util.IdentityHashMap<>();
		ValueVersionKey shared = version(root, 11);
		for(int index = 0; index < 1000; index++)
			aliases.put(key("alias-" + index), shared);
		IndexStats stats = CandidateClosureDependencies.aliasIndexStatsForTesting(aliases);
		Assert.assertEquals(1, stats.aliasGroups());
		Assert.assertEquals(1000, stats.aliasMemberships());
		Assert.assertEquals(0, stats.materializedAliasEdges());
		Assert.assertEquals(0, stats.directedEdges());
	}

	@Test(timeout = 15000)
	public void twentyThousandNodeChainUsesIterativeCycleDetection() {
		int count = 20_000;
		List<CompiledHopKey> nodes = new java.util.ArrayList<>(count);
		Map<CompiledHopKey,List<CompiledHopKey>> chain = new java.util.IdentityHashMap<>();
		for(int index = 0; index < count; index++)
			nodes.add(key("chain-" + index));
		for(int index = 0; index + 1 < count; index++)
			chain.put(nodes.get(index), List.of(nodes.get(index + 1)));

		Revision acyclic = CandidateClosureDependencies.scheduleForTesting(
			Map.of(nodes.get(0), ChangeKind.POSITIVE_RELATION), chain);
		Assert.assertTrue(acyclic.directed());
		Assert.assertEquals(count, acyclic.directOwners().size());

		chain.put(nodes.get(count - 1), List.of(nodes.get(0)));
		Revision cyclic = CandidateClosureDependencies.scheduleForTesting(
			Map.of(nodes.get(0), ChangeKind.POSITIVE_RELATION), chain);
		Assert.assertFalse(cyclic.directed());
		Assert.assertEquals(count, cyclic.directOwners().size());
		Assert.assertEquals(cyclic.invalidationOwners(), cyclic.directOwners());
	}

	@Test
	public void cfgReaderFrontierCoversZeroOneAllAndUnsafeFallbacks() {
		List<Set<Integer>> reaching = List.of(Set.of(), Set.of(0), Set.of(), Set.of(2));
		List<Boolean> readers = List.of(false, true, false, true);
		Assert.assertEquals(Set.of(), CandidateClosureDependencies.selectCfgReaderOrdinals(
			Set.of(), reaching, readers, true));
		Assert.assertEquals("one writer must not schedule the unrelated reader", Set.of(1),
			CandidateClosureDependencies.selectCfgReaderOrdinals(Set.of(0), reaching, readers, true));
		Assert.assertEquals(Set.of(1, 3), CandidateClosureDependencies.selectCfgReaderOrdinals(
			Set.of(0, 2), reaching, readers, true));
		Assert.assertNull("deletion/replacement/authority epochs must replay every reader",
			CandidateClosureDependencies.selectCfgReaderOrdinals(Set.of(0), reaching, readers, false));

		List<Set<Integer>> loop = List.of(Set.of(), Set.of(), Set.of(0, 1));
		Assert.assertNull("affected loop/cycle readers must replay in full",
			CandidateClosureDependencies.selectCfgReaderOrdinals(
				Set.of(0), loop, List.of(false, false, true), true));
	}

	private Revision schedule(Map<CompiledHopKey,ChangeKind> changes) {
		return CandidateClosureDependencies.scheduleForTesting(changes, diamond);
	}

	private static CandidateRuleFact withEmission(CandidateRuleFact owner,
		CandidateEmissionFact original, CandidateEmissionFact replacement) {
		List<CandidateEmissionFact> emissions = new java.util.ArrayList<>(owner.allowedEmissionFacts());
		emissions.set(emissions.indexOf(original), replacement);
		return new CandidateRuleFact(owner.key(), owner.status(), owner.capability(), owner.shapeProof(),
			owner.profile(), emissions, owner.failureCode());
	}

	private static Node node(CompiledHopKey key, NodeKind kind, ValueVersionKey version) {
		return new Node(key, kind, version, false, List.of(), List.of(), List.of());
	}

	private static ValueVersionKey version(CompiledHopKey owner, int ordinal) {
		return new ValueVersionKey(owner.programFingerprint(), "value", owner.controlRegion(), ordinal,
			VersionKind.ORDINARY, List.of());
	}

	private static CompiledHopKey key(String id) {
		ControlRegionKey region = new ControlRegionKey("closure", "main", List.of("root"), "call", "rc");
		return new CompiledHopKey("closure", "main", "call", "rc", region, id, id);
	}

	private static CompiledHopKey keyLike(CompiledHopKey owner, String id) {
		return new CompiledHopKey(owner.programFingerprint(), owner.functionNamespace(),
			owner.callSitePath(), owner.recompileContext(), owner.controlRegion(), id, id);
	}
}
