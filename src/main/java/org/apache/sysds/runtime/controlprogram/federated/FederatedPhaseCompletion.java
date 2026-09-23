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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.util.concurrent.Promise;

/**
 * Opt-in process-wide accounting for physical federated request dispatches in one condition phase.
 *
 * The active scope is deliberately process-wide rather than thread-local: federated instructions may submit work
 * from executor threads. The tracker observes completion on the transport promise, so requests whose returned future
 * is discarded are still included. It records only transport metadata and failure categories, never response data.
 *
 * Sealing and registration use the same monitor. A dispatch concurrent with or after sealing is therefore either in
 * the drain or reported as late. Sealing cannot prove that every producer has stopped; callers must establish producer
 * quiescence independently before interpreting a successful drain as a timed-region boundary.
 *
 * There is intentionally no global force-reset. If a caller loses its scope before all producer roots terminate or an
 * admitted response drains, the terminal gate remains fail-closed until process restart. This prevents an unaccounted
 * cross-phase transition but means scope lifecycle is a required part of the harness contract.
 */
public final class FederatedPhaseCompletion {
	private static final AtomicReference<Phase> ACTIVE = new AtomicReference<>();
	private static final Object ADMISSION_GATE = new Object();

	private FederatedPhaseCompletion() {
		// utility class
	}

	public static Scope begin(String condition) {
		if(condition == null || condition.trim().isEmpty())
			throw new IllegalArgumentException("Federated completion condition must be non-empty");
		Phase phase = new Phase(condition);
		synchronized(ADMISSION_GATE) {
			Phase current = ACTIVE.get();
			if(current != null && !current.canTransition())
				throw new IllegalStateException("A federated completion phase is already active");
			ACTIVE.set(phase);
			return new Scope(phase);
		}
	}

	static void trackDispatch(InetSocketAddress address, long tid, Promise<FederatedResponse> response) {
		final Phase phase;
		final Registration registration;
		synchronized(ADMISSION_GATE) {
			phase = ACTIVE.get();
			if(phase == null)
				return;
			registration = phase.register(address, tid);
		}
		response.addListener(future -> {
			if(!registration.completed.compareAndSet(false, true))
				return;
			if(!future.isSuccess())
				phase.completeExceptionally(registration, future.cause());
			else {
				FederatedResponse value = response.getNow();
				if(value == null)
					phase.completeUnsuccessful(registration, FailureKind.NULL_RESPONSE);
				else if(!value.isSuccessful())
					phase.completeUnsuccessful(registration, FailureKind.UNSUCCESSFUL_RESPONSE);
				else
					phase.completeSuccessfully(registration);
			}
		});
	}

	public enum FailureKind {
		EXCEPTIONAL_COMPLETION,
		UNSUCCESSFUL_RESPONSE,
		NULL_RESPONSE,
		LATE_DISPATCH_REJECTED,
		PRODUCER_EXCEPTIONAL_COMPLETION,
		PRODUCER_CANCELLED
	}

	static final class LateDispatchException extends IllegalStateException {
		private static final long serialVersionUID = 1L;

		private LateDispatchException(String endpoint, long tid) {
			super("Federated dispatch rejected after phase seal for endpoint " + endpoint + " and TID " + tid);
		}
	}

	public static final class Failure {
		private final String _endpoint;
		private final long _tid;
		private final FailureKind _kind;
		private final String _exceptionClass;
		private final boolean _lateDispatch;

		private Failure(Registration registration, FailureKind kind, Throwable cause) {
			_endpoint = registration.endpoint;
			_tid = registration.tid;
			_kind = kind;
			_exceptionClass = cause == null ? null : cause.getClass().getName();
			_lateDispatch = registration.lateDispatch;
		}

		public String getEndpoint() {
			return _endpoint;
		}

		public long getTid() {
			return _tid;
		}

		public FailureKind getKind() {
			return _kind;
		}

		public String getExceptionClass() {
			return _exceptionClass;
		}

		public boolean isLateDispatch() {
			return _lateDispatch;
		}
	}

	public static final class Result {
		private final Phase _livePhase;
		private final String _condition;
		private final boolean _timedOut;

		private Result(Phase phase, boolean timedOut) {
			_livePhase = phase;
			_condition = phase.condition;
			_timedOut = timedOut;
		}

