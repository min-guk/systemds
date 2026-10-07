/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.common.Types.ExecType;
import org.junit.Assert;
import org.junit.Test;

public class PlacementIdentityAnalysisScopeTest {
	@Test
	public void exactCandidateReferencePairIsReusedOnlyInsideItsAnalysisScope() throws Exception {
		CompiledHopKey owner = key("reference-owner");
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		CandidateEmissionRealization realization = CandidateEmissionRealization.local(
			new PlacementEmissionState(
				new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false));

		Assert.assertNotSame(CandidateRealizationReference.of(rule, realization),
			CandidateRealizationReference.of(rule, realization));
		CandidateRealizationReference retained;
		PlacementIdentity.beginAnalysisScope(null);
		try {
			retained = CandidateRealizationReference.of(rule, realization);
			Assert.assertSame(retained, CandidateRealizationReference.of(rule, realization));

			CandidateRuleKey equalRule = new CandidateRuleKey(owner, List.of());
			CandidateEmissionRealization equalRealization = new CandidateEmissionRealization(
				new PlacementRealizationKey(realization.key().emissionState(),
					realization.key().layoutKind(), realization.key().durableAnchor(),
					realization.key().nativeLineage()), realization.supportClauses());
			Assert.assertEquals(rule, equalRule);
			Assert.assertEquals(realization.key(), equalRealization.key());
			Assert.assertNotSame("equal copies must not substitute exact reference identities",
				retained, CandidateRealizationReference.of(equalRule, equalRealization));

			AtomicBoolean childReused = new AtomicBoolean(true);
			Thread child = new Thread(() -> childReused.set(
				CandidateRealizationReference.of(rule, realization)
					== CandidateRealizationReference.of(rule, realization)));
			child.start();
			child.join();
			Assert.assertFalse("analysis reference reuse must remain thread-confined", childReused.get());
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}

		PlacementIdentity.beginAnalysisScope(null);
		try {
			Assert.assertNotSame("a new analysis must not retain an old reference",
				retained, CandidateRealizationReference.of(rule, realization));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void exceptionalAnalysisCleanupReleasesCandidateReferences() {
		CompiledHopKey owner = key("exception-owner");
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		CandidateEmissionRealization realization = CandidateEmissionRealization.local(
			new PlacementEmissionState(
				new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false));
		CandidateRealizationReference retained;
		PlacementIdentity.beginAnalysisScope(null);
		try {
			retained = CandidateRealizationReference.of(rule, realization);
			throw new IllegalStateException("expected");
		}
		catch(IllegalStateException expected) {
			Assert.assertEquals("expected", expected.getMessage());
			retained = CandidateRealizationReference.of(rule, realization);
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
		Assert.assertNotSame(retained, CandidateRealizationReference.of(rule, realization));
	}
	@Test
	public void metricsOffUsesTheSameAnalysisLocalSignatureCachesAndCleansThemUp() throws Exception {
		PlacementIdentity.resetNormalizedSignatureCache();
		PlacementIdentity.beginAnalysisScope(null);
		try {
			Assert.assertNull("metrics remain diagnostic-only", PlacementIdentity.activeMetrics());
			DurableAnchorKey first = anchor("first");
			DurableAnchorKey equalCopy = anchor("first");
			String signature = first.normalizedSignature();
			Assert.assertSame("equal immutable copies share the analysis-local serialization",
				signature, equalCopy.normalizedSignature());

			Map<?,?> identity = activeMap("ACTIVE_IDENTITY_SIGNATURES");
			Map<?,?> structural = activeMap("ACTIVE_STRUCTURAL_SIGNATURES");
			Assert.assertNotNull(identity);
			Assert.assertNotNull(structural);
			Assert.assertTrue("same-object lookup is retained without weak-reference probes",
				identity.containsKey(first));
			Assert.assertTrue("equal-copy lookup is retained structurally",
				structural.containsKey(equalCopy));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			PlacementIdentity.resetNormalizedSignatureCache();
		}
		Assert.assertNull(activeMap("ACTIVE_IDENTITY_SIGNATURES"));
		Assert.assertNull(activeMap("ACTIVE_STRUCTURAL_SIGNATURES"));
	}

	@Test
	public void metricsOnStillCountsHitsWithoutChangingCacheSelection() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			DurableAnchorKey anchor = anchor("measured");
			anchor.normalizedSignature();
			long before = metrics.snapshot().signatureIdentityCacheHits();
			anchor.normalizedSignature();
			Assert.assertEquals(before + 1, metrics.snapshot().signatureIdentityCacheHits());
			Assert.assertNotNull(activeMap("ACTIVE_IDENTITY_SIGNATURES"));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	private static Map<?,?> activeMap(String fieldName) throws Exception {
		Field field = PlacementIdentity.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		return (Map<?,?>)((ThreadLocal<?>)field.get(null)).get();
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"reference-cache", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("reference-cache", "main", name, "compiled", region,
			name, name);
	}
}
