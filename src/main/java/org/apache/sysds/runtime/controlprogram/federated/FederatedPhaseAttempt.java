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

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseTasks.PhaseToken;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.BatchTag;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.Control;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.ControlOp;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.PhaseIdentity;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.PhaseKind;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.Reply;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.ReplyStatus;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.StreamFence;

/**
 * Attempt-scoped owner for the bounded STRICT PLANNING to WARMUP lifecycle.
 *
 * The owner is allocated before any network operation. One owner retains the declared endpoint set,
 * discovered worker UUID/PID pairs, continuous per-worker control sequences, coordinator task trees,
 * and ordinary response journals for both phases. Any ambiguous transition, rejected dispatch, or
 * transport failure poisons the owner permanently. The only recovery is external teardown of the
 * attempt's dedicated coordinator and worker containers; this class never treats ABORT or CLOSE as
 * proof that an ambiguous attempt became reusable.
 *
 * PREREAD, TIMED, warm-state reset, and full-program installation are intentionally not exposed.
 */
public final class FederatedPhaseAttempt {
	private enum State {
		ALLOCATED,
		PLANNING_STARTING, PLANNING_OPEN, PLANNING_ROOTS_CLOSED, PLANNING_FINISHING, PLANNING_DONE,
		WARMUP_STARTING, WARMUP_OPEN, WARMUP_ROOTS_CLOSED, WARMUP_FINISHING, WARMUP_DONE,
		POISONED
	}

	interface Transport {
		Future<FederatedResponse> control(InetSocketAddress address, Control control);
		Future<FederatedResponse> ordinary(InetSocketAddress address, FederatedRequest[] requests);
	}

	private static final Transport PRODUCTION_TRANSPORT = new Transport() {
		@Override
		public Future<FederatedResponse> control(InetSocketAddress address, Control control) {
			return FederatedData.executeNativePhaseControl(address, control);
		}

		@Override
		public Future<FederatedResponse> ordinary(InetSocketAddress address, FederatedRequest[] requests) {
			return FederatedData.executeTaggedPhaseOperation(address, requests);
		}
	};

	private final Object _gate = new Object();
	private final PhaseIdentity _planningIdentity;
	private final Transport _transport;
	private final long _deadlineNanos;
	private final Map<InetSocketAddress, Worker> _workers = new LinkedHashMap<>();
	private final ThreadLocal<Phase> _boundPhase = new ThreadLocal<>();
	private State _state = State.ALLOCATED;
	private Phase _phase;
	private Throwable _failure;
	private boolean _networkStarted;

	/** Allocate a local attempt handle. This method performs no network operation. */
	public static FederatedPhaseAttempt allocate(PhaseIdentity planningIdentity,
		Set<InetSocketAddress> endpoints, Duration overallTimeout) {
		return new FederatedPhaseAttempt(planningIdentity, endpoints, overallTimeout, PRODUCTION_TRANSPORT);
	}

	static FederatedPhaseAttempt allocateForTest(PhaseIdentity planningIdentity,
		Set<InetSocketAddress> endpoints, Duration overallTimeout, Transport transport) {
		return new FederatedPhaseAttempt(planningIdentity, endpoints, overallTimeout, transport);
	}

	private FederatedPhaseAttempt(PhaseIdentity planningIdentity, Set<InetSocketAddress> endpoints,
		Duration overallTimeout, Transport transport) {
		if(planningIdentity == null || planningIdentity.getKind() != PhaseKind.PLANNING
			|| planningIdentity.getEpoch() != FederatedPhaseWire.INITIAL_EPOCH)
			throw new IllegalArgumentException("Attempt must start with initial PLANNING identity");
		if(endpoints == null || endpoints.isEmpty() || endpoints.contains(null))
			throw new IllegalArgumentException("Attempt requires a non-empty declared endpoint set");
		if(transport == null)
			throw new IllegalArgumentException("Attempt transport must be non-null");
		_planningIdentity = planningIdentity;
		_transport = transport;
		_deadlineNanos = saturatingAdd(System.nanoTime(), requireTimeout(overallTimeout));
		for(InetSocketAddress endpoint : new LinkedHashSet<>(endpoints))
			_workers.put(endpoint, new Worker());
	}

