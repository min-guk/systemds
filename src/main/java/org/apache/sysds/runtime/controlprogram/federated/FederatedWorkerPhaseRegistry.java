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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Callable;

import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseTasks.PhaseToken;
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
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse.ResponseType;

/** Shared, exclusive STRICT phase state for all handlers in one federated worker JVM. */
public final class FederatedWorkerPhaseRegistry {
	private static final AtomicLong THREAD_IDS = new AtomicLong();
	private static final FederatedWorkerPhaseRegistry SHARED = new FederatedWorkerPhaseRegistry(
		UUID.randomUUID(), ProcessHandle.current().pid(), newExecutor("fed-phase-task-"),
		newExecutor("fed-phase-control-"));

	private final UUID _workerJvmInstanceId;
	private final long _workerPid;
	private final ExecutorService _taskExecutor;
	private final ExecutorService _controlExecutor;
	private final FederatedPhaseTasks _tasks = new FederatedPhaseTasks();
	private final ThreadLocal<Session> _boundSession = new ThreadLocal<>();

	private Owner _owner;
	private Session _session;

	public static FederatedWorkerPhaseRegistry shared() {
		return SHARED;
	}

	FederatedWorkerPhaseRegistry(UUID workerJvmInstanceId, long workerPid,
		ExecutorService taskExecutor, ExecutorService controlExecutor) {
		_workerJvmInstanceId = Objects.requireNonNull(workerJvmInstanceId);
		if(workerPid <= 0)
			throw new IllegalArgumentException("workerPid must be positive");
		_workerPid = workerPid;
		_taskExecutor = Objects.requireNonNull(taskExecutor);
		_controlExecutor = Objects.requireNonNull(controlExecutor);
	}

	public UUID getWorkerJvmInstanceId() {
		return _workerJvmInstanceId;
	}

	public synchronized boolean hasStrictSession() {
		return _owner != null;
	}

	public boolean isBoundStrictTask() {
		return _boundSession.get() != null;
	}

	public CompletableFuture<FederatedResponse> handleControl(Control control, long requestPid, String remoteHost) {
		if(control == null)
			return CompletableFuture.completedFuture(controlFailure(null, ErrorCode.INVALID_CONTROL));
		try {
			switch(control.getOp()) {
				case BEGIN_PHASE:
					return CompletableFuture.completedFuture(begin(control, requestPid, remoteHost));
				case END_PHASE:
					return end(control, requestPid, remoteHost);
				case ABORT_SESSION:
				case CLOSE_SESSION:
					return cleanup(control, requestPid, remoteHost);
				case RESET_WARM_STATE:
				case PREREAD_SOURCES:
				default:
					return CompletableFuture.completedFuture(rejectControl(control, ErrorCode.INVALID_CONTROL));
			}
		}
		catch(RuntimeException ex) {
			return CompletableFuture.completedFuture(rejectControl(control, currentError(ErrorCode.INVALID_CONTROL)));
		}
	}

	public CompletableFuture<FederatedResponse> executeBatch(FederatedRequest[] requests, String remoteHost,
		Callable<FederatedResponse> body) {
		CompletableFuture<FederatedResponse> response = new CompletableFuture<>();
		try {
			submitRoot(requests, remoteHost, body, response);
		}
		catch(RuntimeException ex) {
			response.complete(batchFailure(null));
		}
		return response;
	}

	private synchronized void submitRoot(FederatedRequest[] requests, String remoteHost,
		Callable<FederatedResponse> body, CompletableFuture<FederatedResponse> response) {
		Admission admission = admit(requests, remoteHost);
		try {
			admission.session.rootsRegistered++;
			_tasks.submitRoot(admission.session.token, _taskExecutor, () -> {
				bind(admission.session);
				try {
					FederatedResponse value = body.call();
					value.setPhaseBatchTag(admission.tag);
					if(!value.isSuccessful()) {
						recordFailedTask(admission.session, ErrorCode.TASK_FAILURE);
						response.complete(value);
						throw new StrictTaskFailureException();
					}
					response.complete(value);
					return null;
				}
				catch(Throwable ex) {
					if(!(ex instanceof StrictTaskFailureException))
						recordFailedTask(admission.session, ErrorCode.TASK_FAILURE);
					FederatedResponse failure = batchFailure(admission.tag);
					response.complete(failure);
					if(ex instanceof Exception)
						throw (Exception) ex;
					throw (Error) ex;
				}
				finally {
					_boundSession.remove();
				}
			});
		}
		catch(RuntimeException ex) {
			quarantine(admission.session, ErrorCode.INVALID_BATCH);
			response.complete(batchFailure(admission.tag));
			throw ex;
		}
	}

