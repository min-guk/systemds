/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

public class PlacementIdentityAnalysisScopeTest {
	@Test
	public void aliasSaturationKeepsStructuralReuseWithoutRetainingEveryCopy() throws Exception {
		PlacementIdentity.resetNormalizedSignatureCache();
		PlacementIdentity.beginAnalysisScope(null);
		try {
			String text = anchor("first").normalizedSignature();
			long retained = PlacementIdentity.normalizedSignatureCacheRetainedChars();
			for(int index = 0; index < 70_000; index++)
				Assert.assertSame(text, anchor("first").normalizedSignature());
			Assert.assertTrue("equal aliases must have bounded identity retention",
				activeMap("ACTIVE_IDENTITY_SIGNATURES").size() <= 65_536);
			Assert.assertEquals(retained, PlacementIdentity.normalizedSignatureCacheRetainedChars());
			String firstCollision = anchor("Aa").normalizedSignature();
			String secondCollision = anchor("BB").normalizedSignature();
			Assert.assertNotEquals(firstCollision, secondCollision);
			Assert.assertSame("new distinct signatures still enter the structural cache after saturation",
				firstCollision, anchor("Aa").normalizedSignature());
			Assert.assertSame(secondCollision, anchor("BB").normalizedSignature());
			Assert.assertTrue(activeMap("ACTIVE_IDENTITY_SIGNATURES").size() <= 65_536);
			PlacementIdentity.endAnalysisScope();
			PlacementIdentity.beginAnalysisScope(null);
			DurableAnchorKey fresh = anchor("fresh");
			fresh.normalizedSignature();
			Assert.assertTrue(activeMap("ACTIVE_IDENTITY_SIGNATURES").containsKey(fresh));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			PlacementIdentity.resetNormalizedSignatureCache();
		}
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

	@Test
	public void signatureBudgetFollowsTheCurrentCacheLifecycleOwner() throws Exception {
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(8L);
		boolean active = false;
		try {
			Object firstActiveKey = new Object();
			String firstActiveText = new String("12345678");
			PlacementIdentity.beginAnalysisScope(null);
			active = true;
			PlacementIdentity.rememberSignature(firstActiveKey, firstActiveText);
			Assert.assertSame(firstActiveText, PlacementIdentity.cachedSignature(firstActiveKey));
			Assert.assertEquals(8, PlacementIdentity.normalizedSignatureCacheRetainedChars());

			PlacementIdentity.endAnalysisScope();
			active = false;
			Assert.assertNull(activeMap("ACTIVE_IDENTITY_SIGNATURES"));
			Assert.assertNull(activeMap("ACTIVE_STRUCTURAL_SIGNATURES"));
			Assert.assertEquals("released strong values must not consume the weak-cache budget",
				0, PlacementIdentity.normalizedSignatureCacheRetainedChars());
			Assert.assertNull("analysis-owned values must not leak into the weak cache",
				PlacementIdentity.cachedSignature(firstActiveKey));

			Object weakKey = new Object();
			String weakText = new String("abcdefgh");
			PlacementIdentity.rememberSignature(weakKey, weakText);
			Assert.assertSame("the fresh weak cache can retain after a full analysis scope",
				weakText, PlacementIdentity.cachedSignature(weakKey));
			Assert.assertEquals(8, PlacementIdentity.normalizedSignatureCacheRetainedChars());

			PlacementIdentity.beginAnalysisScope(null);
			active = true;
			Assert.assertNull("the next strong scope must release prior weak values",
				PlacementIdentity.cachedSignature(weakKey));
			Assert.assertEquals(0, PlacementIdentity.normalizedSignatureCacheRetainedChars());
			Object secondActiveKey = new Object();
			String secondActiveText = new String("ABCDEFGH");
			PlacementIdentity.rememberSignature(secondActiveKey, secondActiveText);
			Assert.assertSame(secondActiveText, PlacementIdentity.cachedSignature(secondActiveKey));
			Assert.assertEquals(8, PlacementIdentity.normalizedSignatureCacheRetainedChars());

			PlacementIdentity.endAnalysisScope();
			active = false;
			Assert.assertEquals(0, PlacementIdentity.normalizedSignatureCacheRetainedChars());
			Assert.assertNull("completed strong values must be released",
				PlacementIdentity.cachedSignature(secondActiveKey));
		}
		finally {
			if(active)
				PlacementIdentity.endAnalysisScope();
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
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
}