	/** Begin initial PLANNING exactly once and discover every declared worker UUID/PID. */
	public PhaseHandle beginPlanning() throws Exception {
		final Phase phase;
		synchronized(_gate) {
			requireState(State.ALLOCATED, "PLANNING BEGIN is not available");
			_state = State.PLANNING_STARTING;
			phase = new Phase(_planningIdentity);
			_phase = phase;
		}
		try {
			beginWorkers(phase, true);
			synchronized(_gate) {
				requireHealthy();
				_state = State.PLANNING_OPEN;
				return phase.handle;
			}
		}
		catch(Throwable ex) {
			poison(ex);
			throw asException(ex);
		}
	}

	/** Begin the exact WARMUP successor once, retaining worker identities and control sequences. */
	public PhaseHandle nextWarmup(PhaseIdentity warmupIdentity) throws Exception {
		final Phase phase;
		synchronized(_gate) {
			requireState(State.PLANNING_DONE, "WARMUP BEGIN requires one clean PLANNING finish");
			try {
				validateWarmupSuccessor(warmupIdentity);
			}
			catch(RuntimeException ex) {
				poisonLocked(ex);
				throw ex;
			}
			_state = State.WARMUP_STARTING;
			phase = new Phase(warmupIdentity);
			_phase = phase;
		}
		try {
			beginWorkers(phase, false);
			synchronized(_gate) {
				requireHealthy();
				_state = State.WARMUP_OPEN;
				return phase.handle;
			}
		}
		catch(Throwable ex) {
			poison(ex);
			throw asException(ex);
		}
	}

	public boolean isPoisoned() {
		synchronized(_gate) {
			return _state == State.POISONED;
		}
	}

	/** Dedicated containers must be removed after any network interaction, whether success or failure. */
	public boolean isTeardownRequired() {
		synchronized(_gate) {
			return _networkStarted || _state == State.POISONED;
		}
	}

	/** This bounded owner never sends CLOSE/ABORT and therefore never claims remote owner release. */
	public boolean isWorkerOwnerReleased() {
		return false;
	}

	public Throwable getFailure() {
		synchronized(_gate) {
			return _failure;
		}
	}

	private void beginWorkers(Phase phase, boolean discover) throws Exception {
		for(Map.Entry<InetSocketAddress, Worker> entry : _workers.entrySet()) {
			Worker worker = entry.getValue();
			Control control;
			synchronized(_gate) {
				requireHealthy();
				control = new Control(phase.identity, discover ? null : worker.workerId,
					worker.nextControlSequence++, ControlOp.BEGIN_PHASE, remainingControlMillis(),
					new StreamFence[0], new String[0], null);
				_networkStarted = true;
			}
			Reply reply = awaitReply(sendControl(entry.getKey(), control));
			validateBeginReply(control, reply, worker, discover);
			synchronized(_gate) {
				worker.workerId = reply.getWorkerJvmInstanceId();
				worker.workerPid = reply.getWorkerPid();
			}
		}
	}

	private Future<FederatedResponse> sendControl(InetSocketAddress address, Control control) {
		Future<FederatedResponse> future = _transport.control(address, control);
		if(future == null)
			throw new DMLRuntimeException("Phase control transport returned a null future");
		return future;
	}

	private void validateBeginReply(Control control, Reply reply, Worker worker, boolean discover) {
		validateCommonReply(control, reply, discover ? null : worker);
		if(reply.getStatus() != ReplyStatus.ACK || reply.getError() != FederatedPhaseWire.ErrorCode.NONE
			|| reply.getRootsRegistered() != 0 || reply.getChildrenRegistered() != 0
			|| reply.getTasksCompleted() != 0 || reply.getOutstanding() != 0
			|| reply.getRejectedTasks() != 0 || reply.getFailedTasks() != 0
			|| reply.isRootsClosed() || reply.isTerminal() || reply.isResetVerified()
			|| reply.getCompletedFences().length != 0 || reply.getSources().length != 0
			|| reply.getResidencyLeaseId() != null)
			throw new DMLRuntimeException("Worker returned invalid BEGIN semantic evidence");
	}

