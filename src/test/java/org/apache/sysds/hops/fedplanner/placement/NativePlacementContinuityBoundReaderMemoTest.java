/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact reverse-reader projection reuse; this is not a planner acceptance fixture. */
public class NativePlacementContinuityBoundReaderMemoTest {
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@Test
	public void ownedIndexMatchesColdOracleForMixedBindingsAndIdentityKeys() throws Exception {
		CompiledHopKey source = key("source", 0);
		CompiledHopKey equalForeignSource = key("source", 0);
		CompiledHopKey excluded = key("excluded", 1);
		CompiledHopKey readerA = key("reader-a", 2);
		CompiledHopKey readerB = key("reader-b", 3);
		Assert.assertEquals(source, equalForeignSource);
		Assert.assertNotSame(source, equalForeignSource);

		CandidateRealizationReference sourceRef = reference(source, "source");
		CandidateRealizationReference foreignRef = reference(equalForeignSource, "foreign");
		CandidateRealizationReference excludedRef = reference(excluded, "excluded");
		CandidateRuleFact first = fact(readerA, "first", List.of(
			valueMap("map-a", List.of(
				clause(direct(0, sourceRef), logical(1, foreignRef), direct(2, sourceRef)),
				clause(relocation(3, excludedRef, readerA), direct(4, excludedRef)))),
			durable("durable", direct(0, excludedRef)),
			nativeLineage("native", direct(0, excludedRef))));
		CandidateRuleFact second = fact(readerB, "second", List.of(
			valueMap("map-b", List.of(clause(direct(0, sourceRef))))));
		NativePlacementContinuity resolver = resolver(List.of(first, second));

		Map<CompiledHopKey,List<CompiledHopKey>> cold = coldIndex(resolver);
		Map<CompiledHopKey,List<CompiledHopKey>> owned = ownedIndex(resolver, null);
		assertIdentityMapEquals(cold, owned);
		Assert.assertEquals(2L, resolver.boundSourceProjectionScans());
		Assert.assertSame(readerA, readers(owned, equalForeignSource).get(0));
		Assert.assertEquals(readers(cold, source), readers(owned, source));
		Assert.assertEquals(List.of(readerA), readers(owned, excluded));

		Set<CompiledHopKey> changed = identitySet(source);
		assertIdentitySetEquals(reverse(changed, cold), reverse(changed, owned));
		Assert.assertEquals("the cached complete index avoids every nested rescan", 2L,
			resolver.boundSourceProjectionScans());
	}

	@Test
	public void retainedFactIdentityCrossesRevisionWithoutKeepingWithdrawnFacts() throws Exception {
		CompiledHopKey retainedOwner = key("retained-reader", 10);
		CompiledHopKey churnOwner = key("churn-reader", 11);
		CompiledHopKey source = key("source", 12);
		CandidateRuleFact retained = fact(retainedOwner, "retained", List.of(
			valueMap("retained-map", List.of(clause(direct(0, reference(source, "source")))))));
		CandidateRuleFact removed = fact(churnOwner, "removed", List.of(
			valueMap("removed-map", List.of(clause(direct(0, reference(source, "source")))))));
		NativePlacementContinuity before = resolver(List.of(retained, removed));
		ownedIndex(before, null);
		Assert.assertEquals(2L, before.boundSourceProjectionScans());

		CandidateRuleFact replacement = fact(churnOwner, "replacement", List.of(
			valueMap("replacement-map", List.of(clause(
				logical(0, reference(retainedOwner, "retained")))))));
		NativePlacementContinuity after = before.nextRevision(List.of(retained, replacement));
		Assert.assertEquals(1, projectionMemo(after).size());
		Assert.assertTrue(identityContains(projectionMemo(after), retained));
		Assert.assertFalse(identityContains(projectionMemo(after), removed));
		assertIdentityMapEquals(coldIndex(after), ownedIndex(after, before));
		Assert.assertEquals("only the reconstructed fact is scanned", 1L,
			after.boundSourceProjectionScans());
		Assert.assertTrue(identityContains(projectionMemo(after), replacement));
		Assert.assertFalse(identityContains(projectionMemo(after), removed));
	}

