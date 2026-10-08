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
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Invocation-local memoization contract for completed root worker-count proofs. */
public class ExactRootWorkerCountMemoTest {
	private static final PlacementState STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(STATE, false);

	private record MemoProbe(List<Integer> counts, int cachedRoots, int cachedSources,
		int sourceAggregateComputations, List<Integer> resolverCallsAfterEach) {
	}

	@Test
	public void repeatedRootsMatchUncachedCycleAndSharedSupportResults() throws Exception {
		CandidateRuleKey aRule = rule("cycle-a");
		CandidateRuleKey bRule = rule("cycle-b");
		CandidateEmissionRealization aKey = valueMap("cycle-a", unknownClause());
		CandidateEmissionRealization bKey = valueMap("cycle-b", unknownClause());
		CandidateEmissionRealization a = valueMap("cycle-a", clause(ref(bRule, bKey)));
		CandidateEmissionRealization b = valueMap("cycle-b", clause(ref(aRule, aKey)));

		CandidateRuleKey seedRule = rule("shared-seed");
		CandidateRuleKey sharedRule = rule("shared-support");
		CandidateRuleKey rootRule = rule("shared-root");
		CandidateEmissionRealization seed = durable("shared-seed", 2);
		CandidateEmissionRealization sharedKey = valueMap("shared-support", unknownClause());
		CandidateEmissionRealization shared = valueMap("shared-support", clause(ref(seedRule, seed)));
		CandidateEmissionRealization root = valueMap("shared-root", clause(ref(sharedRule, sharedKey)));

		Map<CandidateRealizationReference,CandidateEmissionRealization> realizations = new java.util.HashMap<>();
		realizations.put(ref(aRule, a), a);
		realizations.put(ref(bRule, b), b);
		realizations.put(ref(seedRule, seed), seed);
		realizations.put(ref(sharedRule, shared), shared);
		realizations.put(ref(rootRule, root), root);
		PlacementAnalysis analysis = mock(PlacementAnalysis.class);
		when(analysis.requireExactCandidateRealization(any())).thenAnswer(invocation ->
			realizations.get((CandidateRealizationReference)invocation.getArgument(0)));

		ExactPhysicalModel.Alternative cycle = alternative(aRule, a, "cycle-root");
		ExactPhysicalModel.Alternative sharedRoot = alternative(rootRule, root, "shared-root");
		ExactPhysicalModel.Alternative equalButDistinct = alternative(rootRule, root, "shared-root");
		Assert.assertEquals(sharedRoot, equalButDistinct);
		Assert.assertNotSame(sharedRoot, equalButDistinct);
		List<ExactPhysicalModel.Alternative> requests =
			List.of(cycle, cycle, sharedRoot, sharedRoot, equalButDistinct);
		List<Integer> fallbacks = List.of(3, 7, 9, 11, 13);

		List<Integer> uncached = new ArrayList<>();
		for(int index = 0; index < requests.size(); index++)
			uncached.add(ExactPhysicalCostModel.realizationWorkerCount(
				analysis, requests.get(index), fallbacks.get(index)));
		clearInvocations(analysis);
		MemoProbe memoized = memoProbe(analysis, requests, fallbacks);

		Assert.assertEquals(List.of(3, 7, 2, 2, 2), uncached);
		Assert.assertEquals("memoization must not change fallback-sensitive cycle results",
			uncached, memoized.counts());
		Assert.assertEquals("two repeated root identities compute once; an equal foreign identity computes separately",
			3, memoized.cachedRoots());
		Assert.assertEquals("cache hits perform no recursive realization lookup",
			List.of(2, 2, 4, 4, 5), memoized.resolverCallsAfterEach());
		Assert.assertEquals(1, memoized.cachedSources());
		Assert.assertEquals("cyclic aggregates are recomputed while the shared acyclic source is computed once",
			3, memoized.sourceAggregateComputations());
	}