	private void validateWarmupSuccessor(PhaseIdentity identity) {
		if(identity == null || identity.getKind() != PhaseKind.WARMUP
			|| identity.getEpoch() != _planningIdentity.getEpoch() + 1
			|| !identity.getAttemptId().equals(_planningIdentity.getAttemptId())
			|| !identity.getConditionDigest().equals(_planningIdentity.getConditionDigest())
			|| !identity.getStageSeal().equals(_planningIdentity.getStageSeal())
			|| !identity.getSourceManifestDigest().equals(_planningIdentity.getSourceManifestDigest())
			|| !identity.getSettingsDigest().equals(_planningIdentity.getSettingsDigest())
			|| !identity.getCoordinatorJvmInstanceId().equals(_planningIdentity.getCoordinatorJvmInstanceId())
			|| identity.getCoordinatorPid() != _planningIdentity.getCoordinatorPid())
			throw new IllegalArgumentException("WARMUP identity is not the exact PLANNING successor");
	}

	private void closeRoots(Phase phase) {
		synchronized(_gate) {
			requireCurrent(phase);
			State open = phase.identity.getKind() == PhaseKind.PLANNING ? State.PLANNING_OPEN : State.WARMUP_OPEN;
			requireState(open, "Phase root admission is not open");
			phase.tasks.closeRootAdmission(phase.token);
			_state = phase.identity.getKind() == PhaseKind.PLANNING
				? State.PLANNING_ROOTS_CLOSED : State.WARMUP_ROOTS_CLOSED;
		}
	}

	private Result finish(Phase phase) throws Exception {
		final State finishing;
		synchronized(_gate) {
			requireCurrent(phase);
			State closed = phase.identity.getKind() == PhaseKind.PLANNING
				? State.PLANNING_ROOTS_CLOSED : State.WARMUP_ROOTS_CLOSED;
			requireState(closed, "Phase finish requires exactly one root-admission close");
			finishing = phase.identity.getKind() == PhaseKind.PLANNING
				? State.PLANNING_FINISHING : State.WARMUP_FINISHING;
			_state = finishing;
		}
		try {
			FederatedPhaseTasks.Result tasks = phase.tasks.await(phase.token, remainingDuration());
			if(!tasks.isClean() || phase.coordinatorRoots == 0)
				throw new DMLRuntimeException("Coordinator phase has no clean admitted root task tree");
			// This is only a necessary admission check; the full-DML bridge must separately
			// prove that these batches exercised the selected kernels on each worker.
			if(phase.identity.getKind() == PhaseKind.WARMUP && phase.workers.values().stream()
				.anyMatch(worker -> worker.dispatchedRoots == 0))
				throw new DMLRuntimeException("WARMUP did not dispatch to every declared worker");
			for(PendingBatch pending : snapshotPending(phase))
				validateOrdinaryReply(pending.tag, await(pending.response));
			Map<InetSocketAddress, Reply> replies = endWorkers(phase);
			synchronized(_gate) {
				requireState(finishing, "Phase transition changed during finish");
				_state = phase.identity.getKind() == PhaseKind.PLANNING ? State.PLANNING_DONE : State.WARMUP_DONE;
			}
			return new Result(phase.identity, tasks.getRegistered(), tasks.getCompleted(), phase.pending.size(), replies);
		}
		catch(Throwable ex) {
			poison(ex);
			throw asException(ex);
		}
	}

