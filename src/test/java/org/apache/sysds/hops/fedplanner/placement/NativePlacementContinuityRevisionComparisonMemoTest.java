/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

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

/** Private projection algebra, not an executable-plan privacy acceptance fixture. */
public class NativePlacementContinuityRevisionComparisonMemoTest {
	@Test
	public void comparisonIsSharedOnlyWithinOneRevisionPairAndExactOwnerIdentity() throws Exception {
		CompiledHopKey owner = owner();
		CandidateRuleFact before = fact(owner, ExecType.CP, "before");
		CandidateRuleFact metadata = fact(owner, ExecType.CP, "metadata-only");
		CandidateRuleFact changed = fact(owner, ExecType.FED, "changed-emission");
		NativePlacementContinuity old = resolver(before);
		Class<?> workType = Class.forName(NativePlacementContinuity.class.getName() + "$RevisionComparisonWork");
		Constructor<?> constructor = workType.getDeclaredConstructor(); constructor.setAccessible(true);
		Method compare = NativePlacementContinuity.class.getDeclaredMethod("directContinuityComparison",
			NativePlacementContinuity.class, CompiledHopKey.class, workType);
		compare.setAccessible(true);
		for(CandidateRuleFact after : List.of(before, metadata, changed)) {
			NativePlacementContinuity next = resolver(after);
			Object work = constructor.newInstance();
			Object first = compare.invoke(old, next, owner, work);
			Assert.assertSame(first, compare.invoke(old, next, owner, work));
			Method unchanged = first.getClass().getDeclaredMethod("unchanged"); unchanged.setAccessible(true);
			Method projected = first.getClass().getDeclaredMethod("projectionNeeded"); projected.setAccessible(true);
			Assert.assertEquals(legacyEqual(before, after), unchanged.invoke(first));
			Assert.assertEquals(!before.equals(after), projected.invoke(first));
			Assert.assertNotSame("new revision comparisons never inherit an old pair's result", first,
				compare.invoke(old, next, owner, constructor.newInstance()));
			CompiledHopKey equalForeign = owner();
			Assert.assertEquals(owner, equalForeign); Assert.assertNotSame(owner, equalForeign);
			Object foreign = compare.invoke(old, next, equalForeign, work);
			Assert.assertNotSame("owner routing stays identity-sensitive", first, foreign);
			Assert.assertEquals(true, unchanged.invoke(foreign));
			Assert.assertEquals(false, projected.invoke(foreign));
		}
	}

	private static boolean legacyEqual(CandidateRuleFact before, CandidateRuleFact after) throws Exception {
		if(before.equals(after)) return true;
		Method project = NativePlacementContinuity.class.getDeclaredMethod("continuityProjection", List.class);
		project.setAccessible(true);
		return project.invoke(null, List.of(before)).equals(project.invoke(null, List.of(after)));
	}
	private static NativePlacementContinuity resolver(CandidateRuleFact fact) {
		return new NativePlacementContinuity(Map.of(), Map.of(), List.of(fact), List.of(), Map.of());
	}
	private static CompiledHopKey owner() {
		ControlRegionKey region = new ControlRegionKey("revision-pair", "main", List.of("root"), "root", "compiled");
		return new CompiledHopKey("revision-pair", "main", "root", "compiled", region, "value", "value");
	}
	private static CandidateRuleFact fact(CompiledHopKey owner, ExecType exec, String metadata) {
		PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(exec, FederatedOutput.LOUT, null, false), false);
		return new CandidateRuleFact(new CandidateRuleKey(owner, List.of()), CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", exec, FederatedOutput.LOUT, null, ReasonCode.OK, metadata, List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()), new CandidateProfileFact(List.of(), ""),
			List.of(new CandidateEmissionFact(emission, null, null, List.of(CandidateEmissionRealization.local(emission)))), "");
	}
}
