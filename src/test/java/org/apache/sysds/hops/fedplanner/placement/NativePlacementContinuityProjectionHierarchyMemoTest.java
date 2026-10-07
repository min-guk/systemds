/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Identity hierarchy reuse checks; this is not a planner acceptance fixture. */
public class NativePlacementContinuityProjectionHierarchyMemoTest {
	private static final PlacementEmissionState LOCAL = new PlacementEmissionState(
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false);
	private static final PlacementEmissionState FED = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@Test
	public void changedFactReusesEveryUnaffectedIdentityLayerAndMatchesColdOracle() throws Exception {
		Fixture fixture = fixture("layers");
		NativePlacementContinuity before = resolver(fixture.before);
		NativePlacementContinuity after = resolver(fixture.after);
		assertOwnedMatchesCold(before, fixture.before, true, null);
		assertOwnedMatchesCold(after, fixture.after, true, before);

		Object beforeFact = factMemo(before, fixture.before);
		Object afterFact = factMemo(after, fixture.after);
		Object beforeStableEmission = child(beforeFact, "emissions", fixture.stableEmission);
		Object afterStableEmission = child(afterFact, "emissions", fixture.stableEmission);
		Assert.assertSame("an exact retained emission carries its complete descendant memo",
			beforeStableEmission, afterStableEmission);

		Object beforeChangedEmission = child(beforeFact, "emissions", fixture.beforeChangedEmission);
		Object afterChangedEmission = child(afterFact, "emissions", fixture.afterChangedEmission);
		Assert.assertNotSame(beforeChangedEmission, afterChangedEmission);
		Object beforeRealization = child(beforeChangedEmission, "realizations", fixture.beforeRealization);
		Object afterRealization = child(afterChangedEmission, "realizations", fixture.afterRealization);
		Assert.assertNotSame(beforeRealization, afterRealization);
		Assert.assertSame("an exact retained clause projection is shared",
			child(beforeRealization, "clauses", fixture.stableClause),
			child(afterRealization, "clauses", fixture.stableClause));
		Assert.assertNotSame(child(beforeRealization, "clauses", fixture.beforeChangedClause),
			child(afterRealization, "clauses", fixture.afterChangedClause));
		Assert.assertSame("a retained binding wrapper survives clause reconstruction",
			child(beforeRealization, "bindings", fixture.stableBinding),
			child(afterRealization, "bindings", fixture.stableBinding));
	}

	@Test
	public void generatedAndPublishedModesRemainSeparateAcrossReconstructedParents() throws Exception {
		Fixture fixture = fixture("modes");
		NativePlacementContinuity before = resolver(fixture.before);
		NativePlacementContinuity after = resolver(fixture.after);
		Set<?> beforeGenerated = owned(before, fixture.before, false, null);
		Set<?> afterGenerated = owned(after, fixture.after, false, before);
		Assert.assertEquals(beforeGenerated, afterGenerated);
		Object beforeEmissionMemo = child(factMemo(before, fixture.before), "emissions",
			fixture.beforeChangedEmission);
		Object afterEmissionMemo = child(factMemo(after, fixture.after), "emissions",
			fixture.afterChangedEmission);
		Assert.assertSame(field(beforeEmissionMemo, "generatedRoot"),
			field(afterEmissionMemo, "generatedRoot"));
		Assert.assertNull(field(afterEmissionMemo, "published"));

		assertOwnedMatchesCold(after, fixture.after, true, before);
		Assert.assertNotNull(field(afterEmissionMemo, "published"));
		Assert.assertNotSame(field(afterEmissionMemo, "generatedRoot"),
			field(afterEmissionMemo, "published"));
	}