	@Test
	public void reconvergentFanoutComputesEachAcyclicSourceAggregateOnce() throws Exception {
		CandidateRuleKey seedRule = rule("fanout-seed");
		CandidateEmissionRealization seed = durable("fanout-seed", 4);
		Map<CandidateRealizationReference,CandidateEmissionRealization> realizations =
			new java.util.LinkedHashMap<>();
		realizations.put(ref(seedRule, seed), seed);
		CandidateEmissionRealization child = seed;
		CandidateRuleKey childRule = seedRule;
		int levels = 14;
		for(int level = 0; level < levels; level++) {
			CandidateRuleKey parentRule = rule("fanout-" + level);
			CandidateEmissionRealization parent = valueMap("fanout-" + level,
				clause(ref(childRule, child), ref(childRule, child)));
			realizations.put(ref(parentRule, parent), parent);
			child = parent;
			childRule = parentRule;
		}
		PlacementAnalysis analysis = mock(PlacementAnalysis.class);
		when(analysis.requireExactCandidateRealization(any())).thenAnswer(invocation ->
			realizations.get((CandidateRealizationReference)invocation.getArgument(0)));
		ExactPhysicalModel.Alternative root = alternative(childRule, child, "fanout-root");

		Assert.assertEquals(4, explicitReferenceCount(analysis, root, 9));
		long explicitResolverCalls = resolverCalls(analysis);
		clearInvocations(analysis);
		MemoProbe memoized = memoProbe(analysis, List.of(root), List.of(9));

		Assert.assertEquals(List.of(4), memoized.counts());
		Assert.assertEquals("one aggregate per non-root DAG level", levels - 1,
			memoized.sourceAggregateComputations());
		Assert.assertEquals(levels - 1, memoized.cachedSources());
		Assert.assertTrue("incoming edges still resolve exact authority while recursive expansion is linear",
			memoized.resolverCallsAfterEach().get(0) <= levels * 2);
		Assert.assertTrue("memoized traversal must collapse the explicit exponential re-walk",
			explicitResolverCalls > 100L * memoized.resolverCallsAfterEach().get(0));
	}

	@Test
	public void completedDescendantCacheDoesNotMaskLaterCycle() throws Exception {
		CandidateRuleKey seedRule = rule("mixed-seed");
		CandidateEmissionRealization seed = durable("mixed-seed", 3);
		CandidateRuleKey sharedRule = rule("mixed-shared");
		CandidateEmissionRealization shared = valueMap("mixed-shared", clause(ref(seedRule, seed)));
		CandidateRealizationReference sharedRef = ref(sharedRule, shared);
		CandidateRuleKey aRule = rule("mixed-a");
		CandidateRuleKey bRule = rule("mixed-b");
		CandidateEmissionRealization aKey = valueMap("mixed-a", unknownClause());
		CandidateEmissionRealization bKey = valueMap("mixed-b", unknownClause());
		CandidateEmissionRealization a = valueMap("mixed-a",
			clause(ref(bRule, bKey), sharedRef));
		CandidateEmissionRealization b = valueMap("mixed-b", clause(ref(aRule, aKey)));
		Map<CandidateRealizationReference,CandidateEmissionRealization> realizations = new java.util.HashMap<>();
		realizations.put(ref(seedRule, seed), seed);
		realizations.put(sharedRef, shared);
		realizations.put(ref(aRule, a), a);
		realizations.put(ref(bRule, b), b);
		PlacementAnalysis analysis = mock(PlacementAnalysis.class);
		when(analysis.requireExactCandidateRealization(any())).thenAnswer(invocation ->
			realizations.get((CandidateRealizationReference)invocation.getArgument(0)));
		ExactPhysicalModel.Alternative sharedRoot = alternative(sharedRule, shared, "shared-first");
		ExactPhysicalModel.Alternative cyclicRoot = alternative(aRule, a, "cycle-second");

		List<Integer> explicit = List.of(
			explicitReferenceCount(analysis, sharedRoot, 8),
			explicitReferenceCount(analysis, cyclicRoot, 11));
		clearInvocations(analysis);
		MemoProbe memoized = memoProbe(analysis,
			List.of(sharedRoot, cyclicRoot), List.of(8, 11));

		Assert.assertEquals(List.of(3, 3), explicit);
		Assert.assertEquals(explicit, memoized.counts());
		Assert.assertEquals("only the completed shared descendant is reusable", 1, memoized.cachedSources());
	}

