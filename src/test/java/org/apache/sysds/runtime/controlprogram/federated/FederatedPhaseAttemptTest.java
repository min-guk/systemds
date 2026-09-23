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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseAttempt.PhaseHandle;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.BatchTag;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.Control;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.ControlOp;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.ErrorCode;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.PhaseIdentity;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.PhaseKind;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.Reply;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.ReplyStatus;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.SourceResidencyReceipt;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.StreamFence;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse.ResponseType;
import org.junit.Test;

public class FederatedPhaseAttemptTest {
	private static final Executor DIRECT = Runnable::run;
	private static final InetSocketAddress W1 = InetSocketAddress.createUnresolved("worker-one", 19001);
	private static final InetSocketAddress W2 = InetSocketAddress.createUnresolved("worker-two", 19002);
	private static final UUID ATTEMPT = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID COORDINATOR = UUID.fromString("10000000-0000-0000-0000-000000000002");

	@Test
	public void allocationPrecedesNetworkAndPartialBeginRequiresTeardown() {
		FakeTransport transport = new FakeTransport(W1, W2);
		transport.failControlNumber = 2;
		FederatedPhaseAttempt attempt = attempt(transport, workers(W1, W2), Duration.ofSeconds(2));
		assertEquals(0, transport.controls.size());
		assertFalse(attempt.isTeardownRequired());

		expectFailure(attempt::beginPlanning);
		assertEquals(2, transport.controls.size());
		assertTrue(attempt.isPoisoned());
		assertTrue(attempt.isTeardownRequired());
		assertFalse(attempt.isWorkerOwnerReleased());
		assertNotNull(attempt.getFailure());
	}

	@Test
	public void planningAndWarmupShareOwnerSequencesAndDrainIgnoredResponsesAndChildren() throws Exception {
		FakeTransport transport = new FakeTransport(W1, W2);
		FederatedPhaseAttempt attempt = attempt(transport, workers(W1, W2), Duration.ofSeconds(3));
		PhaseHandle planning = attempt.beginPlanning();
		planning.submitRoot(DIRECT, () -> {
			planning.submitChild(DIRECT, () -> { });
			planning.dispatch(W1, request()); // caller deliberately discards the future
			planning.dispatch(W2, request());
		});
		planning.closeRootAdmission();
		FederatedPhaseAttempt.Result planningResult = planning.finish();
		assertEquals(2, planningResult.getTasksRegistered());
		assertEquals(2, planningResult.getTasksCompleted());
		assertEquals(2, planningResult.getResponsesDrained());

		PhaseHandle warmup = attempt.nextWarmup(warmupIdentity());
		warmup.submitRoot(DIRECT, () -> {
			warmup.dispatch(W1, request());
			warmup.dispatch(W2, request());
		});
		warmup.closeRootAdmission();
		FederatedPhaseAttempt.Result warmupResult = warmup.finish();
		assertEquals(2, warmupResult.getResponsesDrained());
		assertFalse(attempt.isPoisoned());
		assertTrue(attempt.isTeardownRequired());
		assertFalse(attempt.isWorkerOwnerReleased());
		assertEquals(Arrays.asList(1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L), transport.controlSequences());
	}

	@Test
	public void warmupWithoutEveryDeclaredWorkerPoisonsBeforeEnd() throws Exception {
		FakeTransport transport = new FakeTransport(W1, W2);
		FederatedPhaseAttempt attempt = attempt(transport, workers(W1, W2), Duration.ofSeconds(2));
		PhaseHandle planning = attempt.beginPlanning();
		planning.submitRoot(DIRECT, () -> {
			planning.dispatch(W1, request());
			planning.dispatch(W2, request());
		});
		planning.closeRootAdmission();
		planning.finish();

		PhaseHandle warmup = attempt.nextWarmup(warmupIdentity());
		warmup.submitRoot(DIRECT, () -> warmup.dispatch(W1, request()));
		warmup.closeRootAdmission();
		expectFailure(warmup::finish);
		assertTrue(attempt.isPoisoned());
		assertEquals(2, transport.controlOps().stream()
			.filter(op -> op == ControlOp.END_PHASE).count());
	}