	@Test
	public void equalForeignSourceOwnerCannotReuseBindingAuthority() throws Exception {
		CompiledHopKey owner = key("foreign-reader", 20);
		CompiledHopKey source = key("source", 21);
		CompiledHopKey foreign = key("source", 21);
		Assert.assertEquals(source, foreign);
		Assert.assertNotSame(source, foreign);
		CandidateRealizationInputBinding beforeBinding = direct(0, source, "source");
		CandidateRealizationInputBinding afterBinding = direct(0, foreign, "source");
		CandidateRuleFact beforeFact = fact(owner, "before", List.of(valueEmission("map",
			new CandidateRealizationSupportClause(List.of(), List.of(beforeBinding)))));
		CandidateRuleFact afterFact = fact(owner, "after", List.of(valueEmission("map",
			new CandidateRealizationSupportClause(List.of(), List.of(afterBinding)))));
		NativePlacementContinuity before = resolver(beforeFact);
		NativePlacementContinuity after = resolver(afterFact);
		Set<?> beforeProjection = owned(before, beforeFact, true, null);
		Set<?> afterProjection = owned(after, afterFact, true, before);
		Assert.assertNotEquals(beforeProjection, afterProjection);
		Assert.assertEquals(cold(afterFact, true), afterProjection);
		Object beforeRealization = onlyRealizationMemo(factMemo(before, beforeFact));
		Object afterRealization = onlyRealizationMemo(factMemo(after, afterFact));
		Assert.assertNotSame(child(beforeRealization, "bindings", beforeBinding),
			child(afterRealization, "bindings", afterBinding));
	}

	@Test
	public void lateDonorAndAlternatingChurnRetainOnlyCurrentInventory() throws Exception {
		Fixture fixture = fixture("lifecycle");
		NativePlacementContinuity first = resolver(fixture.before);
		NativePlacementContinuity second = first.nextRevision(List.of(fixture.after));
		owned(first, fixture.before, true, null);
		owned(second, fixture.after, true, first);
		Assert.assertFalse(identityContains(emissionMemos(factMemo(second, fixture.after)),
			fixture.beforeChangedEmission));
		Assert.assertTrue(identityContains(emissionMemos(factMemo(second, fixture.after)),
			fixture.afterChangedEmission));
		Object secondRealization = child(child(factMemo(second, fixture.after), "emissions",
			fixture.afterChangedEmission), "realizations", fixture.afterRealization);
		Assert.assertFalse(identityContains(children(secondRealization, "bindings"),
			fixture.replacedBinding));
		Assert.assertTrue(identityContains(children(secondRealization, "bindings"),
			fixture.replacementBinding));

		NativePlacementContinuity third = second.nextRevision(List.of(fixture.before));
		owned(third, fixture.before, true, second);
		Assert.assertFalse(identityContains(emissionMemos(factMemo(third, fixture.before)),
			fixture.afterChangedEmission));
		Assert.assertTrue(identityContains(emissionMemos(factMemo(third, fixture.before)),
			fixture.beforeChangedEmission));
		Object thirdRealization = child(child(factMemo(third, fixture.before), "emissions",
			fixture.beforeChangedEmission), "realizations", fixture.beforeRealization);
		Assert.assertSame("stable clause reuse survives alternating parent reconstruction",
			child(secondRealization, "clauses", fixture.stableClause),
			child(thirdRealization, "clauses", fixture.stableClause));
		Assert.assertSame("stable binding reuse survives alternating clause reconstruction",
			child(secondRealization, "bindings", fixture.stableBinding),
			child(thirdRealization, "bindings", fixture.stableBinding));
		Assert.assertTrue(identityContains(children(thirdRealization, "bindings"),
			fixture.replacedBinding));
		Assert.assertFalse(identityContains(children(thirdRealization, "bindings"),
			fixture.replacementBinding));
		Assert.assertEquals(cold(fixture.before, true), owned(third, fixture.before, true, second));

		NativePlacementContinuity fresh = third.freshQueryState();
		Assert.assertTrue(projectionMemos(fresh).isEmpty());
		owned(fresh, fixture.before, true, null);
		Assert.assertNotSame(factMemo(third, fixture.before), factMemo(fresh, fixture.before));
	}

	@Test
	public void ambiguousRuleRowAndEqualForeignOwnerFailClosedToColdProjection() throws Exception {
		CompiledHopKey owner = key("ambiguous-owner", 50);
		CandidateEmissionFact firstEmission = valueEmission("first",
			new CandidateRealizationSupportClause(List.of(), List.of(
				direct(0, key("first-source", 51), "first"))));
		CandidateEmissionFact secondEmission = valueEmission("second",
			new CandidateRealizationSupportClause(List.of(), List.of(
				direct(0, key("second-source", 52), "second"))));
		CandidateRuleFact first = fact(owner, "first", List.of(firstEmission));
		CandidateRuleFact second = fact(owner, "second", List.of(secondEmission));
		NativePlacementContinuity ambiguous = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(first, second), List.of(), Map.of());
		owned(ambiguous, first, true, null);
		owned(ambiguous, second, true, null);

