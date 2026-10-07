/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder.PrivacyEvidenceMode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class DirectSupportUnionScheduleTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"support-union", "main", List.of("root"), "call", "compiled");
	private static final PlacementState LOCAL = new PlacementState(
		ExecType.CP, FederatedOutput.LOUT, null, false);

	@Test
	public void potentialOverlapKeepsOldChangedSignalButNotUnionSignal() throws Exception {
		CompiledHopKey source = key("source"), owner = key("owner");
		List<CandidateRuleFact> absent = List.of(excludedFact(owner));
		Object index = index(absent, adjacency(source, owner));

		Object added = update(index, owner, List.of(supportFact(source, owner)));
		Assert.assertTrue(changed(added));
		Assert.assertFalse(dependencyUnionChanged(added));

		Object removed = update(index, owner, absent);
		Assert.assertTrue(changed(removed));
		Assert.assertFalse(dependencyUnionChanged(removed));
		Assert.assertTrue(removed(removed).get(source).contains(owner));
	}

	@Test
	public void nonPotentialAndIdentityDistinctEdgesChangeTheDependencyUnion() throws Exception {
		CompiledHopKey source = key("source"), owner = key("owner");
		CompiledHopKey equalSource = key("source"), equalOwner = key("owner");
		Assert.assertEquals(source, equalSource);
		Assert.assertEquals(owner, equalOwner);
		Assert.assertNotSame(source, equalSource);
		Assert.assertNotSame(owner, equalOwner);
		List<CandidateRuleFact> absent = List.of(excludedFact(owner));
		Object index = index(absent, adjacency(equalSource, equalOwner));

		Object added = update(index, owner, List.of(supportFact(source, owner)));
		Assert.assertTrue(changed(added));
		Assert.assertTrue("structurally equal potential endpoints are not identity authority",
			dependencyUnionChanged(added));
		Object removed = update(index, owner, absent);
		Assert.assertTrue(dependencyUnionChanged(removed));

		CompiledHopKey unknown = key("unknown");
		Object unknownIndex = index(absent, Map.of());
		Assert.assertTrue(dependencyUnionChanged(
			update(unknownIndex, owner, List.of(supportFact(unknown, owner)))));
	}

	@Test
	public void identityPairMembershipRequiresBothExactEndpoints() throws Exception {
		CompiledHopKey source = key("source"), owner = key("owner");
		CompiledHopKey equalSource = key("source"), equalOwner = key("owner");
		List<CandidateRuleFact> absent = List.of(excludedFact(owner));

		Object distinctConsumer = index(absent, adjacency(source, equalOwner));
		Assert.assertTrue("the same source cannot authorize an equal but distinct consumer",
			dependencyUnionChanged(update(distinctConsumer, owner, List.of(supportFact(source, owner)))));

		Object distinctSource = index(absent, adjacency(equalSource, owner));
		Assert.assertTrue("the same consumer cannot authorize an equal but distinct source",
			dependencyUnionChanged(update(distinctSource, owner, List.of(supportFact(source, owner)))));
	}

	@Test
	public void mixedCoveredAndUncoveredDeltaChangesTheDependencyUnion() throws Exception {
		CompiledHopKey covered = key("covered"), uncovered = key("uncovered"), owner = key("owner");
		List<CandidateRuleFact> absent = List.of(excludedFact(owner));
		Object index = index(absent, adjacency(covered, owner));

		Object added = update(index, owner, List.of(supportFact(List.of(covered, uncovered), owner)));
		Assert.assertTrue(changed(added));
		Assert.assertTrue(dependencyUnionChanged(added));

		Object removed = update(index, owner, absent);
		Assert.assertTrue(changed(removed));
		Assert.assertTrue(dependencyUnionChanged(removed));
		Assert.assertTrue(removed(removed).get(covered).contains(owner));
		Assert.assertTrue(removed(removed).get(uncovered).contains(owner));
	}

	@Test
	public void selfEdgeAndCycleUseExactPairMembershipRatherThanSccHeuristics() throws Exception {
		CompiledHopKey a = key("a"), b = key("b");
		Map<CompiledHopKey,Set<CompiledHopKey>> potential = new IdentityHashMap<>();
		add(potential, a, a);
		add(potential, a, b);
		add(potential, b, a);

		Object self = index(List.of(excludedFact(a)), potential);
		Assert.assertFalse(dependencyUnionChanged(update(self, a, List.of(supportFact(a, a)))));
		Assert.assertFalse(dependencyUnionChanged(update(self, a, List.of(excludedFact(a)))));

		Object cycleEdge = index(List.of(excludedFact(b)), potential);
		Assert.assertFalse(dependencyUnionChanged(update(cycleEdge, b, List.of(supportFact(a, b)))));
		CompiledHopKey outside = key("outside");
		Assert.assertTrue("same SCC is not a substitute for exact potential-pair membership",
			dependencyUnionChanged(update(cycleEdge, b, List.of(supportFact(outside, b)))));
	}

	@Test
	public void nativeRebindingRetainsGroundedAlternativesButNotStagingAuthority() throws Exception {
		CompiledHopKey source = key("source"), owner = key("owner");
		List<CandidateRealizationInputBinding> bindings = supportFact(source, owner).allowedEmissionFacts()
			.get(0).realizations().get(0).supportClauses().get(0).inputBindings();
		PlacementEmissionState nativeEmission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
		CandidateEmissionRealization staging = CandidateEmissionRealization.nativeLineage(
			nativeEmission, "staging", List.of(), List.of());
		CandidateEmissionRealization widthOne = CandidateEmissionRealization.nativeLineage(
			nativeEmission, "layout-width-1", List.of(), bindings);
		CandidateEmissionRealization widthMany = CandidateEmissionRealization.nativeLineage(
			nativeEmission, "layout-width-128", List.of(), bindings);
		CandidateEmissionFact mixed = new CandidateEmissionFact(nativeEmission, FType.ROW, null,
			List.of(staging, widthOne, widthMany));

		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"retainPreviouslyGroundedNativeSupport", CandidateEmissionFact.class);
		method.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<CandidateEmissionRealization> retained =
			(List<CandidateEmissionRealization>)method.invoke(null, mixed);

		Assert.assertEquals(2, retained.size());
		Assert.assertEquals(1, retained.get(0).supportClauses().size());
		Assert.assertEquals(1, retained.get(0).supportClauses().get(0).inputBindings().size());
		for(CandidateEmissionRealization realization : retained)
			Assert.assertSame(source, realization.supportClauses().get(0)
				.inputBindings().get(0).source().rule().parentOccurrence());
	}

	@Test
	public void exactGroundedSubsetReusesOldOrAuthorityOnly() throws Exception {
		CompiledHopKey firstSource = key("first-source"), secondSource = key("second-source");
		CandidateEmissionFact first = supportFact(firstSource, key("owner"))
			.allowedEmissionFacts().get(0);
		CandidateEmissionFact second = supportFact(secondSource, key("owner"))
			.allowedEmissionFacts().get(0);
		CandidateEmissionFact prior = new CandidateEmissionFact(first.emissionState(), null, null,
			List.of(first.realizations().get(0), second.realizations().get(0)));
		CandidateEmissionRealization merged = prior.realizations().get(0);
		Assert.assertEquals("fixture must retain two exact OR clauses", 2,
			merged.supportClauses().size());
		CandidateEmissionRealization subset = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(merged.key(),
				List.of(merged.supportClauses().get(1)));

		Assert.assertTrue(containsExactGroundedNativeSupport(prior,
			List.of(merged, subset)));
		CandidateRealizationSupportClause unbound = new CandidateRealizationSupportClause(
			List.of(), List.of());
		CandidateEmissionRealization staging = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(merged.key(), List.of(unbound));
		Assert.assertFalse("generic staging authority must never enter the reuse path",
			containsExactGroundedNativeSupport(prior, List.of(staging)));
	}

	@Test
	public void changedDynamicWitnessAndNonEqualTieForceCanonicalMerge() throws Exception {
		CompiledHopKey source = key("source"), owner = key("owner");
		CandidateEmissionFact base = supportFact(source, owner).allowedEmissionFacts().get(0);
		List<CandidateRealizationInputBinding> bindings = base.realizations().get(0)
			.supportClauses().get(0).inputBindings();
		DurableAnchorKey witness = new DurableAnchorKey("witness", FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(4L, 2L))));
		PlacementProofKey proof = new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
			owner, "same-ordering-proof");
		PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
		CandidateEmissionRealization exact = CandidateEmissionRealization.nativeLineage(
			emission, "same-lineage", witness, List.of(proof), bindings);
		CandidateEmissionRealization dynamic = CandidateEmissionRealization.nativeLineageDynamicLayout(
			emission, "same-lineage", witness, List.of(proof), bindings);
		CandidateEmissionFact prior = new CandidateEmissionFact(emission, FType.ROW, null,
			List.of(exact));
		CandidateEmissionRealization newKey = CandidateEmissionRealization.nativeLineage(
			emission, "other-lineage", witness, List.of(proof), bindings);
		Assert.assertFalse("a new realization key must force the canonical merge",
			containsExactGroundedNativeSupport(prior, List.of(newKey)));
		Assert.assertEquals("the layout precision difference stays under one realization key",
			exact.key(), dynamic.key());
		Assert.assertFalse("an equal-key dynamic witness is a distinct support clause",
			containsExactGroundedNativeSupport(prior, List.of(dynamic)));

		CandidateRealizationSupportClause exactClause = exact.supportClauses().get(0);
		CandidateRealizationSupportClause nonEqual = new CandidateRealizationSupportClause(
			exactClause.proofDependencies(), exactClause.inputBindings(), witness, false);
		Assert.assertFalse("only equals, never ordering equivalence, authorizes reuse",
			containsExactGroundedNativeSupport(prior, List.of(
				CandidateEmissionRealization.fromAlreadyCanonicalSupportClauses(
					exact.key(), List.of(nonEqual)))));
	}

	@Test
	public void exactSubsetRejectsStructurallyEqualForeignAuthority() throws Exception {
		CompiledHopKey source = key("source"), foreignSource = key("source");
		CompiledHopKey owner = key("owner"), foreignOwner = key("owner");
		CandidateEmissionFact prior = supportFact(source, owner).allowedEmissionFacts().get(0);
		CandidateEmissionRealization foreignBinding = supportFact(foreignSource, owner)
			.allowedEmissionFacts().get(0).realizations().get(0);
		Assert.assertEquals(prior.realizations().get(0).supportClauses().get(0),
			foreignBinding.supportClauses().get(0));
		Assert.assertFalse("structural equality cannot replace exact input-owner authority",
			containsExactGroundedNativeSupport(prior, List.of(foreignBinding)));

		CandidateEmissionRealization retained = prior.realizations().get(0);
		List<CandidateRealizationInputBinding> bindings = retained.supportClauses().get(0).inputBindings();
		PlacementProofKey proof = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, owner, "proof");
		PlacementProofKey foreignProof = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, foreignOwner, "proof");
		CandidateEmissionRealization ownedProof = CandidateEmissionRealization.local(
			prior.emissionState(), List.of(proof), bindings);
		CandidateEmissionRealization structurallyEqualForeignProof = CandidateEmissionRealization.local(
			prior.emissionState(), List.of(foreignProof), bindings);
		Assert.assertEquals(ownedProof.supportClauses().get(0),
			structurallyEqualForeignProof.supportClauses().get(0));
		CandidateEmissionFact proofPrior = new CandidateEmissionFact(
			prior.emissionState(), null, null, List.of(ownedProof));
		Assert.assertFalse("structural equality cannot replace exact proof-owner authority",
			containsExactGroundedNativeSupport(proofPrior,
				List.of(structurallyEqualForeignProof)));
	}

	@Test
	public void directNativePublicationMemoUsesCompleteIdentityContext() throws Exception {
		CompiledHopKey owner = key("owner"), foreignOwner = key("owner");
		PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
		DurableAnchorKey seed = anchor("seed", 4, 2);
		DurableAnchorKey output = anchor("output", 4, 2);
		DurableAnchorKey changedExtent = anchor("output", 4, 3);
		NativePlacementContinuity.NativeContinuityProof proof =
			new NativePlacementContinuity.NativeContinuityProof(seed, seed, true, List.of());
		PlacementRelationClosure closure = closure();

		CandidateEmissionRealization first = directNativePublication(
			closure, proof, owner, emission, output, "lineage", true);
		Assert.assertSame(first, directNativePublication(
			closure, proof, owner, emission, output, "lineage", true));
		Assert.assertEquals(PlacementLayoutKind.DURABLE_MAP, first.key().layoutKind());

		NativePlacementContinuity.NativeContinuityProof equalForeignProof =
			new NativePlacementContinuity.NativeContinuityProof(seed, seed, true, List.of());
		CandidateEmissionRealization proofMiss = directNativePublication(
			closure, equalForeignProof, owner, emission, output, "lineage", true);
		Assert.assertNotSame("equal proof values do not share query authority", first, proofMiss);
		Assert.assertEquals(first, proofMiss);

		CandidateEmissionRealization ownerMiss = directNativePublication(
			closure, proof, foreignOwner, emission, output, "lineage", true);
		Assert.assertNotSame(first, ownerMiss);
		Assert.assertSame(foreignOwner,
			ownerMiss.supportClauses().get(0).proofDependencies().get(0).owner());
		PlacementEmissionState equalForeignEmission = new PlacementEmissionState(
			emission.placementState(), emission.derivedFedFout());
		Assert.assertEquals(emission, equalForeignEmission);
		Assert.assertNotSame(emission, equalForeignEmission);
		CandidateEmissionRealization emissionMiss = directNativePublication(
			closure, proof, owner, equalForeignEmission, output, "lineage", true);
		Assert.assertNotSame(first, emissionMiss);
		Assert.assertSame(equalForeignEmission, emissionMiss.key().emissionState());
		Assert.assertSame("state rebinding must retain the immutable proof clause",
			first.supportClauses().get(0), emissionMiss.supportClauses().get(0));
		Assert.assertNotSame(first, directNativePublication(
			closure, proof, owner, emission, changedExtent, "lineage", true));
		Assert.assertNotSame(first, directNativePublication(
			closure, proof, owner, emission, output, "other-lineage", true));

		CandidateEmissionRealization inexactInputs = directNativePublication(
			closure, proof, owner, emission, output, "lineage", false);
		Assert.assertNotSame(first, inexactInputs);
		Assert.assertEquals(PlacementLayoutKind.NATIVE_LINEAGE,
			inexactInputs.key().layoutKind());
		NativePlacementContinuity.NativeContinuityProof dynamicProof =
			new NativePlacementContinuity.NativeContinuityProof(seed, seed, false, List.of());
		CandidateEmissionRealization dynamic = directNativePublication(
			closure, dynamicProof, owner, emission, null, "lineage", true);
		Assert.assertEquals(PlacementLayoutKind.NATIVE_LINEAGE, dynamic.key().layoutKind());
		Assert.assertFalse(dynamic.supportClauses().get(0).nativeWorkerPoolLayoutExact());

		CandidateEmissionRealization cold = directNativePublication(
			closure(), proof, owner, emission, output, "lineage", true);
		Assert.assertNotSame(first, cold);
		Assert.assertEquals("memoization must preserve cold publication semantics", first, cold);
	}

	@Test
	public void directNativePublicationMemoEvictsOldestAndClearsPerBuild() throws Exception {
		CompiledHopKey owner = key("owner");
		PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
		DurableAnchorKey seed = anchor("seed", 4, 2);
		DurableAnchorKey output = anchor("output", 4, 2);
		NativePlacementContinuity.NativeContinuityProof proof =
			new NativePlacementContinuity.NativeContinuityProof(seed, seed, true, List.of());
		PlacementRelationClosure closure = closure();
		CandidateEmissionRealization untouched = directNativePublication(
			closure, proof, owner, emission, output, "untouched", true);
		CandidateEmissionRealization retained = directNativePublication(
			closure, proof, owner, emission, output, "retained", true);
		for(int entry = 0; entry < 32766; entry++)
			directNativePublication(closure, proof, owner, emission, output,
				"recent-" + entry, true);
		Assert.assertEquals(32768, publicationMemoSize(closure));
		Assert.assertSame(retained, directNativePublication(
			closure, proof, owner, emission, output, "retained", true));
		directNativePublication(closure, proof, owner, emission, output, "overflow", true);
		CandidateEmissionRealization reloaded = directNativePublication(
			closure, proof, owner, emission, output, "untouched", true);
		Assert.assertNotSame("the least-recent entry must be recomputed after eviction",
			untouched, reloaded);
		Assert.assertEquals("eviction changes caching only", untouched, reloaded);
		Assert.assertSame("a touched old entry must survive access-order eviction", retained,
			directNativePublication(closure, proof, owner, emission, output, "retained", true));

		PlacementCandidateGenerator generator = new PlacementCandidateGenerator(
			Mockito.mock(OracleFacade.class), null);
		PlacementRelationClosure resettable = new PlacementRelationClosure(
			generator, null, null, false, PrivacyEvidenceMode.NONE, false);
		directNativePublication(resettable, proof, owner, emission, output, "entry", true);
		Assert.assertEquals(1, publicationMemoSize(resettable));
		resettable.clearBuildState();
		Assert.assertEquals(0, publicationMemoSize(resettable));
	}

	private static PlacementRelationClosure closure() {
		return new PlacementRelationClosure(null, null, null, false, PrivacyEvidenceMode.NONE, false);
	}

	private static CandidateEmissionRealization directNativePublication(
		PlacementRelationClosure closure,
		NativePlacementContinuity.NativeContinuityProof proof, CompiledHopKey owner,
		PlacementEmissionState emission, DurableAnchorKey outputAnchor,
		String lineage, boolean directInputsExact) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("directNativePublication",
			NativePlacementContinuity.NativeContinuityProof.class, CompiledHopKey.class,
			PlacementEmissionState.class, DurableAnchorKey.class, String.class,
			boolean.class);
		method.setAccessible(true);
		return (CandidateEmissionRealization)method.invoke(closure, proof, owner, emission,
			outputAnchor, lineage, directInputsExact);
	}

	private static DurableAnchorKey anchor(String id, long rows, long columns) {
		return new DurableAnchorKey(id, FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(rows, columns))));
	}

	private static int publicationMemoSize(PlacementRelationClosure closure) throws Exception {
		var field = PlacementRelationClosure.class.getDeclaredField("directNativePublicationMemo");
		field.setAccessible(true);
		return ((Map<?,?>)field.get(closure)).size();
	}

	private static boolean containsExactGroundedNativeSupport(CandidateEmissionFact emission,
		List<CandidateEmissionRealization> realizations) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"containsExactGroundedNativeSupport", CandidateEmissionFact.class, List.class);
		method.setAccessible(true);
		return (boolean)method.invoke(null, emission, realizations);
	}

	private static Object index(List<CandidateRuleFact> facts,
		Map<CompiledHopKey,Set<CompiledHopKey>> potential) throws Exception {
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$DirectSupportIndex");
		Constructor<?> constructor = type.getDeclaredConstructor(Map.class, List.class, Map.class);
		constructor.setAccessible(true);
		Map<CompiledHopKey,List<Integer>> slots = new IdentityHashMap<>();
		for(int slot = 0; slot < facts.size(); slot++)
			slots.computeIfAbsent(facts.get(slot).key().parentOccurrence(), ignored -> new ArrayList<>()).add(slot);
		return constructor.newInstance(slots, facts, potential);
	}

	private static Object update(Object index, CompiledHopKey owner,
		List<CandidateRuleFact> facts) throws Exception {
		Method method = index.getClass().getDeclaredMethod("update", Set.class, List.class);
		method.setAccessible(true);
		Set<CompiledHopKey> changed = Collections.newSetFromMap(new IdentityHashMap<>());
		changed.add(owner);
		return method.invoke(index, changed, facts);
	}

	private static boolean changed(Object update) throws Exception {
		Method method = update.getClass().getDeclaredMethod("changed");
		method.setAccessible(true);
		return (boolean)method.invoke(update);
	}

	private static boolean dependencyUnionChanged(Object update) throws Exception {
		Method method = update.getClass().getDeclaredMethod("dependencyUnionChanged");
		method.setAccessible(true);
		return (boolean)method.invoke(update);
	}

	@SuppressWarnings("unchecked")
	private static Map<CompiledHopKey,Set<CompiledHopKey>> removed(Object update) throws Exception {
		Method method = update.getClass().getDeclaredMethod("removed");
		method.setAccessible(true);
		return (Map<CompiledHopKey,Set<CompiledHopKey>>)method.invoke(update);
	}

	private static Map<CompiledHopKey,Set<CompiledHopKey>> adjacency(
		CompiledHopKey source, CompiledHopKey owner) {
		Map<CompiledHopKey,Set<CompiledHopKey>> result = new IdentityHashMap<>();
		add(result, source, owner);
		return result;
	}

	private static void add(Map<CompiledHopKey,Set<CompiledHopKey>> graph,
		CompiledHopKey source, CompiledHopKey owner) {
		graph.computeIfAbsent(source,
			ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(owner);
	}

	private static CandidateRuleFact supportFact(CompiledHopKey source, CompiledHopKey owner) {
		return supportFact(List.of(source), owner);
	}

	private static CandidateRuleFact supportFact(List<CompiledHopKey> sources, CompiledHopKey owner) {
		PlacementEmissionState emission = new PlacementEmissionState(LOCAL, false);
		List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
		for(int inputIndex = 0; inputIndex < sources.size(); inputIndex++) {
			CandidateRuleKey sourceRule = new CandidateRuleKey(sources.get(inputIndex), List.of());
			CandidateRealizationReference sourceRef = CandidateRealizationReference.of(sourceRule,
				CandidateEmissionRealization.local(emission));
			bindings.add(CandidateRealizationInputBinding.direct(inputIndex, sourceRef));
		}
		CandidateEmissionRealization realization = CandidateEmissionRealization.local(emission,
			List.of(), bindings);
		CandidateEmissionFact output = new CandidateEmissionFact(emission, null, null, List.of(realization));
		return fact(owner, CandidateEvaluationStatus.AVAILABLE, List.of(output), "");
	}

	private static CandidateRuleFact excludedFact(CompiledHopKey owner) {
		return fact(owner, CandidateEvaluationStatus.PRIVACY_EXCLUDED, List.of(), "PRIVATE_AGGREGATE");
	}

	private static CandidateRuleFact fact(CompiledHopKey owner, CandidateEvaluationStatus status,
		List<CandidateEmissionFact> emissions, String failure) {
		return new CandidateRuleFact(new CandidateRuleKey(owner, List.of()), status,
			new CandidateCapabilityFact(OpCategory.BINARY_EWISE, "fixture", ExecType.CP,
				FederatedOutput.LOUT, null, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""), emissions, failure);
	}

	private static CompiledHopKey key(String name) {
		return new CompiledHopKey("support-union", "main", "call", "compiled", REGION, name, name);
	}
}