	private Map<InetSocketAddress, Reply> endWorkers(Phase phase) throws Exception {
		Map<InetSocketAddress, Reply> replies = new LinkedHashMap<>();
		for(Map.Entry<InetSocketAddress, Worker> entry : _workers.entrySet()) {
			Worker worker = entry.getValue();
			WorkerPhase workerPhase = phase.workers.get(entry.getKey());
			StreamFence[] fences = workerPhase.streams.values().stream()
				.map(stream -> new StreamFence(stream.streamId, stream.normalizedTid, stream.lastSequence))
				.toArray(StreamFence[]::new);
			Control control;
			synchronized(_gate) {
				requireHealthy();
				control = new Control(phase.identity, worker.workerId, worker.nextControlSequence++,
					ControlOp.END_PHASE, remainingControlMillis(), fences, new String[0], null);
			}
			Reply reply = awaitReply(sendControl(entry.getKey(), control));
			validateEndReply(control, reply, worker, workerPhase, fences);
			replies.put(entry.getKey(), reply);
		}
		return replies;
	}

	private void validateEndReply(Control control, Reply reply, Worker worker,
		WorkerPhase workerPhase, StreamFence[] fences) {
		validateCommonReply(control, reply, worker);
		long registered = reply.getRootsRegistered() + reply.getChildrenRegistered();
		if(reply.getStatus() != ReplyStatus.ACK || reply.getError() != FederatedPhaseWire.ErrorCode.NONE
			|| reply.getRootsRegistered() != workerPhase.dispatchedRoots
			|| reply.getTasksCompleted() != registered || reply.getOutstanding() != 0
			|| reply.getRejectedTasks() != 0 || reply.getFailedTasks() != 0
			|| !reply.isRootsClosed() || !reply.isTerminal() || reply.isResetVerified()
			|| reply.getSources().length != 0 || reply.getResidencyLeaseId() != null
			|| !sameFences(fences, reply.getCompletedFences()))
			throw new DMLRuntimeException("Worker returned invalid terminal END semantic evidence");
	}

	private void validateCommonReply(Control control, Reply reply, Worker expectedWorker) {
		if(reply == null || !reply.getAttemptId().equals(control.getIdentity().getAttemptId())
			|| reply.getEpoch() != control.getIdentity().getEpoch()
			|| reply.getControlSequence() != control.getControlSequence()
			|| reply.getOp() != control.getOp()
			|| (expectedWorker != null && (!expectedWorker.workerId.equals(reply.getWorkerJvmInstanceId())
				|| expectedWorker.workerPid != reply.getWorkerPid()))
			|| !reply.getAcceptedStageSeal().equals(control.getIdentity().getStageSeal())
			|| !reply.getAcceptedSettingsDigest().equals(control.getIdentity().getSettingsDigest()))
			throw new DMLRuntimeException("Worker control acknowledgement identity mismatch");
	}

	private <T> Future<T> submitRoot(Phase phase, Executor executor, Callable<T> task) {
		synchronized(_gate) {
			requireCurrentOpen(phase);
			try {
				Future<T> future = phase.tasks.submitRoot(phase.token, executor, () -> runBound(phase, task));
				phase.coordinatorRoots++;
				return future;
			}
			catch(RuntimeException | Error ex) {
				poisonLocked(ex);
				throw ex;
			}
		}
	}

	private <T> Future<T> submitChild(Phase phase, Executor executor, Callable<T> task) {
		synchronized(_gate) {
			requireCurrentUsable(phase);
			try {
				return phase.tasks.submitChild(phase.token, executor, () -> runBound(phase, task));
			}
			catch(RuntimeException | Error ex) {
				poisonLocked(ex);
				throw ex;
			}
		}
	}