	@Test
	public void zeroCoordinatorRootCannotSucceedOrSendEnd() throws Exception {
		FakeTransport transport = new FakeTransport(W1);
		FederatedPhaseAttempt attempt = attempt(transport, workers(W1), Duration.ofSeconds(2));
		PhaseHandle planning = attempt.beginPlanning();
		planning.closeRootAdmission();
		expectFailure(planning::finish);
		assertEquals(Collections.singletonList(ControlOp.BEGIN_PHASE), transport.controlOps());
		assertTrue(attempt.isPoisoned());
	}

	@Test
	public void wrongWorkerRootCountPoisonsAttempt() throws Exception {
		FakeTransport transport = new FakeTransport(W1);
		transport.wrongRootCount = true;
		FederatedPhaseAttempt attempt = attempt(transport, workers(W1), Duration.ofSeconds(2));
		PhaseHandle planning = phaseWithOneDispatch(attempt, W1);
		expectFailure(planning::finish);
		assertTrue(attempt.isPoisoned());
	}

	@Test
	public void wrongWorkerFencePoisonsAttempt() throws Exception {
		FakeTransport transport = new FakeTransport(W1);
		transport.wrongFence = true;
		FederatedPhaseAttempt attempt = attempt(transport, workers(W1), Duration.ofSeconds(2));
		PhaseHandle planning = phaseWithOneDispatch(attempt, W1);
		expectFailure(planning::finish);
		assertTrue(attempt.isPoisoned());
	}

	@Test
	public void changedWorkerPidAtWarmupPoisonsAttempt() throws Exception {
		FakeTransport transport = new FakeTransport(W1);
		FederatedPhaseAttempt attempt = attempt(transport, workers(W1), Duration.ofSeconds(2));
		finishOneDispatch(attempt.beginPlanning(), W1);
		transport.changePidOnWarmup = true;
		expectFailure(() -> attempt.nextWarmup(warmupIdentity()));
		assertTrue(attempt.isPoisoned());
		assertTrue(attempt.isTeardownRequired());
	}

	@Test
	public void invalidWarmupSuccessorPoisonsAttemptWithoutSendingBegin() throws Exception {
		FakeTransport transport = new FakeTransport(W1);
		FederatedPhaseAttempt attempt = attempt(transport, workers(W1), Duration.ofSeconds(2));
		finishOneDispatch(attempt.beginPlanning(), W1);
		PhaseIdentity wrong = identity(3, PhaseKind.WARMUP);
		expectFailure(() -> attempt.nextWarmup(wrong));
		assertEquals(Arrays.asList(ControlOp.BEGIN_PHASE, ControlOp.END_PHASE), transport.controlOps());
		assertTrue(attempt.isPoisoned());
	}

	@Test
	public void lostEndPoisonsAndRequiresTeardown() throws Exception {
		FakeTransport transport = new FakeTransport(W1);
		transport.loseEnd = true;
		FederatedPhaseAttempt attempt = attempt(transport, workers(W1), Duration.ofMillis(150));
		PhaseHandle planning = phaseWithOneDispatch(attempt, W1);
		expectFailure(planning::finish);
		assertTrue(attempt.isPoisoned());
		assertTrue(attempt.isTeardownRequired());
		assertFalse(attempt.isWorkerOwnerReleased());
	}

	@Test
	public void duplicateFinishDoesNotSendSecondEndAndPoisonsOwner() throws Exception {
		FakeTransport transport = new FakeTransport(W1);
		FederatedPhaseAttempt attempt = attempt(transport, workers(W1), Duration.ofSeconds(2));
		PhaseHandle planning = phaseWithOneDispatch(attempt, W1);
		planning.finish();
		expectFailure(planning::finish);
		assertEquals(Arrays.asList(ControlOp.BEGIN_PHASE, ControlOp.END_PHASE), transport.controlOps());
		assertTrue(attempt.isPoisoned());
	}

	@Test
	public void stalePlanningDispatchAfterWarmupHandoffPoisonsSharedOwner() throws Exception {
		FakeTransport transport = new FakeTransport(W1);
		FederatedPhaseAttempt attempt = attempt(transport, workers(W1), Duration.ofSeconds(2));
		PhaseHandle planning = attempt.beginPlanning();
		finishOneDispatch(planning, W1);
		attempt.nextWarmup(warmupIdentity());
		expectFailure(() -> planning.dispatch(W1, request()));
		assertTrue(attempt.isPoisoned());
	}

