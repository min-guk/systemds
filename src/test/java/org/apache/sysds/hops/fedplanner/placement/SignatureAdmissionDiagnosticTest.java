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
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.placement.SearchSpaceMetrics.SignatureAdmissionSnapshot;
import org.junit.Assert;
import org.junit.Test;

public class SignatureAdmissionDiagnosticTest {
	@Test
	public void classifiesAdmissionsOversizeAndRemainingCapacityWithoutChangingPolicy() {
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(3L);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			KeyA admitted = new KeyA();
			KeyA remaining = new KeyA();
			KeyB oversized = new KeyB();
			PlacementIdentity.rememberSignature(admitted, "abc");
			PlacementIdentity.rememberSignature(remaining, "x");
			PlacementIdentity.rememberSignature(oversized, "abcd");
			Assert.assertSame("abc", PlacementIdentity.cachedSignature(admitted));
			Assert.assertNull(PlacementIdentity.cachedSignature(remaining));
			Assert.assertNull(PlacementIdentity.cachedSignature(oversized));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
		SignatureAdmissionSnapshot snapshot = metrics.signatureAdmissionSnapshot();
		Assert.assertEquals(1, snapshot.admittedSerializations());
		Assert.assertEquals(2, snapshot.rejectedSerializations());
		Assert.assertEquals(1, snapshot.oversizedRejections());
		Assert.assertEquals(1, snapshot.remainingCapacityRejections());
		Assert.assertEquals(1, snapshot.endStructuralEntries());
		Assert.assertEquals(1, snapshot.endIdentityEntries());
		Assert.assertEquals(3, snapshot.endRetainedChars());
		Assert.assertTrue(snapshot.firstRejectMaxHeapBytes() > 0);
		Assert.assertTrue(snapshot.firstRejectUsedHeapBytes() >= 0);
		Assert.assertEquals(2, snapshot.classes().size());
	}

	@Test
	public void rejectedContentObserverCountsExactRepeatsCollisionsAndIdentity() {
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(0L);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			KeyA same = new KeyA();
			PlacementIdentity.rememberSignature(same, new String("repeat"));
			PlacementIdentity.rememberSignature(same, new String("repeat"));
			PlacementIdentity.rememberSignature(new KeyA(), new String("repeat"));
			Assert.assertEquals("Aa".hashCode(), "BB".hashCode());
			PlacementIdentity.rememberSignature(new KeyA(), new String("Aa"));
			PlacementIdentity.rememberSignature(new KeyA(), new String("BB"));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
		SignatureAdmissionSnapshot snapshot = metrics.signatureAdmissionSnapshot();
		Assert.assertEquals(5, snapshot.rejectedSerializations());
		Assert.assertEquals(3, snapshot.observedFirstContents());
		Assert.assertEquals(2, snapshot.observedRepeatSerializations());
		Assert.assertEquals(1, snapshot.sameFirstKeyIdentityRepeats());
		Assert.assertEquals(0, snapshot.firstKeyClearedBeforeRepeat());
		Assert.assertEquals(0, snapshot.observedOverflowSerializations());
		Assert.assertEquals(12, snapshot.observedRepeatUtf16LowerBound());
	}

	@Test
	public void observerBoundsEntriesAndUtf16Content() {
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(0L);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			for(int index = 0; index < 257; index++)
				PlacementIdentity.rememberSignature(new KeyA(), "unique-" + index);
			PlacementIdentity.rememberSignature(new KeyA(), new String("unique-0"));
			PlacementIdentity.rememberSignature(new KeyA(), "z".repeat(1024 * 1024 + 1));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
		SignatureAdmissionSnapshot snapshot = metrics.signatureAdmissionSnapshot();
		Assert.assertEquals(256, snapshot.observedFirstContents());
		Assert.assertEquals(1, snapshot.observedRepeatSerializations());
		Assert.assertEquals(2, snapshot.observedOverflowSerializations());
	}

	@Test
	public void observerBoundsRejectedUtf16ContentIndependentlyOfEntryCount() {
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(0L);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			PlacementIdentity.rememberSignature(new KeyA(), "tracked");
			PlacementIdentity.rememberSignature(new KeyA(), "z".repeat(1024 * 1024));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
		SignatureAdmissionSnapshot snapshot = metrics.signatureAdmissionSnapshot();
		Assert.assertEquals(1, snapshot.observedFirstContents());
		Assert.assertEquals(1, snapshot.observedOverflowSerializations());
	}