	@Test
	public void ambiguousZeroIsCachedBeforePerRootFallback() throws Exception {
		CandidateRuleKey twoRule = rule("ambiguous-two");
		CandidateRuleKey fiveRule = rule("ambiguous-five");
		CandidateEmissionRealization two = durable("ambiguous-two", 2);
		CandidateEmissionRealization five = durable("ambiguous-five", 5);
		CandidateRuleKey ambiguousRule = rule("ambiguous-source");
		CandidateEmissionRealization ambiguous = CandidateEmissionRealization.valueMap(EMISSION,
			"ambiguous-source", List.of(clause(ref(twoRule, two)), clause(ref(fiveRule, five))));
		CandidateRealizationReference ambiguousRef = ref(ambiguousRule, ambiguous);
		Map<CandidateRealizationReference,CandidateEmissionRealization> realizations = Map.of(
			ref(twoRule, two), two, ref(fiveRule, five), five, ambiguousRef, ambiguous);
		PlacementAnalysis analysis = mock(PlacementAnalysis.class);
		when(analysis.requireExactCandidateRealization(any())).thenAnswer(invocation ->
			realizations.get((CandidateRealizationReference)invocation.getArgument(0)));
		CandidateRuleKey rootRule = rule("ambiguous-root");
		CandidateEmissionRealization root = valueMap("ambiguous-root", clause(ambiguousRef));
		ExactPhysicalModel.Alternative first = alternative(rootRule, root, "ambiguous-first");
		ExactPhysicalModel.Alternative second = alternative(rootRule, root, "ambiguous-second");

		MemoProbe memoized = memoProbe(analysis, List.of(first, second), List.of(7, 13));

		Assert.assertEquals(List.of(7, 13), memoized.counts());
		Assert.assertEquals(1, memoized.sourceAggregateComputations());
		Assert.assertEquals(1, memoized.cachedSources());
	}

	@Test
	public void structurallyEqualForeignOwnersNeverShareSourceAggregate() throws Exception {
		CandidateRuleKey twoSeedRule = rule("identity-two-seed");
		CandidateRuleKey fiveSeedRule = rule("identity-five-seed");
		CandidateEmissionRealization twoSeed = durable("identity-two-seed", 2);
		CandidateEmissionRealization fiveSeed = durable("identity-five-seed", 5);
		CandidateRuleKey ownerA = rule("identity-equal-owner");
		CandidateRuleKey ownerB = rule("identity-equal-owner");
		Assert.assertEquals(ownerA.parentOccurrence(), ownerB.parentOccurrence());
		Assert.assertNotSame(ownerA.parentOccurrence(), ownerB.parentOccurrence());
		CandidateEmissionRealization sourceA = valueMap(
			"identity-equal-source", clause(ref(twoSeedRule, twoSeed)));
		CandidateEmissionRealization sourceB = valueMap(
			"identity-equal-source", clause(ref(fiveSeedRule, fiveSeed)));
		CandidateRealizationReference refA = ref(ownerA, sourceA);
		CandidateRealizationReference refB = ref(ownerB, sourceB);
		Assert.assertEquals("the adversary must collide under structural reference equality", refA, refB);
		PlacementAnalysis analysis = mock(PlacementAnalysis.class);
		when(analysis.requireExactCandidateRealization(any())).thenAnswer(invocation -> {
			CandidateRealizationReference requested = invocation.getArgument(0);
			if(requested.rule().parentOccurrence() == ownerA.parentOccurrence())
				return sourceA;
			if(requested.rule().parentOccurrence() == ownerB.parentOccurrence())
				return sourceB;
			if(requested.rule().parentOccurrence() == twoSeedRule.parentOccurrence())
				return twoSeed;
			if(requested.rule().parentOccurrence() == fiveSeedRule.parentOccurrence())
				return fiveSeed;
			throw new AssertionError("foreign source owner");
		});
		CandidateRuleKey rootRule = rule("identity-root");
		CandidateEmissionRealization root = valueMap(
			"identity-root", clause(refA, refB));
		ExactPhysicalModel.Alternative alternative = alternative(rootRule, root, "identity-root");

		Assert.assertEquals(17, explicitReferenceCount(analysis, alternative, 17));
		clearInvocations(analysis);
		MemoProbe memoized = memoProbe(analysis, List.of(alternative), List.of(17));

		Assert.assertEquals(List.of(17), memoized.counts());
		Assert.assertEquals("identity-distinct owners retain both exact source aggregates",
			2, memoized.cachedSources());
	}

