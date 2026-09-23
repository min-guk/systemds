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

package org.apache.sysds.hops.recompile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.recompile.RuntimeRecompileAudit.Binding;
import org.apache.sysds.hops.recompile.RuntimeRecompileAudit.EventType;
import org.apache.sysds.hops.recompile.RuntimeRecompileAudit.InvalidReason;
import org.apache.sysds.hops.recompile.RuntimeRecompileAudit.Observation;
import org.apache.sysds.hops.recompile.RuntimeRecompileAudit.PhaseToken;
import org.apache.sysds.hops.recompile.RuntimeRecompileAudit.Session;
import org.apache.sysds.hops.recompile.RuntimeRecompileAudit.Snapshot;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.controlprogram.BasicProgramBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.ProgramBlock;
import org.junit.Test;

@net.jcip.annotations.NotThreadSafe
public class RuntimeRecompileAuditTest {
	@Test
	public void disabledObservationHasNoSideEffects() {
		Observation observation = RuntimeRecompileAudit.observeDag(new DMLProgram(), false);
		observation.succeeded();
		observation.close();
	}

	@Test
	public void onlyOneProcessWideSessionMayBeActive() {
		try(Session session = RuntimeRecompileAudit.start(new DMLProgram(), 4)) {
			assertThrows(IllegalStateException.class,
				() -> RuntimeRecompileAudit.start(new DMLProgram(), 4));
		}
	}

	@Test
	public void ownerMismatchIsStickyAndNotRecorded() {
		DMLProgram expected = new DMLProgram();
		try(Session session = RuntimeRecompileAudit.start(expected, 4);
			PhaseToken phase = session.newPhase(); Binding ignored = session.bind(phase)) {
			RuntimeRecompileAudit.observeDag(new DMLProgram(), false).close();
			Snapshot snapshot = session.snapshot();
			assertFalse(snapshot.isValid());
			assertTrue(snapshot.getInvalidReasons().contains(InvalidReason.OWNER_MISMATCH));
			assertTrue(snapshot.getEvents().isEmpty());
		}
	}

	@Test
	public void unboundAndStaleCallsInvalidateWithoutPhaseAttribution() {
		DMLProgram expected = new DMLProgram();
		try(Session session = RuntimeRecompileAudit.start(expected, 4)) {
			RuntimeRecompileAudit.observeDag(expected, false).close();
			PhaseToken phase = session.newPhase();
			try(Binding ignored = session.bind(phase)) {
				phase.close();
				RuntimeRecompileAudit.observeDag(expected, false).close();
			}
			Snapshot snapshot = session.snapshot();
			assertTrue(snapshot.getInvalidReasons().contains(InvalidReason.UNBOUND_THREAD));
			assertTrue(snapshot.getInvalidReasons().contains(InvalidReason.STALE_BINDING));
			assertTrue(snapshot.getEvents().isEmpty());
		}
	}

	@Test
	public void admittedConcurrentEventMayFinishAfterPhaseClosure() throws Exception {
		DMLProgram expected = new DMLProgram();
		try(Session session = RuntimeRecompileAudit.start(expected, 4); PhaseToken phase = session.newPhase()) {
			CountDownLatch admitted = new CountDownLatch(1);
			CountDownLatch finish = new CountDownLatch(1);
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Thread thread = new Thread(() -> {
				try(Binding ignored = session.bind(phase);
					Observation observation = RuntimeRecompileAudit.observeDag(expected, false)) {
					admitted.countDown();
					if(!finish.await(10, TimeUnit.SECONDS))
						throw new AssertionError("timed out");
					observation.succeeded();
				}
				catch(Throwable ex) {
					failure.set(ex);
				}
			});
			thread.start();
			assertTrue(admitted.await(10, TimeUnit.SECONDS));
			Snapshot partial = session.snapshot();
			assertEquals(1, partial.getOutstandingEvents());
			assertFalse(partial.isValid());
			phase.close();
			finish.countDown();
			thread.join(10000);
			assertFalse(thread.isAlive());
			if(failure.get() != null)
				throw new AssertionError(failure.get());
			Snapshot snapshot = session.snapshot();
			assertEquals(0, snapshot.getOutstandingEvents());
			assertEquals(1, snapshot.getEvents().size());
			assertTrue(snapshot.getEvents().get(0).isSuccess());
		}
	}

