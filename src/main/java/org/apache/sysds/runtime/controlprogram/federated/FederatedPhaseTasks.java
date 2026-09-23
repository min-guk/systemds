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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opt-in accounting for the complete task tree of one coordinator phase.
 *
 * Callers explicitly submit every root and descendant through this accountant. A regular, non-inheritable
 * {@link ThreadLocal} binds a phase context only while an admitted task body is executing. Root admission may be
 * closed while already admitted tasks continue to register descendants. Submissions without that bound context,
 * with a stale token, or after terminal completion are rejected rather than assigned to a later phase.
 *
 * Completion is based on the tracked task body's {@code finally} block. In particular, a cancelled {@link Future}
 * does not complete a running task: a body that ignores interruption remains outstanding until it actually returns.
 * This class is deliberately not connected to existing executor call sites yet, so creating an instance has no
 * effect on the legacy runtime.
 */
public final class FederatedPhaseTasks {
	private final Object _gate = new Object();
	private final ThreadLocal<TaskContext> _taskContext = new ThreadLocal<>();
	private long _nextEpoch;
	private Phase _current;

	public PhaseToken begin(String condition) {
		if(condition == null || condition.trim().isEmpty())
			throw new IllegalArgumentException("Federated task phase condition must be non-empty");
		synchronized(_gate) {
			if(_current != null && !_current.terminal)
				throw new IllegalStateException("A federated task phase is already active");
			_current = new Phase(++_nextEpoch, condition);
			return new PhaseToken(this, _current);
		}
	}

	public Future<?> submitRoot(PhaseToken token, Executor executor, Runnable task) {
		return submitRoot(token, executor, toCallable(task));
	}

	public <T> Future<T> submitRoot(PhaseToken token, Executor executor, Callable<T> task) {
		return submit(token, executor, task, true);
	}

	public Future<?> submitChild(PhaseToken token, Executor executor, Runnable task) {
		return submitChild(token, executor, toCallable(task));
	}

	public <T> Future<T> submitChild(PhaseToken token, Executor executor, Callable<T> task) {
		return submit(token, executor, task, false);
	}

	public void closeRootAdmission(PhaseToken token) {
		synchronized(_gate) {
			Phase phase = requireCurrent(token);
			phase.rootsClosed = true;
			_gate.notifyAll();
		}
	}

	/**
	 * Wait for root admission to be closed and all admitted task bodies to terminate.
	 * A timeout is permanently recorded as a phase failure, and the phase remains active until the bodies terminate.
	 */
	public Result await(PhaseToken token, Duration timeout) throws InterruptedException {
		if(timeout == null || timeout.isZero() || timeout.isNegative())
			throw new IllegalArgumentException("Federated task phase timeout must be positive and finite");
		final long timeoutNanos;
		try {
			timeoutNanos = timeout.toNanos();
		}
		catch(ArithmeticException ex) {
			throw new IllegalArgumentException("Federated task phase timeout is too large", ex);
		}
		if(timeoutNanos <= 0)
			throw new IllegalArgumentException("Federated task phase timeout must be positive and finite");

		synchronized(_gate) {
			Phase phase = requireCurrent(token);
			if(!phase.rootsClosed)
				throw new IllegalStateException("Root admission must be closed before awaiting task termination");
			long remaining = timeoutNanos;
			long deadline = System.nanoTime() + timeoutNanos;
			while(phase.outstanding > 0 && remaining > 0) {
				TimeUnit.NANOSECONDS.timedWait(_gate, remaining);
				remaining = deadline - System.nanoTime();
			}
			if(phase.outstanding > 0) {
				if(!phase.hadTimeout) {
					phase.hadTimeout = true;
					phase.failures.add(new Failure(FailureKind.TIMEOUT, null));
				}
			}
			else
				phase.terminal = true;
			return new Result(phase);
		}
	}

	public Result snapshot(PhaseToken token) {
		Phase phase = requireOwned(token);
		return new Result(phase);
	}

	private <T> Future<T> submit(PhaseToken token, Executor executor, Callable<T> task, boolean root) {
		if(executor == null)
			throw new IllegalArgumentException("Executor must be non-null");
		if(task == null)
			throw new IllegalArgumentException("Task must be non-null");

		final Phase phase;
		final Registration registration;
		synchronized(_gate) {
			phase = requireCurrent(token);
			if(root) {
				if(phase.rootsClosed) {
					reject(phase, FailureKind.ROOT_ADMISSION_CLOSED, null);
					throw new IllegalStateException("Federated task root admission is closed");
				}
			}
			else {
				TaskContext context = _taskContext.get();
				if(context == null) {
					reject(phase, FailureKind.UNBOUND_SUBMISSION, null);
					throw new IllegalStateException("A child task requires an explicitly bound admitted task context");
				}
				if(context.phase != phase) {
					reject(phase, FailureKind.WRONG_PHASE_CONTEXT, null);
					throw new IllegalStateException("The bound task context belongs to another phase");
				}
			}
			registration = new Registration(phase);
			phase.registered++;
			phase.outstanding++;
		}

		TrackedFutureTask<T> future = new TrackedFutureTask<>(registration,
			() -> runTask(registration, task));
		try {
			executor.execute(future);
		}
		catch(RuntimeException | Error ex) {
			registration.completeBeforeStart(FailureKind.EXECUTOR_REJECTED, ex);
			throw ex;
		}
		return future;
	}

	private <T> T runTask(Registration registration, Callable<T> task) throws Exception {
		if(!registration.start())
			throw new CancellationException("Task was cancelled before its body started");
		TaskContext previous = _taskContext.get();
		_taskContext.set(new TaskContext(registration.phase));
		try {
			T result = task.call();
			registration.completeRunning(null);
			return result;
		}
		catch(Throwable ex) {
			registration.completeRunning(ex);
			if(ex instanceof Exception)
				throw (Exception) ex;
			throw (Error) ex;
		}
		finally {
			if(previous == null)
				_taskContext.remove();
			else
				_taskContext.set(previous);
		}
	}