	@Test
	public void sourceMemoHasAHardEntryBound() throws Exception {
		CandidateRuleKey seedRule = rule("bound-seed");
		CandidateEmissionRealization seed = durable("bound-seed", 2);
		Map<CandidateRealizationReference,CandidateEmissionRealization> realizations =
			new java.util.LinkedHashMap<>();
		realizations.put(ref(seedRule, seed), seed);
		List<CandidateRealizationReference> sources = new ArrayList<>();
		for(int index = 0; index < 4100; index++) {
			CandidateRuleKey sourceRule = rule("bound-source-" + index);
			CandidateEmissionRealization source = valueMap("bound-source-" + index,
				clause(ref(seedRule, seed)));
			CandidateRealizationReference sourceRef = ref(sourceRule, source);
			realizations.put(sourceRef, source);
			sources.add(sourceRef);
		}
		PlacementAnalysis analysis = mock(PlacementAnalysis.class);
		when(analysis.requireExactCandidateRealization(any())).thenAnswer(invocation ->
			realizations.get((CandidateRealizationReference)invocation.getArgument(0)));
		CandidateRuleKey rootRule = rule("bound-root");
		CandidateEmissionRealization root = valueMap("bound-root",
			clause(sources.toArray(CandidateRealizationReference[]::new)));

		MemoProbe memoized = memoProbe(analysis,
			List.of(alternative(rootRule, root, "bound-root")), List.of(9));

		Assert.assertEquals(List.of(2), memoized.counts());
		Assert.assertEquals(4100, memoized.sourceAggregateComputations());
		Assert.assertEquals(4096, memoized.cachedSources());
	}