		public String getCondition() { return _condition; }
		public boolean isSealed() { synchronized(_livePhase) { return _livePhase.sealed; } }
		public boolean isTimedOut() { return _timedOut; }
		public long getRegistered() { synchronized(_livePhase) { return _livePhase.registered; } }
		public long getCompleted() { synchronized(_livePhase) { return _livePhase.completed; } }
		public long getSuccessful() { synchronized(_livePhase) { return _livePhase.successful; } }
		public long getUnsuccessful() { synchronized(_livePhase) { return _livePhase.unsuccessful; } }
		public long getExceptional() { synchronized(_livePhase) { return _livePhase.exceptional; } }
		public long getLateDispatches() { synchronized(_livePhase) { return _livePhase.lateDispatches; } }
		public long getRejectedDispatches() { synchronized(_livePhase) { return _livePhase.rejectedDispatches; } }
		public long getOutstanding() { synchronized(_livePhase) { return _livePhase.outstanding; } }
		public boolean isProducerQuiescenceAttested() {
			synchronized(_livePhase) { return _livePhase.producerQuiescenceAttested; }
		}
		public int getProducerEvidenceCount() {
			synchronized(_livePhase) { return _livePhase.producerEvidenceCount; }
		}
		public boolean hadDrainTimeout() { synchronized(_livePhase) { return _livePhase.hadDrainTimeout; } }
		public boolean isAborted() { synchronized(_livePhase) { return _livePhase.aborted; } }
		public Set<String> getEndpoints() {
			synchronized(_livePhase) {
				return Collections.unmodifiableSet(new LinkedHashSet<>(_livePhase.endpoints));
			}
		}
		public Set<Long> getTids() {
			synchronized(_livePhase) {
				return Collections.unmodifiableSet(new LinkedHashSet<>(_livePhase.tids));
			}
		}
		public List<Failure> getFailures() {
			synchronized(_livePhase) {
				return Collections.unmodifiableList(new ArrayList<>(_livePhase.failures));
			}
		}

		public boolean isClean() {
			return !_timedOut && _livePhase.isTerminalClean();
		}
	}

	public static final class Scope implements AutoCloseable {
		private final Phase _phase;
		private final AtomicBoolean _closed = new AtomicBoolean();

		private Scope(Phase phase) {
			_phase = phase;
		}

		public void seal() {
			ensureOpen();
			_phase.seal();
		}

		/**
		 * Seal the admission gate after independently managed producer futures have terminated.
		 * Detached work not represented by these futures is not proven quiescent and will be rejected if it dispatches.
		 *
		 * The caller attests that these terminated futures cover every submission root. The tracker cannot discover
		 * detached work omitted from that set; such work is a contract violation and is rejected by the terminal gate.
		 *
		 * Exceptional producers prove future termination and are recorded as phase failures, requiring abort. Cancellation
		 * is rejected here because it does not prove task termination; use the separate cancellation-termination contract.
		 *
		 * @param producers terminated futures covering the phase's submission roots
		 */
		public void sealAfterProducersComplete(Future<?>... producers) {
			ensureOpen();
			ProducerEvidence evidence = verifyProducerEvidence(producers);
			_phase.sealWithProducerEvidence(evidence);
		}

		/**
		 * Seal after a cancelled producer has separately signalled that its underlying task actually terminated.
		 * Cancellation alone is not quiescence because a task may ignore interruption. The caller attests that the
		 * termination future belongs to the same producer root.
		 *
		 * @param cancelledProducer cancelled producer future
		 * @param terminationEvidence successful completion signal emitted after the producer task exits
		 */
		public void sealAfterCancelledProducerTerminates(Future<?> cancelledProducer,
			Future<?> terminationEvidence) {
			ensureOpen();
			_phase.sealWithProducerEvidence(
				verifyCancelledProducerTermination(cancelledProducer, terminationEvidence));
		}

		/**
		 * Attest producer quiescence for an already sealed phase. This is useful after an unattested drain was used
		 * diagnostically; the phase remains fail-closed until terminated-producer evidence is supplied. The caller owns
		 * coverage of all roots; this method does not discover detached work.
		 *
		 * @param producers terminated futures covering the phase's submission roots
		 */
		public void attestProducerQuiescence(Future<?>... producers) {
			ensureOpen();
			ProducerEvidence evidence = verifyProducerEvidence(producers);
			_phase.attestProducerQuiescence(evidence);
		}

		public Result await(Duration timeout) throws InterruptedException {
			ensureOpen();
			if(timeout == null || timeout.isZero() || timeout.isNegative())
				throw new IllegalArgumentException("Federated completion drain timeout must be positive and finite");
			return _phase.await(timeout);
		}