	@Test
	public void unboundDispatchPoisonsEvenWhenCallerCatches() throws Exception {
		FakeTransport transport = new FakeTransport(W1);
		FederatedPhaseAttempt attempt = attempt(transport, workers(W1), Duration.ofSeconds(2));
		PhaseHandle planning = attempt.beginPlanning();
		expectFailure(() -> planning.dispatch(W1, request()));
		assertTrue(attempt.isPoisoned());
	}

	@Test
	public void asynchronousOrdinaryFailurePoisonsEvenWhenCallerConsumesFuture() throws Exception {
		FakeTransport transport = new FakeTransport(W1);
		transport.failOrdinary = true;
		FederatedPhaseAttempt attempt = attempt(transport, workers(W1), Duration.ofSeconds(2));
		PhaseHandle planning = attempt.beginPlanning();
		AtomicReference<Future<FederatedResponse>> sent = new AtomicReference<>();
		planning.submitRoot(DIRECT, () -> sent.set(planning.dispatch(W1, request())));
		expectFailure(() -> sent.get().get());
		assertTrue(attempt.isPoisoned());
	}

	@Test
	public void concurrentNextWarmupSendsAtMostOneBeginAndPoisonsAmbiguity() throws Exception {
		FakeTransport transport = new FakeTransport(W1);
		FederatedPhaseAttempt attempt = attempt(transport, workers(W1), Duration.ofSeconds(3));
		finishOneDispatch(attempt.beginPlanning(), W1);
		transport.blockWarmBegin = true;
		AtomicReference<Throwable> firstFailure = new AtomicReference<>();
		Thread first = new Thread(() -> {
			try {
				attempt.nextWarmup(warmupIdentity());
			}
			catch(Throwable ex) {
				firstFailure.set(ex);
			}
		});
		first.start();
		assertTrue(transport.warmBeginEntered.await(2, TimeUnit.SECONDS));
		expectFailure(() -> attempt.nextWarmup(warmupIdentity()));
		transport.releaseWarmBegin.countDown();
		first.join(2000);
		assertNotNull(firstFailure.get());
		assertEquals(1, transport.controlOps().stream()
			.filter(op -> op == ControlOp.BEGIN_PHASE).count() - 1);
		assertTrue(attempt.isPoisoned());
	}

	private static PhaseHandle phaseWithOneDispatch(FederatedPhaseAttempt attempt,
		InetSocketAddress worker) throws Exception {
		PhaseHandle phase = attempt.beginPlanning();
		phase.submitRoot(DIRECT, () -> phase.dispatch(worker, request()));
		phase.closeRootAdmission();
		return phase;
	}

	private static void finishOneDispatch(PhaseHandle phase, InetSocketAddress worker) throws Exception {
		phase.submitRoot(DIRECT, () -> phase.dispatch(worker, request()));
		phase.closeRootAdmission();
		phase.finish();
	}

	private static FederatedPhaseAttempt attempt(FakeTransport transport,
		Set<InetSocketAddress> workers, Duration timeout) {
		return FederatedPhaseAttempt.allocateForTest(planningIdentity(), workers, timeout, transport);
	}

	private static PhaseIdentity planningIdentity() {
		return identity(1, PhaseKind.PLANNING);
	}

	private static PhaseIdentity warmupIdentity() {
		return identity(2, PhaseKind.WARMUP);
	}

	private static PhaseIdentity identity(long epoch, PhaseKind kind) {
		return new PhaseIdentity(ATTEMPT, "condition", "stage", "manifest", "settings",
			COORDINATOR, ProcessHandle.current().pid(), epoch, kind);
	}

	private static FederatedRequest request() {
		FederatedRequest request = new FederatedRequest(RequestType.NOOP);
		request.setTID(0);
		return request;
	}

	private static Set<InetSocketAddress> workers(InetSocketAddress... addresses) {
		return new LinkedHashSet<>(Arrays.asList(addresses));
	}

	private static void expectFailure(Throwing action) {
		try {
			action.run();
			fail("Expected failure");
		}
		catch(Throwable expected) {
			// expected
		}
	}

	@FunctionalInterface
	private interface Throwing {
		void run() throws Exception;
	}