	private Future<FederatedResponse> dispatch(Phase phase, InetSocketAddress address,
		FederatedRequest... requests) {
		try {
			synchronized(_gate) {
				requireCurrentUsable(phase);
				if(_boundPhase.get() != phase)
					throw new IllegalStateException("Federated dispatch requires this phase's admitted task context");
				Worker worker = _workers.get(address);
				if(worker == null)
					throw new IllegalArgumentException("Endpoint is not in this attempt's declared worker set");
				validateRequests(requests, phase.identity);
				long tid = requests[0].getTID();
				long normalizedTid = tid <= 0 ? 0 : tid;
				WorkerPhase workerPhase = phase.workers.get(address);
				Stream stream = workerPhase.streams.computeIfAbsent(normalizedTid,
					ignored -> new Stream(UUID.randomUUID(), normalizedTid));
				BatchTag tag = new BatchTag(phase.identity, worker.workerId, stream.streamId,
					normalizedTid, ++stream.lastSequence);
				FederatedRequest[] tagged = Arrays.stream(requests).map(FederatedRequest::deepClone)
					.toArray(FederatedRequest[]::new);
				for(FederatedRequest request : tagged)
					request.setPhaseBatchTag(tag);
				Future<FederatedResponse> response = _transport.ordinary(address, tagged);
				if(response == null)
					throw new DMLRuntimeException("Phase transport returned a null ordinary future");
				PendingBatch pending = new PendingBatch(tag, response);
				phase.pending.add(pending);
				workerPhase.dispatchedRoots++;
				observeOrdinary(pending);
				return response;
			}
		}
		catch(RuntimeException | Error ex) {
			poison(ex);
			throw ex;
		}
	}

	private static void validateRequests(FederatedRequest[] requests, PhaseIdentity identity) {
		if(requests == null || requests.length == 0 || requests[0] == null)
			throw new IllegalArgumentException("Federated phase batch must be non-empty");
		long pid = requests[0].getPID();
		long tid = requests[0].getTID();
		if(pid != identity.getCoordinatorPid())
			throw new IllegalArgumentException("Federated phase batch coordinator PID mismatch");
		for(FederatedRequest request : requests)
			if(request == null || request.getPID() != pid || request.getTID() != tid
				|| request.getType() == FederatedRequest.RequestType.PHASE_CONTROL
				|| request.getPhaseBatchTag() != null)
				throw new IllegalArgumentException("Federated phase batch has inconsistent or pre-tagged origin");
	}

	@SuppressWarnings("unchecked")
	private void observeOrdinary(PendingBatch pending) {
		if(pending.response instanceof CompletableFuture) {
			((CompletableFuture<FederatedResponse>) pending.response).whenComplete((response, error) -> {
				if(error != null)
					poison(error);
				else
					validateObserved(pending.tag, response);
			});
		}
		else if(pending.response instanceof io.netty.util.concurrent.Future) {
			((io.netty.util.concurrent.Future<FederatedResponse>) pending.response).addListener(future -> {
				if(!future.isSuccess())
					poison(future.cause());
				else
					validateObserved(pending.tag, (FederatedResponse) future.getNow());
			});
		}
	}

	private void validateObserved(BatchTag tag, FederatedResponse response) {
		try {
			validateOrdinaryReply(tag, response);
		}
		catch(RuntimeException ex) {
			poison(ex);
		}
	}

	private static void validateOrdinaryReply(BatchTag tag, FederatedResponse response) {
		if(response == null || !response.isSuccessful() || !tag.equals(response.getPhaseBatchTag()))
			throw new DMLRuntimeException("Federated phase ordinary response failed correlation");
	}

	private Reply awaitReply(Future<FederatedResponse> future) throws Exception {
		FederatedResponse response = await(future);
		if(response == null || !response.isSuccessful())
			throw new DMLRuntimeException("Phase control transport failed");
		Object[] data = response.getData();
		if(data == null || data.length != 1 || data[0] == null || data[0].getClass() != Reply.class)
			throw new DMLRuntimeException("Phase control response has invalid payload");
		return (Reply) data[0];
	}

	private FederatedResponse await(Future<FederatedResponse> future) throws Exception {
		return future.get(remainingNanos(), TimeUnit.NANOSECONDS);
	}

	private <T> T runBound(Phase phase, Callable<T> task) throws Exception {
		Phase previous = _boundPhase.get();
		_boundPhase.set(phase);
		try {
			return task.call();
		}
		finally {
			if(previous == null)
				_boundPhase.remove();
			else
				_boundPhase.set(previous);
		}
	}