		public Result snapshot() {
			ensureOpen();
			return _phase.snapshot(false);
		}

		@Override
		public void close() {
			if(_closed.get())
				return;
			finish();
		}

		/**
		 * Finish a successful phase. The terminal admission gate remains installed until the next phase begins, so a
		 * detached late producer invalidates previously returned live clean verdicts and is rejected before network write.
		 *
		 * @return live terminal result whose clean verdict can still be invalidated by a late producer contract violation
		 */
		public Result finish() {
			if(_closed.get())
				throw new IllegalStateException("Federated completion phase is closed");
			_phase.ensureClosable();
			_phase.markTerminal();
			Result result = _phase.snapshot(false);
			_closed.set(true);
			return result;
		}

		/**
		 * Explicitly terminate a failed phase after its producers are quiescent and all admitted requests have drained.
		 * Unlike {@link #close()}, this records an unsuccessful transition before allowing a later phase to begin.
		 */
		public Result abort() {
			if(_closed.get()) {
				_phase.markAbortedTerminal();
				return _phase.snapshot(false);
			}
			_phase.ensureDrained();
			_phase.markAborted();
			_phase.markTerminal();
			Result result = _phase.snapshot(false);
			_closed.set(true);
			return result;
		}

		private static ProducerEvidence verifyProducerEvidence(Future<?>[] producers) {
			if(producers == null || producers.length == 0)
				throw new IllegalArgumentException("At least one producer completion future is required");
			ProducerEvidence evidence = new ProducerEvidence(producers.length);
			for(int i = 0; i < producers.length; i++) {
				Future<?> producer = producers[i];
				if(producer == null || !producer.isDone())
					throw new IllegalStateException("All producer completion futures must be done before phase seal");
				try {
					producer.get();
				}
				catch(CancellationException ex) {
					throw new IllegalStateException(
						"Cancelled producer requires separate same-root termination evidence", ex);
				}
				catch(ExecutionException ex) {
					Throwable cause = ex.getCause() == null ? ex : ex.getCause();
					evidence.failures.add(
						new ProducerFailure(i, FailureKind.PRODUCER_EXCEPTIONAL_COMPLETION, cause));
				}
				catch(InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("Interrupted while validating producer completion evidence", ex);
				}
			}
			return evidence;
		}

		private static ProducerEvidence verifyCancelledProducerTermination(Future<?> cancelledProducer,
			Future<?> terminationEvidence) {
			if(cancelledProducer == null || !cancelledProducer.isCancelled())
				throw new IllegalStateException("Producer future must be cancelled");
			if(terminationEvidence == null || !terminationEvidence.isDone())
				throw new IllegalStateException("Cancelled producer termination evidence must be done");
			try {
				terminationEvidence.get();
			}
			catch(CancellationException | ExecutionException ex) {
				throw new IllegalStateException("Cancelled producer termination evidence must be successful", ex);
			}
			catch(InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while validating producer termination evidence", ex);
			}
			ProducerEvidence evidence = new ProducerEvidence(1);
			evidence.failures.add(
				new ProducerFailure(0, FailureKind.PRODUCER_CANCELLED, new CancellationException()));
			return evidence;
		}

		private void ensureOpen() {
			if(_closed.get())
				throw new IllegalStateException("Federated completion phase is closed");
		}
	}

	private static final class ProducerEvidence {
		private final int count;
		private final List<ProducerFailure> failures = new ArrayList<>();

		private ProducerEvidence(int count) {
			this.count = count;
		}
	}

	private static final class ProducerFailure {
		private final int index;
		private final FailureKind kind;
		private final Throwable cause;

		private ProducerFailure(int index, FailureKind kind, Throwable cause) {
			this.index = index;
			this.kind = kind;
			this.cause = cause;
		}
	}

	private static final class Registration {
		private final String endpoint;
		private final long tid;
		private final boolean lateDispatch;
		private final AtomicBoolean completed = new AtomicBoolean();

		private Registration(String endpoint, long tid, boolean lateDispatch) {
			this.endpoint = endpoint;
			this.tid = tid;
			this.lateDispatch = lateDispatch;
		}
	}

	private static final class Phase {
		private final String condition;
		private final Set<String> endpoints = new LinkedHashSet<>();
		private final Set<Long> tids = new LinkedHashSet<>();
		private final List<Failure> failures = new ArrayList<>();
		private boolean sealed;
		private boolean producerQuiescenceAttested;
		private int producerEvidenceCount;
		private boolean hadDrainTimeout;
		private boolean aborted;
		private boolean terminal;
		private long registered;
		private long completed;
		private long successful;
		private long unsuccessful;
		private long exceptional;
		private long lateDispatches;
		private long rejectedDispatches;
		private long outstanding;

