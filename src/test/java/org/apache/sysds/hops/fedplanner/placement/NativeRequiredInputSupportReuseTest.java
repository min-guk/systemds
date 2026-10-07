/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class NativeRequiredInputSupportReuseTest {
	@After
	public void clearCaches() {
		PlacementIdentity.setActiveMetrics(null);
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
	}

	@Test
	public void exactClauseAcrossResolversDoesNotRepeatCanonicalSerialization() throws Exception {
		List<CandidateRealizationReference> sources = new ArrayList<>();
		for(int index = 0; index < 96; index++)
			sources.add(reference("source-" + index));
		java.util.Collections.shuffle(sources, new java.util.Random(71));
		CandidateRealizationSupportClause clause = clause(sources);
		List<CandidateRealizationReference> expected = legacy(clause);
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(0L);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try(PlacementAnalysis.CanonicalTextScope ignored = PlacementAnalysis.beginCanonicalTextScope(0, 0)) {
			List<CandidateRealizationReference> first = support(resolver(), clause);
			long cold = metrics.snapshot().signatureSerializations();
			Assert.assertTrue(cold > 0);
			List<CandidateRealizationReference> next = support(resolver(), clause);
			Assert.assertEquals("an identical immutable clause must not sort and serialize again",
				cold, metrics.snapshot().signatureSerializations());
			Assert.assertSame(first, next);
			Assert.assertEquals(expected, next);
			for(int index = 0; index < next.size(); index++)
				Assert.assertSame(expected.get(index), next.get(index));
			Assert.assertThrows(UnsupportedOperationException.class, () -> next.clear());
		}
	}

	@Test
	public void equalForeignOwnersRemainDistinctAndClausesDoNotShareEntries() throws Exception {
		CandidateRealizationReference first = reference("same");
		CandidateRealizationReference foreign = reference("same");
		Assert.assertEquals(first, foreign);
		Assert.assertNotSame(first.rule().parentOccurrence(), foreign.rule().parentOccurrence());
		CandidateRealizationReference duplicate = new CandidateRealizationReference(first.rule(), first.realization());
		CandidateRealizationSupportClause left = clause(List.of(first, foreign, duplicate));
		CandidateRealizationSupportClause right = clause(List.of(first, foreign, duplicate));
		List<CandidateRealizationReference> a = support(resolver(), left);
		List<CandidateRealizationReference> b = support(resolver(), right);
		Assert.assertEquals(2, a.size());
		Assert.assertSame(first, a.get(0));
		Assert.assertSame(foreign, a.get(1));
		Assert.assertEquals(legacy(left), a);
		Assert.assertNotSame(a, b);
		Assert.assertSame(a, support(resolver(), left));
		Assert.assertSame(b, support(resolver(), right));
	}

	@Test
	public void resetAndCompilerThreadsIsolateThePureListCache() throws Exception {
		CandidateRealizationSupportClause clause = clause(List.of(reference("a"), reference("b")));
		List<CandidateRealizationReference> first = support(resolver(), clause);
		PlacementIdentity.resetNormalizedSignatureCache();
		List<CandidateRealizationReference> reset = support(resolver(), clause);
		Assert.assertEquals(first, reset);
		Assert.assertNotSame(first, reset);
		AtomicReference<List<CandidateRealizationReference>> cold = new AtomicReference<>();
		AtomicReference<List<CandidateRealizationReference>> warm = new AtomicReference<>();
		AtomicReference<Throwable> error = new AtomicReference<>();
		Thread compiler = new Thread(() -> {
			try {
				cold.set(support(resolver(), clause));
				warm.set(support(resolver(), clause));
			}
			catch(Throwable failure) { error.set(failure); }
			finally { PlacementIdentity.resetNormalizedSignatureCache(); }
		});
		compiler.start();
		compiler.join();
		Assert.assertNull(error.get());
		Assert.assertEquals(reset, cold.get());
		Assert.assertNotSame(reset, cold.get());
		Assert.assertSame(cold.get(), warm.get());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void reusedInputListDoesNotRetainWithdrawnSourceAuthority() throws Exception {
		Method fixtureMethod = NativeValueMapFixedPoolContinuityTest.class.getDeclaredMethod("fixture", boolean.class);
		fixtureMethod.setAccessible(true);
		Object fixture = fixtureMethod.invoke(null, false);
		NativePlacementContinuity initial = (NativePlacementContinuity)fixtureValue(fixture, "continuity");
		CandidateRealizationReference reference =
			(CandidateRealizationReference)fixtureValue(fixture, "valueMap");
		DurableAnchorKey pool = (DurableAnchorKey)fixtureValue(fixture, "expectedPool");
		CompiledHopKey withdrawnOwner = (CompiledHopKey)fixtureValue(fixture, "secondLeaf");
		List<CandidateRuleFact> facts = (List<CandidateRuleFact>)fixtureValue(fixture, "facts");
		CandidateRealizationSupportClause clause = facts.stream()
			.filter(fact -> fact.key().parentOccurrence() == reference.rule().parentOccurrence())
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.flatMap(realization -> realization.supportClauses().stream())
			.filter(value -> value.inputBindings().stream().anyMatch(binding ->
				binding.source().rule().parentOccurrence() == withdrawnOwner))
			.findFirst().orElseThrow();
		List<CandidateRealizationReference> list = support(initial, clause);
		Assert.assertNotNull(initial.proveCandidate(reference, pool));
		NativePlacementContinuity revised = initial.nextRevision(facts);
		Assert.assertSame(list, support(revised, clause));
		Assert.assertSame(list, support(revised.freshQueryState(), clause));
		Assert.assertNotNull(revised.proveCandidate(reference, pool));

		NativePlacementContinuity withdrawn = revised.nextRevision(facts.stream()
			.filter(fact -> fact.key().parentOccurrence() != withdrawnOwner).toList());
		Assert.assertSame("pure source keys survive; their authority must be revalidated",
			list, support(withdrawn, clause));
		Assert.assertNull(withdrawn.fixedValueMapPool(reference));
		Assert.assertNull(withdrawn.proveCandidate(reference, pool));
		PlacementIdentity.resetNormalizedSignatureCache();
		NativePlacementContinuity cold = withdrawn.freshQueryState();
		Assert.assertNotSame(list, support(cold, clause));
		Assert.assertNull(cold.fixedValueMapPool(reference));
		Assert.assertEquals(cold.proveCandidateAlternatives(reference, pool),
			withdrawn.proveCandidateAlternatives(reference, pool));
	}

	private static Object fixtureValue(Object fixture, String name) throws Exception {
		Method getter = fixture.getClass().getDeclaredMethod(name);
		getter.setAccessible(true);
		return getter.invoke(fixture);
	}

	private static List<CandidateRealizationReference> legacy(CandidateRealizationSupportClause clause) {
		List<CandidateRealizationReference> result = new ArrayList<>();
		for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
			CandidateRealizationReference source = binding.source();
			if(result.stream().noneMatch(previous -> previous.rule().parentOccurrence()
				== source.rule().parentOccurrence() && previous.equals(source)))
				result.add(source);
		}
		result.sort(PlacementAnalysis.canonicalComparator());
		return List.copyOf(result);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationReference> support(NativePlacementContinuity resolver,
		CandidateRealizationSupportClause clause) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("requiredInputSupport",
			CandidateRealizationSupportClause.class);
		method.setAccessible(true);
		return (List<CandidateRealizationReference>)method.invoke(resolver, clause);
	}

	private static NativePlacementContinuity resolver() {
		return new NativePlacementContinuity(Map.of(), Map.of(), List.of(), List.of(), Map.of());
	}

	private static CandidateRealizationSupportClause clause(List<CandidateRealizationReference> sources) {
		List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
		for(int index = 0; index < sources.size(); index++)
			bindings.add(CandidateRealizationInputBinding.direct(index, sources.get(index)));
		return new CandidateRealizationSupportClause(List.of(), bindings);
	}

	private static CandidateRealizationReference reference(String name) {
		ControlRegionKey region = new ControlRegionKey("native-support", "main", List.of("root"),
			"root", "compiled");
		CompiledHopKey owner = new CompiledHopKey("native-support", "main", "root", "compiled",
			region, name, name);
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of(CandidateInputState.present(FType.ROW)));
		PlacementEmissionState state = new PlacementEmissionState(
			new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
		DurableAnchorKey anchor = new DurableAnchorKey("shared-anchor", FType.ROW,
			List.of(new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(4L, 2L))));
		return CandidateRealizationReference.of(rule,
			CandidateEmissionRealization.durable(state, anchor, List.of(), List.of()));
	}
}
