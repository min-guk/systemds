/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class RelocationProductMemoTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"relocation-product", "main", List.of("root"), "root", "compiled");
	private static final CompiledHopKey OWNER = key("owner");
	private static final CompiledHopKey SOURCE = key("source");
	private static final ValueVersionKey SOURCE_VERSION = version("source", 0);
	private static final ValueVersionKey OWNER_VERSION = version("owner", 1);
	private static final PlacementState SOURCE_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementState TARGET_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState SOURCE_EMISSION =
		new PlacementEmissionState(SOURCE_STATE, false);
	private static final PlacementEmissionState TARGET_EMISSION =
		new PlacementEmissionState(TARGET_STATE, false);
	private static final CandidateRuleKey SOURCE_RULE = new CandidateRuleKey(SOURCE, List.of());

	@Test
	public void exactCurrentProductIsReusedWithoutEnumeratingLeavesAgain() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder(null, metrics);
		Map<Object,Object> currentProducts = new HashMap<>();
		CandidateEmissionFact emission = new CandidateEmissionFact(TARGET_EMISSION, FType.ROW);
		CandidateRealizationInputBinding first = relocationBinding(SOURCE, relocationAction("pool-a"));
		CandidateRealizationInputBinding second = relocationBinding(SOURCE, relocationAction("pool-b"));
		List<List<CandidateRealizationInputBinding>> choices = List.of(List.of(first, second));
		DurableAnchorKey output = anchor("output", 4);

		List<CandidateEmissionRealization> cold = product(builder, OWNER, emission,
			choices, output, false, null, currentProducts);
		long coldLeaves = metrics.snapshot().relocationLeaves();
		List<CandidateEmissionRealization> hot = product(builder, OWNER, emission,
			List.of(List.copyOf(choices.get(0))), output, false, null, currentProducts);

		Assert.assertEquals(1, cold.size());
		Assert.assertEquals(2, cold.get(0).supportClauses().size());
		Assert.assertEquals(2, coldLeaves);
		Assert.assertSame("an exact current-pass product must reuse its immutable relation", cold, hot);
		Assert.assertEquals("a cache hit must not enumerate the product again",
			coldLeaves, metrics.snapshot().relocationLeaves());
	}

	@Test
	public void previousInvocationReuseRetainsOnlyFreshExactChoices() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder(null, metrics);
		CandidateEmissionFact emission = new CandidateEmissionFact(TARGET_EMISSION, FType.ROW);
		CandidateRealizationInputBinding first = relocationBinding(SOURCE, relocationAction("pool-a"));
		CandidateRealizationInputBinding second = relocationBinding(SOURCE, relocationAction("pool-b"));
		DurableAnchorKey output = anchor("output", 4);
		Map<Object,Object> previous = new HashMap<>();
		List<CandidateEmissionRealization> original = product(builder, OWNER, emission,
			List.of(List.of(first)), output, false, null, previous);
		Object relationClosure = PlacementBuilderTestAccess.relationClosure(builder);
		Field cache = PlacementRelationClosure.class.getDeclaredField("relocationProducts");
		cache.setAccessible(true);
		cache.set(relationClosure, previous);
		Map<Object,Object> current = new HashMap<>();
		long leaves = metrics.snapshot().relocationLeaves();
		Assert.assertSame(original, product(builder, OWNER, emission,
			List.of(List.of(first)), output, false, null, current));
		Assert.assertEquals(leaves, metrics.snapshot().relocationLeaves());
		Assert.assertEquals("a previous-generation hit enters the current generation", 1, current.size());
		List<CandidateEmissionRealization> expanded = product(builder, OWNER, emission,
			List.of(List.of(first, second)), output, false, null, current);
		Assert.assertEquals(2, expanded.get(0).supportClauses().size());
		Assert.assertEquals(cold(emission, List.of(List.of(first, second)), output, false, null), expanded);
		List<List<CandidateRealizationInputBinding>> absent = List.of(List.of());
		Assert.assertTrue(product(builder, OWNER, emission, absent, output, false, null, current).isEmpty());
		Field hits = PlacementRelationClosure.class.getDeclaredField("relocationProductHits");
		hits.setAccessible(true);
		long before = hits.getLong(relationClosure);
		Assert.assertTrue(product(builder, OWNER, emission, absent, output, false, null, current).isEmpty());
		Assert.assertEquals("cached empty is not an absent entry", before + 1, hits.getLong(relationClosure));
		Assert.assertSame("restored exact input may reuse its prior generated relation", original,
			product(builder, OWNER, emission, List.of(List.of(first)), output, false, null, current));
	}

	@Test
	public void changedChoicesReuseCurrentClausesAndEnumerateOnlyFirstAddedSlices() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder(null, metrics);
		CandidateEmissionFact emission = new CandidateEmissionFact(TARGET_EMISSION, FType.ROW);
		DurableAnchorKey output = anchor("output", 4);
		CompiledHopKey otherSource = key("other-source");
		CandidateRealizationInputBinding first = relocationBinding(0, SOURCE, relocationAction("pool-a"));
		CandidateRealizationInputBinding second = relocationBinding(0, SOURCE, relocationAction("pool-b"));
		CandidateRealizationInputBinding addedFirst = relocationBinding(
			0, SOURCE, relocationAction("pool-added-first"));
		CandidateRealizationInputBinding third = relocationBinding(
			1, otherSource, relocationAction("pool-c"));
		CandidateRealizationInputBinding addedSecond = relocationBinding(
			1, otherSource, relocationAction("pool-added-second"));
		Map<Object,Object> previous = new HashMap<>();
		product(builder, OWNER, emission, List.of(List.of(first, second), List.of(third)),
			output, false, null, previous);
		Object relationClosure = PlacementBuilderTestAccess.relationClosure(builder);
		Field cache = PlacementRelationClosure.class.getDeclaredField("relocationProducts");
		cache.setAccessible(true);
		cache.set(relationClosure, previous);
		Map<Object,Object> current = new HashMap<>();

		long beforeExpansion = metrics.snapshot().relocationLeaves();
		List<List<CandidateRealizationInputBinding>> expandedChoices = List.of(
			List.of(first, second, addedFirst), List.of(third, addedSecond));
		List<CandidateEmissionRealization> expanded = product(builder, OWNER, emission,
			expandedChoices, output, false, null, current);
		Assert.assertEquals(cold(emission, expandedChoices, output, false, null), expanded);
		Assert.assertEquals("only the four disjoint first-added slices are new",
			4, metrics.snapshot().relocationLeaves() - beforeExpansion);

		long beforeDeletion = metrics.snapshot().relocationLeaves();
		List<List<CandidateRealizationInputBinding>> reducedChoices =
			List.of(List.of(second, addedFirst), List.of(addedSecond));
		Assert.assertEquals(cold(emission, reducedChoices, output, false, null),
			product(builder, OWNER, emission, reducedChoices, output, false, null, current));
		Assert.assertEquals("deletion only filters retained clauses", beforeDeletion,
			metrics.snapshot().relocationLeaves());

		CandidateRealizationInputBinding foreignAction = relocationBinding(
			0, SOURCE, relocationAction("pool-b"));
		List<List<CandidateRealizationInputBinding>> replacement = List.of(List.of(foreignAction));
		long beforeReplacement = metrics.snapshot().relocationLeaves();
		Assert.assertEquals(cold(emission, replacement, output, false, null),
			product(builder, OWNER, emission, replacement, output, false, null, current));
		Assert.assertEquals("equal-valued foreign action authority is a new binding", 1,
			metrics.snapshot().relocationLeaves() - beforeReplacement);

		List<List<CandidateRealizationInputBinding>> empty = List.of(List.of());
		long beforeEmpty = metrics.snapshot().relocationLeaves();
		Assert.assertEquals(cold(emission, empty, output, false, null),
			product(builder, OWNER, emission, empty, output, false, null, current));
		Assert.assertEquals("empty choices remove stale clauses without enumeration", beforeEmpty,
			metrics.snapshot().relocationLeaves());

		CandidateRealizationInputBinding foreignOwner = relocationBinding(
			0, key("source"), foreignAction.relocationAction());
		List<List<CandidateRealizationInputBinding>> foreignOwnerChoices = List.of(List.of(foreignOwner));
		long beforeForeignOwner = metrics.snapshot().relocationLeaves();
		Assert.assertEquals(cold(emission, foreignOwnerChoices, output, false, null),
			product(builder, OWNER, emission, foreignOwnerChoices, output, false, null, current));
		Assert.assertEquals("equal-valued foreign source ownership is a new binding", 1,
			metrics.snapshot().relocationLeaves() - beforeForeignOwner);
	}

	@Test
	public void deltaSlicesPreserveRepeatedOwnerSourceConflictPruning() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder(null, metrics);
		CandidateEmissionFact emission = new CandidateEmissionFact(TARGET_EMISSION, FType.ROW);
		DurableAnchorKey output = anchor("output", 4);
		CandidateEmissionRealization local = CandidateEmissionRealization.local(SOURCE_EMISSION);
		PlacementEmissionState alternateSourceEmission = new PlacementEmissionState(
			new PlacementState(ExecType.FED, FederatedOutput.LOUT, FType.COL, false), false);
		CandidateEmissionRealization alternate = CandidateEmissionRealization.local(alternateSourceEmission);
		CandidateRealizationInputBinding localFirst = relocationBinding(
			0, SOURCE, local, relocationAction("pool-local-first"));
		CandidateRealizationInputBinding alternateFirst = relocationBinding(
			0, SOURCE, alternate, relocationAction("pool-alternate-first"));
		CandidateRealizationInputBinding localSecond = relocationBinding(
			1, SOURCE, local, relocationAction("pool-local-second"));
		CandidateRealizationInputBinding alternateSecond = relocationBinding(
			1, SOURCE, alternate, relocationAction("pool-alternate-second"));
		Map<Object,Object> previous = new HashMap<>();
		product(builder, OWNER, emission, List.of(List.of(localFirst), List.of(localSecond)),
			output, false, null, previous);
		Object relationClosure = PlacementBuilderTestAccess.relationClosure(builder);
		Field cache = PlacementRelationClosure.class.getDeclaredField("relocationProducts");
		cache.setAccessible(true);
		cache.set(relationClosure, previous);
		Map<Object,Object> current = new HashMap<>();
		List<List<CandidateRealizationInputBinding>> expanded = List.of(
			List.of(localFirst, alternateFirst), List.of(localSecond, alternateSecond));
		long before = metrics.snapshot().relocationLeaves();
		List<CandidateEmissionRealization> delta = product(builder, OWNER, emission,
			expanded, output, false, null, current);

		Assert.assertEquals(cold(emission, expanded, output, false, null), delta);
		Assert.assertEquals("only the compatible durable/durable assignment is newly emitted",
			1, metrics.snapshot().relocationLeaves() - before);
		Assert.assertEquals(2, delta.get(0).supportClauses().size());
	}

	@Test
	public void ownerAndOutputBranchIdentityCannotAlias() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder(null, metrics);
		Map<Object,Object> current = new HashMap<>();
		CandidateEmissionFact emission = new CandidateEmissionFact(TARGET_EMISSION, FType.ROW);
		List<List<CandidateRealizationInputBinding>> choices = List.of(
			List.of(relocationBinding(SOURCE, relocationAction("pool"))));
		DurableAnchorKey output = anchor("output", 4);
		List<CandidateEmissionRealization> baseline = product(builder, OWNER, emission,
			choices, output, false, null, current);
		long leaves = metrics.snapshot().relocationLeaves();
		Assert.assertNotSame(baseline, product(builder, key("owner"), emission,
			choices, output, false, null, current));
		Assert.assertTrue(metrics.snapshot().relocationLeaves() > leaves);
		assertMiss(builder, metrics, current, baseline,
			new CandidateEmissionFact(TARGET_EMISSION, FType.COL), choices, output, false, null);
		assertMiss(builder, metrics, current, baseline, emission, choices, output, true, null);
		CandidateEmissionFact local = new CandidateEmissionFact(SOURCE_EMISSION, FType.ROW);
		assertMiss(builder, metrics, current, baseline, local, choices, output, false, null);
	}

	@Test
	public void freshCarriedClausesAreNotPartOfTheMemoizedGeneratedContribution() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder(null, metrics);
		Map<Object,Object> current = new HashMap<>();
		DurableAnchorKey output = anchor("output", 4);
		PlacementProofKey oldProof = new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, OWNER, "old-carried");
		PlacementProofKey newProof = new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, OWNER, "new-carried");
		CandidateEmissionFact first = new CandidateEmissionFact(TARGET_EMISSION, FType.ROW, null,
			List.of(CandidateEmissionRealization.durable(TARGET_EMISSION, output, List.of(oldProof), List.of())));
		CandidateEmissionFact changed = new CandidateEmissionFact(TARGET_EMISSION, FType.ROW, null,
			List.of(CandidateEmissionRealization.durable(TARGET_EMISSION, output, List.of(newProof), List.of())));
		List<List<CandidateRealizationInputBinding>> choices = List.of(
			List.of(relocationBinding(SOURCE, relocationAction("pool"))));
		List<CandidateEmissionRealization> generated = product(builder, OWNER, first,
			choices, output, false, null, current);
		Assert.assertSame(generated, product(builder, OWNER, changed, choices, output, false, null, current));
		CandidateEmissionFact merged = mergeCarried(changed, generated);
		Assert.assertEquals(mergeCarried(changed, cold(changed, choices, output, false, null)), merged);
		Assert.assertTrue(merged.realizations().stream().flatMap(realization -> realization.supportClauses().stream())
			.anyMatch(clause -> clause.proofDependencies().contains(newProof)));
		Assert.assertFalse("old carried history must not leak out of the product cache",
			merged.realizations().stream().flatMap(realization -> realization.supportClauses().stream())
				.anyMatch(clause -> clause.proofDependencies().contains(oldProof)));
	}

	private static CandidateEmissionFact mergeCarried(CandidateEmissionFact emission,
		List<CandidateEmissionRealization> generated) {
		List<CandidateEmissionRealization> combined = new ArrayList<>(emission.realizations());
		combined.addAll(generated);
		return new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
			emission.derivedFoutAction(), combined);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> cold(CandidateEmissionFact emission,
		List<List<CandidateRealizationInputBinding>> choices, DurableAnchorKey output,
		boolean recomputes, DurableAnchorKey dynamic) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("generateRelocationBindingProduct",
			CompiledHopKey.class, CandidateEmissionFact.class, List.class, DurableAnchorKey.class,
			boolean.class, DurableAnchorKey.class, SearchSpaceMetrics.class);
		method.setAccessible(true);
		return (List<CandidateEmissionRealization>)method.invoke(null, OWNER, emission,
			choices, output, recomputes, dynamic, null);
	}

	@Test
	public void choiceAuthorityAndOutputGeometryRemainExactCacheBoundaries() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder(null, metrics);
		Map<Object,Object> currentProducts = new HashMap<>();
		CandidateEmissionFact emission = new CandidateEmissionFact(TARGET_EMISSION, FType.ROW);
		RelocationActionKey action = relocationAction("pool");
		CandidateRealizationInputBinding binding = relocationBinding(SOURCE, action);
		DurableAnchorKey output = anchor("output", 4);
		List<CandidateEmissionRealization> baseline = product(builder, OWNER, emission,
			List.of(List.of(binding)), output, false, null, currentProducts);

		CompiledHopKey equalButForeignSource = key("source");
		CandidateRealizationInputBinding changedSource = relocationBinding(equalButForeignSource, action);
		assertMiss(builder, metrics, currentProducts, baseline, emission,
			List.of(List.of(changedSource)), output, false, null);

		RelocationActionKey equalButForeignAction = relocationAction("pool");
		CandidateRealizationInputBinding changedAction = relocationBinding(SOURCE, equalButForeignAction);
		assertMiss(builder, metrics, currentProducts, baseline, emission,
			List.of(List.of(changedAction)), output, false, null);

		assertMiss(builder, metrics, currentProducts, baseline, emission,
			List.of(List.of(binding)), anchor("other-output", 4), false, null);
		assertMiss(builder, metrics, currentProducts, baseline, emission,
			List.of(List.of(binding)), anchor("output", 6), false, null);

		List<CandidateEmissionRealization> dynamic = product(builder, OWNER, emission,
			List.of(List.of(binding)), null, true, anchor("dynamic", 4), currentProducts);
		assertMiss(builder, metrics, currentProducts, dynamic, emission,
			List.of(List.of(binding)), null, true, anchor("other-dynamic", 4));
		assertMiss(builder, metrics, currentProducts, dynamic, emission,
			List.of(List.of(binding)), null, true, anchor("dynamic", 6));
	}

	@Test
	public void derivedActionAndExactOutputKeysRemainPartOfProductIdentity() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder(null, metrics);
		Map<Object,Object> currentProducts = new HashMap<>();
		CandidateRealizationInputBinding binding = relocationBinding(SOURCE, relocationAction("pool"));
		CandidateEmissionFact first = derivedEmission(derivedAction(anchor("derived-target", 4)), "derived-a");
		List<CandidateEmissionRealization> baseline = product(builder, OWNER, first,
			List.of(List.of(binding)), anchor("ignored-for-derived", 4), false, null, currentProducts);

		CandidateEmissionFact changedAction = derivedEmission(
			derivedAction(anchor("other-derived-target", 4)), "derived-a");
		assertMiss(builder, metrics, currentProducts, baseline, changedAction,
			List.of(List.of(binding)), anchor("ignored-for-derived", 4), false, null);

		CandidateEmissionFact changedOutput = derivedEmission(first.derivedFoutAction(), "derived-b");
		assertMiss(builder, metrics, currentProducts, baseline, changedOutput,
			List.of(List.of(binding)), anchor("ignored-for-derived", 4), false, null);
	}

	private static void assertMiss(NeutralPlacementGraphBuilder builder, SearchSpaceMetrics metrics,
		Map<Object,Object> currentProducts, List<CandidateEmissionRealization> baseline,
		CandidateEmissionFact emission, List<List<CandidateRealizationInputBinding>> choices,
		DurableAnchorKey output, boolean recomputesRanges, DurableAnchorKey dynamic) throws Exception {
		long before = metrics.snapshot().relocationLeaves();
		List<CandidateEmissionRealization> changed = product(builder, OWNER, emission,
			choices, output, recomputesRanges, dynamic, currentProducts);
		Assert.assertNotSame("a changed exact product key must not reuse a prior relation", baseline, changed);
		Assert.assertTrue("a cache miss must enumerate at least one new leaf",
			metrics.snapshot().relocationLeaves() > before);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> product(NeutralPlacementGraphBuilder builder,
		CompiledHopKey owner, CandidateEmissionFact emission,
		List<List<CandidateRealizationInputBinding>> choices, DurableAnchorKey output,
		boolean recomputesRanges, DurableAnchorKey dynamic, Map<Object,Object> currentProducts) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("relocationBindingProduct",
			CompiledHopKey.class, CandidateEmissionFact.class, List.class, DurableAnchorKey.class,
			boolean.class, DurableAnchorKey.class, Map.class);
		method.setAccessible(true);
		return (List<CandidateEmissionRealization>)method.invoke(
			PlacementBuilderTestAccess.relationClosure(builder), owner, emission, choices,
			output, recomputesRanges, dynamic, currentProducts);
	}

	private static CandidateEmissionFact derivedEmission(
		DerivedFoutMaterializationActionKey action, String outputId) {
		PlacementEmissionState state = new PlacementEmissionState(TARGET_STATE, true);
		CandidateEmissionRealization output = CandidateEmissionRealization.durable(
			state, anchor(outputId, 4), List.of(), List.of());
		return new CandidateEmissionFact(state, FType.ROW, action, List.of(output));
	}

	private static DerivedFoutMaterializationActionKey derivedAction(DurableAnchorKey target) {
		CandidateRuleKey rule = new CandidateRuleKey(OWNER,
			List.of(CandidateInputState.present(FType.ROW)));
		return new DerivedFoutMaterializationActionKey(OWNER, OWNER_VERSION, rule,
			SOURCE_STATE, TARGET_STATE, target, OWNER, FType.ROW, FType.ROW,
			REGION.normalizedSignature());
	}

	private static CandidateRealizationInputBinding relocationBinding(
		CompiledHopKey sourceOwner, RelocationActionKey action) {
		return relocationBinding(0, sourceOwner, action);
	}

	private static CandidateRealizationInputBinding relocationBinding(
		int inputPosition, CompiledHopKey sourceOwner, RelocationActionKey action) {
		CandidateEmissionRealization source = CandidateEmissionRealization.local(SOURCE_EMISSION);
		return relocationBinding(inputPosition, sourceOwner, source, action);
	}

	private static CandidateRealizationInputBinding relocationBinding(int inputPosition,
		CompiledHopKey sourceOwner, CandidateEmissionRealization source, RelocationActionKey action) {
		CandidateRuleKey rule = sourceOwner == SOURCE ? SOURCE_RULE : new CandidateRuleKey(sourceOwner, List.of());
		CandidateRealizationReference reference = CandidateRealizationReference.of(rule, source);
		return CandidateRealizationInputBinding.relocation(inputPosition, reference, action);
	}

	private static RelocationActionKey relocationAction(String poolId) {
		return new RelocationActionKey(SOURCE_VERSION, TARGET_STATE, FType.ROW,
			anchor(poolId, 4), REGION.normalizedSignature(), List.of(OWNER));
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("relocation-product", "main", "root", "compiled",
			REGION, id, id);
	}

	private static ValueVersionKey version(String id, int ordinal) {
		return new ValueVersionKey("relocation-product", id, REGION, ordinal,
			VersionKind.ORDINARY, List.of());
	}

	private static DurableAnchorKey anchor(String id, long split) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(split, 2L)),
			new AnchorPartition("localhost:1235", List.of(split, 0L), List.of(8L, 2L))));
	}
}
