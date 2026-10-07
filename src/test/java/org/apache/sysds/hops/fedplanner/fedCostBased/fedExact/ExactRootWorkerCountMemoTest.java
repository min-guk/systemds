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
import java.util.List;
import java.util.Map;

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

	private record MemoProbe(List<Integer> counts, int cachedRoots,
		List<Integer> resolverCallsAfterEach) {
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
			List.of(2, 2, 4, 4, 6), memoized.resolverCallsAfterEach());
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
			resolverCalls.add((int)mockingDetails(analysis).getInvocations().stream()
				.filter(invocation -> invocation.getMethod().getName()
					.equals("requireExactCandidateRealization")).count());
		}
		Field roots = cacheType.getDeclaredField("exactByRoot");
		roots.setAccessible(true);
		return new MemoProbe(counts, ((Map<?,?>)roots.get(cache)).size(), resolverCalls);
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

	private static CandidateRealizationSupportClause clause(CandidateRealizationReference source) {
		return new CandidateRealizationSupportClause(List.of(),
			List.of(CandidateRealizationInputBinding.direct(0, source)));
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
