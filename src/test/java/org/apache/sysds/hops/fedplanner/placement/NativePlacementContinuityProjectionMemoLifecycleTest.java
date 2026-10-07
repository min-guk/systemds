/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact projection reuse and lifecycle checks; this is not a planner acceptance fixture. */
public class NativePlacementContinuityProjectionMemoLifecycleTest {
	@Test
	public void changedOwnerSharesOnlyRetainedFactProjectionDuringTheComparison() throws Exception {
		CompiledHopKey owner = owner("mixed-owner");
		CandidateRuleFact retained = fact(owner, ExecType.CP, "retained");
		CandidateRuleFact removed = fact(owner, ExecType.FED, "removed");
		CandidateRuleFact added = fact(owner, ExecType.CP, "added");
		NativePlacementContinuity before = resolver(List.of(retained, removed));
		NativePlacementContinuity after = resolver(List.of(retained, added));

		Class<?> workType = Class.forName(
			NativePlacementContinuity.class.getName() + "$RevisionComparisonWork");
		Constructor<?> constructor = workType.getDeclaredConstructor();
		constructor.setAccessible(true);
		Method compare = NativePlacementContinuity.class.getDeclaredMethod(
			"directContinuityComparison", NativePlacementContinuity.class,
			CompiledHopKey.class, workType);
		compare.setAccessible(true);
		compare.invoke(before, after, owner, constructor.newInstance());

		Assert.assertEquals(2, projectionMemo(before).size());
		Assert.assertEquals(2, projectionMemo(after).size());
		Assert.assertTrue(identityContains(projectionMemo(after), retained));
		Assert.assertTrue(identityContains(projectionMemo(after), added));
		Assert.assertFalse(identityContains(projectionMemo(after), removed));
		Assert.assertSame(factProjection(before, retained, true, null),
			factProjection(after, retained, true, before));
		Assert.assertEquals(coldProjection(List.of(retained, added), true),
			ownedProjection(after, List.of(retained, added), true, before));
	}

	@Test
	public void revisionRetainsOnlyCurrentFactIdentitiesAndKeepsProjectionModesSeparate() throws Exception {
		CompiledHopKey retainedOwner = owner("retained");
		CompiledHopKey churnOwner = owner("churn");
		CandidateRuleFact retained = fact(retainedOwner, ExecType.CP, "retained");
		CandidateRuleFact oldChurn = fact(churnOwner, ExecType.CP, "old");
		NativePlacementContinuity first = resolver(List.of(retained, oldChurn));

		Object retainedFull = factProjection(first, retained, true, null);
		Object retainedGenerated = factProjection(first, retained, false, null);
		Object oldFull = factProjection(first, oldChurn, true, null);
		Assert.assertNotSame("published and generated-root projections have different semantics",
			retainedFull, retainedGenerated);
		Assert.assertEquals(2, projectionMemo(first).size());

		CandidateRuleFact recreatedEqual = fact(churnOwner, ExecType.CP, "old");
		Assert.assertEquals(oldChurn, recreatedEqual);
		Assert.assertNotSame(oldChurn, recreatedEqual);
		NativePlacementContinuity next = first.nextRevision(List.of(retained, recreatedEqual));
		Map<?,?> nextBeforeQuery = projectionMemo(next);
		Assert.assertEquals("only a current fact with the exact retained identity crosses the revision", 1,
			nextBeforeQuery.size());
		Assert.assertTrue(identityContains(nextBeforeQuery, retained));
		Assert.assertFalse(identityContains(nextBeforeQuery, oldChurn));
		Assert.assertFalse(identityContains(nextBeforeQuery, recreatedEqual));

		Assert.assertSame(retainedFull, factProjection(next, retained, true, first));
		Assert.assertSame(retainedGenerated, factProjection(next, retained, false, first));
		Object recreatedFull = factProjection(next, recreatedEqual, true, first);
		Assert.assertNotSame("equal reconstruction must build its own exact authority projection",
			oldFull, recreatedFull);
		Assert.assertEquals(coldProjection(List.of(recreatedEqual), true).iterator().next(), recreatedFull);
		Assert.assertEquals(2, projectionMemo(next).size());

		CandidateRuleFact changedAuthority = fact(churnOwner, ExecType.FED, "changed");
		NativePlacementContinuity changed = next.nextRevision(List.of(changedAuthority));
		Assert.assertTrue("the retained fact left the inventory", projectionMemo(changed).isEmpty());
		Object changedFull = factProjection(changed, changedAuthority, true, next);
		Assert.assertNotEquals(recreatedFull, changedFull);
		Assert.assertEquals(1, projectionMemo(changed).size());
	}