	@Test
	public void lateDonorProjectionTransfersButColdQueryStatesRemainIsolated() throws Exception {
		CompiledHopKey reader = key("reader", 20);
		CompiledHopKey source = key("source", 21);
		CandidateRuleFact retained = fact(reader, "retained", List.of(
			valueMap("map", List.of(clause(direct(0, reference(source, "source")))))));
		NativePlacementContinuity before = resolver(List.of(retained));
		NativePlacementContinuity after = before.nextRevision(List.of(retained));

		ownedIndex(before, null);
		Assert.assertEquals(1L, before.boundSourceProjectionScans());
		assertIdentityMapEquals(coldIndex(after), ownedIndex(after, before));
		Assert.assertEquals("the donor was populated after the revision copy", 0L,
			after.boundSourceProjectionScans());

		NativePlacementContinuity fresh = after.freshQueryState();
		ownedIndex(fresh, null);
		Assert.assertEquals("fresh query state owns no prior-analysis projection", 1L,
			fresh.boundSourceProjectionScans());

		NativePlacementContinuity ownerResolver = resolverWithOwnerNode(retained);
		ownedIndex(ownerResolver, null);
		NativePlacementContinuity ownerRevision = ownerResolver.nextOwnerRevision(reader,
			List.of(retained));
		ownedIndex(ownerRevision, null);
		Assert.assertEquals("an owner revision is a cold independent authority snapshot", 1L,
			ownerRevision.boundSourceProjectionScans());
	}

	@Test
	public void withdrawalAndRestoreMatchColdReachabilityAcrossACycle() throws Exception {
		CompiledHopKey a = key("cycle-a", 30);
		CompiledHopKey b = key("cycle-b", 31);
		CandidateRuleFact aReadsB = fact(a, "a-reads-b", List.of(
			valueMap("a-map", List.of(clause(direct(0, reference(b, "b")))))));
		CandidateRuleFact bReadsA = fact(b, "b-reads-a", List.of(
			valueMap("b-map", List.of(clause(direct(0, reference(a, "a")))))));
		NativePlacementContinuity cyclic = resolver(List.of(aReadsB, bReadsA));
		Map<CompiledHopKey,List<CompiledHopKey>> cyclicOwned = ownedIndex(cyclic, null);
		assertIdentityMapEquals(coldIndex(cyclic), cyclicOwned);
		Assert.assertTrue(reverse(identitySet(a), cyclicOwned).contains(a));
		Assert.assertTrue(reverse(identitySet(a), cyclicOwned).contains(b));

		CandidateRuleFact aWithdrawn = fact(a, "a-withdrawn", List.of(
			valueMap("a-empty", List.of(clause()))));
		NativePlacementContinuity withdrawn = cyclic.nextRevision(List.of(aWithdrawn, bReadsA));
		Map<CompiledHopKey,List<CompiledHopKey>> withdrawnOwned = ownedIndex(withdrawn, cyclic);
		assertIdentityMapEquals(coldIndex(withdrawn), withdrawnOwned);
		Set<CompiledHopKey> withdrawnReachability = reverse(identitySet(a), withdrawnOwned);
		Assert.assertFalse(withdrawnReachability.contains(a));
		Assert.assertTrue(withdrawnReachability.contains(b));
		Assert.assertEquals(1L, withdrawn.boundSourceProjectionScans());

		NativePlacementContinuity restored = withdrawn.nextRevision(List.of(aReadsB, bReadsA));
		Map<CompiledHopKey,List<CompiledHopKey>> restoredOwned = ownedIndex(restored, withdrawn);
		assertIdentityMapEquals(coldIndex(restored), restoredOwned);
		assertIdentitySetEquals(reverse(identitySet(a), cyclicOwned),
			reverse(identitySet(a), restoredOwned));
		Assert.assertEquals("a fact absent from the current inventory is rebuilt on restore", 1L,
			restored.boundSourceProjectionScans());
	}