	private static final class FakeTransport implements FederatedPhaseAttempt.Transport {
		private final Map<InetSocketAddress, UUID> workerIds = new LinkedHashMap<>();
		private final Map<InetSocketAddress, Long> workerPids = new LinkedHashMap<>();
		private final Map<String, Long> ordinaryRoots = new LinkedHashMap<>();
		private final List<SentControl> controls = Collections.synchronizedList(new ArrayList<>());
		private int failControlNumber;
		private boolean wrongRootCount;
		private boolean wrongFence;
		private boolean changePidOnWarmup;
		private boolean loseEnd;
		private boolean failOrdinary;
		private boolean blockWarmBegin;
		private final CountDownLatch warmBeginEntered = new CountDownLatch(1);
		private final CountDownLatch releaseWarmBegin = new CountDownLatch(1);

		private FakeTransport(InetSocketAddress... workers) {
			for(int i = 0; i < workers.length; i++) {
				workerIds.put(workers[i], new UUID(200, i + 1));
				workerPids.put(workers[i], 500L + i);
			}
		}

		@Override
		public Future<FederatedResponse> control(InetSocketAddress address, Control control) {
			controls.add(new SentControl(address, control));
			if(failControlNumber > 0 && controls.size() == failControlNumber)
				return failedFuture(new IllegalStateException("control failed"));
			if(blockWarmBegin && control.getOp() == ControlOp.BEGIN_PHASE
				&& control.getIdentity().getKind() == PhaseKind.WARMUP) {
				warmBeginEntered.countDown();
				await(releaseWarmBegin);
			}
			if(loseEnd && control.getOp() == ControlOp.END_PHASE)
				return new CompletableFuture<>();
			UUID workerId = workerIds.get(address);
			long workerPid = workerPids.get(address);
			if(changePidOnWarmup && control.getIdentity().getKind() == PhaseKind.WARMUP)
				workerPid++;
			long roots = control.getOp() == ControlOp.END_PHASE
				? ordinaryRoots.getOrDefault(key(address, control.getIdentity().getEpoch()), 0L) : 0;
			if(wrongRootCount && control.getOp() == ControlOp.END_PHASE)
				roots++;
			StreamFence[] fences = control.getOp() == ControlOp.END_PHASE ? control.getFences() : new StreamFence[0];
			if(wrongFence && control.getOp() == ControlOp.END_PHASE)
				fences = new StreamFence[] {new StreamFence(UUID.randomUUID(), 0, 1)};
			boolean terminal = control.getOp() == ControlOp.END_PHASE;
			Reply reply = new Reply(control.getIdentity().getAttemptId(), control.getIdentity().getEpoch(),
				control.getControlSequence(), control.getOp(), ReplyStatus.ACK, ErrorCode.NONE,
				workerId, workerPid, control.getIdentity().getStageSeal(), control.getIdentity().getSettingsDigest(),
				fences, roots, terminal ? 1 : 0, roots + (terminal ? 1 : 0), 0, 0, 0,
				terminal, terminal, false, null, new SourceResidencyReceipt[0]);
			return CompletableFuture.completedFuture(new FederatedResponse(ResponseType.SUCCESS, reply));
		}

		@Override
		public Future<FederatedResponse> ordinary(InetSocketAddress address, FederatedRequest[] requests) {
			BatchTag tag = requests[0].getPhaseBatchTag();
			ordinaryRoots.merge(key(address, tag.getIdentity().getEpoch()), 1L, Long::sum);
			if(failOrdinary)
				return failedFuture(new IllegalStateException("ordinary failed"));
			FederatedResponse response = new FederatedResponse(ResponseType.SUCCESS_EMPTY);
			response.setPhaseBatchTag(tag);
			return CompletableFuture.completedFuture(response);
		}

		private List<Long> controlSequences() {
			List<Long> result = new ArrayList<>();
			for(SentControl sent : controls)
				result.add(sent.control.getControlSequence());
			return result;
		}

		private List<ControlOp> controlOps() {
			List<ControlOp> result = new ArrayList<>();
			for(SentControl sent : controls)
				result.add(sent.control.getOp());
			return result;
		}

		private static String key(InetSocketAddress address, long epoch) {
			return address.toString() + ":" + epoch;
		}

		private static <T> CompletableFuture<T> failedFuture(Throwable failure) {
			CompletableFuture<T> future = new CompletableFuture<>();
			future.completeExceptionally(failure);
			return future;
		}
	}

	private static final class SentControl {
		private final InetSocketAddress address;
		private final Control control;

		private SentControl(InetSocketAddress address, Control control) {
			this.address = address;
			this.control = control;
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if(!latch.await(2, TimeUnit.SECONDS))
				throw new IllegalStateException("latch timeout");
		}
		catch(InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}
}
