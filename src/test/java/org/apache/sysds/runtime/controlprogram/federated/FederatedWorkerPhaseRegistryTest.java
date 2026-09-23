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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.BatchTag;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.Control;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.ControlOp;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.ErrorCode;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.PhaseIdentity;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.PhaseKind;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.Reply;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.ReplyStatus;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.StreamFence;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse.ResponseType;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.cp.IntObject;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.netty.channel.embedded.EmbeddedChannel;

public class FederatedWorkerPhaseRegistryTest {
	private static final String HOST = "coordinator";
	private static final UUID WORKER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final long WORKER_PID = 41;

	private ExecutorService _tasks;
	private ExecutorService _controls;
	private FederatedWorkerPhaseRegistry _registry;

	@Before
	public void setup() {
		_tasks = Executors.newCachedThreadPool();
		_controls = Executors.newSingleThreadExecutor();
		_registry = new FederatedWorkerPhaseRegistry(WORKER_ID, WORKER_PID, _tasks, _controls);
	}

	@After
	public void cleanup() {
		_registry.shutdownForTests();
	}

	@Test
	public void endWaitsForTrackedChildAndReportsTerminalAccounting() throws Exception {
		FederatedRequest request = new FederatedRequest(RequestType.NOOP);
		PhaseIdentity identity = identity(request.getPID());
		FederatedResponse opened = _registry.handleControl(begin(identity), request.getPID(), HOST).get();
		assertEquals(ReplyStatus.ACK, reply(opened).getStatus());

		UUID stream = UUID.randomUUID();
		BatchTag tag = new BatchTag(identity, WORKER_ID, stream, 0, 1);
		request.setTID(-1);
		request.setPhaseBatchTag(tag);
		CountDownLatch childStarted = new CountDownLatch(1);
		CountDownLatch releaseChild = new CountDownLatch(1);
		CompletableFuture<FederatedResponse> batch = _registry.executeBatch(
			new FederatedRequest[] {request}, HOST, () -> {
				_registry.submitChild(() -> {
					childStarted.countDown();
					await(releaseChild);
				});
				return new FederatedResponse(ResponseType.SUCCESS_EMPTY);
			});
		assertTrue(childStarted.await(5, TimeUnit.SECONDS));
		assertSame(tag, batch.get(5, TimeUnit.SECONDS).getPhaseBatchTag());

		CompletableFuture<FederatedResponse> ending = _registry.handleControl(
			end(identity, stream, 2), request.getPID(), HOST);
		assertFalse("END must wait for descendants", ending.isDone());
		releaseChild.countDown();
		Reply ended = reply(ending.get(5, TimeUnit.SECONDS));
		assertEquals(ReplyStatus.ACK, ended.getStatus());
		assertEquals(ErrorCode.NONE, ended.getError());
		assertEquals(1, ended.getRootsRegistered());
		assertEquals(1, ended.getChildrenRegistered());
		assertEquals(2, ended.getTasksCompleted());
		assertTrue(ended.isRootsClosed());
		assertTrue(ended.isTerminal());
	}

	@Test
	public void mixedBatchFailsBeforeBodyAndQuarantinesPhase() throws Exception {
		FederatedRequest first = new FederatedRequest(RequestType.NOOP);
		PhaseIdentity identity = identity(first.getPID());
		reply(_registry.handleControl(begin(identity), first.getPID(), HOST).get());
		BatchTag tag = new BatchTag(identity, WORKER_ID, UUID.randomUUID(), 0, 1);
		BatchTag other = new BatchTag(identity, WORKER_ID, UUID.randomUUID(), 0, 1);
		first.setPhaseBatchTag(tag);
		FederatedRequest second = new FederatedRequest(RequestType.NOOP);
		second.setPhaseBatchTag(other);
		AtomicBoolean invoked = new AtomicBoolean();
		FederatedResponse response = _registry.executeBatch(new FederatedRequest[] {first, second}, HOST, () -> {
			invoked.set(true);
			return new FederatedResponse(ResponseType.SUCCESS_EMPTY);
		}).get(5, TimeUnit.SECONDS);
		assertFalse(response.isSuccessful());
		assertFalse(invoked.get());
		assertNull(response.getPhaseBatchTag());

		Reply rejected = reply(_registry.handleControl(end(identity, tag.getStreamId(), 2),
			first.getPID(), HOST).get());
		assertEquals(ReplyStatus.QUARANTINED, rejected.getStatus());
		assertEquals(ErrorCode.INVALID_BATCH, rejected.getError());
	}