	@Test
	public void overflowIsBoundedAndReportedAsTruncation() {
		DMLProgram expected = new DMLProgram();
		try(Session session = RuntimeRecompileAudit.start(expected, 2);
			PhaseToken phase = session.newPhase(); Binding ignored = session.bind(phase)) {
			for(int i = 0; i < 5; i++) {
				Observation observation = RuntimeRecompileAudit.observeDag(expected, false);
				observation.succeeded();
				observation.close();
			}
			Snapshot snapshot = session.snapshot();
			assertEquals(2, snapshot.getEvents().size());
			assertEquals(3, snapshot.getDroppedEvents());
			assertTrue(snapshot.isTruncated());
			assertFalse(snapshot.isValid());
		}
	}

	@Test
	public void onlyClosedCompleteSnapshotIsValid() {
		DMLProgram expected = new DMLProgram();
		Session session = RuntimeRecompileAudit.start(expected, 2);
		try {
			PhaseToken phase = session.newPhase();
			try(Binding ignored = session.bind(phase)) {
				Observation observation = RuntimeRecompileAudit.observeDag(expected, false);
				observation.succeeded();
				observation.close();
				assertFalse(session.snapshot().isValid());
			}
			phase.close();
			session.close();
			assertTrue(session.snapshot().isValid());
		}
		finally {
			session.close();
		}
	}

	@Test
	public void failedObservationIsRecordedWithoutExceptionDetails() {
		DMLProgram expected = new DMLProgram();
		try(Session session = RuntimeRecompileAudit.start(expected, 4);
			PhaseToken phase = session.newPhase(); Binding ignored = session.bind(phase)) {
			IllegalStateException original = new IllegalStateException("sensitive compiler detail");
			IllegalStateException actual = assertThrows(IllegalStateException.class, () -> {
				try(Observation observation = RuntimeRecompileAudit.observeDag(expected, false)) {
					throw original;
				}
			});
			assertTrue(actual == original);
			Snapshot snapshot = session.snapshot();
			assertEquals(1, snapshot.getEvents().size());
			assertFalse(snapshot.getEvents().get(0).isSuccess());
			assertEquals(EventType.DAG_NORMAL, snapshot.getEvents().get(0).getType());
		}
	}

	@Test
	public void failedHierarchyCallPreservesOriginalExceptionAndRecordsFailure() {
		DMLProgram expected = new DMLProgram();
		Program runtimeProgram = new Program(expected);
		IllegalStateException original = new IllegalStateException("sensitive compiler detail");
		ArrayList<ProgramBlock> blocks = new ArrayList<>();
		blocks.add(new BasicProgramBlock(runtimeProgram) {
			@Override
			public StatementBlock getStatementBlock() {
				throw original;
			}
		});
		try(Session session = RuntimeRecompileAudit.start(expected, 4);
			PhaseToken phase = session.newPhase(); Binding ignored = session.bind(phase)) {
			IllegalStateException actual = assertThrows(IllegalStateException.class,
				() -> Recompiler.recompileProgramBlockHierarchy(blocks, new LocalVariableMap(), 0, true,
					Recompiler.ResetType.NO_RESET));
			assertTrue(actual == original);
			Snapshot snapshot = session.snapshot();
			assertEquals(1, snapshot.getEvents().size());
			assertEquals(EventType.HIERARCHY_NORMAL, snapshot.getEvents().get(0).getType());
			assertFalse(snapshot.getEvents().get(0).isSuccess());
		}
	}