	public Future<?> submitChild(Runnable body) {
		Session session = _boundSession.get();
		if(session == null) {
			synchronized(this) {
				if(_session != null && !_session.terminal)
					quarantine(_session, ErrorCode.INVALID_BATCH);
			}
			throw new IllegalStateException("STRICT child submission requires a bound worker task");
		}
		synchronized(this) {
			if(session != _session || session.quarantined)
				throw new IllegalStateException("STRICT child submission belongs to a stale or quarantined phase");
			try {
				session.childrenRegistered++;
				return _tasks.submitChild(session.token, _taskExecutor, () -> {
					bind(session);
					try {
						body.run();
					}
					catch(Throwable ex) {
						synchronized(FederatedWorkerPhaseRegistry.this) {
							session.failedTasks++;
							session.quarantined = true;
							session.error = ErrorCode.TASK_FAILURE;
						}
						throw ex;
					}
					finally {
						_boundSession.remove();
					}
				});
			}
			catch(RuntimeException ex) {
				quarantine(session, ErrorCode.INVALID_BATCH);
				throw ex;
			}
		}
	}

	private synchronized FederatedResponse begin(Control control, long requestPid, String remoteHost) {
		PhaseIdentity identity = control.getIdentity();
		validateRequestOwner(identity, requestPid, remoteHost);
		if(control.getControlSequence() != (_owner == null ? 1 : _owner.lastControlSequence + 1))
			throw new IllegalStateException("control sequence is not monotonic");
		if(_owner == null) {
			if(control.getExpectedWorkerJvmInstanceId() != null || identity.getKind() != PhaseKind.PLANNING
				|| identity.getEpoch() != FederatedPhaseWire.INITIAL_EPOCH)
				throw new IllegalStateException("first STRICT control is not initial PLANNING discovery");
			_owner = new Owner(identity, remoteHost);
		}
		else {
			validateOwner(identity, remoteHost);
			if(!_workerJvmInstanceId.equals(control.getExpectedWorkerJvmInstanceId()))
				throw new IllegalStateException("worker JVM identity mismatch");
			if(_session == null || !_session.terminal || _session.quarantined)
				throw new IllegalStateException("previous STRICT phase is not clean and terminal");
			if(identity.getEpoch() != _owner.lastEpoch + 1
				|| identity.getKind().ordinal() != _owner.lastKind.ordinal() + 1)
				throw new IllegalStateException("phase epoch/kind transition mismatch");
		}
		PhaseToken token = _tasks.begin(identity.getConditionDigest() + ":" + identity.getKind());
		_session = new Session(identity, token);
		_owner.lastControlSequence = control.getControlSequence();
		_owner.lastEpoch = identity.getEpoch();
		_owner.lastKind = identity.getKind();
		return controlResponse(reply(control, ReplyStatus.ACK, ErrorCode.NONE, _session, null));
	}

	private CompletableFuture<FederatedResponse> end(Control control, long requestPid, String remoteHost) {
		final Session session;
		synchronized(this) {
			validateActiveControl(control, requestPid, remoteHost);
			session = _session;
			if(session.ending)
				throw quarantineAndThrow(session, ErrorCode.INVALID_SEQUENCE);
			if(session.quarantined)
				return CompletableFuture.completedFuture(controlResponse(
					reply(control, ReplyStatus.QUARANTINED, session.error, session, _tasks.snapshot(session.token))));
			validateFences(session, control.getFences());
			_tasks.closeRootAdmission(session.token);
			session.rootsClosed = true;
			session.ending = true;
			_owner.lastControlSequence = control.getControlSequence();
		}
		return CompletableFuture.supplyAsync(() -> awaitEnd(control, session), _controlExecutor);
	}

