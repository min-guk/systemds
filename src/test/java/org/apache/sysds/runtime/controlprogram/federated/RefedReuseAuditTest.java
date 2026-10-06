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
package org.apache.sysds.runtime.controlprogram.federated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RefedReuseAuditTest {
	private String _previousAuditProperty;

	private static class FakeMap extends FederationMap {
		private int _cleanups;

		FakeMap(long id) {
			this(id, 10);
		}

		FakeMap(long id, long rows) {
			super(id, List.of(Pair.of(new FederatedRange(new long[] {0, 0}, new long[] {rows, 1}),
				new FederatedData(DataType.MATRIX, new InetSocketAddress("127.0.0.1", 19001), "unused"))),
				FType.ROW);
		}

		@Override
		public FederationMap identCopy(long tid, long id) {
			return new FakeMap(id);
		}

		@Override
		public void execCleanup(long tid, long... ids) {
			_cleanups++;
		}
	}

	@Before
	public void before() {
		_previousAuditProperty = System.getProperty(RefedReuseAudit.PROPERTY);
		System.setProperty(RefedReuseAudit.PROPERTY, "true");
		FederationUtils.clearOwnedRefedReuseCache();
		RefedReuseAudit.reset();
	}

	@After
	public void after() {
		FederationUtils.clearOwnedRefedReuseCache();
		RefedReuseAudit.reset();
		if(_previousAuditProperty == null)
			System.clearProperty(RefedReuseAudit.PROPERTY);
		else
			System.setProperty(RefedReuseAudit.PROPERTY, _previousAuditProperty);
	}

	@Test
	public void invariantCreatesOnceWhileUpdatedVersionsCreateEachTime() {
		MatrixObject invariant = local("invariant");
		AtomicInteger materializations = new AtomicInteger();
		planned(invariant, new FakeMap(7000), materializations);
		planned(invariant, new FakeMap(7001), materializations);
		planned(invariant, new FakeMap(7002), materializations);

		RefedReuseAudit.Snapshot invariantSnapshot = RefedReuseAudit.snapshot();
		RefedReuseAudit.ModeCounters invariantPlanned = mode(invariantSnapshot, "PLANNED");
		assertEquals(1, materializations.get());
		assertEquals(1, invariantPlanned.creationSuccesses());
		assertEquals(2, invariantPlanned.hits());
		assertEquals(3, invariantPlanned.aliases());

		FederationUtils.clearOwnedRefedReuseCache();
		RefedReuseAudit.reset();
		materializations.set(0);
		MatrixObject updated = local("updated");
		for(int i = 0; i < 3; i++) {
			planned(updated, new FakeMap(7100 + i), materializations);
			updated.acquireModify(new MatrixBlock(10, 1, false));
			updated.release();
		}
		RefedReuseAudit.ModeCounters updatedPlanned = mode(RefedReuseAudit.snapshot(), "PLANNED");
		assertEquals(3, materializations.get());
		assertEquals(3, updatedPlanned.creationSuccesses());
		assertEquals(0, updatedPlanned.hits());
		assertEquals(3, updatedPlanned.aliases());
	}

	@Test
	public void plannedResidencySurvivesLegacyBudgetAndRetiresWithReason() {
		MatrixObject owner = local("planned-large");
		FakeMap planned = new FakeMap(7200, 10_000_000);
		planned(owner, planned, new AtomicInteger());
		legacy(local("legacy-large"), new FakeMap(7201, 10_000_000));

		RefedReuseAudit.Snapshot resident = RefedReuseAudit.snapshot();
		assertEquals(1, mode(resident, "PLANNED").currentCanonicalCount());
		assertEquals(1, mode(resident, "PLANNED").peakCanonicalCount());
		assertEquals(0, mode(resident, "LEGACY").currentCanonicalCount());
		owner.clearData();

		RefedReuseAudit.Snapshot retired = RefedReuseAudit.snapshot();
		assertEquals(0, mode(retired, "PLANNED").currentCanonicalCount());
		assertEquals(1, planned._cleanups);
		assertTrue(retired.events().stream().anyMatch(event -> "RETIREMENT".equals(event.event())
			&& RefedReuseAudit.SOURCE_CLEAR.equals(event.reason()) && event.canonicalRemoteId() == 7200));
		assertTrue(retired.events().stream().anyMatch(event -> "CLEANUP".equals(event.event())
			&& Boolean.TRUE.equals(event.cleanupSuccess()) && event.canonicalRemoteId() == 7200));
	}

	@Test
	public void singleUseHasCreationsButNoCanonicalResidency() {
		MatrixObject owner = local("single-use");
		for(int i = 0; i < 2; i++) {
			long id = 7300 + i;
			FederationUtils.materializePlannedRefed(owner, owner.getMutationVersion(), 10, 1, 10, 7,
				"layout", FType.ROW, "", () -> new FakeMap(id));
		}
		RefedReuseAudit.ModeCounters singleUse = mode(RefedReuseAudit.snapshot(), "SINGLE_USE");
		assertEquals(2, singleUse.creationSuccesses());
		assertEquals(0, singleUse.aliases());
		assertEquals(0, singleUse.currentCanonicalCount());
		assertEquals(0, singleUse.peakCanonicalCount());
	}

	@Test
	public void disabledAuditRetainsNoObservations() {
		System.setProperty(RefedReuseAudit.PROPERTY, "false");
		RefedReuseAudit.reset();
		MatrixObject owner = local("disabled");
		planned(owner, new FakeMap(7400), new AtomicInteger());
		RefedReuseAudit.Snapshot snapshot = RefedReuseAudit.snapshot();
		assertFalse(snapshot.enabled());
		assertEquals(0, snapshot.creationAttempts());
		assertEquals(0, snapshot.currentCanonicalCount());
		assertTrue(snapshot.events().isEmpty());
		assertTrue(snapshot.supplyEvents().isEmpty());
	}

	@Test
	public void resetRejectsLiveCanonicalCopies() {
		MatrixObject owner = local("live-at-reset");
		planned(owner, new FakeMap(7500), new AtomicInteger());
		assertThrows(IllegalStateException.class, RefedReuseAudit::reset);
		owner.clearData();
		RefedReuseAudit.reset();
		assertEquals(0, RefedReuseAudit.snapshot().currentCanonicalCount());
	}

	@Test
	public void supplyEventsExposeStableJoinDigestsAndCreationFlag() {
		RefedReuseAudit.recordSupply("action", "X", 42, 3, "group", "layout", true, 8000, true);
		RefedReuseAudit.SupplyEvent event = RefedReuseAudit.snapshot().supplyEvents().get(0);
		assertEquals(RefedReuseAudit.digest("action"), event.actionKeyDigest());
		assertEquals(RefedReuseAudit.digest("layout"), event.layoutDigest());
		assertEquals(42, event.sourceUniqueId());
		assertEquals(3, event.sourceVersion());
		assertTrue(event.staged());
		assertTrue(event.created());
	}

	@Test
	public void workerResetRecordsRemoteCleanupOutcomeWithoutPerEntryRequests() {
		Boolean[] outcomes = {Boolean.TRUE, Boolean.FALSE, null};
		for(int index = 0; index < outcomes.length; index++) {
			long canonicalId = 7600 + index;
			FakeMap canonical = new FakeMap(canonicalId);
			planned(local("worker-reset-" + index), canonical, new AtomicInteger());
			if(outcomes[index] == null)
				FederationUtils.discardOwnedRefedReuseCache();
			else
				FederationUtils.discardOwnedRefedReuseCache(outcomes[index]);
			RefedReuseAudit.Event cleanup = RefedReuseAudit.snapshot().events().stream()
				.filter(event -> "CLEANUP".equals(event.event()) && event.canonicalRemoteId() == canonicalId)
				.findFirst().orElseThrow();
			assertEquals(RefedReuseAudit.WORKER_RESET, cleanup.reason());
			assertEquals(outcomes[index], cleanup.cleanupSuccess());
			assertEquals("Worker-wide CLEAR owns remote cleanup; metadata discard sends none", 0,
				canonical._cleanups);
			assertEquals(0, RefedReuseAudit.snapshot().currentCanonicalCount());
			RefedReuseAudit.reset();
		}
	}

	private static MatrixObject local(String file) {
		return new MatrixObject(ValueType.FP64, file,
			new MetaData(new MatrixCharacteristics(10, 1, 1024, 10)));
	}

	private static void planned(MatrixObject owner, FakeMap canonical, AtomicInteger materializations) {
		FederationUtils.materializePlannedRefed(owner, owner.getMutationVersion(), 10, 1, 10, 7,
			"layout", FType.ROW, "shared", () -> {
				materializations.incrementAndGet();
				return canonical;
			});
	}

	private static void legacy(MatrixObject owner, FakeMap canonical) {
		FederationUtils.getOrCreateOwnedRefedAlias(owner, owner.getMutationVersion(), 10, 1, 10, 7,
			"layout", FType.ROW, () -> canonical);
	}

	private static RefedReuseAudit.ModeCounters mode(RefedReuseAudit.Snapshot snapshot, String name) {
		return snapshot.modes().stream().filter(mode -> name.equals(mode.mode())).findFirst().orElseThrow();
	}
}