	private Phase requireCurrent(PhaseToken token) {
		Phase phase = requireOwned(token);
		if(_current != phase || phase.terminal) {
			reject(phase, FailureKind.STALE_TOKEN, null);
			throw new IllegalStateException("Federated task phase token is stale");
		}
		return phase;
	}

	private Phase requireOwned(PhaseToken token) {
		if(token == null || token.owner != this)
			throw new IllegalArgumentException("Federated task phase token belongs to another accountant");
		return token.phase;
	}

	private void reject(Phase phase, FailureKind kind, Throwable cause) {
		phase.rejected++;
		phase.failures.add(new Failure(kind, cause));
	}

	private static Callable<Void> toCallable(Runnable task) {
		if(task == null)
			throw new IllegalArgumentException("Task must be non-null");
		return () -> {
			task.run();
			return null;
		};
	}

	public enum FailureKind {
		TASK_EXCEPTION,
		EXECUTOR_REJECTED,
		FUTURE_CANCELLED,
		ROOT_ADMISSION_CLOSED,
		UNBOUND_SUBMISSION,
		WRONG_PHASE_CONTEXT,
		STALE_TOKEN,
		TIMEOUT
	}

	/** Opaque capability for exactly one accountant epoch. */
	public static final class PhaseToken {
		private final FederatedPhaseTasks owner;
		private final Phase phase;

		private PhaseToken(FederatedPhaseTasks owner, Phase phase) {
			this.owner = owner;
			this.phase = phase;
		}
	}

	public static final class Failure {
		private final FailureKind _kind;
		private final String _exceptionClass;

		private Failure(FailureKind kind, Throwable cause) {
			_kind = kind;
			_exceptionClass = cause == null ? null : cause.getClass().getName();
		}

		public FailureKind getKind() {
			return _kind;
		}

		public String getExceptionClass() {
			return _exceptionClass;
		}
	}

	public final class Result {
		private final Phase _phase;

		private Result(Phase phase) {
			_phase = phase;
		}

		public String getCondition() { return _phase.condition; }
		public long getEpoch() { return _phase.epoch; }
		public long getRegistered() { synchronized(_gate) { return _phase.registered; } }
		public long getCompleted() { synchronized(_gate) { return _phase.completed; } }
		public long getOutstanding() { synchronized(_gate) { return _phase.outstanding; } }
		public long getRejected() { synchronized(_gate) { return _phase.rejected; } }
		public boolean isRootAdmissionClosed() { synchronized(_gate) { return _phase.rootsClosed; } }
		public boolean isTerminal() { synchronized(_gate) { return _phase.terminal; } }
		public boolean isTimedOut() { synchronized(_gate) { return _phase.hadTimeout; } }
		public List<Failure> getFailures() {
			synchronized(_gate) {
				return Collections.unmodifiableList(new ArrayList<>(_phase.failures));
			}
		}
		public boolean isClean() {
			synchronized(_gate) {
				return _phase.terminal && _phase.rootsClosed && _phase.outstanding == 0 && _phase.failures.isEmpty();
			}
		}
	}

	private static final class TaskContext {
		private final Phase phase;

		private TaskContext(Phase phase) {
			this.phase = phase;
		}
	}

	private static final class Phase {
		private final long epoch;
		private final String condition;
		private final List<Failure> failures = new ArrayList<>();
		private boolean rootsClosed;
		private boolean terminal;
		private boolean hadTimeout;
		private long registered;
		private long completed;
		private long outstanding;
		private long rejected;

		private Phase(long epoch, String condition) {
			this.epoch = epoch;
			this.condition = condition;
		}
	}

	private final class Registration {
		private static final int REGISTERED = 0;
		private static final int RUNNING = 1;
		private static final int TERMINATED = 2;

		private final Phase phase;
		private final AtomicInteger state = new AtomicInteger(REGISTERED);
		private final AtomicBoolean cancellationRecorded = new AtomicBoolean();

		private Registration(Phase phase) {
			this.phase = phase;
		}

		private boolean start() {
			return state.compareAndSet(REGISTERED, RUNNING);
		}

		private void completeBeforeStart(FailureKind kind, Throwable cause) {
			if(state.compareAndSet(REGISTERED, TERMINATED))
				complete(kind, cause);
		}

		private void cancel() {
			if(cancellationRecorded.compareAndSet(false, true)) {
				synchronized(_gate) {
					phase.failures.add(new Failure(FailureKind.FUTURE_CANCELLED, null));
				}
			}
			if(state.compareAndSet(REGISTERED, TERMINATED))
				complete(null, null);
		}

		private void completeRunning(Throwable cause) {
			if(state.compareAndSet(RUNNING, TERMINATED))
				complete(cause == null ? null : FailureKind.TASK_EXCEPTION, cause);
		}

		private void complete(FailureKind kind, Throwable cause) {
			synchronized(_gate) {
				phase.completed++;
				phase.outstanding--;
				if(kind != null)
					phase.failures.add(new Failure(kind, cause));
				_gate.notifyAll();
			}
		}
	}

	private final class TrackedFutureTask<T> extends FutureTask<T> {
		private final Registration _registration;

		private TrackedFutureTask(Registration registration, Callable<T> task) {
			super(task);
			_registration = registration;
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			boolean cancelled = super.cancel(mayInterruptIfRunning);
			if(cancelled)
				_registration.cancel();
			return cancelled;
		}
	}
}