	@Test
	public void fixedSeedDagMatchesExplicitReferenceAcrossClausesAndFallbacks() throws Exception {
		java.util.Random random = new java.util.Random(0x5eedC0DEL);
		Map<CandidateRealizationReference,CandidateEmissionRealization> realizations =
			new java.util.LinkedHashMap<>();
		List<CandidateRuleKey> rules = new ArrayList<>();
		List<CandidateEmissionRealization> nodes = new ArrayList<>();
		for(int index = 0; index < 48; index++) {
			CandidateRuleKey nodeRule = rule("random-" + index);
			CandidateEmissionRealization node;
			if(index < 4 || random.nextInt(5) == 0)
				node = durable("random-" + index, 1 + random.nextInt(5));
			else {
				Set<CandidateRealizationSupportClause> uniqueClauses = new LinkedHashSet<>();
				int clauseCount = 1 + random.nextInt(3);
				for(int attempt = 0; uniqueClauses.size() < clauseCount && attempt < 12; attempt++) {
					int sourceCount = random.nextInt(4);
					CandidateRealizationReference[] sources = new CandidateRealizationReference[sourceCount];
					for(int sourceIndex = 0; sourceIndex < sourceCount; sourceIndex++) {
						int selected = random.nextInt(index);
						sources[sourceIndex] = ref(rules.get(selected), nodes.get(selected));
					}
					uniqueClauses.add(clause(sources));
				}
				node = CandidateEmissionRealization.valueMap(
					EMISSION, "random-" + index, List.copyOf(uniqueClauses));
			}
			rules.add(nodeRule);
			nodes.add(node);
			realizations.put(ref(nodeRule, node), node);
		}
		PlacementAnalysis analysis = mock(PlacementAnalysis.class);
		when(analysis.requireExactCandidateRealization(any())).thenAnswer(invocation ->
			realizations.get((CandidateRealizationReference)invocation.getArgument(0)));
		List<ExactPhysicalModel.Alternative> alternatives = new ArrayList<>();
		List<Integer> fallbacks = new ArrayList<>();
		List<Integer> expected = new ArrayList<>();
		for(int index = 4; index < nodes.size(); index++) {
			CandidateEmissionRealization node = nodes.get(index);
			if(node.anchor() != null)
				continue;
			int fallback = 7 + index;
			ExactPhysicalModel.Alternative alternative = alternative(
				rules.get(index), node, "random-root-" + index);
			alternatives.add(alternative);
			fallbacks.add(fallback);
			expected.add(explicitReferenceCount(analysis, alternative, fallback));
		}
		clearInvocations(analysis);

		MemoProbe memoized = memoProbe(analysis, alternatives, fallbacks);

		Assert.assertEquals(expected, memoized.counts());
		Assert.assertTrue("fixture must reuse completed intermediate sources",
			memoized.cachedSources() > 0);
	}

	@Test
	public void memoStateDoesNotEscapeOneCostInvocation() throws Exception {
		CandidateRuleKey seedRule = rule("scope-seed");
		CandidateRuleKey rootRule = rule("scope-root");
		CandidateEmissionRealization seed = durable("scope-seed", 3);
		CandidateEmissionRealization root = valueMap("scope-root", clause(ref(seedRule, seed)));
		PlacementAnalysis analysis = mock(PlacementAnalysis.class);
		when(analysis.requireExactCandidateRealization(any())).thenReturn(seed);
		ExactPhysicalModel.Alternative alternative = alternative(rootRule, root, "scope-root");

		var first = memoProbe(analysis, List.of(alternative, alternative), List.of(8, 9));
		var second = memoProbe(analysis, List.of(alternative), List.of(10));
		Assert.assertEquals(List.of(3, 3), first.counts());
		Assert.assertEquals(1, first.cachedRoots());
		Assert.assertEquals(List.of(3), second.counts());
		Assert.assertEquals("a new cost invocation owns a new memo", 1, second.cachedRoots());
	}

	private static MemoProbe memoProbe(PlacementAnalysis analysis,
		List<ExactPhysicalModel.Alternative> alternatives, List<Integer> fallbacks) throws Exception {
		Class<?> cacheType = Class.forName(ExactPhysicalCostModel.class.getName() + "$PhysicalWorkerCounts");
		Constructor<?> constructor = cacheType.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object cache = constructor.newInstance();
		Method count = ExactPhysicalCostModel.class.getDeclaredMethod("realizationWorkerCount",
			PlacementAnalysis.class, ExactPhysicalModel.Alternative.class, int.class, cacheType);
		count.setAccessible(true);
		List<Integer> counts = new ArrayList<>();
		List<Integer> resolverCalls = new ArrayList<>();
		for(int index = 0; index < alternatives.size(); index++) {
			counts.add((Integer)count.invoke(
				null, analysis, alternatives.get(index), fallbacks.get(index), cache));
			resolverCalls.add((int)resolverCalls(analysis));
		}
		Field roots = cacheType.getDeclaredField("exactByRoot");
		roots.setAccessible(true);
		Field sourceEntries = cacheType.getDeclaredField("sourceExactEntries");
		sourceEntries.setAccessible(true);
		Field computations = cacheType.getDeclaredField("sourceAggregateComputations");
		computations.setAccessible(true);
		return new MemoProbe(counts, ((Map<?,?>)roots.get(cache)).size(),
			sourceEntries.getInt(cache), computations.getInt(cache), resolverCalls);
	}