	private FederatedResponse awaitEnd(Control control, Session session) {
		FederatedPhaseTasks.Result result;
		try {
			result = _tasks.await(session.token, Duration.ofMillis(control.getTimeoutMillis()));
		}
		catch(InterruptedException ex) {
			Thread.currentThread().interrupt();
			quarantine(session, ErrorCode.TIMEOUT);
			result = _tasks.snapshot(session.token);
		}
		synchronized(this) {
			if(!result.isClean() || session.quarantined) {
				session.quarantined = true;
				if(session.error == ErrorCode.NONE)
					session.error = result.isTimedOut() ? ErrorCode.TIMEOUT : ErrorCode.TASK_FAILURE;
				return controlResponse(reply(control, ReplyStatus.QUARANTINED, session.error, session, result));
			}
			session.terminal = true;
			return controlResponse(reply(control, ReplyStatus.ACK, ErrorCode.NONE, session, result));
		}
	}

	private CompletableFuture<FederatedResponse> cleanup(Control control, long requestPid, String remoteHost) {
		final Session session;
		synchronized(this) {
			validateActiveControl(control, requestPid, remoteHost);
			session = _session;
			if(control.getOp() == ControlOp.CLOSE_SESSION && !session.terminal && !session.quarantined)
				throw new IllegalStateException("CLOSE_SESSION requires a terminal or quarantined phase");
			if(!session.rootsClosed) {
				_tasks.closeRootAdmission(session.token);
				session.rootsClosed = true;
			}
			session.ending = true;
			_owner.lastControlSequence = control.getControlSequence();
		}
		return CompletableFuture.supplyAsync(() -> awaitCleanup(control, session), _controlExecutor);
	}

	private FederatedResponse awaitCleanup(Control control, Session session) {
		FederatedPhaseTasks.Result result;
		try {
			result = session.terminal ? _tasks.snapshot(session.token)
				: _tasks.await(session.token, Duration.ofMillis(control.getTimeoutMillis()));
		}
		catch(InterruptedException ex) {
			Thread.currentThread().interrupt();
			quarantine(session, ErrorCode.TIMEOUT);
			result = _tasks.snapshot(session.token);
		}
		synchronized(this) {
			boolean drained = result.isTerminal() && result.getOutstanding() == 0;
			if(!drained) {
				quarantine(session, ErrorCode.TIMEOUT);
				return controlResponse(reply(control, ReplyStatus.QUARANTINED,
					ErrorCode.TIMEOUT, session, result));
			}
			session.terminal = true;
			ReplyStatus status = result.isClean() && !session.quarantined
				? ReplyStatus.ACK : ReplyStatus.QUARANTINED;
			ErrorCode error = status == ReplyStatus.ACK ? ErrorCode.NONE
				: currentError(ErrorCode.SESSION_QUARANTINED);
			FederatedResponse response = controlResponse(reply(control, status, error, session, result));
			if(session == _session) {
				_session = null;
				_owner = null;
			}
			return response;
		}
	}

	private Admission admit(FederatedRequest[] requests, String remoteHost) {
		if(_session == null || _session.terminal || _session.quarantined)
			throw new IllegalStateException("no open STRICT worker phase");
		if(requests == null || requests.length == 0)
			throw quarantineAndThrow(_session, ErrorCode.INVALID_BATCH);
		BatchTag tag = requests[0].getPhaseBatchTag();
		if(tag == null)
			throw quarantineAndThrow(_session, ErrorCode.INVALID_BATCH);
		for(FederatedRequest request : requests) {
			if(request == null || request.getType() == FederatedRequest.RequestType.PHASE_CONTROL
				|| !tag.equals(request.getPhaseBatchTag()) || request.getPID() != _owner.coordinatorPid
				|| normalizeTid(request.getTID()) != tag.getNormalizedTid())
				throw quarantineAndThrow(_session, ErrorCode.INVALID_BATCH);
		}
		if(!remoteHost.equals(_owner.remoteHost) || !tag.getIdentity().equals(_session.identity)
			|| !_workerJvmInstanceId.equals(tag.getExpectedWorkerJvmInstanceId()))
			throw quarantineAndThrow(_session, ErrorCode.INVALID_IDENTITY);
		StreamKey key = new StreamKey(tag.getStreamId(), tag.getNormalizedTid());
		long expected = _session.sequences.getOrDefault(key, 0L) + 1;
		if(tag.getBatchSequence() != expected)
			throw quarantineAndThrow(_session, ErrorCode.INVALID_SEQUENCE);
		_session.sequences.put(key, tag.getBatchSequence());
		return new Admission(_session, tag);
	}