	@Test
	public void continuityDonorMergeDoesNotDiscardAnAlreadyBuiltBoundProjection() throws Exception {
		CompiledHopKey reader = key("merge-reader", 40);
		CompiledHopKey source = key("merge-source", 41);
		CandidateRuleFact retained = fact(reader, "merge", List.of(
			valueMap("merge-map", List.of(clause(direct(0, reference(source, "source")))))));
		NativePlacementContinuity donor = resolver(List.of(retained));
		NativePlacementContinuity current = donor.nextRevision(List.of(retained));

		ownedIndex(current, null);
		ownedProjection(donor, retained, null);
		ownedProjection(current, retained, donor);

		NativePlacementContinuity next = current.nextRevision(List.of(retained));
		ownedIndex(next, current);
		Assert.assertEquals("merging another projection slot must retain bound sources", 0L,
			next.boundSourceProjectionScans());
	}

	@SuppressWarnings("unchecked")
	private static Map<CompiledHopKey,List<CompiledHopKey>> ownedIndex(
		NativePlacementContinuity resolver, NativePlacementContinuity donor) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"boundCandidateReadersBySource", NativePlacementContinuity.class);
		method.setAccessible(true);
		return (Map<CompiledHopKey,List<CompiledHopKey>>)method.invoke(resolver, donor);
	}

	@SuppressWarnings("unchecked")
	private static Map<CompiledHopKey,List<CompiledHopKey>> coldIndex(
		NativePlacementContinuity resolver) throws Exception {
		Field facts = NativePlacementContinuity.class.getDeclaredField("candidateFactsByKey");
		facts.setAccessible(true);
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"indexBoundCandidateReaders", Map.class);
		method.setAccessible(true);
		return (Map<CompiledHopKey,List<CompiledHopKey>>)method.invoke(null, facts.get(resolver));
	}

	private static void ownedProjection(NativePlacementContinuity resolver,
		CandidateRuleFact fact, NativePlacementContinuity donor) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("continuityProjectionOwned",
			List.class, boolean.class, NativePlacementContinuity.class);
		method.setAccessible(true);
		method.invoke(resolver, List.of(fact), true, donor);
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> reverse(Set<CompiledHopKey> changed,
		Map<CompiledHopKey,List<CompiledHopKey>> index) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"reverseReachableReaders", Set.class, Map.class);
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(null, changed, index);
	}

	@SuppressWarnings("unchecked")
	private static Map<CandidateRuleFact,?> projectionMemo(NativePlacementContinuity resolver)
		throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField("continuityProjectionMemo");
		field.setAccessible(true);
		return (Map<CandidateRuleFact,?>)field.get(resolver);
	}

	private static boolean identityContains(Map<?,?> values, Object expected) {
		for(Object value : values.keySet())
			if(value == expected)
				return true;
		return false;
	}

	private static void assertIdentityMapEquals(Map<CompiledHopKey,List<CompiledHopKey>> expected,
		Map<CompiledHopKey,List<CompiledHopKey>> actual) {
		Assert.assertEquals(expected.size(), actual.size());
		for(var entry : expected.entrySet()) {
			List<CompiledHopKey> readers = readers(actual, entry.getKey());
			Assert.assertEquals(entry.getValue().size(), readers.size());
			for(int index = 0; index < readers.size(); index++)
				Assert.assertSame(entry.getValue().get(index), readers.get(index));
		}
	}

	private static List<CompiledHopKey> readers(Map<CompiledHopKey,List<CompiledHopKey>> index,
		CompiledHopKey source) {
		for(var entry : index.entrySet())
			if(entry.getKey() == source)
				return entry.getValue();
		return List.of();
	}

	private static void assertIdentitySetEquals(Set<CompiledHopKey> expected,
		Set<CompiledHopKey> actual) {
		Assert.assertEquals(expected.size(), actual.size());
		for(CompiledHopKey value : expected)
			Assert.assertTrue(actual.contains(value));
	}

	private static NativePlacementContinuity resolver(List<CandidateRuleFact> facts) {
		return new NativePlacementContinuity(Map.of(), Map.of(), facts, List.of(), Map.of());
	}

	private static NativePlacementContinuity resolverWithOwnerNode(CandidateRuleFact fact) {
		CompiledHopKey owner = fact.key().parentOccurrence();
		ValueVersionKey version = new ValueVersionKey("bound-reader", "owner",
			owner.controlRegion(), 0, VersionKind.ORDINARY, List.of());
		Node node = new Node(owner, NodeKind.OPERATION, version, true,
			List.of(EMISSION.placementState()), List.of(), List.of());
		return new NativePlacementContinuity(Map.of(owner, node), Map.of(),
			List.of(fact), List.of(), Map.of());
	}

	private static CandidateRuleFact fact(CompiledHopKey owner, String id,
		List<CandidateEmissionRealization> realizations) {
		return new CandidateRuleFact(new CandidateRuleKey(owner, List.of()),
			CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "bound-reader", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, id, List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""),
			List.of(new CandidateEmissionFact(EMISSION, FType.ROW, null, realizations)), "");
	}

	private static CandidateEmissionRealization valueMap(String id,
		List<CandidateRealizationSupportClause> clauses) {
		return CandidateEmissionRealization.valueMap(EMISSION, id, clauses);
	}

	private static CandidateEmissionRealization durable(String id,
		CandidateRealizationInputBinding binding) {
		return CandidateEmissionRealization.durable(EMISSION, anchor(id), List.of(), List.of(binding));
	}

	private static CandidateEmissionRealization nativeLineage(String id,
		CandidateRealizationInputBinding binding) {
		return CandidateEmissionRealization.nativeLineage(EMISSION, id, List.of(), List.of(binding));
	}

	private static CandidateRealizationSupportClause clause(
		CandidateRealizationInputBinding... bindings) {
		return new CandidateRealizationSupportClause(List.of(), List.of(bindings));
	}

	private static CandidateRealizationInputBinding direct(int position,
		CandidateRealizationReference source) {
		return CandidateRealizationInputBinding.direct(position, source);
	}

	private static CandidateRealizationInputBinding logical(int position,
		CandidateRealizationReference source) {
		return CandidateRealizationInputBinding.logicalTransient(position, source);
	}

	private static CandidateRealizationInputBinding relocation(int position,
		CandidateRealizationReference source, CompiledHopKey consumer) {
		ValueVersionKey version = new ValueVersionKey("bound-reader", "value", consumer.controlRegion(),
			0, VersionKind.ORDINARY, List.of());
		RelocationActionKey action = new RelocationActionKey(version, EMISSION.placementState(),
			FType.ROW, anchor("relocation"), consumer.controlRegion().normalizedSignature(),
			List.of(consumer));
		return CandidateRealizationInputBinding.relocation(position, source, action);
	}

	private static CandidateRealizationReference reference(CompiledHopKey owner, String id) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		return CandidateRealizationReference.of(rule,
			CandidateEmissionRealization.durable(EMISSION, anchor(id), List.of(), List.of()));
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("worker:1234", List.of(0L, 0L), List.of(4L, 4L))));
	}

	private static CompiledHopKey key(String name, int ordinal) {
		ControlRegionKey region = new ControlRegionKey("bound-reader", "main",
			List.of("root/" + ordinal), "root", "compiled");
		return new CompiledHopKey("bound-reader", "main", "root", "compiled", region,
			name + '@' + ordinal, name);
	}

	@SafeVarargs
	private static <T> Set<T> identitySet(T... values) {
		Set<T> result = Collections.newSetFromMap(new IdentityHashMap<>());
		Collections.addAll(result, values);
		return result;
	}
}
