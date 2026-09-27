/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.placement.PlacementClosureDiagnostics.CompositionRecurrenceObservation;
import org.apache.sysds.hops.fedplanner.placement.PlacementClosureDiagnostics.CompositionRecurrenceTracker;
import org.apache.sysds.hops.fedplanner.placement.PlacementClosureDiagnostics.ExportDeltaDiagnostics;
import org.junit.Assert;
import org.junit.Test;

public class CompositionRecurrenceDiagnosticsTest {
	private static final String PROPERTY = "sysds.fedplanner.cycleDiagnostics";
	private static final String EXPORT_PROPERTY = "sysds.fedplanner.exportDiagnostics";

	@Test
	public void diagnosticsRequireBothMetricsAndExplicitProperty() {
		String prior = System.getProperty(PROPERTY);
		try {
			System.clearProperty(PROPERTY);
			Assert.assertNull(CompositionRecurrenceTracker.enabled(
				new SearchSpaceMetrics()));
			System.setProperty(PROPERTY, "true");
			Assert.assertNull(CompositionRecurrenceTracker.enabled(null));
			Assert.assertNotNull(CompositionRecurrenceTracker.enabled(
				new SearchSpaceMetrics()));
		}
		finally {
			if(prior == null)
				System.clearProperty(PROPERTY);
			else
				System.setProperty(PROPERTY, prior);
		}
	}

	@Test
	public void exportDiagnosticsRequireBothMetricsAndExplicitProperty() {
		String prior = System.getProperty(EXPORT_PROPERTY);
		try {
			System.clearProperty(EXPORT_PROPERTY);
			Assert.assertNull(ExportDeltaDiagnostics.enabled(
				new SearchSpaceMetrics(), Map.of()));
			System.setProperty(EXPORT_PROPERTY, "true");
			Assert.assertNull(ExportDeltaDiagnostics.enabled(null, Map.of()));
			Assert.assertNotNull(ExportDeltaDiagnostics.enabled(
				new SearchSpaceMetrics(), Map.of()));
		}
		finally {
			if(prior == null)
				System.clearProperty(EXPORT_PROPERTY);
			else
				System.setProperty(EXPORT_PROPERTY, prior);
		}
	}

	@Test
	public void detectsExactPeriodWithoutMatchingSameSizedDifferentState() {
		CompositionRecurrenceTracker tracker = tracker();
		Object baseKey = new Object();
		Map<Object,Object> bases = identityMap(baseKey, "base-a");
		Assert.assertNull(observe(tracker, 0, "a", 1, bases));
		Assert.assertNull(observe(tracker, 1, "b", 1, bases));
		CompositionRecurrenceObservation recurrence =
			observe(tracker, 2, "a", 1, bases);

		Assert.assertNotNull(recurrence);
		Assert.assertTrue(recurrence.publicationRecurrence());
		Assert.assertTrue(recurrence.fullContextRecurrence());
		Assert.assertEquals(2, recurrence.repeatPeriod());
		Assert.assertEquals(0, recurrence.publicationPreviousPass());
		Assert.assertEquals(0, recurrence.fullContextPreviousPass());
		Assert.assertTrue(recurrence.nodesSame());
		Assert.assertEquals(3, tracker.retainedStates());
	}

	@Test
	public void recurringPublicationWithNewLedgerIsNotFullContextRecurrence() {
		CompositionRecurrenceTracker tracker = tracker();
		Object baseKey = new Object();
		Map<Object,Object> bases = identityMap(baseKey, "base-a");
		Assert.assertNull(observe(tracker, 0, "same", 1, bases));
		CompositionRecurrenceObservation recurrence =
			observe(tracker, 1, "same", 2, bases);

		Assert.assertNotNull(recurrence);
		Assert.assertTrue(recurrence.publicationRecurrence());
		Assert.assertFalse(recurrence.fullContextRecurrence());
		Assert.assertEquals(-1, recurrence.fullContextPreviousPass());
		Assert.assertEquals(2, recurrence.ledgerSize());
		Assert.assertEquals(1, recurrence.baseSize());
	}

	@Test
	public void sameSizedDifferentClauseListIsNotARecurrence() {
		CompositionRecurrenceTracker tracker = tracker();
		Map<Object,Object> bases = identityMap(new Object(), "base-a");
		Assert.assertNull(tracker.observe(0, List.of("node"), List.of("domain"),
			List.of("clause-a"), List.of("logical"), List.of("action"),
			List.of("pending"), 1, bases));
		Assert.assertNull(tracker.observe(1, List.of("node"), List.of("domain"),
			List.of("clause-b"), List.of("logical"), List.of("action"),
			List.of("pending"), 1, bases));
	}

	@Test
	public void replayBaseUsesKeyIdentityAndExactValueEquality() {
		CompositionRecurrenceTracker tracker = tracker();
		Object originalKey = new String("equal-key");
		Assert.assertNull(observe(tracker, 0, "same", 1,
			identityMap(originalKey, List.of("base-a"))));

		CompositionRecurrenceObservation changedValue =
			observe(tracker, 1, "same", 1, identityMap(originalKey, List.of("base-b")));
		Assert.assertTrue(changedValue.publicationRecurrence());
		Assert.assertFalse(changedValue.fullContextRecurrence());

		CompositionRecurrenceObservation equalButDistinctKey =
			observe(tracker, 2, "same", 1,
				identityMap(new String("equal-key"), List.of("base-a")));
		Assert.assertTrue(equalButDistinctKey.publicationRecurrence());
		Assert.assertFalse(equalButDistinctKey.fullContextRecurrence());
	}

	private static CompositionRecurrenceTracker tracker() {
		return new CompositionRecurrenceTracker();
	}

	private static CompositionRecurrenceObservation observe(
		CompositionRecurrenceTracker tracker, int pass,
		String value, int ledgerSize, Map<Object,Object> bases) {
		return tracker.observe(pass, List.of("nodes-" + value), List.of("domain-" + value),
			List.of("facts-" + value), List.of("logical-" + value),
			List.of("actions-" + value), List.of("pending-" + value), ledgerSize, bases);
	}

	private static Map<Object,Object> identityMap(Object key, Object value) {
		Map<Object,Object> result = new IdentityHashMap<>();
		result.put(key, value);
		return result;
	}
}