	private List<PendingBatch> snapshotPending(Phase phase) {
		synchronized(_gate) {
			return new ArrayList<>(phase.pending);
		}
	}

	private void requireCurrentOpen(Phase phase) {
		requireCurrent(phase);
		State expected = phase.identity.getKind() == PhaseKind.PLANNING ? State.PLANNING_OPEN : State.WARMUP_OPEN;
		requireState(expected, "Phase root admission is not open");
	}

	private void requireCurrentUsable(Phase phase) {
		requireCurrent(phase);
		State open = phase.identity.getKind() == PhaseKind.PLANNING ? State.PLANNING_OPEN : State.WARMUP_OPEN;
		State closed = phase.identity.getKind() == PhaseKind.PLANNING
			? State.PLANNING_ROOTS_CLOSED : State.WARMUP_ROOTS_CLOSED;
		State finishing = phase.identity.getKind() == PhaseKind.PLANNING
			? State.PLANNING_FINISHING : State.WARMUP_FINISHING;
		if(_state != open && _state != closed && _state != finishing)
			throw new IllegalStateException("Phase capability is stale or not dispatchable");
	}

	private void requireCurrent(Phase phase) {
		requireHealthy();
		if(phase == null || _phase != phase) {
			IllegalStateException ex = new IllegalStateException("Phase capability is stale");
			poisonLocked(ex);
			throw ex;
		}
	}

	private void requireState(State expected, String message) {
		if(_state != expected) {
			IllegalStateException ex = new IllegalStateException(message + ": " + _state);
			poisonLocked(ex);
			throw ex;
		}
	}

	private void requireHealthy() {
		if(_state == State.POISONED)
			throw new IllegalStateException("Federated attempt is poisoned", _failure);
	}

	private void poison(Throwable failure) {
		synchronized(_gate) {
			poisonLocked(failure);
		}
	}

	private void poisonLocked(Throwable failure) {
		if(_state != State.POISONED) {
			_state = State.POISONED;
			_failure = failure == null ? new IllegalStateException("Unknown federated attempt failure") : failure;
		}
	}

	private Duration remainingDuration() throws TimeoutException {
		return Duration.ofNanos(remainingNanos());
	}