		CandidateRuleFact rebuilt = fact(owner, "rebuilt", List.of(firstEmission));
		NativePlacementContinuity after = resolver(rebuilt);
		assertOwnedMatchesCold(after, rebuilt, true, ambiguous);
		Assert.assertNotSame("an ambiguous donor row cannot select one child hierarchy",
			child(factMemo(ambiguous, first), "emissions", firstEmission),
			child(factMemo(after, rebuilt), "emissions", firstEmission));

		CompiledHopKey equalForeignOwner = key("ambiguous-owner", 50);
		Assert.assertEquals(owner, equalForeignOwner);
		Assert.assertNotSame(owner, equalForeignOwner);
		CandidateRuleFact foreign = fact(equalForeignOwner, "foreign", List.of(firstEmission));
		NativePlacementContinuity foreignResolver = resolver(foreign);
		assertOwnedMatchesCold(foreignResolver, foreign, true, after);
		Assert.assertNotSame("equal structural owners cannot cross the identity boundary",
			child(factMemo(after, rebuilt), "emissions", firstEmission),
			child(factMemo(foreignResolver, foreign), "emissions", firstEmission));
	}

	private static void assertOwnedMatchesCold(NativePlacementContinuity resolver,
		CandidateRuleFact fact, boolean include, NativePlacementContinuity donor) throws Exception {
		Set<?> expected = cold(fact, include);
		Set<?> actual = owned(resolver, fact, include, donor);
		Assert.assertEquals(expected, actual);
		Assert.assertEquals(expected.hashCode(), actual.hashCode());
	}

	private static Set<?> owned(NativePlacementContinuity resolver, CandidateRuleFact fact,
		boolean include, NativePlacementContinuity donor) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("continuityProjectionOwned",
			List.class, boolean.class, NativePlacementContinuity.class);
		method.setAccessible(true);
		return (Set<?>)method.invoke(resolver, List.of(fact), include, donor);
	}

	private static Set<?> cold(CandidateRuleFact fact, boolean include) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("continuityProjection",
			List.class, boolean.class);
		method.setAccessible(true);
		return (Set<?>)method.invoke(null, List.of(fact), include);
	}

	private static Object factMemo(NativePlacementContinuity resolver, CandidateRuleFact fact)
		throws Exception {
		return projectionMemos(resolver).get(fact);
	}

	@SuppressWarnings("unchecked")
	private static Map<CandidateRuleFact,Object> projectionMemos(NativePlacementContinuity resolver)
		throws Exception {
		return (Map<CandidateRuleFact,Object>)field(resolver, "continuityProjectionMemo");
	}

	@SuppressWarnings("unchecked")
	private static Map<Object,Object> emissionMemos(Object factMemo) throws Exception {
		return (Map<Object,Object>)field(factMemo, "emissions");
	}

	private static Object onlyRealizationMemo(Object factMemo) throws Exception {
		Object emissionMemo = emissionMemos(factMemo).values().iterator().next();
		@SuppressWarnings("unchecked") Map<Object,Object> realizations =
			(Map<Object,Object>)field(emissionMemo, "realizations");
		return realizations.values().iterator().next();
	}

	private static Object child(Object memo, String field, Object identity) throws Exception {
		for(var entry : children(memo, field).entrySet())
			if(entry.getKey() == identity)
				return entry.getValue();
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Map<Object,Object> children(Object memo, String field) throws Exception {
		return (Map<Object,Object>)field(memo, field);
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static boolean identityContains(Map<?,?> values, Object expected) {
		for(Object value : values.keySet())
			if(value == expected)
				return true;
		return false;
	}

	private static NativePlacementContinuity resolver(CandidateRuleFact fact) {
		return new NativePlacementContinuity(Map.of(), Map.of(), List.of(fact), List.of(), Map.of());
	}

	private static Fixture fixture(String id) {
		CompiledHopKey owner = key(id + "-owner", 0);
		CandidateRealizationInputBinding stableBinding = direct(0, key(id + "-source-a", 1), "a");
		CandidateRealizationInputBinding replacedBinding = direct(1, key(id + "-source-b", 2), "b");
		CandidateRealizationInputBinding replacementBinding = direct(1, key(id + "-source-c", 3), "c");
		CandidateRealizationSupportClause stableClause =
			new CandidateRealizationSupportClause(List.of(), List.of(stableBinding));
		CandidateRealizationSupportClause beforeChangedClause =
			new CandidateRealizationSupportClause(List.of(), List.of(stableBinding, replacedBinding));
		CandidateRealizationSupportClause afterChangedClause =
			new CandidateRealizationSupportClause(List.of(), List.of(stableBinding, replacementBinding));
		CandidateEmissionRealization beforeRealization = CandidateEmissionRealization.valueMap(
			FED, id + "-map", List.of(stableClause, beforeChangedClause));
		CandidateEmissionRealization afterRealization = new CandidateEmissionRealization(
			beforeRealization.key(), List.of(stableClause, afterChangedClause));
		CandidateEmissionFact stableEmission = new CandidateEmissionFact(LOCAL, null, null,
			List.of(CandidateEmissionRealization.local(LOCAL)));
		CandidateEmissionFact beforeChangedEmission = new CandidateEmissionFact(FED, FType.ROW,
			null, List.of(beforeRealization));
		CandidateEmissionFact afterChangedEmission = new CandidateEmissionFact(FED, FType.ROW,
			null, List.of(afterRealization));
		CandidateRuleFact before = fact(owner, id + "-before",
			List.of(stableEmission, beforeChangedEmission));
		CandidateRuleFact after = fact(owner, id + "-after",
			List.of(stableEmission, afterChangedEmission));
		return new Fixture(before, after, stableEmission, beforeChangedEmission,
			afterChangedEmission, beforeRealization, afterRealization, stableClause,
			beforeChangedClause, afterChangedClause, stableBinding, replacedBinding,
			replacementBinding);
	}

	private static CandidateEmissionFact valueEmission(String id,
		CandidateRealizationSupportClause clause) {
		return new CandidateEmissionFact(FED, FType.ROW, null,
			List.of(CandidateEmissionRealization.valueMap(FED, id, List.of(clause))));
	}

	private static CandidateRealizationInputBinding direct(int position,
		CompiledHopKey sourceOwner, String id) {
		CandidateRuleKey rule = new CandidateRuleKey(sourceOwner, List.of());
		CandidateEmissionRealization source = CandidateEmissionRealization.durable(FED,
			anchor(id), List.of(), List.of());
		return CandidateRealizationInputBinding.direct(position,
			CandidateRealizationReference.of(rule, source));
	}

	private static CandidateRuleFact fact(CompiledHopKey owner, String metadata,
		List<CandidateEmissionFact> emissions) {
		return new CandidateRuleFact(new CandidateRuleKey(owner, List.of()),
			CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "projection-hierarchy", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, metadata, List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""), emissions, "");
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("worker:1234", List.of(0L, 0L), List.of(4L, 4L))));
	}

	private static CompiledHopKey key(String name, int ordinal) {
		ControlRegionKey region = new ControlRegionKey("projection-hierarchy", "main",
			List.of("root/" + ordinal), "root", "compiled");
		return new CompiledHopKey("projection-hierarchy", "main", "root", "compiled",
			region, name + '@' + ordinal, name);
	}

	private record Fixture(CandidateRuleFact before, CandidateRuleFact after,
		CandidateEmissionFact stableEmission, CandidateEmissionFact beforeChangedEmission,
		CandidateEmissionFact afterChangedEmission,
		CandidateEmissionRealization beforeRealization,
		CandidateEmissionRealization afterRealization,
		CandidateRealizationSupportClause stableClause,
		CandidateRealizationSupportClause beforeChangedClause,
		CandidateRealizationSupportClause afterChangedClause,
		CandidateRealizationInputBinding stableBinding,
		CandidateRealizationInputBinding replacedBinding,
		CandidateRealizationInputBinding replacementBinding) { }
}