	private synchronized void validateActiveControl(Control control, long requestPid, String remoteHost) {
		if(_owner == null || _session == null)
			throw new IllegalStateException("no STRICT session");
		validateRequestOwner(control.getIdentity(), requestPid, remoteHost);
		validateOwner(control.getIdentity(), remoteHost);
		if(!_workerJvmInstanceId.equals(control.getExpectedWorkerJvmInstanceId()))
			throw quarantineAndThrow(_session, ErrorCode.WORKER_IDENTITY_MISMATCH);
		if(!control.getIdentity().equals(_session.identity))
			throw quarantineAndThrow(_session, ErrorCode.STALE_EPOCH);
		if(control.getControlSequence() != _owner.lastControlSequence + 1)
			throw quarantineAndThrow(_session, ErrorCode.INVALID_SEQUENCE);
	}

	private void validateFences(Session session, StreamFence[] fences) {
		Map<StreamKey, Long> expected = new HashMap<>();
		for(StreamFence fence : fences)
			expected.put(new StreamKey(fence.getStreamId(), fence.getNormalizedTid()), fence.getLastSequence());
		if(!expected.equals(session.sequences))
			throw quarantineAndThrow(session, ErrorCode.INVALID_SEQUENCE);
	}

	private void validateRequestOwner(PhaseIdentity identity, long requestPid, String remoteHost) {
		if(identity.getCoordinatorPid() != requestPid || remoteHost == null || remoteHost.isEmpty())
			throw new IllegalStateException("coordinator transport identity mismatch");
	}

	private void validateOwner(PhaseIdentity identity, String remoteHost) {
		if(!_owner.attemptId.equals(identity.getAttemptId())
			|| !_owner.coordinatorJvmInstanceId.equals(identity.getCoordinatorJvmInstanceId())
			|| _owner.coordinatorPid != identity.getCoordinatorPid() || !_owner.remoteHost.equals(remoteHost)
			|| !_owner.conditionDigest.equals(identity.getConditionDigest())
			|| !_owner.stageSeal.equals(identity.getStageSeal())
			|| !_owner.sourceManifestDigest.equals(identity.getSourceManifestDigest())
			|| !_owner.settingsDigest.equals(identity.getSettingsDigest()))
			throw new IllegalStateException("exclusive STRICT session identity mismatch");
	}

	private void bind(Session session) {
		_boundSession.set(session);
	}

	private synchronized void quarantine(Session session, ErrorCode error) {
		if(session != null) {
			session.quarantined = true;
			session.error = error;
		}
	}

	private synchronized void recordFailedTask(Session session, ErrorCode error) {
		session.failedTasks++;
		quarantine(session, error);
	}

	private IllegalStateException quarantineAndThrow(Session session, ErrorCode error) {
		quarantine(session, error);
		return new IllegalStateException("STRICT worker phase rejected: " + error);
	}

	private synchronized FederatedResponse rejectControl(Control control, ErrorCode error) {
		if(_session != null)
			quarantine(_session, error);
		return controlFailure(control, error);
	}

	private synchronized ErrorCode currentError(ErrorCode fallback) {
		return _session != null && _session.quarantined && _session.error != ErrorCode.NONE
			? _session.error : fallback;
	}

	void rejectMalformedEnvelope() {
		synchronized(this) {
			if(_session != null && !_session.terminal)
				quarantine(_session, ErrorCode.INVALID_CONTROL);
		}
	}

	private FederatedResponse controlFailure(Control control, ErrorCode error) {
		if(control == null)
			return new FederatedResponse(ResponseType.ERROR, "invalid phase control");
		Session session;
		synchronized(this) {
			session = _session;
		}
		return controlResponse(reply(control, ReplyStatus.QUARANTINED, error, session,
			session == null ? null : _tasks.snapshot(session.token)));
	}

