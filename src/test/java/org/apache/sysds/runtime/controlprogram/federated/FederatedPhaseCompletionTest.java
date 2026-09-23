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

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseCompletion.FailureKind;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseCompletion.Result;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseCompletion.Scope;
import org.junit.Test;

import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

public class FederatedPhaseCompletionTest {
	private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

	@Test
	public void disabledTrackingLeavesLaterPhaseEmpty() throws Exception {
		Promise<FederatedResponse> promise = promise();
		FederatedPhaseCompletion.trackDispatch(endpoint(17000), 7, promise);
		promise.setSuccess(success());

		try(Scope scope = FederatedPhaseCompletion.begin("disabled-control")) {
			scope.sealAfterProducersComplete(completedProducer());
			Result result = scope.await(TEST_TIMEOUT);
			assertEquals(0, result.getRegistered());
			assertFalse(result.isClean());
			assertTrue(scope.finish().isClean());
		}
	}

	@Test
	public void overlappingScopesAreRejected() {
		try(Scope ignored = FederatedPhaseCompletion.begin("first")) {
			assertThrows(IllegalStateException.class, () -> FederatedPhaseCompletion.begin("second"));
			ignored.sealAfterProducersComplete(completedProducer());
		}
	}

	@Test
	public void exceptionalProducerAttestsTerminationAndRequiresAbort() throws Exception {
		CompletableFuture<Void> failed = new CompletableFuture<>();
		failed.completeExceptionally(new IllegalStateException("producer failed"));
		try(Scope scope = FederatedPhaseCompletion.begin("failed-producer-evidence")) {
			scope.sealAfterProducersComplete(failed);
			Result result = scope.await(TEST_TIMEOUT);
			assertTrue(result.isProducerQuiescenceAttested());
			assertEquals(1, result.getProducerEvidenceCount());
			assertEquals(FailureKind.PRODUCER_EXCEPTIONAL_COMPLETION, result.getFailures().get(0).getKind());
			assertEquals(IllegalStateException.class.getName(), result.getFailures().get(0).getExceptionClass());
			assertThrows(IllegalStateException.class, scope::finish);
			assertTrue(scope.abort().isAborted());
		}
	}

	@Test
	public void cancelledRunningProducerRequiresSeparateTerminationEvidence() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Void> terminated = new CompletableFuture<>();
		Future<?> cancelled = executor.submit(() -> {
			started.countDown();
			try {
				while(release.getCount() > 0) {
					try {
						release.await();
					}
					catch(InterruptedException ignored) {
						// Deliberately ignore cancellation interruption to model an adversarial producer.
					}
				}
			}
			finally {
				terminated.complete(null);
			}
		});
		assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
		assertTrue(cancelled.cancel(true));
		assertTrue(cancelled.isDone());
		assertFalse("cancelled Future must not imply underlying task termination", terminated.isDone());
		try(Scope scope = FederatedPhaseCompletion.begin("cancelled-producer-evidence")) {
			assertThrows(IllegalStateException.class, () -> scope.sealAfterProducersComplete(cancelled));
			assertThrows(IllegalStateException.class,
				() -> scope.sealAfterCancelledProducerTerminates(cancelled, terminated));
			assertThrows(IllegalStateException.class,
				() -> FederatedPhaseCompletion.begin("cancelled-producer-must-remain-active"));

			release.countDown();
			terminated.get(5, java.util.concurrent.TimeUnit.SECONDS);
			scope.sealAfterCancelledProducerTerminates(cancelled, terminated);
			Result result = scope.await(TEST_TIMEOUT);
			assertEquals(FailureKind.PRODUCER_CANCELLED, result.getFailures().get(0).getKind());
			assertEquals(java.util.concurrent.CancellationException.class.getName(),
				result.getFailures().get(0).getExceptionClass());
			assertThrows(IllegalStateException.class, scope::finish);
			scope.abort();
		}
		finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void discardedFutureDelayedUnsuccessfulResponseIsDrained() throws Exception {
		try(Scope scope = FederatedPhaseCompletion.begin("ignored-failure")) {
			Promise<FederatedResponse> ignored = promise();
			FederatedPhaseCompletion.trackDispatch(endpoint(17001), 41, ignored);

			Thread completion = new Thread(() -> {
				try {
					Thread.sleep(100);
				}
				catch(InterruptedException ex) {
					Thread.currentThread().interrupt();
					ignored.setFailure(ex);
					return;
				}
				ignored.setSuccess(new FederatedResponse(FederatedResponse.ResponseType.ERROR,
					"response payload must not enter tracker metadata"));
			});
			completion.start();

			scope.sealAfterProducersComplete(completedProducer());
			Result result = scope.await(TEST_TIMEOUT);
			completion.join();
			assertFalse(result.isTimedOut());
			assertEquals(1, result.getRegistered());
			assertEquals(1, result.getCompleted());
			assertEquals(1, result.getUnsuccessful());
			assertEquals(0, result.getExceptional());
			assertEquals(0, result.getOutstanding());
			assertEquals(FailureKind.UNSUCCESSFUL_RESPONSE, result.getFailures().get(0).getKind());
			assertEquals("localhost:17001", result.getFailures().get(0).getEndpoint());
			assertEquals(null, result.getFailures().get(0).getExceptionClass());
			scope.abort();
		}
	}