	@Test
	public void embeddedNettyHandlerEchoesTagAndReturnsOnlyReplyForControls() throws Exception {
		FederatedWorkerHandler handler = new FederatedWorkerHandler(new FederatedLookupTable(),
			new FederatedReadCache(), null, _registry);
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		try {
			FederatedRequest probe = new FederatedRequest(RequestType.NOOP);
			PhaseIdentity identity = identity(probe.getPID());
			channel.writeInbound((Object) new FederatedRequest[] {controlRequest(begin(identity))});
			Reply opened = reply(readOutbound(channel));
			assertEquals(WORKER_ID, opened.getWorkerJvmInstanceId());

			UUID stream = UUID.randomUUID();
			BatchTag tag = new BatchTag(identity, WORKER_ID, stream, 0, 1);
			probe.setTID(-1);
			probe.setPhaseBatchTag(tag);
			channel.writeInbound((Object) new FederatedRequest[] {probe});
			FederatedResponse ordinary = readOutbound(channel);
			assertTrue(ordinary.isSuccessful());
			assertEquals(tag, ordinary.getPhaseBatchTag());

			channel.writeInbound((Object) new FederatedRequest[] {controlRequest(end(identity, stream, 2))});
			FederatedResponse endResponse = readOutbound(channel);
			assertNull("control replies never carry batch tags", endResponse.getPhaseBatchTag());
			Object[] data = endResponse.getData();
			assertEquals(1, data.length);
			assertTrue(data[0] instanceof Reply);
			assertTrue(((Reply) data[0]).isTerminal());
		}
		finally {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	public void embeddedNettyEndWaitsForActualWorkloadAdaptationChild() throws Exception {
		CountDownLatch childStarted = new CountDownLatch(1);
		CountDownLatch releaseChild = new CountDownLatch(1);
		FederatedWorkloadAnalyzer analyzer = new FederatedWorkloadAnalyzer() {
			@Override
			public void incrementWorkload(ExecutionContext ec, long tid, Instruction instruction) {
				// The test only needs the production adaptation scheduling boundary.
			}

			@Override
			public void compressRun(ExecutionContext ec, long tid, FederatedWorkerPhaseRegistry registry) {
				childStarted.countDown();
				await(releaseChild);
			}
		};
		EmbeddedChannel channel = new EmbeddedChannel(new FederatedWorkerHandler(new FederatedLookupTable(),
			new FederatedReadCache(), analyzer, _registry));
		try {
			FederatedRequest probe = new FederatedRequest(RequestType.NOOP);
			PhaseIdentity identity = identity(probe.getPID());
			channel.writeInbound((Object) new FederatedRequest[] {controlRequest(begin(identity))});
			reply(readOutbound(channel));

			UUID stream = UUID.randomUUID();
			FederatedRequest put = new FederatedRequest(RequestType.PUT_VAR, 17, new IntObject(1));
			put.setPhaseBatchTag(new BatchTag(identity, WORKER_ID, stream, 0, 1));
			channel.writeInbound((Object) new FederatedRequest[] {put});
			assertTrue(readOutbound(channel).isSuccessful());

			String rmvar = InstructionUtils.concatOperands("CP", "rmvar", "17");
			FederatedRequest execute = new FederatedRequest(RequestType.EXEC_INST, 17, rmvar);
			execute.setPhaseBatchTag(new BatchTag(identity, WORKER_ID, stream, 0, 2));
			channel.writeInbound((Object) new FederatedRequest[] {execute});
			assertTrue(readOutbound(channel).isSuccessful());
			assertTrue(childStarted.await(5, TimeUnit.SECONDS));

			channel.writeInbound((Object) new FederatedRequest[] {
				controlRequest(end(identity, stream, 2, 2))});
			channel.runPendingTasks();
			assertNull("END replied before adaptation child completed", channel.readOutbound());
			releaseChild.countDown();
			Reply ended = reply(readOutbound(channel));
			assertEquals(1, ended.getChildrenRegistered());
			assertTrue(ended.isTerminal());
		}
		finally {
			releaseChild.countDown();
			channel.finishAndReleaseAll();
		}
	}

	@Test
	public void embeddedNettyMixedBatchIsRejectedBeforeLookupSideEffects() throws Exception {
		AtomicBoolean lookupInvoked = new AtomicBoolean();
		FederatedLookupTable lookup = new FederatedLookupTable() {
			@Override
			public ExecutionContextMap getECM(String host, long pid) {
				lookupInvoked.set(true);
				return super.getECM(host, pid);
			}
		};
		EmbeddedChannel channel = new EmbeddedChannel(new FederatedWorkerHandler(lookup,
			new FederatedReadCache(), null, _registry));
		try {
			FederatedRequest first = new FederatedRequest(RequestType.NOOP);
			PhaseIdentity identity = identity(first.getPID());
			channel.writeInbound((Object) new FederatedRequest[] {controlRequest(begin(identity))});
			reply(readOutbound(channel));

			UUID stream = UUID.randomUUID();
			first.setPhaseBatchTag(new BatchTag(identity, WORKER_ID, stream, 0, 1));
			FederatedRequest second = new FederatedRequest(RequestType.NOOP);
			second.setPhaseBatchTag(new BatchTag(identity, WORKER_ID, UUID.randomUUID(), 0, 1));
			channel.writeInbound((Object) new FederatedRequest[] {first, second});
			assertFalse(readOutbound(channel).isSuccessful());
			assertFalse("mixed envelope reached the execution-context lookup", lookupInvoked.get());

			channel.writeInbound((Object) new FederatedRequest[] {controlRequest(end(identity, stream, 2))});
			Reply ended = reply(readOutbound(channel));
			assertEquals(ReplyStatus.QUARANTINED, ended.getStatus());
			assertEquals(ErrorCode.INVALID_BATCH, ended.getError());
		}
		finally {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	public void embeddedNettyPipeliningPreservesExecutionAndReplyFifo() throws Exception {
		CountDownLatch firstLookup = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicInteger lookups = new AtomicInteger();
		FederatedLookupTable lookup = new FederatedLookupTable() {
			@Override
			public ExecutionContextMap getECM(String host, long pid) {
				if(lookups.incrementAndGet() == 1) {
					firstLookup.countDown();
					await(releaseFirst);
				}
				return super.getECM(host, pid);
			}
		};
		EmbeddedChannel channel = new EmbeddedChannel(new FederatedWorkerHandler(lookup,
			new FederatedReadCache(), null, _registry));
		try {
			FederatedRequest first = new FederatedRequest(RequestType.NOOP);
			PhaseIdentity identity = identity(first.getPID());
			channel.writeInbound((Object) new FederatedRequest[] {controlRequest(begin(identity))});
			reply(readOutbound(channel));

			UUID stream = UUID.randomUUID();
			BatchTag firstTag = new BatchTag(identity, WORKER_ID, stream, 0, 1);
			BatchTag secondTag = new BatchTag(identity, WORKER_ID, stream, 0, 2);
			first.setPhaseBatchTag(firstTag);
			FederatedRequest second = new FederatedRequest(RequestType.NOOP);
			second.setPhaseBatchTag(secondTag);
			channel.writeInbound((Object) new FederatedRequest[] {first});
			assertTrue(firstLookup.await(5, TimeUnit.SECONDS));
			channel.writeInbound((Object) new FederatedRequest[] {second});
			channel.runPendingTasks();
			assertEquals("second batch executed while first was blocked", 1, lookups.get());
			assertNull(channel.readOutbound());

			releaseFirst.countDown();
			assertEquals(firstTag, readOutbound(channel).getPhaseBatchTag());
			assertEquals(secondTag, readOutbound(channel).getPhaseBatchTag());
		}
		finally {
			releaseFirst.countDown();
			channel.finishAndReleaseAll();
		}
	}

	@Test
	public void embeddedNettyQueuedBeginPreventsUntaggedLegacyBypass() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel(new FederatedWorkerHandler(new FederatedLookupTable(),
			new FederatedReadCache(), null, _registry));
		try {
			FederatedRequest untagged = new FederatedRequest(RequestType.NOOP);
			PhaseIdentity identity = identity(untagged.getPID());
			channel.writeInbound((Object) new FederatedRequest[] {controlRequest(begin(identity))});
			channel.writeInbound((Object) new FederatedRequest[] {untagged});

			assertEquals(ReplyStatus.ACK, reply(readOutbound(channel)).getStatus());
			assertFalse("untagged request bypassed queued BEGIN", readOutbound(channel).isSuccessful());
		}
		finally {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	public void embeddedNettyQueuedCloseRoutesFollowingRequestAsLegacyInReplyOrder() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel(new FederatedWorkerHandler(new FederatedLookupTable(),
			new FederatedReadCache(), null, _registry));
		try {
			FederatedRequest legacy = new FederatedRequest(RequestType.NOOP);
			PhaseIdentity identity = identity(legacy.getPID());
			channel.writeInbound((Object) new FederatedRequest[] {controlRequest(begin(identity))});
			reply(readOutbound(channel));
			channel.writeInbound((Object) new FederatedRequest[] {controlRequest(endEmpty(identity, 2))});
			reply(readOutbound(channel));

			channel.writeInbound((Object) new FederatedRequest[] {
				controlRequest(cleanup(identity, 3, ControlOp.CLOSE_SESSION))});
			channel.writeInbound((Object) new FederatedRequest[] {legacy});
			assertEquals(ControlOp.CLOSE_SESSION, reply(readOutbound(channel)).getOp());
			assertTrue("legacy request was routed before queued CLOSE completed",
				readOutbound(channel).isSuccessful());
		}
		finally {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	public void authenticatedCloseReleasesOwnerForCleanNextAttempt() throws Exception {
		FederatedRequest request = new FederatedRequest(RequestType.NOOP);
		PhaseIdentity first = identity(request.getPID());
		reply(_registry.handleControl(begin(first), request.getPID(), HOST).get());
		Reply ended = reply(_registry.handleControl(endEmpty(first, 2), request.getPID(), HOST).get());
		assertTrue(ended.isTerminal());
		Reply closed = reply(_registry.handleControl(cleanup(first, 3, ControlOp.CLOSE_SESSION),
			request.getPID(), HOST).get());
		assertEquals(ReplyStatus.ACK, closed.getStatus());
		assertFalse(_registry.hasStrictSession());

		PhaseIdentity next = identity(request.getPID(), UUID.randomUUID());
		Reply reopened = reply(_registry.handleControl(begin(next), request.getPID(), HOST).get());
		assertEquals(next.getAttemptId(), reopened.getAttemptId());
	}

	@Test
	public void authenticatedAbortClearsQuarantineButUnauthenticatedCloseCannot() throws Exception {
		FederatedRequest request = new FederatedRequest(RequestType.NOOP);
		PhaseIdentity identity = identity(request.getPID());
		reply(_registry.handleControl(begin(identity), request.getPID(), HOST).get());
		_registry.rejectMalformedEnvelope();

		PhaseIdentity impostor = identity(request.getPID(), UUID.randomUUID());
		Reply denied = reply(_registry.handleControl(cleanup(impostor, 2, ControlOp.CLOSE_SESSION),
			request.getPID(), HOST).get());
		assertEquals(ReplyStatus.QUARANTINED, denied.getStatus());
		assertTrue("unauthenticated cleanup released the owner", _registry.hasStrictSession());

		Reply aborted = reply(_registry.handleControl(cleanup(identity, 2, ControlOp.ABORT_SESSION),
			request.getPID(), HOST).get());
		assertEquals(ReplyStatus.QUARANTINED, aborted.getStatus());
		assertFalse(_registry.hasStrictSession());
		PhaseIdentity next = identity(request.getPID(), UUID.randomUUID());
		assertEquals(ReplyStatus.ACK,
			reply(_registry.handleControl(begin(next), request.getPID(), HOST).get()).getStatus());
	}

	@Test
	public void rootRegistrationIsAtomicAgainstEnd() throws Exception {
		BlockingExecuteExecutor tasks = new BlockingExecuteExecutor();
		ExecutorService controls = Executors.newSingleThreadExecutor();
		FederatedWorkerPhaseRegistry registry = new FederatedWorkerPhaseRegistry(
			WORKER_ID, WORKER_PID, tasks, controls);
		try {
			FederatedRequest request = new FederatedRequest(RequestType.NOOP);
			PhaseIdentity identity = identity(request.getPID());
			reply(registry.handleControl(begin(identity), request.getPID(), HOST).get());
			UUID stream = UUID.randomUUID();
			request.setPhaseBatchTag(new BatchTag(identity, WORKER_ID, stream, 0, 1));
			CompletableFuture<CompletableFuture<FederatedResponse>> admitting = CompletableFuture.supplyAsync(() ->
				registry.executeBatch(new FederatedRequest[] {request}, HOST,
					() -> new FederatedResponse(ResponseType.SUCCESS_EMPTY)));
			assertTrue(tasks.executeEntered.await(5, TimeUnit.SECONDS));
			CountDownLatch endAttempted = new CountDownLatch(1);
			CompletableFuture<CompletableFuture<FederatedResponse>> ending = CompletableFuture.supplyAsync(() -> {
				endAttempted.countDown();
				return registry.handleControl(end(identity, stream, 2), request.getPID(), HOST);
			});
			assertTrue(endAttempted.await(5, TimeUnit.SECONDS));
			assertFalse("END crossed an in-progress root registration", ending.isDone());

			tasks.releaseExecute.countDown();
			assertTrue(admitting.get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS).isSuccessful());
			Reply ended = reply(ending.get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS));
			assertEquals(1, ended.getRootsRegistered());
			assertEquals(0, ended.getChildrenRegistered());
			assertEquals(1, ended.getTasksCompleted());
			assertTrue(ended.isTerminal());
		}
		finally {
			tasks.releaseExecute.countDown();
			registry.shutdownForTests();
		}
	}

	@Test
	public void concurrentEndAndAbortUseStableReplyIdentityAndPermitImmediateNewAttempt() throws Exception {
		ExecutorService tasks = Executors.newCachedThreadPool();
		GatedFirstExecuteExecutor controls = new GatedFirstExecuteExecutor();
		FederatedWorkerPhaseRegistry registry = new FederatedWorkerPhaseRegistry(
			WORKER_ID, WORKER_PID, tasks, controls);
		CountDownLatch releaseRoot = new CountDownLatch(1);
		try {
			FederatedRequest request = new FederatedRequest(RequestType.NOOP);
			PhaseIdentity identity = identity(request.getPID());
			reply(registry.handleControl(begin(identity), request.getPID(), HOST).get());
			UUID stream = UUID.randomUUID();
			request.setPhaseBatchTag(new BatchTag(identity, WORKER_ID, stream, 0, 1));
			CountDownLatch rootStarted = new CountDownLatch(1);
			CompletableFuture<FederatedResponse> batch = registry.executeBatch(
				new FederatedRequest[] {request}, HOST, () -> {
					rootStarted.countDown();
					await(releaseRoot);
					return new FederatedResponse(ResponseType.SUCCESS_EMPTY);
				});
			assertTrue(rootStarted.await(5, TimeUnit.SECONDS));

			CompletableFuture<FederatedResponse> ending = registry.handleControl(
				end(identity, stream, 2), request.getPID(), HOST);
			assertTrue(controls.firstQueued.await(5, TimeUnit.SECONDS));
			CompletableFuture<FederatedResponse> aborting = registry.handleControl(
				cleanup(identity, 3, ControlOp.ABORT_SESSION), request.getPID(), HOST);
			releaseRoot.countDown();
			assertTrue(batch.get(5, TimeUnit.SECONDS).isSuccessful());
			Reply aborted = reply(aborting.get(5, TimeUnit.SECONDS));
			assertEquals(identity.getStageSeal(), aborted.getAcceptedStageSeal());
			assertFalse(registry.hasStrictSession());
			assertFalse("gated END unexpectedly constructed its reply", ending.isDone());

			PhaseIdentity next = identity(request.getPID(), UUID.randomUUID());
			assertEquals(ReplyStatus.ACK,
				reply(registry.handleControl(begin(next), request.getPID(), HOST).get()).getStatus());
			controls.releaseFirst();
			Reply ended = reply(ending.get(5, TimeUnit.SECONDS));
			assertEquals(identity.getStageSeal(), ended.getAcceptedStageSeal());
			assertEquals(identity.getSettingsDigest(), ended.getAcceptedSettingsDigest());
			assertFalse("old END receipt crossed into the new attempt",
				next.getAttemptId().equals(ended.getAttemptId()));
		}
		finally {
			releaseRoot.countDown();
			controls.releaseFirst();
			registry.shutdownForTests();
		}
	}

	private static PhaseIdentity identity(long coordinatorPid) {
		return identity(coordinatorPid, UUID.fromString("10000000-0000-0000-0000-000000000001"));
	}

	private static PhaseIdentity identity(long coordinatorPid, UUID attemptId) {
		return new PhaseIdentity(attemptId,
			"condition", "stage", "sources", "settings",
			UUID.fromString("10000000-0000-0000-0000-000000000002"), coordinatorPid, 1, PhaseKind.PLANNING);
	}

	private static Control begin(PhaseIdentity identity) {
		return new Control(identity, null, 1, ControlOp.BEGIN_PHASE, 5_000,
			new StreamFence[0], new String[0], null);
	}

	private static Control end(PhaseIdentity identity, UUID stream, long controlSequence) {
		return end(identity, stream, controlSequence, 1);
	}

	private static Control end(PhaseIdentity identity, UUID stream, long controlSequence, long lastSequence) {
		return new Control(identity, WORKER_ID, controlSequence, ControlOp.END_PHASE, 5_000,
			new StreamFence[] {new StreamFence(stream, 0, lastSequence)}, new String[0], null);
	}

	private static Control endEmpty(PhaseIdentity identity, long controlSequence) {
		return new Control(identity, WORKER_ID, controlSequence, ControlOp.END_PHASE, 5_000,
			new StreamFence[0], new String[0], null);
	}

	private static Control cleanup(PhaseIdentity identity, long controlSequence, ControlOp op) {
		return new Control(identity, WORKER_ID, controlSequence, op, 5_000,
			new StreamFence[0], new String[0], null);
	}

	private static FederatedRequest controlRequest(Control control) {
		return new FederatedRequest(RequestType.PHASE_CONTROL, -1, control);
	}

	private static Reply reply(FederatedResponse response) throws Exception {
		assertTrue(response.isSuccessful() ? "" : response.getErrorMessage(), response.isSuccessful());
		Object[] data = response.getData();
		assertEquals(1, data.length);
		return (Reply) data[0];
	}

	private static FederatedResponse readOutbound(EmbeddedChannel channel) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		FederatedResponse response;
		do {
			channel.runPendingTasks();
			response = channel.readOutbound();
			if(response == null)
				Thread.sleep(5);
		}
		while(response == null && System.nanoTime() < deadline);
		assertTrue("worker response timed out", response != null);
		return response;
	}

	private static void await(CountDownLatch latch) {
		try {
			if(!latch.await(5, TimeUnit.SECONDS))
				throw new AssertionError("latch timed out");
		}
		catch(InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AssertionError(ex);
		}
	}

	private static final class BlockingExecuteExecutor extends AbstractExecutorService {
		private final ExecutorService delegate = Executors.newSingleThreadExecutor();
		private final CountDownLatch executeEntered = new CountDownLatch(1);
		private final CountDownLatch releaseExecute = new CountDownLatch(1);

		@Override
		public void execute(Runnable command) {
			executeEntered.countDown();
			await(releaseExecute);
			delegate.execute(command);
		}

		@Override
		public void shutdown() { delegate.shutdown(); }

		@Override
		public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }

		@Override
		public boolean isShutdown() { return delegate.isShutdown(); }

		@Override
		public boolean isTerminated() { return delegate.isTerminated(); }

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
			return delegate.awaitTermination(timeout, unit);
		}
	}

	private static final class GatedFirstExecuteExecutor extends AbstractExecutorService {
		private final ExecutorService delegate = Executors.newCachedThreadPool();
		private final AtomicInteger submissions = new AtomicInteger();
		private final AtomicReference<Runnable> first = new AtomicReference<>();
		private final CountDownLatch firstQueued = new CountDownLatch(1);

		@Override
		public void execute(Runnable command) {
			if(submissions.incrementAndGet() == 1) {
				first.set(command);
				firstQueued.countDown();
			}
			else
				delegate.execute(command);
		}

		private void releaseFirst() {
			Runnable command = first.getAndSet(null);
			if(command != null)
				delegate.execute(command);
		}

		@Override
		public void shutdown() { delegate.shutdown(); }

		@Override
		public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }

		@Override
		public boolean isShutdown() { return delegate.isShutdown(); }

		@Override
		public boolean isTerminated() { return delegate.isTerminated(); }

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
			return delegate.awaitTermination(timeout, unit);
		}
	}
}