	@Test
	public void clearedFirstKeyIsObservedAndScopeCleanupRetainsOnlyPrimitiveSnapshot()
		throws Exception {
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(0L);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			PlacementIdentity.rememberSignature(new KeyA(), new String("cleared"));
			clearFirstCompilerKey(metrics);
			PlacementIdentity.rememberSignature(new KeyA(), new String("cleared"));
			Assert.assertTrue(metrics.hasActiveSignatureAdmissionObserver());
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
		Assert.assertFalse(metrics.hasActiveSignatureAdmissionObserver());
		Assert.assertEquals(1,
			metrics.signatureAdmissionSnapshot().firstKeyClearedBeforeRepeat());
		metrics.reset();
		Assert.assertEquals(0, metrics.signatureAdmissionSnapshot().rejectedSerializations());
		Assert.assertFalse(metrics.hasActiveSignatureAdmissionObserver());
	}

	@Test
	public void setActiveMetricsNullFinalizesAndClearsObserver() {
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(0L);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		try {
			PlacementIdentity.setActiveMetrics(metrics);
			PlacementIdentity.rememberSignature(new KeyA(), "rejected");
			Assert.assertTrue(metrics.hasActiveSignatureAdmissionObserver());
			PlacementIdentity.setActiveMetrics(null);
			Assert.assertFalse(metrics.hasActiveSignatureAdmissionObserver());
			Assert.assertEquals(1, metrics.signatureAdmissionSnapshot().rejectedSerializations());
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
	}

	@Test
	public void failedAnalysisScopeSnapshotStillReleasesCachesAndObserver() throws Exception {
		assertFailedSnapshotStillCleans(true);
	}

	@Test
	public void failedActiveMetricsSnapshotStillReleasesCachesAndObserver() throws Exception {
		assertFailedSnapshotStillCleans(false);
	}

	@Test
	public void defaultMetricsObjectDoesNotAllocateObserverUntilSignatureInstrumentation() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Assert.assertFalse(metrics.hasActiveSignatureAdmissionObserver());
		PlacementIdentity.beginAnalysisScope(null);
		try {
			PlacementIdentity.rememberSignature(new KeyA(), "unmeasured");
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
		Assert.assertFalse(metrics.hasActiveSignatureAdmissionObserver());
	}

	private static void clearFirstCompilerKey(SearchSpaceMetrics metrics) throws Exception {
		Field observerField = SearchSpaceMetrics.class.getDeclaredField("signatureAdmissionObserver");
		observerField.setAccessible(true);
		Object observer = observerField.get(metrics);
		Field contentsField = observer.getClass().getDeclaredField("rejectedContents");
		contentsField.setAccessible(true);
		Map<?,?> contents = (Map<?,?>)contentsField.get(observer);
		Object entry = contents.values().iterator().next();
		Field referenceField = entry.getClass().getDeclaredField("firstCompilerKey");
		referenceField.setAccessible(true);
		((WeakReference<?>)referenceField.get(entry)).clear();
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertFailedSnapshotStillCleans(boolean analysisScope) throws Exception {
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(100L);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		KeyA key = new KeyA();
		if(analysisScope)
			PlacementIdentity.beginAnalysisScope(metrics);
		else
			PlacementIdentity.setActiveMetrics(metrics);
		try {
			PlacementIdentity.rememberSignature(key, "accepted");
			Field observerField = SearchSpaceMetrics.class.getDeclaredField(
				"signatureAdmissionObserver");
			observerField.setAccessible(true);
			Object observer = observerField.get(metrics);
			Field byClassField = observer.getClass().getDeclaredField("byClass");
			byClassField.setAccessible(true);
			Map byClass = (Map)byClassField.get(observer);
			Object value = byClass.values().iterator().next();
			byClass.clear();
			byClass.put(null, value);
			try {
				if(analysisScope)
					PlacementIdentity.endAnalysisScope();
				else
					PlacementIdentity.setActiveMetrics(null);
				Assert.fail("Expected poisoned diagnostic snapshot to fail");
			}
			catch(NullPointerException expected) {
				// The diagnostic failure propagates after all analysis-local state is released.
			}
			Assert.assertNull(PlacementIdentity.activeMetrics());
			Assert.assertNull(PlacementIdentity.cachedSignature(key));
			Assert.assertEquals(0, PlacementIdentity.normalizedSignatureCacheRetainedChars());
			Assert.assertFalse(metrics.hasActiveSignatureAdmissionObserver());
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
	}

	private static final class KeyA { }
	private static final class KeyB { }
}