	private static long resolverCalls(PlacementAnalysis analysis) {
		return mockingDetails(analysis).getInvocations().stream()
			.filter(invocation -> invocation.getMethod().getName()
				.equals("requireExactCandidateRealization")).count();
	}

	private static int explicitReferenceCount(PlacementAnalysis analysis,
		ExactPhysicalModel.Alternative alternative, int fallback) {
		int exact = explicitReferenceCount(analysis, alternative.realization(),
			alternative.supportClause(), new LinkedHashSet<>());
		return exact > 0 ? exact : fallback;
	}

	private static int explicitReferenceCount(PlacementAnalysis analysis,
		CandidateEmissionRealization realization, CandidateRealizationSupportClause selectedClause,
		Set<CandidateRealizationReference> visiting) {
		if(realization.anchor() != null)
			return realization.anchor().partitions().stream().map(AnchorPartition::workerId).distinct().toList().size();
		Set<Integer> counts = new LinkedHashSet<>();
		for(var binding : selectedClause.inputBindings()) {
			if(binding.source().realization().emissionState().placementState().output() != FederatedOutput.FOUT
				|| !visiting.add(binding.source()))
				continue;
			CandidateEmissionRealization source = analysis.requireExactCandidateRealization(binding.source());
			Set<Integer> sourceCounts = new LinkedHashSet<>();
			for(var clause : source.supportClauses())
				sourceCounts.add(explicitReferenceCount(analysis, source, clause, visiting));
			visiting.remove(binding.source());
			int count = sourceCounts.size() == 1 ? sourceCounts.iterator().next() : 0;
			if(count > 0)
				counts.add(count);
		}
		return counts.size() == 1 ? counts.iterator().next() : 0;
	}

	private static ExactPhysicalModel.Alternative alternative(CandidateRuleKey rule,
		CandidateEmissionRealization realization, String signature) {
		return new ExactPhysicalModel.Alternative(rule.parentOccurrence(), STATE,
			ExactPhysicalModel.AuthorityKind.CAPTURED_RULE, null, null, null, null,
			null, null, null, rule.orderedInputs(), List.of(), realization,
			realization.supportClauses().get(0), signature);
	}

	private static CandidateEmissionRealization durable(String id, int workers) {
		List<AnchorPartition> partitions = java.util.stream.IntStream.range(0, workers)
			.mapToObj(worker -> new AnchorPartition("worker-" + worker,
				List.of((long)worker, 0L), List.of((long)worker + 1, 3L))).toList();
		return CandidateEmissionRealization.durable(EMISSION,
			new DurableAnchorKey(id, FType.FULL, partitions), List.of(), List.of());
	}

	private static CandidateEmissionRealization valueMap(String id,
		CandidateRealizationSupportClause clause) {
		return CandidateEmissionRealization.valueMap(EMISSION, id, List.of(clause));
	}

	private static CandidateRealizationSupportClause unknownClause() {
		return new CandidateRealizationSupportClause(List.of(), List.of());
	}

	private static CandidateRealizationSupportClause clause(CandidateRealizationReference... sources) {
		return new CandidateRealizationSupportClause(List.of(),
			java.util.stream.IntStream.range(0, sources.length)
				.mapToObj(index -> CandidateRealizationInputBinding.direct(index, sources[index])).toList());
	}

	private static CandidateRealizationReference ref(CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		return CandidateRealizationReference.of(rule, realization);
	}

	private static CandidateRuleKey rule(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"root-worker-count", "main", List.of("main"), "main", "compiled");
		CompiledHopKey owner = new CompiledHopKey("root-worker-count", "main", "main",
			"compiled", region, name, name);
		return new CandidateRuleKey(owner, List.of(CandidateInputState.present(FType.FULL)));
	}
}
