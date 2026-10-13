/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.List;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

public class NativeMissingPinCoverageTest {
	@Test public void missingPinPreservesPublicTemplateProofWithoutExpandingNativeSibling() throws Exception {
		for(boolean scoped : new boolean[] {false, true}) {
			if(scoped) PlacementIdentity.beginAnalysisScope(new SearchSpaceMetrics());
			try {
				Object scenario = scenario();
				CandidateRuleFact fact = (CandidateRuleFact)invoke(scenario, "childFact");
				CandidateEmissionFact emission = (CandidateEmissionFact)invoke(scenario, "childEmission");
				CandidateRealizationReference missing = CandidateRealizationReference.of(fact.key(),
					CandidateEmissionRealization.nativeLineage(emission.emissionState(),
						"missing-template-source", List.of(), List.of()));
				DurableAnchorKey pool = (DurableAnchorKey)invoke(scenario, "pool");
				invoke(scenario, "install", true);
				NativePlacementContinuity.CandidateSupportResult expected = resolver(scenario)
					.proveCandidateSupport(missing, pool);
				NativeContinuitySupportClauses nativeRelation =
					(NativeContinuitySupportClauses)invoke(scenario, "install", false);
				NativePlacementContinuity.CandidateSupportResult actual = resolver(scenario)
					.proveCandidateSupport(missing, pool);
				Method parity = NativePinnedMixedOwnerParityTest.class.getDeclaredMethod("assertParity",
					NativePlacementContinuity.CandidateSupportResult.class,
					NativePlacementContinuity.CandidateSupportResult.class);
				parity.setAccessible(true); parity.invoke(null, expected, actual);
				Assert.assertEquals("missing template pin must not expand unrelated native authority",
					1, nativeRelation.materializedHandleCount());
			}
			finally { if(scoped) PlacementIdentity.endAnalysisScope(); }
		}
	}

	@Test public void missingPinDirectOverlayRetainsItsExactTemplateObligation() throws Exception {
		Object scenario = scenario();
		CandidateRuleFact fact = (CandidateRuleFact)invoke(scenario, "childFact");
		CandidateEmissionFact emission = (CandidateEmissionFact)invoke(scenario, "childEmission");
		CandidateRealizationReference missing = CandidateRealizationReference.of(fact.key(),
			CandidateEmissionRealization.nativeLineage(emission.emissionState(),
				"missing-template-source", List.of(), List.of()));
		invoke(scenario, "install", true);
		List<?> expected = alternatives(scenario, resolver(scenario), missing);
		Assert.assertFalse("missing pin must retain a real template obligation", expected.isEmpty());
		NativeContinuitySupportClauses nativeRelation =
			(NativeContinuitySupportClauses)invoke(scenario, "install", false);
		List<?> actual = alternatives(scenario, resolver(scenario), missing);
		Assert.assertEquals(expected, actual);
		Assert.assertEquals(1, nativeRelation.materializedHandleCount());
	}

	private static List<?> alternatives(Object scenario, NativePlacementContinuity resolver,
		CandidateRealizationReference source) throws Exception {
		Method method = NativeHybridUnpinnedTopologyTest.class.getDeclaredMethod("candidateAlternatives",
			NativePlacementContinuity.class, CompiledHopKey.class, CandidateRealizationReference.class,
			DurableAnchorKey.class);
		method.setAccessible(true);
		return (List<?>)method.invoke(null, resolver, invoke(scenario, "childKey"), source, invoke(scenario, "pool"));
	}
	private static NativePlacementContinuity resolver(Object scenario) throws Exception {
		return (NativePlacementContinuity)invoke(invoke(scenario, "fixture"), "resolver");
	}
	private static Object scenario() throws Exception {
		Method method = NativeHybridUnpinnedTopologyTest.class.getDeclaredMethod("scenario", String.class, List.class);
		method.setAccessible(true); return method.invoke(null, "m-choice", List.of("a-choice", "z-choice"));
	}
	private static Object invoke(Object object, String name, Object... args) throws Exception {
		for(Method method : object.getClass().getDeclaredMethods())
			if(method.getName().equals(name) && method.getParameterCount() == args.length) {
				method.setAccessible(true); return method.invoke(object, args);
			}
		throw new NoSuchMethodException(name);
	}
}