	@Test
	public void repeatedRevisionChurnNeverRetainsHistoricalFacts() throws Exception {
		CompiledHopKey owner = owner("churn-only");
		CandidateRuleFact current = fact(owner, ExecType.CP, "0");
		NativePlacementContinuity resolver = resolver(List.of(current));
		factProjection(resolver, current, true, null);
		for(int revision = 1; revision <= 32; revision++) {
			CandidateRuleFact replacement = fact(owner,
				revision % 2 == 0 ? ExecType.CP : ExecType.FED, Integer.toString(revision));
			NativePlacementContinuity next = resolver.nextRevision(List.of(replacement));
			Assert.assertTrue("reconstructed identity cannot cross a revision",
				projectionMemo(next).isEmpty());
			factProjection(next, replacement, true, resolver);
			Assert.assertEquals(1, projectionMemo(next).size());
			Assert.assertTrue(identityContains(projectionMemo(next), replacement));
			Assert.assertFalse(identityContains(projectionMemo(next), current));
			resolver = next;
			current = replacement;
		}
	}

	private static Object factProjection(NativePlacementContinuity resolver, CandidateRuleFact fact,
		boolean includePublished, NativePlacementContinuity donor) throws Exception {
		return ownedProjection(resolver, List.of(fact), includePublished, donor).iterator().next();
	}

	private static Set<?> ownedProjection(NativePlacementContinuity resolver,
		List<CandidateRuleFact> facts, boolean includePublished, NativePlacementContinuity donor)
		throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("continuityProjectionOwned",
			List.class, boolean.class, NativePlacementContinuity.class);
		method.setAccessible(true);
		return (Set<?>)method.invoke(resolver, facts, includePublished, donor);
	}

	private static Set<?> coldProjection(List<CandidateRuleFact> facts, boolean includePublished)
		throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("continuityProjection",
			List.class, boolean.class);
		method.setAccessible(true);
		return (Set<?>)method.invoke(null, facts, includePublished);
	}

	@SuppressWarnings("unchecked")
	private static Map<CandidateRuleFact,?> projectionMemo(NativePlacementContinuity resolver)
		throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField("continuityProjectionMemo");
		field.setAccessible(true);
		return (Map<CandidateRuleFact,?>)field.get(resolver);
	}

	private static boolean identityContains(Map<?,?> map, Object expected) {
		for(Object key : map.keySet())
			if(key == expected)
				return true;
		return false;
	}

	private static NativePlacementContinuity resolver(List<CandidateRuleFact> facts) {
		return new NativePlacementContinuity(Map.of(), Map.of(), facts, List.of(), Map.of());
	}

	private static CompiledHopKey owner(String value) {
		ControlRegionKey region = new ControlRegionKey("projection-memo", "main", List.of("root"),
			"root", "compiled");
		return new CompiledHopKey("projection-memo", "main", "root", "compiled", region,
			value, value);
	}

	private static CandidateRuleFact fact(CompiledHopKey owner, ExecType exec, String metadata) {
		PlacementEmissionState emission = new PlacementEmissionState(
			new PlacementState(exec, FederatedOutput.LOUT, null, false), false);
		return new CandidateRuleFact(new CandidateRuleKey(owner, List.of()),
			CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", exec, FederatedOutput.LOUT,
				null, ReasonCode.OK, metadata, List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""),
			List.of(new CandidateEmissionFact(emission, null, null,
				List.of(CandidateEmissionRealization.local(emission)))), "");
	}
}