	@Test
	public void nestedHierarchyAndDagDurationsRemainSeparate() {
		DMLProgram expected = new DMLProgram();
		try(Session session = RuntimeRecompileAudit.start(expected, 4);
			PhaseToken phase = session.newPhase(); Binding ignored = session.bind(phase);
			Observation hierarchy = RuntimeRecompileAudit.observeHierarchy(expected, false)) {
			try(Observation dag = RuntimeRecompileAudit.observeDag(expected, true)) {
				dag.succeeded();
			}
			hierarchy.succeeded();
			hierarchy.close();
			Snapshot snapshot = session.snapshot();
			assertEquals(2, snapshot.getEvents().size());
			assertTrue(snapshot.getDagDurationNanos() >= 0);
			assertTrue(snapshot.getHierarchyDurationNanos() >= snapshot.getDagDurationNanos());
			assertFalse(snapshot.isAsyncQuiescenceVerified());
		}
	}

	@Test
	public void normalAndForcedHierarchyEntryPointsAreObserved() {
		DMLProgram expected = new DMLProgram();
		Program runtimeProgram = new Program(expected);
		ArrayList<ProgramBlock> blocks = new ArrayList<>();
		blocks.add(new BasicProgramBlock(runtimeProgram));
		try(Session session = RuntimeRecompileAudit.start(expected, 4);
			PhaseToken phase = session.newPhase(); Binding ignored = session.bind(phase)) {
			Recompiler.recompileProgramBlockHierarchy(blocks, new LocalVariableMap(), 0, true,
				Recompiler.ResetType.NO_RESET);
			Recompiler.recompileProgramBlockHierarchy2Forced(blocks, 0, Collections.emptySet(), null);
			Snapshot snapshot = session.snapshot();
			assertEquals(2, snapshot.getEvents().size());
			assertEquals(EventType.HIERARCHY_NORMAL, snapshot.getEvents().get(0).getType());
			assertEquals(EventType.HIERARCHY_FORCED, snapshot.getEvents().get(1).getType());
			assertTrue(snapshot.getEvents().get(0).isSuccess());
			assertTrue(snapshot.getEvents().get(1).isSuccess());
		}
	}

	@Test
	public void coreNormalAndForcedDagEntryPointsAreObserved() {
		DMLProgram expected = new DMLProgram();
		try(Session session = RuntimeRecompileAudit.start(expected, 4);
			PhaseToken phase = session.newPhase(); Binding ignored = session.bind(phase)) {
			Recompiler.recompileHopsDag(new LiteralOp(1L), new LocalVariableMap(), null,
				true, false, 0, expected);
			Recompiler.recompileHopsDag2Forced(new LiteralOp(2L), 0, ExecType.CP, expected);
			Snapshot snapshot = session.snapshot();
			assertEquals(2, snapshot.getEvents().size());
			assertEquals(EventType.DAG_NORMAL, snapshot.getEvents().get(0).getType());
			assertEquals(EventType.DAG_FORCED, snapshot.getEvents().get(1).getType());
			assertTrue(snapshot.getEvents().get(0).isSuccess());
			assertTrue(snapshot.getEvents().get(1).isSuccess());
		}
	}

	@Test
	public void hierarchyOwnerInspectionRetainsCallerListSynchronization() throws Exception {
		DMLProgram expected = new DMLProgram();
		Program runtimeProgram = new Program(expected);
		CountDownLatch ownerInspected = new CountDownLatch(1);
		ArrayList<ProgramBlock> blocks = new ArrayList<>();
		blocks.add(new BasicProgramBlock(runtimeProgram) {
			@Override
			public Program getProgram() {
				ownerInspected.countDown();
				return super.getProgram();
			}
		});
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread thread = new Thread(() -> {
			try {
				Recompiler.recompileProgramBlockHierarchy(blocks, new LocalVariableMap(), 0, true,
					Recompiler.ResetType.NO_RESET);
			}
			catch(Throwable ex) {
				failure.set(ex);
			}
		});
		synchronized(blocks) {
			thread.start();
			assertFalse(ownerInspected.await(250, TimeUnit.MILLISECONDS));
		}
		thread.join(10000);
		assertFalse(thread.isAlive());
		assertTrue(ownerInspected.await(1, TimeUnit.SECONDS));
		if(failure.get() != null)
			throw new AssertionError(failure.get());
	}
}