	private long remainingControlMillis() throws TimeoutException {
		return Math.min(FederatedPhaseWire.MAX_TIMEOUT_MILLIS,
			Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos())));
	}

	private long remainingNanos() throws TimeoutException {
		long remaining = _deadlineNanos - System.nanoTime();
		if(remaining <= 0)
			throw new TimeoutException("Federated attempt deadline expired");
		return remaining;
	}

	private static boolean sameFences(StreamFence[] expected, StreamFence[] actual) {
		return expected.length == new HashSet<>(Arrays.asList(expected)).size()
			&& actual.length == new HashSet<>(Arrays.asList(actual)).size()
			&& new HashSet<>(Arrays.asList(expected)).equals(new HashSet<>(Arrays.asList(actual)));
	}

	private static long requireTimeout(Duration timeout) {
		if(timeout == null || timeout.isZero() || timeout.isNegative())
			throw new IllegalArgumentException("Attempt timeout must be positive and finite");
		try {
			long nanos = timeout.toNanos();
			if(nanos <= 0)
				throw new IllegalArgumentException("Attempt timeout must be positive and finite");
			return nanos;
		}
		catch(ArithmeticException ex) {
			throw new IllegalArgumentException("Attempt timeout is too large", ex);
		}
	}

	private static long saturatingAdd(long left, long right) {
		long result = left + right;
		return ((left ^ result) & (right ^ result)) < 0 ? Long.MAX_VALUE : result;
	}

	private static Exception asException(Throwable failure) {
		if(failure instanceof Exception)
			return (Exception) failure;
		if(failure instanceof Error)
			throw (Error) failure;
		return new DMLRuntimeException("Federated attempt failed", new Exception(failure));
	}

	private static Callable<Void> callable(Runnable task) {
		if(task == null)
			throw new IllegalArgumentException("Task must be non-null");
		return () -> {
			task.run();
			return null;
		};
	}

	/** Opaque, phase-specific capability. A handle becomes stale at handoff and cannot attach work later. */
	public final class PhaseHandle {
		private final Phase _owned;

		private PhaseHandle(Phase phase) {
			_owned = phase;
		}

		public PhaseIdentity getIdentity() {
			return _owned.identity;
		}

		public Future<?> submitRoot(Executor executor, Runnable task) {
			return submitRoot(executor, callable(task));
		}

		public <T> Future<T> submitRoot(Executor executor, Callable<T> task) {
			return FederatedPhaseAttempt.this.submitRoot(_owned, executor, task);
		}

		public Future<?> submitChild(Executor executor, Runnable task) {
			return submitChild(executor, callable(task));
		}

		public <T> Future<T> submitChild(Executor executor, Callable<T> task) {
			return FederatedPhaseAttempt.this.submitChild(_owned, executor, task);
		}

		public Future<FederatedResponse> dispatch(InetSocketAddress address, FederatedRequest... requests) {
			return FederatedPhaseAttempt.this.dispatch(_owned, address, requests);
		}

		public void closeRootAdmission() {
			FederatedPhaseAttempt.this.closeRoots(_owned);
		}

		public Result finish() throws Exception {
			return FederatedPhaseAttempt.this.finish(_owned);
		}
	}

	public static final class Result {
		private final PhaseIdentity _identity;
		private final long _tasksRegistered;
		private final long _tasksCompleted;
		private final int _responsesDrained;
		private final Map<InetSocketAddress, Reply> _workerReplies;

		private Result(PhaseIdentity identity, long tasksRegistered, long tasksCompleted,
			int responsesDrained, Map<InetSocketAddress, Reply> workerReplies) {
			_identity = identity;
			_tasksRegistered = tasksRegistered;
			_tasksCompleted = tasksCompleted;
			_responsesDrained = responsesDrained;
			_workerReplies = Collections.unmodifiableMap(new LinkedHashMap<>(workerReplies));
		}

		public PhaseIdentity getIdentity() { return _identity; }
		public long getTasksRegistered() { return _tasksRegistered; }
		public long getTasksCompleted() { return _tasksCompleted; }
		public int getResponsesDrained() { return _responsesDrained; }
		public Map<InetSocketAddress, Reply> getWorkerReplies() { return _workerReplies; }
	}

	private final class Phase {
		private final PhaseIdentity identity;
		private final FederatedPhaseTasks tasks = new FederatedPhaseTasks();
		private final PhaseToken token;
		private final PhaseHandle handle;
		private final Map<InetSocketAddress, WorkerPhase> workers = new LinkedHashMap<>();
		private final List<PendingBatch> pending = new ArrayList<>();
		private long coordinatorRoots;

		private Phase(PhaseIdentity identity) {
			this.identity = identity;
			token = tasks.begin(identity.getConditionDigest() + ":" + identity.getKind());
			handle = new PhaseHandle(this);
			for(InetSocketAddress endpoint : _workers.keySet())
				workers.put(endpoint, new WorkerPhase());
		}
	}

	private static final class Worker {
		private UUID workerId;
		private long workerPid;
		private long nextControlSequence = 1;
	}

	private static final class WorkerPhase {
		private final Map<Long, Stream> streams = new HashMap<>();
		private long dispatchedRoots;
	}

	private static final class Stream {
		private final UUID streamId;
		private final long normalizedTid;
		private long lastSequence;

		private Stream(UUID streamId, long normalizedTid) {
			this.streamId = streamId;
			this.normalizedTid = normalizedTid;
		}
	}

	private static final class PendingBatch {
		private final BatchTag tag;
		private final Future<FederatedResponse> response;

		private PendingBatch(BatchTag tag, Future<FederatedResponse> response) {
			this.tag = tag;
			this.response = response;
		}
	}
}
