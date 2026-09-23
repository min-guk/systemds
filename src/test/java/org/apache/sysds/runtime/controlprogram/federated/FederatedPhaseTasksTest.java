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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseTasks.FailureKind;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseTasks.PhaseToken;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseTasks.Result;
import org.junit.Test;

public class FederatedPhaseTasksTest {
	private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

	@Test
	public void admittedTaskMayRegisterNestedDescendantsAfterRootsClose() throws Exception {
		FederatedPhaseTasks tasks = new FederatedPhaseTasks();
		PhaseToken token = tasks.begin("nested-descendants");
		ExecutorService executor = Executors.newFixedThreadPool(3);
		CountDownLatch rootStarted = new CountDownLatch(1);
		CountDownLatch rootsClosed = new CountDownLatch(1);
		CountDownLatch grandchildRan = new CountDownLatch(1);
		try {
			Future<?> root = tasks.submitRoot(token, executor, () -> {
				rootStarted.countDown();
				await(rootsClosed);
				tasks.submitChild(token, executor,
					() -> tasks.submitChild(token, executor, grandchildRan::countDown));
			});
			assertTrue(rootStarted.await(5, TimeUnit.SECONDS));
			tasks.closeRootAdmission(token);
			rootsClosed.countDown();

			Result result = tasks.await(token, TEST_TIMEOUT);
			root.get(5, TimeUnit.SECONDS);
			assertTrue(grandchildRan.await(5, TimeUnit.SECONDS));
			assertTrue(result.isClean());
			assertEquals(3, result.getRegistered());
			assertEquals(3, result.getCompleted());
			assertEquals(0, result.getOutstanding());
		}
		finally {
			rootsClosed.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void taskFailureRecordsOnlyCategoryAndExceptionClass() throws Exception {
		FederatedPhaseTasks tasks = new FederatedPhaseTasks();
		PhaseToken token = tasks.begin("task-failure");
		Executor direct = Runnable::run;
		Future<?> failed = tasks.submitRoot(token, direct,
			() -> { throw new IllegalArgumentException("payload must not be retained"); });
		tasks.closeRootAdmission(token);

		assertThrows(java.util.concurrent.ExecutionException.class, failed::get);
		Result result = tasks.await(token, TEST_TIMEOUT);
		assertFalse(result.isClean());
		assertEquals(1, result.getFailures().size());
		assertEquals(FailureKind.TASK_EXCEPTION, result.getFailures().get(0).getKind());
		assertEquals(IllegalArgumentException.class.getName(), result.getFailures().get(0).getExceptionClass());
	}

	@Test
	public void rejectedExecutorSubmissionCannotLeakOutstandingTask() throws Exception {
		FederatedPhaseTasks tasks = new FederatedPhaseTasks();
		PhaseToken token = tasks.begin("executor-rejection");
		Executor rejecting = command -> { throw new RejectedExecutionException("sensitive executor detail"); };
		assertThrows(RejectedExecutionException.class,
			() -> tasks.submitRoot(token, rejecting, () -> { }));
		tasks.closeRootAdmission(token);

		Result result = tasks.await(token, TEST_TIMEOUT);
		assertEquals(1, result.getRegistered());
		assertEquals(1, result.getCompleted());
		assertEquals(0, result.getOutstanding());
		assertEquals(FailureKind.EXECUTOR_REJECTED, result.getFailures().get(0).getKind());
		assertEquals(RejectedExecutionException.class.getName(), result.getFailures().get(0).getExceptionClass());
	}

	@Test
	public void cancellingRunningFutureDoesNotCompleteBodyThatIgnoresInterruption() throws Exception {
		FederatedPhaseTasks tasks = new FederatedPhaseTasks();
		PhaseToken token = tasks.begin("running-cancellation");
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try {
			Future<?> future = tasks.submitRoot(token, executor, () -> {
				started.countDown();
				while(release.getCount() > 0) {
					try {
						release.await();
					}
					catch(InterruptedException ignored) {
						// Deliberately ignore Future cancellation while the task body is still live.
					}
				}
			});
			assertTrue(started.await(5, TimeUnit.SECONDS));
			assertTrue(future.cancel(true));
			assertFalse(future.cancel(true));
			tasks.closeRootAdmission(token);
			Result running = tasks.snapshot(token);
			assertEquals(1, running.getOutstanding());
			assertEquals(0, running.getCompleted());
			assertEquals(FailureKind.FUTURE_CANCELLED, running.getFailures().get(0).getKind());

			release.countDown();
			Result terminated = tasks.await(token, TEST_TIMEOUT);
			assertTrue(terminated.isTerminal());
			assertEquals(0, terminated.getOutstanding());
			assertFalse(terminated.isTimedOut());
			assertFalse("successful Future cancellation is a phase failure", terminated.isClean());
			assertEquals(1, terminated.getFailures().size());
		}
		finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void cancelledQueuedTaskTerminatesWithoutRunningItsBody() throws Exception {
		FederatedPhaseTasks tasks = new FederatedPhaseTasks();
		PhaseToken token = tasks.begin("queued-cancellation");
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch blockerStarted = new CountDownLatch(1);
		CountDownLatch releaseBlocker = new CountDownLatch(1);
		CountDownLatch cancelledBodyRan = new CountDownLatch(1);
		try {
			Future<?> blocker = tasks.submitRoot(token, executor, () -> {
				blockerStarted.countDown();
				await(releaseBlocker);
			});
			assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
			Future<?> queued = tasks.submitRoot(token, executor, cancelledBodyRan::countDown);
			assertTrue(queued.cancel(false));
			tasks.closeRootAdmission(token);
			releaseBlocker.countDown();
			blocker.get(5, TimeUnit.SECONDS);

			Result result = tasks.await(token, TEST_TIMEOUT);
			assertEquals(2, result.getRegistered());
			assertEquals(2, result.getCompleted());
			assertEquals(1, cancelledBodyRan.getCount());
			assertEquals(FailureKind.FUTURE_CANCELLED, result.getFailures().get(0).getKind());
		}
		finally {
			releaseBlocker.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void childSubmissionRequiresExplicitNonInheritedTaskContext() throws Exception {
		FederatedPhaseTasks tasks = new FederatedPhaseTasks();
		PhaseToken token = tasks.begin("non-inherited-context");
		ExecutorService executor = Executors.newSingleThreadExecutor();
		AtomicReference<Throwable> rejection = new AtomicReference<>();
		try {
			Future<?> root = tasks.submitRoot(token, executor, () -> {
				Thread rawChild = new Thread(() -> {
					try {
						tasks.submitChild(token, Runnable::run, () -> { });
					}
					catch(Throwable ex) {
						rejection.set(ex);
					}
				});
				rawChild.start();
				try {
					rawChild.join();
				}
				catch(InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new RuntimeException(ex);
				}
			});
			root.get(5, TimeUnit.SECONDS);
			tasks.closeRootAdmission(token);

			Result result = tasks.await(token, TEST_TIMEOUT);
			assertTrue(rejection.get() instanceof IllegalStateException);
			assertEquals(FailureKind.UNBOUND_SUBMISSION, result.getFailures().get(0).getKind());
			assertFalse(result.isClean());
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void oldTokenIsRejectedAfterNextPhaseWithoutAssignmentToIt() throws Exception {
		FederatedPhaseTasks tasks = new FederatedPhaseTasks();
		PhaseToken old = tasks.begin("old");
		tasks.closeRootAdmission(old);
		Result oldResult = tasks.await(old, TEST_TIMEOUT);
		assertTrue(oldResult.isClean());

		PhaseToken next = tasks.begin("next");
		assertThrows(IllegalStateException.class,
			() -> tasks.submitRoot(old, Runnable::run, () -> { }));
		tasks.closeRootAdmission(next);
		Result nextResult = tasks.await(next, TEST_TIMEOUT);

		assertEquals(0, nextResult.getRegistered());
		assertTrue(nextResult.isClean());
		assertEquals(FailureKind.STALE_TOKEN, oldResult.getFailures().get(0).getKind());
		assertFalse("the live old-phase verdict is invalidated by stale work", oldResult.isClean());
	}

	@Test
	public void concurrentRootCloseAndSubmissionHasExactlyOneAccountedOutcome() throws Exception {
		ExecutorService race = Executors.newFixedThreadPool(2);
		try {
			for(int i = 0; i < 100; i++) {
				FederatedPhaseTasks tasks = new FederatedPhaseTasks();
				PhaseToken token = tasks.begin("close-race-" + i);
				CyclicBarrier start = new CyclicBarrier(2);
				AtomicReference<Throwable> submissionFailure = new AtomicReference<>();
				Future<?> submit = race.submit(() -> {
					await(start);
					try {
						tasks.submitRoot(token, Runnable::run, () -> { });
					}
					catch(Throwable ex) {
						submissionFailure.set(ex);
					}
				});
				Future<?> close = race.submit(() -> {
					await(start);
					tasks.closeRootAdmission(token);
				});
				submit.get(5, TimeUnit.SECONDS);
				close.get(5, TimeUnit.SECONDS);

				Result result = tasks.await(token, TEST_TIMEOUT);
				assertEquals(1, result.getRegistered() + result.getRejected());
				assertEquals(result.getRegistered(), result.getCompleted());
				assertEquals(0, result.getOutstanding());
				if(submissionFailure.get() == null)
					assertTrue(result.isClean());
				else {
					assertTrue(submissionFailure.get() instanceof IllegalStateException);
					assertEquals(FailureKind.ROOT_ADMISSION_CLOSED, result.getFailures().get(0).getKind());
				}
			}
		}
		finally {
			race.shutdownNow();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(5, TimeUnit.SECONDS));
		}
		catch(InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(ex);
		}
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await(5, TimeUnit.SECONDS);
		}
		catch(Exception ex) {
			throw new RuntimeException(ex);
		}
	}
}