		private Phase(String condition) {
			this.condition = condition;
		}

		private synchronized Registration register(InetSocketAddress address, long tid) {
			String endpoint = endpoint(address);
			if(sealed) {
				Registration rejected = new Registration(endpoint, tid, true);
				lateDispatches++;
				rejectedDispatches++;
				failures.add(new Failure(rejected, FailureKind.LATE_DISPATCH_REJECTED, null));
				throw new LateDispatchException(endpoint, tid);
			}
			Registration registration = new Registration(endpoint, tid, false);
			registered++;
			outstanding++;
			endpoints.add(registration.endpoint);
			tids.add(tid);
			return registration;
		}

		private synchronized void completeSuccessfully(Registration registration) {
			completed++;
			successful++;
			outstanding--;
			notifyAll();
		}

		private synchronized void completeUnsuccessful(Registration registration, FailureKind kind) {
			completed++;
			unsuccessful++;
			outstanding--;
			failures.add(new Failure(registration, kind, null));
			notifyAll();
		}

		private synchronized void completeExceptionally(Registration registration, Throwable cause) {
			completed++;
			exceptional++;
			outstanding--;
			failures.add(new Failure(registration, FailureKind.EXCEPTIONAL_COMPLETION, cause));
			notifyAll();
		}

		private synchronized void seal() {
			sealed = true;
		}

		private synchronized void sealWithProducerEvidence(ProducerEvidence evidence) {
			sealed = true;
			applyProducerEvidence(evidence);
		}

		private synchronized void attestProducerQuiescence(ProducerEvidence evidence) {
			if(!sealed)
				throw new IllegalStateException("Federated completion phase must be sealed before confirmation");
			applyProducerEvidence(evidence);
		}

		private void applyProducerEvidence(ProducerEvidence evidence) {
			if(producerQuiescenceAttested)
				throw new IllegalStateException("Producer quiescence was already attested for this phase");
			producerQuiescenceAttested = true;
			producerEvidenceCount = evidence.count;
			for(ProducerFailure failure : evidence.failures) {
				Registration producer = new Registration("producer[" + failure.index + "]", -1, false);
				failures.add(new Failure(producer, failure.kind, failure.cause));
			}
		}

		private synchronized void ensureClosable() {
			ensureDrained();
			if(hadDrainTimeout || !failures.isEmpty() || lateDispatches != 0)
				throw new IllegalStateException("Failed federated completion phase requires explicit abort");
		}

		private synchronized void ensureDrained() {
			if(!sealed || !producerQuiescenceAttested || outstanding != 0)
				throw new IllegalStateException("Federated completion phase is not terminally drained");
		}

		private synchronized void markAborted() {
			aborted = true;
		}

		private synchronized void markTerminal() {
			terminal = true;
		}

		private synchronized void markAbortedTerminal() {
			if(!terminal)
				throw new IllegalStateException("Federated completion phase is closed but not terminal");
			aborted = true;
		}

		private synchronized boolean canTransition() {
			return terminal && (aborted || isTerminalClean());
		}

		private synchronized boolean isTerminalClean() {
			return terminal && sealed && producerQuiescenceAttested && !hadDrainTimeout && !aborted
				&& outstanding == 0 && failures.isEmpty() && lateDispatches == 0;
		}

		private synchronized Result await(Duration timeout) throws InterruptedException {
			if(!sealed)
				throw new IllegalStateException("Federated completion phase must be sealed before draining");
			long timeoutNanos;
			try {
				timeoutNanos = timeout.toNanos();
			}
			catch(ArithmeticException ex) {
				timeoutNanos = Long.MAX_VALUE;
			}
			long start = System.nanoTime();
			long remaining = timeoutNanos;
			while(outstanding > 0 && remaining > 0) {
				long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
				int nanos = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(millis));
				wait(millis, nanos);
				remaining = timeoutNanos - (System.nanoTime() - start);
			}
			boolean timedOut = outstanding > 0;
			hadDrainTimeout |= timedOut;
			return new Result(this, timedOut);
		}

		private synchronized Result snapshot(boolean timedOut) {
			return new Result(this, timedOut);
		}

		private static String endpoint(InetSocketAddress address) {
			return address == null ? "null" : address.getHostString() + ':' + address.getPort();
		}
	}
}