	private Reply reply(Control control, ReplyStatus status, ErrorCode error, Session session,
		FederatedPhaseTasks.Result result) {
		long roots = session == null ? 0 : session.rootsRegistered;
		long children = session == null ? 0 : session.childrenRegistered;
		long completed = result == null ? 0 : result.getCompleted();
		long outstanding = result == null ? roots + children : result.getOutstanding();
		long rejected = result == null ? 0 : result.getRejected();
		long failed = session == null ? 0 : session.failedTasks;
		boolean rootsClosed = result != null && result.isRootAdmissionClosed();
		boolean terminal = result != null && result.isTerminal() && result.getOutstanding() == 0;
		if(status == ReplyStatus.ACK) {
			rejected = 0;
			failed = 0;
		}
		String acceptedStageSeal = _owner == null ? "" : _owner.stageSeal;
		String acceptedSettingsDigest = _owner == null ? "" : _owner.settingsDigest;
		return new Reply(control.getIdentity().getAttemptId(), control.getIdentity().getEpoch(),
			control.getControlSequence(), control.getOp(), status, error, _workerJvmInstanceId, _workerPid,
			acceptedStageSeal, acceptedSettingsDigest,
			toFences(session), roots, children, completed, outstanding, rejected, failed,
			rootsClosed, terminal, false, null, new SourceResidencyReceipt[0]);
	}

	private StreamFence[] toFences(Session session) {
		if(session == null)
			return new StreamFence[0];
		return session.sequences.entrySet().stream()
			.map(entry -> new StreamFence(entry.getKey().streamId, entry.getKey().tid, entry.getValue()))
			.toArray(StreamFence[]::new);
	}

	private static FederatedResponse controlResponse(Reply reply) {
		return new FederatedResponse(ResponseType.SUCCESS, reply);
	}

	private static FederatedResponse batchFailure(BatchTag tag) {
		FederatedResponse response = new FederatedResponse(ResponseType.ERROR, "STRICT worker batch rejected");
		response.setPhaseBatchTag(tag);
		return response;
	}

	private static long normalizeTid(long tid) {
		return tid <= 0 ? 0 : tid;
	}

	void shutdownForTests() {
		_taskExecutor.shutdownNow();
		_controlExecutor.shutdownNow();
	}

	private static ExecutorService newExecutor(String prefix) {
		ThreadFactory factory = runnable -> {
			Thread thread = new Thread(runnable, prefix + THREAD_IDS.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
		return Executors.newCachedThreadPool(factory);
	}

	private static final class Admission {
		private final Session session;
		private final BatchTag tag;

		private Admission(Session session, BatchTag tag) {
			this.session = session;
			this.tag = tag;
		}
	}

	private static final class Owner {
		private final UUID attemptId;
		private final UUID coordinatorJvmInstanceId;
		private final long coordinatorPid;
		private final String remoteHost;
		private final String conditionDigest;
		private final String stageSeal;
		private final String sourceManifestDigest;
		private final String settingsDigest;
		private long lastControlSequence;
		private long lastEpoch;
		private PhaseKind lastKind;

		private Owner(PhaseIdentity identity, String remoteHost) {
			attemptId = identity.getAttemptId();
			coordinatorJvmInstanceId = identity.getCoordinatorJvmInstanceId();
			coordinatorPid = identity.getCoordinatorPid();
			this.remoteHost = remoteHost;
			conditionDigest = identity.getConditionDigest();
			stageSeal = identity.getStageSeal();
			sourceManifestDigest = identity.getSourceManifestDigest();
			settingsDigest = identity.getSettingsDigest();
		}
	}

	private static final class Session {
		private final PhaseIdentity identity;
		private final PhaseToken token;
		private final Map<StreamKey, Long> sequences = new HashMap<>();
		private long rootsRegistered;
		private long childrenRegistered;
		private long failedTasks;
		private boolean rootsClosed;
		private boolean ending;
		private boolean terminal;
		private boolean quarantined;
		private ErrorCode error = ErrorCode.NONE;

		private Session(PhaseIdentity identity, PhaseToken token) {
			this.identity = identity;
			this.token = token;
		}
	}

	private static final class StreamKey {
		private final UUID streamId;
		private final long tid;

		private StreamKey(UUID streamId, long tid) {
			this.streamId = streamId;
			this.tid = tid;
		}

		@Override
		public boolean equals(Object object) {
			if(this == object)
				return true;
			if(!(object instanceof StreamKey))
				return false;
			StreamKey that = (StreamKey) object;
			return tid == that.tid && streamId.equals(that.streamId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(streamId, tid);
		}
	}

	private static final class StrictTaskFailureException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