	@Test
	public void exceptionalCompletionRecordsClassWithoutMessage() throws Exception {
		try(Scope scope = FederatedPhaseCompletion.begin("exception")) {
			Promise<FederatedResponse> promise = promise();
			FederatedPhaseCompletion.trackDispatch(endpoint(17002), 42, promise);
			promise.setFailure(new IllegalArgumentException("sensitive exception message"));

			scope.sealAfterProducersComplete(completedProducer());
			Result result = scope.await(TEST_TIMEOUT);
			assertEquals(1, result.getExceptional());
			assertEquals(IllegalArgumentException.class.getName(),
				result.getFailures().get(0).getExceptionClass());
			assertEquals(FailureKind.EXCEPTIONAL_COMPLETION, result.getFailures().get(0).getKind());
			scope.abort();
		}
	}

	@Test
	public void executorThreadDispatchesAcrossEndpointsAndTidsAreCaptured() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try(Scope scope = FederatedPhaseCompletion.begin("executor-threads")) {
			List<Future<?>> submissions = new ArrayList<>();
			for(int i = 0; i < 24; i++) {
				final int request = i;
				submissions.add(executor.submit(() -> {
					Promise<FederatedResponse> promise = promise();
					FederatedPhaseCompletion.trackDispatch(endpoint(17100 + request % 3), 100 + request, promise);
					promise.setSuccess(success());
				}));
			}
			for(Future<?> submission : submissions)
				submission.get();

			scope.sealAfterProducersComplete(completedProducer());
			Result result = scope.await(TEST_TIMEOUT);
			assertEquals(24, result.getRegistered());
			assertEquals(24, result.getSuccessful());
			assertEquals(3, result.getEndpoints().size());
			assertEquals(24, result.getTids().size());
			assertFalse(result.isClean());
			assertTrue(scope.finish().isClean());
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void dispatchAfterSealIsObservedAndReportedLate() throws Exception {
		try(Scope scope = FederatedPhaseCompletion.begin("late")) {
			scope.seal();
			Promise<FederatedResponse> promise = promise();
			assertThrows(FederatedPhaseCompletion.LateDispatchException.class,
				() -> FederatedPhaseCompletion.trackDispatch(endpoint(17200), 301, promise));
			scope.attestProducerQuiescence(completedProducer());

			Result result = scope.await(TEST_TIMEOUT);
			assertEquals(0, result.getRegistered());
			assertEquals(1, result.getLateDispatches());
			assertEquals(1, result.getRejectedDispatches());
			assertEquals(FailureKind.LATE_DISPATCH_REJECTED, result.getFailures().get(0).getKind());
			assertFalse(result.isClean());
			scope.abort();
		}
	}

	@Test
	public void unconfirmedDrainCannotReturnCleanBeforeLaterProducerAttempt() throws Exception {
		try(Scope scope = FederatedPhaseCompletion.begin("post-await-late")) {
			scope.seal();
			Result preliminary = scope.await(TEST_TIMEOUT);
			assertFalse(preliminary.isClean());
			assertFalse(preliminary.isProducerQuiescenceAttested());

			Promise<FederatedResponse> promise = promise();
			assertThrows(FederatedPhaseCompletion.LateDispatchException.class,
				() -> FederatedPhaseCompletion.trackDispatch(endpoint(17202), 303, promise));
			assertFalse(promise.isDone());
			scope.attestProducerQuiescence(completedProducer());
			Result terminal = scope.await(TEST_TIMEOUT);
			assertEquals(1, terminal.getRejectedDispatches());
			assertEquals(0, terminal.getOutstanding());
			assertFalse(terminal.isClean());
			scope.abort();
		}
	}

	@Test
	public void dispatchArrivingDuringDrainIsRejectedAndIncluded() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try(Scope scope = FederatedPhaseCompletion.begin("during-await")) {
			Promise<FederatedResponse> admitted = promise();
			FederatedPhaseCompletion.trackDispatch(endpoint(17203), 304, admitted);
			scope.seal();
			CountDownLatch awaitInvoked = new CountDownLatch(1);
			Future<Result> drain = executor.submit(() -> {
				awaitInvoked.countDown();
				return scope.await(TEST_TIMEOUT);
			});
			assertTrue(awaitInvoked.await(5, java.util.concurrent.TimeUnit.SECONDS));

			Promise<FederatedResponse> late = promise();
			assertThrows(FederatedPhaseCompletion.LateDispatchException.class,
				() -> FederatedPhaseCompletion.trackDispatch(endpoint(17204), 305, late));
			admitted.setSuccess(success());
			Result result = drain.get();
			assertEquals(1, result.getRegistered());
			assertEquals(1, result.getRejectedDispatches());
			assertEquals(0, result.getOutstanding());
			scope.attestProducerQuiescence(completedProducer());
			scope.abort();
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void drainUsesFiniteDeadline() throws Exception {
		try(Scope scope = FederatedPhaseCompletion.begin("deadline")) {
			Promise<FederatedResponse> neverCompleted = promise();
			FederatedPhaseCompletion.trackDispatch(endpoint(17201), 302, neverCompleted);

			long start = System.nanoTime();
			scope.sealAfterProducersComplete(completedProducer());
			Result result = scope.await(Duration.ofMillis(25));
			long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
			assertTrue(result.isTimedOut());
			assertEquals(1, result.getOutstanding());
			assertTrue("drain exceeded its bounded test deadline", elapsedMillis < 2_000);
			assertThrows(IllegalStateException.class, () -> FederatedPhaseCompletion.begin("must-not-cross"));
			assertThrows(IllegalStateException.class, scope::close);
			neverCompleted.setSuccess(success());
			assertFalse(scope.await(TEST_TIMEOUT).isTimedOut());
			assertThrows(IllegalStateException.class, scope::close);
			scope.abort();
			try(Scope next = FederatedPhaseCompletion.begin("after-explicit-abort")) {
				next.sealAfterProducersComplete(completedProducer());
			}
		}
	}

	@Test
	public void sealRegistrationRaceNeverLosesTheDispatch() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			for(int i = 0; i < 100; i++) {
				try(Scope scope = FederatedPhaseCompletion.begin("seal-race-" + i)) {
					CyclicBarrier start = new CyclicBarrier(2);
					Promise<FederatedResponse> promise = promise();
					Future<?> registration = executor.submit(() -> {
						await(start);
						try {
							FederatedPhaseCompletion.trackDispatch(endpoint(17300), 400, promise);
						}
						catch(FederatedPhaseCompletion.LateDispatchException ignored) {
							// The seal won the admission race; no physical dispatch is admitted.
						}
					});
					Future<?> sealing = executor.submit(() -> {
						await(start);
						scope.seal();
					});
					registration.get();
					sealing.get();
					if(scope.snapshot().getRegistered() == 1)
						promise.setSuccess(success());
					scope.attestProducerQuiescence(completedProducer());

					Result result = scope.await(TEST_TIMEOUT);
					assertEquals(1, result.getRegistered() + result.getRejectedDispatches());
					assertEquals(result.getRegistered(), result.getCompleted());
					assertEquals(0, result.getOutstanding());
					assertTrue(result.getLateDispatches() == 0 || result.getLateDispatches() == 1);
					if(result.getFailures().isEmpty())
						assertTrue(scope.finish().isClean());
					else
						scope.abort();
				}
			}
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void terminalTransitionAndLateAdmissionUseOneAtomicGate() throws Exception {
		Scope oldScope = FederatedPhaseCompletion.begin("atomic-transition-old");
		oldScope.sealAfterProducersComplete(completedProducer());
		oldScope.await(TEST_TIMEOUT);
		Result oldTerminal = oldScope.finish();
		assertTrue(oldTerminal.isClean());

		Field phaseField = Result.class.getDeclaredField("_livePhase");
		phaseField.setAccessible(true);
		Object oldPhase = phaseField.get(oldTerminal);
		AtomicReference<Scope> nextScope = new AtomicReference<>();
		AtomicReference<Throwable> transitionFailure = new AtomicReference<>();
		AtomicReference<Throwable> admissionFailure = new AtomicReference<>();
		Promise<FederatedResponse> latePromise = promise();
		Thread transition = new Thread(() -> {
			try {
				nextScope.set(FederatedPhaseCompletion.begin("atomic-transition-new"));
			}
			catch(Throwable t) {
				transitionFailure.set(t);
			}
		});
		Thread lateAdmission = new Thread(() -> {
			try {
				FederatedPhaseCompletion.trackDispatch(endpoint(17400), 501, latePromise);
			}
			catch(Throwable t) {
				admissionFailure.set(t);
			}
		});

		synchronized(oldPhase) {
			transition.start();
			awaitThreadState(transition, Thread.State.BLOCKED);
			lateAdmission.start();
			awaitThreadState(lateAdmission, Thread.State.BLOCKED);
			assertFalse("late admission passed while transition validation was paused", latePromise.isDone());
		}
		transition.join(5_000);
		lateAdmission.join(5_000);
		assertFalse(transition.isAlive());
		assertFalse(lateAdmission.isAlive());
		assertEquals(null, transitionFailure.get());
		assertEquals(null, admissionFailure.get());

		Scope next = nextScope.get();
		assertTrue(next != null);
		assertTrue(oldTerminal.isClean());
		assertEquals(0, oldTerminal.getLateDispatches());
		assertEquals(1, next.snapshot().getRegistered());
		latePromise.setSuccess(success());
		next.sealAfterProducersComplete(completedProducer());
		assertFalse(next.await(TEST_TIMEOUT).isClean());
		assertTrue(next.finish().isClean());
	}

	private static Promise<FederatedResponse> promise() {
		return ImmediateEventExecutor.INSTANCE.newPromise();
	}

	private static InetSocketAddress endpoint(int port) {
		return InetSocketAddress.createUnresolved("localhost", port);
	}

	private static FederatedResponse success() {
		return new FederatedResponse(FederatedResponse.ResponseType.SUCCESS_EMPTY);
	}

	private static Future<?> completedProducer() {
		return CompletableFuture.completedFuture(null);
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await();
		}
		catch(Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	private static void awaitThreadState(Thread thread, Thread.State expected) throws InterruptedException {
		long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
		while(thread.getState() != expected && System.nanoTime() < deadline)
			Thread.sleep(1);
		assertEquals(expected, thread.getState());
	}
}
